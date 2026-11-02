package com.personal.personalai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import com.personal.personalai.data.local.dao.CandidateMemoryDao
import com.personal.personalai.data.local.dao.MessageDao
import com.personal.personalai.data.local.entity.CandidateMemoryEntity
import com.personal.personalai.data.local.entity.MessageEntity
import com.personal.personalai.data.local.entity.toDomain
import com.personal.personalai.data.local.entity.toEntity
import com.personal.personalai.domain.model.CandidateMemory
import com.personal.personalai.domain.model.CandidateStatus
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.MemoryCategory
import com.personal.personalai.domain.model.MemorySourceType
import com.personal.personalai.domain.repository.AiRepository
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.usecase.ExtractCandidateMemoriesUseCase
import com.personal.personalai.domain.usecase.ReviewCandidateMemoryUseCase
import com.personal.personalai.presentation.settings.PreferencesKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
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
    fun `test ReviewCandidateMemoryUseCase approve delegates to repository transaction`() = runTest {
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        coEvery {
            candidateRepo.approveCandidateInTransaction(5L, null, null)
        } returns Result.success(true)

        val useCase = ReviewCandidateMemoryUseCase(candidateRepo)
        val res = useCase.approveCandidate(5L)

        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow())
        coVerify(exactly = 1) { candidateRepo.approveCandidateInTransaction(5L, null, null) }
    }

    @Test
    fun `test ReviewCandidateMemoryUseCase approve with custom edited text`() = runTest {
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        coEvery {
            candidateRepo.approveCandidateInTransaction(6L, "User exclusively drinks Earl Grey tea", "tea")
        } returns Result.success(true)

        val useCase = ReviewCandidateMemoryUseCase(candidateRepo)
        val res = useCase.approveCandidate(6L, customContent = "User exclusively drinks Earl Grey tea", customTopic = "tea")

        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow())
        coVerify(exactly = 1) {
            candidateRepo.approveCandidateInTransaction(6L, "User exclusively drinks Earl Grey tea", "tea")
        }
    }

    @Test
    fun `test ReviewCandidateMemoryUseCase prevents duplicate save on non-PENDING candidate`() = runTest {
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        // Returns false because candidate is already APPROVED or DISCARDED
        coEvery {
            candidateRepo.approveCandidateInTransaction(7L, any(), any())
        } returns Result.success(false)

        val useCase = ReviewCandidateMemoryUseCase(candidateRepo)
        val res = useCase.approveCandidate(7L)

        assertTrue(res.isSuccess)
        assertFalse(res.getOrThrow())
    }

    @Test
    fun `test ReviewCandidateMemoryUseCase batch approval handles partial failure properly`() = runTest {
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        coEvery { candidateRepo.approveCandidateInTransaction(1L, null, null) } returns Result.success(true)
        coEvery { candidateRepo.approveCandidateInTransaction(2L, null, null) } returns Result.failure(RuntimeException("DB Lock"))
        coEvery { candidateRepo.approveCandidateInTransaction(3L, null, null) } returns Result.success(true)

        val useCase = ReviewCandidateMemoryUseCase(candidateRepo)
        val batchRes = useCase.approveAll(listOf(1L, 2L, 3L))

        assertEquals(2, batchRes.succeededCount)
        assertEquals(1, batchRes.failedCount)
        assertEquals(listOf(2L), batchRes.failedIds)
    }

    @Test
    fun `test candidate memories are isolated from active memories list`() {
        val officialMemories = listOf(
            Memory(id = 1, content = "Official fact", topic = "fact")
        )
        val candidateMemories = listOf(
            CandidateMemory(id = 100, content = "Pending candidate", status = CandidateStatus.PENDING)
        )

        assertFalse(officialMemories.any { it.content == "Pending candidate" })
        assertEquals(1, officialMemories.size)
        assertEquals(1, candidateMemories.size)
    }

    @Test
    fun `test strict JSON parser distinguishes malformed JSON from valid empty array`() {
        val useCase = ExtractCandidateMemoriesUseCase(mockk(), mockk(), mockk(), mockk())

        // 1. Valid empty array
        val emptyRes = useCase.parseCandidatesJsonStrict("[]", 1L, 1000L)
        assertTrue(emptyRes.isSuccess)
        assertTrue(emptyRes.getOrThrow().isEmpty())

        // 2. Valid empty array with whitespace and conversational wrap
        val wrapRes = useCase.parseCandidatesJsonStrict("Here is your output: []", 1L, 1000L)
        assertTrue(wrapRes.isSuccess)
        assertTrue(wrapRes.getOrThrow().isEmpty())

        // 3. Malformed JSON (unclosed bracket or invalid syntax)
        val malformed1 = useCase.parseCandidatesJsonStrict("[ { \"content\": \"test\" ", 1L, 1000L)
        assertTrue(malformed1.isFailure)

        // 4. No JSON array in response at all
        val noJson = useCase.parseCandidatesJsonStrict("Hello there, no memories found today.", 1L, 1000L)
        assertTrue(noJson.isFailure)
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase does NOT advance cursor on malformed JSON`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>()

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 11L, content = "I like matcha tea", role = "USER", timestamp = 1000L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any()) } returns
                Result.success(AgentResponse.Text("This is not valid JSON!"))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)
        val result = useCase()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { candidateRepo.saveCandidates(any()) }
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase advances cursor on valid empty array`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 11L, content = "Good morning!", role = "USER", timestamp = 1000L),
            MessageEntity(id = 12L, content = "Good morning! How can I help?", role = "ASSISTANT", timestamp = 1005L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any()) } returns
                Result.success(AgentResponse.Text("[]"))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)
        val result = useCase()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow())
        coVerify(exactly = 1) { dataStore.updateData(any()) }
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase deduplicates against existing candidate records`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 15L, content = "My name is Alice", role = "USER", timestamp = 1000L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages

        // Existing candidate in database for message 15
        val existingCandidate = CandidateMemory(
            id = 1L,
            content = "User name is Alice",
            topic = "name",
            sourceMessageId = 15L,
            status = CandidateStatus.PENDING
        )
        coEvery { candidateRepo.getBySourceMessageIds(listOf(15L)) } returns listOf(existingCandidate)

        // AI returns identical extraction result
        val aiJson = """[{"content": "User name is Alice", "topic": "name", "source_message_id": 15}]"""
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any()) } returns
                Result.success(AgentResponse.Text(aiJson))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)
        val result = useCase()

        assertTrue(result.isSuccess)
        // Since it's a duplicate, saveCandidates should not re-insert the existing item
        coVerify(exactly = 0) { candidateRepo.saveCandidates(any()) }
        coVerify(exactly = 1) { dataStore.updateData(any()) }
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase does NOT advance cursor when AI call throws network error`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 11L, content = "Offline conversation", role = "USER", timestamp = 1000L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any()) } returns
                Result.failure(java.net.ConnectException("Network unreachable or local server down"))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)
        val result = useCase()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { candidateRepo.saveCandidates(any()) }
        coVerify(exactly = 0) { dataStore.updateData(any()) }
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase does NOT advance cursor when candidate save throws DB error`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>()
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 11L, content = "User likes apples", role = "USER", timestamp = 1000L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages
        coEvery { candidateRepo.getBySourceMessageIds(any()) } returns emptyList()
        coEvery { candidateRepo.saveCandidates(any()) } throws android.database.sqlite.SQLiteFullException("Disk full")

        val aiJson = """[{"content": "User likes apples", "topic": "fruit", "source_message_id": 11}]"""
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any()) } returns
                Result.success(AgentResponse.Text(aiJson))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)
        val result = useCase()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dataStore.updateData(any()) }
    }
}
