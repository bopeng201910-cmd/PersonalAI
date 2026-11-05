package com.personal.personalai.domain.usecase

import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.ProactiveCheckInConfig
import com.personal.personalai.domain.repository.MemoryRepository
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import javax.inject.Inject

class GenerateProactiveConversationUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository
) {
    suspend operator fun invoke(config: ProactiveCheckInConfig, currentTime: LocalTime = LocalTime.now()): String? {
        if (!config.isEnabled) return null

        // Check quiet hours
        val startQuiet = runCatching { LocalTime.parse(config.quietHoursStart) }.getOrDefault(LocalTime.of(22, 0))
        val endQuiet = runCatching { LocalTime.parse(config.quietHoursEnd) }.getOrDefault(LocalTime.of(7, 0))

        val inQuietHours = if (startQuiet.isBefore(endQuiet)) {
            currentTime.isAfter(startQuiet) && currentTime.isBefore(endQuiet)
        } else {
            // Over midnight (e.g. 22:00 to 07:00)
            currentTime.isAfter(startQuiet) || currentTime.isBefore(endQuiet)
        }

        if (inQuietHours) return null

        // Fetch recent personal memories to produce context-aware follow-up
        val memories: List<Memory> = memoryRepository.getMemories().first()
        val recentMemory = memories.firstOrNull { it.topic.isNotBlank() }

        val isMorning = currentTime.hour in 5..11
        val isEvening = currentTime.hour in 17..22

        return when {
            recentMemory != null && isMorning -> {
                "Good morning! I remembered that you mentioned \"${recentMemory.content}\". How is that going today?"
            }
            recentMemory != null && isEvening -> {
                "Good evening! Checking in regarding your note on \"${recentMemory.topic}\". How did everything turn out?"
            }
            isMorning -> {
                "Good morning! I'm here if you'd like to plan your day or discuss anything on your mind."
            }
            isEvening -> {
                "Good evening! How was your day? Let me know if you want to reflect or organize tomorrow's schedule."
            }
            else -> {
                "Hello! Just checking in to see if there's anything you'd like to talk through or record."
            }
        }
    }
}
