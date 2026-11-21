package aismo.sms.domain.mms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** WSP / MMS well-known assigned numbers used by both the decoder and the encoder. */
internal object Wsp {
    // MMS header field names (OMA-TS-MMS_ENC-V1_2 table 7), already OR-ed with 0x80.
    const val BCC = 0x81
    const val CC = 0x82
    const val CONTENT_LOCATION = 0x83
    const val CONTENT_TYPE = 0x84
    const val DATE = 0x85
    const val DELIVERY_REPORT = 0x86
    const val DELIVERY_TIME = 0x87
    const val EXPIRY = 0x88
    const val FROM = 0x89
    const val MESSAGE_CLASS = 0x8A
    const val MESSAGE_ID = 0x8B
    const val MESSAGE_TYPE = 0x8C
    const val MMS_VERSION = 0x8D
    const val MESSAGE_SIZE = 0x8E
    const val PRIORITY = 0x8F
    const val READ_REPLY = 0x90
    const val REPORT_ALLOWED = 0x91
    const val RESPONSE_STATUS = 0x92
    const val RESPONSE_TEXT = 0x93
    const val SENDER_VISIBILITY = 0x94
    const val STATUS = 0x95
    const val SUBJECT = 0x96
    const val TO = 0x97
    const val TRANSACTION_ID = 0x98
    const val RETRIEVE_STATUS = 0x99
    const val RETRIEVE_TEXT = 0x9A
    const val READ_STATUS = 0x9B

    // Body part header fields (WSP table 39 + MMS usage).
    const val PART_CONTENT_LOCATION = 0x8E
    const val PART_CONTENT_ID = 0xC0
    const val PART_DISPOSITION_OLD = 0xAE
    const val PART_DISPOSITION = 0xC5
    const val PART_TRANSFER_ENCODING = 0xC8

    // Content-type parameter tokens (both the WSP 1.1 and the 1.4 numbering are seen in the wild).
    const val P_CHARSET = 0x81
    const val P_TYPE = 0x83
    const val P_NAME_OLD = 0x85
    const val P_FILENAME_OLD = 0x86
    const val P_CT_MR_TYPE = 0x89
    const val P_START_OLD = 0x8A
    const val P_START_INFO_OLD = 0x8B
    const val P_NAME = 0x97
    const val P_FILENAME = 0x98
    const val P_START = 0x99
    const val P_START_INFO = 0x9A

    const val QUOTE = 0x7F
    const val LENGTH_QUOTE = 0x1F
    const val ADDRESS_PRESENT = 0x80
    const val INSERT_ADDRESS = 0x81
    const val ABSOLUTE_TOKEN = 0x80
    const val RELATIVE_TOKEN = 0x81

    /** WSP table 40 "Content Type Assignments" for the types MMS actually carries. */
    val wellKnownTypes: Map<Int, String> = mapOf(
        0x00 to "*/*", 0x01 to "text/*", 0x02 to "text/html", 0x03 to "text/plain", 0x06 to "text/x-vCalendar",
        0x07 to "text/x-vCard", 0x08 to "text/vnd.wap.wml", 0x0B to "multipart/*", 0x0C to "multipart/mixed",
        0x0F to "multipart/alternative", 0x10 to "application/*", 0x1C to "image/*", 0x1D to "image/gif",
        0x1E to "image/jpeg", 0x1F to "image/tiff", 0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp",
        0x22 to "application/vnd.wap.multipart.*", 0x23 to "application/vnd.wap.multipart.mixed",
        0x26 to "application/vnd.wap.multipart.alternative", 0x27 to "application/xml", 0x28 to "text/xml",
        0x33 to "application/vnd.wap.multipart.related", 0x3E to "application/vnd.wap.mms-message",
        0x4F to "audio/*", 0x50 to "video/*",
    )
    val wellKnownCodes: Map<String, Int> = wellKnownTypes.entries.associate { it.value.lowercase() to it.key }

    fun charsetFor(mib: Int?): Charset = when (mib) {
        MmsCharset.US_ASCII -> Charsets.US_ASCII
        MmsCharset.ISO_8859_1 -> Charsets.ISO_8859_1
        MmsCharset.UTF_16 -> Charsets.UTF_16
        1013 -> Charsets.UTF_16BE
        1014 -> Charsets.UTF_16LE
        else -> Charsets.UTF_8
    }
}

