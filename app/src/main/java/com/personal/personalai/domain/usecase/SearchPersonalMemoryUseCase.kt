package com.personal.personalai.domain.usecase

import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.SearchResult
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.repository.MemoryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SearchPersonalMemoryUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(query: String, conversationId: String? = null): SearchResult {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) {
            return SearchResult(query, emptyList(), emptyList(), false)
        }

        val allMemories = memoryRepository.getMemories().first()
        val matchedMemories = allMemories.filter {
            it.content.lowercase().contains(cleanQuery) || it.topic.lowercase().contains(cleanQuery)
        }

        val allMessages: List<Message> = if (conversationId != null) {
            chatRepository.getMessagesByConversation(conversationId).first()
        } else {
            chatRepository.getMessages().first()
        }

        val matchedMessages = allMessages.filter {
            it.content.lowercase().contains(cleanQuery)
        }

        val evidenceFound = matchedMemories.isNotEmpty() || matchedMessages.isNotEmpty()

        return SearchResult(
            query = query,
            matchedMemories = matchedMemories,
            matchedMessages = matchedMessages,
            evidenceFound = evidenceFound
        )
    }

    /**
     * Formats evidence into an honest, evidence-grounded summary statement.
     * When evidence is unavailable or uncertain, it explicitly reports that
     * no stored records were found rather than hallucinating.
     */
    fun formatEvidenceResponse(result: SearchResult): String {
        if (!result.evidenceFound) {
            return "Based on your stored records and memory archive, I could not find any evidence or conversations regarding \"${result.query}\"."
        }

        val sb = StringBuilder()
        sb.append("Here is what was found in your stored records for \"${result.query}\":\n\n")

        if (result.matchedMemories.isNotEmpty()) {
            sb.append("🧠 **Confirmed Memories:**\n")
            result.matchedMemories.forEach { mem ->
                sb.append("• ${mem.content}")
                if (mem.topic.isNotBlank()) sb.append(" _[Topic: ${mem.topic}]_")
                sb.append("\n")
            }
            sb.append("\n")
        }

        if (result.matchedMessages.isNotEmpty()) {
            sb.append("💬 **Relevant Conversation Records:**\n")
            result.matchedMessages.take(5).forEach { msg ->
                val roleLabel = if (msg.role.name == "USER") "You" else "Lumio"
                sb.append("• $roleLabel: \"${msg.content}\"\n")
            }
            if (result.matchedMessages.size > 5) {
                sb.append("• _...and ${result.matchedMessages.size - 5} more records._\n")
            }
        }

        return sb.toString().trim()
    }
}
