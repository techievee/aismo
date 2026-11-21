package aismo.sms.telephony

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch
import aismo.sms.R
import aismo.sms.container
import aismo.sms.data.IngestedMessage
import aismo.sms.data.MessageRepository
import aismo.sms.data.settings.SettingsRepository
import aismo.sms.data.sms.ContactDirectory
import aismo.sms.data.sms.SmsProvider
import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.FinanceService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Which conversation is on screen, so its notifications can be suppressed. */
object ActiveConversation {
    @Volatile var key: String? = null
}

/** Sends texts through the system radio and records them in the provider as the default SMS app. */
class SmsSender(
    private val context: Context,
    private val provider: SmsProvider,
    private val repository: MessageRepository,
    private val settings: SettingsRepository,
) {
    /**
     * Stores the message as outgoing, then hands it to the radio. Returns the provider id or -1. [key] files
     * the message in a given conversation instead of the one its address derives: a reply stays in the thread
     * it was sent from, which for a text reply to an MMS thread is not the thread its number alone would give.
     */
    suspend fun send(address: String, body: String, subId: Int, key: String? = null): Long {
        val text = body.trim()
        if (address.isBlank() || text.isEmpty()) return -1
        val id = provider.insertOutgoing(address, text, subId)
        if (id < 0) return -1
        repository.refresh(listOf(id), key?.let { mapOf(id to it) } ?: emptyMap())
        dispatch(id, address, text, subId)
        if (settings.current().sendSound) playSendSound()
        return id
    }

    /** Re-sends a failed or queued message in place. */
    suspend fun resend(messageId: Long) {
        val raw = provider.readById(messageId) ?: return
        provider.updateSendState(messageId, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        repository.refresh(listOf(messageId))
        dispatch(messageId, raw.address, raw.body, raw.subId)
    }

    private suspend fun dispatch(id: Long, address: String, body: String, subId: Int) {
        try {
            val sms = smsManager(subId)
            val parts = sms.divideMessage(body)
            PartTracker.expect(id, parts.size)
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                sent += status(Actions.SENT, id, i, parts.size)
                delivered += status(Actions.DELIVERED, id, i, parts.size)
            }
            if (parts.size == 1) sms.sendTextMessage(address, null, body, sent[0], delivered[0])
            else sms.sendMultipartTextMessage(address, null, parts, sent, delivered)
        } catch (e: Exception) {
            provider.updateSendState(id, Telephony.Sms.MESSAGE_TYPE_FAILED)
            repository.refresh(listOf(id))
        }
    }

    private fun status(action: String, id: Long, part: Int, parts: Int): PendingIntent {
        val i = Intent(context, SmsStatusReceiver::class.java).setAction(action)
            .putExtra(Actions.EXTRA_MESSAGE_ID, id).putExtra(Actions.EXTRA_PART, part).putExtra(Actions.EXTRA_PARTS, parts)
        val code = (id.hashCode() * 31 + part) * 2 + if (action == Actions.SENT) 0 else 1
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun smsManager(subId: Int): SmsManager = context.smsManagerFor(subId)

    private fun playSendSound() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        }
    }
}

/** Counts per-message part results; a multipart message is sent or delivered only when every part is. */
object PartTracker {
    private val sentOk = ConcurrentHashMap<Long, AtomicInteger>()
    private val deliveredOk = ConcurrentHashMap<Long, AtomicInteger>()

    fun expect(id: Long, parts: Int) {
        sentOk[id] = AtomicInteger(0); deliveredOk[id] = AtomicInteger(0)
    }

    fun sentPart(id: Long, parts: Int): Boolean = complete(sentOk, id, parts)
    fun deliveredPart(id: Long, parts: Int): Boolean = complete(deliveredOk, id, parts)

    private fun complete(map: ConcurrentHashMap<Long, AtomicInteger>, id: Long, parts: Int): Boolean {
        val n = map.getOrPut(id) { AtomicInteger(0) }.incrementAndGet()
        if (n >= parts) { map.remove(id); return true }
        return false
    }

    fun fail(id: Long) {
        sentOk.remove(id); deliveredOk.remove(id)
    }
}

