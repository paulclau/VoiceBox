package dev.barkboard.core

import dev.barkboard.core.asr.EnergyVad
import dev.barkboard.core.asr.Rms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VadTest {

    @Test
    fun `speech opens the gate and quiet closes it after the hold`() {
        val vad = EnergyVad(silenceHoldMs = 700)
        vad.process(0.001f, 0)
        vad.process(0.001f, 20)

        assertEquals(EnergyVad.State.SILENCE, vad.state)
        assertEquals(EnergyVad.State.SPEECH, vad.process(0.05f, 40))
        assertEquals(40L, vad.lastVoiceMs)
        assertFalse(vad.shouldAutoStop(600))

        vad.process(0.001f, 80)
        assertEquals(EnergyVad.State.SPEECH, vad.state, "a short pause must not cut the utterance")
        assertTrue(vad.shouldAutoStop(740))
        assertEquals(EnergyVad.State.SILENCE, vad.process(0.001f, 740))
    }

    @Test
    fun `quiet between thresholds keeps the gate open`() {
        val vad = EnergyVad(openThreshold = 0.012f, closeThreshold = 0.008f, silenceHoldMs = 500)
        vad.process(0.03f, 0)

        vad.process(0.010f, 100) // above close, below open
        assertEquals(EnergyVad.State.SPEECH, vad.state)
        assertEquals(100L, vad.lastVoiceMs)
    }

    @Test
    fun `reset clears state`() {
        val vad = EnergyVad()
        vad.process(0.05f, 0)
        vad.reset()

        assertEquals(EnergyVad.State.SILENCE, vad.state)
        assertEquals(0L, vad.speechMs)
        assertFalse(vad.shouldAutoStop(10_000))
    }

    @Test
    fun `rms of a full-scale square wave is 0_5`() {
        val samples = shortArrayOf(16384, -16384, 16384, -16384)
        assertEquals(0.5f, Rms.of(samples), 0.001f)
        assertEquals(0f, Rms.of(shortArrayOf()), 0.001f)
        assertEquals(0f, Rms.of(shortArrayOf(1, 2, 3), 2, 2), 0.001f)
    }
}
