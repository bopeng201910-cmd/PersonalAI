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
    val isLowMemory: Boolean,
    val isApi36Compliant: Boolean,
    val is12GbRamBaselineMet: Boolean,
    val recommendedModel: LocalModel?,
    val suitabilityExplanation: String
)

object DeviceCapabilityEvaluator {

    fun evaluate(context: Context): DeviceAssessment {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRam = memInfo.totalMem
        val totalRamGb = totalRam.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val isLowMemory = memInfo.lowMemory

        val storageResult = runCatching {
            val statFs = StatFs(context.filesDir.absolutePath)
            statFs.availableBlocksLong * statFs.blockSizeLong
        }

        val availableStorage = storageResult.getOrNull()
        val availableStorageGb = if (availableStorage != null) {
            availableStorage.toDouble() / (1024.0 * 1024.0 * 1024.0)
        } else {
            -1.0 // Unknown / detection failed
        }

        val apiLevel = Build.VERSION.SDK_INT
        val isApi36 = apiLevel >= 36
        // Alpha 1.0 baseline: 12GB physical RAM devices report ~10.8GB - 11.5GB totalMem to user space
        val is12GbBaseline = totalRamGb >= 10.5

        val (recommended, explanation) = when {
            availableStorage == null || availableStorageGb < 0 -> {
                Pair(
                    null,
                    "Storage detection failed or unknown. Local model recommendation disabled until storage availability is confirmed."
                )
            }
            !is12GbBaseline -> {
                Pair(
                    null,
                    "Below Alpha 1.0 12GB RAM baseline (detected ${"%.1f".format(totalRamGb)} GB). Local model execution disabled to prevent system instability."
                )
            }
            availableStorageGb < 4.0 -> {
                Pair(
                    null,
                    "Insufficient storage space (${"%.1f".format(availableStorageGb)} GB available). At least 4.0 GB required for model weights and cache."
                )
            }
            isLowMemory -> {
                Pair(
                    LocalModel.QWEN_2_5_1_5B,
                    "Device memory pressure detected. Recommending compact Qwen 2.5 1.5B."
                )
            }
            availableStorageGb >= 7.0 -> {
                Pair(
                    LocalModel.GEMMA_4_E2B,
                    "12GB baseline validated. Recommended default: Gemma 4 E2B for optimal quality and balanced power consumption."
                )
            }
            else -> {
                Pair(
                    LocalModel.QWEN_2_5_1_5B,
                    "12GB baseline validated. Recommended compact model: Qwen 2.5 1.5B."
                )
            }
        }

        return DeviceAssessment(
            androidVersion = apiLevel,
            totalPhysicalRamBytes = totalRam,
            totalPhysicalRamGb = totalRamGb,
            availableStorageBytes = availableStorage ?: -1L,
            availableStorageGb = availableStorageGb,
            isLowMemory = isLowMemory,
            isApi36Compliant = isApi36,
            is12GbRamBaselineMet = is12GbBaseline,
            recommendedModel = recommended,
            suitabilityExplanation = explanation
        )
    }
}
