package aismo.sms.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One line of the backup stream. A backup is a gzip of newline-delimited JSON records, encrypted
 * as a whole with AES-256-GCM, so a large message store never has to be one in-memory JSON tree.
 */
@Serializable
sealed interface Record

@Serializable @SerialName("meta")
data class MetaRecord(val format: Int, val createdAt: Long, val app: String, val messageCount: Int, val mmsCount: Int = 0) : Record

@Serializable @SerialName("msg")
data class MessageRecord(
    val address: String, val body: String, val date: Long, val type: Int, val read: Boolean, val subId: Int, val starred: Boolean,
) : Record

/**
 * One MMS. Text parts are inline in [parts]; each binary part (text == null) is followed, in the same order,
 * by a raw BLOB frame with its bytes, so attachments are not inflated by base64.
 */
@Serializable @SerialName("mms")
data class MmsRecord(
    val box: Int, val dateMs: Long, val read: Boolean, val subId: Int, val subject: String?,
    val from: String?, val to: List<String>, val cc: List<String>, val starred: Boolean,
    val parts: List<MmsPartRecord>, val transactionId: String? = null,
) : Record

@Serializable
data class MmsPartRecord(val mime: String, val name: String?, val cid: String?, val charset: Int?, val text: String?, val size: Long)

@Serializable @SerialName("conv")
data class ConversationRecord(val key: String, val category: String, val locked: Boolean, val pinned: Boolean, val muted: Boolean) : Record

@Serializable @SerialName("cat")
data class CategoryRecord(val key: String, val name: String, val icon: String? = null) : Record

@Serializable @SerialName("rule")
data class RuleRecord(
    val name: String, val senderContains: String?, val bodyContains: String?, val categoryKey: String, val merchant: String?, val enabled: Boolean,
) : Record

@Serializable @SerialName("budget")
data class BudgetRecord(val bucket: String, val limitMinor: Long, val currency: String, val alertPercent: Int) : Record

@Serializable @SerialName("settings")
data class SettingsRecord(
    val theme: Int, val includeSpamInAll: Boolean, val otpNotifications: Boolean, val otpAutoDelete: Boolean, val otpAutoDeleteHours: Int,
    val otpDeleteAfterCopy: Boolean, val transactionPreviews: Boolean, val sendSound: Boolean, val deliveryNotifications: Boolean,
    val reminderNotifications: Boolean, val budgetAlerts: Boolean, val protectionAlerts: Boolean,
    // Added later; null in older backups, which then leave the current value alone.
    val categoryOrder: List<String>? = null, val mutedCategories: List<String>? = null, val spamReviewReminder: Boolean? = null,
    val palette: String? = null, val pureBlack: Boolean? = null, val hiddenCategories: List<String>? = null,
    val sendDelaySeconds: Int? = null, val translateEnabled: Boolean? = null, val translateTargetLang: String? = null,
    val securedCategories: List<String>? = null, val unlockWindowMinutes: Int? = null, val unlockMethod: String? = null,
) : Record

object BackupFormat {
    /** 1 = whole-file AES-GCM over NDJSON (still readable); 2 = chunked streaming AES-GCM over length-prefixed frames. */
    const val VERSION = 2
    const val APP_ID = "aismo.sms"
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true }

    fun encodeRecord(r: Record): ByteArray = json.encodeToString(Record.serializer(), r).toByteArray(Charsets.UTF_8)

    /** Null for a record type from a newer version, which is skipped rather than fatal. */
    fun decodeRecord(bytes: ByteArray): Record? =
        runCatching { json.decodeFromString(Record.serializer(), String(bytes, Charsets.UTF_8)) }.getOrNull()

    fun encode(records: Sequence<Record>): ByteArray {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).bufferedWriter(Charsets.UTF_8).use { w ->
            for (r in records) { w.write(json.encodeToString(Record.serializer(), r)); w.write("\n") }
        }
        return buffer.toByteArray()
    }

    /** Decodes records lazily; unknown record types from newer versions are skipped, not fatal. */
    fun decode(gzip: ByteArray): Sequence<Record> = sequence {
        GZIPInputStream(ByteArrayInputStream(gzip)).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val record = runCatching { json.decodeFromString(Record.serializer(), line) }.getOrNull() ?: continue
                yield(record)
            }
        }
    }
}

class WrongPassphraseException : GeneralSecurityException("Wrong passphrase or damaged backup")
class NotABackupException : GeneralSecurityException("Not an aismo backup")
class BackupDamagedException(detail: String) : GeneralSecurityException("The backup is damaged or incomplete ($detail)")

/** AES-256-GCM with a PBKDF2-HMAC-SHA256 key. Layout: magic | salt(16) | iv(12) | ciphertext+tag. */
object BackupCrypto {
    private val MAGIC = "TSOB1".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val IV = 12
    private const val ITERATIONS = 210_000
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    /** Authenticates the whole payload before returning any plaintext. */
    fun decrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        if (data.size < MAGIC.size + SALT + IV + 16 || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackupException()
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + SALT)
        val iv = data.copyOfRange(MAGIC.size + SALT, MAGIC.size + SALT + IV)
        val body = data.copyOfRange(MAGIC.size + SALT + IV, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        return try { cipher.doFinal(body) } catch (e: GeneralSecurityException) { throw WrongPassphraseException() }
    }

    internal fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
