package aismo.sms

import android.app.NotificationManager
import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.domain.ConversationKeys
import aismo.sms.telephony.NotificationCenter
import aismo.sms.ui.OrganizerViewModel

/** Per-category notification muting, through the real receive pipeline and the system notification list. */
@RunWith(AndroidJUnit4::class)
class CategoryAlertsTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val system get() = app.getSystemService(NotificationManager::class.java)
    private val now get() = System.currentTimeMillis()

    private val bank = "VM-HDFCBK"
    private val bankKey = "s:HDFCBK"
    private val friend = "+61411000001"
    private val friendKey = "p:411000001"

    @Before fun setUp() {
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty())
            c.repository.syncRecent()
            c.settings.update { it.copy(mutedCategories = emptySet(), categoryOrder = emptyList(), smartSearch = false) }
        }
        system.cancelAll()
    }

    @After fun tearDown() = runBlocking { c.settings.update { it.copy(mutedCategories = emptySet(), categoryOrder = emptyList(), smartSearch = true) }; system.cancelAll() }

    private fun showing(key: String) = system.activeNotifications.any { it.id == NotificationCenter.notificationId(key) }

    /** Posting is asynchronous, so a notification that should be there is waited for rather than looked for once. */
    private suspend fun waitUntilShowing(key: String): Boolean {
        repeat(40) { if (showing(key)) return true; kotlinx.coroutines.delay(100) }
        return false
    }

    /**
     * Proves a notification that must not exist really is not coming: the system handles posts in order, so once a
     * notification sent afterwards (from an unmuted category) is showing, an earlier one would be showing too.
     */
    private suspend fun assertNeverShown(key: String) {
        c.settings.update { it.copy(mutedCategories = it.mutedCategories - "personal") }
        c.incoming.handle("+61411000999", "Control message", now, now, -1)
        assertTrue("the control notification arrived", waitUntilShowing(ConversationKeys.of("+61411000999")))
        assertFalse("no notification for $key", showing(key))
    }

    private suspend fun receiveBankAlert() = c.incoming.handle(bank, "Rs 45.00 debited from a/c XX1234. Info: COFFEE", now, now, -1)
    private suspend fun receiveFriend() = c.incoming.handle(friend, "Are we still on for lunch?", now, now, -1)

    @Test fun anUnmutedCategoryNotifiesAsBefore() = runBlocking {
        receiveBankAlert()
        assertEquals("finance", c.db.conversations().get(bankKey)?.category)
        assertTrue("the bank alert is in the drawer", waitUntilShowing(bankKey))
    }

    @Test fun aMutedCategoryNeverNotifiesButTheMessageStillArrives() = runBlocking {
        c.settings.update { it.copy(mutedCategories = setOf("finance")) }
        receiveBankAlert()
        assertNeverShown(bankKey)
        val conversation = c.db.conversations().get(bankKey)!!
        assertEquals("the message is stored and unread", 1, conversation.unreadCount)
        assertEquals(1, c.provider.readAfter(0, 10).count { it.address == bank })
    }

    @Test fun mutingOneCategoryLeavesTheOthersNotifying() = runBlocking {
        c.settings.update { it.copy(mutedCategories = setOf("finance")) }
        receiveBankAlert(); receiveFriend()
        assertTrue("personal still notifies", waitUntilShowing(friendKey))
        assertFalse(showing(bankKey))
    }

    @Test fun aCategoryTheUserChoseForAConversationIsWhatCounts() = runBlocking {
        receiveFriend()
        assertTrue(waitUntilShowing(friendKey))
        c.repository.setConversationCategory(friendKey, "promo")   // the user files this chat under Promotions
        system.cancel(NotificationCenter.notificationId(friendKey))
        c.settings.update { it.copy(mutedCategories = setOf("promo")) }
        c.incoming.handle(friend, "Another message", now, now, -1)
        assertNeverShown(friendKey)
    }

    @Test fun unmutingBringsNotificationsBack() = runBlocking {
        c.settings.update { it.copy(mutedCategories = setOf("finance")) }
        receiveBankAlert()
        assertNeverShown(bankKey)
        c.settings.update { it.copy(mutedCategories = emptySet()) }
        receiveBankAlert()
        assertTrue(waitUntilShowing(bankKey))
    }

    @Test fun mutingACategoryClearsWhatIsAlreadyShowing() = runBlocking {
        receiveBankAlert()
        assertTrue(waitUntilShowing(bankKey))
        OrganizerViewModel(c).setCategoryNotifications("finance", notify = false).join()
        kotlinx.coroutines.delay(500)
        assertFalse("the shown notification was cancelled", showing(bankKey))
        assertTrue("finance is recorded as muted", "finance" in c.settings.current().mutedCategories)
        OrganizerViewModel(c).setCategoryNotifications("finance", notify = true).join()
        assertFalse(("finance" in c.settings.current().mutedCategories))
    }

    @Test fun aMutedCategoryAlsoSilencesItsOneTimeCodes() = runBlocking {
        c.settings.update { it.copy(mutedCategories = setOf("otp")) }
        c.incoming.handle("VM-UBER", "123456 is your Uber verification code. Do not share it.", now, now, -1)
        assertEquals("otp", c.db.conversations().get("s:UBER")?.category)
        // A code has its own notification (on its own channel), so the conversation's id is not the one to look for.
        c.incoming.handle("+61411000999", "Control message", now, now, -1)
        assertTrue("the control notification arrived", waitUntilShowing(ConversationKeys.of("+61411000999")))
        assertFalse("no code notification", system.activeNotifications.any { it.notification.channelId == aismo.sms.telephony.Channels.OTP })
    }
}
