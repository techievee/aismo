package aismo.sms.domain.llm

/**
 * The user asked for the answer, not the reasoning. Models that "think before answering" (Qwen3 and its
 * like) wrap the reasoning in <think> ... think> tags, and whatever the backend, it is stripped
 * before the text is judged or shown. When the model is cut off mid-thought (no closing tag), the remainder is
 * handed back; a model that closes its reasoning with a stray closing tag and no opener has the text cut at
 * that tag; and the caller's own checks then decide whether the result is usable.
 */
object ModelOutput {
    const val THINKING_OPEN = "<think>"
    const val THINKING_CLOSE = "think>"

    fun withoutThinking(raw: String): String {
        val open = raw.lastIndexOf(THINKING_OPEN)
        val close = raw.lastIndexOf(THINKING_CLOSE)
        val kept = when {
            close > open -> raw.substring(close + THINKING_CLOSE.length)
            open >= 0 -> raw.substring(open + THINKING_OPEN.length)
            close >= 0 -> raw.substring(close + THINKING_CLOSE.length)
            else -> raw
        }
        return kept.trim()
    }
}
