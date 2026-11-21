package aismo.sms.domain.rephrase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import aismo.sms.domain.llm.ChatMessage
import aismo.sms.domain.llm.GenerationException
import aismo.sms.domain.llm.TextGenerator

class RephraserTest {
    private val prompts = RephrasePrompts.parse(File("src/main/assets/rephrase/prompts.json").readText())

    /** Answers from a script and records how it was asked. */
    private class Scripted(private val answers: List<Any>) : TextGenerator {
        val asked = mutableListOf<Triple<List<ChatMessage>, Int, Float>>()
        override suspend fun generate(messages: List<ChatMessage>, maxTokens: Int, temperature: Float, seed: Int): String {
            asked += Triple(messages, maxTokens, temperature)
            val next = answers[minOf(asked.size - 1, answers.lastIndex)]
            if (next is Throwable) throw next
            return next as String
        }
        override suspend fun close() {}
    }

    private fun run(mode: RephraseMode, draft: String, vararg answers: Any): Pair<RephraseOutcome, Scripted> {
        val generator = Scripted(answers.toList())
        return runBlocking { Rephraser(prompts, generator).rephrase(mode, draft) } to generator
    }

    @Test fun aGoodAnswerIsOfferedAfterOneAttempt() {
        val (outcome, generator) = run(RephraseMode.POLITE, "send it pls", "Please send it.")
        assertEquals(RephraseOutcome.Suggestion("Please send it.", 1), outcome)
        assertEquals(1, generator.asked.size)
        assertEquals(prompts.sampling.temperature, generator.asked.single().third)
    }

    @Test fun aRejectedAnswerIsRetriedHotterAndTheSecondOneIsOffered() {
        val (outcome, generator) = run(RephraseMode.POLITE, "pay me $50 pls", "Please pay me.", "Please pay me $50.")
        assertEquals(RephraseOutcome.Suggestion("Please pay me $50.", 2), outcome)
        assertEquals(listOf(prompts.sampling.temperature, prompts.sampling.retryTemperature), generator.asked.map { it.third })
    }

    @Test fun twoRejectionsLeaveTheDraftAlone() {
        val (outcome, generator) = run(RephraseMode.POLITE, "pay me $50 pls", "Please pay me.", "Kindly pay me.")
        assertTrue(outcome is RephraseOutcome.Declined)
        assertEquals(2, generator.asked.size)
        assertTrue((outcome as RephraseOutcome.Declined).detail.contains("50"))
    }

    @Test fun shorterExplainsItselfWhenItCannotShortenSafely() {
        val (outcome, _) = run(RephraseMode.SHORTER, "call me at 5 on 0412 345 678", "Call me.")
        assertTrue((outcome as RephraseOutcome.Declined).message.contains("shortened"))
    }

    @Test fun fixReportsThatThereWasNothingToFix() {
        val (outcome, generator) = run(RephraseMode.FIX, "Send it please.", "Send it please.")
        assertEquals(RephraseOutcome.NothingToFix, outcome)
        assertEquals("no retry is needed", 1, generator.asked.size)
    }

    @Test fun fixEverythingReportsThatThereWasNothingToFixToo() {
        val (outcome, generator) = run(RephraseMode.POLISH, "Send it please.", "Send it please.")
        assertEquals(RephraseOutcome.NothingToFix, outcome)
        assertEquals(1, generator.asked.size)
    }

    @Test fun aModelThatCannotRunIsReportedAndNotRetried() {
        val (outcome, generator) = run(RephraseMode.FIX, "cn u call me", GenerationException("the model file is missing"))
        assertEquals(RephraseOutcome.Unavailable("the model file is missing"), outcome)
        assertEquals(1, generator.asked.size)
    }

    @Test fun aBlankOrHugeDraftNeverReachesTheModel() {
        val (blank, g1) = run(RephraseMode.FIX, "   ", "x")
        assertTrue(blank is RephraseOutcome.Declined); assertEquals(0, g1.asked.size)
        val (huge, g2) = run(RephraseMode.FIX, "a ".repeat(400), "x")
        assertTrue((huge as RephraseOutcome.Declined).message.contains("500")); assertEquals(0, g2.asked.size)
    }

    @Test fun theModelIsToldTheShippedPromptAndTheTrimmedDraft() {
        val (_, generator) = run(RephraseMode.FRIENDLY, "  where r u  ", "Hi! Where are you?")
        val messages = generator.asked.single().first
        assertEquals(prompts.messages(RephraseMode.FRIENDLY, "where r u"), messages)
    }

    @Test fun theContextOfTheThreadIsShownToTheModelWithTheDraft() {
        val context = "Them: can you send the file?\nYou: on my way"
        val generator = Scripted(listOf("OK, send it."))
        val outcome = runBlocking { Rephraser(prompts, generator).rephrase(RephraseMode.FIX, "ok snd it", context) }
        assertEquals(RephraseOutcome.Suggestion("OK, send it.", 1), outcome)
        val asked = generator.asked.single().first
        assertEquals(prompts.messages(RephraseMode.FIX, "ok snd it", context), asked)
    }

    @Test fun theReplyIsCappedToAboutTheLengthOfTheDraft() {
        val (_, shortAsk) = run(RephraseMode.FRIENDLY, "where r u", "Hi! Where are you?")
        val (_, longAsk) = run(RephraseMode.FRIENDLY, "x".repeat(400) + " where r u", "Hi! " + "x".repeat(400) + " where are you?")
        assertTrue(shortAsk.asked.single().second < longAsk.asked.single().second)
        assertTrue(longAsk.asked.single().second <= prompts.sampling.maxNewTokens)
    }

    @Test fun cancellingWhileTheModelRunsStopsTheWork() {
        val blocked = object : TextGenerator {
            override suspend fun generate(messages: List<ChatMessage>, maxTokens: Int, temperature: Float, seed: Int): String { kotlinx.coroutines.awaitCancellation() }
            override suspend fun close() {}
        }
        runBlocking {
            val job = launch(Dispatchers.Default) { Rephraser(prompts, blocked).rephrase(RephraseMode.FIX, "cn u call me") }
            delay(100)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }
    }
}
