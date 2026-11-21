package aismo.sms.data.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import aismo.sms.domain.llm.ChatMessage
import aismo.sms.domain.llm.LlmBackend
import aismo.sms.domain.rephrase.RephraseMode
import aismo.sms.domain.rephrase.RephraseOutcome
import aismo.sms.domain.rephrase.RephrasePrompts
import aismo.sms.domain.llm.TextGenerator
import aismo.sms.domain.translate.Language
import aismo.sms.domain.translate.TranslationPrompts
import aismo.sms.domain.translate.TranslationResult
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class RephraseServiceTest {
    @get:Rule val folder = TemporaryFolder()

    private val prompts = RephrasePrompts.parse(File("src/main/assets/rephrase/prompts.json").readText())
    private val translationPrompts = TranslationPrompts.parse(File("src/main/assets/translate/prompts.json").readText())
    private val bytes = ByteArray(5000) { it.toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val llama = SupportedModel(LlmBackend.LLAMA_CPP, "m", "m.bin", bytes.size.toLong(), sha, "t", "https://example.invalid/m.bin", "https://example.invalid", "about 1 GB of RAM")
    private val lite = SupportedModel(LlmBackend.LITERT_LM, "n", "n.bin", bytes.size.toLong(), sha, "t", "https://example.invalid/n.bin", "https://example.invalid", "about 1 GB of RAM")

    private class Fake(val backend: LlmBackend, val log: MutableList<String>, var concurrent: AtomicInteger, var peak: AtomicInteger) : TextGenerator {
        var closed = false
        var failClose = false
        override suspend fun generate(messages: List<ChatMessage>, maxTokens: Int, temperature: Float, seed: Int): String {
            val now = concurrent.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try { delay(30) } finally { concurrent.decrementAndGet() }
            log += "generate:${backend.key}"
            return "Please send it."
        }
        override suspend fun close() {
            if (failClose) { failClose = false; throw IllegalStateException("cannot close") }
            closed = true; log += "close:${backend.key}"
        }
    }

    private class Rig(val service: RephraseService, val made: MutableList<Fake>, val log: MutableList<String>, val peak: AtomicInteger, val backend: () -> LlmBackend, val setBackend: (LlmBackend) -> Unit)

    private fun rig(idleMillis: Long = 60_000, install: List<SupportedModel> = listOf(llama, lite)): Rig {
        // Both test models share the same bytes (the store identifies a file by hash), so each is installed through a
        // store that knows only that one entry.
        val root = folder.newFolder()
        val store = ModelStore(root, listOf(llama, lite))
        runBlocking { for (m in install) ModelStore(root, listOf(m)).import(ByteArrayInputStream(bytes)) }
        val made = mutableListOf<Fake>()
        val log = mutableListOf<String>()
        val peak = AtomicInteger()
        val concurrent = AtomicInteger()
        var backend = LlmBackend.LLAMA_CPP
        val service = RephraseService(
            store,
            chosenBackend = { backend },
            prompts = { prompts },
            translationPrompts = translationPrompts,
            newGenerator = { b, _ -> Fake(b, log, concurrent, peak).also { made += it } },
            scope = CoroutineScope(Dispatchers.Default),
            idleMillis = idleMillis,
        )
        return Rig(service, made, log, peak, { backend }, { backend = it })
    }

    @Test fun translateWithoutAnInstalledModelIsNoModelAndNothingIsLoaded() = runBlocking {
        val rig = rig(install = emptyList())
        assertEquals(TranslationResult.NoModel(LlmBackend.LLAMA_CPP), rig.service.translate("कल मिलते हैं", Language.HI, Language.EN))
        assertTrue(rig.made.isEmpty())
    }

    @Test fun translateSharesTheLoadedModelWithRephrase() = runBlocking {
        val rig = rig()
        assertTrue(rig.service.translate("कल मिलते हैं", Language.HI, Language.EN) is TranslationResult.Success)
        assertTrue(rig.service.rephrase(RephraseMode.POLITE, "send it pls") is RephraseOutcome.Suggestion)
        assertEquals("one generator serves both features", 1, rig.made.size)
    }

    @Test fun withoutAnInstalledModelTheAnswerIsNoModelAndNothingIsLoaded() = runBlocking {
        val rig = rig(install = emptyList())
        assertEquals(RephraseOutcome.NoModel(LlmBackend.LLAMA_CPP), rig.service.rephrase(RephraseMode.POLITE, "send it pls"))
        assertTrue(rig.made.isEmpty())
    }

    @Test fun theModelIsLoadedOnceAndReusedAcrossRequests() = runBlocking {
        val rig = rig()
        repeat(3) { assertTrue(rig.service.rephrase(RephraseMode.POLITE, "send it pls") is RephraseOutcome.Suggestion) }
        assertEquals(1, rig.made.size)
    }

    @Test fun twoRequestsAtOnceRunOneAfterTheOther() = runBlocking {
        val rig = rig()
        val a = async(Dispatchers.Default) { rig.service.rephrase(RephraseMode.POLITE, "send it pls") }
        val b = async(Dispatchers.Default) { rig.service.rephrase(RephraseMode.FRIENDLY, "send it pls") }
        a.await(); b.await()
        assertEquals(1, rig.peak.get())
    }

    @Test fun switchingBackendFreesTheOldModelBeforeLoadingTheNewOne() = runBlocking {
        val rig = rig()
        rig.service.rephrase(RephraseMode.POLITE, "send it pls")
        rig.setBackend(LlmBackend.LITERT_LM)
        rig.service.rephrase(RephraseMode.POLITE, "send it pls")
        assertEquals(listOf("llama", "litert"), rig.made.map { it.backend.key })
        assertTrue(rig.made[0].closed)
        assertTrue(rig.log.indexOf("close:llama") < rig.log.indexOf("generate:litert"))
    }

    @Test fun anIdleModelIsFreedAndLoadedAgainOnTheNextRequest() = runBlocking {
        val rig = rig(idleMillis = 80)
        rig.service.rephrase(RephraseMode.POLITE, "send it pls")
        delay(400)
        assertTrue(rig.made.single().closed)
        rig.service.rephrase(RephraseMode.POLITE, "send it pls")
        assertEquals(2, rig.made.size)
    }

    @Test fun releaseFreesOnlyTheNamedBackend() = runBlocking {
        val rig = rig()
        rig.service.rephrase(RephraseMode.POLITE, "send it pls")
        rig.service.release(LlmBackend.LITERT_LM)
        assertTrue(!rig.made.single().closed)
        rig.service.release(LlmBackend.LLAMA_CPP)
        assertTrue(rig.made.single().closed)
    }

    @Test fun aModelThatCannotBeCreatedIsReportedAsUnavailableNotThrown() = runBlocking {
        val root = folder.newFolder()
        runBlocking { ModelStore(root, listOf(llama)).import(ByteArrayInputStream(bytes)) }
        val service = RephraseService(
            ModelStore(root, listOf(llama)),
            chosenBackend = { LlmBackend.LLAMA_CPP },
            prompts = { prompts },
            translationPrompts = translationPrompts,
            newGenerator = { _, _ -> throw IllegalStateException("out of memory") },
            scope = CoroutineScope(Dispatchers.Default),
        )
        val outcome = service.rephrase(RephraseMode.POLITE, "send it pls")
        assertEquals(RephraseOutcome.Unavailable("out of memory"), outcome)
    }

    @Test fun aFailedReleaseIsRetriedOnTheNextRelease() = runBlocking {
        val rig = rig()
        rig.service.rephrase(RephraseMode.POLITE, "send it pls")
        rig.made.single().failClose = true
        try { rig.service.releaseAll(); throw AssertionError("expected the close failure") } catch (_: IllegalStateException) {}
        assertTrue("still owned, not closed", !rig.made.single().closed)
        rig.service.releaseAll()
        assertTrue(rig.made.single().closed)
    }
}
