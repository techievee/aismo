package aismo.sms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.ai.EmbeddingModel
import aismo.sms.domain.ai.WordPieceTokenizer

/**
 * The tokenizer's unit tests run on the host JVM; Android carries its own Unicode tables, so the same reference cases
 * are checked here, on the device's own `Character` and `Normalizer`.
 */
@RunWith(AndroidJUnit4::class)
class TokenizerOnDeviceTest {
    private val tokenizer by lazy {
        val lines = ApplicationProvider.getApplicationContext<AismoApp>().assets.open(EmbeddingModel.VOCABULARY_ASSET).bufferedReader().readLines()
        WordPieceTokenizer.fromVocabulary(lines, EmbeddingModel.MAX_TOKENS)
    }

    private fun mismatches(asset: String): Pair<Int, List<String>> {
        val cases = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("ai/$asset").bufferedReader().readText()).jsonArray
        val bad = ArrayList<String>()
        for (case in cases) {
            val text = case.jsonObject["text"]!!.jsonPrimitive.content
            val expected = case.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.int }
            if (tokenizer.encode(text).toList() != expected) {
                bad += text.map { if (it.code in 32..126) it.toString() else "\\u%04x".format(it.code) }.joinToString("")
            }
        }
        return cases.size to bad
    }

    @Test fun handPickedMessagesMatchTheReferenceOnTheDevice() {
        val (total, bad) = mismatches("tokenizer-golden.json")
        assertTrue("${bad.size} of $total differ on the device: ${bad.take(5)}", bad.isEmpty())
    }

    @Test fun randomUnicodeMatchesTheReferenceOnTheDevice() {
        val (total, bad) = mismatches("tokenizer-random.json")
        assertTrue("${bad.size} of $total differ on the device: ${bad.take(5)}", bad.isEmpty())
    }
}
