package aismo.sms.data.llm

/** The JNI surface of `libaismo_llm.so`, a thin wrapper around llama.cpp. Use [LlamaCppGenerator], not this. */
internal object LlamaNative {
    init {
        System.loadLibrary("aismo_llm")
    }

    external fun init(nativeLibDir: String): Boolean

    /** A handle, or 0 with the reason in [lastError]. */
    external fun load(modelPath: String, contextSize: Int, threads: Int): Long

    /** The reply as UTF-8 bytes, or null with the reason in [lastError] ("cancelled" if [cancel] was called). */
    external fun generate(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, maxTokens: Int, temperature: Float, seed: Int): ByteArray?

    external fun cancel(handle: Long)
    external fun free(handle: Long)
    external fun lastError(): String
    external fun lastStats(handle: Long): String
    external fun systemInfo(): String
}
