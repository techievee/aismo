package aismo.sms.domain.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A unit vector stored as one signed byte per component plus the scale that restores it. */
class QuantizedVector(val bytes: ByteArray, val scale: Float)

/** Math on embedding vectors; pure, so it is the same on the phone and in the unit tests. */
object VectorMath {
    /** Scales [v] to length 1 in place. A zero vector is left alone. */
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum)
        if (norm > 0.0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
        return v
    }

    /** One byte per component, so a 384-dimension vector costs 388 bytes instead of 1,536. */
    fun quantize(v: FloatArray): QuantizedVector {
        var peak = 0f
        for (x in v) peak = max(peak, abs(x))
        if (peak == 0f) return QuantizedVector(ByteArray(v.size), 0f)
        val scale = peak / 127f
        val bytes = ByteArray(v.size) { (v[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }
        return QuantizedVector(bytes, scale)
    }

    /** The dot product of a float [query] with a quantized vector; for unit vectors this is the cosine similarity. */
    fun dot(query: FloatArray, bytes: ByteArray, scale: Float): Float {
        require(query.size == bytes.size) { "vectors differ in length: ${query.size} and ${bytes.size}" }
        var sum = 0f
        for (i in query.indices) sum += query[i] * bytes[i]
        return sum * scale
    }
}

/** Keeps the [limit] best-scoring ids seen, using memory proportional to the limit and not to what was scanned. */
class TopScores(private val limit: Int) {
    private val ids = LongArray(limit)
    private val scores = FloatArray(limit)
    var size = 0
        private set

    init {
        require(limit > 0) { "limit must be positive" }
    }

    fun offer(id: Long, score: Float) {
        if (size < limit) {
            insert(size, id, score); size++
        } else if (score > scores[size - 1]) {
            insert(size - 1, id, score)
        }
    }

    /** Places the entry at [slot] and moves it up past lower scores, keeping the arrays sorted best first. */
    private fun insert(slot: Int, id: Long, score: Float) {
        var i = slot
        while (i > 0 && scores[i - 1] < score) {
            ids[i] = ids[i - 1]; scores[i] = scores[i - 1]; i--
        }
        ids[i] = id; scores[i] = score
    }

    /** Best first. */
    fun toList(): List<Pair<Long, Float>> = List(size) { ids[it] to scores[it] }
}
