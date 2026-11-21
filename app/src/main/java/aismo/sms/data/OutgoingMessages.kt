package aismo.sms.data

import aismo.sms.data.db.MessageIds
import aismo.sms.data.mms.MmsEngine
import aismo.sms.data.mms.MmsProvider
import aismo.sms.data.mms.MmsSendResult
import aismo.sms.data.mms.MmsTransport
import aismo.sms.data.mms.OutgoingAttachment
import aismo.sms.data.settings.SettingsRepository
import aismo.sms.domain.mms.ConversionRules
import aismo.sms.domain.mms.LongTextPolicy
import aismo.sms.telephony.SmsSender

/** How a message will leave the phone. */
enum class SendRoute { SMS, MMS_LONG_TEXT, MMS }

sealed interface SendOutcome {
    data class Sent(val messageId: Long, val route: SendRoute) : SendOutcome
    data class Failed(val reason: String) : SendOutcome
}

/** Where the carrier-dependent inputs come from; replaced in tests. */
interface MmsConditions {
    fun segmentThreshold(subId: Int): Int?
    fun charThreshold(subId: Int): Int?
    fun maxTextBytes(subId: Int): Int?
    fun mmsAvailable(subId: Int): Boolean
}

class PlatformMmsConditions(private val transport: MmsTransport) : MmsConditions {
    override fun segmentThreshold(subId: Int) = transport.smsToMmsSegments(subId)
    override fun charThreshold(subId: Int) = transport.smsToMmsChars(subId)
    override fun maxTextBytes(subId: Int) = transport.maxTextBytes(subId)
    override fun mmsAvailable(subId: Int) = transport.mmsEnabledByCarrier(subId) && transport.mobileDataEnabled(subId)
}

/**
 * The single place that decides whether a message goes out as SMS or MMS: attachments and group messages
 * are always MMS, and a long text is converted when the user's setting and the carrier allow it.
 */
class OutgoingMessages(
    private val sender: SmsSender,
    private val engine: MmsEngine,
    private val mmsProvider: MmsProvider,
    private val repository: MessageRepository,
    private val settings: SettingsRepository,
    private val conditions: MmsConditions,
) {
    /** The conversion rules in force for a subscription (cheap to cache; the composer uses this per keystroke). */
    suspend fun rulesFor(subId: Int): ConversionRules {
        val s = settings.current()
        return LongTextPolicy.rules(
            enabled = s.longTextToMms, userSegments = s.longTextSegments,
            carrierSegments = conditions.segmentThreshold(subId), carrierChars = conditions.charThreshold(subId),
            mmsAvailable = conditions.mmsAvailable(subId), maxTextBytes = conditions.maxTextBytes(subId),
        )
    }

    fun routeFor(recipientCount: Int, hasAttachments: Boolean, text: String, rules: ConversionRules): SendRoute = when {
        hasAttachments || recipientCount > 1 -> SendRoute.MMS
        LongTextPolicy.shouldConvert(text, rules) -> SendRoute.MMS_LONG_TEXT
        else -> SendRoute.SMS
    }

    /**
     * Sends a message. [key] files it in a given conversation instead of the one its recipients and type
     * derive: a reply stays in the thread it was sent from (a text reply to an MMS thread, a photo from an
     * SMS one); it is null for a new message, which starts (or continues) the conversation its first message
     * belongs to.
     */
    suspend fun send(recipients: List<String>, text: String, attachments: List<OutgoingAttachment>, subId: Int, key: String? = null): SendOutcome {
        val to = recipients.map { it.trim() }.filter { it.isNotEmpty() }
        if (to.isEmpty()) return SendOutcome.Failed("No recipient")
        val route = routeFor(to.size, attachments.isNotEmpty(), text, rulesFor(subId))
        return when (route) {
            SendRoute.SMS -> {
                val id = sender.send(to.single(), text, subId, key)
                if (id < 0) SendOutcome.Failed("Couldn't send. Check the number and try again.") else SendOutcome.Sent(id, route)
            }
            else -> when (val r = engine.send(to, text, attachments, subId, convertedLongText = route == SendRoute.MMS_LONG_TEXT, key = key)) {
                is MmsSendResult.Queued -> SendOutcome.Sent(r.messageId, route)
                is MmsSendResult.Failed -> SendOutcome.Failed(r.reason)
            }
        }
    }

    /**
     * Gives up on a text-only MMS to one person (typically a converted long text that could not be sent as MMS)
     * and sends the same words as ordinary SMS. The failed MMS is removed only once the SMS is on its way.
     */
    suspend fun resendAsText(messageId: Long, key: String? = null): Boolean {
        if (!MessageIds.isMms(messageId)) return false
        val raw = MessageIds.raw(messageId)
        val row = mmsProvider.readById(raw) ?: return false
        if (row.to.size != 1 || row.cc.isNotEmpty()) return false
        val parts = mmsProvider.parts(raw)
        if (parts.any { it.mime != "text/plain" && it.mime != "application/smil" }) return false
        val text = parts.filter { it.mime == "text/plain" }.joinToString("\n") { it.text.orEmpty() }.trim()
        if (text.isEmpty()) return false
        // The replacement text is stored where the failed MMS was, so the thread keeps showing the exchange.
        if (sender.send(row.to.single(), text, row.subId, key) < 0) return false
        repository.deleteMessages(listOf(messageId))
        return true
    }
}
