package com.personal.personalai.domain.model

enum class MemoryCategory {
    USER_PREFERENCE,    // 个人偏好
    IMPORTANT_DECISION, // 重要决定
    LONG_TERM_GOAL,     // 长期目标
    PROJECT_PROGRESS,   // 项目进度
    TODO_ITEM,          // 待办事项
    OTHER_IMPORTANT     // 其他重要信息
}

enum class MemorySourceType {
    EXPLICIT_FACT,      // 用户明确陈述
    INFERRED_PREFERENCE // AI推断偏好
}

enum class CandidateStatus {
    PENDING,            // 待审核
    APPROVED,           // 已确认存入长期记忆
    DISCARDED           // 已丢弃
}

data class CandidateMemory(
    val id: Long = 0,
    val content: String,
    val topic: String = "",
    val originalSnippet: String = "",
    val category: MemoryCategory = MemoryCategory.OTHER_IMPORTANT,
    val sourceType: MemorySourceType = MemorySourceType.EXPLICIT_FACT,
    val sourceMessageId: Long = 0,
    val sourceTimestamp: Long = 0,
    val status: CandidateStatus = CandidateStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)
