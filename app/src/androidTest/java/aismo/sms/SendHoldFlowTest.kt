package aismo.sms

import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.backup.BackupCrypto
import aismo.sms.backup.BackupFormat
import aismo.sms.backup.MetaRecord
import aismo.sms.backup.SettingsRecord

/** The undo window, against the real SMS provider: a held text is not sent until its time is up, and undo means it never is. */
@RunWith(AndroidJUnit4::class)
class SendHoldFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val to = "+61411000555"
    private val key = "p:411000555"
    private val pass = "send hold pass".toCharArray()

    private fun smsRows() = c.provider.readAfter(0, 100)

    private fun waitForRows(count: Int, millis: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) { if (smsRows().size >= count) return true; Thread.sleep(100) }
        return false
    }

    private fun hold(text: String, delayMs: Long, recipients: List<String> = listOf(to)) =
        c.sendHold.hold(key, recipients, text, emptyList(), subId = -1, restoresDraft = true, delayMs = delayMs)

    @Before fun setUp() {
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.sendHold.flushAll()
            c.sendHold.takeFailures(key)
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty()); c.mmsProvider.delete(c.mmsProvider.allIds().orEmpty())
            c.repository.syncRecent()
        }
    }

    @After fun tearDown() {
        c.sendHold.undo(key)
        awaitRadioResults(c.provider)
        runBlocking { c.settings.update { it.copy(sendDelaySeconds = 0) } }
    }

    @Test fun aHeldTextIsNotSentUntilItsTimeIsUp() {
        hold("hold me", 2_500)
        assertTrue("nothing yet", smsRows().isEmpty())
        Thread.sleep(1_200)
        assertTrue("still held", smsRows().isEmpty())
        assertNotNull(c.sendHold.held.value[key])
        assertTrue("it goes out by itself", waitForRows(1))
        assertEquals("hold me", smsRows().single().body)
        assertNull(c.sendHold.held.value[key])
    }

    @Test fun undoMeansItIsNeverSent() {
        hold("take me back", 2_000)
        Thread.sleep(500)
        assertEquals("take me back", c.sendHold.undo(key)?.text)
        Thread.sleep(3_500)
        assertTrue(smsRows().isEmpty())
    }

    @Test fun sendNowDoesNotWaitForTheTimer() {
        hold("right now", 60_000)
        c.sendHold.sendNow(key)
        assertTrue(waitForRows(1, 5_000))
        assertEquals("right now", smsRows().single().body)
    }

    @Test fun leavingTheAppSendsWhatIsHeld() {
        hold("on the way out", 60_000)
        c.sendHold.flushAll()
        assertTrue(waitForRows(1, 5_000))
        assertEquals("on the way out", smsRows().single().body)
    }

    @Test fun aTextThatCannotBeSentComesBackWithItsReason() {
        hold("to nobody", 500, recipients = emptyList())
        val deadline = System.currentTimeMillis() + 5_000
        while (c.sendHold.failed.value[key].isNullOrEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val failure = c.sendHold.takeFailures(key).single()
        assertEquals("to nobody", failure.message.text)
        assertEquals("No recipient", failure.reason)
        assertTrue(smsRows().isEmpty())
    }

    // ---------------------------------------------------------------- the setting

    @Test fun theDelayIsStoredAndReadBackAndOffByDefault() = runBlocking {
        assertEquals(0, c.settings.current().sendDelaySeconds)
        c.settings.update { it.copy(sendDelaySeconds = 10) }
        assertEquals(10, c.settings.current().sendDelaySeconds)
    }

    @Test fun aBackupCarriesTheDelayAndAnOlderOneLeavesItAlone() = runBlocking {
        c.settings.update { it.copy(sendDelaySeconds = 15) }
        val (bytes, _) = c.backup.build(pass)
        c.settings.update { it.copy(sendDelaySeconds = 3) }
        c.backup.restore(bytes, pass)
        assertEquals(15, c.settings.current().sendDelaySeconds)

        val old = BackupCrypto.encrypt(
            BackupFormat.encode(sequenceOf(MetaRecord(1, System.currentTimeMillis(), "old", 0), SettingsRecord(0, false, true, false, 24, false, true, true, false, true, true, true))),
            pass,
        )
        c.backup.restore(old, pass)
        assertEquals("a backup from before the setting existed leaves it as it was", 15, c.settings.current().sendDelaySeconds)
    }
}
