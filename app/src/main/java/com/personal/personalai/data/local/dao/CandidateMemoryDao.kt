package com.personal.personalai.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.personal.personalai.data.local.entity.CandidateMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CandidateMemoryDao {

    @Query("SELECT * FROM candidate_memories WHERE status = :status ORDER BY createdAt DESC")
    fun getCandidateMemoriesByStatus(status: String): Flow<List<CandidateMemoryEntity>>

    @Query("SELECT * FROM candidate_memories WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun getPendingCandidateMemories(): Flow<List<CandidateMemoryEntity>>

    @Query("SELECT * FROM candidate_memories WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CandidateMemoryEntity?

    @Query("SELECT * FROM candidate_memories WHERE sourceMessageId = :sourceMessageId")
    suspend fun getBySourceMessageId(sourceMessageId: Long): List<CandidateMemoryEntity>

    @Query("SELECT * FROM candidate_memories WHERE sourceMessageId IN (:sourceMessageIds)")
    suspend fun getBySourceMessageIds(sourceMessageIds: List<Long>): List<CandidateMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidateMemory(candidate: CandidateMemoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidateMemories(candidates: List<CandidateMemoryEntity>): List<Long>

    @Update
    suspend fun updateCandidateMemory(candidate: CandidateMemoryEntity)

    @Query("UPDATE candidate_memories SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE candidate_memories SET content = :newContent, topic = :newTopic WHERE id = :id")
    suspend fun updateContent(id: Long, newContent: String, newTopic: String)

    @Delete
    suspend fun deleteCandidateMemory(candidate: CandidateMemoryEntity)

    @Query("DELETE FROM candidate_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM candidate_memories")
    suspend fun clearAll()
}
