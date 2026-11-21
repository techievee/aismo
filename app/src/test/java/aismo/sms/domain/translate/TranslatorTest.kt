package aismo.sms.domain.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import aismo.sms.domain.llm.ChatMessage
import aismo.sms.domain.llm.ChatRole
import aismo.sms.domain.llm.GenerationException
import aismo.sms.domain.llm.TextGenerator

class TranslatorTest {
    private val prompts = TranslationPrompts.parse(File("src/main/assets/translate/prompts.json").readText())

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

    private fun run(source: Language?, target: Language, text: String, vararg answers: Any): Pair<TranslationResult, Scripted> {
        val generator = Scripted(answers.toList())
        return runBlocking { Translator(prompts, generator).translate(text, source, target) } to generator
    }

    @Test fun aGoodAnswerIsAcceptedAfterOneAttempt() {
        val (outcome, generator) = run(Language.HI, Language.EN, "कल मिलते हैं", "See you tomorrow")
        assertEquals(TranslationResult.Success("See you tomorrow"), outcome)
        assertEquals(1, generator.asked.size)
    }

    @Test fun anEmptyMessageIsNoTextWithoutAskingTheModel() {
        val (outcome, generator) = run(Language.HI, Language.EN, "   ")
        assertEquals(TranslationResult.NoText, outcome)
        assertEquals(0, generator.asked.size)
    }

    @Test fun aMessageOver500CharactersIsTooLongWithoutAskingTheModel() {
        val (outcome, generator) = run(Language.HI, Language.EN, "x".repeat(501))
        assertEquals(TranslationResult.TooLong, outcome)
        assertEquals(0, generator.asked.size)
    }

    @Test fun theSameLanguageIsAlreadyInWithoutAskingTheModel() {
        val (outcome, generator) = run(Language.EN, Language.EN, "hello there")
        assertEquals(TranslationResult.AlreadyIn, outcome)
        assertEquals(0, generator.asked.size)
    }

    @Test fun theModelEchoingTheInputMeansAlreadyIn() {
        val (outcome, generator) = run(Language.HI, Language.EN, "कल मिलते हैं", "कल मिलते हैं")
        assertEquals(TranslationResult.AlreadyIn, outcome)
        assertEquals(1, generator.asked.size)
    }

    @Test fun anAnswerThatLabelsItselfIsRetriedOnceThenUnavailable() {
        val (outcome, generator) = run(Language.HI, Language.EN, "कल मिलते हैं", "Translation: see you tomorrow", "Translation: see you tomorrow")
        assertTrue(outcome is TranslationResult.Unavailable)
        assertEquals(2, generator.asked.size)
    }

    @Test fun anEnglishAnswerForAnArabicTargetIsRetriedUntilArabic() {
        val (outcome, generator) = run(Language.EN, Language.AR, "I am on my way", "I am still on my way", "أنا في الطريق")
        assertEquals(TranslationResult.Success("أنا في الطريق"), outcome)
        assertEquals(2, generator.asked.size)
    }

    @Test fun aGenerationFailureIsUnavailable() {
        val (outcome, generator) = run(Language.HI, Language.EN, "कल मिलते हैं", GenerationException("not enough memory"))
        assertTrue(outcome is TranslationResult.Unavailable)
        assertEquals(1, generator.asked.size)
    }

    @Test fun quotedAnswersAreUnquoted() {
        val (outcome, _) = run(Language.HI, Language.EN, "कल मिलते हैं", "\"See you tomorrow\"")
        assertEquals(TranslationResult.Success("See you tomorrow"), outcome)
    }

    @Test fun theChatEndsInTheUserMessageNamingTheTargetLanguage() {
        val generator = Scripted(listOf("कल"))
        runBlocking { Translator(prompts, generator).translate("hello", Language.EN, Language.HI) }
        val chat = generator.asked.single().first
        assertEquals(ChatRole.SYSTEM, chat.first().role)
        val last = chat.last()
        assertEquals(ChatRole.USER, last.role)
        assertTrue(last.text.contains("to Hindi"))
        assertTrue(last.text.endsWith("Message: hello"))
    }

    @Test fun whenTheSourceIsUnknownTheRequestDoesNotNameALanguage() {
        val generator = Scripted(listOf("ok"))
        runBlocking { Translator(prompts, generator).translate("hello", null, Language.ES) }
        val last = generator.asked.single().first.last()
        assertTrue(last.text.contains("from its own language"))
        assertTrue(last.text.contains("to Spanish"))
    }

    @Test fun maxTokensGrowsWithTheMessageButStaysUnderTheCap() {
        val generator = Scripted(listOf("ok"))
        runBlocking { Translator(prompts, generator).translate("a".repeat(100), Language.HI, Language.EN) }
        assertEquals("the cap", 200, generator.asked.single().second)
        val short = Scripted(listOf("ok"))
        runBlocking { Translator(prompts, short).translate("ab", Language.HI, Language.EN) }
        assertEquals("grows with the message", 64, short.asked.single().second)
    }
}
