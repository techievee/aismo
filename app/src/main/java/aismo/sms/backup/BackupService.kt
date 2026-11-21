package aismo.sms.backup

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import aismo.sms.data.MessageRepository
import aismo.sms.data.db.BudgetEntity
import aismo.sms.data.db.CustomCategoryEntity
import aismo.sms.data.db.MessageEntity
import aismo.sms.data.db.MessageIds
import aismo.sms.data.db.AismoDatabase
import aismo.sms.data.db.UserRuleEntity
import aismo.sms.data.settings.SettingsRepository
import aismo.sms.data.settings.AppPalette
import aismo.sms.domain.CategoryIconKeys
import aismo.sms.domain.SendDelay
import aismo.sms.data.settings.ThemeMode
import aismo.sms.data.settings.UnlockMethod
import aismo.sms.data.mms.MmsProvider
import aismo.sms.data.mms.OwnNumbers
import aismo.sms.data.sms.SmsProvider
import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.mms.MmsPart
import android.provider.Telephony.Mms
import java.io.OutputStream
import java.security.KeyStore
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RestoreSummary(
    val messagesAdded: Int,
    val messagesSkipped: Int,
    val categories: Int,
    val rules: Int,
    val budgets: Int,
    val settingsRestored: Boolean,
    /** Messages could not be written because this app is not the default SMS app. */
    val messagesNeedDefaultApp: Boolean,
    val mmsAdded: Int = 0,
    val mmsSkipped: Int = 0,
)

/** [skippedParts] counts attachments left out because they were unreadable or too large for one frame. */
data class BackupStats(val messages: Int, val mms: Int, val skippedParts: Int)

data class BackupResult(val name: String, val stats: BackupStats)

class BackupTooNewException : Exception("This backup was made by a newer version of the app")

