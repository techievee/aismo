package aismo.sms

import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.backup.WrongPassphraseException
import aismo.sms.data.sms.RawSms

/**
 * Runs against the real SMS provider, so the app must hold the default-SMS role on the device
 * (`cmd role add-role-holder --user 0 android.app.role.SMS aismo.sms`).
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val c get() = ApplicationProvider.getApplicationContext<AismoApp>().container
    private val pass = "test passphrase 123".toCharArray()

    @Before fun requireDefaultSmsRole() {
        val holds = ApplicationProvider.getApplicationContext<AismoApp>()
            .getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
        assumeTrue("app must be the default SMS app for this test", holds)
    }

    private fun providerRows(): List<RawSms> = c.provider.readAfter(0, 10_000)

    private fun wipe() = runBlocking {
        c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
        // Anything the index didn't know about is still removed from the provider.
        c.provider.delete(c.provider.allIds().orEmpty())
        c.repository.syncRecent()
    }

    @Test fun backupDeleteRestoreRestoresEverythingAndNeverDuplicates() = runBlocking {
        wipe()
        val now = System.currentTimeMillis()
        c.provider.insertIncoming("+61411000001", "Dinner at 7? ✓ \"quoted\"", now - 5_000, now - 5_000, -1)
        c.provider.insertIncoming("VM-HDFCBK", "Rs 250.00 debited from a/c XX9999. Info: TESTSHOP. Avl bal Rs 1,000.00", now - 4_000, now - 4_000, -1)
        c.provider.insertOutgoing("+61411000001", "Yes, see you there", -1, Telephony.Sms.MESSAGE_TYPE_SENT)
        c.repository.syncNew()
        val before = providerRows().map { Triple(it.address, it.body, it.type) }.sortedBy { it.second }
        assertEquals(3, before.size)
        c.repository.setConversationCategory("s:HDFCBK", "custom:abc12345")

        val (bytes, count) = c.backup.build(pass)
        assertEquals(3, count)

        wipe()
        assertEquals("provider should be empty after wipe", 0, providerRows().size)

        val first = c.backup.restore(bytes, pass)
        assertEquals(3, first.messagesAdded)
        assertEquals(0, first.messagesSkipped)
        val restored = providerRows().map { Triple(it.address, it.body, it.type) }.sortedBy { it.second }
        assertEquals("restored messages must match the originals exactly", before, restored)
        assertEquals("the category the user chose survives a restore", "custom:abc12345", c.db.conversations().get("s:HDFCBK")?.category)

        val second = c.backup.restore(bytes, pass)
        assertEquals("restoring twice must not duplicate anything", 0, second.messagesAdded)
        assertEquals(3, second.messagesSkipped)
        assertEquals(3, providerRows().size)
    }

    @Test fun wrongPassphraseChangesNothing() = runBlocking {
        wipe()
        c.provider.insertIncoming("+61411000002", "keep me", System.currentTimeMillis(), 0, -1)
        c.repository.syncNew()
        val (bytes, _) = c.backup.build(pass)
        try {
            c.backup.restore(bytes, "not the passphrase".toCharArray())
            fail("expected WrongPassphraseException")
        } catch (e: WrongPassphraseException) {
            // expected
        }
        assertEquals(1, providerRows().size)
        assertTrue(providerRows().single().body == "keep me")
    }
}
