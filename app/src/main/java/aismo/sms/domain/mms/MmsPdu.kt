package aismo.sms.domain.mms

/** X-Mms-Message-Type values (OMA-TS-MMS_ENC-V1_2, table 8). */
object MmsType {
    const val SEND_REQ = 0x80
    const val SEND_CONF = 0x81
    const val NOTIFICATION_IND = 0x82
    const val NOTIFYRESP_IND = 0x83
    const val RETRIEVE_CONF = 0x84
    const val ACKNOWLEDGE_IND = 0x85
    const val DELIVERY_IND = 0x86
    const val READ_REC_IND = 0x87
    const val READ_ORIG_IND = 0x88
}

object MmsStatus {
    /** X-Mms-Response-Status: the MMSC accepted the message. */
    const val RESPONSE_OK = 0x80
    /** X-Mms-Status carried by an m-notifyresp-ind. */
    const val RETRIEVED = 0x81
    const val REJECTED = 0x82
    const val DEFERRED = 0x83
    const val UNRECOGNISED = 0x84
    /** X-Mms-Retrieve-Status: success. */
    const val RETRIEVE_OK = 0x80
}

object MmsVersion {
    const val V1_0 = 0x90
    const val V1_1 = 0x91
    const val V1_2 = 0x92
}

/** MIBenum character sets that appear in MMS (IANA character-sets registry). */
object MmsCharset {
    const val US_ASCII = 3
    const val ISO_8859_1 = 4
    const val UTF_16 = 1015
    const val UTF_8 = 106
}

/** An address as carried on the wire, e.g. `+15551234567/TYPE=PLMN` or `a@b.example`. */
data class MmsAddress(val raw: String) {
    /** The address without the `/TYPE=` suffix, i.e. what a person would recognise. */
    val value: String get() = raw.substringBefore("/TYPE=", raw).trim()
    val isEmail: Boolean get() = '@' in value

    companion object {
        /** Builds the wire form: phone numbers get `/TYPE=PLMN`, e-mail addresses are left alone. */
        fun of(address: String): MmsAddress {
            val a = address.trim()
            return MmsAddress(if ('@' in a || "/TYPE=" in a) a else "$a/TYPE=PLMN")
        }
    }
}

/** One body part of a multipart MMS (text, image, SMIL layout, audio, vCard...). */
class MmsPart(
    val contentType: String,
    /** Content-Location, normally the file name. */
    val name: String? = null,
    /** Content-ID without the angle brackets. */
    val contentId: String? = null,
    /** MIBenum charset for text parts, null when unspecified. */
    val charset: Int? = null,
    val data: ByteArray = ByteArray(0),
    /** Content-Disposition filename when it differs from [name]. */
    val fileName: String? = null,
) {
    val isText: Boolean get() = contentType.startsWith("text/plain", ignoreCase = true)
    val isSmil: Boolean get() = contentType.equals("application/smil", ignoreCase = true)

    override fun equals(other: Any?): Boolean = other is MmsPart && contentType == other.contentType && name == other.name &&
        contentId == other.contentId && charset == other.charset && data.contentEquals(other.data) && fileName == other.fileName

    override fun hashCode(): Int = listOf(contentType, name, contentId, charset, fileName).hashCode() * 31 + data.contentHashCode()
    override fun toString() = "MmsPart($contentType, name=$name, cid=$contentId, ${data.size}B)"
}

/** A decoded MMS PDU. Only the fields this app uses are modelled. */
data class MmsPdu(
    val type: Int,
    val transactionId: String? = null,
    val version: Int = MmsVersion.V1_2,
    val messageId: String? = null,
    val from: MmsAddress? = null,
    val to: List<MmsAddress> = emptyList(),
    val cc: List<MmsAddress> = emptyList(),
    val bcc: List<MmsAddress> = emptyList(),
    val subject: String? = null,
    /** Seconds since the epoch, as on the wire. */
    val dateSeconds: Long? = null,
    val messageClass: Int? = null,
    val priority: Int? = null,
    val messageSize: Long? = null,
    val expiryRelativeSeconds: Long? = null,
    val contentLocation: String? = null,
    val deliveryReport: Boolean? = null,
    val readReport: Boolean? = null,
    val responseStatus: Int? = null,
    val retrieveStatus: Int? = null,
    val status: Int? = null,
    val contentType: String? = null,
    val parts: List<MmsPart> = emptyList(),
) {
    /** The text parts joined in order, which is what search and the parser read. */
    val text: String get() = parts.filter { it.isText }.joinToString("\n") { it.decodeText() }.trim()
}

fun MmsPart.decodeText(): String = when (charset) {
    MmsCharset.ISO_8859_1 -> String(data, Charsets.ISO_8859_1)
    MmsCharset.US_ASCII -> String(data, Charsets.US_ASCII)
    MmsCharset.UTF_16 -> String(data, Charsets.UTF_16)
    else -> String(data, Charsets.UTF_8)
}

class MmsFormatException(message: String) : Exception(message)