/** Creates encrypted backups and restores them without duplicating messages that already exist. */
class BackupService(
    private val context: Context,
    private val db: AismoDatabase,
    private val provider: SmsProvider,
    private val mms: MmsProvider,
    private val repository: MessageRepository,
    private val settings: SettingsRepository,
    private val ownNumbers: OwnNumbers,
) {
    // ------------------------------------------------------------------ writing

    /** Roughly how many bytes of attachments an MMS backup would add, for the backup screen. */
    suspend fun estimateMmsBytes(): Long = db.attachments().totalBytes()

    /**
     * Streams a v2 backup to [out]: encrypted in chunks, so memory stays bounded however many attachments there
     * are. MMS (and its attachments) is included only when [includeMms] is set.
     */
    suspend fun write(out: OutputStream, passphrase: CharArray, includeMms: Boolean, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): BackupStats =
        withContext(Dispatchers.IO) {
            val smsCount = db.messages().countSms()
            val mmsCount = if (includeMms) db.messages().countBackupableMms() else 0
            val total = smsCount + mmsCount
            var done = 0
            var skippedParts = 0
            FrameWriter(GZIPOutputStream(StreamCrypto.encrypt(out, passphrase), 64 * 1024)).use { w ->
                w.json(MetaRecord(BackupFormat.VERSION, System.currentTimeMillis(), BackupFormat.APP_ID, smsCount, mmsCount))
                val s = settings.current()
                w.json(
                    SettingsRecord(
                        s.theme.ordinal, s.includeSpamInAll, s.otpNotifications, s.otpAutoDelete, s.otpAutoDeleteHours, s.otpDeleteAfterCopy,
                        s.transactionPreviews, s.sendSound, s.deliveryNotifications, s.reminderNotifications, s.budgetAlerts, s.protectionAlerts,
                        s.categoryOrder, s.mutedCategories.toList(), s.spamReviewReminder,
                        s.palette.key, s.pureBlack, s.hiddenCategories.toList(), s.sendDelaySeconds,
                        s.translateEnabled, s.translateTargetLang,
                        s.securedCategories.toList(), s.unlockWindowMinutes, s.unlockMethod.key,
                        s.groupSmsAndMms,
                    ),
                )
                db.config().categories().forEach { w.json(CategoryRecord(it.key, it.name, it.icon)) }
                db.config().allRules().forEach { w.json(RuleRecord(it.name, it.senderContains, it.bodyContains, it.categoryKey, it.merchant, it.isEnabled)) }
                db.config().budgets().forEach { w.json(BudgetRecord(it.bucket, it.limitMinor, it.currency, it.alertPercent)) }

                var cursor = 0L
                while (true) {
                    val page = db.messages().pageAfter(cursor, 1000).takeWhile { !it.isMms }
                    if (page.isEmpty()) break
                    page.forEach { w.json(MessageRecord(it.address, it.body, it.date, it.type, it.isRead, it.subId, it.isStarred)); done++ }
                    cursor = page.last().id
                    onProgress(done, total)
                }
                if (includeMms) {
                    cursor = MessageIds.MMS_BASE - 1
                    while (true) {
                        val page = db.messages().pageAfter(cursor, 50)
                        if (page.isEmpty()) break
                        val placeholders = db.mmsDownloads().forMessages(page.map { it.id }).map { it.messageId }.toSet()
                        for (m in page) {
                            if (m.id in placeholders) continue
                            skippedParts += writeMms(w, m)
                            done++
                        }
                        cursor = page.last().id
                        onProgress(done, total)
                    }
                }
                for (key in db.conversations().allKeys()) {
                    val c = db.conversations().get(key) ?: continue
                    w.json(ConversationRecord(c.key, c.category, c.categoryLocked, c.isPinned, c.isMuted))
                }
            }
            BackupStats(smsCount, mmsCount, skippedParts)
        }

    /** One MMS: its JSON record, then a raw BLOB frame for each binary part. Returns the number of parts left out. */
    private fun writeMms(w: FrameWriter, m: MessageEntity): Int {
        val raw = MessageIds.raw(m.id)
        val row = mms.readById(raw) ?: return 0
        val records = ArrayList<MmsPartRecord>()
        val blobs = ArrayList<ByteArray>()
        var skipped = 0
        for (p in mms.parts(raw)) {
            if (p.text != null) {
                records += MmsPartRecord(p.mime, p.name, p.contentId, p.charset, p.text, p.sizeBytes)
            } else {
                val bytes = mms.openPart(p.partId)?.use { readCapped(it, BackupFrames.MAX_BLOB) }
                if (bytes == null) { skipped++; continue } // unreadable, or too large for one frame
                records += MmsPartRecord(p.mime, p.name, p.contentId, p.charset, null, bytes.size.toLong())
                blobs += bytes
            }
        }
        w.json(MmsRecord(row.box, row.dateMs, row.read, row.subId, row.subject, row.from, row.to, row.cc, m.isStarred, records, row.transactionId))
        blobs.forEach(w::blob)
        return skipped
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** In-memory convenience used by tests: returns the encrypted bytes and the number of messages they hold. */
    suspend fun build(passphrase: CharArray, includeMms: Boolean = false): Pair<ByteArray, Int> {
        val out = java.io.ByteArrayOutputStream()
        val stats = write(out, passphrase, includeMms)
        return out.toByteArray() to (stats.messages + stats.mms)
    }

    /** Writes a new backup file into the chosen folder and returns its name. */
    suspend fun writeToFolder(
        folder: Uri, passphrase: CharArray, automatic: Boolean, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BackupResult = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, folder) ?: error("Backup folder is not available")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "aismo-$stamp${if (automatic) "-auto" else ""}.aismobackup"
        val file = dir.createFile("application/octet-stream", name) ?: error("Could not create the backup file")
        val stats = try {
            val out = context.contentResolver.openOutputStream(file.uri) ?: error("Could not write the backup file")
            write(out.buffered(), passphrase, settings.current().backupIncludeMms, onProgress) // closes the stream when done
        } catch (e: Throwable) {
            file.delete() // never leave a half-written backup that looks real
            throw e
        }
        if (automatic) prune(dir)
        settings.update { it.copy(lastBackupAt = System.currentTimeMillis()) }
        BackupResult(file.name ?: name, stats)
    }

    private fun prune(dir: DocumentFile, keep: Int = 5) {
        dir.listFiles()
            // old backups used the "tso-" prefix; prune those too, so a folder never keeps both
            .filter { (it.name?.startsWith("aismo-") == true || it.name?.startsWith("tso-") == true) && it.name?.contains("-auto") == true }
            .sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { it.delete() }
    }

    // ----------------------------------------------------------------- restoring

    suspend fun restore(bytes: ByteArray, passphrase: CharArray, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RestoreSummary =
        restore({ java.io.ByteArrayInputStream(bytes) }, passphrase, onProgress)

    /**
     * Restores from a backup opened by [open] (called more than once: a v2 backup is read once to verify every
     * chunk and again to apply it, so a damaged file never leaves a half-applied restore).
     */
    suspend fun restore(open: () -> java.io.InputStream, passphrase: CharArray, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RestoreSummary =
        withContext(Dispatchers.IO) {
            val head = open().use { it.readNBytes(StreamCrypto.MAGIC.size) }
            val applier = Applier(onProgress)
            if (StreamCrypto.isStreamFormat(head)) {
                forEachRecord(open, passphrase) { _, _ -> }          // pass 1: authenticate everything, apply nothing
                forEachRecord(open, passphrase) { r, blobs -> applier.apply(r, blobs) }
            } else {
                // v1: the whole file is one encrypted NDJSON blob
                val records = BackupFormat.decode(BackupCrypto.decrypt(open().use { it.readBytes() }, passphrase)).toList()
                val meta = records.filterIsInstance<MetaRecord>().firstOrNull() ?: throw NotABackupException()
                if (meta.format > BackupFormat.VERSION) throw BackupTooNewException()
                records.forEach { applier.apply(it, emptyList()) }
            }
            applier.finish()
        }

    private suspend fun forEachRecord(open: () -> java.io.InputStream, passphrase: CharArray, action: suspend (Record, List<ByteArray>) -> Unit) {
        FrameReader(GZIPInputStream(StreamCrypto.decrypt(open().buffered(), passphrase))).use { reader ->
            var first = true
            while (true) {
                val frame = reader.next() ?: break
                if (frame.type != BackupFrames.JSON) throw BackupDamagedException("an attachment without its message")
                val record = BackupFormat.decodeRecord(frame.payload)
                if (first) {
                    val meta = record as? MetaRecord ?: throw NotABackupException()
                    if (meta.format > BackupFormat.VERSION) throw BackupTooNewException()
                    first = false
                }
                if (record == null) continue // a record type from a newer version
                val blobs = if (record is MmsRecord) {
                    List(record.parts.count { it.text == null }) {
                        val b = reader.next() ?: throw BackupDamagedException("an attachment is missing")
                        if (b.type != BackupFrames.BLOB) throw BackupDamagedException("an attachment is missing")
                        b.payload
                    }
                } else emptyList()
                action(record, blobs)
            }
            if (first) throw NotABackupException()
        }
    }

    /** Applies records one at a time, merging into what is already on the phone and counting what happened. */
    private inner class Applier(private val onProgress: (Int, Int) -> Unit) {
        private val canWrite = isDefaultSmsApp()
        private var synced = false
        private var total = 0
        private var done = 0
        private var smsAdded = 0
        private var smsSkipped = 0
        private var mmsAdded = 0
        private var mmsSkipped = 0
        private var categories = 0
        private var rulesAdded = 0
        private var budgets = 0
        private var settingsRestored = false
        private var needDefaultApp = false
        private val conversations = ArrayList<ConversationRecord>()
        private val starredSms = ArrayList<MessageRecord>()
        private val starredMms = ArrayList<Long>()
        private var ownKeys: Set<String>? = null
        private var existingRules: List<UserRuleEntity>? = null

        suspend fun apply(r: Record, blobs: List<ByteArray>) {
            when (r) {
                is MetaRecord -> total = r.messageCount + r.mmsCount
                is CategoryRecord -> { db.config().upsertCategory(CustomCategoryEntity(r.key, r.name, r.icon ?: CategoryIconKeys.DEFAULT)); categories++ }
                is RuleRecord -> {
                    val rules = existingRules ?: db.config().allRules().also { existingRules = it }
                    val dup = rules.any { it.senderContains == r.senderContains && it.bodyContains == r.bodyContains && it.categoryKey == r.categoryKey }
                    if (!dup) {
                        db.config().insertRule(UserRuleEntity(name = r.name, senderContains = r.senderContains, bodyContains = r.bodyContains, categoryKey = r.categoryKey, merchant = r.merchant, isEnabled = r.enabled))
                        rulesAdded++
                    }
                }
                is BudgetRecord -> { db.config().upsertBudget(BudgetEntity(r.bucket, r.limitMinor, r.currency, r.alertPercent)); budgets++ }
                is SettingsRecord -> {
                    settings.update {
                        it.copy(
                            theme = ThemeMode.entries.getOrElse(r.theme) { ThemeMode.SYSTEM }, includeSpamInAll = r.includeSpamInAll,
                            otpNotifications = r.otpNotifications, otpAutoDelete = r.otpAutoDelete, otpAutoDeleteHours = r.otpAutoDeleteHours,
                            otpDeleteAfterCopy = r.otpDeleteAfterCopy, transactionPreviews = r.transactionPreviews, sendSound = r.sendSound,
                            deliveryNotifications = r.deliveryNotifications, reminderNotifications = r.reminderNotifications,
                            budgetAlerts = r.budgetAlerts, protectionAlerts = r.protectionAlerts,
                            categoryOrder = r.categoryOrder ?: it.categoryOrder, mutedCategories = r.mutedCategories?.toSet() ?: it.mutedCategories,
                            spamReviewReminder = r.spamReviewReminder ?: it.spamReviewReminder,
                            palette = AppPalette.fromKey(r.palette) ?: it.palette, pureBlack = r.pureBlack ?: it.pureBlack,
                            hiddenCategories = r.hiddenCategories?.toSet() ?: it.hiddenCategories,
                            sendDelaySeconds = r.sendDelaySeconds?.let(SendDelay::normalise) ?: it.sendDelaySeconds,
                            translateEnabled = r.translateEnabled ?: it.translateEnabled,
                            translateTargetLang = r.translateTargetLang ?: it.translateTargetLang,
                            securedCategories = r.securedCategories?.toSet() ?: it.securedCategories,
                            unlockWindowMinutes = r.unlockWindowMinutes ?: it.unlockWindowMinutes,
                            unlockMethod = UnlockMethod.fromKey(r.unlockMethod) ?: it.unlockMethod,
                            groupSmsAndMms = r.groupSmsAndMms ?: it.groupSmsAndMms,
                        )
                    }
                    settingsRestored = true
                }
                is ConversationRecord -> conversations += r
                is MessageRecord -> message(r)
                is MmsRecord -> mms(r, blobs)
            }
        }

        private suspend fun ensureSynced() {
            if (!synced) { repository.syncNew(); synced = true } // make the index current so duplicate detection is reliable
        }

        private suspend fun message(m: MessageRecord) {
            if (!canWrite) { needDefaultApp = true; return }
            ensureSynced()
            if (db.messages().findMatching(m.address, m.date, m.body) != null) smsSkipped++
            else if (provider.insertRestored(m.address, m.body, m.date, m.type, m.read, m.subId) >= 0) smsAdded++
            if (m.starred) starredSms += m
            tick()
        }

        private suspend fun mms(r: MmsRecord, blobs: List<ByteArray>) {
            if (!canWrite) { needDefaultApp = true; return }
            if (r.box == Mms.MESSAGE_BOX_DRAFTS) return
            ensureSynced()
            val existing = findExistingMms(r)
            if (existing != null) {
                mmsSkipped++
                if (r.starred) starredMms += existing
            } else {
                var next = 0
                val parts = r.parts.map { p ->
                    val data = if (p.text != null) p.text.toByteArray(Charsets.UTF_8) else blobs[next++]
                    MmsPart(p.mime, p.name, p.cid, p.charset, data)
                }
                val own = ownKeys ?: ownNumbers.keys(settings.current().ownNumber).also { ownKeys = it }
                val others = (listOfNotNull(r.from) + r.to + r.cc).filter { ConversationKeys.of(it) !in own }.toSet()
                val raw = mms.insertRestored(r.box, r.dateMs, r.read, r.subId, r.subject, r.from, r.to, r.cc, r.transactionId, parts, others)
                if (raw >= 0) { mmsAdded++; if (r.starred) starredMms += MessageIds.mms(raw) }
            }
            tick()
        }

        /** An MMS already on the phone has the same second, the same people and the same parts. */
        private suspend fun findExistingMms(r: MmsRecord): Long? {
            val candidates = db.messages().mmsIdsBetween(r.dateMs, r.dateMs + 999)
            if (candidates.isEmpty()) return null
            val parties = (listOfNotNull(r.from) + r.to + r.cc).map(ConversationKeys::of).toSet()
            val shape = r.parts.map { Triple(it.mime, it.name, it.size) }.sortedBy { it.toString() }
            for (id in candidates) {
                val raw = MessageIds.raw(id)
                val row = mms.readById(raw) ?: continue
                if ((listOfNotNull(row.from) + row.to + row.cc).map(ConversationKeys::of).toSet() != parties) continue
                val stored = mms.parts(raw).map { Triple(it.mime, it.name, it.sizeBytes) }.sortedBy { it.toString() }
                if (stored == shape) return id
            }
            return null
        }

        private fun tick() {
            done++
            if (done % 25 == 0) onProgress(done, total)
        }

        suspend fun finish(): RestoreSummary {
            if (canWrite) {
                repository.syncNew()
                // Make the stored keys match the restored "keep together or separate" choice, so the
                // user's per-thread choices below can find their conversations.
                repository.maintainGrouping()
                for (m in starredSms) db.messages().findMatching(m.address, m.date, m.body)?.let { db.messages().setStarred(it, true) }
                for (id in starredMms) if (db.messages().get(id) != null) db.messages().setStarred(id, true)
                val present = db.conversations().allKeys().toSet()
                // A phone that kept text and pictures together stored no `m:` conversations at all, so each of
                // its records is a choice made on the merged thread. If this phone keeps them separate — older
                // backups did not carry the choice — the same choices belong to both halves of the thread
                // instead of being silently dropped.
                val groupedBackup = conversations.none { ConversationKeys.isMmsKey(it.key) }
                for (c in conversations) {
                    val targets = LinkedHashSet<String>()
                    if (c.key in present) targets += c.key
                    // The same conversation can wear the other key shape after a restore: the SMS/MMS shape
                    // follows the "keep together" choice, which the backup did not always carry.
                    if (groupedBackup) targets += ConversationKeys.siblingForms(c.key).filter { it in present }
                    for (t in targets) {
                        if (c.locked) repository.setConversationCategory(t, c.category)
                        repository.setPinned(t, c.pinned)
                        repository.setMuted(t, c.muted)
                    }
                }
            }
            onProgress(total, total)
            return RestoreSummary(smsAdded, smsSkipped, categories, rulesAdded, budgets, settingsRestored, needDefaultApp, mmsAdded, mmsSkipped)
        }
    }

    private fun isDefaultSmsApp() =
        context.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
}

/** Stores secrets (the backup passphrase for automatic backups) encrypted with a non-exportable Keystore key. */
class KeystoreVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? = runCatching {
        val raw = Base64.decode(prefs.getString(name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }.getOrNull()

    fun has(name: String) = prefs.contains(name)
    fun remove(name: String) = prefs.edit().remove(name).apply()

    private companion object { const val ALIAS = "aismo_vault_key" }
}

const val VAULT_BACKUP_PASSPHRASE = "backup_passphrase"
const val VAULT_PIN = "category_pin"
