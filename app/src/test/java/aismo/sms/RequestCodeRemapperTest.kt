package aismo.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCodeRemapperTest {
    /** The registry's codes start at 0x00010000 and are random above that. */
    private val highCodes = (0..49).map { 0x00010000 + it * 977 }

    @Test fun smallCodesPassThroughUntouched() {
        val m = RequestCodeRemapper()
        for (small in listOf(0, 1, 0x1F, 0xFFFF)) {
            assertEquals(small, m.remap(small))
            assertEquals(small, m.unremap(small))
        }
    }

    @Test fun aHighCodeIsTranslatedIntoTheSmallBandAndBack() {
        val m = RequestCodeRemapper()
        val high = 0x00010000 + 42
        val low = m.remap(high)
        assertTrue("the translated code must fit Android 17's 16-bit limit", low in 0x1000..0xFFFF)
        assertEquals(high, m.unremap(low))
    }

    @Test fun theSameHighCodeKeepsTheSameLowWhileInFlight() {
        val m = RequestCodeRemapper()
        val high = 0x00010000 + 7
        assertEquals(m.remap(high), m.remap(high))
    }

    @Test fun aSecondLaunchAfterTheResultReRegistersInsteadOfReusingAStalePair() {
        // The defect this guards: the pair must be gone after the result, so the next launch of the
        // same launcher gets a registered pair and its result reaches the registry.
        val m = RequestCodeRemapper()
        val high = 0x00010000 + 3
        val firstLow = m.remap(high)
        assertEquals(high, m.unremap(firstLow))
        val secondLow = m.remap(high)
        assertTrue("the second launch must have a registered pair", m.unremap(secondLow) == high)
    }

    @Test fun unremappingAnUnknownCodeIsTheIdentity() {
        val m = RequestCodeRemapper()
        m.remap(0x00010000 + 5)
        assertEquals(0x123, m.unremap(0x123))
        assertEquals(0xFFFF, m.unremap(0xFFFF))
    }

    /**
     * FragmentActivity encodes a fragment's result as `(fragmentIndex + 1) << 16 | (requestCode & 0xFFFF)`,
     * so the upper 16 bits identify the fragment. The remapper must hand each such code back *exactly* —
     * with its upper bits intact — or FragmentActivity would no longer recognise the fragment (e.g. the
     * biometric prompt) when its result comes back. MainActivity routes these straight past the registry to
     * `super`, so the code's value is load-bearing.
     */
    @Test fun fragmentEncodedCodesRoundTripWithTheirUpperBitsIntact() {
        val m = RequestCodeRemapper()
        val codes = (0..3).flatMap { index ->
            listOf(1, 0x7F, 0x1234, 0xFFFF).map { raw -> ((index + 1) shl 16) or (raw and 0xFFFF) }
        }
        val lows = codes.map { m.remap(it) }
        for ((code, low) in codes.zip(lows)) {
            assertTrue("the translated code must fit Android 17's 16-bit limit", low in 0x1000..0xFFFF)
            assertEquals("the fragment index in the upper 16 bits must survive the round trip", code, m.unremap(low))
        }
    }

    @Test fun manyLaunchersAtOnceGetDistinctLowsAndAllRoundTrip() {
        val m = RequestCodeRemapper()
        val lows = highCodes.map { m.remap(it) }
        assertEquals(highCodes.size, lows.toSet().size)
        lows.forEach { assertTrue(it in 0x1000..0xFFFF) }
        for ((high, low) in highCodes.zip(lows)) assertEquals(high, m.unremap(low))
    }

    @Test fun theBandWrapsAroundWhenExhausted() {
        val m = RequestCodeRemapper()
        m.restore(null, RequestCodeRemapper.HIGH_START) // nextLow is one past the end of the band
        val high = 0x00010000 + 1
        val low = m.remap(high)
        assertEquals(0x1000, low)
        assertEquals(high, m.unremap(low))
    }

    @Test fun inFlightPairsSurviveASaveAndRestore() {
        val m = RequestCodeRemapper()
        val lows = highCodes.take(5).map { m.remap(it) }
        val saved = m.savedPairs()
        val nextLow = m.nextLow

        val fresh = RequestCodeRemapper()
        fresh.restore(saved, nextLow)
        for ((high, low) in highCodes.take(5).zip(lows)) assertEquals(high, fresh.unremap(low))
        assertEquals(nextLow, fresh.nextLow)
    }

    @Test fun restoreClearsPreviousState() {
        val m = RequestCodeRemapper()
        m.remap(0x00010000 + 1)
        m.restore(IntArray(0))
        assertTrue(m.savedPairs().isEmpty())
    }

    @Test fun aResultForOneLauncherDoesNotConsumeAnotherLowsPair() {
        val m = RequestCodeRemapper()
        val (a, b) = listOf(0x00010000 + 11, 0x00010000 + 12)
        val la = m.remap(a)
        val lb = m.remap(b)
        assertNotEquals(la, lb)
        assertEquals(a, m.unremap(la))
        // b is still in flight and must still round-trip.
        assertEquals(b, m.unremap(lb))
    }
}
