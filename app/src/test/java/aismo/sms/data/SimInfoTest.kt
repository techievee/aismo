package aismo.sms.data

import org.junit.Assert.assertEquals
import org.junit.Test
import aismo.sms.data.sms.SimInfo

class SimInfoTest {
    @Test fun aNamedSimShowsItsSlotNameAndNumber() {
        val sim = SimInfo(subId = 3, slot = 1, name = "Optus", number = "+61 411 000 111")
        assertEquals("SIM 1 · Optus", sim.title)
        assertEquals("+61 411 000 111", sim.detail)
        assertEquals("SIM 1 · Optus · +61 411 000 111", sim.label)
    }

    @Test fun twoSimsFromTheSameCarrierCanStillBeToldApart() {
        val a = SimInfo(3, 1, "Telstra", "+61 411 000 111")
        val b = SimInfo(7, 2, "Telstra", "+61 422 000 222")
        assertEquals(listOf("SIM 1 · Telstra · +61 411 000 111", "SIM 2 · Telstra · +61 422 000 222"), listOf(a, b).map { it.label })
    }

    @Test fun aSimWithoutANumberSaysSo() {
        val sim = SimInfo(3, 2, "Vodafone", null)
        assertEquals("Number not available", sim.detail)
        assertEquals("SIM 2 · Vodafone", sim.label)
    }

    @Test fun aSimWithoutANameIsJustItsSlot() {
        assertEquals("SIM 2", SimInfo(7, 2, "", "+61 422 000 222").title)
        assertEquals("a name that only repeats the slot adds nothing", "SIM 1", SimInfo(3, 1, "SIM 1", null).title)
        assertEquals("SIM 2 · +61 422 000 222", SimInfo(7, 2, "  ", "+61 422 000 222").label)
    }
}
