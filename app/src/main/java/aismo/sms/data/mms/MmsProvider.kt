package aismo.sms.data.mms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.provider.Telephony.Mms
import aismo.sms.domain.mms.MmsCharset
import aismo.sms.domain.mms.MmsPart
import aismo.sms.domain.mms.MmsPdu
import aismo.sms.domain.mms.MmsType
import aismo.sms.domain.mms.MmsVersion
import java.io.InputStream

/** One attachment or text part as stored by the system provider. */
data class StoredPart(
    val partId: Long, val mime: String, val name: String?, val text: String?, val sizeBytes: Long,
    val contentId: String? = null, val charset: Int? = null,
)

/** One row of `content://mms` together with its addresses; parts are loaded separately. */
data class StoredMms(
    val rawId: Long,
    val threadId: Long,
    val box: Int,
    val dateMs: Long,
    val read: Boolean,
    val subId: Int,
    val messageType: Int,
    val subject: String?,
    val contentLocation: String?,
    val transactionId: String?,
    val sizeBytes: Long,
    val expirySeconds: Long?,
    val from: String?,
    val to: List<String>,
    val cc: List<String>,
) {
    val isNotification: Boolean get() = messageType == MmsType.NOTIFICATION_IND
}

/** Gateway to the system MMS provider (`content://mms`). Failures degrade to empty results. */
class MmsProvider(private val context: Context) {
    private val resolver get() = context.contentResolver

    private val projection = arrayOf(
        Mms._ID, Mms.THREAD_ID, Mms.MESSAGE_BOX, Mms.DATE, Mms.READ, Mms.SUBSCRIPTION_ID, Mms.MESSAGE_TYPE, Mms.SUBJECT,
        Mms.CONTENT_LOCATION, Mms.TRANSACTION_ID, Mms.MESSAGE_SIZE, Mms.EXPIRY,
    )

    // ---------------------------------------------------------------- reading

    fun readAfter(afterId: Long, limit: Int): List<StoredMms> =
        query("${Mms._ID} > ?", arrayOf(afterId.toString()), "${Mms._ID} ASC LIMIT $limit")

    fun readNewest(limit: Int): List<StoredMms> = query(null, null, "${Mms._ID} DESC LIMIT $limit")

    fun readByIds(ids: Collection<Long>): List<StoredMms> =
        if (ids.isEmpty()) emptyList() else ids.chunked(400).flatMap { query("${Mms._ID} IN (${it.joinToString(",")})", null, null) }

    fun readById(id: Long): StoredMms? = readByIds(listOf(id)).firstOrNull()

    /** Null when the provider could not be read, which must not be mistaken for "no messages". */
    fun allIds(): Set<Long>? = runCatching {
        resolver.query(Mms.CONTENT_URI, arrayOf(Mms._ID), null, null, null)?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    }.getOrNull()

