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

data class ModelCardUiState(
    val model: LocalModel,
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val isSelected: Boolean = false,
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
    private val dataStore: DataStore<Preferences>
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
            ModelCardUiState(
                model = model,
                isDownloaded = ModelDownloadManager.isDownloaded(context, model),
                isSelected = model.modelId == _uiState.value.activeModelId
            )
        }
        _uiState.update { it.copy(models = models) }
    }

    fun selectModel(model: LocalModel) {
        viewModelScope.launch {
            dataStore.edit { it[PreferencesKeys.LOCAL_MODEL] = model.modelId }
            _uiState.update {
                it.copy(
                    activeModelId = model.modelId,
                    infoMessage = "Active AI model switched to ${model.displayName}"
                )
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
