package aismo.sms.data

import aismo.sms.data.db.AismoDatabase
import aismo.sms.data.settings.SettingsRepository
import aismo.sms.telephony.NotificationCenter

/** The weekly nudge to look through spam and delete it. Says nothing when there is no spam. */
class SpamReviewReminder(
    private val db: AismoDatabase,
    private val settings: SettingsRepository,
    private val notifications: NotificationCenter,
) {
    /** Posts the reminder if it is switched on and spam is waiting; returns whether it did. */
    suspend fun remind(): Boolean {
        if (!settings.current().spamReviewReminder) return false
        val totals = db.conversations().spamTotals()
        if (totals.messages == 0) return false
        notifications.showSpamReview(totals.conversations, totals.messages)
        return true
    }
}
