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

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w(TAG, "Audio focus lost ($focusChange), pausing SCO routing")
                stopHeadsetAudioRoute()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained, restoring SCO routing")
                if (_isHeadsetConnected.value) {
                    startHeadsetAudioRoute()
                }
            }
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                    _isScoActive.value = (state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
                    Log.d(TAG, "Bluetooth SCO state changed: $state, active=${_isScoActive.value}")
                }
                android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)
                    _isHeadsetConnected.value = (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED)
                    Log.d(TAG, "Bluetooth headset connection changed: $state, connected=${_isHeadsetConnected.value}")
                    if (!_isHeadsetConnected.value) {
                        stopHeadsetAudioRoute()
                    }
                }
            }
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
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
            context.registerReceiver(scoReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register SCO/BT receiver: ${e.message}")
        }
    }

    override fun startHeadsetAudioRoute() {
        try {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
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
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
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
