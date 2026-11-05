package com.personal.personalai.localllm

import android.app.ActivityManager
import android.content.Context
import com.personal.personalai.localllm.api.DeviceCapabilityEvaluator
import com.personal.personalai.localllm.api.LocalModel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceCapabilityEvaluatorTest {

    @Test
    fun testEvaluation_whenRamBelow12GbBaseline_disablesModelExecution() {
        val context = mockk<Context>(relaxed = true)
        val actManager = mockk<ActivityManager>(relaxed = true)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns actManager
        every { context.filesDir } returns File("/tmp")

        every { actManager.getMemoryInfo(any()) } answers {
            val info = it.invocation.args[0] as ActivityManager.MemoryInfo
            info.totalMem = 8L * 1024L * 1024L * 1024L // 8GB RAM device
            info.lowMemory = false
        }

        val assessment = DeviceCapabilityEvaluator.evaluate(context)
        assertFalse(assessment.is12GbRamBaselineMet)
        assertNull(assessment.recommendedModel)
        assertTrue(assessment.suitabilityExplanation.contains("Below Alpha 1.0 12GB RAM baseline"))
    }

    @Test
    fun testEvaluation_when12GbBaselineMet_recommendsSupportedModel() {
        val context = mockk<Context>(relaxed = true)
        val actManager = mockk<ActivityManager>(relaxed = true)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns actManager
        every { context.filesDir } returns File("/tmp")

        every { actManager.getMemoryInfo(any()) } answers {
            val info = it.invocation.args[0] as ActivityManager.MemoryInfo
            info.totalMem = 12L * 1024L * 1024L * 1024L // 12GB RAM device
            info.lowMemory = false
        }

        val assessment = DeviceCapabilityEvaluator.evaluate(context)
        assertTrue(assessment.is12GbRamBaselineMet)
        assertTrue(assessment.suitabilityExplanation.contains("12GB baseline validated"))
    }
}
