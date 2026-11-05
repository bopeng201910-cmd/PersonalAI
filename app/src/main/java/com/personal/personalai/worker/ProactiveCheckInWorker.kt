package com.personal.personalai.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personal.personalai.MainActivity
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.MessageRole
import com.personal.personalai.domain.model.ProactiveCheckInConfig
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.usecase.GenerateProactiveConversationUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalTime

@HiltWorker
class ProactiveCheckInWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val generateProactiveConversationUseCase: GenerateProactiveConversationUseCase,
    private val chatRepository: ChatRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ProactiveCheckInWorker"
        const val CHANNEL_ID = "proactive_check_in"
        const val WORK_NAME = "proactive_check_in_work"
        const val KEY_IS_ENABLED = "is_enabled"
        const val KEY_QUIET_START = "quiet_start"
        const val KEY_QUIET_END = "quiet_end"

        fun scheduleNextCheckIn(context: Context) {
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<ProactiveCheckInWorker>(
                12, java.util.concurrent.TimeUnit.HOURS
            ).build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val isEnabled = inputData.getBoolean(KEY_IS_ENABLED, true)
        val quietStart = inputData.getString(KEY_QUIET_START) ?: "22:00"
        val quietEnd = inputData.getString(KEY_QUIET_END) ?: "07:00"

        val config = ProactiveCheckInConfig(
            isEnabled = isEnabled,
            quietHoursStart = quietStart,
            quietHoursEnd = quietEnd
        )

        val promptText = generateProactiveConversationUseCase(config, LocalTime.now())
        if (promptText.isNullOrBlank()) {
            Log.d(TAG, "Proactive check-in suppressed (disabled, quiet hours, or unprompted)")
            return Result.success()
        }

        // Post grounded message into chat history so user can see context when opening app
        chatRepository.saveMessage(
            Message(
                content = "👋 **Lumio Check-in:**\n\n$promptText",
                role = MessageRole.ASSISTANT
            )
        )

        // Post non-intrusive notification without unsolicited microphone activation
        showNotification(promptText)

        return Result.success()
    }

    private fun showNotification(content: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Lumio Check-In")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            ?.notify(1001, notification)
    }
}
