package com.personal.personalai.presentation.settings

import com.personal.personalai.localllm.api.LocalModel

/** Which AI backend the user has selected. */
enum class AiProvider { OPENAI, OLLAMA, LOCAL }

/** Download state for a single on-device model. */
data class ModelDownloadUiState(
    val model: LocalModel,
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadPercent: Int = 0,
    val error: String? = null
)

data class SettingsUiState(
    // ── OpenAI backend ────────────────────────────────────────────────────────
    val apiKey: String = "",
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false,

    // ── Common ────────────────────────────────────────────────────────────────
    val showClearHistoryDialog: Boolean = false,
    val aiProvider: AiProvider = AiProvider.OPENAI,

    // ── Ollama Dev Mode backend ───────────────────────────────────────────────
    /** Base URL of the Ollama server, e.g. "http://10.100.102.75:11434". */
    val ollamaUrl: String = "",
    /** Ollama model tag to use for inference, e.g. "qwen3.5:4b". */
    val ollamaModel: String = "",
    val isOllamaSaving: Boolean = false,
    val ollamaSavedSuccessfully: Boolean = false,

    // ── Local on-device backend ───────────────────────────────────────────────
    /** ID of the selected local model (one of [LocalModel.modelId]). */
    val localSelectedModel: String = LocalModel.GEMMA_4_E2B.modelId,
    /** Optional HuggingFace token for downloading gated models. */
    val hfToken: String = "",
    val isHfTokenSaving: Boolean = false,
    val hfTokenSavedSuccessfully: Boolean = false,
    /** Download / on-disk status for each available local model. */
    val modelStatuses: List<ModelDownloadUiState> = LocalModel.entries.map {
        ModelDownloadUiState(model = it)
    },

    // ── Web Search (Serper.dev) ───────────────────────────────────────────────
    /** Serper.dev API key — used by WebSearchTool for real Google results. */
    val serperApiKey: String = "",
    val isSerperSaving: Boolean = false,
    val serperSavedSuccessfully: Boolean = false,

    // ── Proactive Conversation Check-in ───────────────────────────────────────
    val isProactiveEnabled: Boolean = false,
    val proactiveMorningTime: String = "09:00",
    val proactiveEveningTime: String = "20:00",
    val proactiveQuietStart: String = "22:00",
    val proactiveQuietEnd: String = "07:00",
    val isProactiveFollowupEnabled: Boolean = true
)
