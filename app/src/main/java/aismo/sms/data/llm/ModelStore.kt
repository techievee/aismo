package aismo.sms.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import aismo.sms.domain.llm.LlmBackend
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** What came of choosing a model file. */
sealed interface ModelImport {
    data class Installed(val model: SupportedModel) : ModelImport
    /** A readable file that is not one of the models the app supports; [sha256] helps with support questions. */
    data class NotSupported(val sha256: String) : ModelImport
    data class Failed(val reason: String) : ModelImport
}

/**
 * Keeps the writing assistant's model files in app-private storage, one per backend. A file is only installed after its
 * SHA-256 matches a model in [catalog], so a wrong, partial or tampered download is never loaded.
 */
class ModelStore(val root: File, private val catalog: List<SupportedModel> = ModelCatalog.all) {
    private fun file(model: SupportedModel) = File(File(root, model.backend.key), model.fileName)

    /** The installed model for [backend], or null. The size is checked here; the hash was checked when it was installed. */
    fun installed(backend: LlmBackend): Installed? =
        catalog.filter { it.backend == backend }.firstNotNullOfOrNull { model ->
            file(model).takeIf { it.isFile && it.length() == model.sizeBytes }?.let { Installed(model, it) }
        }

    data class Installed(val model: SupportedModel, val file: File)

    /**
     * Copies [input] into private storage while hashing it, and keeps it only if it is a supported model. Replaces any
     * model already installed for the same backend. Cancelling leaves nothing behind.
     */
    suspend fun import(input: InputStream, onProgress: (bytes: Long) -> Unit = {}): ModelImport = withContext(Dispatchers.IO) {
        root.mkdirs()
        val partial = File(root, "import-${System.nanoTime()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            input.use { source ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n); digest.update(buffer, 0, n)
                        total += n
                        onProgress(total)
                        if (total > MAX_BYTES) return@withContext ModelImport.NotSupported("a file this large is not a supported model")
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val model = catalog.firstOrNull { it.sha256 == sha && it.sizeBytes == total } ?: return@withContext ModelImport.NotSupported(sha)
            val target = file(model)
            target.parentFile?.listFiles()?.forEach { it.delete() }   // one model per backend
            target.parentFile?.mkdirs()
            if (!partial.renameTo(target)) throw IOException("could not move the model into place")
            ModelImport.Installed(model)
        } catch (e: IOException) {
            ModelImport.Failed(e.message ?: "could not read the file")
        } finally {
            partial.delete()   // a no-op after a successful move
        }
    }

    fun remove(backend: LlmBackend) {
        File(root, backend.key).listFiles()?.forEach { it.delete() }
    }

    private companion object {
        /** The heaviest supported model (Gemma 4 E4B) just under 3.5 GB; anything larger is not a phone model. */
        const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    }
}
