package aismo.sms.domain.rephrase

import aismo.sms.domain.llm.LlmBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmBackendTest {
    @Test fun everyBackendIsFoundAgainByItsStoredKey() {
        for (backend in LlmBackend.entries) assertEquals(backend, LlmBackend.fromKey(backend.key))
    }

    @Test fun anUnknownOrMissingKeyIsNotGuessedAt() {
        assertNull(LlmBackend.fromKey(null))
        assertNull(LlmBackend.fromKey(""))
        assertNull(LlmBackend.fromKey("LLAMA_CPP"))
    }

    @Test fun keysAreUniqueBecauseTheyNameStorageFolders() {
        assertEquals(LlmBackend.entries.size, LlmBackend.entries.map { it.key }.toSet().size)
    }
}
