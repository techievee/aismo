package aismo.sms.data.llm

import aismo.sms.domain.llm.LlmBackend

/**
 * A model file the app knows how to use. The app has no network access, so the file is downloaded in the user's browser
 * from [downloadUrl] and chosen in Settings; its SHA-256 must match [sha256] before it is accepted.
 * [ramNote] tells the user what running it costs, and [minRamGb] is the same number made machine-readable,
 * so the app can pick the biggest model that a phone's memory can run.
 */
data class SupportedModel(
    val backend: LlmBackend,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val licence: String,
    val downloadUrl: String,
    val pageUrl: String,
    val ramNote: String,
    val minRamGb: Int = 0,
) {
    val sizeLabel: String get() = "%.0f MB".format(sizeBytes / 1_048_576.0)
}

object ModelCatalog {
    val QWEN_0_5B = SupportedModel(
        backend = LlmBackend.LLAMA_CPP,
        title = "Qwen2.5 0.5B Instruct",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        sizeBytes = 491_400_032L,
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        licence = "Apache-2.0",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        pageUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/9217f5db79a29953eb74d5343926648285ec7e67",
        ramNote = "about 1.5 GB of RAM; runs on almost any phone",
        minRamGb = 3,
    )

    val QWEN_2_5_3B = SupportedModel(
        backend = LlmBackend.LLAMA_CPP,
        title = "Qwen2.5 3B Instruct",
        fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
        sizeBytes = 2_104_932_768L,
        sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
        licence = "Apache-2.0",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/7dabda4d13d513e3e842b20f0d435c732f172cbe/qwen2.5-3b-instruct-q4_k_m.gguf",
        pageUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/tree/7dabda4d13d513e3e842b20f0d435c732f172cbe",
        ramNote = "about 3 GB of RAM; comfortable on phones with 6 GB or more",
        minRamGb = 6,
    )

    val QWEN_3_4B = SupportedModel(
        backend = LlmBackend.LLAMA_CPP,
        title = "Qwen3 4B",
        fileName = "Qwen3-4B-Q4_K_M.gguf",
        sizeBytes = 2_497_280_256L,
        sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
        licence = "Apache-2.0",
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/bc640142c66e1fdd12af0bd68f40445458f3869b/Qwen3-4B-Q4_K_M.gguf",
        pageUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/tree/bc640142c66e1fdd12af0bd68f40445458f3869b",
        ramNote = "about 3.5 GB of RAM; best on phones with 8 GB or more",
        minRamGb = 8,
    )

    val GRANITE_350M = SupportedModel(
        backend = LlmBackend.LITERT_LM,
        title = "Granite 4.0 350M",
        fileName = "granite-4.0-350m_q8_ekv1280.litertlm",
        sizeBytes = 468_209_584L,
        sha256 = "c8e9a29493f62b7c44461fb36980987c4c1454c75e95f57ba0539a8edc9dce76",
        licence = "Apache-2.0",
        downloadUrl = "https://huggingface.co/litert-community/granite-4.0-350m-litert-lm/resolve/6f1e9bce89b174930a79de82d0dcdede708f8c34/granite-4.0-350m_q8_ekv1280.litertlm",
        pageUrl = "https://huggingface.co/litert-community/granite-4.0-350m-litert-lm/tree/6f1e9bce89b174930a79de82d0dcdede708f8c34",
        ramNote = "about 1 GB of RAM; the lightest option",
        minRamGb = 2,
    )

    val GEMMA_4_E2B = SupportedModel(
        backend = LlmBackend.LITERT_LM,
        title = "Gemma 4 E2B (effective 2B)",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        licence = "Gemma Terms of Service",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/2e12bfd869b2962984f97a1f2a26807316d534cc/gemma-4-E2B-it.litertlm",
        pageUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/2e12bfd869b2962984f97a1f2a26807316d534cc",
        ramNote = "about 2 GB of RAM; runs on phones with 4 GB or more",
        minRamGb = 4,
    )

    val GEMMA_4_E4B = SupportedModel(
        backend = LlmBackend.LITERT_LM,
        title = "Gemma 4 E4B (effective 4B)",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        licence = "Gemma Terms of Service",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0/gemma-4-E4B-it.litertlm",
        pageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/tree/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0",
        ramNote = "about 3.5 GB of RAM; best on phones with 8 GB or more",
        minRamGb = 8,
    )

    val all: List<SupportedModel> = listOf(QWEN_0_5B, QWEN_2_5_3B, QWEN_3_4B, GRANITE_350M, GEMMA_4_E2B, GEMMA_4_E4B)

    /** The models [backend] can run, lightest first, so the user sees what each costs before downloading. */
    fun modelsFor(backend: LlmBackend): List<SupportedModel> = all.filter { it.backend == backend }

    /** The model recommended for [backend]: the lightest one, so a modest phone always has a working option. */
    fun defaultFor(backend: LlmBackend): SupportedModel = modelsFor(backend).first()

    /**
     * The best [backend] model a phone with [ramBytes] of memory can run: the biggest one that fits, so the
     * phone gets as much quality as its memory allows. If none of the engine's models fit, the lightest of
     * them, which at least has a shot.
     */
    fun recommended(ramBytes: Long, backend: LlmBackend): SupportedModel =
        modelsFor(backend).filter { it.minRamGb <= ramBytes / GIB }.maxByOrNull { it.sizeBytes } ?: defaultFor(backend)

    /**
     * The engine to start on: llama.cpp when the phone can run one of its models (from about 3 GB of RAM),
     * LiteRT-LM below that, where the 350M model is what the memory allows.
     */
    fun bestEngine(ramBytes: Long): LlmBackend =
        if (ramBytes >= 3 * GIB) LlmBackend.LLAMA_CPP else LlmBackend.LITERT_LM

    private const val GIB = 1_073_741_824L
}
