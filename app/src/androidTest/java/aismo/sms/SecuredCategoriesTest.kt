package aismo.sms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.backup.BackupCrypto
import aismo.sms.backup.BackupFormat
import aismo.sms.backup.MetaRecord
import aismo.sms.backup.SettingsRecord
import aismo.sms.data.security.CategorySecurity
import aismo.sms.data.security.VaultPinStore
import aismo.sms.data.settings.UnlockMethod

/**
 * Secured categories: the settings are stored, the PIN is sealed in the vault (never plain text, never in a backup),
 * a restore brings the settings back, and a secured category is only locked away while there is a way to unlock it.
 */
@RunWith(AndroidJUnit4::class)
class SecuredCategoriesTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val pass = "secured categories pass".toCharArray()

    @After fun tearDown() = runBlocking {
        c.security.clearPin()
        c.security.lock()
        c.settings.update { it.copy(securedCategories = emptySet(), unlockWindowMinutes = 5, unlockMethod = UnlockMethod.BOTH) }
    }

    @Test fun theNewSettingsAreStoredAndReadBack() = runBlocking {
        c.settings.update { it.copy(securedCategories = setOf("finance", "custom:abc12345"), unlockWindowMinutes = 10, unlockMethod = UnlockMethod.PIN) }
        val s = c.settings.current()
        assertEquals(setOf("finance", "custom:abc12345"), s.securedCategories)
        assertEquals(10, s.unlockWindowMinutes)
        assertEquals(UnlockMethod.PIN, s.unlockMethod)
    }

    @Test fun aPinIsSealedInTheVaultAndNeverInPlainText() = runBlocking {
        c.security.setPin("4242")
        c.settings.update { it } // materialise the settings store so it can be checked
        val sealed = app.getSharedPreferences("vault", 0).getString("category_pin", null)
        assertTrue("the vault holds a sealed record", sealed != null)
        assertFalse("the raw PIN is not in the vault value", sealed!!.contains("4242"))
        assertFalse("the raw PIN is not in the settings store", File(app.filesDir, "datastore/settings.preferences_pb").readText().contains("4242"))
        // A fresh owner with the same vault can verify the PIN.
        val fresh = CategorySecurity(c.scope, { 0L }, VaultPinStore(c.vault)) { false }
        assertTrue(fresh.verifyPin("4242"))
        assertFalse(fresh.verifyPin("4243"))
    }

    @Test fun aBackupCarriesTheNewSettingsButNotThePin() = runBlocking {
        c.security.setPin("4242")
        c.settings.update { it.copy(securedCategories = setOf("finance"), unlockWindowMinutes = 30, unlockMethod = UnlockMethod.FINGERPRINT) }
        val (bytes, _) = c.backup.build(pass)
        c.settings.update { it.copy(securedCategories = emptySet(), unlockWindowMinutes = 5, unlockMethod = UnlockMethod.BOTH) }
        c.security.clearPin()
        c.backup.restore(bytes, pass)
        val s = c.settings.current()
        assertEquals(setOf("finance"), s.securedCategories)
        assertEquals(30, s.unlockWindowMinutes)
        assertEquals(UnlockMethod.FINGERPRINT, s.unlockMethod)
        assertFalse("the PIN itself never travels in a backup", c.security.verifyPin("4242"))
    }

    @Test fun anOlderBackupWithoutThemLeavesTheCurrentChoicesAlone() = runBlocking {
        c.settings.update { it.copy(securedCategories = setOf("finance"), unlockWindowMinutes = 10, unlockMethod = UnlockMethod.PIN) }
        // A version 1 backup written before these settings existed: its settings record has no such fields.
        val old = BackupCrypto.encrypt(
            BackupFormat.encode(
                sequenceOf(
                    MetaRecord(1, System.currentTimeMillis(), "old", 0),
                    SettingsRecord(0, false, true, false, 24, false, true, true, false, true, true, true),
                ),
            ),
            pass,
        )
        c.backup.restore(old, pass)
        val s = c.settings.current()
        assertEquals(setOf("finance"), s.securedCategories)
        assertEquals(10, s.unlockWindowMinutes)
        assertEquals(UnlockMethod.PIN, s.unlockMethod)
    }

    @Test fun aSecuredCategoryIsLockedWhileAPinExistsAndOpensAfterAnUnlock() = runBlocking {
        c.security.setPin("4242")
        c.settings.update { it.copy(securedCategories = setOf("finance")) }
        assertTrue(c.security.isLocked(c.settings.current(), "finance"))
        c.security.unlock(60_000)
        assertFalse(c.security.isLocked(c.settings.current(), "finance"))
        c.security.lock()
        assertTrue(c.security.isLocked(c.settings.current(), "finance"))
    }

    @Test fun aSecuredCategoryWithoutAnyMethodIsNotLockedAway() = runBlocking {
        c.security.clearPin()
        c.settings.update { it.copy(securedCategories = setOf("finance"), unlockMethod = UnlockMethod.BOTH) }
        assumeTrue("this test needs a phone with no fingerprint enrolled", UnlockMethod.FINGERPRINT !in c.security.availableMethods(c.settings.current()))
        assertFalse("no PIN and no fingerprint: visible, not locked away", c.security.isLocked(c.settings.current(), "finance"))
    }
}
