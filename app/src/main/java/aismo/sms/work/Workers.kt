package aismo.sms.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import aismo.sms.container
import aismo.sms.domain.SpamReviewSchedule
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Workers {
    private const val IMPORT = "import"
    private const val OTP_CLEANUP = "otp-cleanup"
    private const val REMINDERS = "reminders"
    private const val AUTO_BACKUP = "auto-backup"
    private const val MMS_RETRY = "mms-retry"
    private const val EMBEDDING = "embedding"
    private const val SPAM_REVIEW = "spam-review"

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            OTP_CLEANUP, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OtpCleanupWorker>(1, TimeUnit.HOURS).build(),
        )
        wm.enqueueUniquePeriodicWork(
            REMINDERS, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS).build(),
        )
        scheduleEmbedding(context)
        scheduleSpamReview(context)
    }

    /**
     * Once a week, from the coming Sunday at 10:00 (the system may shift it a little to save battery). The job is always
     * scheduled; it reads the setting when it runs, so switching the reminder off or on needs no rescheduling.
     */
    fun scheduleSpamReview(context: Context) {
        val delay = SpamReviewSchedule.delayUntilNext(ZonedDateTime.now())
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SPAM_REVIEW, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SpamReviewWorker>(7, TimeUnit.DAYS).setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build(),
        )
    }

    /**
     * Makes search-by-meaning vectors for messages that lack one. A request that arrives while one is waiting or running
     * is dropped (the running job takes everything pending, and the next trigger covers a late arrival), so bursts of
     * new messages cost one job. [immediately] replaces a waiting job, for when the user turns the feature on.
     */
    fun scheduleEmbedding(context: Context, immediately: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .apply { if (!immediately) setInitialDelay(EMBEDDING_DELAY_SECONDS, TimeUnit.SECONDS) }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(EMBEDDING, if (immediately) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun cancelEmbedding(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(EMBEDDING)
    }

    private const val EMBEDDING_DELAY_SECONDS = 20L

    /**
     * Fails a transfer whose result never arrived (the app was updated or force-stopped mid-transfer, which
     * cancels the platform's callback). One job per message, replaced by each new attempt and cancelled when
     * the real result comes in, so it only fires for a transfer that has truly gone quiet.
     */
    fun scheduleMmsWatchdog(context: Context, kind: String, messageId: Long, delayMinutes: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "mms-watchdog-$kind-$messageId", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MmsWatchdogWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(androidx.work.workDataOf(KEY_KIND to kind, KEY_ID to messageId))
                .build(),
        )
    }

    fun cancelMmsWatchdog(context: Context, kind: String, messageId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("mms-watchdog-$kind-$messageId")
    }

    internal const val KEY_KIND = "kind"
    internal const val KEY_ID = "id"
    const val KIND_DOWNLOAD = "download"
    const val KIND_SEND = "send"

    /** Retries MMS downloads that failed for a transient reason, once a network is available. */
    fun scheduleMmsRetry(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MMS_RETRY, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MmsRetryWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** (Re)schedules automatic backups every [days] days, or cancels them when [days] is 0. */
    fun scheduleAutoBackup(context: Context, days: Int) {
        val wm = WorkManager.getInstance(context)
        if (days <= 0) { wm.cancelUniqueWork(AUTO_BACKUP); return }
        wm.enqueueUniquePeriodicWork(
            AUTO_BACKUP, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(days.toLong(), TimeUnit.DAYS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiresBatteryNotLow(true).build()).build(),
        )
    }

    /** Imports new provider rows and re-parses stale ones; runs once even if requested repeatedly. */
    fun startImport(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMPORT, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ImportWorker>().build(),
        )
    }
}

class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        return runCatching {
            c.repository.syncNew()
            c.repository.migrateMmsKeys()
            c.repository.maintainGrouping()
            c.repository.reparseStale()
            Workers.scheduleEmbedding(applicationContext)
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

/** Deletes one-time-code messages once they are used or old, when the user opted in. */
class OtpCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        if (!s.otpAutoDelete) return Result.success()
        val now = System.currentTimeMillis()
        val stale = c.db.insights().otpsToCleanUp(usedBefore = now, anyBefore = now - s.otpAutoDeleteHours * 3_600_000L)
        if (stale.isNotEmpty()) c.repository.deleteMessages(stale.map { it.messageId })
        return Result.success()
    }
}

/** Notifies about reminders due within the next day, once each. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.settings.current().reminderNotifications) return Result.success()
        val now = System.currentTimeMillis()
        val due = c.db.insights().remindersDueBetween(now - 12 * 3_600_000L, now + 24 * 3_600_000L)
        for (r in due) {
            c.notifications.showReminder(r)
            c.db.insights().setReminderStatus(r.id, "notified")
        }
        return Result.success()
    }
}

/** Re-arms periodic work after a reboot or an app update. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        Workers.schedule(context)
    }
}

/** Writes an encrypted backup to the chosen folder using the passphrase held in the Keystore vault. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        val folder = s.backupFolderUri?.let(android.net.Uri::parse) ?: return Result.success()
        val passphrase = c.vault.get(aismo.sms.backup.VAULT_BACKUP_PASSPHRASE) ?: return Result.success()
        if (!s.autoBackup) return Result.success()
        return runCatching { c.backup.writeToFolder(folder, passphrase.toCharArray(), automatic = true) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

/** Builds the vectors behind search by meaning, newest messages first, and picks up where it stopped if it is interrupted. */
class EmbeddingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        try {
            var before = Long.MAX_VALUE
            while (c.settings.current().smartSearch) {
                before = c.semanticIndex.indexBatch(before).next ?: break
            }
            c.semanticIndex.removeOrphans()
        } catch (e: aismo.sms.domain.ai.EmbedderUnavailableException) {
            return Result.failure() // the reason is shown through SemanticIndex.unavailable
        }
        return Result.success()
    }
}

/** The weekly reminder to review spam; the work itself is in [aismo.sms.data.SpamReviewReminder]. */
class SpamReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.spamReview.remind()
        return Result.success()
    }
}

class MmsRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.mmsEngine.retryPending()
        return Result.success()
    }
}

class MmsWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(Workers.KEY_KIND) ?: return Result.success()
        val id = inputData.getLong(Workers.KEY_ID, -1)
        if (id >= 0) applicationContext.container.mmsEngine.expireStale(kind, id)
        return Result.success()
    }
}
