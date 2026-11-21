package aismo.sms.data.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads

/** An attachment of an MMS as read from the provider. */
data class RawAttachment(val partId: Long, val mime: String, val name: String?, val sizeBytes: Long)

/** An MMS we were notified about but have not downloaded yet. */
data class PendingDownload(val contentLocation: String, val transactionId: String, val sizeBytes: Long, val expiresAtMs: Long?)

/**
 * One message read from a system provider. SMS rows use the provider `_id`; MMS rows carry the
 * translated id (see `MessageIds`), a subject and attachments. [attachments] is null when the caller
 * does not know them (re-parsing an indexed row), which means "leave the stored ones alone".
 */
data class RawSms(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Boolean,
    val subId: Int,
    val status: Int,
    val subject: String? = null,
    val attachments: List<RawAttachment>? = null,
    val download: PendingDownload? = null,
)

/**
 * Thin gateway to `content://sms`. Everything that reads or writes the system provider goes through
 * here so permission failures (we are not the default app) degrade to empty results, not crashes.
 */
class SmsProvider(private val context: Context) {
    private val resolver get() = context.contentResolver

    private val projection = arrayOf(
        Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.READ, Sms.SUBSCRIPTION_ID, Sms.STATUS,
    )

    private fun Cursor.toRaw() = RawSms(
        id = getLong(0),
        threadId = getLong(1),
        address = getString(2).orEmpty(),
        body = getString(3).orEmpty(),
        date = getLong(4),
        type = getInt(5),
        read = getInt(6) == 1,
        subId = getInt(7),
        status = getInt(8),
    )

    /** Rows with `_id` greater than [afterId], oldest first, excluding drafts. */
    fun readAfter(afterId: Long, limit: Int): List<RawSms> = query(
        "${Sms._ID} > ? AND ${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", arrayOf(afterId.toString()),
        "${Sms._ID} ASC LIMIT $limit",
    )

    /** The newest [limit] non-draft rows, newest first. */
    fun readNewest(limit: Int): List<RawSms> = query(
        "${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", null, "${Sms._ID} DESC LIMIT $limit",
    )

    fun readByIds(ids: Collection<Long>): List<RawSms> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(400).flatMap { chunk ->
            query("${Sms._ID} IN (${chunk.joinToString(",")})", null, null)
        }
    }

    fun readById(id: Long): RawSms? = readByIds(listOf(id)).firstOrNull()

    /**
     * Every non-draft `_id` currently in the provider, used to detect deletions made elsewhere.
     * Returns null when the provider could not be read at all (no permission, null cursor), which is
     * different from an empty provider: callers must not treat "unreadable" as "everything deleted".
     */
    fun allIds(): Set<Long>? = runCatching {
        resolver.query(Sms.CONTENT_URI, arrayOf(Sms._ID), "${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", null, null)
            ?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    }.getOrNull()

    private fun query(selection: String, args: Array<String>?, sort: String?): List<RawSms> = runCatching {
        resolver.query(Sms.CONTENT_URI, projection, selection, args, sort)?.use { c ->
            buildList { while (c.moveToNext()) add(c.toRaw()) }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun insertIncoming(address: String, body: String, date: Long, dateSent: Long, subId: Int): Long {
        val values = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, date)
            put(Sms.DATE_SENT, dateSent)
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_INBOX)
            if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
            put(Sms.THREAD_ID, threadFor(address))
        }
        return insert(values)
    }

    /** Re-creates a message from a backup with its original type, time and read state. */
    fun insertRestored(address: String, body: String, date: Long, type: Int, read: Boolean, subId: Int): Long {
        val values = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, date)
            put(Sms.READ, if (read) 1 else 0)
            put(Sms.SEEN, 1)
            put(Sms.TYPE, type)
            if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
            put(Sms.THREAD_ID, threadFor(address))
        }
        return insert(values)
    }

    fun insertOutgoing(address: String, body: String, subId: Int, type: Int = Sms.MESSAGE_TYPE_OUTBOX): Long {
        val values = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.TYPE, type)
            put(Sms.STATUS, Sms.STATUS_NONE)
            if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
            put(Sms.THREAD_ID, threadFor(address))
        }
        return insert(values)
    }

    private fun insert(values: ContentValues): Long = runCatching {
        resolver.insert(Sms.CONTENT_URI, values)?.let(ContentUris::parseId) ?: -1L
    }.getOrDefault(-1L)

    private fun threadFor(address: String): Long = runCatching { Threads.getOrCreateThreadId(context, address) }.getOrDefault(0L)

    fun updateSendState(id: Long, type: Int, status: Int? = null): Boolean = runCatching {
        val values = ContentValues().apply {
            put(Sms.TYPE, type)
            if (status != null) put(Sms.STATUS, status)
            if (type == Sms.MESSAGE_TYPE_SENT) put(Sms.DATE, System.currentTimeMillis())
        }
        resolver.update(ContentUris.withAppendedId(Sms.CONTENT_URI, id), values, null, null) > 0
    }.getOrDefault(false)

    fun markRead(ids: Collection<Long>, read: Boolean): Int = runCatching {
        if (ids.isEmpty()) return 0
        val values = ContentValues().apply { put(Sms.READ, if (read) 1 else 0); put(Sms.SEEN, 1) }
        ids.chunked(400).sumOf { chunk ->
            resolver.update(Sms.CONTENT_URI, values, "${Sms._ID} IN (${chunk.joinToString(",")})", null)
        }
    }.getOrDefault(0)

    fun delete(ids: Collection<Long>): Int = runCatching {
        if (ids.isEmpty()) return 0
        ids.chunked(400).sumOf { chunk ->
            resolver.delete(Sms.CONTENT_URI, "${Sms._ID} IN (${chunk.joinToString(",")})", null)
        }
    }.getOrDefault(0)
}
