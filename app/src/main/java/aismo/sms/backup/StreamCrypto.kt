package aismo.sms.backup

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chunked AES-256-GCM so a backup of any size is encrypted and decrypted with bounded memory.
 *
 * Layout: `"TSOB2" | salt(16) | nonce-prefix(8)` then chunks of `header(4) | ciphertext`, where the header is the
 * ciphertext length with the top bit set on the final chunk. Each chunk uses nonce `prefix | index(4)` and is
 * authenticated together with its "last" flag, so reordering, dropping the tail, appending chunks or flipping
 * the flag all fail authentication, and nothing from a chunk is released before its tag is checked.
 */
object StreamCrypto {
    val MAGIC = "TSOB2".toByteArray(Charsets.US_ASCII)
    const val CHUNK_SIZE = 64 * 1024
    private const val TAG = 16
    private const val SALT = 16
    private const val PREFIX = 8
    private const val LAST_FLAG = 0x80000000.toInt()
    private val random = SecureRandom()

    fun isStreamFormat(head: ByteArray) = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** Writes the header to [out] and returns a stream that encrypts everything written to it. Close it to finish. */
    fun encrypt(out: OutputStream, passphrase: CharArray): OutputStream {
        val salt = ByteArray(SALT).also(random::nextBytes)
        val prefix = ByteArray(PREFIX).also(random::nextBytes)
        out.write(MAGIC); out.write(salt); out.write(prefix)
        return Encrypting(out, BackupCrypto.key(passphrase, salt), prefix)
    }

    /** Reads the header from [input] and returns a stream of plaintext; authentication failures throw. */
    fun decrypt(input: InputStream, passphrase: CharArray): InputStream {
        val magic = readFully(input, MAGIC.size) ?: throw NotABackupException()
        if (!magic.contentEquals(MAGIC)) throw NotABackupException()
        val salt = readFully(input, SALT) ?: throw NotABackupException()
        val prefix = readFully(input, PREFIX) ?: throw NotABackupException()
        return Decrypting(input, BackupCrypto.key(passphrase, salt), prefix)
    }

    private fun nonce(prefix: ByteArray, index: Int): ByteArray =
        prefix + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())

    private fun aad(last: Boolean) = MAGIC + byteArrayOf(if (last) 1 else 0)

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n); var got = 0
        while (got < n) { val r = input.read(b, got, n - got); if (r < 0) return if (got == 0) null else throw EOFException(); got += r }
        return b
    }

    private class Encrypting(private val out: OutputStream, private val key: SecretKeySpec, private val prefix: ByteArray) : OutputStream() {
        private val buf = ByteArray(CHUNK_SIZE)
        private var n = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

        override fun write(src: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            var o = off; var left = len
            while (left > 0) {
                // A full buffer is sealed only when more data arrives, because until then it might be the last chunk.
                if (n == CHUNK_SIZE) seal(last = false)
                val c = minOf(left, CHUNK_SIZE - n)
                System.arraycopy(src, o, buf, n, c); n += c; o += c; left -= c
            }
        }

        private fun seal(last: Boolean) {
            check(index < Int.MAX_VALUE) { "backup too large" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, index)))
            cipher.updateAAD(aad(last))
            val ct = cipher.doFinal(buf, 0, n)
            val header = ct.size or (if (last) LAST_FLAG else 0)
            out.write(byteArrayOf((header ushr 24).toByte(), (header ushr 16).toByte(), (header ushr 8).toByte(), header.toByte()))
            out.write(ct)
            n = 0; index++
        }

        override fun close() {
            if (closed) return
            closed = true
            try { seal(last = true) } finally { out.close() }
        }
    }

    private class Decrypting(private val input: InputStream, private val key: SecretKeySpec, private val prefix: ByteArray) : InputStream() {
        private var chunk = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var finished = false

        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF }

        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) { if (!next()) return -1 }
            val c = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, dst, off, c); pos += c
            return c
        }

        private fun next(): Boolean {
            if (finished) return false
            val head = try { readFully(input, 4) } catch (e: EOFException) { null }
                ?: throw BackupDamagedException("it ends before its last chunk")
            val header = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            val last = header and LAST_FLAG != 0
            val len = header and LAST_FLAG.inv()
            if (len < TAG || len > CHUNK_SIZE + TAG) throw BackupDamagedException("bad chunk size")
            val ct = try { readFully(input, len) } catch (e: EOFException) { null } ?: throw BackupDamagedException("it ends in the middle of a chunk")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, index)))
            cipher.updateAAD(aad(last))
            chunk = try { cipher.doFinal(ct) } catch (e: GeneralSecurityException) {
                // Only the first chunk can tell a wrong passphrase from damage: the rest share the same key.
                throw if (index == 0) WrongPassphraseException() else BackupDamagedException("a chunk failed authentication")
            }
            pos = 0; index++
            if (last) {
                finished = true
                if (input.read() >= 0) throw BackupDamagedException("data follows the last chunk")
            }
            return true
        }

        override fun close() = input.close()
    }
}