/** Decodes MMS PDUs (m-notification-ind, m-retrieve-conf, m-send-conf, delivery reports...). */
object MmsDecoder {
    fun decode(bytes: ByteArray): MmsPdu {
        try {
            return Reader(bytes).readPdu()
        } catch (e: IndexOutOfBoundsException) {
            throw MmsFormatException("Truncated MMS PDU")
        } catch (e: NegativeArraySizeException) {
            throw MmsFormatException("Corrupt MMS PDU length")
        }
    }

    private class Reader(private val b: ByteArray) {
        var pos = 0

        fun more() = pos < b.size
        fun peek(): Int = b[pos].toInt() and 0xFF
        fun next(): Int = b[pos++].toInt() and 0xFF

        fun uintvar(): Int {
            var value = 0L
            repeat(5) {
                val c = next()
                value = (value shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return value.toInt().also { if (value > Int.MAX_VALUE) throw MmsFormatException("uintvar too large") }
            }
            throw MmsFormatException("uintvar too long")
        }

        /** Short-length (0..30) or Length-quote + Uintvar. */
        fun valueLength(): Int {
            val c = next()
            return when {
                c <= 30 -> c
                c == Wsp.LENGTH_QUOTE -> uintvar()
                else -> throw MmsFormatException("Bad value-length 0x${c.toString(16)}")
            }
        }

        /** Short-integer (0x80|n) or Long-integer (length 1..30 followed by that many big-endian octets). */
        fun integerValue(): Long {
            val c = next()
            if (c >= 0x80) return (c and 0x7F).toLong()
            if (c !in 1..30) throw MmsFormatException("Bad integer-value 0x${c.toString(16)}")
            var v = 0L
            repeat(c) { v = (v shl 8) or next().toLong() }
            return v
        }

        fun textString(charset: Charset = Charsets.UTF_8): String {
            if (peek() == Wsp.QUOTE) pos++
            val start = pos
            while (b[pos].toInt() != 0) pos++
            val s = String(b, start, pos - start, charset)
            pos++ // end-of-string
            return s
        }

        /** Quoted-string as used by Content-ID: an optional 0x22 quote, then a text string. */
        fun quotedString(): String {
            if (peek() == 0x22) pos++
            return textString()
        }

        /** Encoded-string-value: plain text-string, or value-length + charset + text-string. */
        fun encodedString(): String {
            if (peek() > 30 && peek() != Wsp.LENGTH_QUOTE) return textString()
            val len = valueLength()
            val end = pos + len
            val charset = Wsp.charsetFor(integerValue().toInt())
            val s = if (pos < end) {
                if (peek() == Wsp.QUOTE) pos++
                val start = pos
                while (pos < end && b[pos].toInt() != 0) pos++
                String(b, start, pos - start, charset)
            } else ""
            pos = end
            return s
        }

        /** Skips any Field-value (WSP 8.4.2.3) without interpreting it. */
        fun skipValue() {
            val c = peek()
            when {
                c >= 0x80 -> pos++
                c == 0 -> pos++
                c in 1..30 -> { pos++; pos += c }
                c == Wsp.LENGTH_QUOTE -> { pos++; val n = uintvar(); pos += n }
                else -> textString()
            }
        }

        class ContentType(val type: String, val charset: Int?, val start: String?, val name: String?)

        fun contentType(): ContentType {
            val c = peek()
            if (c >= 0x80) {
                pos++
                return ContentType(Wsp.wellKnownTypes[c and 0x7F] ?: "application/octet-stream", null, null, null)
            }
            if (c > 30 && c != Wsp.LENGTH_QUOTE) return ContentType(textString().lowercase(), null, null, null)
            val len = valueLength()
            val end = pos + len
            val type = when {
                peek() >= 0x80 -> Wsp.wellKnownTypes[next() and 0x7F] ?: "application/octet-stream"
                peek() in 1..30 -> Wsp.wellKnownTypes[integerValue().toInt()] ?: "application/octet-stream"
                else -> textString().lowercase()
            }
            var charset: Int? = null
            var start: String? = null
            var name: String? = null
            while (pos < end) {
                val token = next()
                if (token >= 0x80) {
                    when (token) {
                        Wsp.P_CHARSET -> charset = integerValue().toInt()
                        Wsp.P_NAME, Wsp.P_NAME_OLD, Wsp.P_FILENAME, Wsp.P_FILENAME_OLD -> name = textString()
                        Wsp.P_START, Wsp.P_START_OLD -> start = textString()
                        Wsp.P_START_INFO, Wsp.P_START_INFO_OLD, Wsp.P_CT_MR_TYPE, Wsp.P_TYPE -> skipValue()
                        else -> skipValue()
                    }
                } else {
                    pos-- // untyped parameter: token-text name followed by an untyped value
                    textString()
                    skipValue()
                }
            }
            pos = end
            return ContentType(type, charset, start, name)
        }

        fun readPdu(): MmsPdu {
            var type = -1
            var tid: String? = null
            var version = MmsVersion.V1_2
            var messageId: String? = null
            var from: MmsAddress? = null
            val to = mutableListOf<MmsAddress>()
            val cc = mutableListOf<MmsAddress>()
            val bcc = mutableListOf<MmsAddress>()
            var subject: String? = null
            var date: Long? = null
            var cls: Int? = null
            var priority: Int? = null
            var size: Long? = null
            var expiry: Long? = null
            var location: String? = null
            var deliveryReport: Boolean? = null
            var readReport: Boolean? = null
            var responseStatus: Int? = null
            var retrieveStatus: Int? = null
            var status: Int? = null
            var ctype: ContentType? = null

            while (more()) {
                val field = peek()
                if (field < 0x80) { // application-defined header: token text + value
                    textString(); skipValue(); continue
                }
                pos++
                when (field) {
                    Wsp.MESSAGE_TYPE -> type = next()
                    Wsp.TRANSACTION_ID -> tid = textString()
                    Wsp.MMS_VERSION -> version = next()
                    Wsp.MESSAGE_ID -> messageId = textString()
                    Wsp.FROM -> {
                        val len = valueLength(); val end = pos + len
                        from = if (next() == Wsp.ADDRESS_PRESENT) MmsAddress(encodedString()) else null
                        pos = end
                    }
                    Wsp.TO -> to += MmsAddress(encodedString())
                    Wsp.CC -> cc += MmsAddress(encodedString())
                    Wsp.BCC -> bcc += MmsAddress(encodedString())
                    Wsp.SUBJECT -> subject = encodedString()
                    Wsp.DATE -> date = integerValue()
                    Wsp.MESSAGE_CLASS -> cls = if (peek() >= 0x80) next() else { textString(); null }
                    Wsp.PRIORITY -> priority = next()
                    Wsp.MESSAGE_SIZE -> size = integerValue()
                    Wsp.EXPIRY -> {
                        val len = valueLength(); val end = pos + len
                        val token = next()
                        val v = integerValue()
                        if (token == Wsp.RELATIVE_TOKEN) expiry = v
                        pos = end
                    }
                    Wsp.CONTENT_LOCATION -> location = textString()
                    Wsp.DELIVERY_REPORT -> deliveryReport = next() == 0x80
                    Wsp.READ_REPLY -> readReport = next() == 0x80
                    Wsp.RESPONSE_STATUS -> responseStatus = next()
                    Wsp.RETRIEVE_STATUS -> retrieveStatus = next()
                    Wsp.STATUS -> status = next()
                    Wsp.RESPONSE_TEXT, Wsp.RETRIEVE_TEXT -> encodedString()
                    Wsp.CONTENT_TYPE -> { ctype = contentType(); return finish(type, tid, version, messageId, from, to, cc, bcc, subject, date, cls, priority, size, expiry, location, deliveryReport, readReport, responseStatus, retrieveStatus, status, ctype) }
                    else -> skipValue()
                }
            }
            return finish(type, tid, version, messageId, from, to, cc, bcc, subject, date, cls, priority, size, expiry, location, deliveryReport, readReport, responseStatus, retrieveStatus, status, null)
        }

        private fun finish(
            type: Int, tid: String?, version: Int, messageId: String?, from: MmsAddress?, to: List<MmsAddress>, cc: List<MmsAddress>,
            bcc: List<MmsAddress>, subject: String?, date: Long?, cls: Int?, priority: Int?, size: Long?, expiry: Long?, location: String?,
            deliveryReport: Boolean?, readReport: Boolean?, responseStatus: Int?, retrieveStatus: Int?, status: Int?, ctype: ContentType?,
        ): MmsPdu {
            if (type < 0) throw MmsFormatException("Missing X-Mms-Message-Type")
            val parts = if (ctype != null && ctype.type.contains("multipart", ignoreCase = true) && more()) readParts() else emptyList()
            return MmsPdu(
                type = type, transactionId = tid, version = version, messageId = messageId, from = from, to = to, cc = cc, bcc = bcc,
                subject = subject, dateSeconds = date, messageClass = cls, priority = priority, messageSize = size,
                expiryRelativeSeconds = expiry, contentLocation = location, deliveryReport = deliveryReport, readReport = readReport,
                responseStatus = responseStatus, retrieveStatus = retrieveStatus, status = status, contentType = ctype?.type, parts = parts,
            )
        }

        private fun readParts(): List<MmsPart> {
            val count = uintvar()
            if (count < 0 || count > 1000) throw MmsFormatException("Implausible part count $count")
            val parts = ArrayList<MmsPart>(count)
            repeat(count) {
                val headersLen = uintvar()
                val dataLen = uintvar()
                val headersStart = pos
                val headersEnd = headersStart + headersLen
                if (headersEnd + dataLen > b.size) throw MmsFormatException("Part runs past the end of the PDU")
                val ct = contentType()
                var location: String? = null
                var cid: String? = null
                var fileName: String? = null
                var encoding: String? = null
                while (pos < headersEnd) {
                    val field = peek()
                    if (field < 0x80) { textString(); skipValue(); continue }
                    pos++
                    when (field) {
                        Wsp.PART_CONTENT_LOCATION -> location = textString()
                        Wsp.PART_CONTENT_ID -> cid = quotedString().removePrefix("<").removeSuffix(">")
                        Wsp.PART_TRANSFER_ENCODING -> encoding = textString().lowercase()
                        Wsp.PART_DISPOSITION, Wsp.PART_DISPOSITION_OLD -> {
                            val len = valueLength(); val end = pos + len
                            next() // form-data / attachment / inline token
                            while (pos < end) {
                                val t = next()
                                if (t == Wsp.P_FILENAME || t == Wsp.P_FILENAME_OLD) fileName = textString() else if (t >= 0x80) skipValue() else { pos--; textString(); skipValue() }
                            }
                            pos = end
                        }
                        else -> skipValue()
                    }
                }
                pos = headersEnd
                var data = b.copyOfRange(headersEnd, headersEnd + dataLen)
                if (encoding == "base64") data = runCatching { java.util.Base64.getMimeDecoder().decode(data) }.getOrDefault(data)
                pos = headersEnd + dataLen
                parts += MmsPart(ct.type, location ?: ct.name, cid, ct.charset, data, fileName)
            }
            return parts
        }
    }
}

/** Encodes the PDUs this app has to send: m-send-req, m-notifyresp-ind and m-acknowledge-ind. */
object MmsEncoder {
    fun encodeSendReq(pdu: MmsPdu): ByteArray {
        val out = Out()
        out.byte(Wsp.MESSAGE_TYPE); out.byte(MmsType.SEND_REQ)
        out.byte(Wsp.TRANSACTION_ID); out.text(pdu.transactionId ?: error("transaction id required"))
        out.byte(Wsp.MMS_VERSION); out.byte(pdu.version)
        pdu.dateSeconds?.let { out.byte(Wsp.DATE); out.longInt(it) }
        // From: insert-address-token, the MMSC fills in the sender's number.
        out.byte(Wsp.FROM); out.byte(1); out.byte(Wsp.INSERT_ADDRESS)
        pdu.to.forEach { out.byte(Wsp.TO); out.encoded(it.raw) }
        pdu.cc.forEach { out.byte(Wsp.CC); out.encoded(it.raw) }
        pdu.bcc.forEach { out.byte(Wsp.BCC); out.encoded(it.raw) }
        pdu.subject?.takeIf { it.isNotEmpty() }?.let { out.byte(Wsp.SUBJECT); out.encoded(it) }
        out.byte(Wsp.MESSAGE_CLASS); out.byte(pdu.messageClass ?: 0x80) // personal
        out.byte(Wsp.PRIORITY); out.byte(pdu.priority ?: 0x81) // normal
        out.byte(Wsp.DELIVERY_REPORT); out.byte(if (pdu.deliveryReport == true) 0x80 else 0x81)
        out.byte(Wsp.READ_REPLY); out.byte(if (pdu.readReport == true) 0x80 else 0x81)

        val smil = pdu.parts.firstOrNull { it.isSmil }
        val ct = Out()
        ct.byte(0x80 or 0x33) // application/vnd.wap.multipart.related
        if (smil != null) {
            ct.byte(Wsp.P_CT_MR_TYPE); ct.text("application/smil")
            ct.byte(Wsp.P_START_OLD); ct.text("<${smil.contentId ?: "smil"}>")
        }
        out.byte(Wsp.CONTENT_TYPE); out.valueLength(ct.size()); out.raw(ct.bytes())

        out.uintvar(pdu.parts.size)
        for (part in pdu.parts) {
            val headers = Out()
            headers.partContentType(part)
            part.name?.let { headers.byte(Wsp.PART_CONTENT_LOCATION); headers.text(it) }
            part.contentId?.let { headers.byte(Wsp.PART_CONTENT_ID); headers.byte(0x22); headers.text("<$it>") }
            out.uintvar(headers.size()); out.uintvar(part.data.size)
            out.raw(headers.bytes()); out.raw(part.data)
        }
        return out.bytes()
    }

