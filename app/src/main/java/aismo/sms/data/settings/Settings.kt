package aismo.sms.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

import aismo.sms.domain.SendDelay
import aismo.sms.domain.llm.LlmBackend

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How a locked category is unlocked. [BOTH] asks for the fingerprint first and offers the PIN. */
enum class UnlockMethod(val key: String, val label: String) {
    PIN("pin", "PIN"),
    FINGERPRINT("fingerprint", "Fingerprint"),
    BOTH("both", "Fingerprint, then PIN");

    companion object {
        fun fromKey(key: String?): UnlockMethod? = entries.firstOrNull { it.key == key }
    }
}

/** The colour theme. [DYNAMIC] takes its colours from the wallpaper and needs Android 12; the others are fixed palettes. */
enum class AppPalette(val key: String, val label: String) {
    DYNAMIC("dynamic", "Material You"),
    OCEAN("ocean", "Ocean"),
    FOREST("forest", "Forest"),
    SUNSET("sunset", "Sunset"),
    ROSE("rose", "Rose"),
    VIOLET("violet", "Violet"),
    GRAPHITE("graphite", "Graphite");

    companion object {
        fun fromKey(key: String?): AppPalette? = entries.firstOrNull { it.key == key }
    }
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: AppPalette = AppPalette.DYNAMIC,
    /** In the dark theme, use a true black background (saves power on OLED screens). */
    val pureBlack: Boolean = false,
    val includeSpamInAll: Boolean = false,
    val otpNotifications: Boolean = true,
    val otpAutoDelete: Boolean = false,
    val otpAutoDeleteHours: Int = 24,
    val otpDeleteAfterCopy: Boolean = false,
    val transactionPreviews: Boolean = true,
    val sendSound: Boolean = true,
    val deliveryNotifications: Boolean = false,
    val reminderNotifications: Boolean = true,
    val budgetAlerts: Boolean = true,
    val protectionAlerts: Boolean = true,
    /** When on, a notification only goes out for senders that are in the contact book. */
    val notifyContactsOnly: Boolean = false,
    val defaultSubId: Int = -1,
    val autoBackup: Boolean = false,
    val autoBackupDays: Int = 1,
    val backupFolderUri: String? = null,
    val lastBackupAt: Long = 0,
    val onboardingDone: Boolean = false,
    val autoDownloadMms: Boolean = true,
    val downloadMmsWhileRoaming: Boolean = false,
    /**
     * Whether SMS and MMS from the same sender share one thread. Off (the default) keeps them in separate
     * threads, one `p:`-keyed and one `m:`-keyed per sender; on, both live under the plain key.
     */
    val groupSmsAndMms: Boolean = false,
    /** Your own number, used to tell you apart from the other people in a group MMS. Blank means auto-detect. */
    val ownNumber: String = "",
    /** Send texts longer than the threshold as one MMS instead of a chain of SMS segments. */
    val longTextToMms: Boolean = true,
    /** Convert when a text needs more than this many segments; 0 means the carrier's default. */
    val longTextSegments: Int = 0,
    /** Include MMS messages and their attachments in backups. */
    val backupIncludeMms: Boolean = false,
    /** Search by meaning, done on this phone with a bundled model. */
    val smartSearch: Boolean = true,
    /** The order of the category bar in the inbox, as the user arranged it; empty means the natural order. */
    val categoryOrder: List<String> = emptyList(),
    /** Categories whose incoming messages never raise a notification. */
    val mutedCategories: Set<String> = emptySet(),
    /** Categories left out of the inbox's category bar. Their messages also no longer appear under All. */
    val hiddenCategories: Set<String> = emptySet(),
    /** Categories whose contents stay hidden until unlocked with a fingerprint or the app's PIN. */
    val securedCategories: Set<String> = emptySet(),
    /** How long a successful unlock keeps secured categories open, in minutes; one of 1, 5, 10, 30 or 60. */
    val unlockWindowMinutes: Int = 5,
    /** Which way a locked category is unlocked: the fingerprint, the PIN, or the fingerprint first. */
    val unlockMethod: UnlockMethod = UnlockMethod.BOTH,
    /** A reminder once a week to review spam and delete it. */
    val spamReviewReminder: Boolean = true,
    /** How long a sent message is held so it can be undone, in seconds; 0 sends at once. One of [SendDelay.OPTIONS]. */
    val sendDelaySeconds: Int = SendDelay.DEFAULT,
    /** The on-device writing assistant: rewrite a draft with a small language model. Needs a model file chosen in Settings. */
    val rephraseEnabled: Boolean = true,
    val rephraseBackend: LlmBackend = LlmBackend.LLAMA_CPP,
    /**
     * How many of the thread's most recent messages the writing assistant sees when rewriting a reply, so the
     * rewrite fits the conversation; 0 passes only the draft. Defaults to the single most recent message, and is
     * capped at 10 to keep a small model's context window safe.
     */
    val rephraseContextMessages: Int = 1,
    /** Auto-translate incoming messages that are not in [translateTargetLang], using the writing assistant's model. */
    val translateEnabled: Boolean = true,
    /** The language to translate into; blank means the phone's own language when the app knows it. */
    val translateTargetLang: String = "",
)

