package aismo.sms.data.mms

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Telephony.Mms
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import aismo.sms.data.db.MessageIds
import aismo.sms.data.sms.PendingDownload
import aismo.sms.data.sms.RawAttachment
import aismo.sms.data.sms.RawSms
import aismo.sms.domain.ConversationKeys

/** Coarse kind of an attachment, used for icons and the conversation preview. */
fun attachmentKind(mime: String): String = when {
    mime.startsWith("image/") -> "image"
    mime.startsWith("video/") -> "video"
    mime.startsWith("audio/") -> "audio"
    mime.contains("vcard", ignoreCase = true) -> "contact"
    else -> "file"
}

/** Only plain text is the message body; the SMIL layout is plumbing; everything else (incl. vCard, HTML) is an attachment. */
private fun StoredPart.isBody() = mime.equals("text/plain", ignoreCase = true)
private fun StoredPart.isAttachment() = !isBody() && !mime.equals("application/smil", ignoreCase = true)

/**
 * Turns a provider MMS row into the common message shape. The conversation address is the other
 * participant, or all of them (comma-separated) for a group; [ownKeys] removes the user's own numbers.
 */
fun StoredMms.toRaw(parts: List<StoredPart>, ownKeys: Set<String>): RawSms {
    val everyone = when (box) {
        Mms.MESSAGE_BOX_INBOX -> listOfNotNull(from) + to + cc
        else -> to + cc
    }
    val others = everyone.filter { ConversationKeys.of(it) !in ownKeys }.distinctBy { ConversationKeys.of(it) }
    val address = when {
        others.isEmpty() -> from ?: to.firstOrNull() ?: "unknown"
        others.size == 1 -> others[0]
        else -> others.joinToString(",")
    }
    val text = parts.filter { it.isBody() && it.text != null }.joinToString("\n") { it.text.orEmpty() }.trim()
    val body = listOfNotNull(subject, text.ifEmpty { null }).joinToString("\n")
    val attachments = parts.filter { it.isAttachment() }.map { RawAttachment(it.partId, it.mime, it.name, it.sizeBytes) }
    val download = if (isNotification && contentLocation != null && transactionId != null) {
        PendingDownload(contentLocation, transactionId, sizeBytes, expirySeconds?.let { dateMs + it * 1000 })
    } else null
    return RawSms(
        id = MessageIds.mms(rawId), threadId = threadId, address = address, body = body, date = dateMs, type = box,
        read = read, subId = subId, status = -1, subject = subject, attachments = attachments, download = download,
    )
}

/** The user's own phone numbers (as conversation keys), so group MMS can exclude them from participants. */
class OwnNumbers(private val context: Context) {
    @SuppressLint("MissingPermission", "HardwareIds")
    fun keys(manual: String?): Set<String> {
        val out = mutableSetOf<String>()
        manual?.takeIf { it.isNotBlank() }?.let { out += ConversationKeys.of(it) }
        runCatching {
            context.getSystemService(TelephonyManager::class.java)?.line1Number?.takeIf { it.isNotBlank() }?.let { out += ConversationKeys.of(it) }
        }
        runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return@runCatching
            for (info in sm.activeSubscriptionInfoList.orEmpty()) {
                val n = if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId) else @Suppress("DEPRECATION") info.number
                if (!n.isNullOrBlank()) out += ConversationKeys.of(n)
            }
        }
        out.remove("unknown")
        return out
    }
}
