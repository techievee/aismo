package aismo.sms.domain.rephrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import aismo.sms.domain.rephrase.RephraseGuard.Verdict

class RephraseGuardTest {
    private fun accepted(mode: RephraseMode, draft: String, output: String): String =
        (RephraseGuard.judge(mode, draft, output) as? Verdict.Accept)?.text ?: error("expected accept, got ${RephraseGuard.judge(mode, draft, output)}")

    /** For tests written as one expression: JUnit needs a method that returns nothing. */
    private fun passes(mode: RephraseMode, draft: String, output: String) { accepted(mode, draft, output) }

    private fun rejection(mode: RephraseMode, draft: String, output: String): String =
        (RephraseGuard.judge(mode, draft, output) as? Verdict.Reject)?.reason ?: error("expected reject, got ${RephraseGuard.judge(mode, draft, output)}")

    // ------------------------------------------------------------------ what must survive

    @Test fun anAmountThatSurvivesIsAccepted() =
        assertEquals("Hi, a reminder that $142.30 is overdue.", accepted(RephraseMode.FRIENDLY, "Your payment of $142.30 is overdue.", "Hi, a reminder that $142.30 is overdue."))

    @Test fun aLostAmountIsRejected() = assertTrue(rejection(RephraseMode.POLITE, "u still owe me $50 from last week", "You still owe me money from last week.").contains("50"))

    @Test fun aLostTimeIsRejected() = assertTrue(rejection(RephraseMode.SHORTER, "cant make it tmrw, got a dentist apt at 2:30 will call u after", "Can't make it, dentist, will call.").contains("2:30"))

    @Test fun aTimeWrittenWithPmStillMatches() =
        passes(RephraseMode.POLITE, "pls send the invoice, need it by 5pm", "Please send the invoice. I need it by 5 pm.")

    @Test fun thousandsSeparatorsDoNotMatter() = passes(RephraseMode.FIX, "u owe me 1200 dollars", "You owe me 1,200 dollars.")

    @Test fun aPhoneNumberMustBeKept() =
        assertTrue(rejection(RephraseMode.SHORTER, "hi its sam from the gym, ur membership expires on 3 dec, call us on 02 9555 1234", "Hi Sam, membership expires Dec 3.").startsWith("the rewrite lost"))

    @Test fun aLinkMustBeKeptExactly() {
        accepted(RephraseMode.FRIENDLY, "pay here www.pay-now.com/inv/42 thx", "Thanks! You can pay here: www.pay-now.com/inv/42")
        assertTrue(rejection(RephraseMode.POLITE, "pay here www.pay-now.com/inv/42 thx", "Please pay online. Thank you.").startsWith("the rewrite lost"))
    }

    @Test fun anEmailAddressMustBeKept() = assertTrue(rejection(RephraseMode.POLITE, "mail me at jo@example.com", "Please email me.").contains("jo@example.com"))

    @Test fun aWeekdayMustBeKept() =
        assertTrue(rejection(RephraseMode.SHORTER, "Please confirm if you can attend the meeting on Thursday 21 Nov at 3pm", "Please confirm: meeting 21 Nov at 3pm.").lowercase().contains("thu"))

    @Test fun anAbbreviatedWeekdayCountsAsTheSameDay() = passes(RephraseMode.POLITE, "cant do thurs 3pm", "I'm unable to attend on Thursday at 3pm.")

    @Test fun aMonthNextToANumberMustBeKept() {
        assertTrue(rejection(RephraseMode.SHORTER, "your licence expires on 21 Nov so renew soon please", "Your licence expires on 21. Renew soon.").contains("nov"))
        accepted(RephraseMode.SHORTER, "your licence expires on 21 Nov so renew soon please", "Licence expires 21 November, renew soon.")
    }

    @Test fun aWordThatMerelyStartsLikeAMonthIsNotAMonth() =
        passes(RephraseMode.FRIENDLY, "got 2 markets to visit", "Hi! I've got 2 shops to visit.")

    @Test fun aWordThatMerelyContainsADayNameIsNotADay() = passes(RephraseMode.FRIENDLY, "the sunny day was nice", "It was a lovely sunny day.")

    // ------------------------------------------------------------------ what must not appear

    @Test fun aNumberThatWasNotInTheDraftIsAnInvention() =
        assertTrue(rejection(RephraseMode.FRIENDLY, "can u call me later", "Hi! Could you call me at 5 pm?").contains("5"))

