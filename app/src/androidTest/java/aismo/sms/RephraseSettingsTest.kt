package aismo.sms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.settings.AppSettings
import aismo.sms.domain.llm.LlmBackend

@RunWith(AndroidJUnit4::class)
class RephraseSettingsTest {
    private val c get() = ApplicationProvider.getApplicationContext<AismoApp>().container

    @After fun tearDown() = runBlocking { c.settings.update { it.copy(rephraseEnabled = true, rephraseBackend = LlmBackend.LLAMA_CPP) }; Unit }

    @Test fun theAssistantIsOnAndUsesLlamaCppUntilTheUserChoosesOtherwise() {
        assertTrue(AppSettings().rephraseEnabled)
        assertEquals(LlmBackend.LLAMA_CPP, AppSettings().rephraseBackend)
    }

    @Test fun theChoicesAreStoredAndReadBack() = runBlocking {
        c.settings.update { it.copy(rephraseEnabled = false, rephraseBackend = LlmBackend.LITERT_LM) }
        val s = c.settings.current()
        assertEquals(false, s.rephraseEnabled)
        assertEquals(LlmBackend.LITERT_LM, s.rephraseBackend)
    }

    @Test fun theModelsLiveInPrivateStorageThatIsNotBackedUp() {
        val dir = c.modelStore.root
        assertTrue(dir.path, dir.path.startsWith(ApplicationProvider.getApplicationContext<AismoApp>().noBackupFilesDir.path))
    }
}