    private fun query(selection: String?, args: Array<String>?, sort: String?): List<StoredMms> = runCatching {
        resolver.query(Mms.CONTENT_URI, projection, selection, args, sort)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val addrs = addresses(id)
                    add(
                        StoredMms(
                            rawId = id, threadId = c.getLong(1), box = c.getInt(2), dateMs = c.getLong(3) * 1000, read = c.getInt(4) == 1,
                            subId = c.getInt(5), messageType = c.getInt(6), subject = c.getString(7)?.takeIf { it.isNotBlank() },
                            contentLocation = c.getString(8), transactionId = c.getString(9), sizeBytes = c.getLong(10),
                            expirySeconds = if (c.isNull(11)) null else c.getLong(11),
                            from = addrs[TYPE_FROM]?.firstOrNull(), to = addrs[TYPE_TO].orEmpty(), cc = addrs[TYPE_CC].orEmpty(),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun addresses(msgId: Long): Map<Int, List<String>> = runCatching {
        val out = HashMap<Int, MutableList<String>>()
        resolver.query(Mms.Addr.getAddrUriForMessage(msgId.toString()), arrayOf(Mms.Addr.ADDRESS, Mms.Addr.TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val a = c.getString(0)?.trim().orEmpty()
                if (a.isEmpty() || a == INSERT_ADDRESS_TOKEN) continue
                out.getOrPut(c.getInt(1)) { mutableListOf() } += a
            }
        }
        out
    }.getOrDefault(emptyMap())

    fun parts(msgId: Long): List<StoredPart> = runCatching {
        val proj = arrayOf(Mms.Part._ID, Mms.Part.CONTENT_TYPE, Mms.Part.NAME, Mms.Part.FILENAME, Mms.Part.CONTENT_LOCATION, Mms.Part.TEXT, Mms.Part._DATA, Mms.Part.CONTENT_ID, Mms.Part.CHARSET)
        resolver.query(Mms.Part.getPartUriForMessage(msgId.toString()), proj, null, null, "${Mms.Part._ID} ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val mime = c.getString(1).orEmpty().lowercase()
                    val name = c.getString(2) ?: c.getString(3) ?: c.getString(4)
                    // The platform keeps plain text, HTML and the SMIL layout in the `text` column; every other
                    // text type (vCard, calendar...) is a file attachment even though its MIME starts with "text/".
                    val textual = mime == "text/plain" || mime == "text/html" || mime == "application/smil"
                    val text = if (textual) (c.getString(5) ?: readText(id)) else null
                    val size = if (text != null) text.toByteArray().size.toLong() else partSize(id)
                    add(StoredPart(id, mime, name, text, size, c.getString(7)?.removePrefix("<")?.removeSuffix(">"), if (c.isNull(8)) null else c.getInt(8)))
                }
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun partSize(partId: Long): Long = runCatching {
        resolver.openAssetFileDescriptor(partUri(partId), "r")?.use { it.length } ?: 0L
    }.getOrDefault(0L)

    private fun readText(partId: Long): String? = runCatching { openPart(partId)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()

    fun partUri(partId: Long): Uri = Uri.parse("content://mms/part/$partId")

    fun openPart(partId: Long): InputStream? = runCatching { resolver.openInputStream(partUri(partId)) }.getOrNull()

    // ---------------------------------------------------------------- writing

    /** Stores a downloaded message (m-retrieve-conf) in the inbox and returns its provider id, or -1. */
    fun insertRetrieved(pdu: MmsPdu, subId: Int, receivedAtMs: Long, others: Set<String>): Long {
        val values = baseValues(pdu, subId, Mms.MESSAGE_BOX_INBOX, receivedAtMs).apply {
            put(Mms.MESSAGE_TYPE, MmsType.RETRIEVE_CONF)
            put(Mms.READ, 0)
            put(Mms.SEEN, 0)
            pdu.dateSeconds?.let { put(Mms.DATE_SENT, it) }
            put(Mms.THREAD_ID, threadFor(others))
        }
        val id = insertRow(values)
        if (id < 0) return -1
        pdu.from?.let { addAddress(id, it.value, TYPE_FROM) }
        pdu.to.forEach { addAddress(id, it.value, TYPE_TO) }
        pdu.cc.forEach { addAddress(id, it.value, TYPE_CC) }
        pdu.parts.forEach { addPart(id, it) }
        return id
    }

    /** Stores the push notification so the user sees "downloading…" / "tap to download" in the thread. */
    fun insertNotification(pdu: MmsPdu, subId: Int, receivedAtMs: Long): Long {
        val sender = pdu.from?.value
        val values = ContentValues().apply {
            put(Mms.DATE, receivedAtMs / 1000)
            put(Mms.MESSAGE_BOX, Mms.MESSAGE_BOX_INBOX)
            put(Mms.MESSAGE_TYPE, MmsType.NOTIFICATION_IND)
            put(Mms.MMS_VERSION, pdu.version and 0x7F)
            put(Mms.READ, 0)
            put(Mms.SEEN, 0)
            put(Mms.TRANSACTION_ID, pdu.transactionId)
            put(Mms.CONTENT_LOCATION, pdu.contentLocation)
            put(Mms.MESSAGE_SIZE, pdu.messageSize ?: 0L)
            pdu.expiryRelativeSeconds?.let { put(Mms.EXPIRY, it) }
            pdu.subject?.let { put(Mms.SUBJECT, it); put(Mms.SUBJECT_CHARSET, MmsCharset.UTF_8) }
            if (subId >= 0) put(Mms.SUBSCRIPTION_ID, subId)
            put(Mms.THREAD_ID, threadFor(setOfNotNull(sender)))
        }
        val id = insertRow(values)
        if (id >= 0 && sender != null) addAddress(id, sender, TYPE_FROM)
        return id
    }

    /**
     * Re-creates an MMS from a backup in its original box (an outbox message becomes "failed" so that it
     * is not sent again on its own). Returns the provider id, or -1.
     */
    fun insertRestored(
        box: Int, dateMs: Long, read: Boolean, subId: Int, subject: String?, from: String?, to: List<String>, cc: List<String>,
        transactionId: String?, parts: List<MmsPart>, others: Set<String>,
    ): Long {
        val incoming = box == Mms.MESSAGE_BOX_INBOX
        val values = ContentValues().apply {
            put(Mms.DATE, dateMs / 1000)
            put(Mms.MESSAGE_BOX, if (box == Mms.MESSAGE_BOX_OUTBOX) Mms.MESSAGE_BOX_FAILED else box)
            put(Mms.MESSAGE_TYPE, if (incoming) MmsType.RETRIEVE_CONF else MmsType.SEND_REQ)
            put(Mms.MMS_VERSION, MmsVersion.V1_2 and 0x7F)
            put(Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Mms.READ, if (read) 1 else 0)
            put(Mms.SEEN, 1)
            transactionId?.let { put(Mms.TRANSACTION_ID, it) }
            put(Mms.PRIORITY, 129)
            if (!incoming) { put(Mms.DELIVERY_REPORT, 129); put(Mms.READ_REPORT, 129) }
            put(Mms.MESSAGE_SIZE, parts.sumOf { it.data.size }.toLong())
            put(Mms.TEXT_ONLY, if (parts.all { it.isText || it.isSmil }) 1 else 0)
            subject?.takeIf { it.isNotEmpty() }?.let { put(Mms.SUBJECT, it); put(Mms.SUBJECT_CHARSET, MmsCharset.UTF_8) }
            if (subId >= 0) put(Mms.SUBSCRIPTION_ID, subId)
            put(Mms.THREAD_ID, threadFor(others))
        }
        val id = insertRow(values)
        if (id < 0) return -1
        if (incoming) from?.let { addAddress(id, it, TYPE_FROM) } else addAddress(id, INSERT_ADDRESS_TOKEN, TYPE_FROM)
        to.forEach { addAddress(id, it, TYPE_TO) }
        cc.forEach { addAddress(id, it, TYPE_CC) }
        parts.forEach { addPart(id, it) }
        return id
    }

    /** Stores an outgoing message in the outbox (so it shows immediately) and returns its provider id, or -1. */
    fun insertOutgoing(recipients: List<String>, subject: String?, parts: List<MmsPart>, subId: Int, transactionId: String): Long {
        val values = ContentValues().apply {
            put(Mms.DATE, System.currentTimeMillis() / 1000)
            put(Mms.MESSAGE_BOX, Mms.MESSAGE_BOX_OUTBOX)
            put(Mms.MESSAGE_TYPE, MmsType.SEND_REQ)
            put(Mms.MMS_VERSION, MmsVersion.V1_2 and 0x7F)
            put(Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Mms.READ, 1)
            put(Mms.SEEN, 1)
            put(Mms.TRANSACTION_ID, transactionId)
            put(Mms.PRIORITY, 129)
            put(Mms.DELIVERY_REPORT, 129)
            put(Mms.READ_REPORT, 129)
            put(Mms.MESSAGE_SIZE, parts.sumOf { it.data.size }.toLong())
            put(Mms.TEXT_ONLY, if (parts.all { it.isText || it.isSmil }) 1 else 0)
            subject?.takeIf { it.isNotEmpty() }?.let { put(Mms.SUBJECT, it); put(Mms.SUBJECT_CHARSET, MmsCharset.UTF_8) }
            if (subId >= 0) put(Mms.SUBSCRIPTION_ID, subId)
            put(Mms.THREAD_ID, threadFor(recipients.toSet()))
        }
        val id = insertRow(values)
        if (id < 0) return -1
        addAddress(id, INSERT_ADDRESS_TOKEN, TYPE_FROM)
        recipients.forEach { addAddress(id, it, TYPE_TO) }
        parts.forEach { addPart(id, it) }
        return id
    }

    fun updateAfterSend(rawId: Long, box: Int, messageId: String?, responseStatus: Int?): Boolean = runCatching {
        val values = ContentValues().apply {
            put(Mms.MESSAGE_BOX, box)
            messageId?.let { put(Mms.MESSAGE_ID, it) }
            responseStatus?.let { put(Mms.RESPONSE_STATUS, it) }
            if (box == Mms.MESSAGE_BOX_SENT) put(Mms.DATE, System.currentTimeMillis() / 1000)
        }
        resolver.update(ContentUris.withAppendedId(Mms.CONTENT_URI, rawId), values, null, null) > 0
    }.getOrDefault(false)

    fun updateBox(rawId: Long, box: Int): Boolean = updateAfterSend(rawId, box, null, null)

    fun markRead(rawIds: Collection<Long>, read: Boolean): Int = runCatching {
        if (rawIds.isEmpty()) return 0
        val values = ContentValues().apply { put(Mms.READ, if (read) 1 else 0); put(Mms.SEEN, 1) }
        rawIds.chunked(400).sumOf { resolver.update(Mms.CONTENT_URI, values, "${Mms._ID} IN (${it.joinToString(",")})", null) }
    }.getOrDefault(0)

    fun delete(rawIds: Collection<Long>): Int = runCatching {
        if (rawIds.isEmpty()) return 0
        rawIds.chunked(400).sumOf { resolver.delete(Mms.CONTENT_URI, "${Mms._ID} IN (${it.joinToString(",")})", null) }
    }.getOrDefault(0)

    // ---------------------------------------------------------------- helpers

    private fun baseValues(pdu: MmsPdu, subId: Int, box: Int, dateMs: Long) = ContentValues().apply {
        put(Mms.DATE, dateMs / 1000)
        put(Mms.MESSAGE_BOX, box)
        put(Mms.MMS_VERSION, pdu.version and 0x7F)
        put(Mms.CONTENT_TYPE, pdu.contentType ?: "application/vnd.wap.multipart.related")
        pdu.messageId?.let { put(Mms.MESSAGE_ID, it) }
        pdu.transactionId?.let { put(Mms.TRANSACTION_ID, it) }
        put(Mms.PRIORITY, pdu.priority ?: 129)
        put(Mms.MESSAGE_SIZE, pdu.parts.sumOf { it.data.size }.toLong())
        put(Mms.TEXT_ONLY, if (pdu.parts.all { it.isText || it.isSmil }) 1 else 0)
        pdu.subject?.takeIf { it.isNotEmpty() }?.let { put(Mms.SUBJECT, it); put(Mms.SUBJECT_CHARSET, MmsCharset.UTF_8) }
        if (subId >= 0) put(Mms.SUBSCRIPTION_ID, subId)
    }

    private fun insertRow(values: ContentValues): Long = runCatching {
        resolver.insert(Mms.CONTENT_URI, values)?.let(ContentUris::parseId) ?: -1L
    }.getOrDefault(-1L)

    private fun addAddress(msgId: Long, address: String, type: Int) {
        val v = ContentValues().apply {
            put(Mms.Addr.ADDRESS, address); put(Mms.Addr.TYPE, type); put(Mms.Addr.CHARSET, MmsCharset.UTF_8); put(Mms.Addr.MSG_ID, msgId)
        }
        runCatching { resolver.insert(Mms.Addr.getAddrUriForMessage(msgId.toString()), v) }
    }

    private fun addPart(msgId: Long, part: MmsPart) {
        val textual = part.isText || part.isSmil
        val v = ContentValues().apply {
            put(Mms.Part.MSG_ID, msgId)
            put(Mms.Part.CONTENT_TYPE, part.contentType)
            part.name?.let { put(Mms.Part.NAME, it); put(Mms.Part.CONTENT_LOCATION, it) }
            part.contentId?.let { put(Mms.Part.CONTENT_ID, "<$it>") }
            put(Mms.Part.CHARSET, part.charset ?: MmsCharset.UTF_8)
            if (textual) put(Mms.Part.TEXT, String(part.data, Charsets.UTF_8))
        }
        val uri = runCatching { resolver.insert(Mms.Part.getPartUriForMessage(msgId.toString()), v) }.getOrNull() ?: return
        if (!textual) runCatching { resolver.openOutputStream(uri)?.use { it.write(part.data) } }
    }

    private fun threadFor(addresses: Set<String>): Long =
        if (addresses.isEmpty()) 0L else runCatching { Telephony.Threads.getOrCreateThreadId(context, addresses) }.getOrDefault(0L)

    companion object {
        const val TYPE_FROM = 137
        const val TYPE_TO = 151
        const val TYPE_CC = 130
        const val TYPE_BCC = 129
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"
    }
}
