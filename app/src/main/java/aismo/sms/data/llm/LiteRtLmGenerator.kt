package aismo.sms.data.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
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

/** Runs a `.litertlm` model with Google's LiteRT-LM on the CPU. The engine is created on first use and kept until [close]. */
class LiteRtLmGenerator(private val context: Context, private val modelFile: File) : TextGenerator {
    private val lock = Mutex()
    private var engine: Engine? = null

    /** How long the last answer took, in the same spirit as the llama.cpp generator's statistics. */
    var lastStats: String = ""
        private set

    override suspend fun generate(messages: List<ChatMessage>, maxTokens: Int, temperature: Float, seed: Int): String = lock.withLock {
        withContext(Dispatchers.Default) {
            val engine = ensureEngine()
            val system = messages.firstOrNull { it.role == ChatRole.SYSTEM }?.text.orEmpty()
            val turns = messages.filter { it.role != ChatRole.SYSTEM }
            val request = turns.lastOrNull()?.takeIf { it.role == ChatRole.USER } ?: throw GenerationException("nothing to answer")
            // Earlier turns (the worked examples) become the conversation's history; the last one is the question.
            val history = turns.dropLast(1).map { if (it.role == ChatRole.USER) Message.user(it.text) else Message.model(it.text) }
            val config = ConversationConfig(
                systemInstruction = Contents.of(system),
                initialMessages = history,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature.toDouble(), seed = seed),
                maxOutputToken = maxTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            val started = System.nanoTime()
            try {
                engine.createConversation(config).use { conversation ->
                    val finished = AtomicBoolean(false)
                    coroutineScope {
                        val watcher = launch(Dispatchers.Default) {
                            try { awaitCancellation() } catch (e: CancellationException) { if (!finished.get()) conversation.cancelProcess(); throw e }
                        }
                        try {
                            val reply = conversation.sendMessage(request.text)
                            // The thinking flag is off above, but a stubborn model may reason anyway; the user gets the answer only.
                            ModelOutput.withoutThinking(reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                        } finally {
                            finished.set(true)
                            watcher.cancel()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw GenerationException("the model failed: ${e.message}", e)
            }.also { lastStats = "generate_ms=${(System.nanoTime() - started) / 1_000_000}" }
        }
    }

    private fun ensureEngine(): Engine {
        engine?.let { return it }
        if (!modelFile.isFile) throw GenerationException("the model file is missing")
        return try {
            Engine(EngineConfig(modelPath = modelFile.path, backend = Backend.CPU(), maxNumTokens = CONTEXT_TOKENS, cacheDir = context.cacheDir.path))
                .also { it.initialize(); engine = it }
        } catch (e: LinkageError) {
            throw GenerationException("the writing assistant is not supported on this device", e)
        } catch (e: Exception) {
            throw GenerationException("could not load the model: ${e.message}", e)
        }
    }

    override suspend fun close() = lock.withLock {
        engine?.close()
        engine = null
    }

    private companion object {
        const val CONTEXT_TOKENS = 2048
    }
}
