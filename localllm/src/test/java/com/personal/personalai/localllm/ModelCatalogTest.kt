package com.personal.personalai.localllm

import com.personal.personalai.localllm.api.LocalModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `test all model entries have valid repositories and sizes`() {
        val models = LocalModel.entries
        assertTrue("Model catalog must contain entries for Qwen and Gemma", models.size >= 4)

        models.forEach { model ->
            assertNotNull(model.modelId)
            assertTrue("Model ID must not be blank", model.modelId.isNotBlank())
            assertTrue("Provider must not be blank", model.provider.isNotBlank())
            assertTrue("License must not be blank", model.license.isNotBlank())
            assertTrue("HuggingFace repo must be specified", model.huggingFaceRepo.contains("/"))
            assertTrue("FileName must end with litertlm", model.fileName.endsWith(".litertlm"))
            assertTrue("Approximate size must be positive", model.approximateSizeBytes > 1_000_000_000L)
            assertTrue("Estimated RAM must be positive", model.estimatedRamBytes > 2_000_000_000L)
            assertTrue("Supported languages must include Chinese or English", model.supportedLanguages.isNotEmpty())
            assertEquals("Minimum RAM must be 12GB for Alpha 1.0", 12, model.minRamGb)
        }
    }

    @Test
    fun `test Qwen and Gemma family model lookups by ID`() {
        val qwen = LocalModel.fromId("qwen-2.5-1.5b-it")
        assertNotNull(qwen)
        assertEquals("Alibaba Cloud", qwen?.provider)
        assertEquals("Apache 2.0", qwen?.license)

        val gemma = LocalModel.fromId("gemma-4-E2B-it")
        assertNotNull(gemma)
        assertEquals("Google", gemma?.provider)
    }
}
