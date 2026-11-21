package aismo.sms.data.sms

import aismo.sms.data.db.AismoDatabase
import aismo.sms.data.settings.SettingsRepository
import aismo.sms.domain.SimChoice

/**
 * Decides which SIM a message goes out on when nobody picked one: the SIM the conversation last used, so a thread that
 * was on SIM 2 stays on SIM 2, then the app's default, then the system's. See [SimChoice] for the exact order.
 */
class SimSelector(
    private val db: AismoDatabase,
    private val settings: SettingsRepository,
    private val activeSubIds: () -> List<Int>,
    private val systemDefault: () -> Int?,
) {
    suspend fun forConversation(conversationKey: String, picked: Int? = null): Int {
        val preferred = settings.current().defaultSubId
        val active = activeSubIds()
        if (active.size < 2) return preferred // one SIM: nothing to choose, so no need to look at the thread
        return SimChoice.resolve(picked, db.messages().lastSubId(conversationKey), preferred, systemDefault(), active)
    }
}
