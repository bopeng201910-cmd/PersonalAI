package com.personal.personalai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.MessageRole

import androidx.room.ColumnInfo
import androidx.room.Index

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val role: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hasCreatedTask: Boolean = false,
    val createdTaskTitle: String? = null,
    @ColumnInfo(defaultValue = "'default'") val conversationId: String = "default"
)

fun MessageEntity.toDomain() = Message(
    id = id,
    content = content,
    role = MessageRole.valueOf(role),
    timestamp = timestamp,
    hasCreatedTask = hasCreatedTask,
    createdTaskTitle = createdTaskTitle,
    conversationId = conversationId
)

fun Message.toEntity() = MessageEntity(
    id = id,
    content = content,
    role = role.name,
    timestamp = timestamp,
    hasCreatedTask = hasCreatedTask,
    createdTaskTitle = createdTaskTitle,
    conversationId = conversationId
)
