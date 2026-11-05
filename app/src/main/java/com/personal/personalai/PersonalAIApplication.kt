package com.personal.personalai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.personal.personalai.data.network.CleartextNetworkPolicy
import com.personal.personalai.worker.TaskReminderWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PersonalAIApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CleartextNetworkPolicy.installPrivateNetworkOverride()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val taskChannel = NotificationChannel(
            TaskReminderWorker.CHANNEL_ID,
            "Task Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for scheduled AI task reminders"
        }
        notificationManager.createNotificationChannel(taskChannel)

        val proactiveChannel = NotificationChannel(
            com.personal.personalai.worker.ProactiveCheckInWorker.CHANNEL_ID,
            "Lumio Check-In",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for proactive conversation check-ins"
        }
        notificationManager.createNotificationChannel(proactiveChannel)
    }
}
