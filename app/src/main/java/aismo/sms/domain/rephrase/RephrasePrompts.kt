package aismo.sms.domain.rephrase

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import aismo.sms.domain.llm.ChatMessage
import aismo.sms.domain.llm.ChatRole

@Serializable
data class WorkedExample(val input: String, val output: String)

@Serializable
data class ModePrompt(val instruction: String, val examples: List<WorkedExample>)

@Serializable
data class Sampling(val temperature: Float, val retryTemperature: Float, val maxNewTokens: Int)

/**
 * What the model is told, read from `prompts.json` (the same file the evaluation script in tools/rephrase-eval scores, so
 * what is tuned is what ships). Each mode has an instruction and a few worked examples; small models follow examples far
 * more reliably than instructions alone.
 */
@Serializable
data class RephrasePrompts(val version: Int, val system: String, val sampling: Sampling, val modes: Map<String, ModePrompt>) {
    init {
        for (mode in RephraseMode.entries) require(mode.key in modes) { "prompts.json has no entry for ${mode.key}" }
    }

    /**
     * The chat to send: the system message, the worked examples as earlier turns, then the draft. When [context] is
     * given (the recent messages of the thread the reply belongs to), it is shown to the model so the rewrite fits the
     * conversation the draft is answering.
     */
    fun messages(mode: RephraseMode, draft: String, context: String? = null): List<ChatMessage> {
        val prompt = modes.getValue(mode.key)
        return buildList {
            add(ChatMessage(ChatRole.SYSTEM, system))
            for (example in prompt.examples) {
                add(ChatMessage(ChatRole.USER, request(prompt.instruction, example.input)))
                add(ChatMessage(ChatRole.ASSISTANT, example.output))
            }
            add(ChatMessage(ChatRole.USER, request(prompt.instruction, draft, context)))
        }
    }

    private fun request(instruction: String, message: String, context: String? = null): String {
        // The context goes before the instruction, so the instruction and the "Message:" line stay at the end, where a
        // small model looks first.
        val head = if (context.isNullOrBlank()) "" else "This is a reply in an ongoing conversation. It went like this:\n$context\n\n"
        return head + "$instruction\n\nMessage: $message"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): RephrasePrompts = json.decodeFromString(serializer(), text)
    }
}
