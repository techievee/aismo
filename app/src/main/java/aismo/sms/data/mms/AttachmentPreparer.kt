package aismo.sms.data.mms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import aismo.sms.domain.mms.MmsBudget
import aismo.sms.domain.mms.MmsPart
import java.io.ByteArrayOutputStream
import java.io.File

/** A file the user attached, already copied into app storage. */
class OutgoingAttachment(val file: File, val mime: String, val name: String)

class MmsTooLargeException(val limitBytes: Int) : Exception("The attachments are too large for an MMS (limit ${limitBytes / 1024} KB)")

/** Turns user attachments into MMS parts that fit the carrier's size limit, shrinking images as needed. */
object AttachmentPreparer {
    fun prepare(items: List<OutgoingAttachment>, text: String, maxBytes: Int): List<MmsPart> {
        val shrinkable = items.filter { it.mime.startsWith("image/") && it.mime != "image/gif" }
        val fixedBytes = items.filter { it !in shrinkable }.sumOf { it.file.length() }
        val perImage = MmsBudget.perImageBytes(maxBytes, text.toByteArray().size, fixedBytes, shrinkable.size) ?: throw MmsTooLargeException(maxBytes)

        val usedNames = HashSet<String>()
        return items.mapIndexed { i, a ->
            val (mime, bytes) = if (a in shrinkable) compressImage(a, perImage, maxBytes) else a.mime to a.file.readBytes()
            val fileName = uniqueName(safeName(withExtension(a.name, mime)), usedNames)
            MmsPart(mime, fileName, "att_$i", null, bytes)
        }
    }

    /** Keeps JPEG/PNG that already fit; otherwise re-encodes as JPEG, lowering quality then size until it fits. */
    internal fun compressImage(a: OutgoingAttachment, targetBytes: Int, limit: Int): Pair<String, ByteArray> {
        val raw = a.file.readBytes()
        if (raw.size <= targetBytes && a.mime in KEEP_AS_IS) return a.mime to raw

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) throw MmsTooLargeException(limit) // not a decodable image
        val rotation = rotationOf(a.file)

        var maxSide = 1280
        var quality = 85
        repeat(14) {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
            val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: throw MmsTooLargeException(limit)
            val bmp = scaleAndRotate(decoded, maxSide, rotation)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (bmp !== decoded) bmp.recycle()
            decoded.recycle()
            if (out.size() <= targetBytes) return "image/jpeg" to out.toByteArray()
            if (quality > 50) quality -= 10 else maxSide = (maxSide * 0.8).toInt().coerceAtLeast(160)
        }
        throw MmsTooLargeException(limit)
    }

    private fun rotationOf(file: File): Int = runCatching {
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    private fun scaleAndRotate(src: Bitmap, maxSide: Int, rotation: Int): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        if (scale >= 1f && rotation == 0) return src
        val m = Matrix().apply { postScale(scale, scale); if (rotation != 0) postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun withExtension(name: String, mime: String): String {
        if (name.contains('.')) {
            // A re-encoded image is always a JPEG, so its extension must say so.
            return if (mime == "image/jpeg" && !name.substringAfterLast('.').lowercase().let { it == "jpg" || it == "jpeg" }) name.substringBeforeLast('.') + ".jpg" else name
        }
        val ext = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "video/mp4" -> "mp4"; "video/3gpp" -> "3gp"; "audio/mpeg" -> "mp3"; "audio/amr" -> "amr"; "text/x-vcard", "text/vcard" -> "vcf"; else -> "bin" }
        return "$name.$ext"
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "file" }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$base-$n" else "$base-$n.$ext"
            if (used.add(candidate)) return candidate
            n++
        }
    }

    private val KEEP_AS_IS = setOf("image/jpeg", "image/png")
}
