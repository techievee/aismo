package aismo.sms.domain.llm

/** The runtime that runs the writing assistant's model. Each reads its own model format. */
enum class LlmBackend(val key: String, val label: String, val format: String, val summary: String) {
    LLAMA_CPP("llama", "llama.cpp", "GGUF", "Open-source, runs GGUF models. Fastest and most accurate in testing."),
    LITERT_LM("litert", "LiteRT-LM", "LiteRT-LM", "Google's runtime, runs .litertlm models.");

    companion object {
        fun fromKey(key: String?): LlmBackend? = entries.firstOrNull { it.key == key }
    }
}
