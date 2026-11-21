package aismo.sms

import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.db.UserRuleEntity

/** End-to-end behaviour of the organizer against the real SMS provider and Room database. */
@RunWith(AndroidJUnit4::class)
class OrganizerFlowsTest {
    private val c get() = ApplicationProvider.getApplicationContext<AismoApp>().container
    private val now get() = System.currentTimeMillis()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<AismoApp>()
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty())
            c.db.config().allRules().forEach { c.db.config().deleteRule(it.id) }
            c.repository.syncRecent()
        }
    }

    private suspend fun receive(address: String, body: String, ageMs: Long = 0) {
        c.provider.insertIncoming(address, body, now - ageMs, now - ageMs, -1)
        c.repository.syncNew()
    }

    @Test fun conversationsAreCategorisedAndFactsAreExtracted() = runBlocking {
        receive("VM-HDFCBK", "Rs 4500.00 debited from a/c XX1234. Info: ZOMATO. Avl bal Rs 10,200.50")
        receive("AX-AMAZON", "482913 is your Amazon verification code. Valid for 10 minutes. Do not share.")
        receive("+61411222333", "see you soon")

        val byKey = c.db.conversations().observeAll().first().associateBy { it.key }
        assertEquals("finance", byKey["s:HDFCBK"]?.category)
        assertEquals("otp", byKey["s:AMAZON"]?.category)
        assertEquals("personal", byKey["p:411222333"]?.category)
        assertEquals("HDFC Bank", byKey["s:HDFCBK"]?.title)

        val txn = c.db.insights().recentTransactions(10).first().single()
        assertEquals(450000L, txn.amountMinor)
        assertEquals("Zomato", txn.merchant)
        assertEquals("482913", c.db.insights().recentOtps(10).first().single().code)
    }

    @Test fun searchFindsByWordPrefixAmountAndSender() = runBlocking {
        receive("VM-HDFCBK", "Rs 4500.00 debited from a/c XX1234. Info: ZOMATO. Avl bal Rs 10,200.50")
        receive("+61411222333", "lunch tomorrow?")
        assertEquals(1, c.repository.search("debit").size)
        assertEquals("prefix search", 1, c.repository.search("zoma").size)
        assertEquals("amount with decimals", 1, c.repository.search("4500.00").size)
        assertEquals("no match", 0, c.repository.search("zzzzqqq").size)
        assertEquals("punctuation-only query is safe", 0, c.repository.search("%%%").size)
        assertEquals("quote characters are safe", 0, c.repository.search("\"'; DROP TABLE messages;--").size)
    }

    @Test fun userRuleOverridesAndSurvivesReparse() = runBlocking {
        receive("VM-HDFCBK", "Rs 10.00 debited from a/c XX1234. Info: CAFE")
        assertEquals("finance", c.db.conversations().get("s:HDFCBK")?.category)
        c.db.config().insertRule(UserRuleEntity(name = "Bank is work", senderContains = "HDFCBK", bodyContains = null, categoryKey = "job", merchant = null))
        c.repository.reparseAll()
        assertEquals("job", c.db.conversations().get("s:HDFCBK")?.category)
    }

    @Test fun userChosenCategoryIsNotOverwrittenByNewMail() = runBlocking {
        receive("VM-SHOPPE", "Mega sale! 50% off. Shop now. T&C apply")
        c.repository.setConversationCategory("s:SHOPPE", "order")
        receive("VM-SHOPPE", "Flat 70% off today, limited time, shop now")
        val conv = c.db.conversations().get("s:SHOPPE")!!
        assertEquals("order", conv.category)
        assertTrue(conv.categoryLocked)
        c.repository.unlockConversationCategory("s:SHOPPE")
        assertEquals("promo", c.db.conversations().get("s:SHOPPE")?.category)
    }

    @Test fun markReadUpdatesTheProviderAndTheUnreadCount() = runBlocking {
        receive("+61411222333", "one"); receive("+61411222333", "two")
        assertEquals(2, c.db.conversations().get("p:411222333")?.unreadCount)
        c.repository.markConversationRead("p:411222333")
        assertEquals(0, c.db.conversations().get("p:411222333")?.unreadCount)
        assertTrue("provider rows are read too", c.provider.readAfter(0, 10).all { it.read })
        c.repository.markConversationUnread("p:411222333")
        assertEquals(1, c.db.conversations().get("p:411222333")?.unreadCount)
    }

    @Test fun deletingAConversationRemovesEverythingDerivedFromIt() = runBlocking {
        receive("VM-HDFCBK", "Rs 99.00 debited from a/c XX1234. Info: TESTSHOP")
        receive("AX-AMAZON", "111222 is your Amazon verification code")
        c.repository.deleteConversation("s:HDFCBK")
        assertNull(c.db.conversations().get("s:HDFCBK"))
        assertEquals(0, c.db.insights().recentTransactions(10).first().size)
        assertEquals("other conversations untouched", 1, c.db.insights().recentOtps(10).first().size)
        assertEquals(1, c.provider.readAfter(0, 10).size)
    }

    @Test fun blockingHidesTheConversationAndUnblockingRestoresIt() = runBlocking {
        receive("+61411999888", "buy now")
        assertTrue(c.blocked.canBlock())
        assertTrue(c.repository.blockSender("p:411999888"))
        assertTrue(c.blocked.isBlocked("+61411999888"))
        assertFalse("blocked sender hidden from the inbox", c.db.conversations().observeAll().first().any { it.key == "p:411999888" })
        assertTrue(c.db.conversations().observeBlocked().first().any { it.key == "p:411999888" })
        assertTrue(c.repository.unblockSender("p:411999888"))
        assertFalse(c.blocked.isBlocked("+61411999888"))
        assertTrue(c.db.conversations().observeAll().first().any { it.key == "p:411999888" })
    }

    @Test fun incomingMessageThroughTheRealPipelineNotifiesAndStores() = runBlocking {
        c.incoming.handle("VM-HDFCBK", "Rs 45.00 debited from a/c XX1234. Info: COFFEE", now, now, -1)
        val conv = c.db.conversations().get("s:HDFCBK")
        assertNotNull(conv)
        assertEquals(1, conv!!.unreadCount)
        assertEquals(1, c.provider.readAfter(0, 10).size)
    }

    @Test fun messagesDeletedByAnotherAppDisappearFromTheIndex() = runBlocking {
        receive("+61411222333", "temporary")
        val id = c.provider.readAfter(0, 10).single().id
        c.provider.delete(listOf(id)) // simulate another app deleting it
        c.repository.syncRecent()
        assertNull(c.db.conversations().get("p:411222333"))
    }
}
