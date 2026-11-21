package aismo.sms.data.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import aismo.sms.domain.llm.LlmBackend
import aismo.sms.domain.rephrase.RephraseMode
import aismo.sms.domain.rephrase.RephraseOutcome
import aismo.sms.domain.rephrase.RephrasePrompts
import aismo.sms.domain.rephrase.Rephraser
import aismo.sms.domain.llm.TextGenerator
import aismo.sms.domain.translate.Language
import aismo.sms.domain.translate.TranslationPrompts
import aismo.sms.domain.translate.TranslationResult
import aismo.sms.domain.translate.Translator
import java.io.File

/**
 * The one place that owns the loaded language model, shared by the writing assistant and translation. It picks the
 * model of the chosen backend, loads it on the first request, answers one request at a time (a phone cannot
 * usefully run two), and frees the memory again after [idleMillis] without use. A model whose release failed stays
 * owned here so the next release retries it.
 */
class RephraseService(
    private val models: ModelStore,
    private val chosenBackend: suspend () -> LlmBackend,
    private val prompts: () -> RephrasePrompts,
    private val translationPrompts: TranslationPrompts,
    private val newGenerator: (LlmBackend, File) -> TextGenerator,
    private val scope: CoroutineScope,
    private val idleMillis: Long = IDLE_MILLIS,
) {
    private class Loaded(val backend: LlmBackend, val file: File, val generator: TextGenerator)

    private val lock = Mutex()
    private var loaded: Loaded? = null
    private var idleJob: Job? = null

    /**
     * [variation] is 0 for the first answer and higher for "Try again". [context] is the recent messages of the thread
     * the reply belongs to, so the model can write the rewrite with the conversation in mind. Cancelling the caller stops
     * the model. Whatever else goes wrong (a model that will not load or release, a missing prompt file) comes back as
     * [RephraseOutcome.Unavailable], so a tap on the button can never crash the app.
     */
    suspend fun rephrase(mode: RephraseMode, draft: String, context: String? = null, variation: Int = 0): RephraseOutcome {
        val backend = chosenBackend()
        val model = models.installed(backend) ?: return RephraseOutcome.NoModel(backend)
        return lock.withLock {
            idleJob?.cancel()
            try {
                Rephraser(prompts(), generatorFor(backend, model.file)).rephrase(mode, draft, context, variation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RephraseOutcome.Unavailable(e.message ?: "the writing assistant failed")
            } finally {
                scheduleUnload()
            }
        }
    }

    /**
     * Translates [text] into [target]; [source] is null when it was guessed rather than picked. Like [rephrase], the
     * caller's cancellation stops the model and nothing here can crash the app.
     */
    suspend fun translate(text: String, source: Language?, target: Language, variation: Int = 0): TranslationResult {
        val backend = chosenBackend()
        val model = models.installed(backend) ?: return TranslationResult.NoModel(backend)
        return lock.withLock {
            idleJob?.cancel()
            try {
                Translator(translationPrompts, generatorFor(backend, model.file)).translate(text, source, target, variation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslationResult.Unavailable(e.message ?: "the translation failed")
            } finally {
                scheduleUnload()
            }
        }
    }

    /** Frees the model of [backend] if it is loaded; called when its file is replaced or removed. */
    suspend fun release(backend: LlmBackend) = lock.withLock {
        if (loaded?.backend == backend) unloadLocked()
    }

    /** Frees whatever is loaded, for when the system asks for memory back. */
    suspend fun releaseAll() = lock.withLock { unloadLocked() }

    private suspend fun generatorFor(backend: LlmBackend, file: File): TextGenerator {
        loaded?.takeIf { it.backend == backend && it.file == file }?.let { return it.generator }
        unloadLocked()   // never two models in memory at once
        return newGenerator(backend, file).also { loaded = Loaded(backend, file, it) }
    }

    private suspend fun unloadLocked() {
        val current = loaded ?: return
        current.generator.close()
        loaded = null   // only after close succeeded, so a failed close is retried
    }

    private fun scheduleUnload() {
        idleJob = scope.launch {
            delay(idleMillis)
            lock.withLock { runCatching { unloadLocked() } }
        }
    }

    companion object {
        const val IDLE_MILLIS = 2 * 60_000L
    }
}
