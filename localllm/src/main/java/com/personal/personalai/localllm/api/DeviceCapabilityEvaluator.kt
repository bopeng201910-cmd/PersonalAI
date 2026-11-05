package com.personal.personalai.localllm.api

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

data class DeviceAssessment(
    val androidVersion: Int,
    val totalPhysicalRamBytes: Long,
    val totalPhysicalRamGb: Double,
    val availableStorageBytes: Long,
    val availableStorageGb: Double,
    val isApi36Compliant: Boolean,
    val is12GbRamBaselineMet: Boolean,
    val recommendedModel: LocalModel
)

object DeviceCapabilityEvaluator {

    fun evaluate(context: Context): DeviceAssessment {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRam = memInfo.totalMem
        val totalRamGb = totalRam.toDouble() / (1024.0 * 1024.0 * 1024.0)

        val statFs = StatFs(context.filesDir.absolutePath)
        val availableStorage = statFs.availableBlocksLong * statFs.blockSizeLong
        val availableStorageGb = availableStorage.toDouble() / (1024.0 * 1024.0 * 1024.0)

        val apiLevel = Build.VERSION.SDK_INT
        val isApi36 = apiLevel >= 36
        val is12Gb = totalRamGb >= 11.0 // 12GB physical RAM devices typically report ~11.2GB usable

        // Recommendation based on physical RAM and storage
        val recommended = when {
            is12Gb && availableStorageGb >= 6.0 -> LocalModel.QWEN_2_5_1_5B
            availableStorageGb >= 4.0 -> LocalModel.QWEN_2_5_1_5B
            else -> LocalModel.GEMMA_4_E2B
        }

        return DeviceAssessment(
            androidVersion = apiLevel,
            totalPhysicalRamBytes = totalRam,
            totalPhysicalRamGb = totalRamGb,
            availableStorageBytes = availableStorage,
            availableStorageGb = availableStorageGb,
            isApi36Compliant = isApi36,
            is12GbRamBaselineMet = is12Gb,
            recommendedModel = recommended
        )
    }
}
