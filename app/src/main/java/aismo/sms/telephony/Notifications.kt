package aismo.sms.telephony

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import aismo.sms.MainActivity
import aismo.sms.R
import aismo.sms.domain.SmsCategory
import aismo.sms.data.IngestedMessage
import aismo.sms.data.db.ReminderEntity
import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.parser.SenderBrands
import aismo.sms.domain.parser.TxnKind
import java.text.NumberFormat
import java.util.Currency

object Channels {
    const val MESSAGES = "messages"
    const val OTP = "otp"
    const val FINANCE = "finance"
    const val REMINDERS = "reminders"
    const val PROTECTION = "protection"
    const val BACKUP = "backup"
    const val SPAM_REVIEW = "spam_review"
}

object Actions {
    const val REPLY = "aismo.sms.action.REPLY"
    const val MARK_READ = "aismo.sms.action.MARK_READ"
    const val COPY_OTP = "aismo.sms.action.COPY_OTP"
    const val DELETE = "aismo.sms.action.DELETE"
    const val SENT = "aismo.sms.action.SMS_SENT"
    const val DELIVERED = "aismo.sms.action.SMS_DELIVERED"

    const val EXTRA_KEY = "conversation_key"
    /** Opens the inbox on this category. */
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_MESSAGE_ID = "message_id"
    const val EXTRA_OTP = "otp_code"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"
    const val REPLY_KEY = "reply_text"
}

