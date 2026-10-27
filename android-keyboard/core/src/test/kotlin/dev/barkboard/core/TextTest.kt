package dev.barkboard.core

import dev.barkboard.core.text.TranscriptStabilizer
import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {

    @Test
    fun `only whole words are committed`() {
        val stabilizer = TranscriptStabilizer()

        assertEquals("hello ", stabilizer.offer("hello wor"))
        assertEquals("hello ", stabilizer.committedText)
        assertEquals("world ", stabilizer.offer("hello world how"))
        assertEquals("how ", stabilizer.offer("hello world how are"))
    }

    @Test
    fun `a partial that rewrites committed text appends nothing`() {
        val stabilizer = TranscriptStabilizer()
        stabilizer.offer("I scream for ice")

        assertEquals("", stabilizer.offer("I scream. you scream"))
        assertEquals("I scream for ", stabilizer.committedText)
    }

    @Test
    fun `a shorter partial appends nothing`() {
        val stabilizer = TranscriptStabilizer()
        stabilizer.offer("hello wor")

        assertEquals("", stabilizer.offer("hell"))
    }

    @Test
    fun `final appends the remainder`() {
        val stabilizer = TranscriptStabilizer()
        stabilizer.offer("hello world how are")

        assertEquals("are you", stabilizer.finish("hello world how are you"))
        assertEquals("hello world how are you", stabilizer.committedText)
    }

    @Test
    fun `final completes the word withheld from the partial`() {
        val stabilizer = TranscriptStabilizer()
        assertEquals("hello world ", stabilizer.offer("hello world how"))

        assertEquals("how", stabilizer.finish("hello world how"))
    }

    @Test
    fun `final that disagrees leaves the field alone`() {
        val stabilizer = TranscriptStabilizer()
        stabilizer.offer("send it now today")

        assertEquals("", stabilizer.finish("send it later"))
    }

    @Test
    fun `reset clears the committed text`() {
        val stabilizer = TranscriptStabilizer()
        stabilizer.offer("one two three")
        stabilizer.reset()

        assertEquals("", stabilizer.committedText)
        assertEquals("four ", stabilizer.offer("four five"))
    }

    @Test
    fun `punctuation ends a stable prefix`() {
        val stabilizer = TranscriptStabilizer()
        assertEquals("done.", stabilizer.offer("done."))
    }

}
