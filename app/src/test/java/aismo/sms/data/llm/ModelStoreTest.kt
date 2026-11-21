package aismo.sms.data.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import aismo.sms.domain.llm.LlmBackend
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class ModelStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun model(backend: LlmBackend, name: String, bytes: ByteArray) =
        SupportedModel(backend, name, "$name.bin", bytes.size.toLong(), sha(bytes), "test", "https://example.invalid/$name", "https://example.invalid", "about 1 GB of RAM")

    private val gguf = ByteArray(200_000) { (it * 31).toByte() }
    private val litert = ByteArray(150_000) { (it * 17).toByte() }
    private val llama = model(LlmBackend.LLAMA_CPP, "llama-model", gguf)
    private val lite = model(LlmBackend.LITERT_LM, "lite-model", litert)

    private fun store() = ModelStore(folder.newFolder(), listOf(llama, lite))

    @Test fun aFileWithTheRightChecksumIsInstalledForItsBackend() = runBlocking {
        val store = store()
        val result = store.import(ByteArrayInputStream(gguf))
        assertEquals(ModelImport.Installed(llama), result)
        assertEquals(llama, store.installed(LlmBackend.LLAMA_CPP)?.model)
        assertNull("the other backend stays empty", store.installed(LlmBackend.LITERT_LM))
        assertEquals(gguf.size.toLong(), store.installed(LlmBackend.LLAMA_CPP)!!.file.length())
    }

    @Test fun aFileThatIsNotASupportedModelIsRefusedAndLeavesNothingBehind() = runBlocking {
        val store = store()
        val other = ByteArray(1000) { 5 }
        val result = store.import(ByteArrayInputStream(other))
        assertEquals(ModelImport.NotSupported(sha(other)), result)
        assertNull(store.installed(LlmBackend.LLAMA_CPP))
        assertTrue("no temporary file is left", folder.root.walkTopDown().none { it.isFile })
    }

    @Test fun aTruncatedModelIsRefused() = runBlocking {
        val store = store()
        val result = store.import(ByteArrayInputStream(gguf.copyOf(gguf.size - 1)))
        assertTrue(result is ModelImport.NotSupported)
        assertNull(store.installed(LlmBackend.LLAMA_CPP))
    }

    @Test fun importingTheSecondBackendKeepsTheFirst() = runBlocking {
        val store = store()
        store.import(ByteArrayInputStream(gguf))
        store.import(ByteArrayInputStream(litert))
        assertEquals(llama, store.installed(LlmBackend.LLAMA_CPP)?.model)
        assertEquals(lite, store.installed(LlmBackend.LITERT_LM)?.model)
    }

    @Test fun removingOneBackendLeavesTheOther() = runBlocking {
        val store = store()
        store.import(ByteArrayInputStream(gguf))
        store.import(ByteArrayInputStream(litert))
        store.remove(LlmBackend.LLAMA_CPP)
        assertNull(store.installed(LlmBackend.LLAMA_CPP))
        assertEquals(lite, store.installed(LlmBackend.LITERT_LM)?.model)
    }

    @Test fun anInstalledFileThatChangedSizeIsNotReportedAsInstalled() = runBlocking {
        val store = store()
        store.import(ByteArrayInputStream(gguf))
        store.installed(LlmBackend.LLAMA_CPP)!!.file.appendBytes(byteArrayOf(1))
        assertNull(store.installed(LlmBackend.LLAMA_CPP))
    }

    @Test fun aReadErrorIsReportedAndLeavesNothingBehind() = runBlocking {
        val store = store()
        val broken = object : InputStream() {
            var sent = 0
            override fun read(): Int = throw IOException("disk went away")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent > 0) throw IOException("disk went away")
                sent = 1000; return 1000
            }
        }
        val result = store.import(broken)
        assertEquals(ModelImport.Failed("disk went away"), result)
        assertTrue(folder.root.walkTopDown().none { it.isFile })
    }

    @Test fun cancellingAnImportLeavesNothingBehind() {
        val store = store()
        var cancelled = false
        try {
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    store.import(ByteArrayInputStream(gguf)) { bytes -> if (bytes > 50_000) throw CancellationException("stop") }
                }
                job.join()
                cancelled = job.isCancelled
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(folder.root.walkTopDown().none { it.isFile })
        assertFalse(store.installed(LlmBackend.LLAMA_CPP) != null)
    }

    @Test fun theShippedCatalogueCoversBothBackendsWithValidChecksums() {
        for (backend in LlmBackend.entries) assertTrue(backend.name, ModelCatalog.modelsFor(backend).isNotEmpty())
        for (m in ModelCatalog.all) {
            assertTrue(m.title, Regex("[0-9a-f]{64}").matches(m.sha256))
            assertTrue(m.title, m.downloadUrl.startsWith("https://huggingface.co/") && m.downloadUrl.endsWith(m.fileName))
            assertTrue("the download is pinned to a commit, not a branch", Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(m.downloadUrl))
            assertTrue(m.title, m.sizeBytes in 1_000_000L..ModelStoreTest.MAX_CATALOG_BYTES)
            assertTrue("every model says what it costs in RAM: ${m.ramNote}", m.ramNote.contains("RAM"))
        }
        // The default is the lightest option, so a modest phone always has something that will run.
        for (backend in LlmBackend.entries) assertEquals(ModelCatalog.modelsFor(backend).first(), ModelCatalog.defaultFor(backend))
    }

    @Test fun theCatalogueHasNoDuplicateFileNamesPerBackend() {
        for (backend in LlmBackend.entries) {
            val names = ModelCatalog.modelsFor(backend).map { it.fileName }
            assertEquals(names.size, names.toSet().size)
        }
    }

    private companion object {
        /** ModelStore's ceiling for a file it will accept; the catalogue must stay inside it. */
        val MAX_CATALOG_BYTES = 4L * 1024 * 1024 * 1024
    }
}
