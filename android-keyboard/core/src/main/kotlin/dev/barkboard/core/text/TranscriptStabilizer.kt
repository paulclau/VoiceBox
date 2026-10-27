package dev.barkboard.core.text

/**
 * Turns a stream of re-decoded partials into text that is safe to commit.
 *
 * The engine is an offline transducer re-decoded over a growing buffer, so each partial
 * usually extends the last one but may also rewrite it. We only ever *append* text that
 * begins with everything already committed; a partial that rewrites committed words is
 * dropped (committed text cannot be taken back from the field), and the caller replaces
 * the tail once at finish.
 */
class TranscriptStabilizer(private val minStableChars: Int = 2) {

    private val committed = StringBuilder()

    val committedText: String get() = committed.toString()

    fun reset() {
        committed.setLength(0)
    }

    /**
     * Returns the text to append for this partial, or "" when nothing new is stable yet.
     * An incomplete trailing word is withheld until a later partial completes it.
     */
    fun offer(partial: String): String {
        val text = partial.trimStart()
        if (text.isEmpty()) return ""

        val current = committed.toString()
        if (current.isNotEmpty() && !text.startsWith(current)) return ""

        val stable = stablePrefix(text)
        if (stable.length <= current.length) return ""
        if (!stable.startsWith(current)) return ""

        val append = stable.substring(current.length)
        if (append.length < minStableChars && !endsWithBoundary(append)) return ""

        committed.append(append)
        return append
    }

    /**
     * Text still owed after the final decode: the remainder when the final extends what is
     * committed, otherwise "" (the field keeps the committed text rather than churning).
     */
    fun finish(final: String): String {
        val text = final.trim()
        if (text.isEmpty()) return ""

        val current = committed.toString()
        if (!text.startsWith(current)) return ""

        val append = text.substring(current.length)
        committed.append(append)
        return append
    }

    /** Everything after the last complete word boundary, kept only when it ends cleanly. */
    internal fun stablePrefix(text: String): String {
        if (text.isEmpty()) return ""
        val last = text.last()
        if (last.isWhitespace() || isBoundary(last)) return text
        val cut = text.lastIndexOf(' ')
        return if (cut <= 0) "" else text.substring(0, cut + 1)
    }

    private fun endsWithBoundary(text: String): Boolean =
        text.isNotEmpty() && (text.last().isWhitespace() || isBoundary(text.last()))

    private fun isBoundary(c: Char): Boolean = c in ".,!?;:…—"
}
