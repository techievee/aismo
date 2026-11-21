package aismo.sms.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import aismo.sms.container
import aismo.sms.data.db.AttachmentEntity
import aismo.sms.data.mms.OutgoingAttachment
import java.io.File

const val MAX_ATTACHMENTS = 10

/** Decoded thumbnails, capped at roughly 24 MB so scrolling a media-heavy thread cannot exhaust memory. */
private object Thumbnails {
    // LruCache is not synchronized and this cache is fed by several IO coroutines at once.
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = synchronized(cache) { cache.get(key) }
    fun put(key: String, bmp: Bitmap) { synchronized(cache) { cache.put(key, bmp) } }
}

private fun sampleSize(w: Int, h: Int, target: Int): Int {
    var s = 1
    while (maxOf(w, h) / (s * 2) >= target) s *= 2
    return s
}

private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}

private fun degreesOf(orientation: Int) = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90
    ExifInterface.ORIENTATION_ROTATE_180 -> 180
    ExifInterface.ORIENTATION_ROTATE_270 -> 270
    else -> 0
}

/** Decodes an image from [open], downsampled to about [targetPx] and rotated per its EXIF tag. */
private fun decodeImage(open: () -> java.io.InputStream?, targetPx: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return@runCatching null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx) }
    val bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
    val orientation = open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
    rotate(bmp, degreesOf(orientation))
}.getOrNull()

private fun videoFrame(context: Context, uri: Uri): Bitmap? = runCatching {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        r.getFrameAtTime(0)
    }
}.getOrNull()

@Composable
private fun rememberStoredThumbnail(partId: Long, mime: String, targetPx: Int): State<Bitmap?> {
    val context = LocalContext.current
    val c = context.container
    return produceState<Bitmap?>(Thumbnails.get("part:$partId"), partId) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val bmp = if (mime.startsWith("video/")) videoFrame(context, c.mmsProvider.partUri(partId))
            else decodeImage({ c.mmsProvider.openPart(partId) }, targetPx)
            bmp?.also { Thumbnails.put("part:$partId", it) }
        }
    }
}

@Composable
private fun rememberFileThumbnail(file: File, mime: String, targetPx: Int): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(Thumbnails.get("file:${file.path}"), file.path) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val bmp = if (mime.startsWith("video/")) videoFrame(context, Uri.fromFile(file)) else decodeImage({ file.inputStream() }, targetPx)
            bmp?.also { Thumbnails.put("file:${file.path}", it) }
        }
    }
}

fun iconFor(kind: String) = when (kind) {
    "audio" -> Icons.Filled.AudioFile
    "contact" -> Icons.Filled.ContactPage
    else -> Icons.Filled.InsertDriveFile
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/** The attachments of one received or sent MMS, shown inside its bubble. */
@Composable
fun AttachmentContent(attachments: List<AttachmentEntity>, onOpen: (AttachmentEntity) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in attachments) {
            when (a.kind) {
                "image", "video" -> MediaThumbnail(a, onOpen)
                else -> AssistChip(
                    onClick = { onOpen(a) },
                    leadingIcon = { Icon(iconFor(a.kind), null) },
                    label = { Text(listOfNotNull(a.name, formatSize(a.sizeBytes).ifEmpty { null }).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
private fun MediaThumbnail(a: AttachmentEntity, onOpen: (AttachmentEntity) -> Unit) {
    val bmp by rememberStoredThumbnail(a.partId, a.mime, targetPx = 720)
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen(a) }.heightIn(min = 72.dp, max = 260.dp),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = a.name ?: a.kind, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(iconFor(a.kind), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (a.kind == "video") {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f)) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.padding(10.dp).size(28.dp))
            }
        }
    }
}

/** The chosen-but-not-yet-sent attachments shown above the composer. */
@Composable
fun AttachmentStrip(items: List<OutgoingAttachment>, onRemove: (OutgoingAttachment) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.file.path }) { a ->
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))) {
                if (a.mime.startsWith("image/") || a.mime.startsWith("video/")) {
                    val bmp by rememberFileThumbnail(a.file, a.mime, targetPx = 200)
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().size(72.dp))
                    else Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(72.dp)) {}
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(72.dp)) {
                        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(iconFor(if (a.mime.startsWith("audio/")) "audio" else if (a.mime.contains("vcard")) "contact" else "file"), null)
                            Text(a.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = { onRemove(a) }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f)) { Icon(Icons.Filled.Close, "Remove ${a.name}", tint = Color.White, modifier = Modifier.padding(3.dp)) }
                }
            }
        }
    }
}

class AttachmentPicker(val pickMedia: () -> Unit, val pickFile: () -> Unit)

@Composable
fun rememberAttachmentPicker(onPicked: (List<Uri>) -> Unit): AttachmentPicker {
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { result ->
        onPicked(result)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { result ->
        onPicked(result)
    }
    return remember(media, files) {
        AttachmentPicker(
            pickMedia = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            pickFile = { files.launch(arrayOf("*/*")) },
        )
    }
}

/** The paperclip button with its "photo or video / file" menu. */
@Composable
fun AttachButton(picker: AttachmentPicker, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Filled.AttachFile, "Attach") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Photo or video") }, onClick = { open = false; picker.pickMedia() })
            DropdownMenuItem(text = { Text("File") }, onClick = { open = false; picker.pickFile() })
        }
    }
}

/** Opens a stored attachment in another app, copying it out first because the provider's URIs are private. */
@Composable
fun rememberAttachmentOpener(): (AttachmentEntity) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = context.container.attachmentStore
    return { a ->
        scope.launch {
            val intent = store.openIntent(a.partId, a.mime, a.name)
            val ok = intent != null && runCatching { context.startActivity(intent) }.isSuccess
            if (!ok) Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun rememberAttachmentSaver(): (AttachmentEntity) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = context.container.attachmentStore
    return { a ->
        scope.launch {
            val saved = store.saveToDownloads(a.partId, a.mime, a.name)
            Toast.makeText(context, if (saved != null) "Saved $saved to Downloads" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }
}
