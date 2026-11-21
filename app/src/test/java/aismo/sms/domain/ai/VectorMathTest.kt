package aismo.sms.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class VectorMathTest {
    private fun randomUnit(rng: Random, dim: Int = 384): FloatArray =
        VectorMath.normalize(FloatArray(dim) { rng.nextGaussian().toFloat() })

    private fun exactDot(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

    @Test fun normalizeGivesLengthOne() {
        val v = VectorMath.normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
    }

    @Test fun aZeroVectorStaysZero() {
        assertEquals(listOf(0f, 0f), VectorMath.normalize(floatArrayOf(0f, 0f)).toList())
        val q = VectorMath.quantize(FloatArray(4))
        assertEquals(0f, q.scale, 0f)
        assertEquals(0f, VectorMath.dot(FloatArray(4) { 1f }, q.bytes, q.scale), 0f)
    }

    @Test fun quantizedDotProductsStayWithinHalfAPercentOfExact() {
        val rng = Random(11)
        var worst = 0f
        repeat(300) {
            val a = randomUnit(rng); val b = randomUnit(rng)
            val q = VectorMath.quantize(b)
            worst = maxOf(worst, abs(VectorMath.dot(a, q.bytes, q.scale) - exactDot(a, b)))
        }
        assertTrue("worst error $worst", worst < 0.005f)
    }

    @Test fun aVectorIsMostSimilarToItself() {
        val rng = Random(5)
        val v = randomUnit(rng)
        val q = VectorMath.quantize(v)
        assertEquals(1f, VectorMath.dot(v, q.bytes, q.scale), 0.005f)
    }

    @Test fun quantizedComponentsUseTheFullRange() {
        val q = VectorMath.quantize(floatArrayOf(0.5f, -0.2f, 0.1f))
        assertEquals(127, q.bytes[0].toInt())    // the largest magnitude maps to the end of the range
        assertEquals(-51, q.bytes[1].toInt())    // -0.2 / (0.5 / 127) = -50.8
        assertEquals(25, q.bytes[2].toInt())     //  0.1 / (0.5 / 127) =  25.4
    }

    @Test fun topScoresKeepsTheBestInOrder() {
        val top = TopScores(3)
        listOf(1L to 0.2f, 2L to 0.9f, 3L to 0.5f, 4L to 0.7f, 5L to 0.1f, 6L to 0.95f).forEach { top.offer(it.first, it.second) }
        assertEquals(listOf(6L, 2L, 4L), top.toList().map { it.first })
        assertEquals(3, top.size)
    }

    @Test fun topScoresWithFewerEntriesThanTheLimit() {
        val top = TopScores(10)
        top.offer(1, 0.1f); top.offer(2, 0.3f)
        assertEquals(listOf(2L, 1L), top.toList().map { it.first })
    }

    @Test fun topScoresMatchesASortOnRandomInput() {
        val rng = Random(3)
        val all = List(5000) { it.toLong() to rng.nextFloat() }
        val top = TopScores(25)
        all.forEach { top.offer(it.first, it.second) }
        assertEquals(all.sortedByDescending { it.second }.take(25).map { it.first }, top.toList().map { it.first })
    }

    @Test(expected = IllegalArgumentException::class) fun dotRejectsDifferentLengths() {
        VectorMath.dot(FloatArray(3), ByteArray(4), 1f)
    }
}
