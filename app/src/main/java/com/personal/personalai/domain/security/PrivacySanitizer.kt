package com.personal.personalai.domain.security

import com.personal.personalai.domain.model.Memory
import org.json.JSONArray
import org.json.JSONObject

enum class MemoryAccessScope {
    GLOBAL_SHARED,
    LOCAL_ONLY,
    CLOUD_PERMITTED
}

object PrivacySanitizer {

    private val PHONE_REGEX = Regex("""(?:\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}""")
    private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")

    /**
     * Filters out any memories marked as LOCAL_ONLY using both structured field and metadata.
     */
    fun sanitizeMemoriesForCloud(memories: List<Memory>): List<Memory> {
        return memories.filter { memory ->
            memory.accessScope != MemoryAccessScope.LOCAL_ONLY &&
            !memory.topic.contains("LOCAL_ONLY", ignoreCase = true) &&
            !memory.topic.contains("PRIVATE", ignoreCase = true) &&
            !memory.content.contains("[LOCAL_ONLY]", ignoreCase = true)
        }
    }

    /**
     * Sanitizes conversation items before sending to cloud LLM.
     * Mask sensitive PII while preserving dialogue context.
     */
    fun sanitizeConversationForCloud(items: JSONArray): JSONArray {
        val sanitized = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val copy = JSONObject(item.toString())
            if (copy.has("content")) {
                val raw = copy.optString("content")
                if (raw.isNotBlank()) {
                    var cleaned = raw
                    cleaned = PHONE_REGEX.replace(cleaned, "[PHONE_REDACTED]")
                    cleaned = EMAIL_REGEX.replace(cleaned, "[EMAIL_REDACTED]")
                    copy.put("content", cleaned)
                }
            }
            sanitized.put(copy)
        }
        return sanitized
    }
}
