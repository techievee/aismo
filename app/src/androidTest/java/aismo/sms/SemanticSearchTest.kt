package aismo.sms

import android.app.role.RoleManager
import android.content.ContentValues
import android.provider.Telephony
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.ai.EmbeddingModel
import aismo.sms.data.ai.SemanticIndex
import aismo.sms.data.db.MessageEmbeddingEntity

/**
 * Search by meaning with the real bundled model against the real SMS provider; needs the default-SMS role.
 * The reference vectors and the fixture come from the official Python tokenizer and ONNX Runtime (see assets/ai).
 */
@RunWith(AndroidJUnit4::class)
class SemanticSearchTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val index get() = c.semanticIndex
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Before fun setUp() {
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            // The background job would index messages while the test counts them.
            c.settings.update { it.copy(smartSearch = false) }
            WorkManager.getInstance(app).cancelUniqueWork("embedding").result.get()
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty())
            c.repository.syncRecent()
            c.db.embeddings().clear()
        }
    }

    @After fun tearDown() = runBlocking { c.db.embeddings().clear(); c.settings.update { it.copy(smartSearch = true) }; Unit }

    private fun json(name: String) = Json.parseToJsonElement(assets.open("ai/$name").bufferedReader().readText())

    /** Stores the fixture as incoming messages and returns fixture id -> index id. */
    private suspend fun storeFixture(): Map<Int, Long> {
        val now = System.currentTimeMillis()
        val ids = HashMap<Int, Long>()
        for (m in json("search-fixture.json").jsonObject["messages"]!!.jsonArray) {
            val fixtureId = m.jsonObject["id"]!!.jsonPrimitive.int
            val text = m.jsonObject["text"]!!.jsonPrimitive.content
            ids[fixtureId] = c.provider.insertIncoming("+6141100${1000 + fixtureId}", text, now - fixtureId * 60_000L, now - fixtureId * 60_000L, -1)
        }
        c.repository.syncNew()
        return ids
    }

    private suspend fun indexEverything(): Int {
        var total = 0
        var before = Long.MAX_VALUE
        while (true) {
            val batch = index.indexBatch(before)
            total += batch.indexed
            before = batch.next ?: break
        }
        return total
    }

    @Test fun theModelOnThePhoneReproducesTheReferenceVectors() = runBlocking {
        val cases = json("embedding-golden.json").jsonArray
        var worst = 1.0
        val all = ArrayList<String>()
        for (case in cases) {
            val text = case.jsonObject["text"]!!.jsonPrimitive.content
            val reference = case.jsonObject["vector"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }
            val actual = c.embedder.embed(text)
            assertEquals(EmbeddingModel.DIMENSION, actual.size)
            val cosine = actual.indices.sumOf { (actual[it] * reference[it]).toDouble() }
            worst = minOf(worst, cosine)
            all += "%.4f".format(cosine)
        }
        Log.i("SemanticGolden", all.joinToString(" "))
        // The int8 model is not bit-exact across CPUs (different integer kernels); a wrong tokenizer or pooling would score far lower.
        assertTrue("worst cosine against the reference is $worst", worst > 0.99)
    }

    @Test fun vectorsAreUnitLength() = runBlocking {
        val v = c.embedder.embed("Your parcel has been delivered.")
        assertEquals(1.0, v.sumOf { (it * it).toDouble() }, 1e-4)
    }

    @Test fun searchFindsMessagesByMeaningNotByWords() = runBlocking {
        val ids = storeFixture()
        assertEquals(40, indexEverything())
        val fixture = json("search-fixture.json").jsonObject
        val queries = fixture["queries"]!!.jsonArray.map {
            it.jsonObject["query"]!!.jsonPrimitive.content to it.jsonObject["relevant"]!!.jsonArray.map { r -> ids.getValue(r.jsonPrimitive.int) }.toSet()
        }
        var first = 0; var top3 = 0
        val precisions = ArrayList<Double>(); val recalls = ArrayList<Double>()
        for ((query, relevant) in queries) {
            val hits = index.search(query).map { it.messageId }
            val rank = hits.indexOfFirst { it in relevant }
            if (rank == 0) first++
            if (rank in 0..2) top3++
            precisions += if (hits.isEmpty()) 0.0 else hits.count { it in relevant }.toDouble() / hits.size
            recalls += relevant.count { it in hits }.toDouble() / relevant.size
        }
        val precision = precisions.average()
        val recall = recalls.average()
        Log.i("SemanticQuality", "queries=${queries.size} first=$first top3=$top3 precision=$precision recall=$recall")
        assertTrue("the best match is relevant for $first of ${queries.size} queries", first >= 11)
        assertTrue("a relevant message is in the top three for $top3 of ${queries.size} queries", top3 >= 12)
        assertTrue("average precision per query is $precision", precision >= 0.7)
        assertTrue("average recall per query is $recall", recall >= 0.65)
    }

    @Test fun aQueryWithNoAnswerShowsNothing() = runBlocking {
        storeFixture(); indexEverything()
        for (query in json("search-fixture.json").jsonObject["noMatch"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            val hits = index.search(query)
            assertTrue("\"$query\" should have no similar messages, got ${hits.size}", hits.isEmpty())
        }
    }

    @Test fun similarMessagesSkipWhatAKeywordSearchAlreadyFound() = runBlocking {
        val ids = storeFixture(); indexEverything()
        val all = index.similarMessages("electricity bill").map { it.id }
        assertTrue("the AGL bill is similar", ids.getValue(1) in all)
        val without = index.similarMessages("electricity bill", exclude = setOf(ids.getValue(1))).map { it.id }
        assertFalse(ids.getValue(1) in without)
    }

    @Test fun indexingWalksNewestFirstInBatchesAndNeverRepeats() = runBlocking {
        storeFixture()
        val first = index.indexBatch(Long.MAX_VALUE, limit = 10)
        assertEquals(10, first.indexed)
        val stored = c.db.embeddings().page(EmbeddingModel.VERSION, Long.MIN_VALUE, 100).map { it.messageId }
        assertEquals("the ten highest ids come first", c.db.messages().pageAfter(0, 100).map { it.id }.sortedDescending().take(10).sorted(), stored)
        var next = first.next
        var total = first.indexed
        while (next != null) { val b = index.indexBatch(next, limit = 10); total += b.indexed; next = b.next }
        assertEquals(40, total)
        assertEquals("a second pass finds nothing left to do", 0, index.indexBatch(Long.MAX_VALUE).indexed)
        val coverage = index.coverage.first { it.indexed == 40 }
        assertEquals(40, coverage.searchable)
    }

    @Test fun aVectorFromAnOlderModelVersionIsRebuilt() = runBlocking {
        storeFixture(); indexEverything()
        val one = c.db.embeddings().page(EmbeddingModel.VERSION, Long.MIN_VALUE, 1).single()
        c.db.embeddings().upsertAll(listOf(MessageEmbeddingEntity(one.messageId, EmbeddingModel.VERSION - 1, one.scale, one.vector)))
        assertEquals(39, c.db.embeddings().page(EmbeddingModel.VERSION, Long.MIN_VALUE, 100).size)
        assertEquals(1, index.indexBatch(Long.MAX_VALUE).indexed)
        assertEquals(40, c.db.embeddings().page(EmbeddingModel.VERSION, Long.MIN_VALUE, 100).size)
    }

    @Test fun deletingMessagesDeletesTheirVectors() = runBlocking {
        val ids = storeFixture(); indexEverything()
        c.repository.deleteMessages(listOf(ids.getValue(1), ids.getValue(2)))
        val remaining = c.db.embeddings().page(EmbeddingModel.VERSION, Long.MIN_VALUE, 100).map { it.messageId }
        assertEquals(38, remaining.size)
        assertFalse(ids.getValue(1) in remaining)
        assertEquals("an orphan left by a race is cleaned up", 0, index.removeOrphans())
    }

    @Test fun editingAMessageTextDropsItsOutOfDateVector() = runBlocking {
        val ids = storeFixture(); indexEverything()
        val id = ids.getValue(16)
        val values = ContentValues().apply { put(Telephony.Sms.BODY, "Your flight to Perth is delayed by two hours") }
        assertEquals(1, app.contentResolver.update(Telephony.Sms.CONTENT_URI, values, "_id=?", arrayOf(id.toString())))
        c.repository.refresh(listOf(id))
        assertEquals(39, c.db.embeddings().page(EmbeddingModel.VERSION, Long.MIN_VALUE, 100).size)
        assertEquals(1, index.indexBatch(Long.MAX_VALUE).indexed)
        val hits = index.search("flight delayed").map { it.messageId }
        assertTrue("the edited message is now found as a flight message: $hits", id in hits)
    }

    @Test fun aBlankTextIsMarkedDoneAndNeverMatches() = runBlocking {
        val now = System.currentTimeMillis()
        c.provider.insertIncoming("+61411000999", "  \n ", now, now, -1)
        c.provider.insertIncoming("+61411000998", "real words here", now - 1000, now - 1000, -1)
        c.repository.syncNew()
        // Whitespace is still text as far as the database can tell, so it gets a marker vector that matches nothing
        // and is not picked up again.
        assertEquals(2, indexEverything())
        val blank = c.db.embeddings().page(EmbeddingModel.VERSION, Long.MIN_VALUE, 10).single { it.scale == 0f }
        assertTrue(blank.vector.all { it.toInt() == 0 })
        assertEquals(0, index.indexBatch(Long.MAX_VALUE).indexed)
        assertTrue(index.search("   ").isEmpty())
        assertTrue("the blank message never matches", index.search("real words here").none { it.messageId == blank.messageId })
    }

    @Test fun timing() = runBlocking {
        c.embedder.embed("warm up the session")
        val texts = json("search-fixture.json").jsonObject["messages"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        val start = System.nanoTime()
        texts.forEach { c.embedder.embed(it) }
        val perText = (System.nanoTime() - start) / 1e6 / texts.size
        storeFixture(); indexEverything()
        val searchStart = System.nanoTime()
        repeat(10) { index.search("power bill") }
        val perSearch = (System.nanoTime() - searchStart) / 1e6 / 10
        Log.i("SemanticTiming", "embed=%.1f ms per message, search over 40 vectors=%.1f ms".format(perText, perSearch))
        assertTrue("embedding takes $perText ms per message", perText < 1000)
        assertNull(index.unavailable.value)
    }

    @Test fun searchStaysFastOnALargeInbox() = runBlocking {
        // Random vectors stand in for 50,000 messages: the cost being measured is reading and scoring them.
        val rng = java.util.Random(42)
        val rows = List(50_000) { i -> MessageEmbeddingEntity(1_000_000L + i, EmbeddingModel.VERSION, 0.01f, ByteArray(EmbeddingModel.DIMENSION).also { rng.nextBytes(it) }) }
        rows.chunked(5_000).forEach { c.db.embeddings().upsertAll(it) }
        index.search("warm up")
        val start = System.nanoTime()
        repeat(3) { index.search("power bill") }
        val perSearch = (System.nanoTime() - start) / 1e6 / 3
        val bytes = 50_000L * (EmbeddingModel.DIMENSION + 4)
        Log.i("SemanticScale", "50000 vectors (%.1f MB): %.0f ms per search".format(bytes / 1048576.0, perSearch))
        assertTrue("a search over 50,000 messages took $perSearch ms", perSearch < 5_000)
    }
}
