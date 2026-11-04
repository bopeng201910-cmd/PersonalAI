package com.personal.personalai.domain.usecase

import com.personal.personalai.domain.model.CandidateStatus
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import javax.inject.Inject

data class BatchApprovalResult(
    val succeededCount: Int,
    val failedCount: Int,
    val failedIds: List<Long>
)

class ReviewCandidateMemoryUseCase @Inject constructor(
    private val candidateMemoryRepository: CandidateMemoryRepository
) {
    /**
     * Approves and writes candidate memory to official memories table in a single atomic transaction.
     * Only candidates in PENDING status can be approved.
     * Duplicate calls on already approved items return success without duplicating official memory.
     */
    suspend fun approveCandidate(
        candidateId: Long,
        customContent: String? = null,
        customTopic: String? = null
    ): Result<Boolean> {
        return candidateMemoryRepository.approveCandidateInTransaction(
            candidateId = candidateId,
            customContent = customContent,
            customTopic = customTopic
        )
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
     * Correctly captures individual failures and returns detailed batch result.
     */
    suspend fun approveAll(candidateIds: List<Long>): BatchApprovalResult {
        var success = 0
        var fail = 0
        val failedIds = mutableListOf<Long>()

        for (id in candidateIds) {
            val res = approveCandidate(id)
            if (res.isSuccess && res.getOrNull() == true) {
                success++
            } else {
                fail++
                failedIds.add(id)
            }
        }

        return BatchApprovalResult(
            succeededCount = success,
            failedCount = fail,
            failedIds = failedIds
        )
    }
}
