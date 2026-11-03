package com.personal.personalai.data.repository

import com.personal.personalai.data.local.dao.ApiUsageLogDao
import com.personal.personalai.data.local.entity.toDomain
import com.personal.personalai.data.local.entity.toEntity
import com.personal.personalai.domain.model.ApiUsageLog
import com.personal.personalai.domain.repository.ApiUsageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ApiUsageRepositoryImpl @Inject constructor(
    private val dao: ApiUsageLogDao
) : ApiUsageRepository {

    override fun observeAllLogs(): Flow<List<ApiUsageLog>> =
        dao.getAllLogs().map { entities -> entities.map { it.toDomain() } }

    override suspend fun logCall(log: ApiUsageLog): Long =
        dao.insertLog(log.toEntity())

    override suspend fun getCurrentMonthCostUsd(): Double {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return dao.getCostSince(calendar.timeInMillis)
    }

    override suspend fun clearAllLogs() = dao.clearAll()
}
