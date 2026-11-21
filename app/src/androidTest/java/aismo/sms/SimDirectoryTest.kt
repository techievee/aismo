package aismo.sms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The SIM list as the phone really reports it: a name, a slot, and the number written the way people read it. */
@RunWith(AndroidJUnit4::class)
class SimDirectoryTest {
    private val sims get() = ApplicationProvider.getApplicationContext<AismoApp>().container.sims

    @Test fun theRealSimShowsItsSlotNameAndAFormattedNumber() {
        val active = sims.active()
        assumeTrue("needs a SIM", active.isNotEmpty())
        val sim = active.first()
        assertEquals(1, sim.slot)
        assertTrue("the title names the slot: ${sim.title}", sim.title.startsWith("SIM 1"))
        val number = sim.number
        if (number != null) {
            assertTrue("written as an international number: $number", number.startsWith("+"))
            assertTrue("with separators, not a bare digit string: $number", number.any { it == ' ' || it == '-' })
        }
        assertTrue("one line carries both: ${sim.label}", sim.label.startsWith(sim.title))
    }

    @Test fun simsAreListedInSlotOrder() {
        val slots = sims.active().map { it.slot }
        assertEquals(slots.sorted(), slots)
    }

    @Test fun theSystemDefaultSimIsOneOfTheActiveOnes() {
        val active = sims.active()
        assumeTrue("needs a SIM", active.isNotEmpty())
        val default = sims.systemDefaultSmsSubId()
        assertTrue("default $default among ${active.map { it.subId }}", default == null || default in active.map { it.subId })
    }
}
