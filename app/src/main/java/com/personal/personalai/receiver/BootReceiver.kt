package com.personal.personalai.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.data.geofence.GeofenceManager
import com.personal.personalai.presentation.settings.PreferencesKeys
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var geofenceManager: GeofenceManager
    @Inject lateinit var dataStore: DataStore<Preferences>

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                geofenceManager.registerAll()
                Log.d(TAG, "Geofences re-registered after boot")

                // Only reschedule proactive check-in if user explicitly enabled it
                val prefs = dataStore.data.first()
                val isEnabled = prefs[PreferencesKeys.PROACTIVE_ENABLED] ?: false
                if (isEnabled) {
                    val morning = prefs[PreferencesKeys.PROACTIVE_MORNING_TIME] ?: "09:00"
                    val evening = prefs[PreferencesKeys.PROACTIVE_EVENING_TIME] ?: "20:00"
                    com.personal.personalai.worker.ProactiveCheckInWorker.scheduleNextCheckIn(context, morning, evening)
                    Log.d(TAG, "Proactive check-in rescheduled after boot for enabled user")
                } else {
                    com.personal.personalai.worker.ProactiveCheckInWorker.cancelCheckIn(context)
                    Log.d(TAG, "Proactive check-in was disabled by user; will not reschedule on boot")
                }
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
