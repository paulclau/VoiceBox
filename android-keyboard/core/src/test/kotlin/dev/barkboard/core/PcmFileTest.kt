package dev.barkboard.core

import dev.barkboard.core.asr.PcmFile
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PcmFileTest {

    private val dir: File = Files.createTempDirectory("pcm").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `samples survive the round trip`() {
        val file = File(dir, "audio.pcm")
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 0.999f, -1f, 0.001f)

        PcmFile.write(file, samples)
        val read = PcmFile.read(file)

        assertEquals(samples.size, read.size)
        samples.indices.forEach { i ->
            assertEquals(samples[i], read[i], 1f / 16_000f, "sample $i")
        }
    }

    @Test
    fun `a range writes only that window`() {
        val file = File(dir, "window.pcm")
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)

        PcmFile.write(file, samples, from = 1, count = 2)
        val read = PcmFile.read(file)

        assertEquals(2, read.size)
        assertEquals(0.2f, read[0], 1f / 16_000f)
        assertEquals(0.3f, read[1], 1f / 16_000f)
    }

    @Test
    fun `missing file reads as empty`() {
        assertEquals(0, PcmFile.read(File(dir, "nope.pcm")).size)
        assertEquals(0f, PcmFile.durationSeconds(File(dir, "nope.pcm"), 16_000))
    }

    @Test
    fun `duration reflects the sample count`() {
        val file = File(dir, "long.pcm")
        PcmFile.write(file, FloatArray(16_000))

        assertEquals(1f, PcmFile.durationSeconds(file, 16_000), 0.001f)
    }
}
