package com.personal.personalai.presentation.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.localllm.api.LocalModel
import com.personal.personalai.localllm.download.ModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object PreferencesKeys {
    val API_KEY        = stringPreferencesKey("api_key")
    val AI_PROVIDER    = stringPreferencesKey("ai_provider")
    val OLLAMA_URL     = stringPreferencesKey("ollama_url")
    val OLLAMA_MODEL   = stringPreferencesKey("ollama_model")
    val SERPER_API_KEY = stringPreferencesKey("serper_api_key")
    val LOCAL_MODEL    = stringPreferencesKey("local_model")
    val HF_TOKEN       = stringPreferencesKey("hf_token")

    // OpenAI Responses API stateful-chain bookkeeping. We pass last response_id
    // as previous_response_id on the next call, but only while the chain is
    // "warm" (recent activity). After CHAIN_WARM_MS of silence we discard it
    // and cold-start with the last N messages.
    val LAST_RESPONSE_ID    = stringPreferencesKey("last_response_id")
    val LAST_RESPONSE_AT_MS = longPreferencesKey("last_response_at_ms")

    // Daily Memory Review cursor tracking
    val LAST_REVIEWED_MESSAGE_ID = longPreferencesKey("last_reviewed_message_id")

    // Proactive Check-in preferences
    val PROACTIVE_ENABLED        = androidx.datastore.preferences.core.booleanPreferencesKey("proactive_enabled")
    val PROACTIVE_MORNING_TIME   = stringPreferencesKey("proactive_morning_time")
    val PROACTIVE_EVENING_TIME   = stringPreferencesKey("proactive_evening_time")
    val PROACTIVE_QUIET_START    = stringPreferencesKey("proactive_quiet_start")
    val PROACTIVE_QUIET_END      = stringPreferencesKey("proactive_quiet_end")
    val PROACTIVE_FOLLOWUP_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("proactive_followup_enabled")
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        refreshModelStatuses()
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    private fun loadSettings() {
        viewModelScope.launch {
            dataStore.data.collect { preferences ->
                val providerStr = preferences[PreferencesKeys.AI_PROVIDER] ?: "local"
                val provider = when (providerStr) {
                    "ollama" -> AiProvider.OLLAMA
                    "openai" -> AiProvider.OPENAI
                    else     -> AiProvider.LOCAL
                }

                _uiState.update {
                    it.copy(
                        apiKey             = preferences[PreferencesKeys.API_KEY] ?: "",
                        aiProvider         = provider,
                        ollamaUrl          = preferences[PreferencesKeys.OLLAMA_URL]   ?: "",
                        ollamaModel        = preferences[PreferencesKeys.OLLAMA_MODEL] ?: "",
                        serperApiKey       = preferences[PreferencesKeys.SERPER_API_KEY] ?: "",
                        localSelectedModel = preferences[PreferencesKeys.LOCAL_MODEL]
                                                ?: LocalModel.GEMMA_4_E2B.modelId,
                        hfToken            = preferences[PreferencesKeys.HF_TOKEN] ?: "",
                        isProactiveEnabled = preferences[PreferencesKeys.PROACTIVE_ENABLED] ?: false,
                        proactiveMorningTime = preferences[PreferencesKeys.PROACTIVE_MORNING_TIME] ?: "09:00",
                        proactiveEveningTime = preferences[PreferencesKeys.PROACTIVE_EVENING_TIME] ?: "20:00",
                        proactiveQuietStart = preferences[PreferencesKeys.PROACTIVE_QUIET_START] ?: "22:00",
                        proactiveQuietEnd   = preferences[PreferencesKeys.PROACTIVE_QUIET_END] ?: "07:00",
                        isProactiveFollowupEnabled = preferences[PreferencesKeys.PROACTIVE_FOLLOWUP_ENABLED] ?: true
                    )
                }
            }
        }
    }

    // ── Proactive Check-in settings ───────────────────────────────────────────

    fun toggleProactiveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[PreferencesKeys.PROACTIVE_ENABLED] = enabled }
            _uiState.update { it.copy(isProactiveEnabled = enabled) }
            if (enabled) {
                com.personal.personalai.worker.ProactiveCheckInWorker.scheduleNextCheckIn(context)
            } else {
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork(
                    com.personal.personalai.worker.ProactiveCheckInWorker.WORK_NAME
                )
            }
        }
    }

    fun setProactiveTimes(morning: String, evening: String, quietStart: String, quietEnd: String) {
        viewModelScope.launch {
            dataStore.edit {
                it[PreferencesKeys.PROACTIVE_MORNING_TIME] = morning
                it[PreferencesKeys.PROACTIVE_EVENING_TIME] = evening
                it[PreferencesKeys.PROACTIVE_QUIET_START] = quietStart
                it[PreferencesKeys.PROACTIVE_QUIET_END] = quietEnd
            }
            _uiState.update {
                it.copy(
                    proactiveMorningTime = morning,
                    proactiveEveningTime = evening,
                    proactiveQuietStart = quietStart,
                    proactiveQuietEnd = quietEnd
                )
            }
            if (_uiState.value.isProactiveEnabled) {
                com.personal.personalai.worker.ProactiveCheckInWorker.scheduleNextCheckIn(context)
            }
        }
    }

    // ── OpenAI settings ───────────────────────────────────────────────────────

    fun onApiKeyChanged(key: String) =
        _uiState.update { it.copy(apiKey = key, savedSuccessfully = false) }

    fun saveApiKey() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            runCatching {
                dataStore.edit { prefs -> prefs[PreferencesKeys.API_KEY] = _uiState.value.apiKey }
                _uiState.update { it.copy(isSaving = false, savedSuccessfully = true) }
            }.onFailure {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    // ── Provider selection ────────────────────────────────────────────────────

    fun setAiProvider(provider: AiProvider) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PreferencesKeys.AI_PROVIDER] = when (provider) {
                    AiProvider.OLLAMA -> "ollama"
                    AiProvider.OPENAI -> "openai"
                    AiProvider.LOCAL  -> "local"
                }
                // A stored OpenAI response_id is meaningless to other providers.
                prefs.remove(PreferencesKeys.LAST_RESPONSE_ID)
                prefs.remove(PreferencesKeys.LAST_RESPONSE_AT_MS)
            }
            _uiState.update { it.copy(aiProvider = provider) }
        }
    }

    // ── Ollama Dev Mode settings ──────────────────────────────────────────────

    fun onOllamaUrlChanged(url: String) =
        _uiState.update { it.copy(ollamaUrl = url, ollamaSavedSuccessfully = false) }

    fun onOllamaModelChanged(model: String) =
        _uiState.update { it.copy(ollamaModel = model, ollamaSavedSuccessfully = false) }

    fun saveOllamaSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isOllamaSaving = true) }
            runCatching {
                dataStore.edit { prefs ->
                    prefs[PreferencesKeys.OLLAMA_URL]   = _uiState.value.ollamaUrl.trim()
                    prefs[PreferencesKeys.OLLAMA_MODEL] = _uiState.value.ollamaModel.trim()
                }
                _uiState.update { it.copy(isOllamaSaving = false, ollamaSavedSuccessfully = true) }
            }.onFailure {
                _uiState.update { it.copy(isOllamaSaving = false) }
            }
        }
    }

    // ── Web Search (Serper.dev) ────────────────────────────────────────────────

    fun onSerperApiKeyChanged(key: String) =
        _uiState.update { it.copy(serperApiKey = key, serperSavedSuccessfully = false) }

    fun saveSerperApiKey() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSerperSaving = true) }
            runCatching {
                dataStore.edit { prefs ->
                    prefs[PreferencesKeys.SERPER_API_KEY] = _uiState.value.serperApiKey.trim()
                }
                _uiState.update { it.copy(isSerperSaving = false, serperSavedSuccessfully = true) }
            }.onFailure {
                _uiState.update { it.copy(isSerperSaving = false) }
            }
        }
    }

    // ── Local on-device model management ─────────────────────────────────────

    fun onHfTokenChanged(token: String) =
        _uiState.update { it.copy(hfToken = token, hfTokenSavedSuccessfully = false) }

    fun saveHfToken() {
        viewModelScope.launch {
            _uiState.update { it.copy(isHfTokenSaving = true) }
            runCatching {
                dataStore.edit { prefs -> prefs[PreferencesKeys.HF_TOKEN] = _uiState.value.hfToken.trim() }
                _uiState.update { it.copy(isHfTokenSaving = false, hfTokenSavedSuccessfully = true) }
            }.onFailure {
                _uiState.update { it.copy(isHfTokenSaving = false) }
            }
        }
    }

    fun selectLocalModel(modelId: String) {
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[PreferencesKeys.LOCAL_MODEL] = modelId }
            _uiState.update { it.copy(localSelectedModel = modelId) }
        }
    }

    fun downloadModel(model: LocalModel) {
        val hfToken = _uiState.value.hfToken.trim()
        ModelDownloadManager.enqueueDownload(context, model, hfToken)

        // Mark as downloading immediately, then observe real progress
        updateModelStatus(model) { it.copy(isDownloading = true, error = null) }

        viewModelScope.launch {
            ModelDownloadManager.getDownloadProgress(context, model).collect { progress ->
                updateModelStatus(model) { status ->
                    when {
                        progress.error != null -> status.copy(
                            isDownloading = false,
                            error         = progress.error
                        )
                        progress.isCancelled -> status.copy(
                            isDownloading = false
                        )
                        progress.isComplete -> status.copy(
                            isDownloading = false,
                            isDownloaded  = true,
                            downloadPercent = 100
                        )
                        else -> status.copy(
                            downloadPercent = progress.percent
                        )
                    }
                }
            }
        }
    }

    fun cancelDownload(model: LocalModel) {
        ModelDownloadManager.cancelDownload(context, model)
        updateModelStatus(model) { it.copy(isDownloading = false) }
    }

    fun deleteModel(model: LocalModel) {
        ModelDownloadManager.deleteModel(context, model)
        updateModelStatus(model) { it.copy(isDownloaded = false, downloadPercent = 0) }
    }

    private fun refreshModelStatuses() {
        val statuses = LocalModel.entries.map { model ->
            ModelDownloadUiState(
                model        = model,
                isDownloaded = ModelDownloadManager.isDownloaded(context, model)
            )
        }
        _uiState.update { it.copy(modelStatuses = statuses) }
    }

    private fun updateModelStatus(model: LocalModel, transform: (ModelDownloadUiState) -> ModelDownloadUiState) {
        _uiState.update { state ->
            state.copy(
                modelStatuses = state.modelStatuses.map { s ->
                    if (s.model == model) transform(s) else s
                }
            )
        }
    }

    // ── Chat history ──────────────────────────────────────────────────────────

    fun showClearHistoryDialog()    = _uiState.update { it.copy(showClearHistoryDialog = true) }
    fun dismissClearHistoryDialog() = _uiState.update { it.copy(showClearHistoryDialog = false) }

    fun clearChatHistory() {
        viewModelScope.launch {
            chatRepository.clearHistory()
            _uiState.update { it.copy(showClearHistoryDialog = false) }
        }
    }
}
