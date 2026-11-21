package aismo.sms

import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.backup.BackupCrypto
import aismo.sms.backup.BackupDamagedException
import aismo.sms.backup.BackupFormat
import aismo.sms.backup.ConversationRecord
import aismo.sms.backup.MessageRecord
import aismo.sms.backup.MetaRecord
import aismo.sms.backup.SettingsRecord
import aismo.sms.data.db.MessageIds
import aismo.sms.domain.mms.MmsAddress
import aismo.sms.domain.mms.MmsCharset
import aismo.sms.domain.mms.MmsPart
import aismo.sms.domain.mms.MmsPdu
import aismo.sms.domain.mms.MmsType
import java.io.ByteArrayOutputStream
import java.util.Random

/** MMS in backups, against the real SMS and MMS providers; needs the default-SMS role. */
@RunWith(AndroidJUnit4::class)
class MmsBackupTest {
    private val c get() = ApplicationProvider.getApplicationContext<AismoApp>().container
    private val pass = "mms backup passphrase".toCharArray()
    private val me = "+61411999000"
    private val friend = "+61411000777"
    private val other = "+61411000888"

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<AismoApp>()
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking { wipe(); c.settings.update { it.copy(ownNumber = me) } }
    }

    private suspend fun wipe() {
        c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
        c.provider.delete(c.provider.allIds().orEmpty())
        c.mmsProvider.delete(c.mmsProvider.allIds().orEmpty())
        c.repository.syncRecent()
    }

    private fun randomBytes(size: Int, seed: Long) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun received(from: String, text: String, image: ByteArray, atMs: Long): Long {
        val pdu = MmsPdu(
            type = MmsType.RETRIEVE_CONF, transactionId = "bk-$atMs", from = MmsAddress.of(from), to = listOf(MmsAddress.of(me), MmsAddress.of(other)),
            subject = "Backup me", dateSeconds = atMs / 1000, contentType = "application/vnd.wap.multipart.related",
            parts = listOf(
                MmsPart("text/plain", "text_0.txt", "text_0", MmsCharset.UTF_8, text.toByteArray()),
                MmsPart("image/jpeg", "photo.jpg", "photo", null, image),
            ),
        )
        val id = c.mmsProvider.insertRetrieved(pdu, -1, atMs, setOf(from, other))
        assertTrue("provider must accept the MMS", id > 0)
        return id
    }

    /** The same, addressed to me alone: a one-to-one thread, not a group. */
    private fun oneToOne(from: String, text: String, image: ByteArray, atMs: Long): Long {
        val pdu = MmsPdu(
            type = MmsType.RETRIEVE_CONF, transactionId = "1to1-$atMs", from = MmsAddress.of(from), to = listOf(MmsAddress.of(me)),
            subject = "Backup me", dateSeconds = atMs / 1000, contentType = "application/vnd.wap.multipart.related",
            parts = listOf(
                MmsPart("text/plain", "text_0.txt", "text_0", MmsCharset.UTF_8, text.toByteArray()),
                MmsPart("image/jpeg", "photo.jpg", "photo", null, image),
            ),
        )
        val id = c.mmsProvider.insertRetrieved(pdu, -1, atMs, setOf(from))
        assertTrue("provider must accept the MMS", id > 0)
        return id
    }

    private data class Shape(val box: Int, val dateMs: Long, val subject: String?, val parties: Set<String>, val parts: List<Triple<String, String?, Int>>, val digest: List<Int>)

    /** Everything about the MMS rows that a restore must reproduce, including a checksum of every part's bytes. */
    private fun mmsShapes(): List<Shape> = c.mmsProvider.readAfter(0, 1000).map { row ->
        val parts = c.mmsProvider.parts(row.rawId)
        Shape(
            row.box, row.dateMs, row.subject, (listOfNotNull(row.from) + row.to + row.cc).toSet(),
            parts.map { Triple(it.mime, it.name, it.sizeBytes.toInt()) }.sortedBy { it.toString() },
            parts.map { p -> if (p.text != null) p.text.hashCode() else c.mmsProvider.openPart(p.partId)!!.use { it.readBytes() }.contentHashCode() }.sorted(),
        )
    }.sortedBy { it.dateMs }

    @Test fun mmsWithAttachmentsSurvivesBackupWipeRestoreByteForByte() = runBlocking {
        val now = System.currentTimeMillis() / 1000 * 1000
        c.provider.insertIncoming(friend, "an sms in the same chat", now - 9_000, now - 9_000, -1)
        val small = randomBytes(40_000, 1)
        val large = randomBytes(3 * 1024 * 1024 + 17, 2) // spans many 64 KiB encryption chunks
        received(friend, "look at this ✓", small, now - 8_000)
        received(friend, "and this one", large, now - 7_000)
        c.repository.syncNew()
        val starredId = c.db.messages().mmsIdsBetween(now - 7_000, now - 7_000).single()
        c.db.messages().setStarred(starredId, true)
        val before = mmsShapes()
        assertEquals(2, before.size)
        assertEquals(3, c.db.messages().countSms() + c.db.messages().countBackupableMms())

        val (bytes, count) = c.backup.build(pass, includeMms = true)
        assertEquals(3, count)
        assertTrue("the encrypted backup must carry the attachment bytes", bytes.size > large.size)

        wipe()
        assertEquals(0, c.mmsProvider.allIds()!!.size)

        val first = c.backup.restore(bytes, pass)
        assertEquals(1, first.messagesAdded)
        assertEquals(2, first.mmsAdded)
        assertEquals(0, first.mmsSkipped)
        assertEquals("restored MMS must match the originals, part bytes included", before, mmsShapes())

        // The restored messages are real messages: indexed, with attachments and a group conversation.
        c.repository.syncNew()
        val indexed = c.db.messages().mmsIdsBetween(0, Long.MAX_VALUE).map { c.db.messages().get(it)!! }
        assertEquals(2, indexed.size)
        assertTrue(indexed.all { it.isMms && it.attachmentCount == 1 })
        assertTrue(indexed.all { it.body.contains("Backup me") })
        assertEquals("only the starred MMS comes back starred", listOf(now - 7_000), indexed.filter { it.isStarred }.map { it.date })

        val second = c.backup.restore(bytes, pass)
        assertEquals("restoring twice must not duplicate MMS", 0, second.mmsAdded)
        assertEquals(2, second.mmsSkipped)
        assertEquals(0, second.messagesAdded)
        assertEquals(2, c.mmsProvider.allIds()!!.size)
    }

    @Test fun backupWithoutTheOptionLeavesMmsOut() = runBlocking {
        val now = System.currentTimeMillis() / 1000 * 1000
        c.provider.insertIncoming(friend, "plain sms", now - 5_000, now - 5_000, -1)
        received(friend, "with a picture", randomBytes(10_000, 3), now - 4_000)
        c.repository.syncNew()

        val (bytes, count) = c.backup.build(pass, includeMms = false)
        assertEquals(1, count)
        wipe()
        val r = c.backup.restore(bytes, pass)
        assertEquals(1, r.messagesAdded)
        assertEquals(0, r.mmsAdded)
        assertEquals(0, c.mmsProvider.allIds()!!.size)
    }

    @Test fun downloadPlaceholdersAreNotBackedUp() = runBlocking {
        val now = System.currentTimeMillis() / 1000 * 1000
        received(friend, "real one", randomBytes(5_000, 4), now - 3_000)
        c.repository.syncNew()
        val id = c.db.messages().mmsIdsBetween(0, Long.MAX_VALUE).single()
        // A not-yet-downloaded notification has no content; mark the real message as one to prove it is skipped.
        c.db.mmsDownloads().upsert(
            aismo.sms.data.db.MmsDownloadEntity(
                messageId = id, contentLocation = "http://example.invalid/x", transactionId = "x", subId = -1, sizeBytes = 100,
                expiresAt = now + 100_000, state = 0, attempts = 0, lastError = 0, createdAt = now,
            ),
        )
        assertEquals(0, c.db.messages().countBackupableMms())
        val (_, count) = c.backup.build(pass, includeMms = true)
        assertEquals(0, count)
    }

    @Test fun aV1BackupWithoutMmsStillRestores() = runBlocking {
        val now = System.currentTimeMillis()
        val v1 = BackupCrypto.encrypt(
            BackupFormat.encode(sequenceOf(MetaRecord(1, now, "old", 1), MessageRecord(friend, "from a version 1 file", now - 2_000, 1, true, -1, false))),
            pass,
        )
        val r = c.backup.restore(v1, pass)
        assertEquals(1, r.messagesAdded)
        assertEquals("from a version 1 file", c.provider.readAfter(0, 10).single().body)
    }

    @Test fun aDamagedBackupAppliesNothing() = runBlocking {
        val now = System.currentTimeMillis() / 1000 * 1000
        c.provider.insertIncoming(friend, "sms", now - 5_000, now - 5_000, -1)
        received(friend, "pic", randomBytes(200_000, 5), now - 4_000)
        c.repository.syncNew()
        val (bytes, _) = c.backup.build(pass, includeMms = true)
        wipe()

        // Flip one byte near the end of the file: the last chunks (attachments) fail authentication.
        val damaged = bytes.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 0x55).toByte() }
        try {
            c.backup.restore(damaged, pass)
            fail("a damaged backup must be rejected")
        } catch (e: java.security.GeneralSecurityException) {
            assertTrue(e is BackupDamagedException || e is aismo.sms.backup.WrongPassphraseException)
        }
        assertEquals("verification happens before anything is written", 0, c.provider.allIds().orEmpty().size)
        assertEquals(0, c.mmsProvider.allIds()!!.size)

        // And a truncated file is caught too, rather than restoring a prefix.
        try {
            c.backup.restore(bytes.copyOf(bytes.size - 300), pass)
            fail("a truncated backup must be rejected")
        } catch (e: java.security.GeneralSecurityException) {
            // expected
        }
        assertEquals(0, c.provider.allIds().orEmpty().size)
        assertEquals(0, c.mmsProvider.allIds()!!.size)
        assertNotNull(bytes)
        assertFalse(bytes.isEmpty())
    }

    @Test fun anOutgoingMmsComesBackAsSentNotAsPending() = runBlocking {
        val id = c.mmsProvider.insertOutgoing(
            listOf(friend, other), "Plans", listOf(MmsPart("text/plain", "text_0.txt", "text_0", MmsCharset.UTF_8, "see you".toByteArray())),
            -1, "tsoX1",
        )
        assertTrue(id > 0)
        c.repository.syncNew()
        val (bytes, _) = c.backup.build(pass, includeMms = true)
        wipe()
        c.backup.restore(bytes, pass)
        val row = c.mmsProvider.readAfter(0, 10).single()
        assertTrue(
            "an outbox message must not be queued for sending again after a restore",
            row.box != android.provider.Telephony.Mms.MESSAGE_BOX_OUTBOX,
        )
        assertEquals(setOf(friend, other), row.to.toSet())
        assertEquals(MessageIds.mms(row.rawId), c.db.messages().mmsIdsBetween(0, Long.MAX_VALUE).single())
    }

    @Test fun aSentMmsWhoseDateTheSystemRewritesIsNotDuplicatedByARestore() = runBlocking {
        val id = c.mmsProvider.insertOutgoing(
            listOf(friend), null, listOf(MmsPart("text/plain", "text_0.txt", "text_0", MmsCharset.UTF_8, "stamped by the platform".toByteArray())),
            -1, "aism-date-1",
        )
        assertTrue(id > 0)
        c.repository.syncNew()
        val indexId = MessageIds.mms(id)
        val queued = c.db.messages().get(indexId)!!.date

        // After sending, the platform stamps the real send time on the provider row and moves it to "sent".
        val stamped = (queued / 1000 + 1) * 1000
        val values = android.content.ContentValues().apply {
            put(android.provider.Telephony.Mms.DATE, stamped / 1000)
            put(android.provider.Telephony.Mms.MESSAGE_BOX, android.provider.Telephony.Mms.MESSAGE_BOX_SENT)
        }
        val app = ApplicationProvider.getApplicationContext<AismoApp>()
        assertEquals(1, app.contentResolver.update(android.provider.Telephony.Mms.CONTENT_URI, values, "_id=?", arrayOf(id.toString())))
        c.repository.syncRecent()
        assertEquals("the index follows the provider's date, not the date at queue time", stamped, c.db.messages().get(indexId)!!.date)

        val (bytes, _) = c.backup.build(pass, includeMms = true)
        val r = c.backup.restore(bytes, pass)
        assertEquals("a message that is already on the phone is never added again", 0, r.mmsAdded)
        assertEquals(1, r.mmsSkipped)
        assertEquals(1, c.mmsProvider.allIds()!!.size)
    }

    @Test fun aHiddenMmsThreadSurvivesARestoreOnANewPhone() = runBlocking {
        // The phone being backed up: text and pictures kept together, and the thread hidden.
        c.settings.update { it.copy(groupSmsAndMms = true) }
        c.settings.setGroupingApplied(false)
        val now = System.currentTimeMillis() / 1000 * 1000
        c.provider.insertIncoming(friend, "an sms in the same thread", now - 9_000, now - 9_000, -1)
        oneToOne(friend, "a photo message", randomBytes(10_000, 11), now - 4_000)
        c.repository.syncNew()
        c.repository.maintainGrouping()
        val pKey = aismo.sms.domain.ConversationKeys.of(friend)
        assertEquals("with the choice on, the text and the picture are one thread", pKey, c.db.conversations().allKeys().single())
        c.repository.setConversationCategory(pKey, "custom:hidden1234")
        c.repository.setPinned(pKey, true)
        c.repository.setMuted(pKey, true)

        val (bytes, _) = c.backup.build(pass, includeMms = true)
        assertEquals(2, c.db.messages().countSms() + c.db.messages().countBackupableMms())

        // The new phone: no history, and the choice has not been made yet.
        wipe()
        c.settings.update { it.copy(groupSmsAndMms = false) }
        c.settings.setGroupingApplied(false)

        val r = c.backup.restore(bytes, pass)
        assertEquals(1, r.messagesAdded)
        assertEquals(1, r.mmsAdded)
        assertEquals("the restored thread is merged again, as the backup phone kept it", pKey, c.db.conversations().allKeys().single())
        val conv = c.db.conversations().get(pKey)!!
        assertEquals("the category the user chose survives the restore", "custom:hidden1234", conv.category)
        assertTrue("the choice stays locked", conv.categoryLocked)
        assertTrue(conv.isPinned)
        assertTrue(conv.isMuted)
    }

    @Test fun anOlderBackupOfAGroupedPhoneReachesBothHalvesOfASeparatedThread() = runBlocking {
        // The phone as it stands before the restore: text and pictures kept separate.
        c.settings.update { it.copy(groupSmsAndMms = false) }
        c.settings.setGroupingApplied(false)
        val now = System.currentTimeMillis() / 1000 * 1000
        c.provider.insertIncoming(friend, "an sms in the same thread", now - 9_000, now - 9_000, -1)
        oneToOne(friend, "a photo message", randomBytes(10_000, 12), now - 4_000)
        c.repository.syncNew()
        val pKey = aismo.sms.domain.ConversationKeys.of(friend)
        val mKey = aismo.sms.domain.ConversationKeys.of(friend, mms = true)
        assertEquals(setOf(pKey, mKey), c.db.conversations().allKeys().toSet())

        // An older version kept the thread together but could not write the choice down: its backup
        // carries the merged thread's choices under the p: key, and no grouping field at all.
        val old = BackupCrypto.encrypt(
            BackupFormat.encode(
                sequenceOf(
                    MetaRecord(1, now, "old", 0),
                    SettingsRecord(0, false, true, false, 24, false, true, true, false, true, true, true),
                    ConversationRecord(pKey, "custom:hidden1234", true, true, true),
                ),
            ),
            pass,
        )
        val r = c.backup.restore(old, pass)
        assertEquals(0, r.messagesAdded)

        val p = c.db.conversations().get(pKey)!!
        val m = c.db.conversations().get(mKey)!!
        assertEquals("the picture half must not be left behind", "custom:hidden1234", m.category)
        assertTrue(m.categoryLocked)
        assertTrue(m.isPinned)
        assertTrue(m.isMuted)
        assertEquals("custom:hidden1234", p.category)
        assertTrue(p.isPinned)
        assertTrue(p.isMuted)
    }
}
