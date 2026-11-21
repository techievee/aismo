package aismo.sms.domain.parser

import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.SmsCategory
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Rule-based SMS understanding: category, OTP, transaction, reminders, spam score and quick replies.
 * Pure Kotlin (no Android types) so it can be unit tested on the JVM.
 *
 * @param dollarCurrency currency to assume for a bare `$` sign (derive it from the device region).
 */
class SmsParser(
    private val dollarCurrency: String = "USD",
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * The parser must never throw: it runs over arbitrary text off the network (ingest, quick replies,
     * re-parses). A failure degrades to "personal, nothing understood" instead of taking the app down.
     */
    fun parse(
        address: String,
        body: String,
        receivedAt: Long,
        rules: List<UserRule> = emptyList(),
    ): ParsedSms = runCatching {
        parseOnce(address, body, receivedAt, rules)
    }.getOrDefault(ParsedSms.empty(SmsCategory.PERSONAL.key))

    private fun parseOnce(
        address: String,
        body: String,
        receivedAt: Long,
        rules: List<UserRule>,
    ): ParsedSms {
        val text = body.replace('\u00a0', ' ').trim()
        val lower = text.lowercase()
        // A group MMS arrives as a comma-separated list of numbers: people, not a business sender.
        val phoneSender = ConversationKeys.canReply(address)
        val token = ConversationKeys.senderToken(address)
        val brand = if (phoneSender) null else SenderBrands.lookup(token)

        val otp = findOtp(text, lower)
        val txn = findTransaction(text, lower)
        val spam = scoreSpam(text, lower, phoneSender, otp != null)
        val reminders = findReminders(text, lower, receivedAt)
        val tracking = TRACKING_URL.findAll(text).map { it.value }.toList().take(3)

        val merchant = txn?.merchant ?: if (!phoneSender) SenderBrands.displayName(token) else null

        val ruleHit = rules.firstOrNull { r ->
            (r.senderContains != null || r.bodyContains != null) &&
                (r.senderContains == null || address.contains(r.senderContains, ignoreCase = true)) &&
                (r.bodyContains == null || text.contains(r.bodyContains, ignoreCase = true))
        }

        val (category, weak) = when {
            ruleHit != null -> ruleHit.categoryKey to false
            otp != null && spam.score < 70 -> SmsCategory.OTP.key to false
            spam.score >= SPAM_THRESHOLD -> SmsCategory.SPAM.key to false
            txn != null -> SmsCategory.FINANCE.key to false
            isBill(lower) -> SmsCategory.BILL.key to false
            isTravel(lower) -> SmsCategory.TRAVEL.key to false
            isOrder(lower) || (brand?.category == SmsCategory.ORDER) -> SmsCategory.ORDER.key to false
            isGovernment(lower) -> SmsCategory.GOVERNMENT.key to false
            isHealth(lower) -> SmsCategory.HEALTH.key to false
            isEducation(lower) -> SmsCategory.EDUCATION.key to false
            isJob(lower) -> SmsCategory.JOB.key to false
            // Everyday words ("free", "now", "plan") appear all the time in personal texts, so the marketing and
            // service checks only apply to business senders; a message from a person is personal unless one of the
            // content-driven checks above already matched.
            phoneSender -> SmsCategory.PERSONAL.key to false
            isPromo(lower) -> SmsCategory.PROMO.key to false
            brand != null -> brand.category.key to false
            isService(lower) -> SmsCategory.SERVICE.key to false
            else -> SmsCategory.OTHER.key to true
        }

        return ParsedSms(
            categoryKey = category,
            merchant = ruleHit?.merchant ?: merchant,
            otp = otp,
            transaction = txn,
            reminders = reminders,
            spamScore = spam.score,
            spamReasons = spam.reasons,
            quickReplies = findQuickReplies(text, phoneSender),
            trackingUrls = tracking,
            lowConfidence = weak,
        )
    }

    // ---------------------------------------------------------------- OTP

    private fun findOtp(text: String, lower: String): OtpInfo? {
        if (!OTP_HINT.containsMatchIn(lower)) return null
        if (PROMO_CODE_CONTEXT.containsMatchIn(lower) && !OTP_STRONG.containsMatchIn(lower)) return null
        val code = OTP_IS_YOUR.find(text)?.groupValues?.get(1)
            ?: OTP_AFTER_KEYWORD.find(text)?.takeIf { isPlausibleCode(text, it.groups[1]!!.range) }?.groupValues?.get(1)
            ?: OTP_ALNUM.find(text)?.groupValues?.get(1)?.takeIf { c -> c.any { it.isDigit() } && c.any { it.isLetter() } }
            ?: return null
        val purpose = when {
            Regex("(?i)\\b(transaction|txn|payment|purchase|pay|debit)\\b").containsMatchIn(text) -> "payment"
            Regex("(?i)\\b(reset|forgot|recover)").containsMatchIn(text) -> "password reset"
            Regex("(?i)\\b(register|registration|sign ?up|activate|activation|new account)").containsMatchIn(text) -> "sign up"
            Regex("(?i)\\b(login|log in|sign ?in|access)\\b").containsMatchIn(text) -> "login"
            Regex("(?i)\\b(delivery|deliver)").containsMatchIn(text) -> "delivery"
            else -> null
        }
        val validity = VALID_FOR.find(text)?.let {
            val n = it.groupValues[1].toInt()
            if (it.groupValues[2].startsWith("sec", ignoreCase = true)) maxOf(1, n / 60) else n
        }
        return OtpInfo(code, purpose, validity)
    }

    private fun isPlausibleCode(text: String, range: IntRange): Boolean {
        val before = text.substring(maxOf(0, range.first - 4), range.first)
        val after = text.substring(range.last + 1, minOf(text.length, range.last + 3))
        if (CURRENCY_TAIL.containsMatchIn(before)) return false
        if (after.trimStart().startsWith("%")) return false
        return true
    }

    // ------------------------------------------------------------ transactions

    private fun findTransaction(text: String, lower: String): TransactionInfo? {
        if (PROMO_FINANCE.containsMatchIn(lower) && !PAST_TXN.containsMatchIn(lower)) return null
        val maskedCard = CARD_MASKED.containsMatchIn(lower)
        val kind = when {
            DEBIT_WORDS.containsMatchIn(lower) && !CREDIT_ONLY.containsMatchIn(lower) -> TxnKind.DEBIT
            CREDIT_WORDS.containsMatchIn(lower) -> TxnKind.CREDIT
            DEBIT_WORDS.containsMatchIn(lower) -> TxnKind.DEBIT
            // "ZOMATO: Rs 120.00 21/11 5:30pm 4111xxxx": banks that put the merchant first use no verb at all; the
            // masked card number is what marks the line as a charge.
            maskedCard && MERCHANT_FIRST.find(text) != null -> TxnKind.DEBIT
            else -> return null
        }
        if (!PAST_TXN.containsMatchIn(lower) && !ACCOUNT_CONTEXT.containsMatchIn(lower) && !maskedCard) return null
        val amount = firstAmount(text, excludeBalance = true) ?: return null
        val balance = BALANCE.find(text)?.let { toAmount(it.groupValues[1], it.groupValues[2]) }
        val last4 = LAST4.find(text)?.groupValues?.get(1)
        val instrument = when {
            Regex("(?i)\\b(upi|vpa)\\b").containsMatchIn(text) -> "UPI"
            Regex("(?i)\\bcredit card|cc\\b").containsMatchIn(text) -> "Credit card"
            Regex("(?i)\\bdebit card|atm\\b").containsMatchIn(text) -> "Debit card"
            Regex("(?i)\\b(card)\\b").containsMatchIn(text) -> "Card"
            Regex("(?i)\\b(a/c|acct|account)\\b").containsMatchIn(text) -> "Account"
            Regex("(?i)\\bwallet\\b").containsMatchIn(text) -> "Wallet"
            else -> null
        }
        // "to/at/for" introduce the payee of a debit; "from/by" the payer of a credit.
        val wrongLead = if (kind == TxnKind.CREDIT) setOf("at", "to", "towards", "for") else setOf("from", "by")
        val merchant = MERCHANT.findAll(text).firstNotNullOfOrNull { m ->
            if (m.groupValues[1].lowercase().trim() in wrongLead) null else cleanMerchant(m.groupValues[2])
        } ?: VPA.find(text)?.groupValues?.get(0)
            ?: MERCHANT_FIRST.find(text)?.groupValues?.get(1)?.let(::cleanMerchant)
        return TransactionInfo(kind, amount.first, amount.second, merchant, instrument, last4, balance?.first)
    }

    private fun firstAmount(text: String, excludeBalance: Boolean): Pair<Long, String>? {
        for (m in AMOUNT.findAll(text)) {
            if (excludeBalance) {
                val before = text.substring(maxOf(0, m.range.first - 28), m.range.first).lowercase()
                if (BALANCE_LEAD.containsMatchIn(before)) continue
            }
            val cur = m.groupValues[1].ifEmpty { m.groupValues[4] }
            val num = m.groupValues[2].ifEmpty { m.groupValues[3] }
            toAmount(cur, num)?.let { return it }
        }
        return null
    }

    private fun toAmount(cur: String, num: String): Pair<Long, String>? {
        val value = num.replace(",", "").toDoubleOrNull() ?: return null
        if (value <= 0.0 || value > 1_00_00_00_000.0) return null
        return (value * 100).roundToLong() to currencyCode(cur)
    }

    private fun currencyCode(raw: String): String = when (raw.lowercase().trim('.')) {
        "rs", "inr", "₹", "rupees" -> "INR"
        "usd", "us$" -> "USD"
        "aud", "a$" -> "AUD"
        "eur", "€", "euros" -> "EUR"
        "gbp", "£", "pounds" -> "GBP"
        "dollars" -> dollarCurrency
        "$" -> dollarCurrency
        else -> dollarCurrency
    }

    private fun cleanMerchant(raw: String): String? {
        val m = raw.trim().trim('.', ',', '-', ':').replace(Regex("\\s+"), " ")
        if (m.length < 2 || m.length > 32) return null
        if (m.lowercase() in MERCHANT_STOPWORDS) return null
        if (ACCOUNT_LIKE.containsMatchIn(m)) return null
        if (m.all { it.isDigit() || it == 'x' || it == 'X' || it == '*' }) return null
        return m.split(' ').joinToString(" ") { w ->
            if (w.length > 3 && w.all { it.isUpperCase() || it.isDigit() }) w.lowercase().replaceFirstChar { it.uppercase() } else w
        }
    }

    // ---------------------------------------------------------------- reminders

    private fun findReminders(text: String, lower: String, receivedAt: Long): List<ReminderInfo> {
        val out = mutableListOf<ReminderInfo>()
        val due = findDate(text, receivedAt)
        if (isBill(lower) && (DUE_WORDS.containsMatchIn(lower) || due != null)) {
            val amt = firstAmount(text, excludeBalance = true)
            out += ReminderInfo(ReminderKind.BILL_DUE, "Bill due", due, amt?.first, amt?.second)
        } else if (EXPIRY_WORDS.containsMatchIn(lower) && due != null) {
            out += ReminderInfo(ReminderKind.EXPIRY, "Expires", due, null, null)
        }
        if (isTravel(lower) && due != null) out += ReminderInfo(ReminderKind.TRAVEL, "Trip", due, null, null)
        if (isHealth(lower) && APPOINTMENT.containsMatchIn(lower) && due != null) {
            out += ReminderInfo(ReminderKind.APPOINTMENT, "Appointment", due, null, null)
        }
        if (isOrder(lower) && Regex("(?i)\\b(out for delivery|will be delivered|arriv|delivery (?:on|by))").containsMatchIn(text)) {
            out += ReminderInfo(ReminderKind.DELIVERY, "Delivery", due, null, null)
        }
        return out
    }

    /**
     * The date a reminder is about. An explicit calendar date wins over a relative phrase: "due 25/11, see you
     * tomorrow" has two dates, and the one that is written down is the one that matters.
     */
    private fun findDate(text: String, receivedAt: Long): Long? {
        val ref = java.time.Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        val day = explicitDay(text, ref) ?: relativeDay(text, ref) ?: return null
        return at(day, findTime(text))
    }

    private fun explicitDay(text: String, ref: LocalDate): LocalDate? {
        NUMERIC_DATE.find(text)?.let { m ->
            explicitDate(normalizeYear(m.groupValues[3].toInt()), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { return it }
        }
        NAMED_DATE_DMY.find(text)?.let { m ->
            val mo = monthIndex(m.groupValues[2]) ?: return@let
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let(::normalizeYear)
            explicitOf(ref, y, mo, m.groupValues[1].toInt())?.let { return it }
        }
        NAMED_DATE_MDY.find(text)?.let { m ->
            val mo = monthIndex(m.groupValues[1]) ?: return@let
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            explicitOf(ref, y, mo, m.groupValues[2].toInt())?.let { return it }
        }
        return null
    }

    /** A year-less named date: this year, rolled to next when it sits more than 60 days in the past. */
    private fun explicitOf(ref: LocalDate, year: Int?, month: Int, day: Int): LocalDate? {
        if (year != null) return explicitDate(year, month, day)
        var d = explicitDate(ref.year, month, day) ?: return null
        if (d < ref.minusDays(60)) d = explicitDate(ref.year + 1, month, day) ?: return null
        return d
    }

    private fun explicitDate(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /**
     * The relative day words people actually use when no calendar date is written: "tonight", "tomorrow",
     * "in 2 days", "next Friday", "by Friday", "on the 25th", "today".
     */
    private fun relativeDay(text: String, ref: LocalDate): LocalDate? {
        val lower = text.lowercase()
        if ("tonight" in lower) return ref
        if ("tomorrow" in lower) return ref.plusDays(1)
        IN_DAYS.find(lower)?.let {
            val n = it.groupValues[1].toInt()
            if (n in 0..60) return ref.plusDays(n.toLong())
        }
        if ("next week" in lower) return ref.plusDays(7)
        NEXT_WEEKDAY.find(lower)?.let {
            val target = weekdayOf(it.groupValues[1]) ?: return@let
            val delta = (target - ref.dayOfWeek.value + 7) % 7
            return ref.plusDays((if (delta == 0) 7 else delta).toLong())
        }
        BY_OR_ON_WEEKDAY.find(lower)?.let {
            val target = weekdayOf(it.groupValues[1]) ?: return@let
            return ref.plusDays(((target - ref.dayOfWeek.value + 7) % 7).toLong())
        }
        ORDINAL_DATE.find(lower)?.let {
            val d = it.groupValues[1].toInt()
            if (d in 1..31) {
                val thisMonth = runCatching { LocalDate.of(ref.year, ref.monthValue, d) }.getOrNull()
                if (thisMonth != null && thisMonth >= ref) return thisMonth
                return runCatching { LocalDate.of(ref.year, ref.monthValue + 1, d) }.getOrNull()
            }
        }
        if ("today" in lower) return ref
        return null
    }

    private fun weekdayOf(name: String): Int? = when (name) {
        "mon", "monday" -> 1
        "tue", "tues", "tuesday" -> 2
        "wed", "wednesday" -> 3
        "thu", "thur", "thurs", "thursday" -> 4
        "fri", "friday" -> 5
        "sat", "saturday" -> 6
        "sun", "sunday" -> 7
        else -> null
    }

    /** The hour the date is pinned to ("at 11:59pm", "5.30pm", "by 18:30"); null keeps the 9am default. */
    private fun findTime(text: String): Pair<Int, Int>? {
        TIME_COLON_AMPM.find(text)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            val mer = it.groupValues[3].lowercase()
            if (h in 1..12 && m in 0..59) return adjusted(h, mer) to m
        }
        TIME_SIMPLE_AMPM.find(text)?.let {
            val h = it.groupValues[1].toInt()
            val mer = it.groupValues[2].lowercase()
            if (h in 1..12) return adjusted(h, mer) to 0
        }
        TIME_24.find(text)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            if (h in 0..23 && m in 0..59) return h to m
        }
        return null
    }

    private fun adjusted(h: Int, mer: String) = when {
        mer == "am" && h == 12 -> 0
        mer == "pm" && h < 12 -> h + 12
        else -> h
    }

    /** The due moment: the time found in the message, else 9am, when a reminder is least likely to be embarrassing. */
    private fun at(day: LocalDate, time: Pair<Int, Int>?): Long =
        day.atTime(time?.first ?: 9, time?.second ?: 0).atZone(zone).toInstant().toEpochMilli()

    private fun normalizeYear(y: Int) = if (y < 100) 2000 + y else y

    private fun monthIndex(name: String): Int? =
        MONTHS.indexOfFirst { name.lowercase().startsWith(it) }.takeIf { it >= 0 }?.plus(1)

    // --------------------------------------------------------------- category checks

    private fun isBill(l: String) = BILL_WORDS.containsMatchIn(l) && !PROMO_STRONG.containsMatchIn(l)
    private fun isTravel(l: String) = TRAVEL_WORDS.containsMatchIn(l)
    private fun isOrder(l: String) = ORDER_WORDS.containsMatchIn(l)
    private fun isGovernment(l: String) = GOV_WORDS.containsMatchIn(l)
    private fun isHealth(l: String) = HEALTH_WORDS.containsMatchIn(l)
    private fun isEducation(l: String) = EDU_WORDS.containsMatchIn(l)
    private fun isJob(l: String) = JOB_WORDS.containsMatchIn(l)
    private fun isPromo(l: String) = PROMO_STRONG.containsMatchIn(l) || PROMO_WEAK.findAll(l).count() >= 2
    private fun isService(l: String) = SERVICE_WORDS.containsMatchIn(l)

    // ------------------------------------------------------------------- spam

    private class SpamScore(val score: Int, val reasons: List<String>)

    private fun scoreSpam(text: String, lower: String, phoneSender: Boolean, hasOtp: Boolean): SpamScore {
        var s = 0
        val why = mutableListOf<String>()
        fun add(points: Int, reason: String) { s += points; why += reason }
        val hasUrl = URL.containsMatchIn(lower)
        if (SHORTENER.containsMatchIn(lower)) add(25, "shortened link")
        if (hasUrl && phoneSender) add(15, "link from an unknown number")
        if (URGENCY.containsMatchIn(lower)) add(15, "urgent wording")
        if (THREAT.containsMatchIn(lower) && ACCOUNT_WORDS.containsMatchIn(lower)) add(20, "account threat")
        if (KYC.containsMatchIn(lower) && (hasUrl || URGENCY.containsMatchIn(lower))) add(25, "KYC or ID verification request")
        // Legit KYC notices come from the bank's own sender id and a full link; a shortener is a textbook phishing pattern.
        if (KYC.containsMatchIn(lower) && SHORTENER.containsMatchIn(lower)) add(15, "KYC request with a shortened link")
        if (PRIZE.containsMatchIn(lower)) add(30, "prize or reward claim")
        if (CREDENTIAL_ASK.containsMatchIn(lower)) add(30, "asks for credentials")
        if (INVEST_SCAM.containsMatchIn(lower)) add(20, "investment or loan bait")
        if (CLICK_HERE.containsMatchIn(lower) && hasUrl) add(10, "click prompt")
        if (hasOtp && DO_NOT_SHARE.containsMatchIn(lower)) s -= 30
        if (SENDER_ID_PRESENT_FOR_LEGIT.containsMatchIn(lower) && !hasUrl) s -= 10
        return SpamScore(s.coerceIn(0, 100), why)
    }

    // ------------------------------------------------------------------ replies

    private fun findQuickReplies(text: String, phoneSender: Boolean): List<String> {
        val out = linkedSetOf<String>()
        val first = REPLY_WITH.find(text)
        if (first != null) {
            // "Reply Y to confirm or N to cancel": the first option plus any "or X to ..." that follows it.
            val options = listOf(first.groupValues[1]) +
                REPLY_OR.findAll(text, first.range.last).map { it.groupValues[1] }.toList()
            options.map { it.trim() }
                .filter { it.length in 1..12 && !it.equals("to", true) && !it.equals("with", true) }
                .forEach { out += it.uppercase() }
        }
        if (out.isEmpty() && phoneSender) return emptyList()
        return out.take(3)
    }

    private companion object {
        const val SPAM_THRESHOLD = 55

        val OTP_HINT = Regex("\\b(otp|one[- ]?time|verification|verify|security code|passcode|login code|auth(?:entication)? code|pin|code|password)\\b")
        val OTP_STRONG = Regex("\\b(otp|one[- ]?time|verification code|security code|passcode)\\b")
        val PROMO_CODE_CONTEXT = Regex("\\b(use|apply|promo|coupon|discount|referral|voucher)\\s+(?:the\\s+)?code\\b")
        val OTP_IS_YOUR = Regex("(?i)\\b(\\d{4,8})\\b\\s*(?:is|as|:)?\\s*(?:your|the|an?)\\s+(?:[a-z-]+\\s+){0,3}?(?:otp|code|pin|passcode|password)")
        val OTP_AFTER_KEYWORD = Regex("(?i)(?:otp|code|pin|passcode|password|verification)\\b[^0-9]{0,40}?\\b(\\d{4,8})\\b")
        val OTP_ALNUM = Regex("(?i)(?:otp|code)\\s*(?:is|:)\\s*([A-Z0-9]{5,8})\\b")
        val VALID_FOR = Regex("(?i)(?:valid|expires?|expiry)\\s*(?:for|in|within)?\\s*(\\d{1,3})\\s*(min(?:ute)?s?|seconds?|secs?)")
        val DO_NOT_SHARE = Regex("(do not|don't|never|dont)\\s+(share|disclose|give|reveal)")
        val SENDER_ID_PRESENT_FOR_LEGIT = Regex("(valid for|expires? in|do not share)")
        val CURRENCY_TAIL = Regex("(rs\\.?|inr|[$€£₹]|usd|aud)\\s*$", RegexOption.IGNORE_CASE)

        const val CUR = "(?:rs\\.?|inr|₹|us\\$|a\\$|usd|aud|eur|€|gbp|£|[$])"
        const val NUM = "\\d[\\d,]*(?:\\.\\d{1,2})?"
        val AMOUNT = Regex("(?i)(?<![\\w])($CUR)\\s*($NUM)(?!\\d)|(?<![\\w.,])($NUM)\\s*(rs\\.?|inr|usd|aud|dollars?|rupees?|euros?|pounds?)(?![a-z])")
        val BALANCE = Regex("(?i)(?:avl|avail(?:able)?|bal(?:ance)?|closing)[^0-9a-z]{0,12}(?:bal(?:ance)?)?[^0-9a-z]{0,6}($CUR)?\\s*($NUM)")
        val BALANCE_LEAD = Regex("(avl|avail|available|bal|balance|limit|outstanding)[^a-z0-9]{0,14}$")
        val LAST4 = Regex("(?i)(?:a/c|acct|account|card|ending|no\\.?|xx+|\\*+)\\s*[*xX]*\\s*(\\d{3,4})\\b")
        /** A masked card number ("4111xxxx", "4111 **** 1234") — the tell-tale of a card charge that names no account. */
        val CARD_MASKED = Regex("\\b\\d{3,4}[xX*]{2,}\\b|\\b\\d{4}\\s+\\*{4}\\s+\\d{4}\\b")
        val VPA = Regex("[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}")
        /** A bank line that opens with the merchant and a colon: "ZOMATO: Rs 120.00 …". */
        val MERCHANT_FIRST = Regex("(?i)^\\s*([A-Za-z][A-Za-z0-9&*'./ -]{1,31}?)\\s*[:;]\\s")
        val MERCHANT = Regex(
            "(?i)(\\bat|\\bto|\\btowards|\\bfrom|\\bby|\\bfor|\\binfo:?|\\bmerchant:?)\\s+([A-Za-z0-9&*'./ -]{2,40}?)(?=\\s+(?:on|via|ref|using|from|upi|avl|bal|available|txn|trf|card|a/c|acct|at|dated)\\b|[.,;(]|\\s*$)"
        )
        val ACCOUNT_LIKE = Regex("(?i)^(a/c|acct|account|card|xx|x{2,}|\\*)|\\d{4,}")
        val MERCHANT_STOPWORDS = setOf(
            "your", "you", "the", "a", "an", "account", "card", "bank", "a/c", "acct", "me", "info", "upi", "sms",
            "dear", "dear customer", "hi", "hello", "note", "alert", "update", "payment", "txn",
        )

        val DEBIT_WORDS = Regex("\\b(debited|spent|paid|purchase of|purchased|withdrawn|withdrawal|sent|payment of|txn of|used at|charged|debit of)\\b")
        val CREDIT_WORDS = Regex("\\b(credited|received|deposited|refund(?:ed)?|cashback of|credit of|salary)\\b")
        val CREDIT_ONLY = Regex("\\b(credited|deposited|received)\\b")
        val PAST_TXN = Regex("\\b(debited|credited|spent|paid|withdrawn|deposited|received|purchase|charged|transferred|refunded|payment of|txn)\\b")
        val ACCOUNT_CONTEXT = Regex("\\b(a/c|acct|account|card|upi|wallet|neft|imps|rtgs|atm)\\b")
        val PROMO_FINANCE = Regex("\\b(get|win|earn|apply|offer|eligible|pre-?approved|loan|upto|up to)\\b")

        val BILL_WORDS = Regex("\\b(bill|due date|payment due|amount due|outstanding|statement|premium|emi|recharge|renewal|invoice|overdue|minimum due)\\b")
        val DUE_WORDS = Regex("\\b(due|payable|overdue|pay (?:by|before)|last date)\\b")
        val EXPIRY_WORDS = Regex("\\b(expires?|expiry|expiring|valid (?:till|until|upto))\\b")
        val APPOINTMENT = Regex("\\b(appointment|slot|booked|scheduled|consultation|visit)\\b")
        val TRAVEL_WORDS = Regex("\\b(pnr|flight|boarding|train|irctc|bus ticket|e-?ticket|departure|arrival|itinerary|check-?in|seat|journey|trip)\\b")
        val ORDER_WORDS = Regex("\\b(order|shipped|dispatched|out for delivery|delivered|awb|tracking|courier|consignment|parcel|shipment|your package)\\b")
        val GOV_WORDS = Regex("\\b(aadhaar|aadhar|uidai|pan card|income tax|gst|govt|government|e-?challan|passport|electoral|voter|mygov|medicare|centrelink|epf|pension|municipal)\\b")
        val HEALTH_WORDS = Regex("\\b(doctor|dr\\.|hospital|clinic|lab report|prescription|pharmacy|medicine|vaccin|health|insurance claim|diagnostic|appointment|consultation)\\b")
        val EDU_WORDS = Regex("\\b(exam|semester|admission|school|college|university|course|class(?:es)?|tuition|timetable|marks|scholarship)\\b")
        val JOB_WORDS = Regex("\\b(interview|hiring|vacancy|job alert|recruit|resume|shortlisted|offer letter|your application|career)\\b")
        // Only the multi-word and telecom-specific forms: "plan", "usage" and "balance" are everyday words in personal
        // texts and would sort a chat with a person under Services.
        val SERVICE_WORDS = Regex("\\b(data pack|recharge|electricity|water bill|gas bill|broadband|booking id|ticket id|login alert|new device)\\b")
        val PROMO_STRONG = Regex("\\b(sale|discount|% off|flat \\d+|cashback offer|coupon|shop now|limited time|exclusive offer|t&c apply|reply stop|opt[- ]?out|unsubscribe|mega|deal of)\\b")
        val PROMO_WEAK = Regex("\\b(offer|free|save|extra|win|buy|get|upto|up to|enjoy|hurry|now)\\b")

        val URL = Regex("(https?://|www\\.)[^\\s]+|\\b[a-z0-9-]+\\.(?:com|in|co|net|org|xyz|top|click|link|info|me|ly|gl)/[^\\s]*")
        val TRACKING_URL = Regex("(?i)https?://[^\\s]*(?:track|trk|awb|shipment|parcel|delhivery|bluedart|dtdc|auspost|fedex|dhl|ups)[^\\s]*")
        val SHORTENER = Regex("\\b(bit\\.ly|tinyurl\\.com|cutt\\.ly|t\\.co|rb\\.gy|is\\.gd|shorturl\\.at|goo\\.gl|ow\\.ly|tiny\\.cc|s\\.id|rebrand\\.ly|bitly)\\b")
        val URGENCY = Regex("\\b(urgent|immediately|within 24 hours|today only|last chance|act now|expires? today|final notice|right away|asap)\\b")
        val THREAT = Regex("\\b(suspended|blocked|deactivated|frozen|legal action|arrest|penalty|unauthori[sz]ed|will be closed|disconnected)\\b")
        val ACCOUNT_WORDS = Regex("\\b(account|a/c|bank|card|kyc|wallet|sim|electricity|service)\\b")
        val KYC = Regex("\\b(kyc|pan (?:card )?(?:update|verification|link)|aadhaar (?:update|link)|verify your identity|update your (?:pan|aadhaar|details))\\b")
        val PRIZE = Regex("\\b(you (?:have )?won|winner|lottery|prize|reward points|gift card|congratulations|lucky draw|claim your)\\b")
        val CREDENTIAL_ASK = Regex("\\b(enter your (?:password|pin|otp|card)|share your (?:otp|pin|password)|send your (?:otp|pin)|confirm your card|reply with your (?:otp|pin))\\b")
        val INVEST_SCAM = Regex("\\b(guaranteed returns?|double your|crypto|forex|binary options|pre-?approved loan|instant loan|work from home|earn \\$?\\d+ ?(?:per|a) day)\\b")
        val CLICK_HERE = Regex("\\b(click here|tap (?:the )?link|click (?:the )?link|click below|open (?:the )?link|visit (?:the )?link)\\b")
        val REPLY_WITH = Regex("(?i)\\breply\\s+(?:with\\s+)?[\"']?([A-Za-z0-9]{1,12})[\"']?\\s+(?:to|for|if)\\b")
        val REPLY_OR = Regex("(?i)\\b(?:or|,)\\s+[\"']?([A-Za-z0-9]{1,12})[\"']?\\s+(?:to|for|if)\\b")

        val NUMERIC_DATE = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b")
        val NAMED_DATE_DMY = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?[\\s-]+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\\s,.-]*(\\d{2,4})?\\b")
        val NAMED_DATE_MDY = Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s*(\\d{4}))?\\b")
        val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        // Relative dates. Matched against the lowercased text: "within" before "in" so the shorter word never wins.
        val IN_DAYS = Regex("(?:within|in)\\s+(\\d{1,9})\\s+days?\\b")
        val NEXT_WEEKDAY = Regex("\\bnext\\s+(monday|mon|tuesday|tues|wednesday|wed|thursday|thurs|thu|friday|fri|saturday|sat|sunday|sun)\\b")
        val BY_OR_ON_WEEKDAY = Regex("\\b(?:by|on)\\s+(monday|mon|tuesday|tues|wednesday|wed|thursday|thurs|thu|friday|fri|saturday|sat|sunday|sun)\\b")
        val ORDINAL_DATE = Regex("\\b(?:by|on)\\s+the\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")
        // Times of day. "5.30pm" is the common Australian writing; "by 18:30" the 24-hour one.
        val TIME_COLON_AMPM = Regex("(?i)\\b(\\d{1,2})(?::|\\.)(\\d{2})\\s*(am|pm)\\b")
        val TIME_SIMPLE_AMPM = Regex("(?i)\\b(\\d{1,2})\\s*(am|pm)\\b")
        val TIME_24 = Regex("(?i)\\b(?:at|by)\\s+(\\d{1,2}):(\\d{2})\\b")
    }
}
