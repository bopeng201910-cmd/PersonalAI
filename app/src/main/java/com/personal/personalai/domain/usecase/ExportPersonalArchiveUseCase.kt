package com.personal.personalai.domain.usecase

import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.repository.ConversationRepository
import com.personal.personalai.domain.repository.MemoryRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class ExportPersonalArchiveUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val chatRepository: ChatRepository,
    private val conversationRepository: ConversationRepository
) {
    suspend operator fun invoke(): String {
        val root = JSONObject()
        root.put("exportVersion", 1)
        root.put("exportedAtMs", System.currentTimeMillis())

        val memories = memoryRepository.getMemories().first()
        val memoriesArray = JSONArray()
        memories.forEach { mem ->
            val obj = JSONObject().apply {
                put("id", mem.id)
                put("content", mem.content)
                put("topic", mem.topic)
                put("createdAt", mem.createdAt)
            }
            memoriesArray.put(obj)
        }
        root.put("memories", memoriesArray)

        val conversations = conversationRepository.getAllConversations().first()
        val convsArray = JSONArray()
        conversations.forEach { conv ->
            val convObj = JSONObject().apply {
                put("id", conv.id)
                put("title", conv.title)
                put("createdAt", conv.createdAt)
                put("updatedAt", conv.updatedAt)

                val msgs = chatRepository.getMessagesByConversation(conv.id).first()
                val msgsArray = JSONArray()
                msgs.forEach { m ->
                    msgsArray.put(JSONObject().apply {
                        put("id", m.id)
                        put("content", m.content)
                        put("role", m.role.name)
                        put("timestamp", m.timestamp)
                        put("conversationId", m.conversationId)
                    })
                }
                put("messages", msgsArray)
            }
            convsArray.put(convObj)
        }
        root.put("conversations", convsArray)

        return root.toString(2)
    }
}
