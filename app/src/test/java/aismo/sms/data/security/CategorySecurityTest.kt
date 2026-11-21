package aismo.sms.data.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import aismo.sms.data.settings.AppSettings
import aismo.sms.data.settings.UnlockMethod

@OptIn(ExperimentalCoroutinesApi::class)
class CategorySecurityTest {
    private class FakePinStore : PinStore {
        var record: String? = null
        override fun has() = record != null
        override fun record() = record
        override fun store(record: String) { this.record = record }
        override fun clear() { record = null }
    }

    private class Rig(scope: CoroutineScope, now: () -> Long, val store: FakePinStore, enrolled: () -> Boolean) {
        var settings = AppSettings()
        val security = CategorySecurity(scope, now, store, enrolled)
    }

    private fun TestScope.rig(store: FakePinStore = FakePinStore(), enrolled: () -> Boolean = { false }) =
        Rig(backgroundScope, { currentTime }, store, enrolled)

    @Test fun aSetPinIsVerifiedAndAWrongOneIsNot() = runTest {
        val r = rig()
        r.security.setPin("1234")
        assertTrue(r.store.has())
        assertTrue(r.security.verifyPin("1234"))
        assertFalse(r.security.verifyPin("9999"))
    }

    @Test fun fiveWrongPinsLockOutForFiveMinutes() = runTest {
        val r = rig()
        r.security.setPin("1234")
        repeat(CategorySecurity.MAX_FAILURES) { assertFalse(r.security.verifyPin("9999")) }
        assertFalse("the right PIN is refused while locked out", r.security.verifyPin("1234"))
        advanceTimeBy(CategorySecurity.PIN_LOCKOUT_MS - 1_000); runCurrent()
        assertFalse(r.security.verifyPin("1234"))
        advanceTimeBy(1_000); runCurrent()
        assertTrue(r.security.verifyPin("1234"))
    }

    @Test fun changingThePinRequiresTheCurrentOne() = runTest {
        val r = rig()
        r.security.setPin("1234")
        assertFalse(r.security.changePin("9999", "5678"))
        assertTrue(r.security.changePin("1234", "5678"))
        assertTrue(r.security.verifyPin("5678"))
    }

    @Test fun clearingThePinClearsTheStoreAndLocks() = runTest {
        val r = rig()
        r.security.setPin("1234")
        r.security.unlock(60_000)
        r.security.clearPin()
        assertFalse(r.store.has())
        assertFalse("clearing the PIN closes an open window", r.security.unlocked.value)
    }

    @Test fun anUnlockKeepsItsWindowThenLocks() = runTest {
        val r = rig()
        r.security.unlock(60_000)
        assertTrue(r.security.unlocked.value)
        advanceTimeBy(59_999); runCurrent()
        assertTrue(r.security.unlocked.value)
        advanceTimeBy(1); runCurrent()
        assertFalse("the window elapsed", r.security.unlocked.value)
    }

    @Test fun aSecondUnlockRestartsTheWindow() = runTest {
        val r = rig()
        r.security.unlock(60_000)
        advanceTimeBy(30_000); runCurrent()
        r.security.unlock(60_000)
        advanceTimeBy(40_000); runCurrent()
        assertTrue("the restarted window is still running", r.security.unlocked.value)
        advanceTimeBy(20_000); runCurrent()
        assertFalse(r.security.unlocked.value)
    }

    @Test fun recheckLocksAnExpiredWindowButNotARunningOne() = runTest {
        val r = rig()
        r.security.unlock(60_000)
        r.security.recheck()
        assertTrue(r.security.unlocked.value)
        advanceTimeBy(61_000); runCurrent()
        r.security.recheck()
        assertFalse(r.security.unlocked.value)
    }

    @Test fun lockingIsImmediateAndSticky() = runTest {
        val r = rig()
        r.security.unlock(60_000)
        r.security.lock()
        assertFalse(r.security.unlocked.value)
        advanceTimeBy(120_000); runCurrent()
        r.security.recheck()
        assertFalse("a manual lock is not undone by the clock", r.security.unlocked.value)
    }

    @Test fun availableMethodsAreThePreferenceIntersectedWithWhatThePhoneOffers() = runTest {
        val r = rig(enrolled = { true })
        r.security.setPin("1234")
        assertEquals(setOf(UnlockMethod.PIN, UnlockMethod.FINGERPRINT), r.security.availableMethods(r.settings))

        r.settings = r.settings.copy(unlockMethod = UnlockMethod.PIN)
        assertEquals(setOf(UnlockMethod.PIN), r.security.availableMethods(r.settings))
        r.settings = r.settings.copy(unlockMethod = UnlockMethod.FINGERPRINT)
        assertEquals(setOf(UnlockMethod.FINGERPRINT), r.security.availableMethods(r.settings))
    }

    @Test fun anUnavailablePreferenceOffersNothing() = runTest {
        val r = rig() // no PIN, no fingerprint
        r.settings = r.settings.copy(unlockMethod = UnlockMethod.PIN)
        assertEquals(emptySet<UnlockMethod>(), r.security.availableMethods(r.settings))
        r.settings = r.settings.copy(unlockMethod = UnlockMethod.FINGERPRINT)
        assertEquals(emptySet<UnlockMethod>(), r.security.availableMethods(r.settings))
    }

    @Test fun thePinPresenceFollowsItsSettingAndClearing() = runTest {
        val r = rig()
        assertFalse(r.security.pinPresent.value)
        r.security.setPin("1234")
        assertTrue("setting the PIN reports its presence", r.security.pinPresent.value)
        r.security.changePin("1234", "5678")
        assertTrue(r.security.pinPresent.value)
        r.security.clearPin()
        assertFalse("clearing reports the absence", r.security.pinPresent.value)
    }

    @Test fun aSecuredCategoryIsOnlyLockedWhileAWayToUnlockExists() = runTest {
        val r = rig()
        r.settings = r.settings.copy(securedCategories = setOf("finance"))
        assertFalse("secured with no method is visible, not locked away", r.security.isLocked(r.settings, "finance"))
        r.security.setPin("1234")
        assertTrue(r.security.isLocked(r.settings, "finance"))
        r.security.unlock(60_000)
        assertFalse(r.security.isLocked(r.settings, "finance"))
        advanceTimeBy(61_000); runCurrent()
        assertTrue("locked again once the window is gone", r.security.isLocked(r.settings, "finance"))
        r.security.clearPin()
        assertFalse("removing the last method unlocks again", r.security.isLocked(r.settings, "finance"))
    }
}
