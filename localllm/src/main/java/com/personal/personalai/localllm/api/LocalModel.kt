package com.personal.personalai.localllm.api

/**
 * Supported on-device Gemma models available for download and inference.
 *
 * Models are downloaded from HuggingFace in LiteRT task format and stored in
 * the app's external files directory.
 */
enum class LocalModel(
    val modelId: String,
    val displayName: String,
    val provider: String,
    val license: String,
    val huggingFaceRepo: String,
    val fileName: String,
    /** Expected download size in bytes (used for progress estimation). */
    val approximateSizeBytes: Long,
    val estimatedRamBytes: Long,
    val supportedLanguages: List<String>,
    val minRamGb: Int = 12,
    /** Pinned immutable git commit revision on HuggingFace. */
    val pinnedRevision: String? = null,
    /** Official SHA-256 checksum where available from upstream repository. Null if unverified. */
    val officialSha256: String? = null
) {
    // ── Google Gemma Family ───────────────────────────────────────────────────
    GEMMA_4_E2B(
        modelId              = "gemma-4-E2B-it",
        displayName          = "Gemma 4 E2B (~2.6 GB)",
        provider             = "Google",
        license              = "Gemma Terms of Use",
        huggingFaceRepo      = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName             = "gemma-4-E2B-it.litertlm",
        approximateSizeBytes = 2_772_123_648L,
        estimatedRamBytes    = 3_500_000_000L,
        supportedLanguages   = listOf("en", "zh", "es", "fr", "de"),
        minRamGb             = 12,
        pinnedRevision       = "6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94",
        officialSha256       = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    ),
    GEMMA_4_E4B(
        modelId              = "gemma-4-E4B-it",
        displayName          = "Gemma 4 E4B (~3.7 GB)",
        provider             = "Google",
        license              = "Gemma Terms of Use",
        huggingFaceRepo      = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName             = "gemma-4-E4B-it.litertlm",
        approximateSizeBytes = 3_921_479_680L,
        estimatedRamBytes    = 5_200_000_000L,
        supportedLanguages   = listOf("en", "zh", "es", "fr", "de"),
        minRamGb             = 12,
        pinnedRevision       = null,
        officialSha256       = null
    ),

    // ── Alibaba Qwen Family ───────────────────────────────────────────────────
    QWEN_2_5_1_5B(
        modelId              = "qwen-2.5-1.5b-it",
        displayName          = "Qwen 2.5 1.5B Instruct (~1.8 GB)",
        provider             = "Alibaba Cloud",
        license              = "Apache 2.0",
        huggingFaceRepo      = "litert-community/Qwen2.5-1.5B-Instruct-litert-lm",
        fileName             = "qwen2.5-1.5b-instruct.litertlm",
        approximateSizeBytes = 1_850_000_000L,
        estimatedRamBytes    = 2_800_000_000L,
        supportedLanguages   = listOf("zh", "en", "ja", "ko", "ru"),
        minRamGb             = 12,
        pinnedRevision       = null,
        officialSha256       = null
    ),
    QWEN_2_5_3B(
        modelId              = "qwen-2.5-3b-it",
        displayName          = "Qwen 2.5 3B Instruct (~3.2 GB)",
        provider             = "Alibaba Cloud",
        license              = "Qwen Research License",
        huggingFaceRepo      = "litert-community/Qwen2.5-3B-Instruct-litert-lm",
        fileName             = "qwen2.5-3b-instruct.litertlm",
        approximateSizeBytes = 3_250_000_000L,
        estimatedRamBytes    = 4_500_000_000L,
        supportedLanguages   = listOf("zh", "en", "ja", "ko", "es"),
        minRamGb             = 12,
        pinnedRevision       = null,
        officialSha256       = null
    );

    companion object {
        fun fromId(modelId: String): LocalModel? = entries.find { it.modelId == modelId }
    }
}
