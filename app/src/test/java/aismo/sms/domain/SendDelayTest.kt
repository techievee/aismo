package aismo.sms.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendDelayTest {
    @Test fun offIsTheDefaultSoNothingChangesUntilTheUserAsksForADelay() {
        assertEquals(0, SendDelay.DEFAULT)
        assertTrue(SendDelay.DEFAULT in SendDelay.OPTIONS)
    }

    @Test fun optionsAreSortedAndStartWithOff() {
        assertEquals(0, SendDelay.OPTIONS.first())
        assertEquals(SendDelay.OPTIONS.sorted(), SendDelay.OPTIONS)
        assertEquals(SendDelay.OPTIONS.size, SendDelay.OPTIONS.toSet().size)
    }

    @Test fun labelsAreReadable() {
        assertEquals("Off", SendDelay.label(0))
        assertEquals("Off", SendDelay.label(-3))
        assertEquals("10 seconds", SendDelay.label(10))
    }

    @Test fun aValueThatIsNotAnOptionBecomesTheNearestOne() {
        for (option in SendDelay.OPTIONS) assertEquals(option, SendDelay.normalise(option))
        assertEquals(5, SendDelay.normalise(6))
        assertEquals(30, SendDelay.normalise(3600))
        assertEquals(0, SendDelay.normalise(-5))
    }
}
