package com.personal.personalai.data.datasource.ai

import android.media.MediaMetadataRetriever
import com.personal.personalai.data.datasource.ai.usage.OpenAiPricing
import com.personal.personalai.domain.model.ApiUsageLog
import com.personal.personalai.domain.repository.ApiUsageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles audio transcription via the OpenAI Whisper API.
 *
 * Sends a multipart POST to `/v1/audio/transcriptions` and returns the
 * transcribed text. Uses the same [OkHttpClient] as [OpenAiDataSource] —
 * no additional networking dependency is required.
 *
 * Each call is logged to [ApiUsageRepository]; cost is estimated from the
 * audio file's duration ($0.006 / minute).
 */
class WhisperDataSource @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val apiUsageRepository: ApiUsageRepository,
) {

    companion object {
        private const val WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val MODEL = "whisper-1"
        private val MEDIA_TYPE_AUDIO = "audio/mp4".toMediaType()
    }

    /**
     * Transcribes [audioFile] using the given [apiKey].
     * @return [Result.success] containing the transcribed text, or
     *         [Result.failure] with a descriptive error message.
     */
    suspend fun transcribe(apiKey: String, audioFile: File): Result<String> =
        withContext(Dispatchers.IO) {
            val durationSeconds = readDurationSeconds(audioFile)
            val start = System.currentTimeMillis()
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        name = "file",
                        filename = audioFile.name,
                        body = audioFile.asRequestBody(MEDIA_TYPE_AUDIO)
                    )
                    .addFormDataPart("model", MODEL)
                    .build()

                val request = Request.Builder()
                    .url(WHISPER_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                val call = okHttpClient.newCall(request)
                val response = suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation {
                        call.cancel()
                    }
                    val isHandled = AtomicBoolean(false)
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (isHandled.compareAndSet(false, true)) {
                                continuation.resumeWithException(e)
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (isHandled.compareAndSet(false, true)) {
                                continuation.resume(response) {
                                    response.close()
                                }
                            } else {
                                response.close()
                            }
                        }
                    })
                }

                val latencyMs = System.currentTimeMillis() - start
                response.use { resp ->
                    val responseBody = resp.body?.string()
                    if (responseBody == null) {
                        logCall(durationSeconds, latencyMs, success = false, error = "Empty response from Whisper")
                        return@withContext Result.failure(Exception("Empty response from Whisper"))
                    }

                    if (!resp.isSuccessful) {
                        val errorMsg = runCatching {
                            JSONObject(responseBody).getJSONObject("error").getString("message")
                        }.getOrDefault("Whisper error: HTTP ${resp.code}")
                        logCall(durationSeconds, latencyMs, success = false, error = errorMsg)
                        return@withContext Result.failure(Exception(errorMsg))
                    }

                    val text = JSONObject(responseBody).getString("text").trim()
                    logCall(durationSeconds, latencyMs, success = true, error = null)
                    Result.success(text)
                }
            } catch (cancellation: CancellationException) {
                val latencyMs = System.currentTimeMillis() - start
                logCall(durationSeconds, latencyMs, success = false, error = "Transcription cancelled")
                throw cancellation
            } catch (e: Exception) {
                val latencyMs = System.currentTimeMillis() - start
                logCall(durationSeconds, latencyMs, success = false, error = e.message ?: e.javaClass.simpleName)
                Result.failure(e)
            }
        }

    private suspend fun logCall(
        durationSeconds: Double?,
        latencyMs: Long,
        success: Boolean,
        error: String?,
    ) {
        try {
            val cost = if (success && durationSeconds != null) {
                OpenAiPricing.estimateWhisperCostUsd(durationSeconds)
            } else 0.0
            apiUsageRepository.logCall(
                ApiUsageLog(
                    timestamp = System.currentTimeMillis(),
                    provider = "openai",
                    model = MODEL,
                    apiType = "transcription",
                    audioDurationSeconds = durationSeconds,
                    estimatedCostUsd = cost,
                    latencyMs = latencyMs,
                    success = success,
                    errorMessage = error,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ordinary telemetry failures must not break the primary transcription operation.
        }
    }

    private fun readDurationSeconds(audioFile: File): Double? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(audioFile.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ms?.let { it / 1000.0 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }
}
