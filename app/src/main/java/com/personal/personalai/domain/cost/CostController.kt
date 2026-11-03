package com.personal.personalai.domain.cost

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.domain.repository.ApiUsageRepository
import com.personal.personalai.presentation.settings.PreferencesKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed class BudgetCheckResult {
    data class Allowed(val costType: CostType, val reservedUsd: Double) : BudgetCheckResult()
    data class Denied(val reason: String) : BudgetCheckResult()
}

@Singleton
class CostController @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val apiUsageRepository: ApiUsageRepository
) {
    private val reservationMutex = Mutex()
    private var reservedCostUsd: Double = 0.0

    companion object {
        const val DEFAULT_MONTHLY_BUDGET_USD = 5.0
        // GPT-4o pricing: ~$2.50 / 1M input, $10.00 / 1M output
        // Conservative reservation for a standard context (~4K in, ~1K out)
        const val ESTIMATED_RESERVATION_USD = 0.02
    }

    /**
     * Concurrency-safe budget check and reservation.
     */
    suspend fun checkAndReserveBudget(model: String): BudgetCheckResult = reservationMutex.withLock {
        val prefs = dataStore.data.first()
        val userTierStr = prefs[PreferencesKeys.USER_TIER] ?: UserTier.BASIC.name
        val userTier = runCatching { UserTier.valueOf(userTierStr) }.getOrDefault(UserTier.BASIC)
        val apiKey = prefs[PreferencesKeys.API_KEY].orEmpty().trim()

        // 1. Basic tier checks: Lumio platform will NOT fund cloud API calls for Basic users
        if (userTier == UserTier.BASIC) {
            if (apiKey.isBlank()) {
                return@withLock BudgetCheckResult.Denied(
                    "Basic tier cannot access cloud LLM without user-configured BYOK API Key."
                )
            }
            // User provided their own API key (BYOK mode - billed to user account)
            return@withLock BudgetCheckResult.Allowed(CostType.BYOK_USER_BILLED, 0.0)
        }

        // 2. Platform-paid Premium tier: enforce monthly budget limit
        val monthlyBudget = prefs[PreferencesKeys.MONTHLY_BUDGET_USD] ?: DEFAULT_MONTHLY_BUDGET_USD
        val currentSpent = apiUsageRepository.getCurrentMonthCostUsd()

        if (currentSpent + reservedCostUsd + ESTIMATED_RESERVATION_USD > monthlyBudget) {
            return@withLock BudgetCheckResult.Denied(
                "Monthly AI budget limit of $$monthlyBudget reached. Cloud calls blocked."
            )
        }

        // Reserve budget for pending in-flight call
        reservedCostUsd += ESTIMATED_RESERVATION_USD
        BudgetCheckResult.Allowed(CostType.PLATFORM_BILLED, ESTIMATED_RESERVATION_USD)
    }

    /**
     * Settles budget after call completes.
     * If the call was definitely aborted before provider received it (e.g. local policy/network connect timeout),
     * the full reservation is safely released.
     */
    suspend fun releaseReservation(reservedAmount: Double) = reservationMutex.withLock {
        if (reservedAmount > 0.0) {
            reservedCostUsd = (reservedCostUsd - reservedAmount).coerceAtLeast(0.0)
        }
    }
}
