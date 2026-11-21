package aismo.sms.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import aismo.sms.domain.CategoryIconKeys

/** Local index of one row of the system SMS provider; the provider stays the source of truth. */
@Entity(
    tableName = "messages",
    indices = [Index("conversationKey", "date"), Index("threadId"), Index("category"), Index("date")],
)
data class MessageEntity(
    /** The provider `_id`. */
    @PrimaryKey val id: Long,
    val threadId: Long,
    val address: String,
    val conversationKey: String,
    val body: String,
    val date: Long,
    /** Telephony.Sms.MESSAGE_TYPE_*: 1 inbox, 2 sent, 3 draft, 4 outbox, 5 failed, 6 queued. */
    val type: Int,
    val isRead: Boolean,
    val subId: Int,
    /** Telephony.Sms.STATUS_*: -1 none, 0 complete (delivered), 32 pending, 64 failed. */
    val status: Int,
    val isStarred: Boolean = false,
    val category: String,
    /** Which parser revision produced [category]; lets us re-parse after rule changes. */
    val parserVersion: Int,
    /** MMS subject, also folded into [body] so search and the parser see it. */
    val subject: String? = null,
    /** Kind of the first attachment (image, video, audio, contact, file); null for plain texts. */
    val mediaKind: String? = null,
    @ColumnInfo(defaultValue = "0") val attachmentCount: Int = 0,
) {
    val isMms: Boolean get() = MessageIds.isMms(id)
}

/**
 * The index holds rows of two provider tables whose ids overlap (`sms._id` and `mms._id`), so MMS rows
 * live in a separate id range. Provider calls must translate with [raw] / [mms].
 */
object MessageIds {
    const val MMS_BASE = 1L shl 40

    fun mms(rawId: Long) = MMS_BASE + rawId
    fun isMms(id: Long) = id >= MMS_BASE
    fun raw(id: Long) = if (isMms(id)) id - MMS_BASE else id
}

@Entity(tableName = "attachments", primaryKeys = ["messageId", "partId"], indices = [Index("messageId")])
data class AttachmentEntity(
    val messageId: Long,
    /** `_id` of the part in `content://mms/part`. */
    val partId: Long,
    val mime: String,
    val name: String?,
    val sizeBytes: Long,
    /** image, video, audio, contact or file. */
    val kind: String,
)

/** An MMS we were notified about but have not (fully) downloaded yet. */
@Entity(tableName = "mms_downloads")
data class MmsDownloadEntity(
    @PrimaryKey val messageId: Long,
    val contentLocation: String,
    val transactionId: String,
    val subId: Int,
    val sizeBytes: Long,
    val expiresAt: Long?,
    /** 0 waiting, 1 downloading, 2 failed. */
    val state: Int,
    val attempts: Int,
    /** Last SmsManager.MMS_ERROR_* code, or 0. */
    val lastError: Int,
    val createdAt: Long,
) {
    companion object {
        const val WAITING = 0
        const val DOWNLOADING = 1
        const val FAILED = 2
    }
}

@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFts(val body: String, val address: String)

@Entity(tableName = "conversations", indices = [Index("latestDate"), Index("category", "latestDate")])
data class ConversationEntity(
    @PrimaryKey val key: String,
    val address: String,
    /** Business/brand name for non-phone senders. */
    val title: String?,
    val latestMessageId: Long,
    val latestBody: String,
    val latestDate: Long,
    val latestType: Int,
    /** Attachments on the newest message (0 for plain texts); drives the paperclip in the conversation list. */
    @ColumnInfo(defaultValue = "0") val latestAttachmentCount: Int = 0,
    val unreadCount: Int,
    val messageCount: Int,
    val category: String,
    /** True once the user picked the category; automatic parsing then leaves it alone. */
    val categoryLocked: Boolean,
    val needsReview: Boolean,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    /** Sender is on the system blocked-numbers list; hidden from the inbox, no notifications. */
    val isBlocked: Boolean = false,
)

@Entity(tableName = "transactions", indices = [Index("occurredAt"), Index("merchant")])
data class TransactionEntity(
    @PrimaryKey val messageId: Long,
    /** DEBIT, CREDIT or OTHER (name of TxnKind). */
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    val merchant: String?,
    val instrument: String?,
    val last4: String?,
    val balanceMinor: Long?,
    val occurredAt: Long,
    /** The user's spending bucket for budgets; defaults to the merchant-derived label. */
    val spendCategory: String?,
)

@Entity(tableName = "otps", indices = [Index("receivedAt")])
data class OtpEntity(
    @PrimaryKey val messageId: Long,
    val code: String,
    val purpose: String?,
    val sender: String,
    val receivedAt: Long,
    val expiresAt: Long?,
    val isUsed: Boolean = false,
)

@Entity(tableName = "reminders", indices = [Index("messageId"), Index("status", "dueAt")])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    /** ReminderKind name. */
    val kind: String,
    val title: String,
    val sender: String,
    val dueAt: Long?,
    val amountMinor: Long?,
    val currency: String?,
    /** open, done or dismissed. */
    val status: String = "open",
)

@Entity(tableName = "user_rules")
data class UserRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val senderContains: String?,
    val bodyContains: String?,
    val categoryKey: String,
    val merchant: String?,
    val isEnabled: Boolean = true,
)

@Entity(tableName = "custom_categories")
data class CustomCategoryEntity(
    @PrimaryKey val key: String,
    val name: String,
    /** One of [CategoryIconKeys.ALL]; categories made before icons existed have the default. */
    @ColumnInfo(defaultValue = CategoryIconKeys.DEFAULT) val icon: String = CategoryIconKeys.DEFAULT,
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    /** Spend bucket (merchant or label) or `*` for the overall monthly budget. */
    @PrimaryKey val bucket: String,
    val limitMinor: Long,
    val currency: String,
    /** Percentage of the limit at which to alert. */
    val alertPercent: Int = 80,
    /** Last month (yyyyMM) an alert fired, so we alert once per month per threshold. */
    @ColumnInfo(defaultValue = "0") val lastAlertMonth: Int = 0,
)

@Entity(tableName = "incidents", indices = [Index("state", "detectedAt")])
data class IncidentEntity(
    @PrimaryKey val messageId: Long,
    val sender: String,
    val score: Int,
    val reasons: String,
    val detectedAt: Long,
    /** open, dismissed. */
    val state: String = "open",
)

/**
 * One stored translation of a message. Derived data: it can always be made again from the message text, so it is not
 * backed up. One row per message and target language; re-translating into the same language replaces the row.
 * [source] is the language the text was guessed or picked as, null when only the target was known.
 */
@Entity(tableName = "message_translations", primaryKeys = ["messageId", "target"])
data class TranslationEntity(
    val messageId: Long,
    val target: String,
    val source: String?,
    val text: String,
    val at: Long,
)

/**
 * The meaning of one message as a vector, for search by meaning. Derived data: it can always be rebuilt from the
 * message text, so it is not backed up. [model] says which model and tokenization produced it.
 */
@Entity(tableName = "message_embeddings")
data class MessageEmbeddingEntity(
    @PrimaryKey val messageId: Long,
    val model: Int,
    /** Multiplies each signed byte of [vector] to restore the component; 0 marks a text with no content. */
    val scale: Float,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
)
