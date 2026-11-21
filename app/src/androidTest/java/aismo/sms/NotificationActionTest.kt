package aismo.sms

import android.app.Notification
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
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
import aismo.sms.telephony.Actions
import aismo.sms.telephony.Channels
import aismo.sms.telephony.NotificationCenter

/** The reply and delete actions in the notification drawer, through the real receive pipeline and the system's notification list. */
@RunWith(AndroidJUnit4::class)
class NotificationActionTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val system get() = app.getSystemService(NotificationManager::class.java)
    private val now get() = System.currentTimeMillis()

    private val friend = "+61411000001"
    private val friendKey = "p:411000001"
    private val bank = "VM-HDFCBK"
    private val bankKey = "s:HDFCBK"
    private val uber = "VM-UBER"

    @Before fun setUp() {
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty())
            c.repository.syncRecent()
            c.settings.update {
                it.copy(
                    mutedCategories = emptySet(), categoryOrder = emptyList(), smartSearch = false,
                    otpNotifications = true, protectionAlerts = true, sendDelaySeconds = 0,
                )
            }
        }
        system.cancelAll()
    }

    @After fun tearDown() = runBlocking {
        c.settings.update { it.copy(mutedCategories = emptySet(), categoryOrder = emptyList(), smartSearch = true) }
        system.cancelAll()
    }

    private fun showing(id: Int) = system.activeNotifications.any { it.id == id }

    /** Posting is asynchronous, so a notification that should be there is waited for rather than looked for once. */
    private suspend fun waitUntilShowing(id: Int): Boolean {
        repeat(40) { if (showing(id)) return true; kotlinx.coroutines.delay(100) }
        return false
    }

    /** Dismissing is asynchronous too. */
    private suspend fun waitUntilDismissed(id: Int): Boolean {
        repeat(40) { if (!showing(id)) return true; kotlinx.coroutines.delay(100) }
        return false
    }

    /** Delete removes the row from the provider and the database, not just the notification. */
    private suspend fun waitUntilDeleted(key: String, messageId: Long): Boolean {
        repeat(40) {
            val goneLocally = c.db.messages().idsIn(key).isEmpty()
            val goneFromTheProvider = !c.provider.allIds().orEmpty().contains(messageId)
            if (goneLocally && goneFromTheProvider) return true
            kotlinx.coroutines.delay(100)
        }
        return false
    }

    /** The reply is recorded in the provider as an outgoing message to the sender. */
    private suspend fun waitUntilSent(address: String, body: String): Boolean {
        repeat(40) {
            if (c.provider.readNewest(50).any { it.address == address && it.body == body }) return true
            kotlinx.coroutines.delay(100)
        }
        return false
    }

    /** The drawer shows at most three actions, in the order they were added. */
    @Suppress("DEPRECATION")
    private fun actions(id: Int): List<Notification.Action> =
        system.activeNotifications.firstOrNull { it.id == id }?.notification?.actions?.toList() ?: emptyList()

    private fun titles(id: Int) = actions(id).map { it.title.toString() }

    /** Only the reply action is built mutable — an inline RemoteInput field requires a mutable intent. */
    private fun hasInlineReply(id: Int) =
        actions(id).any { it.title.toString() == app.getString(R.string.reply) && !it.actionIntent.isImmutable() }

    @Test fun aPhoneNumberOfASenderGetsMarkReadReplyAndDelete() = runBlocking {
        c.incoming.handle(friend, "Lunch at noon?", now, now, -1)
        val id = NotificationCenter.notificationId(friendKey)
        assertTrue("the message is in the drawer", waitUntilShowing(id))
        assertEquals(
            listOf(app.getString(R.string.mark_read), app.getString(R.string.reply), app.getString(R.string.delete)),
            titles(id),
        )
        assertTrue("the reply action carries an inline text field", hasInlineReply(id))
    }

    @Test fun aBrandedSenderGetsMarkReadAndDeleteButNoReply() = runBlocking {
        c.incoming.handle(bank, "Rs 45.00 debited from a/c XX1234. Info: COFFEE", now, now, -1)
        val id = NotificationCenter.notificationId(bankKey)
        assertTrue("the bank alert is in the drawer", waitUntilShowing(id))
        assertEquals(
            listOf(app.getString(R.string.mark_read), app.getString(R.string.delete)),
            titles(id),
        )
        assertFalse("no reply for a branded sender", hasInlineReply(id))
    }

    @Test fun deleteFromTheDrawerRemovesTheMessageAndDismissesTheNotification() = runBlocking {
        c.incoming.handle(friend, "Lunch at noon?", now, now, -1)
        val id = NotificationCenter.notificationId(friendKey)
        assertTrue("the message is in the drawer", waitUntilShowing(id))
        val messageId = c.db.messages().idsIn(friendKey).single()
        actions(id).single { it.title.toString() == app.getString(R.string.delete) }.actionIntent.send()

        assertTrue("the message left the provider and the database", waitUntilDeleted(friendKey, messageId))
        assertTrue("the notification was dismissed", waitUntilDismissed(id))
    }

    @Test fun deleteFromTheDrawerWorksForABrandedSenderToo() = runBlocking {
        c.incoming.handle(bank, "Rs 45.00 debited from a/c XX1234. Info: COFFEE", now, now, -1)
        val id = NotificationCenter.notificationId(bankKey)
        assertTrue("the bank alert is in the drawer", waitUntilShowing(id))
        val messageId = c.db.messages().idsIn(bankKey).single()
        actions(id).single { it.title.toString() == app.getString(R.string.delete) }.actionIntent.send()

        assertTrue("the message left the provider and the database", waitUntilDeleted(bankKey, messageId))
        assertTrue("the notification was dismissed", waitUntilDismissed(id))
    }

    @Test fun replyingFromTheDrawerSendsTheTextAndDismissesTheNotification() = runBlocking {
        c.incoming.handle(friend, "Lunch at noon?", now, now, -1)
        val id = NotificationCenter.notificationId(friendKey)
        assertTrue("the message is in the drawer", waitUntilShowing(id))
        val reply = actions(id).single { it.title.toString() == app.getString(R.string.reply) }
        val text = "See you at one."
        val results = Bundle().apply { putCharSequence(Actions.REPLY_KEY, text) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(Actions.REPLY_KEY).build()), fill, results)
        reply.actionIntent.send(app, 0, fill)

        assertTrue("the reply was sent and recorded in the provider", waitUntilSent(friend, text))
        assertTrue("the notification was dismissed", waitUntilDismissed(id))
    }

    @Test fun theOtpHeadsUpStillCarriesCopyAndDelete() = runBlocking {
        c.incoming.handle(uber, "123456 is your Uber verification code. Do not share it.", now, now, -1)
        val messageId = c.db.messages().idsIn(ConversationKeys.of(uber)).single()
        val id = NotificationCenter.otpNotificationId(messageId)
        assertTrue("the code is in the drawer", waitUntilShowing(id))
        assertEquals(Channels.OTP, system.activeNotifications.single { it.id == id }.notification.channelId)
        assertEquals(
            listOf(app.getString(R.string.copy_code), app.getString(R.string.delete)),
            titles(id),
        )
    }
}
