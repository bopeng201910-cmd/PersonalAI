package com.personal.personalai.data.repository

import com.personal.personalai.data.local.dao.CandidateMemoryDao
import com.personal.personalai.data.local.entity.toDomain
import com.personal.personalai.data.local.entity.toEntity
import com.personal.personalai.domain.model.CandidateMemory
import com.personal.personalai.domain.model.CandidateStatus
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CandidateMemoryRepositoryImpl @Inject constructor(
    private val candidateMemoryDao: CandidateMemoryDao
) : CandidateMemoryRepository {

    override fun getPendingCandidateMemories(): Flow<List<CandidateMemory>> =
        candidateMemoryDao.getPendingCandidateMemories().map { list ->
            list.map { it.toDomain() }
        }

    override fun getCandidateMemoriesByStatus(status: CandidateStatus): Flow<List<CandidateMemory>> =
        candidateMemoryDao.getCandidateMemoriesByStatus(status.name).map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun getById(id: Long): CandidateMemory? =
        candidateMemoryDao.getById(id)?.toDomain()

    override suspend fun saveCandidates(candidates: List<CandidateMemory>): List<Long> =
        candidateMemoryDao.insertCandidateMemories(candidates.map { it.toEntity() })

    override suspend fun updateStatus(id: Long, status: CandidateStatus) {
        candidateMemoryDao.updateStatus(id, status.name)
    }

    override suspend fun updateContent(id: Long, content: String, topic: String) {
        candidateMemoryDao.updateContent(id, content, topic)
    }

    override suspend fun deleteById(id: Long) {
        candidateMemoryDao.deleteById(id)
    }
}
