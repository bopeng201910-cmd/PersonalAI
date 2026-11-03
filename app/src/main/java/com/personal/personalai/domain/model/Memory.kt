package com.personal.personalai.domain.model

import com.personal.personalai.domain.security.MemoryAccessScope

data class Memory(
    val id: Long = 0,
    val content: String,           // e.g. "User's name is John"
    val topic: String = "",        // e.g. "name", "preference", "fact"
    val accessScope: MemoryAccessScope = MemoryAccessScope.GLOBAL_SHARED,
    val createdAt: Long = System.currentTimeMillis()
)