/** Builds and posts every notification the app shows, and owns the notification channels. */
class NotificationCenter(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        fun channel(id: String, name: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance)
        val sys = context.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannels(
            listOf(
                channel(Channels.MESSAGES, R.string.channel_messages, NotificationManager.IMPORTANCE_HIGH),
                channel(Channels.OTP, R.string.channel_otp, NotificationManager.IMPORTANCE_HIGH),
                channel(Channels.FINANCE, R.string.channel_finance, NotificationManager.IMPORTANCE_DEFAULT),
                channel(Channels.REMINDERS, R.string.channel_reminders, NotificationManager.IMPORTANCE_DEFAULT),
                channel(Channels.PROTECTION, R.string.channel_protection, NotificationManager.IMPORTANCE_HIGH),
                channel(Channels.BACKUP, R.string.channel_backup, NotificationManager.IMPORTANCE_LOW),
                channel(Channels.SPAM_REVIEW, R.string.channel_spam_review, NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun canPost() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun post(id: Int, n: android.app.Notification) {
        if (canPost()) runCatching { manager.notify(id, n) }
    }

    private fun openApp(key: String?, requestCode: Int, category: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (key != null) putExtra(Actions.EXTRA_KEY, key)
            if (category != null) putExtra(Actions.EXTRA_CATEGORY, category)
        }
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(action: String, requestCode: Int, mutable: Boolean = false, fill: Intent.() -> Unit): PendingIntent {
        val i = Intent(context, NotificationActionReceiver::class.java).setAction(action).apply(fill)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, requestCode, i, flags)
    }

    /**
     * Incoming-message notification; a transaction or OTP is summarised in the text itself. Offers mark-read,
     * delete, and an inline reply for phone-number senders — the three most the drawer will show. An OTP with
     * its own heads-up enabled is not shown here; that heads-up carries copy and delete.
     */
    fun showIncoming(m: IngestedMessage, title: String, previewTransactions: Boolean) {
        val key = m.message.conversationKey
        val id = notificationId(key)
        val text = previewText(m, previewTransactions)
        val sender = Person.Builder().setName(title).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.you)).build())
            .addMessage(text, m.message.date, sender)

        val b = NotificationCompat.Builder(context, Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openApp(key, id))
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_message, context.getString(R.string.mark_read),
                    action(Actions.MARK_READ, id) { putExtra(Actions.EXTRA_KEY, key) },
                ).build(),
            )
        if (ConversationKeys.isPhoneNumber(m.message.address)) {
            val remote = RemoteInput.Builder(Actions.REPLY_KEY).setLabel(context.getString(R.string.reply)).build()
            b.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_message, context.getString(R.string.reply),
                    action(Actions.REPLY, id + 1, mutable = true) {
                        putExtra(Actions.EXTRA_KEY, key); putExtra(Actions.EXTRA_ADDRESS, m.message.address)
                    },
                ).addRemoteInput(remote).setAllowGeneratedReplies(true).build(),
            )
        }
        b.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_stat_message, context.getString(R.string.delete),
                action(Actions.DELETE, id + 2) {
                    putExtra(Actions.EXTRA_KEY, key); putExtra(Actions.EXTRA_MESSAGE_ID, m.message.id)
                },
            ).build(),
        )
        post(id, b.build())
    }

    /** A dedicated heads-up for one-time codes, with copy and delete actions. */
    fun showOtp(m: IngestedMessage, title: String) {
        val otp = m.parsed.otp ?: return
        val id = otpNotificationId(m.message.id)
        val detail = listOfNotNull(title, otp.purpose, otp.validForMinutes?.let { context.getString(R.string.valid_minutes, it) })
            .joinToString(" · ")
        val n = NotificationCompat.Builder(context, Channels.OTP)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.code_title, otp.code))
            .setContentText(detail)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openApp(m.message.conversationKey, id))
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_message, context.getString(R.string.copy_code),
                    action(Actions.COPY_OTP, id) { putExtra(Actions.EXTRA_OTP, otp.code); putExtra(Actions.EXTRA_MESSAGE_ID, m.message.id) },
                ).build(),
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_message, context.getString(R.string.delete),
                    action(Actions.DELETE, id + 1) { putExtra(Actions.EXTRA_MESSAGE_ID, m.message.id) },
                ).build(),
            )
            .build()
        post(id, n)
    }

    /** "You have a multimedia message": shown when it could not (or may not) be downloaded automatically. */
    fun showMmsPending(m: IngestedMessage, title: String) {
        val key = m.message.conversationKey
        val n = NotificationCompat.Builder(context, Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.mms_tap_to_download))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp(key, notificationId(key)))
            .build()
        post(notificationId(key), n)
    }

    /** A message that was held for undo could not be sent and nobody was looking at its conversation; tapping opens it with the text back in the box. */
    fun showSendFailed(key: String, title: String, reason: String) {
        val n = NotificationCompat.Builder(context, Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.send_failed_title, title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openApp(key, notificationId(key)))
            .build()
        post(notificationId(key), n)
    }

    fun showProtection(m: IngestedMessage, title: String) {
        val n = NotificationCompat.Builder(context, Channels.PROTECTION)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.suspicious_title))
            .setContentText(context.getString(R.string.suspicious_text, title))
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.parsed.spamReasons.joinToString("\n") { "• $it" }))
            .setAutoCancel(true)
            .setContentIntent(openApp(m.message.conversationKey, m.message.id.toInt()))
            .build()
        post(PROTECTION_BASE + (m.message.id % 1000).toInt(), n)
    }

    /** The weekly "review your spam" nudge; tapping it opens the inbox on the Spam category. */
    fun showSpamReview(conversations: Int, messages: Int) {
        val res = context.resources
        val text = context.getString(
            R.string.spam_review_text,
            res.getQuantityString(R.plurals.spam_messages, messages, messages),
            res.getQuantityString(R.plurals.spam_conversations, conversations, conversations),
        )
        val n = NotificationCompat.Builder(context, Channels.SPAM_REVIEW)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.spam_review_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(null, SPAM_REVIEW_ID, SmsCategory.SPAM.key))
            .build()
        post(SPAM_REVIEW_ID, n)
    }

    fun showReminder(r: ReminderEntity) {
        val n = NotificationCompat.Builder(context, Channels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(r.title + " · " + r.sender)
            .setContentText(r.amountMinor?.let { formatMoney(it, r.currency) } ?: context.getString(R.string.tap_to_view))
            .setAutoCancel(true)
            .setContentIntent(openApp(null, REMINDER_BASE + r.id.toInt()))
            .build()
        post(REMINDER_BASE + r.id.toInt(), n)
    }

    fun showBudget(bucket: String, spentMinor: Long, limitMinor: Long, currency: String) {
        val n = NotificationCompat.Builder(context, Channels.FINANCE)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.budget_title, if (bucket == "*") context.getString(R.string.overall) else bucket))
            .setContentText(context.getString(R.string.budget_text, formatMoney(spentMinor, currency), formatMoney(limitMinor, currency)))
            .setAutoCancel(true)
            .setContentIntent(openApp(null, BUDGET_BASE + (bucket.hashCode() and 0xFFF)))
            .build()
        post(BUDGET_BASE + (bucket.hashCode() and 0xFFF), n)
    }

    fun cancelConversation(key: String) = manager.cancel(notificationId(key))
    fun cancel(id: Int) = manager.cancel(id)
    fun cancelOtp(messageId: Long) {
        manager.cancel(otpNotificationId(messageId))
    }

    private fun previewText(m: IngestedMessage, previewTransactions: Boolean): String {
        val t = m.parsed.transaction
        if (previewTransactions && t != null && t.kind != TxnKind.OTHER) {
            val verb = if (t.kind == TxnKind.DEBIT) context.getString(R.string.debited) else context.getString(R.string.credited)
            return buildString {
                append(verb).append(' ').append(formatMoney(t.amountMinor, t.currency))
                t.merchant?.let { append(" · ").append(it) }
                t.balanceMinor?.let { append(" · ").append(context.getString(R.string.balance_short, formatMoney(it, t.currency))) }
            }
        }
        if (m.message.body.isBlank()) {
            return when (m.message.mediaKind) {
                "image" -> context.getString(R.string.mms_photo)
                "video" -> context.getString(R.string.mms_video)
                "audio" -> context.getString(R.string.mms_audio)
                "contact" -> context.getString(R.string.mms_contact)
                else -> context.getString(R.string.mms_attachment)
            }
        }
        return m.message.body
    }

    companion object {
        private const val PROTECTION_BASE = 40_000
        private const val REMINDER_BASE = 50_000
        private const val BUDGET_BASE = 60_000

        const val SPAM_REVIEW_ID = 30_001
        fun notificationId(key: String) = 10_000 + (key.hashCode() and 0x7FFF)
        fun otpNotificationId(messageId: Long) = 20_000 + (messageId % 10_000).toInt() * 2

        fun formatMoney(minor: Long, currency: String?): String {
            val nf = NumberFormat.getCurrencyInstance()
            runCatching { nf.currency = Currency.getInstance(currency ?: "USD") }
            return nf.format(minor / 100.0)
        }

        @Suppress("unused")
        fun titleFor(address: String, contactName: String?): String =
            contactName ?: if (ConversationKeys.isPhoneNumber(address)) address
            else SenderBrands.displayName(ConversationKeys.senderToken(address))
    }
}
