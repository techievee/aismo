package aismo.sms.data.mms

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moves attachments between the outside world and app storage: picked files are copied in (so a
 * temporary grant cannot expire before sending), and received parts are copied out to share or save them.
 */
class AttachmentStore(private val context: Context, private val mms: MmsProvider) {
    private val outgoingDir get() = File(context.cacheDir, "outgoing").also { it.mkdirs() }
    private val sharedDir get() = File(context.cacheDir, "shared").also { it.mkdirs() }

    /**
     * Copies a picked content URI into app storage. Returns null if it cannot be read — an expired or
     * denied URI grant is the usual cause, and the failure is logged so it is not lost in the logs.
     */
    suspend fun importUri(uri: Uri): OutgoingAttachment? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val name = displayName(uri) ?: "attachment"
            val mime = resolver.getType(uri) ?: mimeFromName(name) ?: "application/octet-stream"
            val file = File(outgoingDir, "${System.nanoTime()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)}")
            val stream = resolver.openInputStream(uri)
            if (stream == null) android.util.Log.w("AttachmentStore", "openInputStream returned null for $uri (grant denied or expired?)")
            stream?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
            OutgoingAttachment(file, mime, name)
        }.onFailure {
            // Only the last segment, so a photo path or document name does not end up in the log in full.
            android.util.Log.w("AttachmentStore", "could not import picked file ${uri.lastPathSegment ?: uri.scheme}", it)
        }.getOrNull()
    }

    fun discard(a: OutgoingAttachment) { a.file.delete() }

    fun cleanOldFiles(maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        listOf(outgoingDir, sharedDir).forEach { d -> d.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() } }
    }

    /** Copies a stored part to a shareable cache file and returns an intent that opens it in another app. */
    suspend fun openIntent(partId: Long, mime: String, name: String?): Intent? = withContext(Dispatchers.IO) {
        val file = copyToCache(partId, name ?: "attachment") ?: return@withContext null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.mmsfiles", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Saves a stored part into the device's Downloads folder; returns the file name, or null on failure. */
    suspend fun saveToDownloads(partId: Long, mime: String, name: String?): String? = withContext(Dispatchers.IO) {
        runCatching {
            val display = name ?: "attachment-$partId"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, display)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
            val ok = mms.openPart(partId)?.use { input -> resolver.openOutputStream(target)?.use { input.copyTo(it); true } } == true
            if (!ok) { resolver.delete(target, null, null); return@runCatching null }
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            display
        }.getOrNull()
    }

    private fun copyToCache(partId: Long, name: String): File? = runCatching {
        val file = File(sharedDir, "$partId-${name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)}")
        if (!file.exists()) mms.openPart(partId)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        file
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    private fun mimeFromName(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
}
