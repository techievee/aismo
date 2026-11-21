package aismo.sms

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.db.AismoDatabase

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AismoDatabase::class.java)

    @Test fun version1ToVersion2KeepsMessagesAndAddsMmsStorage() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO messages (id, threadId, address, conversationKey, body, date, type, isRead, subId, status, isStarred, category, parserVersion) " +
                    "VALUES (7, 1, '+15551234567', 'p:551234567', 'hello from v1', 1700000000000, 1, 0, -1, -1, 1, 'personal', 1)",
            )
            close()
        }

        // Validates the migrated schema against the exported 2.json, so any column/index/table mismatch fails here.
        val db = helper.runMigrationsAndValidate(dbName, 2, true, AismoDatabase.MIGRATION_1_2)

        db.query("SELECT id, body, isStarred, subject, mediaKind, attachmentCount FROM messages WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("hello from v1", c.getString(1))
            assertEquals("existing data survives", 1, c.getInt(2))
            assertTrue("new nullable columns start empty", c.isNull(3) && c.isNull(4))
            assertEquals("attachment count defaults to zero", 0, c.getInt(5))
        }
        db.execSQL("INSERT INTO attachments (messageId, partId, mime, name, sizeBytes, kind) VALUES (7, 1, 'image/jpeg', 'a.jpg', 10, 'image')")
        db.execSQL(
            "INSERT INTO mms_downloads (messageId, contentLocation, transactionId, subId, sizeBytes, expiresAt, state, attempts, lastError, createdAt) " +
                "VALUES (7, 'http://x', 't', -1, 5, NULL, 0, 0, 0, 1)",
        )
        db.query("SELECT COUNT(*) FROM attachments").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        // Full-text search keeps working through the migration.
        db.query("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'hello*'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
    }

    @Test fun version2ToVersion3AddsEmbeddingsAndKeepsMessages() {
        helper.createDatabase("migration-test-3", 2).apply {
            execSQL(
                "INSERT INTO messages (id, threadId, address, conversationKey, body, date, type, isRead, subId, status, isStarred, category, parserVersion, attachmentCount) " +
                    "VALUES (9, 1, '+15551234567', 'p:551234567', 'hello from v2', 1700000000000, 1, 0, -1, -1, 0, 'personal', 1, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-3", 3, true, AismoDatabase.MIGRATION_2_3)
        db.query("SELECT body FROM messages WHERE id = 9").use { c -> assertTrue(c.moveToFirst()); assertEquals("hello from v2", c.getString(0)) }
        db.execSQL("INSERT INTO message_embeddings (messageId, model, scale, vector) VALUES (9, 1, 0.01, x'0102')")
        db.query("SELECT COUNT(*) FROM message_embeddings").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
    }

    @Test fun version3ToVersion4GivesExistingCategoriesTheTagAndAllowsAChosenIcon() {
        helper.createDatabase("migration-test-4", 3).apply {
            execSQL("INSERT INTO custom_categories (`key`, name) VALUES ('custom:old00001', 'Family')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-4", 4, true, AismoDatabase.MIGRATION_3_4)
        db.query("SELECT name, icon FROM custom_categories WHERE `key` = 'custom:old00001'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Family", c.getString(0))
            assertEquals("the category keeps working and wears the plain tag", "label", c.getString(1))
        }
        db.execSQL("INSERT INTO custom_categories (`key`, name, icon) VALUES ('custom:new00001', 'Pets', 'pets')")
        db.query("SELECT icon FROM custom_categories WHERE `key` = 'custom:new00001'").use { c -> assertTrue(c.moveToFirst()); assertEquals("pets", c.getString(0)) }
    }

    @Test fun version4ToVersion5AddsTranslationsAndKeepsMessages() {
        helper.createDatabase("migration-test-5", 4).apply {
            execSQL(
                "INSERT INTO messages (id, threadId, address, conversationKey, body, date, type, isRead, subId, status, isStarred, category, parserVersion, attachmentCount) " +
                    "VALUES (11, 1, '+15551234567', 'p:551234567', 'hello from v4', 1700000000000, 1, 0, -1, -1, 0, 'personal', 1, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-5", 5, true, AismoDatabase.MIGRATION_4_5)
        db.query("SELECT body FROM messages WHERE id = 11").use { c -> assertTrue(c.moveToFirst()); assertEquals("hello from v4", c.getString(0)) }
        db.execSQL("INSERT INTO message_translations (messageId, target, source, text, at) VALUES (11, 'en', 'hi', 'a translated hello', 1)")
        db.query("SELECT text, source FROM message_translations WHERE messageId = 11").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("a translated hello", c.getString(0))
            assertEquals("hi", c.getString(1))
        }
    }
}
