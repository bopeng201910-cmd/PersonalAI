package com.personal.personalai.domain.repository

import com.personal.personalai.domain.model.CandidateMemory
import com.personal.personalai.domain.model.CandidateStatus
import kotlinx.coroutines.flow.Flow

interface CandidateMemoryRepository {
    fun getPendingCandidateMemories(): Flow<List<CandidateMemory>>
    fun getCandidateMemoriesByStatus(status: CandidateStatus): Flow<List<CandidateMemory>>
    suspend fun getById(id: Long): CandidateMemory?
    suspend fun saveCandidates(candidates: List<CandidateMemory>): List<Long>
    suspend fun updateStatus(id: Long, status: CandidateStatus)
    suspend fun updateContent(id: Long, content: String, topic: String)
    suspend fun deleteById(id: Long)
}
