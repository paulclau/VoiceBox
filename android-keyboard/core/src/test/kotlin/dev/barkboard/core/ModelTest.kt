package dev.barkboard.core

import dev.barkboard.core.model.BarkModel
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelTest {

    private val tempDir: File = Files.createTempDirectory("bark-model").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `sha256 matches the known digest`() {
        val file = File(tempDir, "abc.bin")
        file.writeText("abc")

        // SHA-256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BarkModel.sha256(file))
        assertTrue(BarkModel.verify(file, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
        assertFalse(BarkModel.verify(file, "00".repeat(32)))
    }

    @Test
    fun `every file is pinned and the set is complete`() {
        assertEquals(4, BarkModel.FILES.size)
        BarkModel.FILES.forEach { file ->
            assertEquals(64, file.sha256.length, "${file.name} needs a full sha256")
            assertTrue(file.approxBytes > 0)
            assertTrue(BarkModel.urlFor(file).endsWith(file.name))
        }
        assertTrue(BarkModel.totalBytes > 600L * 1024 * 1024, "the int8 build is ~685 MB")
        assertTrue(BarkModel.ID.contains("clean"), "the pinned build is Bark 3.0 Clean")
        assertTrue(BarkModel.REPO.contains("Clean"), "pins must come from the Clean export")
    }

    @Test
    fun `missing model reports every file as unverified`() {
        val unverified = BarkModel.unverifiedFiles(tempDir)
        assertEquals(BarkModel.FILES.size, unverified.size)
    }

    @Test
    fun `one wrong file is enough to reject the model`() {
        BarkModel.FILES.forEach { file ->
            File(tempDir, file.name).writeText("placeholder")
        }

        val unverified = BarkModel.unverifiedFiles(tempDir)
        assertEquals(BarkModel.FILES.size, unverified.size, "placeholder content must not verify")
    }

    @Test
    fun `superseded model directories are named, never the current one`() {
        val modelsRoot = File(tempDir, "models")
        val legacy = BarkModel.legacyDirectories(modelsRoot)

        assertEquals(BarkModel.LEGACY_IDS.size, legacy.size)
        assertTrue(legacy.none { it.name == BarkModel.ID }, "the live model must never be pruned")
        assertEquals(modelsRoot, legacy.first().parentFile)
    }
}
