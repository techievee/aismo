package aismo.sms.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.SmsCategory
import java.time.LocalDate
import java.time.ZoneId

class SmsParserTest {
    private val zone = ZoneId.of("UTC")
    private val parser = SmsParser(dollarCurrency = "AUD", zone = zone)
    private val now = LocalDate.of(2026, 11, 18).atStartOfDay(zone).toInstant().toEpochMilli()
    /** 2026-11-21 10:00 UTC — a Saturday; the relative-date tests anchor to it so their expectations stay stable. */
    private val satNow = LocalDate.of(2026, 11, 21).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun parse(address: String, body: String, rules: List<UserRule> = emptyList()) =
        parser.parse(address, body, now, rules)

    private fun rel(address: String, body: String) = parser.parse(address, body, satNow)

    private fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun otpIsDetectedWithCodePurposeAndValidity() {
        val r = parse("VM-HDFCBK", "123456 is your OTP for login to NetBanking. Valid for 10 minutes. Do not share it with anyone.")
        assertEquals(SmsCategory.OTP.key, r.categoryKey)
        assertEquals("123456", r.otp?.code)
        assertEquals("login", r.otp?.purpose)
        assertEquals(10, r.otp?.validForMinutes)
    }

    @Test fun otpAfterKeyword() {
        val r = parse("AX-AMAZON", "Your Amazon verification code: 482913. Do not share this code.")
        assertEquals("482913", r.otp?.code)
        assertEquals(SmsCategory.OTP.key, r.categoryKey)
    }

    @Test fun promoCodeIsNotAnOtp() {
        val r = parse("VK-SHOPPE", "Mega sale! Use code SAVE50 at checkout and get 50% off. Shop now. T&C apply")
        assertNull(r.otp)
        assertEquals(SmsCategory.PROMO.key, r.categoryKey)
    }

    @Test fun amountIsNeverMistakenForOtpCode() {
        val r = parse("VM-HDFCBK", "Rs 4500.00 debited from a/c XX1234 on 18-11-26. Info: ZOMATO. Avl bal Rs 10,200.50")
        assertNull(r.otp)
    }

    @Test fun debitTransactionIsExtractedWithBalanceAndAccount() {
        val r = parse("VM-HDFCBK", "Rs 4500.00 debited from a/c XX1234 on 18-11-26. Info: ZOMATO. Avl bal Rs 10,200.50")
        assertEquals(SmsCategory.FINANCE.key, r.categoryKey)
        val t = r.transaction ?: error("transaction not parsed")
        assertEquals(TxnKind.DEBIT, t.kind)
        assertEquals(450000L, t.amountMinor)
        assertEquals("INR", t.currency)
        assertEquals("1234", t.last4)
        assertEquals(1020050L, t.balanceMinor)
        assertEquals("Zomato", t.merchant)
    }

    @Test fun creditTransaction() {
        val r = parse("CBA", "You received \$250.00 from J SMITH into account 4321. Available balance \$1,000.00")
        val t = r.transaction!!
        assertEquals(TxnKind.CREDIT, t.kind)
        assertEquals(25000L, t.amountMinor)
        assertEquals("AUD", t.currency)
        assertEquals(100000L, t.balanceMinor)
    }

    @Test fun upiPaymentUsesVpaOrMerchant() {
        val r = parse("AX-PAYTMB", "Paid Rs.120 to ramesh@okaxis via UPI. Ref 99887766.")
        val t = r.transaction!!
        assertEquals(TxnKind.DEBIT, t.kind)
        assertEquals(12000L, t.amountMinor)
        assertEquals("UPI", t.instrument)
    }

    @Test fun loanOfferIsNotATransaction() {
        val r = parse("VM-LOANZZ", "Get pre-approved loan up to Rs 5,00,000 instantly. Apply now!")
        assertNull(r.transaction)
    }

