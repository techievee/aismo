package aismo.sms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.backup.BackupCrypto
import aismo.sms.backup.BackupFormat
import aismo.sms.backup.CategoryRecord
import aismo.sms.backup.MetaRecord
import aismo.sms.data.db.CustomCategoryEntity
import aismo.sms.domain.CategoryIconKeys

/** A custom category's icon is stored with it and travels in backups. */
@RunWith(AndroidJUnit4::class)
class CategoryIconTest {
    private val c get() = ApplicationProvider.getApplicationContext<AismoApp>().container
    private val pass = "category icon pass".toCharArray()
    private val keys = listOf("custom:icontest1", "custom:icontest2")

    @After fun tearDown() = runBlocking { keys.forEach { c.db.config().deleteCategory(it) } }

    private suspend fun iconOf(key: String) = c.db.config().categories().first { it.key == key }.icon

    @Test fun aNewCategoryWithoutAChoiceGetsTheDefaultIcon() = runBlocking {
        c.db.config().upsertCategory(CustomCategoryEntity(keys[0], "Plain"))
        assertEquals(CategoryIconKeys.DEFAULT, iconOf(keys[0]))
    }

    @Test fun theChosenIconIsStoredAndCanBeChangedLater() = runBlocking {
        c.db.config().upsertCategory(CustomCategoryEntity(keys[0], "Pets", "pets"))
        assertEquals("pets", iconOf(keys[0]))
        c.db.config().upsertCategory(CustomCategoryEntity(keys[0], "Pets", "heart"))
        assertEquals("heart", iconOf(keys[0]))
    }

    @Test fun aBackupCarriesTheIconAndARestoreBringsItBack() = runBlocking {
        c.db.config().upsertCategory(CustomCategoryEntity(keys[0], "Family", "family"))
        val (bytes, _) = c.backup.build(pass)
        c.db.config().upsertCategory(CustomCategoryEntity(keys[0], "Family", "bolt"))
        c.backup.restore(bytes, pass)
        assertEquals("family", iconOf(keys[0]))
    }

    @Test fun aBackupFromBeforeIconsRestoresItsCategoriesWithTheDefault() = runBlocking {
        val old = BackupCrypto.encrypt(
            BackupFormat.encode(sequenceOf(MetaRecord(1, System.currentTimeMillis(), "old", 0), CategoryRecord(keys[1], "Old category"))),
            pass,
        )
        c.backup.restore(old, pass)
        assertEquals(CategoryIconKeys.DEFAULT, iconOf(keys[1]))
    }
}
