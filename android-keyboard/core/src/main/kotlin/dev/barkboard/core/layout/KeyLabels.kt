package dev.barkboard.core.layout

/**
 * What is drawn on a key. Shared by the live keyboard and the theme preview so the thing people
 * customise is the thing they get.
 */
object KeyLabels {

    const val SHIFT = "⇧"
    const val BACKSPACE = "⌫"
    const val ENTER = "↵"
    const val MIC = "🎤"
    const val LANGUAGE = "🌐"
    const val EMOJI = "☺"

    fun of(spec: KeySpec, kind: KeyKind, shiftOn: Boolean = false): String = when (kind) {
        KeyKind.SHIFT -> SHIFT
        KeyKind.BACKSPACE -> BACKSPACE
        KeyKind.ENTER -> ENTER
        // Space is deliberately blank: a label on it reads as noise at that width.
        KeyKind.SPACE -> ""
        KeyKind.MIC -> MIC
        KeyKind.LANGUAGE -> LANGUAGE
        KeyKind.EMOJI -> EMOJI
        KeyKind.MACRO -> spec.label ?: ""
        KeyKind.CHAR -> spec.label ?: spec.output.orEmpty().let { if (shiftOn) it.uppercase() else it }
    }
}