/** Receives sent/delivered results from the radio and updates the stored message. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Actions.EXTRA_MESSAGE_ID, -1)
        if (id < 0) return
        val parts = intent.getIntExtra(Actions.EXTRA_PARTS, 1)
        val result = resultCode
        val pending = goAsync()
        val c = context.container
        c.scope.launch {
            try {
                when (intent.action) {
                    Actions.SENT -> if (result == Activity.RESULT_OK) {
                        if (PartTracker.sentPart(id, parts)) c.provider.updateSendState(id, Telephony.Sms.MESSAGE_TYPE_SENT)
                    } else {
                        PartTracker.fail(id)
                        c.provider.updateSendState(id, Telephony.Sms.MESSAGE_TYPE_FAILED)
                    }
                    Actions.DELIVERED -> if (PartTracker.deliveredPart(id, parts)) {
                        c.provider.updateSendState(id, Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_COMPLETE)
                        if (c.settings.current().deliveryNotifications) {
                            Toast.makeText(context, context.getString(R.string.delivered), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                c.repository.refresh(listOf(id))
            } catch (e: Exception) {
                Log.w("TsoSms", "status update", e) // a bad result must not kill the process
            } finally {
                pending.finish()
            }
        }
    }
}

/** Turns a raw provider row into stored facts and notifications; shared by the receiver and tests. */
class IncomingProcessor(
    private val provider: SmsProvider,
    private val repository: MessageRepository,
    private val contacts: ContactDirectory,
    private val settings: SettingsRepository,
    private val notifications: NotificationCenter,
    private val finance: FinanceService,
    private val onNewMessages: () -> Unit = {},
    private val onTranslate: (IngestedMessage) -> Unit = {},
) {
    suspend fun handle(address: String, body: String, date: Long, dateSent: Long, subId: Int) {
        val id = provider.insertIncoming(address, body, date, dateSent, subId)
        if (id < 0) return
        notifyFor(repository.refresh(listOf(id)))
    }

    /** Budget checks and notifications for freshly stored incoming messages (SMS or MMS). */
    suspend fun notifyFor(ingested: List<aismo.sms.data.IngestedMessage>) {
        if (ingested.isNotEmpty()) onNewMessages()
        val s = settings.current()
        for (m in ingested) {
            // Whether or not the message may notify, its translation is made for the thread.
            if (s.translateEnabled && m.message.body.isNotBlank()) onTranslate(m)
            m.parsed.transaction?.let { t ->
                finance.checkBudgets(
                    aismo.sms.data.db.TransactionEntity(
                        m.message.id, t.kind.name, t.amountMinor, t.currency, t.merchant, t.instrument, t.last4, t.balanceMinor, m.message.date, null,
                    ),
                )
            }
            if (m.isBlocked || ActiveConversation.key == m.message.conversationKey) continue
            // The category the conversation is filed under now, which may be one the user chose, decides whether it may notify.
            val category = repository.categoryOf(m.message.conversationKey) ?: m.message.category
            if (category in s.mutedCategories) continue
            val title = conversationTitle(m.message.address)
            if (m.isMmsPlaceholder) { notifications.showMmsPending(m, title); continue }
            val spam = m.parsed.spamScore >= SPAM_NOTIFY
            when {
                spam -> if (s.protectionAlerts) notifications.showProtection(m, title)
                m.parsed.otp != null && s.otpNotifications -> notifications.showOtp(m, title)
                else -> notifications.showIncoming(m, title, s.transactionPreviews)
            }
        }
    }

    /** Contact name, or the brand/number; a group is shown as its members' names. */
    private fun conversationTitle(address: String): String {
        if (!ConversationKeys.isGroup(address)) {
            return contacts.nameFor(address) ?: NotificationCenter.titleFor(address, null)
        }
        val names = ConversationKeys.participants(address).map { contacts.nameFor(it) ?: it }
        return if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " +${names.size - 3}"
    }

    private companion object { const val SPAM_NOTIFY = 55 }
}

/** The SMS_DELIVER receiver: only the default SMS app gets this broadcast. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val address = messages[0].originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val date = System.currentTimeMillis()
        val dateSent = messages[0].timestampMillis
        val subId = intent.getIntExtra("subscription", -1)
        val pending = goAsync()
        val c = context.container
        c.scope.launch {
            try {
                c.incoming.handle(address, body, date, dateSent, subId)
            } catch (e: Exception) {
                Log.w("TsoSms", "deliver", e) // a bad delivery must not kill the process
            } finally {
                pending.finish()
            }
        }
    }
}

/** Handles "respond via message" (quick decline of a call) from the dialer. */
class HeadlessSmsSendService : android.app.Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.data
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val number = data?.schemeSpecificPart?.substringBefore('?')
        if (number.isNullOrBlank() || text.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val c = container
        c.scope.launch {
            try {
                c.outgoing.send(listOf(number), text, emptyList(), c.simSelector.forConversation(ConversationKeys.of(number)))
            } catch (e: Exception) {
                Log.w("TsoSms", "headless send", e) // a reply from the dialer that cannot be sent must not crash the app
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}

/** Reply, mark-read, copy-code and delete actions from notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val key = intent.getStringExtra(Actions.EXTRA_KEY)
        val messageId = intent.getLongExtra(Actions.EXTRA_MESSAGE_ID, -1)
        val pending = goAsync()
        c.scope.launch {
            try {
                when (intent.action) {
                    Actions.MARK_READ -> if (key != null) {
                        c.repository.markConversationRead(key)
                        c.notifications.cancelConversation(key)
                    }
                    Actions.REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Actions.REPLY_KEY)?.toString()
                        val address = intent.getStringExtra(Actions.EXTRA_ADDRESS)
                        if (!text.isNullOrBlank() && address != null && key != null) {
                            c.outgoing.send(listOf(address), text, emptyList(), c.simSelector.forConversation(key), key) // the SIM this thread already uses; the reply stays in this thread
                            c.repository.markConversationRead(key)
                            c.notifications.cancelConversation(key)
                        }
                    }
                    Actions.COPY_OTP -> {
                        val code = intent.getStringExtra(Actions.EXTRA_OTP).orEmpty()
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                        Toast.makeText(context, context.getString(R.string.code_copied), Toast.LENGTH_SHORT).show()
                        if (messageId >= 0) {
                            c.db.insights().markOtpUsed(messageId)
                            if (c.settings.current().otpDeleteAfterCopy) c.repository.deleteMessages(listOf(messageId))
                            c.notifications.cancelOtp(messageId)
                        }
                    }
                    Actions.DELETE -> if (messageId >= 0) {
                        c.repository.deleteMessages(listOf(messageId))
                        c.notifications.cancelOtp(messageId)
                        if (key != null) c.notifications.cancelConversation(key) // the OTP heads-up carries no key
                    }
                }
            } catch (e: Exception) {
                Log.w("TsoSms", "notification action", e) // a bad action must not kill the process
            } finally {
                pending.finish()
            }
        }
    }
}
