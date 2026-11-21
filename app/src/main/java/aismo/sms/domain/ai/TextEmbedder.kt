package aismo.sms.domain.ai

/** Turns text into a unit vector that points the same way for texts that mean the same. */
interface TextEmbedder {
    /** Throws [EmbedderUnavailableException] when the model cannot run on this device. */
    suspend fun embed(text: String): FloatArray
}

/** The model could not be loaded or run (missing file, unsupported architecture, out of memory), not a problem with one text. */
class EmbedderUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
