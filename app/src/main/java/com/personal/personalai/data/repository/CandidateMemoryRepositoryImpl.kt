package com.personal.personalai.data.repository

import androidx.room.withTransaction
import com.personal.personalai.data.local.PersonalAIDatabase
import com.personal.personalai.data.local.dao.CandidateMemoryDao
import com.personal.personalai.data.local.dao.MemoryDao
import com.personal.personalai.data.local.entity.MemoryEntity
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
    private val database: PersonalAIDatabase,
    private val candidateMemoryDao: CandidateMemoryDao,
    private val memoryDao: MemoryDao
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

    override suspend fun getBySourceMessageIds(sourceMessageIds: List<Long>): List<CandidateMemory> =
        if (sourceMessageIds.isEmpty()) emptyList()
        else candidateMemoryDao.getBySourceMessageIds(sourceMessageIds).map { it.toDomain() }

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

    override suspend fun approveCandidateInTransaction(
        candidateId: Long,
        customContent: String?,
        customTopic: String?
    ): Result<Boolean> = try {
        Result.success(
            database.withTransaction {
                val candidate = candidateMemoryDao.getById(candidateId)
                    ?: return@withTransaction false

                // Strict check: Only PENDING candidates can be approved
                if (candidate.status != CandidateStatus.PENDING.name) {
                    return@withTransaction false
                }

                val finalContent = customContent?.trim()?.ifBlank { candidate.content } ?: candidate.content
                val finalTopic = customTopic?.trim() ?: candidate.topic

                // 1. Insert official memory in the same transaction
                memoryDao.insertMemory(
                    MemoryEntity(
                        content = finalContent,
                        topic = finalTopic,
                        createdAt = if (candidate.sourceTimestamp > 0) candidate.sourceTimestamp else System.currentTimeMillis()
                    )
                )

                // 2. Mark candidate as APPROVED
                candidateMemoryDao.updateStatus(candidateId, CandidateStatus.APPROVED.name)
                true
            }
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
