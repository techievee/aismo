package aismo.sms.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import aismo.sms.data.db.MessageEmbeddingEntity
import aismo.sms.data.db.MessageEntity
import aismo.sms.data.db.AismoDatabase
import aismo.sms.domain.ai.EmbedderUnavailableException
import aismo.sms.domain.ai.TextEmbedder
import aismo.sms.domain.ai.TopScores
import aismo.sms.domain.ai.VectorMath

/**
 * Search by meaning: owns the stored vectors. [indexBatch] makes vectors for messages that lack one, [search]
 * compares a query with all of them. Everything runs on the phone; nothing here touches the network.
 */
class SemanticIndex(private val db: AismoDatabase, private val embedder: TextEmbedder) {
    private val dao get() = db.embeddings()

    data class Hit(val messageId: Long, val score: Float)

    /** How much of the inbox can be searched by meaning so far. */
    data class Coverage(val indexed: Int, val searchable: Int)

    /** [next] is where the following batch should start (walking down), or null once nothing is left. */
    data class Batch(val indexed: Int, val failed: Int, val next: Long?)

    private val problem = MutableStateFlow<String?>(null)

    /** Why search by meaning cannot work on this device, or null while it can. */
    val unavailable: StateFlow<String?> get() = problem

    val coverage: Flow<Coverage> = combine(dao.observeIndexed(EmbeddingModel.VERSION), dao.observeSearchable(), ::Coverage).distinctUntilChanged()

    /** Makes vectors for up to [limit] messages below id [before], newest first. Throws [EmbedderUnavailableException] if the model cannot run. */
    suspend fun indexBatch(before: Long, limit: Int = BATCH): Batch {
        val pending = dao.pending(EmbeddingModel.VERSION, before, limit)
        if (pending.isEmpty()) return Batch(0, 0, null)
        val rows = ArrayList<MessageEmbeddingEntity>(pending.size)
        var failed = 0
        for (message in pending) {
            currentCoroutineContext().ensureActive()
            val vector = try {
                // A text with no content gets a vector that matches nothing, so it is not picked up again.
                if (message.body.isBlank()) VectorMath.quantize(FloatArray(EmbeddingModel.DIMENSION)) else VectorMath.quantize(embedder.embed(message.body))
            } catch (e: EmbedderUnavailableException) {
                problem.value = e.message
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++ // one text the model cannot handle must not stop the rest; it is retried by the next run
                continue
            }
            rows += MessageEmbeddingEntity(message.id, EmbeddingModel.VERSION, vector.scale, vector.bytes)
        }
        dao.upsertAll(rows)
        if (rows.isNotEmpty()) problem.value = null
        return Batch(rows.size, failed, pending.last().id)
    }

    /** Vectors left behind by messages that were deleted while their vector was being made. */
    suspend fun removeOrphans() = dao.deleteOrphans()

    /** Drops every vector, for when the user turns search by meaning off. */
    suspend fun clear() = dao.clear()

    /**
     * The messages whose meaning is closest to [query], best first: at least [minScore] similar, and within [window] of the
     * best match so a query with one clear answer does not also list loosely related messages.
     */
    suspend fun search(query: String, limit: Int = RESULT_LIMIT, minScore: Float = MIN_SCORE, window: Float = WINDOW): List<Hit> {
        if (query.isBlank()) return emptyList()
        val vector = try {
            embedder.embed(query)
        } catch (e: EmbedderUnavailableException) {
            problem.value = e.message
            return emptyList()
        }
        val best = TopScores(limit)
        var after = Long.MIN_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.page(EmbeddingModel.VERSION, after, PAGE)
            if (page.isEmpty()) break
            for (stored in page) {
                val score = VectorMath.dot(vector, stored.vector, stored.scale)
                if (score >= minScore) best.offer(stored.messageId, score)
            }
            after = page.last().messageId
        }
        val found = best.toList()
        if (found.isEmpty()) return emptyList()
        val cutoff = maxOf(minScore, found.first().second - window)
        return found.filter { it.second >= cutoff }.map { Hit(it.first, it.second) }
    }

    /** [search] resolved to messages, skipping [exclude] (for example what a keyword search already found). */
    suspend fun similarMessages(query: String, exclude: Set<Long> = emptySet(), limit: Int = RESULT_LIMIT): List<MessageEntity> {
        val hits = search(query, limit + exclude.size).filter { it.messageId !in exclude }.take(limit)
        if (hits.isEmpty()) return emptyList()
        val byId = db.messages().getAll(hits.map { it.messageId }).associateBy { it.id }
        return hits.mapNotNull { byId[it.messageId] }
    }

    companion object {
        const val BATCH = 32
        const val PAGE = 1000
        const val RESULT_LIMIT = 30

        /**
         * Below this cosine similarity nothing is shown. With this model unrelated texts still score 0.4 to 0.6, so the
         * ranking alone cannot tell "no answer" from "a weak answer". Chosen on a small hand-made set of 40 messages and 18
         * queries (see SemanticSearchTest): about 77% of what it shows is relevant and about 71% of the relevant messages
         * are shown, with nothing shown for queries that have no answer. Expect somewhat less on real inboxes.
         */
        const val MIN_SCORE = 0.60f

        /** How far below the best match a result may score and still be shown. */
        const val WINDOW = 0.10f
    }
}
