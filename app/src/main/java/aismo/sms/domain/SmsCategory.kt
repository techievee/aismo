package aismo.sms.domain

/** Built-in inbox categories. Custom categories use keys of the form `custom:<id>`. */
enum class SmsCategory(val key: String, val label: String, val description: String) {
    PERSONAL("personal", "Personal", "Chats with people"),
    OTP("otp", "OTP", "One-time passwords and login codes"),
    FINANCE("finance", "Finance", "Bank, card, wallet and UPI activity"),
    BILL("bill", "Bills", "Bills, dues and statements"),
    ORDER("order", "Orders", "Purchases, shipping and delivery"),
    TRAVEL("travel", "Travel", "Flights, trains, buses and bookings"),
    GOVERNMENT("government", "Government", "Public services and official notices"),
    HEALTH("health", "Health", "Appointments, labs and insurance"),
    EDUCATION("education", "Education", "Schools, exams and courses"),
    JOB("job", "Jobs", "Hiring, interviews and work"),
    SERVICE("service", "Services", "Telecom, utilities and account alerts"),
    PROMO("promo", "Promotions", "Offers, discounts and marketing"),
    SPAM("spam", "Spam", "Suspicious or unwanted messages"),
    OTHER("other", "Other", "Everything else");

    companion object {
        const val CUSTOM_PREFIX = "custom:"
        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String): SmsCategory? = byKey[key]
        fun isCustom(key: String) = key.startsWith(CUSTOM_PREFIX)
        fun isValidKey(key: String) = key in byKey || Regex("^custom:[a-z0-9_]{6,}$").matches(key)
    }
}

/**
 * Maps an address to the key that groups its messages into one conversation. SMS and MMS from the same
 * sender live in separate conversations: the plain-text one keeps the historic `p:`/`s:`/`g:` form, and the
 * multimedia one uses the `m:`/`gm:` form of the same normalized address, so all spelling variants of a
 * number (local, international, with the trunk prefix) still land in the same thread of each type.
 */
object ConversationKeys {
    private val phoneLike = Regex("^[+\\d][\\d\\s().-]{4,}$")
    private val dltPrefix = Regex("^[A-Za-z]{2}-(.+)$")

    fun isPhoneNumber(address: String) = phoneLike.matches(address.trim())

    /** A group conversation stores its participants comma-separated in the address column. */
    fun isGroup(address: String) = ',' in address

    fun participants(address: String): List<String> = address.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** True when every participant is a phone number, i.e. a reply (SMS or MMS) can be sent. */
    fun canReply(address: String): Boolean = participants(address).let { p -> p.isNotEmpty() && p.all(::isPhoneNumber) }

    fun of(address: String, mms: Boolean = false): String {
        val a = address.trim()
        if (a.isEmpty()) return "unknown"
        if (isGroup(a)) {
            // The key is order-independent: the same people always land in the same conversation. The type lives in
            // the prefix (`g:`/`gm:`) so the participants keep the same neutral keys in both forms.
            val parts = participants(a)
            val keys = parts.map { single(it) }.distinct().sorted()
            return if (keys.size == 1) single(parts.first(), mms) else (if (mms) "gm:" else "g:") + keys.joinToString("|")
        }
        return single(a, mms)
    }

    private fun single(address: String, mms: Boolean = false): String {
        val a = address.trim()
        if (a.isEmpty()) return "unknown"
        if (isPhoneNumber(a)) {
            val digits = a.filter { it.isDigit() }
            // The last 9 digits are the subscriber part for most national plans, so `0412345678`,
            // `+61412345678` and `0061 412 345 678` share a key while short codes stay whole.
            return (if (mms) "m:" else "p:") + if (digits.length > 9) digits.takeLast(9) else digits
        }
        val token = dltPrefix.find(a)?.groupValues?.get(1) ?: a
        return (if (mms) "m:" else "s:") + token.uppercase().filter { it.isLetterOrDigit() }
    }

    /** True for the keys of multimedia conversations ([of] with `mms` true). */
    fun isMmsKey(key: String) = key.startsWith("m:") || key.startsWith("gm:")

    /**
     * The other side of the SMS/MMS border for the same conversation: `p:`↔`m:`, `s:`↔`m:`, `g:`↔`gm:`.
     * `m:` has two, because its twin may be a phone or a business sender.
     */
    fun siblingForms(key: String): List<String> = when {
        key.startsWith("gm:") -> listOf("g:" + key.substring(3))
        key.startsWith("g:") -> listOf("gm:" + key.substring(2))
        key.startsWith("p:") -> listOf("m:" + key.substring(2))
        key.startsWith("s:") -> listOf("m:" + key.substring(2))
        key.startsWith("m:") -> listOf("p:" + key.substring(2), "s:" + key.substring(2))
        else -> emptyList()
    }

    /** The sender token shown as a name for business senders (e.g. `VM-HDFCBK` -> `HDFCBK`). */
    fun senderToken(address: String): String = dltPrefix.find(address.trim())?.groupValues?.get(1) ?: address.trim()
}
