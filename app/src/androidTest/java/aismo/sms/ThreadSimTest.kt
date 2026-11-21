package aismo.sms

import android.app.role.RoleManager
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.sms.SimSelector
import aismo.sms.domain.ConversationKeys

/** A thread keeps using the SIM it was last on. The emulator has one SIM, so the phone's SIM list is supplied here. */
@RunWith(AndroidJUnit4::class)
class ThreadSimTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val now get() = System.currentTimeMillis()
    private val friend = "+61411000001"
    private val key = ConversationKeys.of(friend)

    private fun selector(active: List<Int> = listOf(3, 7), system: Int? = 3) = SimSelector(c.db, c.settings, { active }, { system })

    @Before fun setUp() {
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty())
            c.repository.syncRecent()
            c.settings.update { it.copy(defaultSubId = -1, smartSearch = false) }
        }
    }

    @After fun tearDown() = runBlocking { c.settings.update { it.copy(defaultSubId = -1, smartSearch = true) }; Unit }

    /**
     * The system provider only keeps subscription ids of SIMs that exist (the emulator has just one), so the ids
     * under test are set in the index after the messages have been read in. On a real dual-SIM phone the provider
     * stores the real ids and the index mirrors them.
     */
    private suspend fun store(vararg messages: Triple<String, Long, Int>, outgoing: Set<String> = emptySet()) {
        val ids = messages.map { (body, age, _) ->
            if (body in outgoing) c.provider.insertOutgoing(friend, body, -1, Telephony.Sms.MESSAGE_TYPE_SENT)
            else c.provider.insertIncoming(friend, body, now - age, now - age, -1)
        }
        c.repository.syncNew()
        // Dates are set the same way, so "newest" does not depend on how fast the inserts ran.
        val db = c.db.openHelper.writableDatabase
        messages.forEachIndexed { i, (_, age, sub) -> db.execSQL("UPDATE messages SET subId = ?, date = ? WHERE id = ?", arrayOf<Any>(sub, now - age, ids[i])) }
    }

    @Test fun aThreadThatLastUsedSimSevenGoesOutOnSimSeven() = runBlocking {
        store(Triple("Hello on SIM 3", 5_000L, 3), Triple("Hello on SIM 7", 1_000L, 7))
        assertEquals(7, selector().forConversation(key))
    }

    @Test fun aMessageYouSentCountsToo() = runBlocking {
        store(Triple("Hello on SIM 3", 5_000L, 3), Triple("Replying on SIM 7", 1_000L, 7), outgoing = setOf("Replying on SIM 7"))
        assertEquals(7, selector().forConversation(key))
    }

    @Test fun theNewestMessageDecides() = runBlocking {
        store(Triple("Older, SIM 7", 9_000L, 7), Triple("Newer, SIM 3", 1_000L, 3))
        assertEquals(3, selector(system = 7).forConversation(key))
        assertEquals(3, c.db.messages().observeLastSubId(key).first())
    }

    @Test fun aSimYouPickedBeatsTheThreadHistory() = runBlocking {
        store(Triple("On SIM 7", 1_000L, 7))
        assertEquals(3, selector().forConversation(key, picked = 3))
    }

    @Test fun aNewConversationUsesTheDefaultSim() = runBlocking {
        c.settings.update { it.copy(defaultSubId = 7) }
        assertEquals(7, selector().forConversation(key))
        c.settings.update { it.copy(defaultSubId = -1) }
        assertEquals("falls back to the system's SIM for texts", 3, selector(system = 3).forConversation(key))
    }

    @Test fun aSimThatIsNoLongerInThePhoneIsSkipped() = runBlocking {
        store(Triple("On SIM 9, now removed", 1_000L, 9))
        assertEquals(3, selector(active = listOf(3, 7), system = 3).forConversation(key))
    }

    @Test fun withOneSimTheSettingAloneApplies() = runBlocking {
        store(Triple("On SIM 3", 1_000L, 3))
        assertEquals(-1, selector(active = listOf(3)).forConversation(key))
    }

    @Test fun messagesWithNoRecordedSimAreIgnored() = runBlocking {
        store(Triple("No SIM recorded", 1_000L, -1))
        assertNull(c.db.messages().lastSubId(key))
        assertEquals(3, selector().forConversation(key))
    }
}
