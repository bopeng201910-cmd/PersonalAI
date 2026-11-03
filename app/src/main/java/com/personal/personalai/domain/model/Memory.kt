package com.personal.personalai.domain.model

data class Memory(
    val id: Long = 0,
    val content: String,           // e.g. "User's name is John"
    val topic: String = "",        // e.g. "name", "preference", "fact"
    val createdAt: Long = System.currentTimeMillis()
)
