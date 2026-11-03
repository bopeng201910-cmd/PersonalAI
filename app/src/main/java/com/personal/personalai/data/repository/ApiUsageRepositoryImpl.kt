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

    override suspend fun clearAllLogs() = dao.clearAll()
}
