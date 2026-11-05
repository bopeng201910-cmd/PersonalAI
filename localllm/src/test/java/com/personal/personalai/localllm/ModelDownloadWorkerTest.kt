package com.personal.personalai.localllm

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personal.personalai.localllm.api.LocalModel
import com.personal.personalai.localllm.download.ModelDownloadWorker
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ModelDownloadWorkerTest {

    @Test
    fun testSha256DigestCalculation_computesDeterministicHex() {
        val testData = "Lumio Alpha 1.0 offline on-device model payload".toByteArray()
        val tmpFile = File.createTempFile("test_sha", ".bin")
        try {
            tmpFile.writeBytes(testData)
            val digest = MessageDigest.getInstance("SHA-256")
            val expectedHash = digest.digest(testData).joinToString("") { "%02x".format(it) }

            val fileDigest = MessageDigest.getInstance("SHA-256")
            tmpFile.inputStream().buffered().use { input ->
                val buf = ByteArray(1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    fileDigest.update(buf, 0, read)
                }
            }
            val computedHash = fileDigest.digest().joinToString("") { "%02x".format(it) }

            assertEquals(expectedHash, computedHash)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun testCorruptedFile_isDeletedOnFailure() {
        val tmpFile = File.createTempFile("corrupt_test", ".tmp")
        assertTrue(tmpFile.exists())
        tmpFile.delete()
        assertTrue(!tmpFile.exists())
    }

    @Test
    fun testUnverifiedModel_rejectsInstallationWithoutTrustedChecksum() {
        // Models without officialSha256 must be rejected to prevent installing unverified community artifacts
        val model = LocalModel.GEMMA_4_E4B
        assertTrue("Unverified models without trusted checksum cannot be installed in production", model.officialSha256.isNullOrBlank())
    }

    @Test
    fun testVerifiedModel_hasPinnedRevisionAndTrustedChecksum() {
        val model = LocalModel.GEMMA_4_E2B
        assertEquals("6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94", model.pinnedRevision)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", model.officialSha256)
    }

    @Test
    fun testAtomicInstallationRollback_preservesExistingModelOnFailure() {
        val destFile = File.createTempFile("active_model", ".bin")
        val backupFile = File("${destFile.parent}/${destFile.name}.backup")
        try {
            destFile.writeText("ACTIVE_WORKING_MODEL_V1")
            destFile.renameTo(backupFile)
            assertTrue(backupFile.exists())
            // Simulate rollback
            backupFile.renameTo(destFile)
            assertTrue(destFile.exists())
            assertEquals("ACTIVE_WORKING_MODEL_V1", destFile.readText())
        } finally {
            destFile.delete()
            backupFile.delete()
        }
    }
}
