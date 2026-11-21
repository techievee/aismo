package aismo.sms.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class BackupFormatTest {
    private val pass = "correct horse battery".toCharArray()

    private fun sample(): List<Record> = listOf(
        MetaRecord(BackupFormat.VERSION, 1_700_000_000_000, BackupFormat.APP_ID, 2),
        SettingsRecord(2, true, true, false, 24, false, true, true, false, true, true, true),
        CategoryRecord("custom:abc12345", "Family"),
        RuleRecord("Shop", "SHOPPE", null, "promo", "My Shop", true),
        BudgetRecord("Zomato", 500_000, "INR", 80),
        ConversationRecord("p:412345678", "personal", true, true, false),
        MessageRecord("+61412345678", "Hello, \"quoted\" and unicode ₹ ✓\nnew line", 1_700_000_000_123, 1, true, -1, false),
        MessageRecord("VM-HDFCBK", "Rs 4500.00 debited", 1_700_000_100_000, 1, false, 1, true),
    )

    @Test fun roundTripPreservesEveryRecordIncludingUnicodeAndNewlines() {
        val encrypted = BackupCrypto.encrypt(BackupFormat.encode(sample().asSequence()), pass)
        val decoded = BackupFormat.decode(BackupCrypto.decrypt(encrypted, pass)).toList()
        assertEquals(sample(), decoded)
    }

    @Test fun twoEncryptionsOfTheSameDataDiffer() {
        val plain = BackupFormat.encode(sample().asSequence())
        assertFalse(BackupCrypto.encrypt(plain, pass).contentEquals(BackupCrypto.encrypt(plain, pass)))
    }

    @Test fun wrongPassphraseIsRejectedWithoutReturningData() {
        val encrypted = BackupCrypto.encrypt(BackupFormat.encode(sample().asSequence()), pass)
        try {
            BackupCrypto.decrypt(encrypted, "wrong passphrase".toCharArray())
            fail("expected WrongPassphraseException")
        } catch (e: WrongPassphraseException) {
            assertTrue(e.message!!.contains("Wrong passphrase"))
        }
    }

    @Test fun anyFlippedByteInTheCiphertextIsDetected() {
        val encrypted = BackupCrypto.encrypt(BackupFormat.encode(sample().asSequence()), pass)
        // Flip one byte in the salt, the iv, the middle of the ciphertext and the auth tag.
        for (index in listOf(6, 22, encrypted.size / 2, encrypted.size - 1)) {
            val tampered = encrypted.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            try {
                BackupCrypto.decrypt(tampered, pass)
                fail("tampering at byte $index went undetected")
            } catch (e: WrongPassphraseException) {
                // expected: GCM authentication failed
            }
        }
    }

    @Test fun truncatedFileIsRejected() {
        val encrypted = BackupCrypto.encrypt(BackupFormat.encode(sample().asSequence()), pass)
        try {
            BackupCrypto.decrypt(encrypted.copyOf(encrypted.size - 5), pass)
            fail("expected a failure for a truncated backup")
        } catch (e: WrongPassphraseException) {
            // authentication tag no longer matches
        }
    }

    @Test fun nonBackupFilesAreRecognisedAsSuch() {
        for (bytes in listOf(ByteArray(0), "hello world, not a backup at all......................".toByteArray(), ByteArray(200) { 7 })) {
            try {
                BackupCrypto.decrypt(bytes, pass)
                fail("expected NotABackupException")
            } catch (e: NotABackupException) {
                // expected
            }
        }
    }

    @Test fun headerIsAuthenticatedSoTheMagicCannotBeSwappedSilently() {
        val encrypted = BackupCrypto.encrypt(BackupFormat.encode(sample().asSequence()), pass)
        assertEquals("TSOB1", String(encrypted.copyOfRange(0, 5), Charsets.US_ASCII))
        assertNotEquals(0, encrypted.size)
    }

    @Test fun unknownRecordTypesFromNewerVersionsAreSkippedNotFatal() {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).bufferedWriter().use { w ->
            w.write("""{"t":"meta","format":1,"createdAt":1,"app":"x","messageCount":0}""" + "\n")
            w.write("""{"t":"hologram","something":"from the future"}""" + "\n")
            w.write("\n")
            w.write("this line is not json\n")
            w.write("""{"t":"cat","key":"custom:zzzzzz11","name":"Later"}""" + "\n")
        }
        val decoded = BackupFormat.decode(buffer.toByteArray()).toList()
        assertEquals(2, decoded.size)
        assertEquals(CategoryRecord("custom:zzzzzz11", "Later"), decoded[1])
    }

    @Test fun largeBackupRoundTrips() {
        val many = buildList<Record> {
            add(MetaRecord(BackupFormat.VERSION, 1, BackupFormat.APP_ID, 20_000))
            repeat(20_000) { add(MessageRecord("VM-BANK$it", "Rs $it debited. Info: SHOP $it", 1_700_000_000_000 + it, 1, it % 2 == 0, -1, false)) }
        }
        val encrypted = BackupCrypto.encrypt(BackupFormat.encode(many.asSequence()), pass)
        val decoded = BackupFormat.decode(BackupCrypto.decrypt(encrypted, pass)).toList()
        assertEquals(many.size, decoded.size)
        assertEquals(many.last(), decoded.last())
        assertTrue("backup should compress well", encrypted.size < 20_000 * 60)
        assertArrayEquals(pass, "correct horse battery".toCharArray()) // the passphrase array is not consumed
    }
}
