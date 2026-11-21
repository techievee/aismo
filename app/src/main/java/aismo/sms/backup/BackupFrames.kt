package aismo.sms.backup

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The plaintext of a v2 backup: a sequence of `type(1) | length(4) | payload` frames. A JSON frame holds one
 * [Record]; a BLOB frame holds raw attachment bytes that belong to the MMS record just before it.
 */
object BackupFrames {
    const val JSON = 1
    const val BLOB = 2
    const val MAX_JSON = 16 * 1024 * 1024
    const val MAX_BLOB = 64 * 1024 * 1024
}

class Frame(val type: Int, val payload: ByteArray)

class FrameWriter(private val out: OutputStream) : AutoCloseable {
    fun json(record: Record) = write(BackupFrames.JSON, BackupFormat.encodeRecord(record))

    fun blob(bytes: ByteArray) {
        require(bytes.size <= BackupFrames.MAX_BLOB) { "attachment too large for a backup frame" }
        write(BackupFrames.BLOB, bytes)
    }

    private fun write(type: Int, payload: ByteArray) {
        val n = payload.size
        out.write(byteArrayOf(type.toByte(), (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(payload)
    }

    override fun close() = out.close()
}

class FrameReader(private val input: InputStream) : AutoCloseable {
    /** The next frame, or null at a clean end of the stream. */
    fun next(): Frame? {
        val head = ByteArray(5)
        var got = 0
        while (got < 5) {
            val r = input.read(head, got, 5 - got)
            if (r < 0) { if (got == 0) return null else throw BackupDamagedException("a frame header is cut short") }
            got += r
        }
        val type = head[0].toInt() and 0xFF
        val len = ((head[1].toInt() and 0xFF) shl 24) or ((head[2].toInt() and 0xFF) shl 16) or ((head[3].toInt() and 0xFF) shl 8) or (head[4].toInt() and 0xFF)
        val max = when (type) { BackupFrames.JSON -> BackupFrames.MAX_JSON; BackupFrames.BLOB -> BackupFrames.MAX_BLOB; else -> throw BackupDamagedException("unknown frame type $type") }
        if (len < 0 || len > max) throw BackupDamagedException("implausible frame length")
        val payload = ByteArray(len)
        var read = 0
        while (read < len) {
            val r = input.read(payload, read, len - read)
            if (r < 0) throw BackupDamagedException("a frame is cut short")
            read += r
        }
        return Frame(type, payload)
    }

    override fun close() = input.close()
}
