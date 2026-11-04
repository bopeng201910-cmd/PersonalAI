package com.personal.personalai

import com.personal.personalai.data.local.PersonalAIDatabase
import com.personal.personalai.data.local.dao.CandidateMemoryDao
import com.personal.personalai.data.local.dao.MemoryDao
import com.personal.personalai.data.local.entity.CandidateMemoryEntity
import com.personal.personalai.data.repository.CandidateMemoryRepositoryImpl
import com.personal.personalai.domain.model.CandidateStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CandidateMemoryRepositoryTransactionTest {

    private val database = mockk<PersonalAIDatabase>(relaxed = true)
    private val candidateMemoryDao = mockk<CandidateMemoryDao>()
    private val memoryDao = mockk<MemoryDao>()
    private lateinit var repository: CandidateMemoryRepositoryImpl

    @Before
    fun setUp() {
        every { database.transactionExecutor } returns java.util.concurrent.Executor { it.run() }
        repository = CandidateMemoryRepositoryImpl(database, candidateMemoryDao, memoryDao)
    }

    @Test
    fun `test approveCandidateInTransaction rethrows CancellationException directly`() {
        coEvery { candidateMemoryDao.getById(1L) } throws CancellationException("Coroutine was cancelled")

        var exceptionThrown = false
        var failureReturned = false

        runBlocking {
            try {
                val result = repository.approveCandidateInTransaction(1L, null, null)
                if (result.isFailure) {
                    failureReturned = true
                }
            } catch (e: CancellationException) {
                exceptionThrown = true
                assertEquals("Coroutine was cancelled", e.message)
            }
        }

        assertTrue("CancellationException must be rethrown and escape to caller", exceptionThrown)
        assertFalse("CancellationException must never be converted to Result.failure", failureReturned)
    }

    @Test
    fun `test approveCandidateInTransaction returns failure on ordinary database exception`() = runBlocking {
        coEvery { candidateMemoryDao.getById(2L) } throws java.io.IOException("Disk IO error")

        val result = repository.approveCandidateInTransaction(2L, null, null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
        assertEquals("Disk IO error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `test approveCandidateInTransaction rejects non-PENDING candidate and avoids duplicate memory insertion`() = runBlocking {
        val nonPendingCandidate = CandidateMemoryEntity(
            id = 3L,
            content = "Already approved memory",
            topic = "topic",
            originalSnippet = "snippet",
            category = "USER_PREFERENCE",
            sourceType = "EXPLICIT_FACT",
            sourceMessageId = 100L,
            sourceTimestamp = 1000L,
            status = CandidateStatus.APPROVED.name,
            createdAt = 1000L
        )

        coEvery { candidateMemoryDao.getById(3L) } returns nonPendingCandidate

        val result = repository.approveCandidateInTransaction(3L, null, null)

        assertTrue(result.isSuccess)
        assertFalse("Approval must return false for non-PENDING candidate", result.getOrThrow())
        coVerify(exactly = 0) { memoryDao.insertMemory(any()) }
        coVerify(exactly = 0) { candidateMemoryDao.updateStatus(any(), any()) }
    }
}
