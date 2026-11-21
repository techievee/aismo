package aismo.sms.domain.parser

/** A user-authored rule: if the sender and body fragments match, force a category and optional merchant. */
data class UserRule(
    val senderContains: String?,
    val bodyContains: String?,
    val categoryKey: String,
    val merchant: String?,
)

data class OtpInfo(val code: String, val purpose: String?, val validForMinutes: Int?)

enum class TxnKind { DEBIT, CREDIT, OTHER }

data class TransactionInfo(
    val kind: TxnKind,
    val amountMinor: Long,
    val currency: String,
    val merchant: String?,
    val instrument: String?,
    val last4: String?,
    val balanceMinor: Long?,
)

enum class ReminderKind { BILL_DUE, APPOINTMENT, TRAVEL, DELIVERY, EXPIRY }

data class ReminderInfo(
    val kind: ReminderKind,
    val title: String,
    val dueAt: Long?,
    val amountMinor: Long?,
    val currency: String?,
)

data class ParsedSms(
    val categoryKey: String,
    val merchant: String?,
    val otp: OtpInfo?,
    val transaction: TransactionInfo?,
    val reminders: List<ReminderInfo>,
    val spamScore: Int,
    val spamReasons: List<String>,
    val quickReplies: List<String>,
    val trackingUrls: List<String>,
    /** True when the category came from weak evidence and the UI should invite a correction. */
    val lowConfidence: Boolean,
) {
    companion object {
        /** A result with no extracted facts, for messages whose content is not available yet. */
        fun empty(categoryKey: String) = ParsedSms(
            categoryKey = categoryKey, merchant = null, otp = null, transaction = null, reminders = emptyList(),
            spamScore = 0, spamReasons = emptyList(), quickReplies = emptyList(), trackingUrls = emptyList(), lowConfidence = false,
        )
    }
}
