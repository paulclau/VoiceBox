package dev.barkboard.core.layout

/**
 * What a key does. Parsed from the wire name in layout JSON so a bad value becomes a
 * readable validation error instead of a serializer exception.
 */
enum class KeyKind(val wire: String) {
    CHAR("char"),
    SHIFT("shift"),
    BACKSPACE("backspace"),
    ENTER("enter"),
    SPACE("space"),
    MIC("mic"),
    LANGUAGE("language"),
    EMOJI("emoji"),
    MACRO("macro");

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(value: String): KeyKind? = byWire[value.trim().lowercase()]
    }
}
