package aismo.sms

import android.app.role.RoleManager
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.db.MessageIds
import aismo.sms.data.mms.MmsSendResult
import aismo.sms.data.mms.OutgoingAttachment
import aismo.sms.domain.mms.MmsAddress
import aismo.sms.domain.mms.MmsPdu
import aismo.sms.domain.mms.MmsType
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Real MMS transfers through the platform MMS service against a fake MMSC on the host (the emulator's APN
 * must point at it). Runs only with `-e live true`, so it never affects ordinary test runs.
 */
@RunWith(AndroidJUnit4::class)
class MmsLiveNetworkTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val me = "+61411999000"

    @Before fun setUp() {
        assumeTrue("live network test", InstrumentationRegistry.getArguments().getString("live") == "true")
        assumeTrue(app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty()); c.mmsProvider.delete(c.mmsProvider.allIds().orEmpty())
            c.settings.update { it.copy(ownNumber = me, autoDownloadMms = false) }
            c.repository.syncRecent()
        }
    }

    private suspend fun <T : Any> waitFor(seconds: Int, what: String, probe: suspend () -> T?): T {
        repeat(seconds * 2) { probe()?.let { return it }; delay(500) }
        throw AssertionError("timed out after ${seconds}s waiting for $what")
    }

    private fun photo(): File {
        val bmp = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(30, 136, 229)) }
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        return File(app.cacheDir, "live.jpg").also { it.writeBytes(bytes) }
    }

    @Test fun anMmsSentThroughThePlatformReachesTheMmscAndIsMarkedSent() = runBlocking {
        val result = c.mmsEngine.send(listOf("+61411000555", "+61411000556"), "hello from the app", listOf(OutgoingAttachment(photo(), "image/jpeg", "live.jpg")), -1)
        assertTrue("queued, got $result", result is MmsSendResult.Queued)
        val id = (result as MmsSendResult.Queued).messageId
        val box = waitFor(60, "the platform to report the send (last box ${c.mmsProvider.readById(MessageIds.raw(id))?.box})") {
            c.mmsProvider.readById(MessageIds.raw(id))?.box?.takeIf { it == Telephony.Mms.MESSAGE_BOX_SENT || it == Telephony.Mms.MESSAGE_BOX_FAILED }
        }
        assertEquals("sent, not failed", Telephony.Mms.MESSAGE_BOX_SENT, box)
        assertEquals("the index follows the provider", 2, c.db.messages().get(id)!!.type)
    }

    @Test fun aLongTextIsConvertedAndSentAsOneMmsThroughThePlatform() = runBlocking {
        val text = "Long message for the carrier test, number 0123456789. ".repeat(13).trim() // ~700 chars, 5 SMS parts
        val r = c.outgoing.send(listOf("+61411000555"), text, emptyList(), -1)
        assertTrue("the real carrier conditions allow conversion, got $r", r is aismo.sms.data.SendOutcome.Sent && r.route == aismo.sms.data.SendRoute.MMS_LONG_TEXT)
        val id = (r as aismo.sms.data.SendOutcome.Sent).messageId
        val box = waitFor(60, "the platform to report the send (last box ${c.mmsProvider.readById(MessageIds.raw(id))?.box})") {
            c.mmsProvider.readById(MessageIds.raw(id))?.box?.takeIf { it == Telephony.Mms.MESSAGE_BOX_SENT || it == Telephony.Mms.MESSAGE_BOX_FAILED }
        }
        assertEquals("sent, not failed", Telephony.Mms.MESSAGE_BOX_SENT, box)
        assertEquals("no SMS was sent alongside it", 0, c.provider.readAfter(0, 10).size)
        assertTrue(c.mmsProvider.readById(MessageIds.raw(id))!!.transactionId!!.startsWith("tsol"))
    }

    @Test fun anMmsDownloadedThroughThePlatformIsDecodedAndStored() = runBlocking {
        val note = MmsPdu(
            type = MmsType.NOTIFICATION_IND, transactionId = "live-note-1", from = MmsAddress.of("+61411000999"), subject = "Greetings",
            messageSize = 130_000, expiryRelativeSeconds = 259_200, contentLocation = "http://10.0.2.2:8089/mms/retrieve/1",
        )
        val raw = c.mmsProvider.insertNotification(note, -1, System.currentTimeMillis())
        val placeholder = MessageIds.mms(raw)
        c.repository.refresh(listOf(placeholder))
        assertTrue(c.mmsEngine.startDownload(placeholder))

        val stored = waitFor(90, "the message to be downloaded (state ${c.db.mmsDownloads().get(placeholder)?.let { "${it.state}/err ${it.lastError}" }})") {
            c.db.messages().mmsIds().firstOrNull { it != placeholder }?.let { c.db.messages().get(it) }
        }
        assertTrue("subject and text are both kept: ${stored.body}", stored.body.contains("Hello from the fake MMSC"))
        assertEquals("p:411000999", stored.conversationKey)
        val att = c.db.attachments().forMessage(stored.id).single()
        assertEquals("image/png", att.mime)
        assertEquals("scene.png", att.name)
        assertTrue("the image bytes arrived (${att.sizeBytes})", att.sizeBytes > 1_000)
        assertNotNull(c.mmsProvider.openPart(att.partId))
        assertEquals("the placeholder was replaced", null, c.db.messages().get(placeholder))
        assertEquals(null, c.db.mmsDownloads().get(placeholder))
    }
}
