package aismo.sms.data.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import aismo.sms.domain.llm.LlmBackend

private val GB = 1_073_741_824L

class ModelCatalogTest {
    @Test fun aModestPhoneGetsTheSmallestModelItCanRun() {
        assertEquals(ModelCatalog.GRANITE_350M, ModelCatalog.recommended(2 * GB, LlmBackend.LITERT_LM))
        // 3 GB is the floor of the llama.cpp catalogue.
        assertEquals(ModelCatalog.QWEN_0_5B, ModelCatalog.recommended(3 * GB, LlmBackend.LLAMA_CPP))
    }

    @Test fun aMidRangePhoneGetsTheNextSizeUp() {
        assertEquals(ModelCatalog.QWEN_0_5B, ModelCatalog.recommended(4 * GB, LlmBackend.LLAMA_CPP))
        assertEquals(ModelCatalog.QWEN_2_5_3B, ModelCatalog.recommended(6 * GB, LlmBackend.LLAMA_CPP))
        assertEquals(ModelCatalog.GEMMA_4_E2B, ModelCatalog.recommended(4 * GB, LlmBackend.LITERT_LM))
    }

    @Test fun aLargePhoneGetsTheBiggestModelOfItsEngine() {
        assertEquals(ModelCatalog.QWEN_3_4B, ModelCatalog.recommended(8 * GB, LlmBackend.LLAMA_CPP))
        assertEquals(ModelCatalog.QWEN_3_4B, ModelCatalog.recommended(12 * GB, LlmBackend.LLAMA_CPP))
        assertEquals(ModelCatalog.GEMMA_4_E4B, ModelCatalog.recommended(8 * GB, LlmBackend.LITERT_LM))
    }

    @Test fun belowEveryFloorTheLightestModelStillFits() {
        assertEquals(ModelCatalog.QWEN_0_5B, ModelCatalog.recommended(1 * GB, LlmBackend.LLAMA_CPP))
        assertEquals(ModelCatalog.GRANITE_350M, ModelCatalog.recommended(1 * GB, LlmBackend.LITERT_LM))
        assertEquals(ModelCatalog.defaultFor(LlmBackend.LLAMA_CPP), ModelCatalog.recommended(0, LlmBackend.LLAMA_CPP))
    }

    @Test fun theSuggestedEngineFollowsTheMemory() {
        assertEquals(LlmBackend.LITERT_LM, ModelCatalog.bestEngine(2 * GB))
        assertEquals(LlmBackend.LITERT_LM, ModelCatalog.bestEngine(1 * GB))
        assertEquals(LlmBackend.LLAMA_CPP, ModelCatalog.bestEngine(3 * GB))
        assertEquals(LlmBackend.LLAMA_CPP, ModelCatalog.bestEngine(8 * GB))
    }
}
