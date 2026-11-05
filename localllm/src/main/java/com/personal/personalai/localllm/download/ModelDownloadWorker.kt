package com.personal.personalai.localllm.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personal.personalai.localllm.api.LocalModel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkManager worker that downloads a LiteRT model file from HuggingFace.
 *
 * - Downloads to a `.tmp` file, then atomically renames on success.
 * - Deletes the `.tmp` file if the download fails or is cancelled.
 * - Reports progress every 128 KB via [setProgress].
 * - Supports optional HuggingFace Bearer token for gated model access.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MODEL_ID       = "model_id"
        const val KEY_HF_TOKEN       = "hf_token"
        const val PROGRESS_PERCENT   = "progress_percent"
        const val PROGRESS_BYTES_DL  = "bytes_downloaded"
        const val PROGRESS_BYTES_TOT = "bytes_total"
        const val KEY_ERROR          = "error_message"

        private const val BUFFER_SIZE = 128 * 1024  // 128 KB

        fun downloadUrl(repo: String, fileName: String) =
            "https://huggingface.co/$repo/resolve/main/$fileName"
    }

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing model_id input"))
        val hfToken = inputData.getString(KEY_HF_TOKEN) ?: ""

        val model = LocalModel.fromId(modelId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Unknown model ID: $modelId"))

        val destFile = ModelDownloadManager.modelFile(applicationContext, model)
        val tmpFile  = File(destFile.parent, "${destFile.name}.tmp")
        destFile.parentFile?.mkdirs()

        // 1. Storage validation: Ensure at least model size + 500MB safety buffer is available
        val freeSpace = destFile.parentFile?.usableSpace ?: 0L
        val requiredSpace = model.approximateSizeBytes + (500L * 1024L * 1024L)
        if (freeSpace < requiredSpace) {
            return Result.failure(
                workDataOf(KEY_ERROR to "Insufficient storage space. Need at least ${(requiredSpace / (1024 * 1024))} MB.")
            )
        }

        return try {
            downloadFile(
                urlStr        = downloadUrl(model.huggingFaceRepo, model.fileName),
                hfToken       = hfToken,
                dest          = tmpFile,
                expectedBytes = model.approximateSizeBytes
            )

            // 2. File size validation: Reject incomplete downloads
            if (tmpFile.length() < (model.approximateSizeBytes / 2)) {
                tmpFile.delete()
                return Result.failure(workDataOf(KEY_ERROR to "Downloaded file size is corrupted or truncated."))
            }

            // 3. Atomic rename only after verification passes
            tmpFile.renameTo(destFile)
            Result.success()
        } catch (e: Exception) {
            tmpFile.delete()
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    private suspend fun downloadFile(
        urlStr: String,
        hfToken: String,
        dest: File,
        expectedBytes: Long
    ) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            connectTimeout = 30_000
            readTimeout    = 120_000
            setRequestProperty("User-Agent", "PersonalAI-Android")
            if (hfToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $hfToken")
            instanceFollowRedirects = true
            connect()
        }

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}")
        }

        val totalBytes = if (conn.contentLengthLong > 0L) conn.contentLengthLong else expectedBytes
        var downloaded = 0L
        val buffer     = ByteArray(BUFFER_SIZE)

        conn.inputStream.use { input ->
            dest.outputStream().buffered().use { output ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isStopped) {
                        dest.delete()
                        return
                    }
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead

                    val percent = if (totalBytes > 0L) {
                        ((downloaded * 100L) / totalBytes).toInt().coerceAtMost(99)
                    } else 0

                    setProgress(
                        workDataOf(
                            PROGRESS_PERCENT   to percent,
                            PROGRESS_BYTES_DL  to downloaded,
                            PROGRESS_BYTES_TOT to totalBytes
                        )
                    )
                }
            }
        }
    }
}
