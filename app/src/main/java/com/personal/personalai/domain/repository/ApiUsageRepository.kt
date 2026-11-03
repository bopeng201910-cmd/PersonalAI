package com.personal.personalai.domain.repository

import com.personal.personalai.domain.model.ApiUsageLog
import kotlinx.coroutines.flow.Flow

interface ApiUsageRepository {
    fun observeAllLogs(): Flow<List<ApiUsageLog>>
    suspend fun logCall(log: ApiUsageLog): Long
    suspend fun clearAllLogs()
}