/** All user preferences, persisted with Jetpack DataStore and exposed as one immutable snapshot. */
class SettingsRepository(private val context: Context) {
    private object K {
        val theme = intPreferencesKey("theme")
        val palette = stringPreferencesKey("palette")
        val pureBlack = booleanPreferencesKey("pure_black")
        val hiddenCategories = stringSetPreferencesKey("hidden_categories")
        val securedCategories = stringSetPreferencesKey("secured_categories")
        val unlockWindowMinutes = intPreferencesKey("unlock_window_minutes")
        val unlockMethod = stringPreferencesKey("unlock_method")
        val includeSpam = booleanPreferencesKey("include_spam_in_all")
        val otpNotifications = booleanPreferencesKey("otp_notifications")
        val otpAutoDelete = booleanPreferencesKey("otp_auto_delete")
        val otpAutoDeleteHours = intPreferencesKey("otp_auto_delete_hours")
        val otpDeleteAfterCopy = booleanPreferencesKey("otp_delete_after_copy")
        val transactionPreviews = booleanPreferencesKey("transaction_previews")
        val sendSound = booleanPreferencesKey("send_sound")
        val deliveryNotifications = booleanPreferencesKey("delivery_notifications")
        val reminderNotifications = booleanPreferencesKey("reminder_notifications")
        val budgetAlerts = booleanPreferencesKey("budget_alerts")
        val protectionAlerts = booleanPreferencesKey("protection_alerts")
        val notifyContactsOnly = booleanPreferencesKey("notify_contacts_only")
        val defaultSubId = intPreferencesKey("default_sub_id")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val autoBackupDays = intPreferencesKey("auto_backup_days")
        val backupFolderUri = stringPreferencesKey("backup_folder_uri")
        val lastBackupAt = longPreferencesKey("last_backup_at")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val autoDownloadMms = booleanPreferencesKey("auto_download_mms")
        val downloadMmsWhileRoaming = booleanPreferencesKey("download_mms_roaming")
        val groupSmsAndMms = booleanPreferencesKey("group_sms_and_mms")
        val ownNumber = stringPreferencesKey("own_number")
        val longTextToMms = booleanPreferencesKey("long_text_to_mms")
        val longTextSegments = intPreferencesKey("long_text_segments")
        val backupIncludeMms = booleanPreferencesKey("backup_include_mms")
        val smartSearch = booleanPreferencesKey("smart_search")
        val categoryOrder = stringPreferencesKey("category_order")
        val mutedCategories = stringSetPreferencesKey("muted_categories")
        val spamReviewReminder = booleanPreferencesKey("spam_review_reminder")
        val sendDelaySeconds = intPreferencesKey("send_delay_seconds")
        val rephraseEnabled = booleanPreferencesKey("rephrase_enabled")
        val rephraseBackend = stringPreferencesKey("rephrase_backend")
        val rephraseContextMessages = intPreferencesKey("rephrase_context_messages")
        val translateEnabled = booleanPreferencesKey("translate_enabled")
        val translateTargetLang = stringPreferencesKey("translate_target_lang")
        val importCompleted = booleanPreferencesKey("import_completed")
        val parserVersionApplied = intPreferencesKey("parser_version_applied")
        val mmsKeyVersion = intPreferencesKey("mms_key_version")
        val groupingApplied = booleanPreferencesKey("grouping_applied")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = ThemeMode.entries.getOrElse(p[K.theme] ?: 0) { ThemeMode.SYSTEM },
            palette = AppPalette.fromKey(p[K.palette]) ?: d.palette,
            pureBlack = p[K.pureBlack] ?: d.pureBlack,
            hiddenCategories = p[K.hiddenCategories] ?: d.hiddenCategories,
            securedCategories = p[K.securedCategories] ?: d.securedCategories,
            unlockWindowMinutes = (p[K.unlockWindowMinutes] ?: d.unlockWindowMinutes).coerceIn(1, 60),
            unlockMethod = UnlockMethod.fromKey(p[K.unlockMethod]) ?: d.unlockMethod,
            includeSpamInAll = p[K.includeSpam] ?: d.includeSpamInAll,
            otpNotifications = p[K.otpNotifications] ?: d.otpNotifications,
            otpAutoDelete = p[K.otpAutoDelete] ?: d.otpAutoDelete,
            otpAutoDeleteHours = p[K.otpAutoDeleteHours] ?: d.otpAutoDeleteHours,
            otpDeleteAfterCopy = p[K.otpDeleteAfterCopy] ?: d.otpDeleteAfterCopy,
            transactionPreviews = p[K.transactionPreviews] ?: d.transactionPreviews,
            sendSound = p[K.sendSound] ?: d.sendSound,
            deliveryNotifications = p[K.deliveryNotifications] ?: d.deliveryNotifications,
            reminderNotifications = p[K.reminderNotifications] ?: d.reminderNotifications,
            budgetAlerts = p[K.budgetAlerts] ?: d.budgetAlerts,
            protectionAlerts = p[K.protectionAlerts] ?: d.protectionAlerts,
            notifyContactsOnly = p[K.notifyContactsOnly] ?: d.notifyContactsOnly,
            defaultSubId = p[K.defaultSubId] ?: d.defaultSubId,
            autoBackup = p[K.autoBackup] ?: d.autoBackup,
            autoBackupDays = p[K.autoBackupDays] ?: d.autoBackupDays,
            backupFolderUri = p[K.backupFolderUri],
            lastBackupAt = p[K.lastBackupAt] ?: 0,
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            autoDownloadMms = p[K.autoDownloadMms] ?: d.autoDownloadMms,
            downloadMmsWhileRoaming = p[K.downloadMmsWhileRoaming] ?: d.downloadMmsWhileRoaming,
            groupSmsAndMms = p[K.groupSmsAndMms] ?: d.groupSmsAndMms,
            ownNumber = p[K.ownNumber] ?: d.ownNumber,
            longTextToMms = p[K.longTextToMms] ?: d.longTextToMms,
            longTextSegments = p[K.longTextSegments] ?: d.longTextSegments,
            backupIncludeMms = p[K.backupIncludeMms] ?: d.backupIncludeMms,
            smartSearch = p[K.smartSearch] ?: d.smartSearch,
            categoryOrder = p[K.categoryOrder]?.split(',')?.filter { it.isNotBlank() } ?: d.categoryOrder,
            mutedCategories = p[K.mutedCategories] ?: d.mutedCategories,
            spamReviewReminder = p[K.spamReviewReminder] ?: d.spamReviewReminder,
            sendDelaySeconds = SendDelay.normalise(p[K.sendDelaySeconds] ?: d.sendDelaySeconds),
            rephraseEnabled = p[K.rephraseEnabled] ?: d.rephraseEnabled,
            rephraseBackend = LlmBackend.fromKey(p[K.rephraseBackend]) ?: d.rephraseBackend,
            rephraseContextMessages = (p[K.rephraseContextMessages] ?: d.rephraseContextMessages).coerceIn(0, 10),
            translateEnabled = p[K.translateEnabled] ?: d.translateEnabled,
            translateTargetLang = p[K.translateTargetLang] ?: d.translateTargetLang,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        context.dataStore.edit { p ->
            p[K.theme] = next.theme.ordinal
            p[K.palette] = next.palette.key
            p[K.pureBlack] = next.pureBlack
            p[K.hiddenCategories] = next.hiddenCategories
            p[K.securedCategories] = next.securedCategories
            p[K.unlockWindowMinutes] = next.unlockWindowMinutes.coerceIn(1, 60)
            p[K.unlockMethod] = next.unlockMethod.key
            p[K.includeSpam] = next.includeSpamInAll
            p[K.otpNotifications] = next.otpNotifications
            p[K.otpAutoDelete] = next.otpAutoDelete
            p[K.otpAutoDeleteHours] = next.otpAutoDeleteHours
            p[K.otpDeleteAfterCopy] = next.otpDeleteAfterCopy
            p[K.transactionPreviews] = next.transactionPreviews
            p[K.sendSound] = next.sendSound
            p[K.deliveryNotifications] = next.deliveryNotifications
            p[K.reminderNotifications] = next.reminderNotifications
            p[K.budgetAlerts] = next.budgetAlerts
            p[K.protectionAlerts] = next.protectionAlerts
            p[K.notifyContactsOnly] = next.notifyContactsOnly
            p[K.defaultSubId] = next.defaultSubId
            p[K.autoBackup] = next.autoBackup
            p[K.autoBackupDays] = next.autoBackupDays
            if (next.backupFolderUri != null) p[K.backupFolderUri] = next.backupFolderUri else p.remove(K.backupFolderUri)
            p[K.lastBackupAt] = next.lastBackupAt
            p[K.onboardingDone] = next.onboardingDone
            p[K.autoDownloadMms] = next.autoDownloadMms
            p[K.downloadMmsWhileRoaming] = next.downloadMmsWhileRoaming
            p[K.groupSmsAndMms] = next.groupSmsAndMms
            p[K.ownNumber] = next.ownNumber
            p[K.longTextToMms] = next.longTextToMms
            p[K.longTextSegments] = next.longTextSegments
            p[K.backupIncludeMms] = next.backupIncludeMms
            p[K.smartSearch] = next.smartSearch
            p[K.categoryOrder] = next.categoryOrder.joinToString(",")
            p[K.mutedCategories] = next.mutedCategories
            p[K.spamReviewReminder] = next.spamReviewReminder
            p[K.sendDelaySeconds] = SendDelay.normalise(next.sendDelaySeconds)
            p[K.rephraseEnabled] = next.rephraseEnabled
            p[K.rephraseBackend] = next.rephraseBackend.key
            p[K.rephraseContextMessages] = next.rephraseContextMessages.coerceIn(0, 10)
            p[K.translateEnabled] = next.translateEnabled
            p[K.translateTargetLang] = next.translateTargetLang
        }
    }

    /** Internal bookkeeping that is not a user-facing setting. */
    suspend fun isImportCompleted(): Boolean = context.dataStore.data.first()[K.importCompleted] ?: false
    suspend fun setImportCompleted(done: Boolean) = context.dataStore.edit { it[K.importCompleted] = done }.let { }
    suspend fun parserVersionApplied(): Int = context.dataStore.data.first()[K.parserVersionApplied] ?: 0
    suspend fun setParserVersionApplied(v: Int) = context.dataStore.edit { it[K.parserVersionApplied] = v }.let { }
    suspend fun mmsKeyVersion(): Int = context.dataStore.data.first()[K.mmsKeyVersion] ?: 0
    suspend fun setMmsKeyVersion(v: Int) = context.dataStore.edit { it[K.mmsKeyVersion] = v }.let { }
    /** Whether the stored keys already match the group/separate choice (false until the first migration ran). */
    suspend fun groupingApplied(): Boolean = context.dataStore.data.first()[K.groupingApplied] ?: false
    suspend fun setGroupingApplied(v: Boolean) = context.dataStore.edit { it[K.groupingApplied] = v }.let { }
}
