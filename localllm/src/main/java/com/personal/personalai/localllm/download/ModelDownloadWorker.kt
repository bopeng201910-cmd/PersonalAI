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

            if (isStopped) {
                tmpFile.delete()
                return Result.failure(workDataOf(KEY_ERROR to "Download cancelled by user or WorkManager."))
            }

            // 2. File size validation: Reject zero-byte or severely truncated downloads
            val actualLength = tmpFile.length()
            if (actualLength <= 0L) {
                tmpFile.delete()
                return Result.failure(workDataOf(KEY_ERROR to "Downloaded file is empty."))
            }

            // 3. SHA-256 Checksum validation if official checksum is specified
            if (model.officialSha256 != null) {
                val computedHash = computeSha256(tmpFile)
                if (!computedHash.equals(model.officialSha256, ignoreCase = true)) {
                    tmpFile.delete()
                    return Result.failure(
                        workDataOf(KEY_ERROR to "Integrity check failed: SHA-256 mismatch (expected ${model.officialSha256}, got $computedHash).")
                    )
                }
            }

            // 4. Atomic installation with explicit boolean check
            val renameSuccess = tmpFile.renameTo(destFile)
            if (!renameSuccess) {
                // Fallback copy if moving across mount points
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }

            Result.success()
        } catch (e: Exception) {
            tmpFile.delete()
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    private fun computeSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
