package aismo.sms.data.llm

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import aismo.sms.domain.llm.ChatMessage
import aismo.sms.domain.llm.ChatRole
import aismo.sms.domain.llm.GenerationException
import aismo.sms.domain.llm.ModelOutput
import aismo.sms.domain.llm.TextGenerator
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Runs a GGUF model with llama.cpp on the CPU. The model is loaded on first use and kept until [close]. */
class LlamaCppGenerator(private val context: Context, private val modelFile: File) : TextGenerator {
    private val lock = Mutex()
    private var handle = 0L

    /** Timing of the last answer, for the quality harness and the settings screen: "prompt_tokens=… generate_ms=…". */
    var lastStats: String = ""
        private set

    override suspend fun generate(messages: List<ChatMessage>, maxTokens: Int, temperature: Float, seed: Int): String = lock.withLock {
        withContext(Dispatchers.Default) {
            val model = ensureLoaded()
            val roles = messages.map { it.role.name.lowercase().toByteArray(Charsets.UTF_8) }.toTypedArray()
            val texts = messages.map { it.text.toByteArray(Charsets.UTF_8) }.toTypedArray()
            val finished = AtomicBoolean(false)
            coroutineScope {
                // Cancelling the caller stops the native loop at once; a normal finish must not.
                val watcher = launch(Dispatchers.Default) {
                    try { awaitCancellation() } catch (e: CancellationException) { if (!finished.get()) LlamaNative.cancel(model); throw e }
                }
                try {
                    val reply = LlamaNative.generate(model, roles, texts, maxTokens, temperature, seed)
                    lastStats = LlamaNative.lastStats(model)
                    if (reply == null) {
                        val why = LlamaNative.lastError()
                        if (why == "cancelled") throw CancellationException("generation cancelled")
                        throw GenerationException(why)
                    }
                    // A thinking model may still reason before answering; the user wants the answer only.
                    ModelOutput.withoutThinking(String(reply, Charsets.UTF_8))
                } finally {
                    finished.set(true)
                    watcher.cancel()
                }
            }
        }
    }

    private fun ensureLoaded(): Long {
        if (handle != 0L) return handle
        if (!modelFile.isFile) throw GenerationException("the model file is missing")
        try {
            synchronized(LlamaNative) { if (!initialised) initialised = LlamaNative.init(context.applicationInfo.nativeLibraryDir) }
        } catch (e: LinkageError) { // no native library for this device's architecture
            throw GenerationException("the writing assistant is not supported on this device", e)
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
        val loaded = LlamaNative.load(modelFile.path, CONTEXT_TOKENS, threads)
        if (loaded == 0L) throw GenerationException(LlamaNative.lastError())
        handle = loaded
        return loaded
    }

    override suspend fun close() = lock.withLock {
        if (handle != 0L) { LlamaNative.free(handle); handle = 0L }
    }

    private companion object {
        /** The instructions and examples (about 450 tokens), a draft of a few hundred characters, and the reply. */
        const val CONTEXT_TOKENS = 2048
        var initialised = false
    }
}
