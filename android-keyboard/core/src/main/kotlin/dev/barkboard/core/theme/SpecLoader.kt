package dev.barkboard.core.theme

import dev.barkboard.core.layout.KeyKind
import dev.barkboard.core.layout.KeySpec
import dev.barkboard.core.layout.LayoutSpec
import dev.barkboard.core.profile.ProfileSet
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface SpecResult<out T> {
    data class Ok<T>(val value: T) : SpecResult<T>
    data class Invalid(val errors: List<String>) : SpecResult<Nothing>
}

/**
 * Parses and validates the three declarative documents. Strict on unknown fields: a theme
 * that names a key the engine does not understand is a bug in the theme, and silently
 * ignoring it is how a keyboard ends up looking broken.
 */
object SpecLoader {

    val GESTURE_NAMES = setOf("swipe-up", "swipe-down", "swipe-left", "swipe-right")

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        prettyPrint = false
    }

    private val encoder = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        prettyPrint = true
        encodeDefaults = true
    }

    fun theme(text: String): SpecResult<Theme> = decode("theme", text, Theme.serializer()) { validateTheme(it) }

    fun layout(text: String): SpecResult<LayoutSpec> =
        decode("layout", text, LayoutSpec.serializer()) { validateLayout(it) }

    fun gestures(text: String): SpecResult<GestureMap> =
        decode("gestures", text, GestureMap.serializer()) { validateGestures(it) }

    fun profiles(text: String): SpecResult<ProfileSet> =
        decode("profiles", text, ProfileSet.serializer()) { validateProfiles(it) }

    /**
     * Serialises a theme back to the file format, for saving and `.kbtheme` export. Defaults are
     * written out in full so an exported file is a complete, self-describing document rather
     * than one that depends on whatever the reader's defaults happen to be.
     */
    fun encode(theme: Theme): String = encoder.encodeToString(Theme.serializer(), theme)

    /** Serialises the profile set back to its file format. */
    fun encode(profiles: ProfileSet): String = encoder.encodeToString(ProfileSet.serializer(), profiles)

    fun keyKind(spec: KeySpec): KeyKind = KeyKind.fromWire(spec.kind) ?: KeyKind.CHAR

    private fun <T> decode(
        what: String,
        text: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        validate: (T) -> List<String>,
    ): SpecResult<T> {
        val parsed = try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            return SpecResult.Invalid(listOf("$what: ${firstLine(e.message)}"))
        } catch (e: IllegalArgumentException) {
            return SpecResult.Invalid(listOf("$what: ${firstLine(e.message)}"))
        }

        val errors = validate(parsed)
        return if (errors.isEmpty()) SpecResult.Ok(parsed) else SpecResult.Invalid(errors)
    }

    private fun firstLine(message: String?): String =
        (message ?: "could not be parsed").lineSequence().first().take(200)

    fun validateTheme(theme: Theme): List<String> {
        val errors = mutableListOf<String>()

        if (theme.id.isBlank()) errors += "theme.id must not be blank"

        val colors = linkedMapOf(
            "colors.background" to theme.colors.background,
            "colors.key" to theme.colors.key,
            "colors.keyPressed" to theme.colors.keyPressed,
            "colors.accent" to theme.colors.accent,
            "colors.label" to theme.colors.label,
            "colors.labelOnAccent" to theme.colors.labelOnAccent,
            "colors.keyBorder" to theme.colors.keyBorder,
        )
        colors.forEach { (name, hex) ->
            if (ColorSpec.parse(hex) == null) errors += "$name is not a colour: '$hex'"
        }

        if (theme.key.radiusDp !in ThemeLimits.radiusDp) {
            errors += "key.radiusDp must be ${ThemeLimits.radiusDp} (was ${theme.key.radiusDp})"
        }
        if (theme.key.gapDp !in ThemeLimits.gapDp) {
            errors += "key.gapDp must be ${ThemeLimits.gapDp} (was ${theme.key.gapDp})"
        }
        if (theme.key.labelSizeSp !in ThemeLimits.labelSizeSp) {
            errors += "key.labelSizeSp must be ${ThemeLimits.labelSizeSp} (was ${theme.key.labelSizeSp})"
        }
        if (theme.key.pressedScale !in ThemeLimits.pressedScale) {
            errors += "key.pressedScale must be ${ThemeLimits.pressedScale} (was ${theme.key.pressedScale})"
        }

        if (theme.flight.durationMs !in ThemeLimits.durationMs) {
            errors += "flight.durationMs must be ${ThemeLimits.durationMs} (was ${theme.flight.durationMs})"
        }
        if (theme.flight.arc !in ThemeLimits.arc) {
            errors += "flight.arc must be ${ThemeLimits.arc} (was ${theme.flight.arc})"
        }
        if (theme.flight.startScale !in ThemeLimits.flightScale) {
            errors += "flight.startScale must be ${ThemeLimits.flightScale} (was ${theme.flight.startScale})"
        }
        if (theme.flight.endScale !in ThemeLimits.flightScale) {
            errors += "flight.endScale must be ${ThemeLimits.flightScale} (was ${theme.flight.endScale})"
        }
        if (theme.flight.laneDp !in ThemeLimits.laneDp) {
            errors += "flight.laneDp must be ${ThemeLimits.laneDp} (was ${theme.flight.laneDp})"
        }

        if (theme.haptics.press !in ThemeLimits.hapticKinds) {
            errors += "haptics.press is unknown: '${theme.haptics.press}'"
        }
        if (theme.haptics.land !in ThemeLimits.hapticKinds) {
            errors += "haptics.land is unknown: '${theme.haptics.land}'"
        }

        return errors
    }

    fun validateLayout(layout: LayoutSpec): List<String> {
        val errors = mutableListOf<String>()

        if (layout.id.isBlank()) errors += "layout.id must not be blank"
        if (layout.rows.isEmpty()) errors += "layout.rows must not be empty"

        layout.rows.forEachIndexed { rowIndex, row ->
            val rowName = "rows[$rowIndex]"
            if (row.height <= 0f) errors += "$rowName.height must be positive"
            if (row.keys.isEmpty()) errors += "$rowName has no keys"

            row.keys.forEachIndexed { keyIndex, key ->
                val keyName = "$rowName.keys[$keyIndex]"
                val kind = KeyKind.fromWire(key.kind)
                if (kind == null) {
                    errors += "$keyName.kind is unknown: '${key.kind}'"
                } else if (kind == KeyKind.CHAR && key.output.isNullOrEmpty()) {
                    errors += "$keyName needs an output for kind 'char'"
                }
                if (key.width <= 0f) errors += "$keyName.width must be positive"
                if (key.longPress.any { it.isEmpty() }) errors += "$keyName.longPress has an empty entry"
                key.gesture.forEach { (gesture, action) ->
                    if (gesture !in GESTURE_NAMES) {
                        errors += "$keyName.gesture '$gesture' is not one of ${GESTURE_NAMES.joinToString()}"
                    }
                    if (action.isBlank()) errors += "$keyName.gesture '$gesture' has a blank action"
                }
            }
        }
        return errors
    }

    fun validateProfiles(profiles: ProfileSet): List<String> {
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        profiles.apps.forEachIndexed { index, profile ->
            val name = "apps[$index]"
            val packageName = profile.packageName.trim()

            if (packageName.isEmpty()) {
                errors += "$name.packageName must not be blank"
            } else if (packageName.any { it.isWhitespace() }) {
                errors += "$name.packageName must not contain whitespace: '$packageName'"
            } else if (!packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
                errors += "$name.packageName is not a package name: '$packageName'"
            }

            // Case-insensitively, because that is how a profile is matched, so two entries that
            // differ only in case would make the lookup ambiguous.
            if (!seen.add(packageName.lowercase())) {
                errors += "$name.packageName is listed twice: '$packageName'"
            }

            if (profile.themeId != null && profile.themeId.isBlank()) {
                errors += "$name.themeId is blank; omit it to inherit the global theme"
            }
            if (profile.layoutId != null && profile.layoutId.isBlank()) {
                errors += "$name.layoutId is blank; omit it to inherit the global layout"
            }
        }
        return errors
    }

    fun validateGestures(gestures: GestureMap): List<String> {
        val errors = mutableListOf<String>()
        gestures.bindings.forEach { (binding, action) ->
            val separator = binding.lastIndexOf('.')
            if (separator <= 0) {
                errors += "gesture binding '$binding' must look like <key>.<gesture>"
                return@forEach
            }
            val gesture = binding.substring(separator + 1)
            if (gesture !in GESTURE_NAMES) {
                errors += "gesture binding '$binding' uses unknown gesture '$gesture'"
            }
            if (action.isBlank()) errors += "gesture binding '$binding' has a blank action"
        }
        return errors
    }
}
