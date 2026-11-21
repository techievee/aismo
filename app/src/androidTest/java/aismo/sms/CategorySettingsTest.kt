package aismo.sms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.backup.BackupCrypto
import aismo.sms.backup.BackupFormat
import aismo.sms.backup.MetaRecord
import aismo.sms.backup.SettingsRecord

/** The category order, the muted categories and the spam reminder survive a restart and travel in backups. */
@RunWith(AndroidJUnit4::class)
class CategorySettingsTest {
    private val c get() = ApplicationProvider.getApplicationContext<AismoApp>().container
    private val pass = "category settings pass".toCharArray()

    @After fun tearDown() = runBlocking { c.settings.update { it.copy(categoryOrder = emptyList(), mutedCategories = emptySet(), spamReviewReminder = true, smartSearch = true) }; Unit }

    @Test fun theyAreStoredAndReadBack() = runBlocking {
        c.settings.update { it.copy(categoryOrder = listOf("spam", "personal", "custom:abc12345"), mutedCategories = setOf("promo", "spam"), spamReviewReminder = false) }
        val s = c.settings.current()
        assertEquals(listOf("spam", "personal", "custom:abc12345"), s.categoryOrder)
        assertEquals(setOf("promo", "spam"), s.mutedCategories)
        assertFalse(s.spamReviewReminder)
    }

    @Test fun anEmptyOrderMeansTheNaturalOne() = runBlocking {
        c.settings.update { it.copy(categoryOrder = listOf("spam")) }
        c.settings.update { it.copy(categoryOrder = emptyList()) }
        assertTrue(c.settings.current().categoryOrder.isEmpty())
    }

    @Test fun aBackupCarriesThemAndARestoreBringsThemBack() = runBlocking {
        c.settings.update { it.copy(categoryOrder = listOf("finance", "otp"), mutedCategories = setOf("promo"), spamReviewReminder = false) }
        val (bytes, _) = c.backup.build(pass)
        c.settings.update { it.copy(categoryOrder = listOf("spam"), mutedCategories = emptySet(), spamReviewReminder = true) }
        c.backup.restore(bytes, pass)
        val s = c.settings.current()
        assertEquals(listOf("finance", "otp"), s.categoryOrder)
        assertEquals(setOf("promo"), s.mutedCategories)
        assertFalse(s.spamReviewReminder)
    }

    @Test fun anOlderBackupWithoutThemLeavesYourChoicesAlone() = runBlocking {
        c.settings.update { it.copy(categoryOrder = listOf("health", "job"), mutedCategories = setOf("promo"), spamReviewReminder = false) }
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
        assertEquals(listOf("health", "job"), s.categoryOrder)
        assertEquals(setOf("promo"), s.mutedCategories)
        assertFalse("the reminder setting is also left as it was", s.spamReviewReminder)
    }
}
