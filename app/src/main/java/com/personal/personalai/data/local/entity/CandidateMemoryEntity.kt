package com.personal.personalai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.personal.personalai.domain.model.CandidateMemory
import com.personal.personalai.domain.model.CandidateStatus
import com.personal.personalai.domain.model.MemoryCategory
import com.personal.personalai.domain.model.MemorySourceType

@Entity(tableName = "candidate_memories")
data class CandidateMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val topic: String = "",
    val originalSnippet: String = "",
    val category: String = MemoryCategory.OTHER_IMPORTANT.name,
    val sourceType: String = MemorySourceType.EXPLICIT_FACT.name,
    val sourceMessageId: Long = 0,
    val sourceTimestamp: Long = 0,
    val status: String = CandidateStatus.PENDING.name,
    val createdAt: Long = System.currentTimeMillis()
)

fun CandidateMemoryEntity.toDomain() = CandidateMemory(
    id = id,
    content = content,
    topic = topic,
    originalSnippet = originalSnippet,
    category = runCatching { MemoryCategory.valueOf(category) }.getOrDefault(MemoryCategory.OTHER_IMPORTANT),
    sourceType = runCatching { MemorySourceType.valueOf(sourceType) }.getOrDefault(MemorySourceType.EXPLICIT_FACT),
    sourceMessageId = sourceMessageId,
    sourceTimestamp = sourceTimestamp,
    status = runCatching { CandidateStatus.valueOf(status) }.getOrDefault(CandidateStatus.PENDING),
    createdAt = createdAt
)

fun CandidateMemory.toEntity() = CandidateMemoryEntity(
    id = id,
    content = content,
    topic = topic,
    originalSnippet = originalSnippet,
    category = category.name,
    sourceType = sourceType.name,
    sourceMessageId = sourceMessageId,
    sourceTimestamp = sourceTimestamp,
    status = status.name,
    createdAt = createdAt
)