    @Test fun anEmojiIsRejectedWhenTheDraftHadNone() = assertTrue(rejection(RephraseMode.FRIENDLY, "thanks for the lift", "Thanks so much for the lift! 😊").contains("emoji"))

    @Test fun anEmojiTheDraftAlreadyHadIsFine() = passes(RephraseMode.FIX, "thx for the lift 😊", "Thanks for the lift 😊")

    @Test fun aRewriteFarLongerThanTheMessageIsRejected() =
        assertTrue(rejection(RephraseMode.FRIENDLY, "call me", "Hello there! I was just hoping, if it is not too much trouble at all, that you might find a spare moment to give me a call when you can, thank you so very much indeed.").contains("longer"))

    @Test fun twoParagraphsAreMoreThanOneMessage() = assertTrue(rejection(RephraseMode.POLITE, "send it pls", "Please send it.\n\nThank you for your time.").contains("one message"))

    @Test fun theInstructionsEchoedBackAreRejected() =
        assertTrue(rejection(RephraseMode.FRIENDLY, "u still owe me 50", "Fix the spelling and grammar. Keep the wording otherwise.").contains("instructions"))

    @Test fun aBlankAnswerIsRejected() = assertTrue(rejection(RephraseMode.POLITE, "send it pls", "   \n ").contains("no answer"))

    // ------------------------------------------------------------------ names, speakers, mood

    @Test fun aNameInTheMiddleOfTheDraftMustBeKept() {
        assertTrue(rejection(RephraseMode.SHORTER, "your appointment with Dr Lee is confirmed for 14 Jan at 10:30", "Appointment 14 Jan 10:30 confirmed.").contains("lee"))
        passes(RephraseMode.SHORTER, "your appointment with Dr Lee is confirmed for 14 Jan at 10:30", "Appointment with Dr Lee confirmed 14 Jan 10:30.")
    }

    @Test fun anAllCapsKeywordMustBeKept() =
        assertTrue(rejection(RephraseMode.SHORTER, "Your parcel is ready for collection today. Reply STOP to cancel alerts", "Your parcel is ready today.").contains("stop"))

    @Test fun aCapitalisedFirstWordOfASentenceIsNotAName() =
        passes(RephraseMode.FRIENDLY, "Running late. Sorry about that", "Hi! I'm running late, sorry about that.")

    @Test fun aGreetingIsNotAName() = passes(RephraseMode.SHORTER, "Hello there, can you send the file please when you get a chance", "Can you send the file when you can?")

    @Test fun speakingAsMyWhereTheDraftDidNotIsRejected() =
        assertTrue(rejection(RephraseMode.FRIENDLY, "hi its sam from the gym, ur membership expires 3 dec", "Hi, I'm from the gym and my membership expires 3 Dec.").contains("my"))

    @Test fun swappingTheReaderForTheWriterIsRejected() {
        assertTrue(rejection(RephraseMode.POLITE, "u still owe 50 dollars", "I still owe 50 dollars.").contains("swaps"))
        assertTrue(rejection(RephraseMode.POLITE, "I will pay 50 dollars", "You will pay 50 dollars.").contains("swaps"))
    }

    @Test fun keepingBothPartiesIsNotASwap() = passes(RephraseMode.FRIENDLY, "can u call me later", "Hi! Could you call me when you get a chance?")

    @Test fun fixAndShorterMayNotTurnAStatementIntoAQuestion() {
        assertTrue(rejection(RephraseMode.SHORTER, "your appointment is confirmed for 14 jan at 10:30 so please arrive early", "Appointment 14 Jan 10:30?").contains("question"))
        assertTrue(rejection(RephraseMode.FIX, "see u at 7 ok", "See you at 7, okay?").contains("question"))
    }

    @Test fun anInformalQuestionMayGainItsQuestionMark() {
        passes(RephraseMode.FIX, "cn u call me tomoro", "Can you call me tomorrow?")
        passes(RephraseMode.FIX, "where r u", "Where are you?")
    }

    @Test fun monthAndWeekdayNamesAreNotTreatedAsNames() = passes(RephraseMode.POLITE, "pls come on Friday or 21 Nov", "Please come on Friday or 21 November.")

    @Test fun politeAndFriendlyMayAskARequestAsAQuestion() = passes(RephraseMode.POLITE, "send me the file", "Could you please send me the file?")

