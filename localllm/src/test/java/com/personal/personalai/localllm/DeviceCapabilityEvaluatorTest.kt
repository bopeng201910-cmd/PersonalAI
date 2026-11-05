package com.personal.personalai.localllm

import android.app.ActivityManager
import android.content.Context
import com.personal.personalai.localllm.api.DeviceCapabilityEvaluator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DeviceCapabilityEvaluatorTest {

    @Test
    fun testEvaluation_whenRamBelow12GbBaseline_disablesModelExecution() {
        val context = mockk<Context>(relaxed = true)
        val actManager = mockk<ActivityManager>(relaxed = true)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns actManager
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir") ?: "/tmp")

        every { actManager.getMemoryInfo(any()) } answers {
            val info = it.invocation.args[0] as ActivityManager.MemoryInfo
            info.totalMem = 8L * 1024L * 1024L * 1024L // 8GB RAM device
            info.lowMemory = false
        }

        val assessment = DeviceCapabilityEvaluator.evaluate(context)
        assertFalse(assessment.is12GbRamBaselineMet)
        assertNull(assessment.recommendedModel)
    }

    @Test
    fun testEvaluation_whenLowMemoryPressure_suspendsRecommendation() {
        val context = mockk<Context>(relaxed = true)
        val actManager = mockk<ActivityManager>(relaxed = true)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns actManager
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir") ?: "/tmp")

        every { actManager.getMemoryInfo(any()) } answers {
            val info = it.invocation.args[0] as ActivityManager.MemoryInfo
            info.totalMem = 12L * 1024L * 1024L * 1024L
            info.lowMemory = true // Severe memory pressure
        }

        val assessment = DeviceCapabilityEvaluator.evaluate(context)
        assertNull(assessment.recommendedModel)
    }

    @Test
    fun testEvaluation_whenStorageDetectionFails_reportsUnknownAndDisablesRecommendation() {
        val context = mockk<Context>(relaxed = true)
        val actManager = mockk<ActivityManager>(relaxed = true)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns actManager
        every { context.filesDir } returns File("/non_existent_dir_for_statfs_failure")

        every { actManager.getMemoryInfo(any()) } answers {
            val info = it.invocation.args[0] as ActivityManager.MemoryInfo
            info.totalMem = 12L * 1024L * 1024L * 1024L
            info.lowMemory = false
        }

        val assessment = DeviceCapabilityEvaluator.evaluate(context)
        assertNull("Model recommendation must be disabled when storage is unknown", assessment.recommendedModel)
    }
}
