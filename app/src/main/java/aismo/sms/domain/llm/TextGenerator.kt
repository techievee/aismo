package aismo.sms.domain.llm

enum class ChatRole { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: ChatRole, val text: String)

/** A language model that answers a chat. Implemented once per runtime (llama.cpp, LiteRT-LM). */
interface TextGenerator {
    /**
     * The model's reply to [messages], whose last message is the user's request. Cancelling the calling coroutine stops
     * the model. Throws [GenerationException] if the model cannot run.
     */
    suspend fun generate(messages: List<ChatMessage>, maxTokens: Int, temperature: Float, seed: Int): String

    /** Frees the model's memory; the next [generate] loads it again. */
    suspend fun close()
}

/** The model could not be loaded or failed while answering (a damaged file, not enough memory). */
class GenerationException(message: String, cause: Throwable? = null) : Exception(message, cause)
