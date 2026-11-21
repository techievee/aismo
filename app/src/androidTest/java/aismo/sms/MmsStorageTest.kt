package aismo.sms

import android.app.role.RoleManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.db.MessageIds
import aismo.sms.data.db.MmsDownloadEntity
import aismo.sms.domain.mms.MmsAddress
import aismo.sms.domain.mms.MmsCharset
import aismo.sms.domain.mms.MmsPart
import aismo.sms.domain.mms.MmsPdu
import aismo.sms.domain.mms.MmsType
import java.io.ByteArrayOutputStream

/** MMS storage and indexing against the real `content://mms` provider; needs the default-SMS role. */
@RunWith(AndroidJUnit4::class)
class MmsStorageTest {
    private val c get() = ApplicationProvider.getApplicationContext<AismoApp>().container
    private val now get() = System.currentTimeMillis()
    private val me = "+61411999000"

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<AismoApp>()
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty())
            c.mmsProvider.delete(c.mmsProvider.allIds().orEmpty())
            c.settings.update { it.copy(ownNumber = me) }
            c.repository.syncRecent()
        }
    }

    private fun jpeg(color: Int = Color.RED): ByteArray {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun retrieved(from: String, to: List<String>, text: String?, image: ByteArray? = null, subject: String? = null) = MmsPdu(
        type = MmsType.RETRIEVE_CONF, transactionId = "t-${System.nanoTime()}", from = MmsAddress.of(from),
        to = to.map { MmsAddress.of(it) }, subject = subject, dateSeconds = now / 1000, contentType = "application/vnd.wap.multipart.related",
        parts = listOfNotNull(
            text?.let { MmsPart("text/plain", "text_0.txt", "text_0", MmsCharset.UTF_8, it.toByteArray()) },
            image?.let { MmsPart("image/jpeg", "photo.jpg", "photo", null, it) },
        ),
    )

    private fun store(pdu: MmsPdu, others: Set<String>): Long {
        val id = c.mmsProvider.insertRetrieved(pdu, -1, now, others)
        assertTrue("provider must accept the MMS", id > 0)
        return MessageIds.mms(id)
    }

    @Test fun retrievedMmsIsIndexedWithItsAttachmentAndSharesTheSmsConversation() = runBlocking {
        c.provider.insertIncoming("+61411000555", "earlier sms", now - 60_000, now - 60_000, -1)
        c.repository.syncNew()
        val bytes = jpeg()
        val id = store(retrieved("+61411000555", listOf(me), "See the beach", bytes, "Holiday"), setOf("+61411000555"))
        c.repository.refresh(listOf(id))

        val m = c.db.messages().get(id)!!
        assertTrue(m.isMms)
        assertEquals("Holiday\nSee the beach", m.body)
        assertEquals("Holiday", m.subject)
        assertEquals(1, m.attachmentCount)
        assertEquals("image", m.mediaKind)
        assertEquals("p:411000555", m.conversationKey)

        val conv = c.db.conversations().get("p:411000555")!!
        assertEquals("the MMS joins the existing SMS conversation", 2, conv.messageCount)
        assertEquals("Holiday\nSee the beach", conv.latestBody)

        val att = c.db.attachments().forMessage(id).single()
        assertEquals("image/jpeg", att.mime)
        assertEquals("photo.jpg", att.name)
        assertEquals(bytes.size.toLong(), att.sizeBytes)
        assertArrayEquals("the stored part returns the original bytes", bytes, c.mmsProvider.openPart(att.partId)!!.use { it.readBytes() })
    }

    @Test fun aVCardPartIsAnAttachmentNotMessageText() = runBlocking {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Sam Rivera\nEND:VCARD\n".toByteArray()
        val pdu = retrieved("+61411000555", listOf(me), "Here is Sam", null).let { it.copy(parts = it.parts + MmsPart("text/x-vCard", "Sam.vcf", "vcf", null, vcf)) }
        val id = store(pdu, setOf("+61411000555"))
        c.repository.refresh(listOf(id))
        val m = c.db.messages().get(id)!!
        assertEquals("only the plain text is the body", "Here is Sam", m.body)
        assertEquals("contact", m.mediaKind)
        val att = c.db.attachments().forMessage(id).single()
        assertEquals("Sam.vcf", att.name)
        assertEquals("contact", att.kind)
        assertEquals(vcf.toList(), c.mmsProvider.openPart(att.partId)!!.use { it.readBytes() }.toList())
    }

    @Test fun attachmentOnlyMmsShowsAPhotoLabelInTheConversationList() = runBlocking {
        val id = store(retrieved("+61411000555", listOf(me), null, jpeg()), setOf("+61411000555"))
        c.repository.refresh(listOf(id))
        assertEquals("Photo", c.db.conversations().get("p:411000555")?.latestBody)
    }

    @Test fun groupMmsBecomesAnOrderIndependentGroupConversationExcludingMe() = runBlocking {
        val a = "+61411000001"; val b = "+61411000002"
        val id = store(retrieved(a, listOf(me, b), "hello team"), setOf(a, b))
        c.repository.refresh(listOf(id))
        val key = "g:p:411000001|p:411000002"
        val conv = c.db.conversations().get(key)
        assertNotNull("group conversation $key, got ${c.db.conversations().allKeys()}", conv)
        assertEquals("$a,$b", conv!!.address)
        assertNull(conv.title)

        // A reply sent by me to the same people, listed in a different order, lands in the same conversation.
        val out = c.mmsProvider.insertOutgoing(listOf(b, a), null, listOf(MmsPart("text/plain", "t.txt", "t", MmsCharset.UTF_8, "ok".toByteArray())), -1, "tx-1")
        assertTrue(out > 0)
        c.repository.refresh(listOf(MessageIds.mms(out)))
        assertEquals(2, c.db.conversations().get(key)?.messageCount)
        assertEquals("one conversation, not two", 1, c.db.conversations().allKeys().size)
    }

    @Test fun downloadNotificationBecomesAPlaceholderWithADownloadRow() = runBlocking {
        val pdu = MmsPdu(
            type = MmsType.NOTIFICATION_IND, transactionId = "tx-note", from = MmsAddress.of("+61411000777"), subject = "Pics",
            messageSize = 250_000, expiryRelativeSeconds = 3 * 86_400, contentLocation = "http://mmsc.invalid/msg/1",
        )
        val raw = c.mmsProvider.insertNotification(pdu, -1, now)
        assertTrue(raw > 0)
        val id = MessageIds.mms(raw)
        val ingested = c.repository.refresh(listOf(id))
        assertTrue("reported as a placeholder", ingested.single().isMmsPlaceholder)
        val d = c.db.mmsDownloads().get(id)!!
        assertEquals("tx-note", d.transactionId)
        assertEquals("http://mmsc.invalid/msg/1", d.contentLocation)
        assertEquals(250_000L, d.sizeBytes)
        assertEquals(MmsDownloadEntity.WAITING, d.state)
        assertEquals("Pics", c.db.messages().get(id)?.body)
        assertEquals("a placeholder is not parsed for facts", 0, c.db.insights().recentOtps(5).first().size)

        c.repository.deleteMessages(listOf(id))
        assertNull("download row is removed with the placeholder", c.db.mmsDownloads().get(id))
    }

    @Test fun deletingAnMmsRemovesItFromTheProviderAttachmentsAndIndex() = runBlocking {
        val id = store(retrieved("+61411000555", listOf(me), "bye", jpeg()), setOf("+61411000555"))
        c.repository.refresh(listOf(id))
        assertEquals(1, c.db.attachments().forMessage(id).size)
        c.repository.deleteMessages(listOf(id))
        assertNull(c.db.messages().get(id))
        assertEquals(0, c.db.attachments().forMessage(id).size)
        assertNull(c.mmsProvider.readById(MessageIds.raw(id)))
        assertNull(c.db.conversations().get("p:411000555"))
    }

    @Test fun anMmsDeletedByAnotherAppDisappearsFromTheIndex() = runBlocking {
        val id = store(retrieved("+61411000555", listOf(me), "temp"), setOf("+61411000555"))
        c.repository.refresh(listOf(id))
        c.mmsProvider.delete(listOf(MessageIds.raw(id)))
        c.repository.syncRecent()
        assertNull(c.db.messages().get(id))
    }

    @Test fun syncNewImportsMmsThatWereWrittenStraightToTheProvider() = runBlocking {
        c.provider.insertIncoming("+61411000555", "sms one", now, now, -1)
        c.repository.syncNew()
        store(retrieved("+61411000555", listOf(me), "mms one", jpeg(Color.BLUE)), setOf("+61411000555")) // not refreshed
        store(retrieved("+61411000556", listOf(me), "mms two"), setOf("+61411000556"))
        val added = c.repository.syncNew()
        assertEquals(2, added)
        assertEquals("a second sync finds nothing new", 0, c.repository.syncNew())
        assertEquals(2, c.db.messages().mmsIds().size)
    }

    @Test fun markingAConversationReadUpdatesTheMmsProviderToo() = runBlocking {
        c.provider.insertIncoming("+61411000555", "sms", now - 1000, now - 1000, -1)
        val id = store(retrieved("+61411000555", listOf(me), "mms"), setOf("+61411000555"))
        c.repository.syncNew()
        assertEquals(2, c.db.conversations().get("p:411000555")?.unreadCount)
        c.repository.markConversationRead("p:411000555")
        assertEquals(0, c.db.conversations().get("p:411000555")?.unreadCount)
        assertTrue("MMS row is read in the provider", c.mmsProvider.readById(MessageIds.raw(id))!!.read)
        assertTrue(c.provider.readAfter(0, 10).all { it.read })
    }

    @Test fun mmsTextIsSearchable() = runBlocking {
        val id = store(retrieved("+61411000555", listOf(me), "barbecue on saturday", jpeg(), "Weekend"), setOf("+61411000555"))
        c.repository.refresh(listOf(id))
        assertEquals(1, c.repository.search("barbecue").size)
        assertEquals("the subject is searchable too", 1, c.repository.search("weekend").size)
        assertFalse(c.repository.search("barbecue").single().id < MessageIds.MMS_BASE)
    }
}
