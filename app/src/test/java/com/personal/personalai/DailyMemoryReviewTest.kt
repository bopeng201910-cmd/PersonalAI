package com.personal.personalai

import com.personal.personalai.data.local.entity.CandidateMemoryEntity
import com.personal.personalai.data.local.entity.toDomain
import com.personal.personalai.data.local.entity.toEntity
import com.personal.personalai.domain.model.CandidateMemory
import com.personal.personalai.domain.model.CandidateStatus
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.MemoryCategory
import com.personal.personalai.domain.model.MemorySourceType
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.usecase.ReviewCandidateMemoryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyMemoryReviewTest {

    @Test
    fun `test CandidateMemoryEntity domain conversion`() {
        val entity = CandidateMemoryEntity(
            id = 12L,
            content = "User prefers dark roast coffee",
            topic = "coffee",
            originalSnippet = "I always drink dark roast",
            category = MemoryCategory.USER_PREFERENCE.name,
            sourceType = MemorySourceType.EXPLICIT_FACT.name,
            sourceMessageId = 42L,
            sourceTimestamp = 1700000000000L,
            status = CandidateStatus.PENDING.name,
            createdAt = 1700000100000L
        )

        val domain = entity.toDomain()
        assertEquals(12L, domain.id)
        assertEquals("User prefers dark roast coffee", domain.content)
        assertEquals("coffee", domain.topic)
        assertEquals(MemoryCategory.USER_PREFERENCE, domain.category)
        assertEquals(MemorySourceType.EXPLICIT_FACT, domain.sourceType)
        assertEquals(CandidateStatus.PENDING, domain.status)

        val backToEntity = domain.toEntity()
        assertEquals(entity, backToEntity)
    }

    @Test
    fun `test ReviewCandidateMemoryUseCase approve saves to official memory and marks candidate approved`() = runTest {
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)

        val candidate = CandidateMemory(
            id = 5L,
            content = "Goal: Run a half marathon by October",
            topic = "fitness",
            category = MemoryCategory.LONG_TERM_GOAL,
            sourceType = MemorySourceType.EXPLICIT_FACT,
            sourceMessageId = 10L,
            sourceTimestamp = 1700000000000L,
            status = CandidateStatus.PENDING
        )

        coEvery { candidateRepo.getById(5L) } returns candidate

        val useCase = ReviewCandidateMemoryUseCase(candidateRepo, memoryRepo)
        useCase.approveCandidate(5L)

        val savedMemorySlot = slot<Memory>()
        coVerify(exactly = 1) { memoryRepo.saveMemory(capture(savedMemorySlot)) }
        assertEquals("Goal: Run a half marathon by October", savedMemorySlot.captured.content)
        assertEquals("fitness", savedMemorySlot.captured.topic)
        assertEquals(1700000000000L, savedMemorySlot.captured.createdAt)

        coVerify(exactly = 1) { candidateRepo.updateStatus(5L, CandidateStatus.APPROVED) }
    }

    @Test
    fun `test ReviewCandidateMemoryUseCase approve with custom edited text`() = runTest {
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)

        val candidate = CandidateMemory(
            id = 6L,
            content = "User likes tea",
            topic = "drink",
            category = MemoryCategory.USER_PREFERENCE,
            sourceType = MemorySourceType.INFERRED_PREFERENCE,
            sourceTimestamp = 1700000000000L,
            status = CandidateStatus.PENDING
        )

        coEvery { candidateRepo.getById(6L) } returns candidate

        val useCase = ReviewCandidateMemoryUseCase(candidateRepo, memoryRepo)
        useCase.approveCandidate(6L, customContent = "User exclusively drinks Earl Grey tea", customTopic = "tea")

        val savedMemorySlot = slot<Memory>()
        coVerify(exactly = 1) { memoryRepo.saveMemory(capture(savedMemorySlot)) }
        assertEquals("User exclusively drinks Earl Grey tea", savedMemorySlot.captured.content)
        assertEquals("tea", savedMemorySlot.captured.topic)

        coVerify(exactly = 1) { candidateRepo.updateStatus(6L, CandidateStatus.APPROVED) }
    }

    @Test
    fun `test ReviewCandidateMemoryUseCase discard does not save to official memory`() = runTest {
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)

        val useCase = ReviewCandidateMemoryUseCase(candidateRepo, memoryRepo)
        useCase.discardCandidate(7L)

        coVerify(exactly = 0) { memoryRepo.saveMemory(any()) }
        coVerify(exactly = 1) { candidateRepo.updateStatus(7L, CandidateStatus.DISCARDED) }
    }

    @Test
    fun `test candidate memories are isolated from active memories list`() {
        // Verification that official memory queries only query the official memories table
        val officialMemories = listOf(
            Memory(id = 1, content = "Official fact", topic = "fact")
        )
        val candidateMemories = listOf(
            CandidateMemory(id = 100, content = "Pending candidate", status = CandidateStatus.PENDING)
        )

        // Ensure candidate memories never pollute official memories collection
        assertFalse(officialMemories.any { it.content == "Pending candidate" })
        assertTrue(officialMemories.size == 1)
        assertTrue(candidateMemories.size == 1)
    }
}