    /** m-notifyresp-ind: tells the MMSC the message was (or was not) retrieved so it stops re-notifying. */
    fun encodeNotifyResp(transactionId: String, status: Int = MmsStatus.RETRIEVED): ByteArray {
        val out = Out()
        out.byte(Wsp.MESSAGE_TYPE); out.byte(MmsType.NOTIFYRESP_IND)
        out.byte(Wsp.TRANSACTION_ID); out.text(transactionId)
        out.byte(Wsp.MMS_VERSION); out.byte(MmsVersion.V1_2)
        out.byte(Wsp.STATUS); out.byte(status)
        out.byte(Wsp.REPORT_ALLOWED); out.byte(0x81) // do not send a delivery report on our behalf
        return out.bytes()
    }

    private class Out {
        private val s = ByteArrayOutputStream()
        fun size() = s.size()
        fun bytes(): ByteArray = s.toByteArray()
        fun byte(v: Int) = s.write(v)
        fun raw(b: ByteArray) = s.write(b, 0, b.size)

        fun uintvar(v: Int) {
            var value = v
            val groups = ArrayList<Int>()
            do { groups += value and 0x7F; value = value ushr 7 } while (value != 0)
            for (i in groups.indices.reversed()) s.write(groups[i] or if (i != 0) 0x80 else 0)
        }

