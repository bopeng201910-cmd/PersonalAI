package com.personal.personalai

import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.ProactiveCheckInConfig
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.usecase.GenerateProactiveConversationUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ProactiveConversationTest {

    private val router = mockk<com.personal.personalai.domain.router.LumioAiRouter>(relaxed = true)

    @Test
    fun `test disabled config produces no proactive conversation`() = runTest {
        val memoryRepo = mockk<MemoryRepository>()
        val useCase = GenerateProactiveConversationUseCase(memoryRepo, router)

        val config = ProactiveCheckInConfig(isEnabled = false)
        val result = useCase(config, LocalTime.of(9, 30))

        assertNull(result)
    }

    @Test
    fun `test quiet hours prevent proactive conversation delivery`() = runTest {
        val memoryRepo = mockk<MemoryRepository>()
        val useCase = GenerateProactiveConversationUseCase(memoryRepo, router)

        val config = ProactiveCheckInConfig(
            isEnabled = true,
            quietHoursStart = "22:00",
            quietHoursEnd = "07:00"
        )

        // Test during quiet hours (e.g. 23:30 and 05:00)
        assertNull(useCase(config, LocalTime.of(23, 30)))
        assertNull(useCase(config, LocalTime.of(5, 0)))
    }

    @Test
    fun `test morning proactive conversation incorporates personal memory follow up`() = runTest {
        val memoryRepo = mockk<MemoryRepository>()
        val memories = listOf(
            Memory(id = 1, content = "User is preparing for a marathon", topic = "marathon", createdAt = 1000L)
        )
        coEvery { memoryRepo.getMemories() } returns flowOf(memories)

        val useCase = GenerateProactiveConversationUseCase(memoryRepo, router)
        val config = ProactiveCheckInConfig(isEnabled = true)

        val result = useCase(config, LocalTime.of(8, 30))

        assertNotNull(result)
        assertTrue(result!!.contains("Good morning!"))
        assertTrue(result.contains("User is preparing for a marathon"))
    }
}
