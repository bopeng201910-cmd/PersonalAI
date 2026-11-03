package com.personal.personalai.presentation.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.personalai.domain.model.CandidateMemory
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.usecase.ExtractCandidateMemoriesUseCase
import com.personal.personalai.domain.usecase.ReviewCandidateMemoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import javax.inject.Inject

data class FileEntry(
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val lastModified: String
)

data class LibraryUiState(
    val selectedTab: Int = 0,
    val files: List<FileEntry> = emptyList(),
    val candidateMemories: List<CandidateMemory> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val isLoadingFiles: Boolean = true,
    val isLoadingCandidates: Boolean = true,
    val isLoadingMemories: Boolean = true,
    val isExtracting: Boolean = false,
    val openedFile: Pair<String, String>? = null,
    val editingCandidate: CandidateMemory? = null,
    val error: String? = null,
    val infoMessage: String? = null
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val candidateMemoryRepository: CandidateMemoryRepository,
    private val extractCandidateMemoriesUseCase: ExtractCandidateMemoriesUseCase,
    private val reviewCandidateMemoryUseCase: ReviewCandidateMemoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val aiFilesDir get() = context.getExternalFilesDir("ai_files")

    init {
        loadFiles()
        viewModelScope.launch {
            memoryRepository.getMemories().collect { memories ->
                _uiState.update { it.copy(memories = memories, isLoadingMemories = false) }
            }
        }
        viewModelScope.launch {
            candidateMemoryRepository.getPendingCandidateMemories().collect { candidates ->
                _uiState.update { it.copy(candidateMemories = candidates, isLoadingCandidates = false) }
            }
        }
    }

    fun loadFiles() {
        _uiState.update { it.copy(isLoadingFiles = true) }
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                try {
                    val index = File(aiFilesDir, "_index.json")
                    if (!index.exists()) return@withContext emptyList()
                    val json = JSONArray(index.readText())
                    (0 until json.length()).map { i ->
                        val obj = json.getJSONObject(i)
                        FileEntry(
                            name = obj.optString("name"),
                            description = obj.optString("description"),
                            sizeBytes = obj.optLong("size_bytes"),
                            lastModified = obj.optString("last_modified")
                        )
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            _uiState.update { it.copy(files = entries, isLoadingFiles = false) }
        }
    }

    fun onTabSelected(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
        if (index == 0) loadFiles()
    }

    fun openFile(entry: FileEntry) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    File(aiFilesDir, entry.name).readText()
                } catch (e: Exception) {
                    null
                }
            }
            if (content != null) {
                _uiState.update { it.copy(openedFile = entry.name to content) }
            } else {
                _uiState.update { it.copy(error = "Could not read file: ${entry.name}") }
            }
        }
    }

    fun closeFile() = _uiState.update { it.copy(openedFile = null) }

    fun deleteMemory(memory: Memory) {
        viewModelScope.launch { memoryRepository.deleteMemory(memory) }
    }

    fun triggerReviewExtraction() {
        if (_uiState.value.isExtracting) return
        _uiState.update { it.copy(isExtracting = true) }
        viewModelScope.launch {
            extractCandidateMemoriesUseCase()
                .onSuccess { count ->
                    _uiState.update {
                        it.copy(
                            isExtracting = false,
                            infoMessage = if (count > 0) "Extracted $count new candidate memories"
                                          else "No new long-term memories found in recent chats"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isExtracting = false,
                            error = "Review extraction failed: ${e.message}"
                        )
                    }
                }
        }
    }

    fun approveCandidate(candidate: CandidateMemory, customContent: String? = null, customTopic: String? = null) {
        viewModelScope.launch {
            val result = reviewCandidateMemoryUseCase.approveCandidate(candidate.id, customContent, customTopic)
            if (result.isSuccess && result.getOrNull() == true) {
                _uiState.update { it.copy(editingCandidate = null, infoMessage = "Memory approved and saved.") }
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to save memory. Item may no longer be pending."
                _uiState.update { it.copy(error = errorMsg) }
            }
        }
    }

    fun approveCandidates(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val result = reviewCandidateMemoryUseCase.approveAll(ids)
            if (result.failedCount > 0) {
                _uiState.update {
                    it.copy(
                        infoMessage = "Saved ${result.succeededCount} memories. ${result.failedCount} failed.",
                        error = if (result.succeededCount == 0) "Failed to save selected memories." else null
                    )
                }
            } else {
                _uiState.update { it.copy(infoMessage = "Successfully saved all ${result.succeededCount} memories.") }
            }
        }
    }

    fun approveAllCandidates() {
        approveCandidates(_uiState.value.candidateMemories.map { it.id })
    }

    fun discardCandidate(candidate: CandidateMemory) {
        viewModelScope.launch {
            reviewCandidateMemoryUseCase.discardCandidate(candidate.id)
            if (_uiState.value.editingCandidate?.id == candidate.id) {
                _uiState.update { it.copy(editingCandidate = null) }
            }
        }
    }

    fun startEditingCandidate(candidate: CandidateMemory) {
        _uiState.update { it.copy(editingCandidate = candidate) }
    }

    fun dismissEditingCandidate() {
        _uiState.update { it.copy(editingCandidate = null) }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
    fun dismissInfoMessage() = _uiState.update { it.copy(infoMessage = null) }
}
