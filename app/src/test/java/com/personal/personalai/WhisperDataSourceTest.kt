package com.personal.personalai

import com.personal.personalai.data.datasource.ai.WhisperDataSource
import com.personal.personalai.domain.repository.ApiUsageRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WhisperDataSourceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val okHttpClient = mockk<OkHttpClient>()
    private val apiUsageRepository = mockk<ApiUsageRepository>(relaxed = true)
    private val mockHttpCall = mockk<Call>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { okHttpClient.newCall(any()) } returns mockHttpCall
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test WhisperDataSource cancels OkHttp Call and rethrows CancellationException when coroutine is cancelled`() = runTest {
        val callbackSlot = slot<Callback>()
        val enqueueInvoked = CompletableDeferred<Unit>()

        every { mockHttpCall.enqueue(capture(callbackSlot)) } answers {
            enqueueInvoked.complete(Unit)
        }

        val tempAudio = File.createTempFile("whisper_test", ".m4a")

        val dataSource = WhisperDataSource(okHttpClient, apiUsageRepository)

        val deferred = async(Dispatchers.IO) {
            dataSource.transcribe("test-api-key", tempAudio)
        }

        enqueueInvoked.await()

        deferred.cancelAndJoin()

        verify(atLeast = 1) { mockHttpCall.cancel() }
        assertTrue(deferred.isCancelled)
        tempAudio.delete()
    }

    @Test
    fun `test WhisperDataSource parses successful JSON response properly`() = runTest {
        val callbackSlot = slot<Callback>()

        every { mockHttpCall.enqueue(capture(callbackSlot)) } answers {
            val response = Response.Builder()
                .request(Request.Builder().url("https://api.openai.com/v1/audio/transcriptions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"text": "Hello world from Whisper"}""".toResponseBody("application/json".toMediaType()))
                .build()
            callbackSlot.captured.onResponse(mockHttpCall, response)
        }

        val tempAudio = File.createTempFile("whisper_success", ".m4a")
        val dataSource = WhisperDataSource(okHttpClient, apiUsageRepository)

        val result = dataSource.transcribe("test-api-key", tempAudio)

        assertTrue(result.isSuccess)
        assertEquals("Hello world from Whisper", result.getOrThrow())
        coVerify(atLeast = 1) { apiUsageRepository.logCall(match { it.success && it.model == "whisper-1" }) }
        tempAudio.delete()
    }

    @Test
    fun `test WhisperDataSource handles HTTP error gracefully and returns failure`() = runTest {
        val callbackSlot = slot<Callback>()

        every { mockHttpCall.enqueue(capture(callbackSlot)) } answers {
            val response = Response.Builder()
                .request(Request.Builder().url("https://api.openai.com/v1/audio/transcriptions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"error": {"message": "Invalid API key provided"}}""".toResponseBody("application/json".toMediaType()))
                .build()
            callbackSlot.captured.onResponse(mockHttpCall, response)
        }

        val tempAudio = File.createTempFile("whisper_err", ".m4a")
        val dataSource = WhisperDataSource(okHttpClient, apiUsageRepository)

        val result = dataSource.transcribe("invalid-key", tempAudio)

        assertTrue(result.isFailure)
        assertEquals("Invalid API key provided", result.exceptionOrNull()?.message)
        coVerify(atLeast = 1) { apiUsageRepository.logCall(match { !it.success && it.errorMessage == "Invalid API key provided" }) }
        tempAudio.delete()
    }

    @Test
    fun `test WhisperDataSource handles network IOException gracefully`() = runTest {
        val callbackSlot = slot<Callback>()

        every { mockHttpCall.enqueue(capture(callbackSlot)) } answers {
            callbackSlot.captured.onFailure(mockHttpCall, IOException("Connection refused"))
        }

        val tempAudio = File.createTempFile("whisper_net_err", ".m4a")
        val dataSource = WhisperDataSource(okHttpClient, apiUsageRepository)

        val result = dataSource.transcribe("test-key", tempAudio)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("Connection refused", result.exceptionOrNull()?.message)
        tempAudio.delete()
    }

    @Test
    fun `test late callback after cancellation does not crash or throw double resume and closes response body`() = runTest {
        val callbackSlot = slot<Callback>()
        val enqueueInvoked = CompletableDeferred<Unit>()

        every { mockHttpCall.enqueue(capture(callbackSlot)) } answers {
            enqueueInvoked.complete(Unit)
        }

        val tempAudio = File.createTempFile("whisper_late", ".m4a")
        val dataSource = WhisperDataSource(okHttpClient, apiUsageRepository)

        val deferred = async(Dispatchers.IO) {
            dataSource.transcribe("test-api-key", tempAudio)
        }

        enqueueInvoked.await()
        deferred.cancelAndJoin()

        var responseBodyClosed = false
        val trackingBody = object : okhttp3.ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = 10L
            override fun source(): okio.BufferedSource = okio.Buffer().writeUtf8("""{"text": "Late"}""")
            override fun close() {
                responseBodyClosed = true
                super.close()
            }
        }

        // Invoke callback AFTER cancellation to simulate race condition
        val lateResponse = Response.Builder()
            .request(Request.Builder().url("https://api.openai.com/v1/audio/transcriptions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(trackingBody)
            .build()

        // Should safely close response without throwing IllegalStateException from double-resume
        callbackSlot.captured.onResponse(mockHttpCall, lateResponse)
        callbackSlot.captured.onFailure(mockHttpCall, IOException("Late error"))

        assertTrue("Coroutine must remain cancelled", deferred.isCancelled)
        assertTrue("Late response body must be closed immediately upon race condition", responseBodyClosed)
        tempAudio.delete()
    }

    @Test
    fun `test logCall rethrows CancellationException directly`() = runTest {
        val callbackSlot = slot<Callback>()

        every { mockHttpCall.enqueue(capture(callbackSlot)) } answers {
            val response = Response.Builder()
                .request(Request.Builder().url("https://api.openai.com/v1/audio/transcriptions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"text": "Hello"}""".toResponseBody("application/json".toMediaType()))
                .build()
            callbackSlot.captured.onResponse(mockHttpCall, response)
        }

        io.mockk.coEvery { apiUsageRepository.logCall(any()) } throws kotlinx.coroutines.CancellationException("Logging cancelled")

        val tempAudio = File.createTempFile("whisper_log_cancel", ".m4a")
        val dataSource = WhisperDataSource(okHttpClient, apiUsageRepository)

        var cancellationThrown = false
        try {
            dataSource.transcribe("test-key", tempAudio)
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancellationThrown = true
        }

        assertTrue("CancellationException in logCall must propagate and not be swallowed", cancellationThrown)
        tempAudio.delete()
    }
}
