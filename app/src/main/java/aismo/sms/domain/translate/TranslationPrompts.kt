package aismo.sms.domain.translate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import aismo.sms.domain.llm.ChatMessage
import aismo.sms.domain.llm.ChatRole

@Serializable
data class TranslationSampling(val temperature: Float, val retryTemperature: Float, val maxNewTokens: Int)

/** One translation shown to the model as an earlier turn of the chat. */
@Serializable
data class TranslationExample(val from: String, val to: String, val input: String, val output: String)

/**
 * What the model is told when translating, read from `translate/prompts.json`. The worked examples are the main
 * lever: a small model follows a shown example far more reliably than an instruction alone, and examples in the
 * requested direction teach it to reply in the target script.
 */
@Serializable
data class TranslationPrompts(
    val version: Int,
    val system: String,
    val sampling: TranslationSampling,
    val examples: List<TranslationExample>,
) {
    /** The chat to send: the system message, a couple of worked examples, then the request. */
    fun messages(source: Language?, target: Language, text: String): List<ChatMessage> {
        return buildList {
            add(ChatMessage(ChatRole.SYSTEM, system))
            for (example in pickExamples(source, target)) {
                val from = Language.fromKey(example.from)
                val to = Language.fromKey(example.to) ?: Language.EN
                add(ChatMessage(ChatRole.USER, request(from, to, example.input)))
                add(ChatMessage(ChatRole.ASSISTANT, example.output))
            }
            add(ChatMessage(ChatRole.USER, request(source, target, text)))
        }
    }

    /** Up to two examples, preferring the exact requested direction, then the target language, then any. */
    private fun pickExamples(source: Language?, target: Language): List<TranslationExample> {
        val wanted = listOfNotNull(source?.key, target.key)
        val ordered = examples
            .sortedWith(compareByDescending<TranslationExample> { it.from in wanted && it.to in wanted }
                .thenByDescending { it.to == target.key }
                .thenBy { examples.indexOf(it) })
        return ordered.take(MAX_EXAMPLES)
    }

    private fun request(source: Language?, target: Language, text: String) =
        "Translate the message from ${source?.label ?: "its own language"} to ${target.label}.\n\nMessage: $text"

    companion object {
        private const val MAX_EXAMPLES = 2
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): TranslationPrompts = json.decodeFromString(serializer(), text)
    }
}
