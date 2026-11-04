package com.personal.personalai.domain.model

data class SearchResult(
    val query: String,
    val matchedMemories: List<Memory>,
    val matchedMessages: List<Message>,
    val evidenceFound: Boolean
)
