package com.personal.personalai.domain.usecase

import com.personal.personalai.domain.model.CandidateStatus
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.repository.MemoryRepository
import javax.inject.Inject

class ReviewCandidateMemoryUseCase @Inject constructor(
    private val candidateMemoryRepository: CandidateMemoryRepository,
    private val memoryRepository: MemoryRepository
) {
    /**
     * Approves and writes candidate memory to official memories table.
     */
    suspend fun approveCandidate(candidateId: Long, customContent: String? = null, customTopic: String? = null) {
        val candidate = candidateMemoryRepository.getById(candidateId) ?: return
        val finalContent = customContent?.trim()?.ifBlank { candidate.content } ?: candidate.content
        val finalTopic = customTopic?.trim() ?: candidate.topic

        // 1. Save to official memories table
        memoryRepository.saveMemory(
            Memory(
                content = finalContent,
                topic = finalTopic,
                createdAt = candidate.sourceTimestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        )

        // 2. Mark candidate as APPROVED
        candidateMemoryRepository.updateStatus(candidateId, CandidateStatus.APPROVED)
    }

    /**
     * Discards candidate memory.
     */
    suspend fun discardCandidate(candidateId: Long) {
        candidateMemoryRepository.updateStatus(candidateId, CandidateStatus.DISCARDED)
    }

    /**
     * Updates candidate content and topic before approval.
     */
    suspend fun updateCandidate(candidateId: Long, content: String, topic: String) {
        candidateMemoryRepository.updateContent(candidateId, content.trim(), topic.trim())
    }

    /**
     * Bulk approves all candidates with their current contents.
     */
    suspend fun approveAll(candidateIds: List<Long>) {
        candidateIds.forEach { id ->
            approveCandidate(id)
        }
    }
}