        fun valueLength(n: Int) { if (n <= 30) s.write(n) else { s.write(Wsp.LENGTH_QUOTE); uintvar(n) } }

        fun shortInt(v: Int) = s.write(v or 0x80)

        /** Integer-value: a short-integer up to 127, otherwise a long-integer. */
        fun intValue(v: Int) { if (v in 0..127) shortInt(v) else longInt(v.toLong()) }

        fun longInt(v: Long) {
            var n = 1
            while (n < 8 && (v ushr (8 * n)) != 0L) n++
            s.write(n)
            for (i in n - 1 downTo 0) s.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun text(t: String) {
            val bytes = t.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty() && (bytes[0].toInt() and 0x80) != 0) s.write(Wsp.QUOTE)
            raw(bytes); s.write(0)
        }

        /** General form with an explicit UTF-8 charset; this is what carriers' MMSCs expect for To and Subject. */
        fun encoded(t: String) {
            val body = Out()
            body.intValue(MmsCharset.UTF_8); body.text(t)
            valueLength(body.size()); raw(body.bytes())
        }

        fun partContentType(part: MmsPart) {
            val code = Wsp.wellKnownCodes[part.contentType.lowercase()]
            if (part.charset == null) {
                if (code != null) shortInt(code) else text(part.contentType)
                return
            }
            val body = Out()
            if (code != null) body.shortInt(code) else body.text(part.contentType)
            body.byte(Wsp.P_CHARSET); body.intValue(part.charset)
            valueLength(body.size()); raw(body.bytes())
        }
    }
}
