package aismo.sms.domain.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * The decoder tests use byte sequences assembled by hand from OMA-TS-MMS_ENC-V1_2 and WAP-230-WSP, with
 * helpers that are independent of [MmsEncoder], so they check conformance to the spec, not self-consistency.
 */
class MmsCodecTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun z(s: String) = s.toByteArray(Charsets.UTF_8) + 0
    private fun cat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()
    private fun uv(n: Int): ByteArray {
        val g = ArrayList<Int>(); var v = n
        do { g += v and 0x7F; v = v ushr 7 } while (v != 0)
        return ByteArray(g.size) { i -> (g[g.size - 1 - i] or if (i != g.size - 1) 0x80 else 0).toByte() }
    }
    /** Encoded-string general form with UTF-8: value-length, 0xEA, text, 0. */
    private fun enc(s: String): ByteArray { val body = b(0xEA) + z(s); return b(body.size) + body }

    private fun notificationInd() = cat(
        b(0x8C, 0x82), b(0x98), z("Tx1"), b(0x8D, 0x92),
        b(0x89), b(1 + z("+15551234567/TYPE=PLMN").size), b(0x80), z("+15551234567/TYPE=PLMN"),
        b(0x96), z("Hello"), b(0x8A, 0x80), b(0x8E, 0x02, 0x07, 0xD0),
        b(0x88, 0x05, 0x81, 0x03, 0x03, 0xF4, 0x80),
        b(0x83), z("http://mmsc.example/msg/123"),
    )

    @Test fun notificationIndIsDecodedFromSpecBytes() {
        val p = MmsDecoder.decode(notificationInd())
        assertEquals(MmsType.NOTIFICATION_IND, p.type)
        assertEquals("Tx1", p.transactionId)
        assertEquals(MmsVersion.V1_2, p.version)
        assertEquals("+15551234567", p.from?.value)
        assertEquals("Hello", p.subject)
        assertEquals(2000L, p.messageSize)
        assertEquals(259_200L, p.expiryRelativeSeconds)
        assertEquals("http://mmsc.example/msg/123", p.contentLocation)
        assertEquals(0x80, p.messageClass)
    }

    private val jpeg = b(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF, 0xD9)

    private fun retrieveConf(): ByteArray {
        val ct = b(0x80 or 0x33) + b(0x89) + z("application/smil") + b(0x8A) + z("<0000>")
        val text = "Hello ✓".toByteArray(Charsets.UTF_8)
        val h1 = b(0x03, 0x83, 0x81, 0xEA) + b(0x8E) + z("text_0.txt") + b(0xC0, 0x22) + z("<text_0>")
        val h2 = b(0x9E) + b(0x8E) + z("photo.jpg") + b(0xC0, 0x22) + z("<photo>")
        return cat(
            b(0x8C, 0x84), b(0x98), z("R7"), b(0x8D, 0x92),
            b(0x89, 1 + z("+15551234567/TYPE=PLMN").size, 0x80), z("+15551234567/TYPE=PLMN"),
            b(0x97), z("+15557654321/TYPE=PLMN"), b(0x97), z("+15550001111/TYPE=PLMN"),
            b(0x96), enc("Subject ✓"), b(0x85, 0x04, 0x65, 0x5B, 0x3E, 0x80),
            b(0x84), b(ct.size), ct,
            uv(2),
            uv(h1.size), uv(text.size), h1, text,
            uv(h2.size), uv(jpeg.size), h2, jpeg,
        )
    }

    @Test fun retrieveConfWithMultipartRelatedIsDecodedFromSpecBytes() {
        val p = MmsDecoder.decode(retrieveConf())
        assertEquals(MmsType.RETRIEVE_CONF, p.type)
        assertEquals("R7", p.transactionId)
        assertEquals("+15551234567", p.from?.value)
        assertEquals(listOf("+15557654321", "+15550001111"), p.to.map { it.value })
        assertEquals("Subject ✓", p.subject)
        assertEquals(0x655B3E80L, p.dateSeconds)
        assertEquals("application/vnd.wap.multipart.related", p.contentType)
        assertEquals(2, p.parts.size)
        val text = p.parts[0]
        assertEquals("text/plain", text.contentType)
        assertEquals(MmsCharset.UTF_8, text.charset)
        assertEquals("text_0.txt", text.name)
        assertEquals("text_0", text.contentId)
        assertEquals("Hello ✓", text.decodeText())
        val image = p.parts[1]
        assertEquals("image/jpeg", image.contentType)
        assertEquals("photo.jpg", image.name)
        assertArrayEquals(jpeg, image.data)
        assertEquals("Hello ✓", p.text)
    }

    @Test fun base64TransferEncodedPartIsDecoded() {
        val ct = b(0x80 or 0x33)
        val data = "SGVsbG8sIHdvcmxkIQ==".toByteArray()
        val h = b(0x03, 0x83, 0x81, 0xEA) + b(0xC8) + z("base64")
        val pdu = cat(b(0x8C, 0x84, 0x8D, 0x92, 0x84, ct.size), ct, uv(1), uv(h.size), uv(data.size), h, data)
        assertEquals("Hello, world!", MmsDecoder.decode(pdu).parts.single().decodeText())
    }

    @Test fun textStringsStartingWithAHighByteNeedTheQuoteAndAreDecoded() {
        val pdu = cat(b(0x8C, 0x82, 0x8D, 0x92, 0x96, 0x7F, 0xC3, 0xA9, 0x00))
        assertEquals("é", MmsDecoder.decode(pdu).subject)
    }

    @Test fun unknownAndApplicationHeadersAreSkipped() {
        val pdu = cat(b(0x8C, 0x82), b(0x9C, 0x80), z("X-Foo"), z("bar"), b(0xB5, 0x03, 1, 2, 3), b(0x98), z("T9"), b(0x8D, 0x92))
        val p = MmsDecoder.decode(pdu)
        assertEquals("T9", p.transactionId)
    }

    @Test fun sendConfCarriesTheMmscResponse() {
        val pdu = cat(b(0x8C, 0x81), b(0x98), z("S1"), b(0x8D, 0x92), b(0x92, 0x80), b(0x8B), z("msgid-123"))
        val p = MmsDecoder.decode(pdu)
        assertEquals(MmsType.SEND_CONF, p.type)
        assertEquals(MmsStatus.RESPONSE_OK, p.responseStatus)
        assertEquals("msgid-123", p.messageId)
    }

    @Test fun missingMessageTypeIsRejected() {
        try { MmsDecoder.decode(cat(b(0x98), z("T"))); fail() } catch (e: MmsFormatException) { /* ok */ }
        try { MmsDecoder.decode(ByteArray(0)); fail() } catch (e: MmsFormatException) { /* ok */ }
    }

    @Test fun everyTruncationOfAValidPduFailsCleanlyOrDecodes() {
        for (full in listOf(retrieveConf(), notificationInd())) {
            for (n in 0 until full.size) {
                try { MmsDecoder.decode(full.copyOf(n)) } catch (e: MmsFormatException) { /* expected */ }
            }
        }
    }

    @Test fun randomGarbageNeverCrashesTheDecoderWithAnythingButFormatErrors() {
        val rnd = Random(42)
        val seed = retrieveConf()
        repeat(4000) {
            val bytes = if (it % 2 == 0) ByteArray(rnd.nextInt(80)).also(rnd::nextBytes) else seed.copyOf().also { s -> repeat(1 + rnd.nextInt(4)) { s[rnd.nextInt(s.size)] = rnd.nextInt(256).toByte() } }
            try { MmsDecoder.decode(bytes) } catch (e: MmsFormatException) { /* expected */ }
        }
    }

    @Test fun absurdLengthsDoNotAllocateOrHang() {
        // part count 0x0FFFFFFF followed by nothing
        val ct = b(0x80 or 0x33)
        val pdu = cat(b(0x8C, 0x84, 0x8D, 0x92, 0x84, ct.size), ct, b(0xFF, 0xFF, 0xFF, 0x7F))
        try { MmsDecoder.decode(pdu); fail() } catch (e: MmsFormatException) { /* ok */ }
        // a part claiming a huge data length
        val h = b(0x03, 0x83, 0x81, 0xEA)
        val pdu2 = cat(b(0x8C, 0x84, 0x8D, 0x92, 0x84, ct.size), ct, uv(1), uv(h.size), uv(1_000_000), h)
        try { MmsDecoder.decode(pdu2); fail() } catch (e: MmsFormatException) { /* ok */ }
    }

    private fun sample(imageSize: Int = 10) = MmsPdu(
        type = MmsType.SEND_REQ, transactionId = "tso-1", dateSeconds = 1_700_000_000,
        to = listOf(MmsAddress.of("+15551230001"), MmsAddress.of("+15551230002")), cc = listOf(MmsAddress.of("friend@example.com")),
        subject = "Trip ✓",
        parts = listOf(
            MmsPart("application/smil", "smil.xml", "smil", null, "<smil/>".toByteArray()),
            MmsPart("text/plain", "text_0.txt", "text_0", MmsCharset.UTF_8, "See you ✓".toByteArray()),
            MmsPart("image/jpeg", "p.jpg", "p", null, ByteArray(imageSize) { (it * 7).toByte() }),
            MmsPart("video/3gpp", "v.3gp", "v", null, ByteArray(5) { 9 }),
        ),
    )

    @Test fun sendReqRoundTrips() {
        val sent = sample()
        val back = MmsDecoder.decode(MmsEncoder.encodeSendReq(sent))
        assertEquals(MmsType.SEND_REQ, back.type)
        assertEquals("tso-1", back.transactionId)
        assertEquals(1_700_000_000L, back.dateSeconds)
        assertNull("the MMSC fills in From", back.from)
        assertEquals(sent.to.map { it.raw }, back.to.map { it.raw })
        assertEquals(listOf("+15551230001/TYPE=PLMN", "+15551230002/TYPE=PLMN"), back.to.map { it.raw })
        assertEquals(listOf("friend@example.com"), back.cc.map { it.raw })
        assertEquals("Trip ✓", back.subject)
        assertEquals(false, back.deliveryReport)
        assertEquals(false, back.readReport)
        assertEquals("application/vnd.wap.multipart.related", back.contentType)
        assertEquals(sent.parts, back.parts)
    }

    @Test fun partSizesAroundUintvarBoundariesRoundTrip() {
        for (size in listOf(0, 1, 127, 128, 16_383, 16_384, 70_000)) {
            val back = MmsDecoder.decode(MmsEncoder.encodeSendReq(sample(size)))
            assertEquals("size $size", size, back.parts[2].data.size)
        }
    }

    @Test fun notifyRespBytesMatchTheSpecExactly() {
        val expected = cat(b(0x8C, 0x83), b(0x98), z("T"), b(0x8D, 0x92, 0x95, 0x81, 0x91, 0x81))
        assertArrayEquals(expected, MmsEncoder.encodeNotifyResp("T"))
        assertEquals(MmsStatus.DEFERRED, MmsDecoder.decode(MmsEncoder.encodeNotifyResp("T", MmsStatus.DEFERRED)).status)
    }

    @Test fun sendReqHeaderStartsTheWayCarriersExpect() {
        val bytes = MmsEncoder.encodeSendReq(sample())
        assertArrayEquals(b(0x8C, 0x80, 0x98), bytes.copyOf(3))                    // message type first, then transaction id
        assertTrue(bytes.indexOfSub(b(0x89, 0x01, 0x81)) > 0)                       // From: insert-address-token
        assertTrue(bytes.indexOfSub(z("+15551230001/TYPE=PLMN")) > 0)              // PLMN suffix on phone numbers
        assertFalse(bytes.indexOfSub(z("friend@example.com/TYPE=PLMN")) > 0)       // e-mail addresses get no suffix
    }

    @Test fun addressHelpers() {
        assertEquals("+1555/TYPE=PLMN", MmsAddress.of("+1555").raw)
        assertEquals("a@b.co", MmsAddress.of("a@b.co").raw)
        assertEquals("+1555", MmsAddress("+1555/TYPE=PLMN").value)
        assertTrue(MmsAddress("a@b.co").isEmail)
    }

    @Test fun nonUtf8CharsetsAreHonoured() {
        val latin1 = "café".toByteArray(Charsets.ISO_8859_1)
        assertEquals("café", MmsPart("text/plain", charset = MmsCharset.ISO_8859_1, data = latin1).decodeText())
    }

    private fun ByteArray.indexOfSub(sub: ByteArray): Int {
        outer@ for (i in 0..size - sub.size) { for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer; return i }
        return -1
    }
}
