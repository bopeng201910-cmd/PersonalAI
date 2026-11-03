package com.personal.personalai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.personal.personalai.data.local.entity.ApiUsageLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiUsageLogDao {

    @Query("SELECT * FROM api_usage_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ApiUsageLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ApiUsageLogEntity): Long

    @Query("DELETE FROM api_usage_logs")
    suspend fun clearAll()
}
