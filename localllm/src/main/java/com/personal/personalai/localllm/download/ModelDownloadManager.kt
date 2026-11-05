package com.personal.personalai.localllm.download

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.personal.personalai.localllm.api.LocalModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.UUID

/**
 * Manages on-device model downloads and on-disk state.
 *
 * Downloads are performed by [ModelDownloadWorker] via WorkManager so they survive app
 * process death. Each model has a unique work tag so cancellation and status queries are
 * model-specific.
 */
object ModelDownloadManager {

    /** Progress update emitted by [getDownloadProgress]. */
    data class DownloadProgress(
        val percent: Int = 0,
        val bytesDownloaded: Long = 0L,
        val bytesTotal: Long = 0L,
        val isComplete: Boolean = false,
        val isCancelled: Boolean = false,
        val error: String? = null
    )

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Absolute [File] where [model] is (or will be) stored on this device. */
    fun modelFile(context: Context, model: LocalModel): File =
        File(context.getExternalFilesDir("models"), model.fileName)

    /** Returns true if the model file exists on disk with verified installation manifest. */
    fun isDownloaded(context: Context, model: LocalModel): Boolean {
        val file = modelFile(context, model)
        val manifest = File(file.parent, "${file.name}.verified_manifest")
        return file.exists() && file.length() > 0L && manifest.exists()
    }

    /**
     * Enqueues a background download for [model].
     *
     * @param hfToken Optional HuggingFace API token for gated model access.
     * @return The WorkManager work ID for the enqueued request.
     */
    fun enqueueDownload(context: Context, model: LocalModel, hfToken: String = ""): UUID {
        val inputData = workDataOf(
            ModelDownloadWorker.KEY_MODEL_ID to model.modelId,
            ModelDownloadWorker.KEY_HF_TOKEN to hfToken
        )
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .addTag(workTag(model))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workTag(model),
            ExistingWorkPolicy.KEEP,
            request
        )
        return request.id
    }

    /** Cancels an in-progress download for [model]. */
    fun cancelDownload(context: Context, model: LocalModel) {
        WorkManager.getInstance(context).cancelUniqueWork(workTag(model))
    }

    /** Deletes the on-disk model file for [model]. */
    fun deleteModel(context: Context, model: LocalModel) {
        modelFile(context, model).delete()
    }

    /**
     * Returns a [Flow] that emits [DownloadProgress] updates for [model].
     *
     * The flow completes (closes) when the download finishes, is cancelled, or fails.
     * Use this in a [ViewModel] with [collectAsStateWithLifecycle] to drive UI state.
     */
    fun getDownloadProgress(context: Context, model: LocalModel): Flow<DownloadProgress> =
        callbackFlow {
            val workManager = WorkManager.getInstance(context)
            val liveData = workManager.getWorkInfosForUniqueWorkLiveData(workTag(model))

            val observer = androidx.lifecycle.Observer<List<WorkInfo>> { infos ->
                val info = infos.firstOrNull()
                if (info == null) {
                    trySend(DownloadProgress())
                    return@Observer
                }
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = info.progress
                        trySend(
                            DownloadProgress(
                                percent          = progress.getInt(ModelDownloadWorker.PROGRESS_PERCENT, 0),
                                bytesDownloaded  = progress.getLong(ModelDownloadWorker.PROGRESS_BYTES_DL, 0L),
                                bytesTotal       = progress.getLong(ModelDownloadWorker.PROGRESS_BYTES_TOT, 0L)
                            )
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        trySend(DownloadProgress(percent = 100, isComplete = true))
                        close()
                    }
                    WorkInfo.State.CANCELLED -> {
                        trySend(DownloadProgress(isCancelled = true))
                        close()
                    }
                    WorkInfo.State.FAILED -> {
                        val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                            ?: "Download failed"
                        trySend(DownloadProgress(error = error))
                        close()
                    }
                    else -> { /* ENQUEUED / BLOCKED — no update needed */ }
                }
            }

            // LiveData must be observed on the main thread
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                liveData.observeForever(observer)
            }

            awaitClose {
                mainHandler.post { liveData.removeObserver(observer) }
            }
        }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun workTag(model: LocalModel): String = "download_${model.modelId}"
}
