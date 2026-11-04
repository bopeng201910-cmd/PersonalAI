package com.personal.personalai.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personal.personalai.domain.model.CandidateMemory
import com.personal.personalai.domain.model.CandidateStatus
import com.personal.personalai.domain.model.MemoryCategory
import com.personal.personalai.domain.model.MemorySourceType

@Entity(
    tableName = "candidate_memories",
    indices = [
        Index(value = ["status"]),
        Index(value = ["sourceMessageId"])
    ]
)
data class CandidateMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    @ColumnInfo(defaultValue = "''") val topic: String = "",
    @ColumnInfo(defaultValue = "''") val originalSnippet: String = "",
    @ColumnInfo(defaultValue = "'OTHER_IMPORTANT'") val category: String = MemoryCategory.OTHER_IMPORTANT.name,
    @ColumnInfo(defaultValue = "'EXPLICIT_FACT'") val sourceType: String = MemorySourceType.EXPLICIT_FACT.name,
    @ColumnInfo(defaultValue = "0") val sourceMessageId: Long = 0,
    @ColumnInfo(defaultValue = "0") val sourceTimestamp: Long = 0,
    @ColumnInfo(defaultValue = "'PENDING'") val status: String = CandidateStatus.PENDING.name,
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