    // ------------------------------------------------------------------ changes and no changes

    @Test fun anEchoIsRejectedForEveryModeExceptFix() {
        assertTrue(rejection(RephraseMode.POLITE, "send it pls", "send it pls").contains("nothing"))
        assertEquals(Verdict.Unchanged, RephraseGuard.judge(RephraseMode.FIX, "Send it please.", "Send it please."))
    }

    @Test fun differencesOnlyInCaseAndPunctuationCountAsNoChange() =
        assertEquals(Verdict.Unchanged, RephraseGuard.judge(RephraseMode.FIX, "see you at 7", "See you at 7!"))

    @Test fun shorterMustBeShorter() {
        assertTrue(rejection(RephraseMode.SHORTER, "can u pls send me the file", "Could you please send me the file when you get a chance?").contains("not shorter"))
        accepted(RephraseMode.SHORTER, "can u pls send me the file when u get a chance thx", "Send me the file, thanks?")
    }

    @Test fun otherModesMayBeLonger() = passes(RephraseMode.FRIENDLY, "send the file", "Could you please send the file when you can? Thanks!")

    // ------------------------------------------------------------------ cleaning

    @Test fun cleaningRemovesQuotesLabelsAndThinking() {
        assertEquals("Thanks for the lift.", RephraseCleaner.clean("\"Thanks for the lift.\""))
        assertEquals("Thanks for the lift.", RephraseCleaner.clean("Here is the rewritten message: Thanks for the lift."))
        assertEquals("Thanks for the lift.", RephraseCleaner.clean("Rewritten message: \"Thanks for the lift.\""))
        assertEquals("Thanks for the lift.", RephraseCleaner.clean("<think>I should be polite</think>\nThanks for the lift."))
        assertEquals("Thanks for the lift.", RephraseCleaner.clean("  “Thanks for the lift.”  "))
    }

    @Test fun cleaningKeepsAnInnerQuoteAndRealNewlines() {
        assertEquals("He said \"yes\" to dinner.", RephraseCleaner.clean("He said \"yes\" to dinner."))
        assertEquals("Line one.\nLine two.", RephraseCleaner.clean("Line one.\nLine two."))
    }

    @Test fun aLabelIsRemovedBeforeTheGuardSeesTheText() =
        assertEquals("Hi! Where are you?", accepted(RephraseMode.FRIENDLY, "where r u", "Message: Hi! Where are you?"))

    // ------------------------------------------------------------------ "fix everything"

    @Test fun fixEverythingMayWriteOutMonthsAndOrdinalsAndKeepsTheFacts() {
        passes(RephraseMode.POLISH, "hi its sam from the gym, ur membership expires on 3 dec, call us on 02 9555 1234",
            "Hi, it's Sam from the gym, your membership expires on 3 December. Call us on 02 9555 1234.")
        passes(RephraseMode.POLISH, "Your appointment with Dr Lee is confirmed for 14 Jan at 10:30. Reply STOP to cancel.",
            "Your appointment with Dr. Lee is confirmed for 14th January at 10:30. Reply STOP to cancel.")
    }

    @Test fun fixEverythingStillMayNotLoseAnAmountOrAName() {
        assertTrue(rejection(RephraseMode.POLISH, "u still owe me $50 from last week", "You still owe me money from last week.").contains("50"))
        assertTrue(rejection(RephraseMode.POLISH, "sorry Sam cant make it", "Sorry, I can't make it.").contains("sam"))
    }

    @Test fun fixEverythingOfAnAlreadyCleanMessageIsNothingToFix() =
        assertEquals(Verdict.Unchanged, RephraseGuard.judge(RephraseMode.POLISH, "Your payment is overdue.", "Your payment is overdue."))

    @Test fun fixEverythingMayNotTurnAStatementIntoAQuestionButMayPunctuateAnInformalOne() {
        assertTrue(rejection(RephraseMode.POLISH, "see u at 7 ok", "See you at 7, okay?").contains("question"))
        passes(RephraseMode.POLISH, "cn u call me tomoro", "Can you call me tomorrow?")
    }

    @Test fun onlyTheCorrectingModesAreSaidToCorrect() {
        assertEquals(setOf(RephraseMode.POLISH, RephraseMode.FIX), RephraseMode.entries.filter { it.corrects }.toSet())
        assertEquals("the most useful choice is listed first", RephraseMode.POLISH, RephraseMode.entries.first())
    }
}
