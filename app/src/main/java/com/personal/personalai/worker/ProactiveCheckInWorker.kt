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
import kotlinx.coroutines.flow.first
import java.time.LocalTime

@HiltWorker
class ProactiveCheckInWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
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

        fun calculateDelayToNextCheckIn(morningTimeStr: String, eveningTimeStr: String): Long {
            val now = java.time.LocalTime.now()
            val morning = runCatching { java.time.LocalTime.parse(morningTimeStr) }.getOrDefault(java.time.LocalTime.of(9, 0))
            val evening = runCatching { java.time.LocalTime.parse(eveningTimeStr) }.getOrDefault(java.time.LocalTime.of(20, 0))

            val nextTarget = when {
                now.isBefore(morning) -> morning
                now.isBefore(evening) -> evening
                else -> morning // next morning
            }

            val targetMinutes = nextTarget.toSecondOfDay() / 60
            val nowMinutes = now.toSecondOfDay() / 60
            val diffMinutes = if (targetMinutes > nowMinutes) {
                targetMinutes - nowMinutes
            } else {
                (24 * 60) - (nowMinutes - targetMinutes)
            }
            return diffMinutes.toLong().coerceAtLeast(1L)
        }

        fun scheduleNextCheckIn(
            context: Context,
            morningTime: String = "09:00",
            eveningTime: String = "20:00"
        ) {
            val delayMinutes = calculateDelayToNextCheckIn(morningTime, eveningTime)
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<ProactiveCheckInWorker>()
                .setInitialDelay(delayMinutes, java.util.concurrent.TimeUnit.MINUTES)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val prefs = dataStore.data.first()
        val isEnabled = prefs[com.personal.personalai.presentation.settings.PreferencesKeys.PROACTIVE_ENABLED] ?: false
        val morningTime = prefs[com.personal.personalai.presentation.settings.PreferencesKeys.PROACTIVE_MORNING_TIME] ?: "09:00"
        val eveningTime = prefs[com.personal.personalai.presentation.settings.PreferencesKeys.PROACTIVE_EVENING_TIME] ?: "20:00"
        val quietStart = prefs[com.personal.personalai.presentation.settings.PreferencesKeys.PROACTIVE_QUIET_START] ?: "22:00"
        val quietEnd = prefs[com.personal.personalai.presentation.settings.PreferencesKeys.PROACTIVE_QUIET_END] ?: "07:00"

        if (!isEnabled) {
            return Result.success()
        }

        val config = ProactiveCheckInConfig(
            isEnabled = true,
            morningTime = morningTime,
            eveningTime = eveningTime,
            quietHoursStart = quietStart,
            quietHoursEnd = quietEnd
        )

        val promptText = generateProactiveConversationUseCase(config, LocalTime.now())
        if (promptText.isNullOrBlank()) {
            Log.d(TAG, "Proactive check-in suppressed (disabled, quiet hours, or unprompted)")
            // Reschedule next check-in window even if quiet hours or conditions suppressed this check-in!
            scheduleNextCheckIn(context, morningTime, eveningTime)
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

        // Reschedule next occurrence (e.g. morning -> evening or evening -> morning)
        scheduleNextCheckIn(context, morningTime, eveningTime)

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
