package com.personal.personalai.domain.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.presentation.settings.PreferencesKeys
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

sealed class CloudPermissionResult {
    object Allowed : CloudPermissionResult()
    data class Denied(val reason: String) : CloudPermissionResult()
}

@Singleton
class PermissionEngine @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    /**
     * Checks if cloud AI enhancements are explicitly permitted by the user.
     * Default is strictly FALSE if not explicitly enabled.
     */
    suspend fun isCloudEnhancementPermitted(): CloudPermissionResult {
        val prefs = dataStore.data.first()
        val isEnabled = prefs[PreferencesKeys.CLOUD_ENHANCEMENT_ENABLED] ?: false
        return if (isEnabled) {
            CloudPermissionResult.Allowed
        } else {
            CloudPermissionResult.Denied("Cloud AI is disabled by user policy. Local models only.")
        }
    }
}
