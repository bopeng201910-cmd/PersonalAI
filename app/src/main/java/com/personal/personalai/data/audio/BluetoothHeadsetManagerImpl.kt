package com.personal.personalai.data.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.personal.personalai.domain.audio.BluetoothHeadsetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothHeadsetManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : BluetoothHeadsetManager {

    companion object {
        private const val TAG = "BluetoothHeadsetMgr"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isHeadsetConnected = MutableStateFlow(false)
    override val isHeadsetConnected: StateFlow<Boolean> = _isHeadsetConnected.asStateFlow()

    private val _isScoActive = MutableStateFlow(false)
    override val isScoActive: StateFlow<Boolean> = _isScoActive.asStateFlow()

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
            _isScoActive.value = (state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
            Log.d(TAG, "Bluetooth SCO state changed: $state, active=${_isScoActive.value}")
        }
    }

    init {
        checkConnectedDevices()
        registerReceivers()
    }

    private fun checkConnectedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
            val hasBt = devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            _isHeadsetConnected.value = hasBt
        } else {
            @Suppress("DEPRECATION")
            _isHeadsetConnected.value = audioManager.isBluetoothScoAvailableOffCall
        }
    }

    private fun registerReceivers() {
        try {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            context.registerReceiver(scoReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register SCO receiver: ${e.message}")
        }
    }

    override fun startHeadsetAudioRoute() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Log.d(TAG, "Requested Bluetooth SCO audio routing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth SCO: ${e.message}")
        }
    }

    override fun stopHeadsetAudioRoute() {
        try {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
            _isScoActive.value = false
            Log.d(TAG, "Stopped Bluetooth SCO audio routing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Bluetooth SCO: ${e.message}")
        }
    }

    override fun release() {
        stopHeadsetAudioRoute()
        try {
            context.unregisterReceiver(scoReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
