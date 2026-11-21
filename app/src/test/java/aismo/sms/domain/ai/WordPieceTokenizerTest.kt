package aismo.sms.domain.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WordPieceTokenizerTest {
    /** Just enough vocabulary to show each rule; ids are the list positions. */
    private val tiny = WordPieceTokenizer.fromVocabulary(
        listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", "##s", "un", "##able", ",", "!", "cafe", "东", "京", "a", "##a"),
        maxTokens = 8,
    )

    private fun ids(text: String) = tiny.encode(text).toList()

    @Test fun wrapsPiecesInClsAndSep() = assertEquals(listOf(2, 4, 3), ids("hello"))

    @Test fun lowercasesAndSplitsContinuationsAndPunctuation() =
        assertEquals(listOf(2, 4, 9, 5, 6, 10, 3), ids("Hello, Worlds!"))

    @Test fun matchesTheLongestPieceFirst() = assertEquals(listOf(2, 7, 8, 3), ids("unable"))

    @Test fun aWordThatCannotBeSplitIsOneUnknown() = assertEquals(listOf(2, 1, 3), ids("unabled"))

    @Test fun accentsAreStrippedBeforeMatching() = assertEquals(listOf(2, 11, 3), ids("Café"))

    @Test fun cjkCharactersAreEachTheirOwnWord() = assertEquals(listOf(2, 12, 13, 3), ids("东京"))

    @Test fun aWordOverOneHundredCharactersIsUnknown() = assertEquals(listOf(2, 1, 3), ids("a".repeat(101)))

    @Test fun aHundredCharactersStillSplit() {
        val result = ids("a".repeat(100))
        assertTrue("100 characters is allowed: $result", 1 !in result)
    }

    @Test fun truncationKeepsClsAndSepAndStopsEarly() {
        val result = ids("hello ".repeat(50))
        assertEquals(8, result.size)
        assertEquals(2, result.first())
        assertEquals(3, result.last())
    }

    @Test fun emptyAndBlankTextGiveJustTheMarkers() {
        assertEquals(listOf(2, 3), ids(""))
        assertEquals(listOf(2, 3), ids("  \t\n "))
    }

    @Test fun controlCharactersVanishAndOddSpacesSeparate() {
        assertEquals(listOf(2, 4, 5, 3), ids("hel\u0000lo world"))   // NUL removed, no-break space separates
        assertEquals(listOf(2, 4, 3), ids("hel​lo"))               // zero-width space is a format character: removed
    }

    // --- against the real vocabulary and the reference tokenizer's output ---

    private val real: WordPieceTokenizer by lazy {
        WordPieceTokenizer.fromVocabulary(File("src/main/assets/models/vocab.txt").readLines())
    }

    private fun cases(resource: String): JsonArray =
        Json.parseToJsonElement(javaClass.getResource(resource)!!.readText()).jsonArray

    private fun assertMatchesReference(cases: JsonArray) {
        val mismatches = ArrayList<String>()
        for (case in cases) {
            val text = case.jsonObject["text"]!!.jsonPrimitive.content
            val expected = case.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.int }
            val actual = real.encode(text).toList()
            if (actual != expected) {
                val at = actual.zip(expected).indexOfFirst { it.first != it.second }.let { if (it < 0) minOf(actual.size, expected.size) else it }
                mismatches += "text=${text.map { if (it.code < 32 || it.code > 126) "\\u%04x".format(it.code) else it.toString() }.joinToString("")} " +
                    "first difference at $at: got ${actual.drop(at).take(4)} expected ${expected.drop(at).take(4)}"
            }
        }
        assertTrue("${mismatches.size} of ${cases.size} differ from the reference tokenizer:\n" + mismatches.take(10).joinToString("\n"), mismatches.isEmpty())
    }

    @Test fun matchesTheReferenceTokenizerOnHandPickedMessages() = assertMatchesReference(cases("/ai/tokenizer-golden.json"))

    @Test fun matchesTheReferenceTokenizerOnRandomUnicode() {
        // AISMO_RANDOM_CORPUS points at a larger file of the same shape for an occasional wider check.
        val wide = System.getenv("AISMO_RANDOM_CORPUS")?.let { Json.parseToJsonElement(File(it).readText()).jsonArray }
        assertMatchesReference(wide ?: cases("/ai/tokenizer-random.json"))
    }

    @Test fun aLiteralSpecialTokenInAMessageIsOrdinaryText() {
        // The reference tokenizer would read "[CLS]" as the special token; a message that says so is just text.
        val result = real.encode("literal [CLS] and [SEP] here").toList()
        assertTrue("only the first and last ids may be special: $result", result.subList(1, result.size - 1).none { it in 100..103 })
    }

    @Test fun neverReturnsMoreThanTheLimit() {
        val result = real.encode("word ".repeat(1000))
        assertEquals(WordPieceTokenizer.DEFAULT_MAX_TOKENS, result.size)
        assertArrayEquals(intArrayOf(101), result.take(1).toIntArray())
        assertEquals(102, result.last())
    }
}
