package com.personal.personalai.presentation.modelcenter

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.personalai.localllm.api.DeviceAssessment
import com.personal.personalai.localllm.api.DeviceCapabilityEvaluator
import com.personal.personalai.localllm.api.LocalModel
import com.personal.personalai.localllm.download.ModelDownloadManager
import com.personal.personalai.presentation.settings.PreferencesKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    LOADING,
    ACTIVE,
    LOAD_FAILED,
    UNAVAILABLE
}

data class ModelCardUiState(
    val model: LocalModel,
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val isSelected: Boolean = false,
    val status: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val downloadPercent: Int = 0,
    val error: String? = null
)

data class ModelCenterUiState(
    val deviceAssessment: DeviceAssessment? = null,
    val activeModelId: String = LocalModel.GEMMA_4_E2B.modelId,
    val models: List<ModelCardUiState> = emptyList(),
    val infoMessage: String? = null,
    val error: String? = null
)

@HiltViewModel
class ModelCenterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val localLlmDataSource: com.personal.personalai.data.datasource.ai.LocalLlmDataSource? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelCenterUiState())
    val uiState: StateFlow<ModelCenterUiState> = _uiState.asStateFlow()

    init {
        evaluateDevice()
        loadModelStatuses()
        observeActiveModel()
    }

    fun evaluateDevice() {
        val assessment = DeviceCapabilityEvaluator.evaluate(context)
        _uiState.update { it.copy(deviceAssessment = assessment) }
    }

    private fun observeActiveModel() {
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                val active = prefs[PreferencesKeys.LOCAL_MODEL] ?: LocalModel.GEMMA_4_E2B.modelId
                _uiState.update { state ->
                    state.copy(
                        activeModelId = active,
                        models = state.models.map { it.copy(isSelected = it.model.modelId == active) }
                    )
                }
            }
        }
    }

    fun loadModelStatuses() {
        val models = LocalModel.entries.map { model ->
            val isDownloaded = ModelDownloadManager.isDownloaded(context, model)
            val isSelected = model.modelId == _uiState.value.activeModelId
            val status = when {
                !isDownloaded -> ModelStatus.NOT_DOWNLOADED
                isSelected -> ModelStatus.ACTIVE
                else -> ModelStatus.INSTALLED
            }
            ModelCardUiState(
                model = model,
                isDownloaded = isDownloaded,
                isSelected = isSelected,
                status = status
            )
        }
        _uiState.update { it.copy(models = models) }
    }

    fun selectModel(model: LocalModel) {
        viewModelScope.launch {
            // Confirm model is downloaded and verified before marking active
            if (!ModelDownloadManager.isDownloaded(context, model)) {
                _uiState.update {
                    it.copy(error = "Cannot activate '${model.displayName}'. Model must be downloaded and verified first.")
                }
                return@launch
            }

            updateModel(model) { it.copy(status = ModelStatus.LOADING) }

            val loadResult = localLlmDataSource?.activateModel(model) ?: Result.success(Unit)
            loadResult.onSuccess {
                dataStore.edit { it[PreferencesKeys.LOCAL_MODEL] = model.modelId }
                _uiState.update {
                    it.copy(
                        activeModelId = model.modelId,
                        infoMessage = "Active AI model switched to ${model.displayName}"
                    )
                }
                loadModelStatuses()
            }.onFailure { err ->
                updateModel(model) { it.copy(status = ModelStatus.LOAD_FAILED, error = err.message) }
                _uiState.update {
                    it.copy(error = "Model activation failed: ${err.message}. Cloud fallback blocked.")
                }
            }
        }
    }

    fun downloadModel(model: LocalModel) {
        viewModelScope.launch {
            val hfToken = dataStore.data.first()[PreferencesKeys.HF_TOKEN].orEmpty()
            ModelDownloadManager.enqueueDownload(context, model, hfToken)
            updateModel(model) { it.copy(isDownloading = true, error = null) }

            ModelDownloadManager.getDownloadProgress(context, model).collect { progress ->
                updateModel(model) { current ->
                    when {
                        progress.error != null -> current.copy(isDownloading = false, error = progress.error)
                        progress.isComplete -> current.copy(isDownloading = false, isDownloaded = true, downloadPercent = 100)
                        progress.isCancelled -> current.copy(isDownloading = false)
                        else -> current.copy(downloadPercent = progress.percent)
                    }
                }
            }
        }
    }

    fun cancelDownload(model: LocalModel) {
        ModelDownloadManager.cancelDownload(context, model)
        updateModel(model) { it.copy(isDownloading = false) }
    }

    fun deleteModel(model: LocalModel) {
        // Guard: Prevent removal of the currently active model
        if (model.modelId == _uiState.value.activeModelId) {
            _uiState.update {
                it.copy(error = "Cannot remove active model '${model.displayName}'. Please switch to another model first.")
            }
            return
        }
        ModelDownloadManager.deleteModel(context, model)
        updateModel(model) { it.copy(isDownloaded = false, downloadPercent = 0) }
        _uiState.update { it.copy(infoMessage = "Removed model ${model.displayName} from storage.") }
    }

    private fun updateModel(model: LocalModel, transform: (ModelCardUiState) -> ModelCardUiState) {
        _uiState.update { state ->
            state.copy(models = state.models.map { if (it.model == model) transform(it) else it })
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(infoMessage = null, error = null) }
}
