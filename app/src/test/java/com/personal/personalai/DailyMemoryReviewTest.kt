package com.personal.personalai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
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
import com.personal.personalai.domain.router.LumioTaskType
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.usecase.ExtractCandidateMemoriesUseCase
import com.personal.personalai.domain.usecase.ReviewCandidateMemoryUseCase
import com.personal.personalai.presentation.library.LibraryViewModel
import com.personal.personalai.presentation.settings.PreferencesKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DailyMemoryReviewTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    // ── P1-1 Strict JSON Tests ────────────────────────────────────────────────

    @Test
    fun `test strict JSON parser distinguishes malformed JSON from valid empty array`() {
        val useCase = ExtractCandidateMemoriesUseCase(mockk(), mockk(), mockk(), mockk())

        // 1. Valid empty array
        val emptyRes = useCase.parseCandidatesJsonStrict("[]", emptySet(), 1000L)
        assertTrue(emptyRes.isSuccess)
        assertTrue(emptyRes.getOrThrow().isEmpty())

        // 2. Valid empty array with conversational wrap
        val wrapRes = useCase.parseCandidatesJsonStrict("Here is your output: []", emptySet(), 1000L)
        assertTrue(wrapRes.isSuccess)
        assertTrue(wrapRes.getOrThrow().isEmpty())

        // 3. Malformed JSON (unclosed syntax)
        val malformed1 = useCase.parseCandidatesJsonStrict("[ { \"content\": \"test\" ", emptySet(), 1000L)
        assertTrue(malformed1.isFailure)

        // 4. No JSON array in response at all
        val noJson = useCase.parseCandidatesJsonStrict("Hello there, no memories found today.", emptySet(), 1000L)
        assertTrue(noJson.isFailure)

        // 5. Non-object elements mixed in array
        val nonObjectArray = useCase.parseCandidatesJsonStrict("""["not an object", 123]""", emptySet(), 1000L)
        assertTrue(nonObjectArray.isFailure)

        // 6. Record missing content field
        val missingContent = useCase.parseCandidatesJsonStrict("""[{"topic": "only_topic", "source_message_id": 10}]""", setOf(10L), 1000L)
        assertTrue(missingContent.isFailure)

        // 7. Content is empty string
        val emptyContent = useCase.parseCandidatesJsonStrict("""[{"content": "", "source_message_id": 10}]""", setOf(10L), 1000L)
        assertTrue(emptyContent.isFailure)

        // 8. Content is only whitespace
        val whitespaceContent = useCase.parseCandidatesJsonStrict("""[{"content": "   ", "source_message_id": 10}]""", setOf(10L), 1000L)
        assertTrue(whitespaceContent.isFailure)

        // 9. Invalid category
        val invalidCategory = useCase.parseCandidatesJsonStrict("""[{"content": "valid", "category": "INVALID_CAT", "source_message_id": 10}]""", setOf(10L), 1000L)
        assertTrue(invalidCategory.isFailure)

        // 10. Invalid source_type
        val invalidSourceType = useCase.parseCandidatesJsonStrict("""[{"content": "valid", "source_type": "UNKNOWN_SOURCE", "source_message_id": 10}]""", setOf(10L), 1000L)
        assertTrue(invalidSourceType.isFailure)
    }

    // ── P1-3 Strict source_message_id Tests ───────────────────────────────────

    @Test
    fun `test source_message_id validation rules`() {
        val useCase = ExtractCandidateMemoriesUseCase(mockk(), mockk(), mockk(), mockk())
        val validSet = setOf(101L, 102L)

        // 1. Valid and belongs to current batch: PASS
        val validRes = useCase.parseCandidatesJsonStrict(
            """[{"content": "I like running", "source_message_id": 101}]""",
            validSet,
            1000L
        )
        assertTrue(validRes.isSuccess)
        assertEquals(101L, validRes.getOrThrow()[0].sourceMessageId)

        // 2. validMessageIds is EMPTY: FAIL unconditionally
        val emptyBatchRes = useCase.parseCandidatesJsonStrict(
            """[{"content": "I like running", "source_message_id": 101}]""",
            emptySet(),
            1000L
        )
        assertTrue(emptyBatchRes.isFailure)

        // 3. Missing source_message_id: FAIL
        val missingId = useCase.parseCandidatesJsonStrict(
            """[{"content": "I like running"}]""",
            validSet,
            1000L
        )
        assertTrue(missingId.isFailure)

        // 4. source_message_id is null: FAIL
        val nullId = useCase.parseCandidatesJsonStrict(
            """[{"content": "I like running", "source_message_id": null}]""",
            validSet,
            1000L
        )
        assertTrue(nullId.isFailure)

        // 5. source_message_id is string: FAIL
        val stringId = useCase.parseCandidatesJsonStrict(
            """[{"content": "I like running", "source_message_id": "101"}]""",
            validSet,
            1000L
        )
        assertTrue(stringId.isFailure)

        // 6. source_message_id is float: FAIL
        val floatId = useCase.parseCandidatesJsonStrict(
            """[{"content": "I like running", "source_message_id": 101.5}]""",
            validSet,
            1000L
        )
        assertTrue(floatId.isFailure)

        // 7. source_message_id does NOT belong to current batch: FAIL (NEVER guessed or replaced)
        val outOfBatchId = useCase.parseCandidatesJsonStrict(
            """[{"content": "I like running", "source_message_id": 99999}]""",
            validSet,
            1000L
        )
        assertTrue(outOfBatchId.isFailure)
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase fails entirely and does not advance cursor or save when source_message_id is invalid`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 11L, content = "I like green tea", role = "USER", timestamp = 1000L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages
        // AI returns hallucinated message ID 999
        val aiJson = """[{"content": "User likes green tea", "source_message_id": 999}]"""
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } returns
                Result.success(AgentResponse.Text(aiJson))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)
        val result = useCase()

        // Entire extraction must fail
        assertTrue(result.isFailure)
        // Nothing must be saved
        coVerify(exactly = 0) { candidateRepo.saveCandidates(any()) }
        // Cursor must NOT advance
        coVerify(exactly = 0) { dataStore.updateData(any()) }
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
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } returns
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
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } returns
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

        val existingCandidate = CandidateMemory(
            id = 1L,
            content = "User name is Alice",
            topic = "name",
            sourceMessageId = 15L,
            status = CandidateStatus.PENDING
        )
        coEvery { candidateRepo.getBySourceMessageIds(listOf(15L)) } returns listOf(existingCandidate)

        val aiJson = """[{"content": "User name is Alice", "topic": "name", "source_message_id": 15}]"""
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } returns
                Result.success(AgentResponse.Text(aiJson))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)
        val result = useCase()

        assertTrue(result.isSuccess)
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
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } returns
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
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } returns
                Result.success(AgentResponse.Text(aiJson))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)
        val result = useCase()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dataStore.updateData(any()) }
    }

    // ── Concurrency and Mutex Tests ───────────────────────────────────────────

    @Test
    fun `test ExtractCandidateMemoriesUseCase serializes concurrent extraction invocations via Mutex`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>()

        // Faithful DataStore simulation executing actual transform on Preferences
        var storedPrefs: Preferences = mutablePreferencesOf(PreferencesKeys.LAST_REVIEWED_MESSAGE_ID to 10L)
        every { dataStore.data } answers { flowOf(storedPrefs) }
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            val updated = transform(storedPrefs)
            storedPrefs = updated
            updated
        }

        val messages = listOf(
            MessageEntity(id = 11L, content = "User message", role = "USER", timestamp = 1000L)
        )
        // Before cursor is updated (cursor = 10), messages after 10 contains msg 11.
        // After cursor is updated (cursor = 11), messages after 11 is empty.
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages
        coEvery { messageDao.getMessagesAfterId(11L) } returns emptyList()
        coEvery { candidateRepo.getBySourceMessageIds(any()) } returns emptyList()

        // Synchronization barrier to deterministically hold Job 1 inside the Mutex critical section
        val job1EnteredCriticalSection = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job1AllowFinish = kotlinx.coroutines.CompletableDeferred<Unit>()

        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } coAnswers {
            job1EnteredCriticalSection.complete(Unit)
            job1AllowFinish.await()
            Result.success(AgentResponse.Text("""[{"content": "fact", "source_message_id": 11}]"""))
        }

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)

        // Launch first extraction in a concurrent coroutine
        val job1 = async { useCase() }

        // Wait until Job 1 is inside the critical section (holding the Mutex)
        job1EnteredCriticalSection.await()

        // Launch second extraction concurrently while Job 1 is still inside the critical section
        val job2 = async { useCase() }

        // Advance dispatcher to give job2 a chance to run up to the Mutex lock
        testScheduler.advanceUntilIdle()

        // Prove that Job 2 is suspended waiting for the Mutex and cannot complete while Job 1 holds the lock
        assertFalse("Job 1 must still be running inside critical section", job1.isCompleted)
        assertFalse("Job 2 must be blocked waiting for Mutex", job2.isCompleted)

        // Now allow Job 1 to finish its AI call and release the Mutex
        job1AllowFinish.complete(Unit)
        testScheduler.advanceUntilIdle()

        // Both jobs should now be completed
        assertTrue("Job 1 should have completed successfully", job1.isCompleted)
        assertTrue("Job 2 should have completed successfully", job2.isCompleted)

        val res1 = job1.await()
        val res2 = job2.await()

        assertTrue(res1.isSuccess)
        assertEquals("Job 1 should have extracted 1 candidate", 1, res1.getOrThrow())

        assertTrue(res2.isSuccess)
        assertEquals("Job 2 should observe updated cursor and extract 0 candidates", 0, res2.getOrThrow())

        // Verify cursor in DataStore was actually updated to 11 via dataStore.edit
        assertEquals(11L, storedPrefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID])

        // Verify that candidates were only saved once (no duplicates created by the concurrent invocation)
        coVerify(exactly = 1) { candidateRepo.saveCandidates(any()) }
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase concurrent extraction handles new messages arriving during execution`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>()

        // Faithful DataStore simulation executing actual transform on Preferences
        var storedPrefs: Preferences = mutablePreferencesOf(PreferencesKeys.LAST_REVIEWED_MESSAGE_ID to 10L)
        every { dataStore.data } answers { flowOf(storedPrefs) }
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            val updated = transform(storedPrefs)
            storedPrefs = updated
            updated
        }

        val msg11 = MessageEntity(id = 11L, content = "User likes tea", role = "USER", timestamp = 1000L)
        val msg12 = MessageEntity(id = 12L, content = "User likes coffee", role = "USER", timestamp = 2000L)

        // 1. Initially, only msg11 exists in the simulated message database
        val inMemoryMessages = mutableListOf(msg11)
        coEvery { messageDao.getMessagesAfterId(any()) } answers {
            val afterId = firstArg<Long>()
            inMemoryMessages.filter { it.id > afterId }
        }

        coEvery { candidateRepo.getBySourceMessageIds(any()) } returns emptyList()

        val savedCandidatesList = mutableListOf<CandidateMemory>()
        coEvery { candidateRepo.saveCandidates(any()) } coAnswers {
            val list = firstArg<List<CandidateMemory>>()
            savedCandidatesList.addAll(list)
            list.mapIndexed { idx, _ -> idx.toLong() + 1L }
        }

        val job1Entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job1Proceed = kotlinx.coroutines.CompletableDeferred<Unit>()

        // AI Mock for batch with msg 11
        coEvery {
            aiRepository.sendMessageWithTools(
                conversationItems = match { it.toString().contains("User likes tea") },
                any(), any(), any(), any()
            )
        } coAnswers {
            job1Entered.complete(Unit)
            job1Proceed.await()
            Result.success(AgentResponse.Text("""[{"content": "Likes tea", "source_message_id": 11}]"""))
        }

        // AI Mock for batch with msg 12
        coEvery {
            aiRepository.sendMessageWithTools(
                conversationItems = match { it.toString().contains("User likes coffee") },
                any(), any(), any(), any()
            )
        } returns Result.success(AgentResponse.Text("""[{"content": "Likes coffee", "source_message_id": 12}]"""))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)

        // 2. Launch Job 1 and wait until its AI call is suspended inside the Mutex critical section
        val job1 = async { useCase() }
        job1Entered.await()

        // 3. While Job 1 is suspended, dynamically insert message 12 into the database.
        // It was NOT preconfigured or available when Job 1 queried or started.
        inMemoryMessages.add(msg12)

        // 4. Launch Job 2 while Job 1 still holds the Mutex
        val job2 = async { useCase() }
        testScheduler.advanceUntilIdle()

        // 5. Verify Job 2 cannot complete while Job 1 is holding the Mutex
        assertFalse("Job 1 is running inside critical section", job1.isCompleted)
        assertFalse("Job 2 is suspended waiting for Mutex", job2.isCompleted)

        // 6. Release Job 1. Production code persists cursor 11 through DataStore
        job1Proceed.complete(Unit)
        testScheduler.advanceUntilIdle()

        // 7. Job 2 acquires Mutex, queries messages after 11, finds message 12, and completes
        assertTrue("Job 1 completed", job1.isCompleted)
        assertTrue("Job 2 completed", job2.isCompleted)

        val res1 = job1.await()
        val res2 = job2.await()

        assertTrue(res1.isSuccess)
        assertEquals(1, res1.getOrThrow())
        assertTrue(res2.isSuccess)
        assertEquals(1, res2.getOrThrow())

        // 8. Verify the final persisted cursor is 12
        assertEquals(12L, storedPrefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID])

        // 9. Verify exactly two distinct candidates were saved with correct contents and sourceMessageIds
        assertEquals("Exactly two candidate memories must be saved", 2, savedCandidatesList.size)

        val candidate1 = savedCandidatesList.first { it.sourceMessageId == 11L }
        assertEquals("Likes tea", candidate1.content)
        assertEquals(11L, candidate1.sourceMessageId)

        val candidate2 = savedCandidatesList.first { it.sourceMessageId == 12L }
        assertEquals("Likes coffee", candidate2.content)
        assertEquals(12L, candidate2.sourceMessageId)

        // 10. Verify there are no duplicate saves and neither message is lost
        assertEquals(2, savedCandidatesList.map { it.sourceMessageId }.distinct().size)

        // 11. Verify exact chronological query sequence and cursor arguments
        coVerify(exactly = 1) { messageDao.getMessagesAfterId(10L) }
        coVerify(exactly = 1) { messageDao.getMessagesAfterId(11L) }

        coVerifyOrder {
            // Job 1 queries DB with initial cursor 10
            messageDao.getMessagesAfterId(10L)
            // Job 1 saves candidate from msg 11
            candidateRepo.saveCandidates(match { it.any { c -> c.sourceMessageId == 11L } })
            // Job 1 persists cursor 11
            dataStore.updateData(any())
            // Job 2 queries DB with updated cursor 11, discovering msg 12
            messageDao.getMessagesAfterId(11L)
            // Job 2 saves candidate from msg 12
            candidateRepo.saveCandidates(match { it.any { c -> c.sourceMessageId == 12L } })
            // Job 2 persists cursor 12
            dataStore.updateData(any())
        }
    }

    // ── Cancellation and Exception Propagation Tests for ExtractCandidateMemoriesUseCase ──

    @Test
    fun `test ExtractCandidateMemoriesUseCase directly rethrows CancellationException thrown by dependency`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>()

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 11L, content = "User statement", role = "USER", timestamp = 1000L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages

        // 1. Dependency throws CancellationException directly while parent coroutine is active
        val thrownCancellation = kotlinx.coroutines.CancellationException("Direct cancellation from AI dependency")
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } throws thrownCancellation

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)

        var cancellationPropagated: Throwable? = null
        var returnedResult: Result<Int>? = null

        // 2. Launch real use case in controlled child coroutine
        val childJob = launch {
            try {
                returnedResult = useCase()
            } catch (e: Throwable) {
                cancellationPropagated = e
                throw e
            }
        }

        testScheduler.advanceUntilIdle()

        // 3. Assert parent remains active, child completes as cancelled, and cancellation was rethrown
        assertTrue("Child coroutine must complete as cancelled", childJob.isCancelled)
        assertNotNull("CancellationException must be captured when propagated", cancellationPropagated)
        assertTrue("Propagated exception must be CancellationException", cancellationPropagated is kotlinx.coroutines.CancellationException)
        assertEquals("Direct cancellation from AI dependency", cancellationPropagated?.message)
        assertNull("Cancellation must never be returned as Result.failure", returnedResult)
        coVerify(exactly = 0) { candidateRepo.saveCandidates(any()) }
        coVerify(exactly = 0) { dataStore.updateData(any()) }
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase rethrows CancellationException and terminates coroutine cleanly`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>()

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 11L, content = "User statement", role = "USER", timestamp = 1000L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages

        val aiCallStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val postCancelCheck = kotlinx.coroutines.CompletableDeferred<Unit>()

        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } coAnswers {
            aiCallStarted.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
            postCancelCheck.complete(Unit)
            Result.success(AgentResponse.Text("""[{"content": "fact", "source_message_id": 11}]"""))
        }

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)

        var cancellationPropagated = false
        var resultFailureReturned = false

        val job = backgroundScope.launch {
            try {
                val res = useCase()
                if (res.isFailure) {
                    resultFailureReturned = true
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) {
                    cancellationPropagated = true
                }
                throw e
            }
        }

        testScheduler.advanceUntilIdle()
        aiCallStarted.await()

        job.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue("CancellationException must be rethrown and not converted into Result.failure", cancellationPropagated || job.isCancelled)
        assertFalse("CancellationException must NOT be swallowed or returned as Result.failure", resultFailureReturned)
        assertTrue("Coroutine job must be marked cancelled", job.isCancelled)
        assertFalse("No subsequent operations should continue after cancellation", postCancelCheck.isCompleted)
        coVerify(exactly = 0) { candidateRepo.saveCandidates(any()) }
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase returns Result failure on ordinary exception without crashing`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>()

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 11L, content = "User statement", role = "USER", timestamp = 1000L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } returns
                Result.failure(java.io.IOException("Socket timeout"))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)

        val result = useCase()

        assertTrue("Ordinary exception must be converted into Result.failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
        coVerify(exactly = 0) { candidateRepo.saveCandidates(any()) }
    }

    @Test
    fun `test ExtractCandidateMemoriesUseCase returns Result success with correct candidate count`() = runTest {
        val messageDao = mockk<MessageDao>()
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val aiRepository = mockk<AiRepository>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] } returns 10L
        every { dataStore.data } returns flowOf(prefs)

        val messages = listOf(
            MessageEntity(id = 11L, content = "I like matcha", role = "USER", timestamp = 1000L)
        )
        coEvery { messageDao.getMessagesAfterId(10L) } returns messages
        coEvery { candidateRepo.getBySourceMessageIds(any()) } returns emptyList()
        coEvery { aiRepository.sendMessageWithTools(any(), any(), any(), any(), any()) } returns
                Result.success(AgentResponse.Text("""[{"content": "Likes matcha", "source_message_id": 11}]"""))

        val useCase = ExtractCandidateMemoriesUseCase(messageDao, candidateRepo, aiRepository, dataStore)

        val result = useCase()

        assertTrue("Successful extraction must return Result.success", result.isSuccess)
        assertEquals("Expected extraction count must be 1", 1, result.getOrThrow())
        coVerify(exactly = 1) { candidateRepo.saveCandidates(any()) }
    }

    // ── P1-2 LibraryViewModel Approval and Feedback Tests ─────────────────────

    @Test
    fun `test LibraryViewModel approveCandidate closes edit dialog and shows success on transaction success`() = runTest {
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val extractUseCase = mockk<ExtractCandidateMemoriesUseCase>(relaxed = true)
        val reviewUseCase = mockk<ReviewCandidateMemoryUseCase>()

        val candidate = CandidateMemory(id = 10L, content = "Old content", topic = "topic")
        coEvery {
            reviewUseCase.approveCandidate(10L, "New edited content", "new_topic")
        } returns Result.success(true)

        val viewModel = LibraryViewModel(
            mockk(relaxed = true), memoryRepo, candidateRepo, extractUseCase, reviewUseCase
        )

        // User starts editing
        viewModel.startEditingCandidate(candidate)
        assertNotNull(viewModel.uiState.value.editingCandidate)

        // User saves successfully
        viewModel.approveCandidate(candidate, "New edited content", "new_topic")
        testDispatcher.scheduler.advanceUntilIdle()

        // Dialog should be dismissed, success message displayed, no error
        assertNull(viewModel.uiState.value.editingCandidate)
        assertNull(viewModel.uiState.value.error)
        assertEquals("Memory approved and saved.", viewModel.uiState.value.infoMessage)
    }

    @Test
    fun `test LibraryViewModel approveCandidate keeps dialog open, preserves edited content, and surfaces error on failure`() = runTest {
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)
        val candidateRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val extractUseCase = mockk<ExtractCandidateMemoriesUseCase>(relaxed = true)
        val reviewUseCase = mockk<ReviewCandidateMemoryUseCase>()

        val candidate = CandidateMemory(id = 10L, content = "Initial content", topic = "initial_topic")
        coEvery {
            reviewUseCase.approveCandidate(10L, "User edited content", "edited_topic")
        } returns Result.failure(RuntimeException("Database lock error"))

        val viewModel = LibraryViewModel(
            mockk(relaxed = true), memoryRepo, candidateRepo, extractUseCase, reviewUseCase
        )

        // User starts editing
        viewModel.startEditingCandidate(candidate)

        // User saves but transaction fails
        viewModel.approveCandidate(candidate, "User edited content", "edited_topic")
        testDispatcher.scheduler.advanceUntilIdle()

        // Dialog must REMAIN open
        assertNotNull(viewModel.uiState.value.editingCandidate)
        // User's edited content must be PRESERVED
        assertEquals("User edited content", viewModel.uiState.value.editingCandidate?.content)
        assertEquals("edited_topic", viewModel.uiState.value.editingCandidate?.topic)
        // Error message must be surfaced
        assertEquals("Database lock error", viewModel.uiState.value.error)
    }
}
