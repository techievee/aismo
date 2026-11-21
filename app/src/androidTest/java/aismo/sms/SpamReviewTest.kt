package aismo.sms

import android.app.NotificationManager
import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.domain.ConversationKeys
import aismo.sms.telephony.NotificationCenter

/** The weekly "review your spam" reminder: what it counts, when it stays quiet, and what it says. */
@RunWith(AndroidJUnit4::class)
class SpamReviewTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val system get() = app.getSystemService(NotificationManager::class.java)
    private val now get() = System.currentTimeMillis()

    @Before fun setUp() {
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty())
            c.repository.syncRecent()
            c.settings.update { it.copy(spamReviewReminder = true, mutedCategories = emptySet(), smartSearch = false) }
        }
        system.cancelAll()
    }

    @After fun tearDown() = runBlocking { c.settings.update { it.copy(spamReviewReminder = true, mutedCategories = emptySet(), smartSearch = true) }; system.cancelAll() }

    private fun reminder() = system.activeNotifications.firstOrNull { it.id == NotificationCenter.SPAM_REVIEW_ID }

    /** Posting is asynchronous: wait for the reminder to show up. */
    private suspend fun postedReminder(): android.service.notification.StatusBarNotification? {
        repeat(40) { reminder()?.let { return it }; kotlinx.coroutines.delay(100) }
        return null
    }

    /** Proves the reminder was not posted: wait for a later notification, which the system handles after any earlier one. */
    private suspend fun assertNoReminder() {
        c.incoming.handle("+61411000999", "Control message", now, now, -1)
        repeat(40) { if (system.activeNotifications.any { it.id == NotificationCenter.notificationId(ConversationKeys.of("+61411000999")) }) return@repeat; kotlinx.coroutines.delay(100) }
        assertNull(reminder())
    }

    /** Stores a message and files its conversation under Spam, as the user or the parser would. */
    private suspend fun spam(address: String, vararg bodies: String): String {
        bodies.forEachIndexed { i, body -> c.provider.insertIncoming(address, body, now - i * 1000L, now - i * 1000L, -1) }
        c.repository.syncNew()
        val key = c.db.conversations().allKeys().first { c.db.conversations().get(it)?.address == address }
        c.repository.setConversationCategory(key, "spam")
        return key
    }

    @Test fun nothingIsPostedWhenThereIsNoSpam() = runBlocking {
        c.provider.insertIncoming("+61411000001", "Lunch?", now, now, -1)
        c.repository.syncNew()
        assertFalse(c.spamReview.remind())
        assertNoReminder()
    }

    @Test fun itCountsSpamMessagesAndConversations() = runBlocking {
        spam("+61411000100", "You won a prize", "Claim now")
        spam("+61411000101", "Cheap pills")
        assertTrue(c.spamReview.remind())
        val n = postedReminder()!!.notification
        val text = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue("says how many: $text", text.startsWith("3 spam messages in 2 conversations"))
        assertEquals("the weekly channel is used", "spam_review", n.channelId)
    }

    @Test fun oneMessageReadsInTheSingular() = runBlocking {
        spam("+61411000100", "Only one")
        assertTrue(c.spamReview.remind())
        val text = postedReminder()!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith("1 spam message in 1 conversation."))
    }

    @Test fun itStaysQuietWhenSwitchedOff() = runBlocking {
        spam("+61411000100", "Spam")
        c.settings.update { it.copy(spamReviewReminder = false) }
        assertFalse(c.spamReview.remind())
        assertNoReminder()
    }

    @Test fun blockedSendersAreNotCounted() = runBlocking {
        val key = spam("+61411000100", "Spam")
        c.db.conversations().setBlocked(key, true)
        assertFalse("a blocked sender is already out of sight, so there is nothing to review", c.spamReview.remind())
    }

    @Test fun mutingSpamNotificationsDoesNotSilenceTheWeeklyReview() = runBlocking {
        c.settings.update { it.copy(mutedCategories = setOf("spam")) }
        spam("+61411000100", "Spam")
        assertTrue(c.spamReview.remind())
        assertNotNull(postedReminder())
    }

    @Test fun theReminderOpensTheSpamCategory() {
        // The notification's tap target is MainActivity with the category extra; the inbox takes it once.
        runBlocking {
            c.requestedCategory.value = "spam"
            val vm = aismo.sms.ui.InboxViewModel(c)
            kotlinx.coroutines.withTimeout(5_000) { while (vm.selectedCategory.value != "spam") kotlinx.coroutines.delay(50) }
            assertNull("the request was consumed", c.requestedCategory.value)
        }
    }
}
