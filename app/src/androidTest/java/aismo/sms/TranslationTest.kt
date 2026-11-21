package aismo.sms

import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.db.TranslationEntity
import aismo.sms.domain.llm.LlmBackend
import aismo.sms.domain.translate.Language
import aismo.sms.domain.translate.TranslationResult
import kotlinx.coroutines.delay

/**
 * Translation of received messages, through the same pipeline the receiver uses. The model tests assume a model is
 * installed (the same file the writing assistant uses); without one the service must decline, not crash.
 */
@RunWith(AndroidJUnit4::class)
class TranslationTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val now get() = System.currentTimeMillis()

    private val friend = "+61411000042"
    private val friendKey = "p:411000042"
    private val hindi = "कल दोपहर को खाने पर आना"

    private suspend fun modelInstalled(): Boolean = c.modelStore.installed(c.settings.current().rephraseBackend) != null

    @Before fun setUp() {
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty())
            c.repository.syncRecent()
            c.settings.update {
                it.copy(
                    mutedCategories = emptySet(), categoryOrder = emptyList(), smartSearch = false,
                    otpNotifications = true, sendDelaySeconds = 0,
                    translateEnabled = true, translateTargetLang = "en", rephraseBackend = LlmBackend.LLAMA_CPP,
                )
            }
        }
    }

    @After fun tearDown() = runBlocking {
        c.settings.update { it.copy(mutedCategories = emptySet(), categoryOrder = emptyList(), smartSearch = true) }
    }

    /** A translation is stored a few seconds after the message lands: the first one loads the model. */
    private suspend fun waitForTranslation(messageId: Long, target: String, timeoutMillis: Long = 180_000): TranslationEntity? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val row = c.db.translations().observeAll().firstOrNull()?.firstOrNull { it.messageId == messageId && it.target == target }
            if (row != null) return row
            delay(100)
        }
        return null
    }

    @Test fun aHindiMessageIsTranslatedIntoEnglishByTheOnDeviceModel() = runBlocking {
        assumeTrue("needs an installed model", modelInstalled())
        c.incoming.handle(friend, hindi, now, now, -1)
        val messageId = c.db.messages().idsIn(friendKey).single()
        val row = waitForTranslation(messageId, "en")
        assertNotNull("a translation was stored under the message", row)
        assertNotNull("the translation is not empty", row!!.text)
        assertNotEquals("the translation differs from the original", hindi, row.text)
    }

    @Test fun anEnglishMessageIsNotTranslatedIntoEnglish() = runBlocking {
        assumeTrue("needs an installed model", modelInstalled())
        c.incoming.handle(friend, "See you at noon?", now, now, -1)
        val messageId = c.db.messages().idsIn(friendKey).single()
        // The skip is decided before the model runs, so an absent row after a short wait is meaningful.
        delay(15_000)
        val rows = c.db.translations().observeAll().firstOrNull().orEmpty()
        assertFalse("an English message is not translated into English", rows.any { it.messageId == messageId })
    }

    @Test fun withNoModelForTheChosenBackendTheServiceDeclinesWithoutCrashing() = runBlocking {
        // Choose whichever backend has no model installed; when a test phone has both, there is nothing to decline.
        val llama = c.modelStore.installed(LlmBackend.LLAMA_CPP) != null
        val litert = c.modelStore.installed(LlmBackend.LITERT_LM) != null
        val backend = if (llama) (if (litert) null else LlmBackend.LITERT_LM) else (if (litert) null else LlmBackend.LLAMA_CPP)
        assumeTrue("both engines have a model installed", backend != null)
        c.settings.update { it.copy(rephraseBackend = backend!!) }
        assertEquals(TranslationResult.NoModel(backend!!), c.rephrase.translate("कल मिलते हैं", Language.HI, Language.EN))
    }

    @Test fun translatingTheSameMessageIntoTwoLanguagesStoresBoth() = runBlocking {
        assumeTrue("needs an installed model", modelInstalled())
        c.incoming.handle(friend, hindi, now, now, -1)
        val messageId = c.db.messages().idsIn(friendKey).single()
        assertNotNull("english first", waitForTranslation(messageId, "en"))
        val intoFrench = c.rephrase.translate(hindi, Language.HI, Language.FR)
        assertTrue("a french translation was produced", intoFrench is TranslationResult.Success)
        (intoFrench as TranslationResult.Success).let { c.repository.saveTranslation(messageId, Language.HI, Language.FR, it.text) }
        val rows = c.db.translations().observeAll().first().filter { it.messageId == messageId }
        assertEquals("both target languages are stored", 2, rows.size)
        assertTrue(rows.any { it.target == "en" } && rows.any { it.target == "fr" })
    }
}
