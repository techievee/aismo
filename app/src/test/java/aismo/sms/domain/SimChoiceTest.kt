package aismo.sms.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SimChoiceTest {
    private val both = listOf(3, 7)

    @Test fun theSimJustPickedWins() = assertEquals(7, SimChoice.resolve(picked = 7, thread = 3, preferred = 3, systemDefault = 3, active = both))

    @Test fun otherwiseTheSimThisThreadLastUsed() = assertEquals(7, SimChoice.resolve(picked = null, thread = 7, preferred = 3, systemDefault = 3, active = both))

    @Test fun otherwiseTheSimChosenInSettings() = assertEquals(7, SimChoice.resolve(picked = null, thread = null, preferred = 7, systemDefault = 3, active = both))

    @Test fun otherwiseTheSystemDefault() = assertEquals(7, SimChoice.resolve(picked = null, thread = null, preferred = -1, systemDefault = 7, active = both))

    @Test fun withNothingKnownThePlatformDecides() = assertEquals(SimChoice.SYSTEM_DEFAULT, SimChoice.resolve(null, null, -1, null, both))

    @Test fun aSimThatWasRemovedIsSkipped() {
        // The thread used SIM 9, which is no longer in the phone: fall back to the next preference.
        assertEquals(3, SimChoice.resolve(picked = null, thread = 9, preferred = 3, systemDefault = 7, active = both))
        assertEquals(SimChoice.SYSTEM_DEFAULT, SimChoice.resolve(picked = 9, thread = 8, preferred = -1, systemDefault = 6, active = both))
    }

    @Test fun withOneSimTheThreadHistoryIsNotConsulted() {
        assertEquals(-1, SimChoice.resolve(picked = null, thread = 3, preferred = -1, systemDefault = 3, active = listOf(3)))
        assertEquals(5, SimChoice.resolve(picked = null, thread = 3, preferred = 5, systemDefault = 3, active = emptyList()))
    }

    @Test fun anUnusedSubscriptionIdOfMinusOneIsNeverTreatedAsASim() =
        assertEquals(7, SimChoice.resolve(picked = null, thread = -1, preferred = -1, systemDefault = 7, active = both))
}
