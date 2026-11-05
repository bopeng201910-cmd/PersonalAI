package com.personal.personalai.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.personalai.data.geofence.GeofenceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var geofenceManager: GeofenceManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                geofenceManager.registerAll()
                Log.d(TAG, "Geofences re-registered after boot")
                // Reschedule proactive check-in WorkManager tasks
                com.personal.personalai.worker.ProactiveCheckInWorker.scheduleNextCheckIn(context)
                Log.d(TAG, "Proactive check-in rescheduled after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-register on boot: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
