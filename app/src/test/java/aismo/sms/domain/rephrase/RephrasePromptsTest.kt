package aismo.sms.domain.rephrase

import aismo.sms.domain.llm.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RephrasePromptsTest {
    /** The file the app ships, so a broken edit to it fails here and not on a phone. */
    private val shipped by lazy { RephrasePrompts.parse(File("src/main/assets/rephrase/prompts.json").readText()) }

    @Test fun theShippedPromptsCoverEveryMode() {
        for (mode in RephraseMode.entries) {
            val prompt = shipped.modes.getValue(mode.key)
            assertTrue("${mode.key} has an instruction", prompt.instruction.isNotBlank())
            assertEquals("${mode.key} has three worked examples", 3, prompt.examples.size)
        }
    }

    @Test fun aChatIsSystemThenExamplesAsTurnsThenTheDraft() {
        val chat = shipped.messages(RephraseMode.POLITE, "send it pls")
        assertEquals(ChatRole.SYSTEM, chat.first().role)
        assertEquals(1 + 3 * 2 + 1, chat.size)
        assertEquals(List(3) { listOf(ChatRole.USER, ChatRole.ASSISTANT) }.flatten(), chat.subList(1, chat.size - 1).map { it.role })
        val last = chat.last()
        assertEquals(ChatRole.USER, last.role)
        assertTrue(last.text.endsWith("Message: send it pls"))
        assertTrue(last.text.startsWith(shipped.modes.getValue("polite").instruction))
    }

    @Test fun aReplyInAConversationIsShownToTheModelAsPartOfTheLastTurn() {
        val context = "Them: are we still on for friday?\nYou: i think so, checking the kids"
        val chat = shipped.messages(RephraseMode.FIX, "sounds good", context)
        assertEquals(1 + 3 * 2 + 1, chat.size)
        val last = chat.last()
        assertTrue(last.text.startsWith("This is a reply in an ongoing conversation. It went like this:\n$context\n\n"))
        assertTrue(last.text.endsWith("Message: sounds good"))
    }

    @Test fun theSystemMessageAndExamplesAreIdenticalAcrossDraftsSoTheirStartCanBeReused() {
        val a = shipped.messages(RephraseMode.FIX, "one draft")
        val b = shipped.messages(RephraseMode.FIX, "a different draft")
        assertEquals(a.dropLast(1), b.dropLast(1))
    }

    @Test fun everyWorkedExampleWouldPassTheGuardItself() {
        // Examples that the guard rejects would teach the model to produce what the app then throws away.
        for (mode in RephraseMode.entries) for (example in shipped.modes.getValue(mode.key).examples) {
            val verdict = RephraseGuard.judge(mode, example.input, example.output)
            assertTrue("${mode.key}: \"${example.input}\" -> \"${example.output}\" is $verdict", verdict is RephraseGuard.Verdict.Accept || verdict == RephraseGuard.Verdict.Unchanged)
        }
    }

    @Test fun samplingIsConservative() {
        assertTrue(shipped.sampling.temperature <= 0.3f)
        assertTrue(shipped.sampling.retryTemperature > shipped.sampling.temperature)
        assertTrue(shipped.sampling.maxNewTokens in 60..200)
    }

    @Test(expected = IllegalArgumentException::class) fun aFileMissingAModeIsRejected() {
        RephrasePrompts.parse("""{"version":1,"system":"s","sampling":{"temperature":0.2,"retryTemperature":0.5,"maxNewTokens":100},"modes":{}}""")
    }
}
