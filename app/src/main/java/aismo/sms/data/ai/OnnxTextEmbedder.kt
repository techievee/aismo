package aismo.sms.data.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import aismo.sms.domain.ai.EmbedderUnavailableException
import aismo.sms.domain.ai.TextEmbedder
import aismo.sms.domain.ai.VectorMath
import aismo.sms.domain.ai.WordPieceTokenizer
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.LongBuffer

/**
 * Runs the bundled model with ONNX Runtime on the CPU. Owns the model file copy, the runtime session and the
 * tokenizer; the session is created on first use and kept until [close].
 */
class OnnxTextEmbedder(private val context: Context) : TextEmbedder {
    private class Ready(val session: OrtSession, val tokenizer: WordPieceTokenizer)

    private val loadLock = Mutex()
    private val runLock = Mutex()
    private var ready: Ready? = null
    private var failure: EmbedderUnavailableException? = null

    override suspend fun embed(text: String): FloatArray {
        val model = ready()
        return runLock.withLock {
            withContext(Dispatchers.Default) {
                try {
                    run(model, text)
                } catch (e: OutOfMemoryError) {
                    throw EmbedderUnavailableException("Not enough memory to run the search model", e)
                }
            }
        }
    }

    private suspend fun ready(): Ready {
        ready?.let { return it }
        return loadLock.withLock {
            ready ?: failure?.let { throw it } ?: withContext(Dispatchers.IO) {
                try {
                    load()
                } catch (e: EmbedderUnavailableException) {
                    failure = e; throw e
                } catch (e: Exception) {
                    throw EmbedderUnavailableException("The search model could not be loaded: ${e.message}", e).also { failure = it }
                } catch (e: LinkageError) { // no ONNX Runtime library for this device's architecture
                    throw EmbedderUnavailableException("Search by meaning is not supported on this device", e).also { failure = it }
                } catch (e: OutOfMemoryError) {
                    throw EmbedderUnavailableException("Not enough memory to load the search model", e).also { failure = it }
                }
            }.also { ready = it }
        }
    }

    private fun load(): Ready {
        val vocabulary = try {
            context.assets.open(EmbeddingModel.VOCABULARY_ASSET).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        } catch (e: FileNotFoundException) {
            throw EmbedderUnavailableException("The search model is not part of this build", e)
        }
        val file = modelFile()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val environment = OrtEnvironment.getEnvironment()
        // ONNX Runtime ships a usage-telemetry client that is on unless switched off. The manifest already removes what it
        // needs to reach the network; this closes it in the library as well.
        environment.setTelemetry(false)
        val session = environment.createSession(file.path, options)
        return Ready(session, WordPieceTokenizer.fromVocabulary(vocabulary, EmbeddingModel.MAX_TOKENS))
    }

    /** The model is read through a path so the runtime can map it; a copy out of the APK is made once per model version. */
    private fun modelFile(): File {
        val dir = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        val target = File(dir, "embedding-v${EmbeddingModel.VERSION}.onnx")
        if (target.length() == EmbeddingModel.MODEL_BYTES) return target
        dir.listFiles()?.forEach { it.delete() } // an older version, or a copy that was cut short
        val partial = File(dir, target.name + ".part")
        try {
            context.assets.open(EmbeddingModel.MODEL_ASSET).use { input -> partial.outputStream().use { input.copyTo(it) } }
        } catch (e: FileNotFoundException) {
            throw EmbedderUnavailableException("The search model is not part of this build", e)
        } catch (e: IOException) {
            partial.delete()
            throw EmbedderUnavailableException("The search model could not be copied: ${e.message}", e)
        }
        check(partial.length() == EmbeddingModel.MODEL_BYTES) { "model is ${partial.length()} bytes, expected ${EmbeddingModel.MODEL_BYTES}" }
        check(partial.renameTo(target)) { "could not move the model into place" }
        return target
    }

    private fun run(model: Ready, text: String): FloatArray {
        val ids = model.tokenizer.encode(text)
        val shape = longArrayOf(1, ids.size.toLong())
        val env = OrtEnvironment.getEnvironment()
        val tokens = LongArray(ids.size) { ids[it].toLong() }
        val mask = LongArray(ids.size) { 1L }
        val types = LongArray(ids.size)
        OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), shape).use { inputIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { attention ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape).use { tokenTypes ->
                    val inputs = mapOf("input_ids" to inputIds, "attention_mask" to attention, "token_type_ids" to tokenTypes)
                    try {
                        model.session.run(inputs).use { result ->
                            val hidden = result[0] as OnnxTensor
                            val dimension = hidden.info.shape.last()
                            if (dimension != EmbeddingModel.DIMENSION.toLong()) {
                                throw EmbedderUnavailableException("The search model gave vectors of $dimension numbers, not ${EmbeddingModel.DIMENSION}")
                            }
                            // The first token ([CLS]) of the first and only text is the sentence vector.
                            val vector = FloatArray(EmbeddingModel.DIMENSION)
                            hidden.floatBuffer.get(vector)
                            return VectorMath.normalize(vector)
                        }
                    } catch (e: OrtException) {
                        throw IllegalStateException("The search model failed on a text: ${e.message}", e)
                    }
                }
            }
        }
    }

    /** Releases the runtime session; the next [embed] loads it again. */
    suspend fun close() = loadLock.withLock {
        runLock.withLock {
            ready?.session?.close()
            ready = null
            failure = null
        }
    }

    private companion object {
        const val THREADS = 2
    }
}
