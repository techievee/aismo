package aismo.sms.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class StreamBackupTest {
    private val pass = "stream passphrase 1".toCharArray()
    private val rnd = Random(7)

    private fun seal(plain: ByteArray, p: CharArray = pass): ByteArray {
        val out = ByteArrayOutputStream()
        StreamCrypto.encrypt(out, p).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, p: CharArray = pass): ByteArray = StreamCrypto.decrypt(ByteArrayInputStream(sealed), p).use { it.readBytes() }

    private fun bytes(n: Int) = ByteArray(n).also(rnd::nextBytes)

    // ------------------------------------------------------------------ crypto

    @Test fun roundTripsAroundEveryChunkBoundary() {
        val c = StreamCrypto.CHUNK_SIZE
        for (n in listOf(0, 1, 15, 16, c - 1, c, c + 1, 2 * c - 1, 2 * c, 2 * c + 1, 5 * c + 123)) {
            val plain = bytes(n)
            assertArrayEquals("size $n", plain, open(seal(plain)))
        }
    }

    @Test fun anExactMultipleOfTheChunkSizeStillEndsWithALastChunk() {
        val sealed = seal(bytes(StreamCrypto.CHUNK_SIZE * 2))
        // header 5+16+8, then two full chunks of (4 + 65536 + 16) bytes each
        assertEquals(29 + 2 * (4 + StreamCrypto.CHUNK_SIZE + 16), sealed.size)
        val lastHeader = sealed[29 + 4 + StreamCrypto.CHUNK_SIZE + 16].toInt() and 0xFF
        assertTrue("second chunk carries the last flag", lastHeader and 0x80 != 0)
    }

    @Test fun writesInOddlySizedPiecesProduceTheSameData() {
        val plain = bytes(200_000)
        val out = ByteArrayOutputStream()
        StreamCrypto.encrypt(out, pass).use { s ->
            var o = 0
            val sizes = intArrayOf(1, 7, 4096, 65_535, 65_536, 65_537, 3)
            var i = 0
            while (o < plain.size) { val n = minOf(sizes[i++ % sizes.size], plain.size - o); s.write(plain, o, n); o += n }
        }
        assertArrayEquals(plain, open(out.toByteArray()))
    }

    @Test fun twoEncryptionsOfTheSameDataDiffer() = assertNotEquals(seal(bytes(100)).toList(), seal(ByteArray(100)).toList())

    @Test fun aWrongPassphraseIsRejectedBeforeAnyPlaintextIsReleased() {
        val sealed = seal(bytes(300_000))
        try { open(sealed, "nope nope nope".toCharArray()); fail() } catch (e: WrongPassphraseException) { /* ok */ }
    }

    @Test fun flippingAnyByteInTheFileIsDetected() {
        val plain = bytes(3 * StreamCrypto.CHUNK_SIZE + 500)
        val sealed = seal(plain)
        val positions = listOf(12, 22, 29, 30, 29 + 4 + 100, sealed.size / 2, sealed.size - 20, sealed.size - 1)
        for (i in positions) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x04).toByte() }
            try { open(bad); fail("flip at byte $i went undetected") } catch (e: java.security.GeneralSecurityException) { /* expected */ }
        }
    }

    @Test fun droppingTheTailIsDetected() {
        val c = StreamCrypto.CHUNK_SIZE
        val sealed = seal(bytes(3 * c + 10))
        val oneChunk = 4 + c + 16
        // cut exactly at a chunk boundary: the survivors are authentic but the last chunk is gone
        val cut = sealed.copyOf(29 + 2 * oneChunk)
        try { open(cut); fail("truncation at a chunk boundary went undetected") } catch (e: BackupDamagedException) { /* ok */ }
        for (n in listOf(29 + 3, 29 + oneChunk - 1, sealed.size - 1, 29)) {
            try { open(sealed.copyOf(n)); fail("truncation to $n bytes went undetected") } catch (e: java.security.GeneralSecurityException) { /* ok */ }
        }
    }

    @Test fun reorderedChunksAreDetected() {
        val c = StreamCrypto.CHUNK_SIZE
        val sealed = seal(bytes(3 * c + 10))
        val one = 4 + c + 16
        val a = sealed.copyOfRange(29, 29 + one); val b = sealed.copyOfRange(29 + one, 29 + 2 * one)
        val swapped = sealed.copyOf().also { System.arraycopy(b, 0, it, 29, one); System.arraycopy(a, 0, it, 29 + one, one) }
        try { open(swapped); fail() } catch (e: java.security.GeneralSecurityException) { /* ok */ }
    }

    @Test fun dataAppendedAfterTheLastChunkIsDetected() {
        val sealed = seal(bytes(1000))
        try { open(sealed + bytes(40)); fail() } catch (e: BackupDamagedException) { /* ok */ }
    }

    @Test fun anAbsurdChunkLengthIsRejectedWithoutAllocatingIt() {
        val sealed = seal(bytes(100))
        val bad = sealed.copyOf().also { it[29] = 0x7F; it[30] = 0xFF.toByte(); it[31] = 0xFF.toByte(); it[32] = 0xFF.toByte() }
        try { open(bad); fail() } catch (e: BackupDamagedException) { /* ok */ }
    }

    @Test fun otherFilesAreNotBackups() {
        for (b in listOf(ByteArray(0), "hello".toByteArray(), bytes(100))) {
            try { open(b); fail() } catch (e: NotABackupException) { /* ok */ }
        }
        assertTrue(StreamCrypto.isStreamFormat(seal(bytes(5))))
        assertTrue(!StreamCrypto.isStreamFormat("TSOB1....".toByteArray()))
    }

    @Test fun encryptionEmitsOutputProgressivelyInsteadOfBufferingEverything() {
        var emitted = 0L
        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) { emitted++ }
            override fun write(b: ByteArray, o: Int, l: Int) { emitted += l }
        }
        val s = StreamCrypto.encrypt(sink, pass)
        val block = bytes(1 shl 20)
        repeat(10) { s.write(block) }
        // 10 MB written, the stream not yet closed: all but the final (held-back) chunk must already be out.
        assertTrue("only $emitted bytes emitted after 10 MB of input", emitted >= 10L * 1024 * 1024 - StreamCrypto.CHUNK_SIZE)
        s.close()
        assertTrue(emitted > 10L * 1024 * 1024)
    }

    @Test fun decryptionNeedsOnlyOneChunkToReturnTheFirstByte() {
        val sealed = seal(bytes(5 * StreamCrypto.CHUNK_SIZE))
        var consumed = 0
        val counting = object : java.io.InputStream() {
            private val inner = ByteArrayInputStream(sealed)
            override fun read(): Int = inner.read().also { if (it >= 0) consumed++ }
            override fun read(b: ByteArray, o: Int, l: Int): Int = inner.read(b, o, l).also { if (it > 0) consumed += it }
        }
        val d = StreamCrypto.decrypt(counting, pass)
        d.read()
        assertTrue("consumed $consumed bytes for the first byte", consumed <= 29 + 4 + StreamCrypto.CHUNK_SIZE + 16)
    }

    // ------------------------------------------------------------------ frames and records

    private fun sampleMms() = MmsRecord(
        box = 1, dateMs = 1_700_000_000_000, read = false, subId = -1, subject = "Holiday ✓", from = "+61411000555", to = listOf("+61411999000"),
        cc = emptyList(), starred = true, transactionId = "t1",
        parts = listOf(MmsPartRecord("text/plain", "text_0.txt", "text_0", 106, "See the beach", 13), MmsPartRecord("image/jpeg", "beach.jpg", "beach", null, null, 5)),
    )

    @Test fun framesCarryRecordsAndRawBlobs() {
        val photo = bytes(300_000)
        val out = ByteArrayOutputStream()
        FrameWriter(GZIPOutputStream(StreamCrypto.encrypt(out, pass))).use { w ->
            w.json(MetaRecord(BackupFormat.VERSION, 1, BackupFormat.APP_ID, messageCount = 2, mmsCount = 1))
            w.json(MessageRecord("+61411000555", "hi ✓\nthere", 1_700_000_000_000, 1, true, -1, false))
            w.json(sampleMms()); w.blob(photo)
        }
        val r = FrameReader(GZIPInputStream(StreamCrypto.decrypt(ByteArrayInputStream(out.toByteArray()), pass)))
        val meta = BackupFormat.decodeRecord(r.next()!!.payload) as MetaRecord
        assertEquals(1, meta.mmsCount); assertEquals(BackupFormat.VERSION, meta.format)
        assertEquals("hi ✓\nthere", (BackupFormat.decodeRecord(r.next()!!.payload) as MessageRecord).body)
        assertEquals(sampleMms(), BackupFormat.decodeRecord(r.next()!!.payload))
        val blob = r.next()!!
        assertEquals(BackupFrames.BLOB, blob.type); assertArrayEquals(photo, blob.payload)
        assertNull("clean end of stream", r.next())
    }

    @Test fun anUnknownRecordTypeFromANewerVersionIsSkippedNotFatal() {
        assertNull(BackupFormat.decodeRecord("""{"t":"hologram","x":1}""".toByteArray()))
        assertNull(BackupFormat.decodeRecord("not json".toByteArray()))
    }

    @Test fun aV1MetaRecordWithoutAnMmsCountStillDecodes() {
        val meta = BackupFormat.decodeRecord("""{"t":"meta","format":1,"createdAt":1,"app":"x","messageCount":3}""".toByteArray()) as MetaRecord
        assertEquals(0, meta.mmsCount); assertEquals(3, meta.messageCount)
    }

    @Test fun corruptFramesFailCleanly() {
        for (bad in listOf(byteArrayOf(9, 0, 0, 0, 1, 7), byteArrayOf(1, 0x7F, 0x7F, 0x7F, 0x7F), byteArrayOf(2, 0, 0, 0, 10, 1, 2), byteArrayOf(1, 0, 0))) {
            try { FrameReader(ByteArrayInputStream(bad)).next(); fail("accepted ${bad.toList()}") } catch (e: BackupDamagedException) { /* ok */ }
        }
    }
}
