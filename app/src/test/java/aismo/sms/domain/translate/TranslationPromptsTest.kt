package aismo.sms.domain.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import aismo.sms.domain.llm.ChatRole

class TranslationPromptsTest {
    /** The file the app ships, so a broken edit to it fails here and not on a phone. */
    private val shipped by lazy { TranslationPrompts.parse(File("src/main/assets/translate/prompts.json").readText()) }

    @Test fun theShippedPromptsCarryExamplesForTheMajorDirections() {
        val pairs = shipped.examples.map { it.from to it.to }
        assertTrue("hi→en is a worked example", "hi" to "en" in pairs)
        assertTrue("en→hi is a worked example", "en" to "hi" in pairs)
        assertTrue("es→en is a worked example", "es" to "en" in pairs)
        assertTrue("en→ar is a worked example", "en" to "ar" in pairs)
        for (example in shipped.examples) {
            assertTrue("${example.from}→${example.to}: the input is not blank", example.input.isNotBlank())
            assertTrue("${example.from}→${example.to}: the output is not blank", example.output.isNotBlank())
        }
    }

    @Test fun aChatIsSystemThenExamplesThenTheRequest() {
        val chat = shipped.messages(Language.HI, Language.EN, "कल मिलते हैं")
        assertEquals(ChatRole.SYSTEM, chat.first().role)
        val last = chat.last()
        assertEquals(ChatRole.USER, last.role)
        assertTrue(last.text.endsWith("Message: कल मिलते हैं"))
        assertTrue(last.text.contains("to English"))
        // The examples precede the request as full turns.
        val exampleUsers = chat.drop(1).dropLast(1).filter { it.role == ChatRole.USER }
        val exampleAssistants = chat.drop(1).dropLast(1).filter { it.role == ChatRole.ASSISTANT }
        assertEquals(exampleUsers.size, exampleAssistants.size)
        assertTrue("there are examples before the request", exampleUsers.isNotEmpty())
    }

    @Test fun examplesInTheRequestedDirectionComeFirst() {
        val chat = shipped.messages(Language.HI, Language.EN, "कल मिलत")
        val firstExample = chat.drop(1).first { it.role == ChatRole.USER }
        assertTrue("the first example is in the requested direction", firstExample.text.contains("from Hindi") && firstExample.text.contains("to English"))
    }

    @Test fun anUnknownSourceFallsBackToExamplesInTheTargetDirection() {
        val chat = shipped.messages(Language.SI, Language.ES, "හිතා")
        val firstExample = chat.drop(1).first { it.role == ChatRole.USER }
        assertTrue("the first example targets the requested language", firstExample.text.contains("to Spanish"))
    }

    @Test fun samplingIsConservative() {
        assertTrue(shipped.sampling.temperature <= 0.3f)
        assertTrue(shipped.sampling.retryTemperature > shipped.sampling.temperature)
        assertTrue(shipped.sampling.maxNewTokens in 60..300)
    }

    @Test fun aFileWithoutExamplesIsAccepted() {
        TranslationPrompts.parse(
            """{"version":1,"system":"s","sampling":{"temperature":0.1,"retryTemperature":0.4,"maxNewTokens":200},"examples":[]}""",
        )
    }
}
