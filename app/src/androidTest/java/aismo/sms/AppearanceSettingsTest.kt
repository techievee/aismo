package aismo.sms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.backup.BackupCrypto
import aismo.sms.backup.BackupFormat
import aismo.sms.backup.MetaRecord
import aismo.sms.backup.SettingsRecord
import aismo.sms.data.settings.AppPalette
import aismo.sms.data.settings.ThemeMode

/** The colour theme, pure black and the hidden inbox categories survive a restart and travel in backups. */
@RunWith(AndroidJUnit4::class)
class AppearanceSettingsTest {
    private val c get() = ApplicationProvider.getApplicationContext<AismoApp>().container
    private val pass = "appearance settings pass".toCharArray()

    @After fun tearDown() = runBlocking {
        c.settings.update { it.copy(theme = ThemeMode.SYSTEM, palette = AppPalette.DYNAMIC, pureBlack = false, hiddenCategories = emptySet(), smartSearch = true) }
        Unit
    }

    @Test fun theyAreStoredAndReadBack() = runBlocking {
        c.settings.update { it.copy(theme = ThemeMode.DARK, palette = AppPalette.FOREST, pureBlack = true, hiddenCategories = setOf("promo", "custom:abc12345")) }
        val s = c.settings.current()
        assertEquals(ThemeMode.DARK, s.theme)
        assertEquals(AppPalette.FOREST, s.palette)
        assertTrue(s.pureBlack)
        assertEquals(setOf("promo", "custom:abc12345"), s.hiddenCategories)
    }

    @Test fun nothingIsHiddenAndTheWallpaperThemeIsUsedUntilTheUserChoosesOtherwise() = runBlocking {
        val s = c.settings.current()
        assertEquals(AppPalette.DYNAMIC, s.palette)
        assertTrue(s.hiddenCategories.isEmpty())
    }

    @Test fun showingACategoryAgainRemovesItFromTheHiddenSet() = runBlocking {
        c.settings.update { it.copy(hiddenCategories = setOf("promo", "spam")) }
        c.settings.update { it.copy(hiddenCategories = it.hiddenCategories - "promo") }
        assertEquals(setOf("spam"), c.settings.current().hiddenCategories)
    }

    @Test fun aBackupCarriesThemAndARestoreBringsThemBack() = runBlocking {
        c.settings.update { it.copy(palette = AppPalette.ROSE, pureBlack = true, hiddenCategories = setOf("travel")) }
        val (bytes, _) = c.backup.build(pass)
        c.settings.update { it.copy(palette = AppPalette.OCEAN, pureBlack = false, hiddenCategories = emptySet()) }
        c.backup.restore(bytes, pass)
        val s = c.settings.current()
        assertEquals(AppPalette.ROSE, s.palette)
        assertTrue(s.pureBlack)
        assertEquals(setOf("travel"), s.hiddenCategories)
    }

    @Test fun anOlderBackupWithoutThemLeavesYourChoicesAlone() = runBlocking {
        c.settings.update { it.copy(palette = AppPalette.VIOLET, pureBlack = true, hiddenCategories = setOf("health")) }
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
        assertEquals(AppPalette.VIOLET, s.palette)
        assertTrue(s.pureBlack)
        assertEquals(setOf("health"), s.hiddenCategories)
    }
}
