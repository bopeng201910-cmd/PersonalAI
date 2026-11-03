package com.personal.personalai.data.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.domain.security.NetworkServiceType
import com.personal.personalai.presentation.settings.PreferencesKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe network privacy interceptor.
 * Blocks unauthorized cloud LLM socket connections by continuously synchronizing
 * an in-memory Atomic policy cache with DataStore Preferences.
 * Default policy is strictly FALSE until explicitly enabled by user preference.
 */
@Singleton
class PrivacyNetworkInterceptor @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : Interceptor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cloudAiAllowed = AtomicBoolean(false)
    private val cloudSpeechAllowed = AtomicBoolean(false)
    private val webSearchAllowed = AtomicBoolean(true)

    init {
        // Asynchronously observe DataStore and sync in-memory atomic cache
        scope.launch {
            dataStore.data.map { prefs ->
                val cloudEnabled = prefs[PreferencesKeys.CLOUD_ENHANCEMENT_ENABLED] ?: false
                val hasKey = !prefs[PreferencesKeys.API_KEY].isNullOrBlank()
                // Cloud AI requires explicit opt-in AND configured key
                cloudEnabled && hasKey
            }.distinctUntilChanged().collect { isAllowed ->
                cloudAiAllowed.set(isAllowed)
                // Speech (Whisper) is authorized whenever cloud enhancement is enabled
                cloudSpeechAllowed.set(isAllowed)
            }
        }
    }

    fun updatePolicy(service: NetworkServiceType, allowed: Boolean) {
        when (service) {
            NetworkServiceType.CLOUD_AI -> cloudAiAllowed.set(allowed)
            NetworkServiceType.CLOUD_SPEECH -> cloudSpeechAllowed.set(allowed)
            NetworkServiceType.WEB_SEARCH -> webSearchAllowed.set(allowed)
            NetworkServiceType.LAN_PRIVATE -> { /* Local subnet always permitted */ }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        // 1. Intercept Cloud LLM requests
        if (host.contains("api.openai.com") && request.url.encodedPath.contains("responses")) {
            if (!cloudAiAllowed.get()) {
                throw IOException("Privacy Firewall Block: Cloud AI requests to $host are disabled by user policy.")
            }
        }

        // 2. Intercept Cloud Speech requests
        if (host.contains("api.openai.com") && request.url.encodedPath.contains("audio")) {
            if (!cloudSpeechAllowed.get()) {
                throw IOException("Privacy Firewall Block: Cloud Speech requests to $host are disabled by user policy.")
            }
        }

        return chain.proceed(request)
    }
}
