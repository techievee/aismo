package aismo.sms.domain.translate

import kotlinx.coroutines.CancellationException
import aismo.sms.domain.llm.GenerationException
import aismo.sms.domain.llm.LlmBackend
import aismo.sms.domain.llm.TextGenerator

/** What happened when the user asked for a translation. */
sealed interface TranslationResult {
    /** A translation that passed the checks; the original message is left untouched. */
    data class Success(val text: String) : TranslationResult

    /** The message is already in the target language (or the model says it is), so nothing was produced. */
    data object AlreadyIn : TranslationResult

    data object NoText : TranslationResult
    data object TooLong : TranslationResult

    /** The model answered, but what it said is not a usable translation. */
    data class Unavailable(val reason: String) : TranslationResult

    /** No model file is installed for the chosen backend yet; the user needs to set one up in Settings. */
    data class NoModel(val backend: LlmBackend) : TranslationResult
}

/**
 * Asks a [TextGenerator] to translate a message and keeps only what the checks accept. If the first answer is
 * not usable it asks once more with a higher temperature before giving up. Unlike a rewrite, a translation
 * legitimately changes every word, so there are no fact checks — only guards against a model that answers with an
 * explanation, a label, or the same text back.
 */
class Translator(private val prompts: TranslationPrompts, private val generator: TextGenerator) {
    /** [variation] is 0 for the first answer and higher for "Try again". */
    suspend fun translate(text: String, source: Language?, target: Language, variation: Int = 0): TranslationResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TranslationResult.NoText
        if (trimmed.length > MAX_CHARS) return TranslationResult.TooLong
        if (source != null && source == target) return TranslationResult.AlreadyIn

        val messages = prompts.messages(source, target, trimmed)
        // A translation is about as long as the message, a little longer for languages that expand; this stops a
        // model that rambles without cutting off a long message.
        val maxTokens = minOf(prompts.sampling.maxNewTokens, trimmed.length * 2 + 60)
        // Asking again with the same temperature and seed would give the same answer, so a retry starts hotter.
        val temperatures = if (variation == 0) listOf(prompts.sampling.temperature, prompts.sampling.retryTemperature)
        else listOf(prompts.sampling.retryTemperature, prompts.sampling.retryTemperature)
        for ((attempt, temperature) in temperatures.withIndex()) {
            val raw = try {
                generator.generate(messages, maxTokens, temperature, SEEDS[attempt] + variation * 101)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenerationException) {
                return TranslationResult.Unavailable(e.message ?: "the translation could not run")
            }
            when (val verdict = judge(trimmed, target, raw)) {
                is TranslationResult.Success -> return verdict
                is TranslationResult.Unavailable -> continue
                TranslationResult.AlreadyIn -> return TranslationResult.AlreadyIn
                else -> continue
            }
        }
        return TranslationResult.Unavailable("The model did not give a translation that could be used. Try again.")
    }

    companion object {
        const val MAX_CHARS = 500
        private val SEEDS = intArrayOf(7, 11)

        /** Answers that start with this are the model talking about the translation, not translating. */
        private val LABEL_PREFIXES = listOf(
            "translation:", "translated:", "translated to", "the translation", "the translated",
            "here is the translation", "here's the translation", "here is my translation",
        )

        private fun judge(text: String, target: Language, raw: String): TranslationResult {
            val out = clean(raw)
            if (out.isEmpty()) return TranslationResult.Unavailable("The model did not answer.")
            if (out == text || out.equals(text, ignoreCase = true)) return TranslationResult.AlreadyIn
            val lower = out.lowercase()
            if (LABEL_PREFIXES.any { lower.startsWith(it) }) return TranslationResult.Unavailable("The model labelled its answer instead of translating.")
            if (target.scripts.isNotEmpty() && !out.any { ch -> target.scripts.any { s -> ch.code in s.from..s.to } }) {
                return TranslationResult.Unavailable("The model replied in a different language.")
            }
            return TranslationResult.Success(out)
        }

        /** Trims the answer and strips a pair of quotes a model likes to add around everything. */
        private fun clean(raw: String): String {
            var out = raw.trim()
            while (out.length >= 2 && (out.first() == '"' && out.last() == '"' || out.first() == '「' && out.last() == '」')) {
                out = out.substring(1, out.lastIndex).trim()
            }
            return out
        }
    }
}
