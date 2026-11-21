package aismo.sms

import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.provider.Telephony
import android.telephony.SmsManager
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
import aismo.sms.data.db.MessageIds
import aismo.sms.data.db.MmsDownloadEntity
import aismo.sms.data.mms.AttachmentPreparer
import aismo.sms.data.mms.MmsSendResult
import aismo.sms.data.mms.OutgoingAttachment
import aismo.sms.domain.mms.MmsCharset
import aismo.sms.domain.mms.MmsPart
import aismo.sms.telephony.MmsDeliverReceiver
import aismo.sms.telephony.NotificationCenter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/** The MMS protocol engine against the real provider, driven by the same callbacks the platform delivers. */
@RunWith(AndroidJUnit4::class)
class MmsEngineTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val me = "+61411999000"

    @Before fun setUp() {
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty())
            c.mmsProvider.delete(c.mmsProvider.allIds().orEmpty())
            // No automatic platform download in tests: every transfer result is delivered by hand.
            c.settings.update { it.copy(ownNumber = me, autoDownloadMms = false) }
            c.repository.syncRecent()
        }
    }

    @After fun restoreSettings() = runBlocking { c.settings.update { it.copy(autoDownloadMms = true) } }

    // ---- PDU builders, hand-assembled and independent of the encoder under test
    private fun z(s: String) = s.toByteArray() + 0
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun cat(vararg p: ByteArray) = ByteArrayOutputStream().also { o -> p.forEach(o::write) }.toByteArray()
    private fun uv(n: Int): ByteArray { val g = ArrayList<Int>(); var v = n; do { g += v and 0x7F; v = v ushr 7 } while (v != 0); return ByteArray(g.size) { i -> (g[g.size - 1 - i] or if (i != g.size - 1) 0x80 else 0).toByte() } }

    private fun notificationInd(tid: String, from: String, subject: String?, size: Int = 123_456, expirySeconds: Int = 259_200, location: String = "http://mmsc.invalid/m/$tid") = cat(
        b(0x8C, 0x82), b(0x98), z(tid), b(0x8D, 0x92),
        b(0x89, 1 + z("$from/TYPE=PLMN").size, 0x80), z("$from/TYPE=PLMN"),
        subject?.let { cat(b(0x96), z(it)) } ?: ByteArray(0),
        b(0x8A, 0x80), b(0x8E, 0x03, (size shr 16) and 0xFF, (size shr 8) and 0xFF, size and 0xFF),
        b(0x88, 0x05, 0x81, 0x03, (expirySeconds shr 16) and 0xFF, (expirySeconds shr 8) and 0xFF, expirySeconds and 0xFF),
        b(0x83), z(location),
    )

    private fun retrieveConf(tid: String, from: String, text: String, image: ByteArray?): ByteArray {
        val ct = b(0x80 or 0x33) + b(0x89) + z("application/smil") + b(0x8A) + z("<0000>")
        val textBytes = text.toByteArray()
        val h1 = b(0x03, 0x83, 0x81, 0xEA) + b(0x8E) + z("text_0.txt") + b(0xC0, 0x22) + z("<text_0>")
        val entries = ArrayList<ByteArray>()
        entries += cat(uv(h1.size), uv(textBytes.size), h1, textBytes)
        if (image != null) {
            val h2 = b(0x9E) + b(0x8E) + z("photo.jpg") + b(0xC0, 0x22) + z("<photo>")
            entries += cat(uv(h2.size), uv(image.size), h2, image)
        }
        return cat(
            b(0x8C, 0x84), b(0x98), z(tid), b(0x8D, 0x92),
            b(0x89, 1 + z("$from/TYPE=PLMN").size, 0x80), z("$from/TYPE=PLMN"),
            b(0x97), z("$me/TYPE=PLMN"), b(0x85, 0x04, 0x65, 0x5B, 0x3E, 0x80),
            b(0x84), b(ct.size), ct, uv(entries.size), *entries.toTypedArray(),
        )
    }

    private fun jpeg(w: Int = 64, h: Int = 48, noise: Boolean = false, quality: Int = 90): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (noise) { val r = Random(1); val px = IntArray(w * h) { r.nextInt() or (0xFF shl 24) }; bmp.setPixels(px, 0, w, 0, 0, w, h) } else bmp.eraseColor(0xFF3366CC.toInt())
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    private fun notificationShown(key: String) = (app.getSystemService(NotificationManager::class.java)).activeNotifications.any { it.id == NotificationCenter.notificationId(key) }

    // ---- receiving

    @Test fun pushCreatesOnePlaceholderAndADuplicatePushIsIgnored() = runBlocking {
        val pdu = notificationInd("tid-1", "+61411000555", "Pics")
        c.mmsEngine.onPush(pdu, -1)
        c.mmsEngine.onPush(pdu, -1) // the network re-sent it
        assertEquals(1, c.db.messages().mmsIds().size)
        val id = c.db.messages().mmsIds().single()
        val d = c.db.mmsDownloads().get(id)!!
        assertEquals(MmsDownloadEntity.WAITING, d.state)
        assertEquals("http://mmsc.invalid/m/tid-1", d.contentLocation)
        assertEquals(123_456L, d.sizeBytes)
        assertEquals("the placeholder shows the subject until the message is downloaded", "Pics", c.db.conversations().get("p:411000555")?.latestBody)
    }

    @Test fun theRealReceiverTurnsAWapPushIntoAPlaceholder() = runBlocking {
        val intent = Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).putExtra("data", notificationInd("tid-rx", "+61411000666", null)).putExtra("subscription", -1)
        MmsDeliverReceiver().onReceive(app, intent)
        repeat(50) { if (c.db.messages().mmsIds().isNotEmpty()) return@repeat else Thread.sleep(100) }
        assertEquals(1, c.db.messages().mmsIds().size)
        assertNotNull(c.db.mmsDownloads().byTransaction("tid-rx"))
    }

    @Test fun aSuccessfulDownloadReplacesThePlaceholderWithTheRealMessage() = runBlocking {
        c.mmsEngine.onPush(notificationInd("tid-2", "+61411000555", "Pics"), -1)
        val placeholder = c.db.messages().mmsIds().single()
        val photo = jpeg()
        val file = c.mmsTransport.newFile("dl").also { it.writeBytes(retrieveConf("tid-2", "+61411000555", "Look at this", photo)) }

        c.mmsEngine.onDownloaded(placeholder, file.absolutePath, Activity.RESULT_OK)

        assertNull("placeholder removed from the index", c.db.messages().get(placeholder))
        assertNull("placeholder removed from the provider", c.mmsProvider.readById(MessageIds.raw(placeholder)))
        assertNull("download row removed", c.db.mmsDownloads().get(placeholder))
        assertTrue("PDU file cleaned up", !file.exists())
        val msg = c.db.messages().get(c.db.messages().mmsIds().single())!!
        assertEquals("Look at this", msg.body)
        assertEquals(1, msg.attachmentCount)
        assertEquals("image", msg.mediaKind)
        val att = c.db.attachments().forMessage(msg.id).single()
        assertEquals(photo.toList(), c.mmsProvider.openPart(att.partId)!!.use { it.readBytes() }.toList())
        val conv = c.db.conversations().get("p:411000555")!!
        assertEquals(1, conv.messageCount)
        assertEquals(1, conv.unreadCount)
        assertTrue("user is notified of the new message", notificationShown("p:411000555"))
    }

    @Test fun aFailedDownloadKeepsThePlaceholderAndRecordsWhy() = runBlocking {
        c.mmsEngine.onPush(notificationInd("tid-3", "+61411000555", "Pics"), -1)
        val id = c.db.messages().mmsIds().single()
        val file = c.mmsTransport.newFile("dl").also { it.createNewFile() }
        c.mmsEngine.onDownloaded(id, file.absolutePath, SmsManager.MMS_ERROR_NO_DATA_NETWORK)
        val d = c.db.mmsDownloads().get(id)!!
        assertEquals(MmsDownloadEntity.FAILED, d.state)
        assertEquals(SmsManager.MMS_ERROR_NO_DATA_NETWORK, d.lastError)
        assertNotNull("the user can still retry it", c.db.messages().get(id))
        assertTrue(!file.exists())
    }

    @Test fun aCorruptDownloadIsTreatedAsAFailureNotACrash() = runBlocking {
        c.mmsEngine.onPush(notificationInd("tid-4", "+61411000555", null), -1)
        val id = c.db.messages().mmsIds().single()
        val file = c.mmsTransport.newFile("dl").also { it.writeBytes(ByteArray(64) { i -> (i * 13).toByte() }) }
        c.mmsEngine.onDownloaded(id, file.absolutePath, Activity.RESULT_OK)
        assertEquals(MmsDownloadEntity.FAILED, c.db.mmsDownloads().get(id)!!.state)
        assertNotNull(c.db.messages().get(id))
    }

    @Test fun anExpiredNotificationIsNotDownloaded() = runBlocking {
        c.mmsEngine.onPush(notificationInd("tid-5", "+61411000555", null, expirySeconds = 1), -1)
        val id = c.db.messages().mmsIds().single()
        Thread.sleep(1_500)
        assertTrue("download refused after expiry", !c.mmsEngine.startDownload(id))
        assertEquals(MmsDownloadEntity.FAILED, c.db.mmsDownloads().get(id)!!.state)
    }

    @Test fun aWatchdogFailsATransferThatNeverReportedBack() = runBlocking {
        // A download stuck in "downloading" (its callback was lost) becomes a retryable failure.
        c.mmsEngine.onPush(notificationInd("tid-w", "+61411000555", "Stuck"), -1)
        val id = c.db.messages().mmsIds().single()
        c.db.mmsDownloads().setState(id, MmsDownloadEntity.DOWNLOADING, 1, 0)
        c.mmsEngine.expireStale("download", id)
        assertEquals(MmsDownloadEntity.FAILED, c.db.mmsDownloads().get(id)!!.state)

        // ...but a download that is not stuck is left alone.
        c.db.mmsDownloads().setState(id, MmsDownloadEntity.WAITING, 0, 0)
        c.mmsEngine.expireStale("download", id)
        assertEquals(MmsDownloadEntity.WAITING, c.db.mmsDownloads().get(id)!!.state)

        // A message stuck in the outbox is failed; one that already completed is not.
        val parts = listOf(MmsPart("text/plain", "t.txt", "t", MmsCharset.UTF_8, "hi".toByteArray()))
        val stuck = MessageIds.mms(c.mmsProvider.insertOutgoing(listOf("+61411000001"), null, parts, -1, "w1"))
        val done = MessageIds.mms(c.mmsProvider.insertOutgoing(listOf("+61411000002"), null, parts, -1, "w2"))
        c.mmsProvider.updateBox(MessageIds.raw(done), Telephony.Mms.MESSAGE_BOX_SENT)
        c.mmsEngine.expireStale("send", stuck); c.mmsEngine.expireStale("send", done)
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, c.mmsProvider.readById(MessageIds.raw(stuck))!!.box)
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, c.mmsProvider.readById(MessageIds.raw(done))!!.box)
    }

    // ---- sending

    @Test fun sendComposesAValidMmsAndStoresItInTheOutbox() = runBlocking {
        val img = File(app.cacheDir, "pick.jpg").also { it.writeBytes(jpeg(400, 300)) }
        val result = c.mmsEngine.send(listOf("+61411000001", "+61411000002"), "hello team", listOf(OutgoingAttachment(img, "image/jpeg", "pick.jpg")), -1)
        assertTrue("queued, got $result", result is MmsSendResult.Queued)
        val id = (result as MmsSendResult.Queued).messageId
        val row = c.mmsProvider.readById(MessageIds.raw(id))!!
        assertTrue("outbox or already failed by the platform", row.box == Telephony.Mms.MESSAGE_BOX_OUTBOX || row.box == Telephony.Mms.MESSAGE_BOX_FAILED)
        assertEquals(listOf("+61411000001", "+61411000002"), row.to)
        val parts = c.mmsProvider.parts(row.rawId)
        assertEquals(listOf("application/smil", "text/plain", "image/jpeg"), parts.map { it.mime })
        assertEquals("hello team", parts[1].text)
        assertTrue("smil references the image", parts[0].text!!.contains("<img src=\"pick.jpg\""))
        val conv = c.db.conversations().get("g:p:411000001|p:411000002")
        assertNotNull("group conversation, have ${c.db.conversations().allKeys()}", conv)
        assertEquals("hello team", conv!!.latestBody)
    }

    @Test fun oversizeAttachmentsAreRejectedBeforeAnythingIsStored() = runBlocking {
        val big = File(app.cacheDir, "big.bin").also { it.writeBytes(ByteArray(6 * 1024 * 1024)) }
        val result = c.mmsEngine.send(listOf("+61411000001"), "x", listOf(OutgoingAttachment(big, "application/octet-stream", "big.bin")), -1)
        assertTrue("got $result", result is MmsSendResult.Failed)
        assertEquals(0, c.mmsProvider.allIds().orEmpty().size)
    }

    @Test fun aLargePhotoIsShrunkToFitTheCarrierLimit() = runBlocking {
        val noisy = File(app.cacheDir, "noisy.jpg").also { it.writeBytes(jpeg(2400, 1800, noise = true, quality = 100)) }
        assertTrue("fixture should be large, was ${noisy.length()}", noisy.length() > 2_000_000)
        val limit = c.mmsTransport.maxMessageBytes(-1)
        val parts = AttachmentPreparer.prepare(listOf(OutgoingAttachment(noisy, "image/jpeg", "noisy.jpg")), "hi", limit)
        val data = parts.single().data
        assertTrue("shrunk to ${data.size} (limit $limit)", data.size + 4096 <= limit)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        assertTrue("still a real image, max side ${maxOf(opts.outWidth, opts.outHeight)}", opts.outWidth > 100 && maxOf(opts.outWidth, opts.outHeight) <= 1280)
    }

    @Test fun exifRotationIsAppliedWhenAnImageIsReEncoded() {
        val f = File(app.cacheDir, "rot.jpg").also { it.writeBytes(jpeg(1600, 800, noise = true, quality = 95)) }
        ExifInterface(f.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val (_, bytes) = AttachmentPreparer.compressImage(OutgoingAttachment(f, "image/jpeg", "rot.jpg"), targetBytes = 150_000, limit = 300_000)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        assertTrue("landscape photo tagged 'rotate 90' comes out portrait (${o.outWidth}x${o.outHeight})", o.outHeight > o.outWidth)
    }

    @Test fun sendResultsMoveTheMessageToSentOrFailed() = runBlocking {
        val sentConf = cat(b(0x8C, 0x81), b(0x98), z("t"), b(0x8D, 0x92), b(0x92, 0x80), b(0x8B), z("MMSC-MSG-1"))
        val rejected = cat(b(0x8C, 0x81), b(0x98), z("t"), b(0x8D, 0x92), b(0x92, 0x82))
        val parts = listOf(MmsPart("text/plain", "t.txt", "t", MmsCharset.UTF_8, "hi".toByteArray()))

        fun outgoing(tid: String): Long = MessageIds.mms(c.mmsProvider.insertOutgoing(listOf("+61411000001"), null, parts, -1, tid)).also { runBlocking { c.repository.refresh(listOf(it)) } }
        fun box(id: Long) = c.mmsProvider.readById(MessageIds.raw(id))!!.box
        fun typeInIndex(id: Long) = runBlocking { c.db.messages().get(id)!!.type }

        val ok = outgoing("a"); c.mmsEngine.onSent(ok, c.mmsTransport.newFile("snd").absolutePath, Activity.RESULT_OK, sentConf)
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, box(ok)); assertEquals(2, typeInIndex(ok))

        val no = outgoing("b"); c.mmsEngine.onSent(no, c.mmsTransport.newFile("snd").absolutePath, Activity.RESULT_OK, rejected)
        assertEquals("an MMSC rejection is a failure even though the transport succeeded", Telephony.Mms.MESSAGE_BOX_FAILED, box(no))

        val err = outgoing("c"); c.mmsEngine.onSent(err, c.mmsTransport.newFile("snd").absolutePath, SmsManager.MMS_ERROR_NO_DATA_NETWORK, null)
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, box(err)); assertEquals(5, typeInIndex(err))

        val noBody = outgoing("d"); c.mmsEngine.onSent(noBody, c.mmsTransport.newFile("snd").absolutePath, Activity.RESULT_OK, null)
        assertEquals("OK without a response body counts as sent", Telephony.Mms.MESSAGE_BOX_SENT, box(noBody))
    }
}
