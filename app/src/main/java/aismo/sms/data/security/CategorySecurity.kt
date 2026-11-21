package aismo.sms.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import aismo.sms.backup.KeystoreVault
import aismo.sms.backup.VAULT_PIN
import aismo.sms.data.settings.AppSettings
import aismo.sms.data.settings.UnlockMethod

/** Where the app's PIN verifier lives. The sealed record (salt + derived key, never the PIN itself) is in the vault. */
interface PinStore {
    fun has(): Boolean
    fun record(): String?
    fun store(record: String)
    fun clear()
}

class VaultPinStore(private val vault: KeystoreVault) : PinStore {
    override fun has() = vault.has(VAULT_PIN)
    override fun record() = vault.get(VAULT_PIN)
    override fun store(record: String) = vault.put(VAULT_PIN, record)
    override fun clear() = vault.remove(VAULT_PIN)
}

/**
 * The PIN verifier, derived the same way a backup derives its key: PBKDF2, a fresh random salt per PIN. The record
 * never contains the PIN, so nothing readable sits in app storage.
 */
object PinCrypto {
    private const val FORMAT = "1"
    private const val KEY_LENGTH = 256

    const val ITERATIONS = 210_000

    fun isAcceptable(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }

    fun record(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "$FORMAT:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(derive(pin, salt))}"
    }

    /** False for a wrong PIN and for anything that is not a well-formed record. */
    fun check(pin: String, record: String): Boolean {
        val parts = record.split(":")
        if (parts.size != 3 || parts[0] != FORMAT) return false
        return try {
            val salt = Base64.getDecoder().decode(parts[1])
            val expected = Base64.getDecoder().decode(parts[2])
            MessageDigest.isEqual(expected, derive(pin, salt))
        } catch (e: Exception) {
            false
        }
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

/**
 * Owns whether the secured categories are unlocked. The unlock is in memory only, so a restart of the app (or of the
 * process) locks them again; nothing about it is persisted. [unlock] keeps them open for the caller's window, a second
 * unlock restarts it, and [recheck] re-locks an expired window when the app comes back to the foreground.
 */
class CategorySecurity(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val pin: PinStore,
    private val biometricEnrolled: () -> Boolean,
) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    /**
     * Whether the vault holds the PIN's verifier. Setting or clearing a PIN does not touch the settings store, so the
     * UI keys its derived state on this instead of re-reading the vault itself.
     */
    private val _pinPresent = MutableStateFlow(pin.has())
    val pinPresent: StateFlow<Boolean> = _pinPresent

    private var unlockedUntil = 0L
    private var relockJob: Job? = null

    private var failures = 0
    private var lockedOutUntil = 0L

    /** The methods the user chose that this phone can actually offer right now. */
    fun availableMethods(s: AppSettings): Set<UnlockMethod> {
        val wantsPin = s.unlockMethod != UnlockMethod.FINGERPRINT
        val wantsFingerprint = s.unlockMethod != UnlockMethod.PIN
        return buildSet {
            if (wantsPin && pin.has()) add(UnlockMethod.PIN)
            if (wantsFingerprint && biometricEnrolled()) add(UnlockMethod.FINGERPRINT)
        }
    }

    /**
     * Whether [key] is secured and actually locked. Secured with no way to unlock is not locked: a phone that cannot
     * re-lock its categories (for example after a restore) must not be able to hide them from their owner.
     */
    fun isLocked(s: AppSettings, key: String): Boolean =
        key in s.securedCategories && !unlocked.value && availableMethods(s).isNotEmpty()

    fun setPin(pin: String) {
        if (!PinCrypto.isAcceptable(pin)) return
        this.pin.store(PinCrypto.record(pin))
        _pinPresent.value = true
        failures = 0
        lockedOutUntil = 0L
    }

    fun changePin(current: String, next: String): Boolean {
        if (!verifyPin(current) || !PinCrypto.isAcceptable(next)) return false
        setPin(next)
        return true
    }

    fun clearPin() {
        pin.clear()
        _pinPresent.value = false
        lock()
    }

    fun verifyPin(candidate: String): Boolean {
        if (now() < lockedOutUntil) return false
        val record = pin.record() ?: return false
        return if (PinCrypto.check(candidate, record)) {
            failures = 0
            true
        } else {
            failures++
            if (failures >= MAX_FAILURES) lockedOutUntil = now() + PIN_LOCKOUT_MS
            false
        }
    }

    fun unlock(windowMs: Long) {
        relockJob?.cancel()
        val until = now() + windowMs
        unlockedUntil = until
        _unlocked.value = true
        relockJob = scope.launch {
            delay(windowMs)
            if (unlockedUntil == until) lock()
        }
    }

    fun lock() {
        relockJob?.cancel()
        unlockedUntil = 0L
        _unlocked.value = false
    }

    fun recheck() {
        if (_unlocked.value && now() >= unlockedUntil) lock()
    }

    companion object {
        const val MAX_FAILURES = 5
        const val PIN_LOCKOUT_MS = 5 * 60_000L
    }
}