    @Test fun billDueProducesReminderWithDate() {
        val r = parse("VM-JIOPAY", "Your bill of Rs 799.00 is due on 25-11-2026. Pay now to avoid late fee.")
        assertEquals(SmsCategory.BILL.key, r.categoryKey)
        val rem = r.reminders.single { it.kind == ReminderKind.BILL_DUE }
        assertEquals(79900L, rem.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 25).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), rem.dueAt)
    }

    @Test fun namedDateWithoutYearRollsToNearestFuture() {
        val r = parse("VM-ELECTR", "Electricity bill payable Rs 1200. Due date 05 Dec.")
        val rem = r.reminders.single { it.kind == ReminderKind.BILL_DUE }
        assertEquals(LocalDate.of(2026, 12, 5).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), rem.dueAt)
    }

    @Test fun orderWithTrackingLink() {
        val r = parse("AX-AMAZON", "Your order #123 has been shipped. Track your parcel: https://track.example.com/awb/998877")
        assertEquals(SmsCategory.ORDER.key, r.categoryKey)
        assertEquals(1, r.trackingUrls.size)
    }

    @Test fun travelBooking() {
        val r = parse("VM-IRCTC", "PNR 1234567890 confirmed. Train 12951 departure 20-11-2026 seat S4/32.")
        assertEquals(SmsCategory.TRAVEL.key, r.categoryKey)
        assertTrue(r.reminders.any { it.kind == ReminderKind.TRAVEL })
    }

    @Test fun phishingIsSpam() {
        val r = parse("+919876543210", "URGENT: Your bank account will be suspended today. Update KYC now at http://bit.ly/3xYz12 or lose access.")
        assertEquals(SmsCategory.SPAM.key, r.categoryKey)
        assertTrue(r.spamScore >= 55)
        assertTrue(r.spamReasons.isNotEmpty())
    }

    @Test fun legitOtpIsNotSpamEvenWithAccountWords() {
        val r = parse("VM-HDFCBK", "Use OTP 554433 to verify your bank account login. Do not share this OTP with anyone.")
        assertEquals(SmsCategory.OTP.key, r.categoryKey)
        assertFalse(r.spamScore >= 55)
    }

    @Test fun personalMessageFromPhoneNumber() {
        val r = parse("+61412345678", "Hey, are we still on for lunch tomorrow?")
        assertEquals(SmsCategory.PERSONAL.key, r.categoryKey)
        assertTrue(r.quickReplies.isEmpty())
    }

    @Test fun aGroupOfPhoneNumbersIsPersonalNotAnUnknownBusinessSender() {
        val r = parse("+61411000001,+61411000002,+61411000003", "Dinner at 7? Table is booked.")
        assertEquals(SmsCategory.PERSONAL.key, r.categoryKey)
        assertNull(r.merchant)
    }

    @Test fun groupKeysAreOrderIndependentAndDistinctFromSingleConversations() {
        assertEquals(ConversationKeys.of("+61411000001,+61411000002"), ConversationKeys.of("+61411000002, +61411000001"))
        assertTrue(ConversationKeys.of("+61411000001,+61411000002").startsWith("g:"))
        assertEquals("the same person written two ways is not a group", ConversationKeys.of("+61411000001"), ConversationKeys.of("+61411000001,0411000001"))
        assertTrue(ConversationKeys.canReply("+61411000001,+61411000002"))
        assertFalse(ConversationKeys.canReply("VM-HDFCBK"))
        assertFalse("a group containing a business sender cannot be replied to", ConversationKeys.canReply("+61411000001,VM-HDFCBK"))
    }

    @Test fun quickRepliesAreExtractedFromReplyInstructions() {
        val r = parse("VM-CLINIC", "Your appointment is on 20-11-2026. Reply Y to confirm or N to cancel.")
        assertTrue(r.quickReplies.containsAll(listOf("Y", "N")))
        assertEquals(SmsCategory.HEALTH.key, r.categoryKey)
    }

    @Test fun userRuleOverridesEverything() {
        val rule = UserRule(senderContains = "SHOPPE", bodyContains = null, categoryKey = "custom:abc12345", merchant = "My Shop")
        val r = parse("VK-SHOPPE", "Mega sale! 50% off. Shop now.", listOf(rule))
        assertEquals("custom:abc12345", r.categoryKey)
        assertEquals("My Shop", r.merchant)
    }

    @Test fun unknownBusinessSenderIsLowConfidenceOther() {
        val r = parse("VM-QWERTY", "Hello from us.")
        assertEquals(SmsCategory.OTHER.key, r.categoryKey)
        assertTrue(r.lowConfidence)
    }

    @Test fun conversationKeysMergeCarrierPrefixesAndNumberFormats() {
        assertEquals(ConversationKeys.of("VM-HDFCBK"), ConversationKeys.of("AX-HDFCBK"))
        assertEquals(ConversationKeys.of("+61 412 345 678"), ConversationKeys.of("0412345678"))
        assertEquals(ConversationKeys.of("+61412345678"), ConversationKeys.of("0412 345 678"))
        assertEquals(ConversationKeys.of("+919876543210"), ConversationKeys.of("9876543210"))
        assertTrue(ConversationKeys.of("HDFCBK").startsWith("s:"))
    }

    // -- Everyday words from a person must not look like marketing or a service notice ---------------------------

    @Test fun everydayWordsFromAPersonStayPersonal() {
        val bodies = listOf(
            "What's your plan for tonight? I'm free after 6",
            "I can give you a free lift to the city now",
            "my data usage is over, I'll top up later",
            "let's grab lunch, it's free if we go before noon",
        )
        for (body in bodies) {
            val r = rel("+61412345678", body)
            assertEquals("the body '$body' should be personal", SmsCategory.PERSONAL.key, r.categoryKey)
            assertTrue(r.reminders.isEmpty())
        }
    }

    @Test fun aDoctorsAppointmentOnANamedWeekdayIsHealthWithADueDate() {
        val r = rel("+61402333444", "the doctor said my appointment is on Friday")
        assertEquals(SmsCategory.HEALTH.key, r.categoryKey)
        // 2026-11-21 is a Saturday, so "on Friday" is the next Friday, six days out.
        assertEquals(at(LocalDate.of(2026, 11, 27), 9, 0), r.reminders.single { it.kind == ReminderKind.APPOINTMENT }.dueAt)
    }

    // -- Relative dates and times are the spans the old engine missed -------------------------------------------

    @Test fun dueTodayKeepsTheStatedTime() {
        val r = rel("AGL", "Your electricity bill of \$89.40 is due today at 11:59pm.")
        assertEquals(SmsCategory.BILL.key, r.categoryKey)
        assertEquals(at(LocalDate.of(2026, 11, 21), 23, 59), r.reminders.single { it.kind == ReminderKind.BILL_DUE }.dueAt)
    }

    @Test fun payableInTwoDaysBecomesDueInTwoDays() {
        val r = rel("TPG", "Your mobile bill of \$60.00 is payable in 2 days.")
        assertEquals(at(LocalDate.of(2026, 11, 23), 9, 0), r.reminders.single { it.kind == ReminderKind.BILL_DUE }.dueAt)
    }

    @Test fun aDeliveryTomorrowAtSixPmKeepsTheTimeAndTheTrackingLink() {
        val r = rel("AMZN-AU", "Your order has shipped. It will arrive tomorrow at 6pm. Track: https://a.co/track123")
        assertEquals(SmsCategory.ORDER.key, r.categoryKey)
        assertEquals(at(LocalDate.of(2026, 11, 22), 18, 0), r.reminders.single { it.kind == ReminderKind.DELIVERY }.dueAt)
        assertEquals(1, r.trackingUrls.size)
    }

    @Test fun aFlightDepartingTomorrowCarriesTheExactTime() {
        val r = rel("QANTAS", "Flight QF117 departs tomorrow at 9:15am. Check in online.")
        assertEquals(SmsCategory.TRAVEL.key, r.categoryKey)
        assertEquals(at(LocalDate.of(2026, 11, 22), 9, 15), r.reminders.single { it.kind == ReminderKind.TRAVEL }.dueAt)
    }

    @Test fun aClinicConsultationTomorrowWithReplyCodes() {
        val r = rel("WALKIN", "Your consultation is tomorrow at 10am. Reply C to confirm or X to cancel.")
        assertEquals(SmsCategory.HEALTH.key, r.categoryKey)
        assertEquals(at(LocalDate.of(2026, 11, 22), 10, 0), r.reminders.single { it.kind == ReminderKind.APPOINTMENT }.dueAt)
        assertTrue(r.quickReplies.containsAll(listOf("C", "X")))
    }

    @Test fun a24HourClockTimeSurvivesTheBill() {
        val r = rel("AGL", "Bill of \$45 due by 18:30 on 22/11/2026.")
        assertEquals(at(LocalDate.of(2026, 11, 22), 18, 30), r.reminders.single { it.kind == ReminderKind.BILL_DUE }.dueAt)
    }

    // -- Spam and transaction spans that used to fall through -----------------------------------------------------

    @Test fun aKycNoticeWithAShortenedLinkIsSpam() {
        val r = rel("CBA", "Verify your KYC at tinyurl.com/kyc99 to keep your account active.")
        assertEquals(SmsCategory.SPAM.key, r.categoryKey)
        assertTrue(r.spamScore >= 55)
        assertNull(r.transaction)
    }

    @Test fun aMerchantFirstDebitWithNoVerbIsStillATransaction() {
        // Some banks put the merchant first and use no verb: "ZOMATO: Rs 120.00 21/11 5:30pm 4111xxxx".
        val r = rel("ICICIBANK", "ZOMATO: Rs 120.00 21/11 5:30pm 4111xxxx")
        assertEquals(SmsCategory.FINANCE.key, r.categoryKey)
        val t = r.transaction ?: error("transaction not parsed")
        assertEquals(TxnKind.DEBIT, t.kind)
        assertEquals(12000L, t.amountMinor)
        assertEquals("INR", t.currency)
        assertEquals("Zomato", t.merchant)
    }

    @Test fun aPlainDollarsWordGetsTheConfiguredCurrency() {
        val r = rel("+61404555666", "your refund of 250 dollars was credited, the bank said")
        val t = r.transaction ?: error("transaction not parsed")
        assertEquals(TxnKind.CREDIT, t.kind)
        assertEquals(25000L, t.amountMinor)
        assertEquals("AUD", t.currency)
    }
}
