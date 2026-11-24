package kia.app.navigation.domain

import java.util.Locale

/**
 * Converts provider direction names into the semantic maneuver ids understood by the cluster.
 *
 * The distinction between a regular turn, an angled take/fork and an explicit road exit is
 * intentional: NORMAL mode uses different composite bytes for a regular turn, while TBT has
 * separate glyphs for all three families.
 */
internal object ManeuverCanonicalizer {
    private const val SIDE_NONE: Int = 0
    private const val SIDE_LEFT: Int = 1
    private const val SIDE_RIGHT: Int = 2
    private const val SIDE_AMBIGUOUS: Int = 3

    @JvmStatic
    fun canonicalize(value: String?): String? {
        return ManeuverCanonicalizer.canonicalize(value, ManeuverCanonicalizer.SIDE_NONE)
    }

    @JvmStatic
    fun canonicalize(value: String?, directionLr: Int): String? {
        val text = ManeuverCanonicalizer.normalize(value)
        if (
            text.isEmpty() ||
                text.contains("_gray_") ||
                text.contains("roundabout") ||
                text.contains("circular")
        ) {
            return ""
        }

        var side: Int = ManeuverCanonicalizer.side(text)
        if (
            side == ManeuverCanonicalizer.SIDE_NONE &&
                (directionLr == ManeuverCanonicalizer.SIDE_LEFT ||
                    directionLr == ManeuverCanonicalizer.SIDE_RIGHT)
        ) {
            side = directionLr
        }
        val straight: Boolean =
            ManeuverCanonicalizer.containsAny(text, "straight", "forward", "ahead", "прям")
        if (
            side == ManeuverCanonicalizer.SIDE_AMBIGUOUS ||
                (straight && side != ManeuverCanonicalizer.SIDE_NONE)
        ) {
            return ""
        }

        if (
            ManeuverCanonicalizer.containsAny(
                text,
                "turn_back",
                "u_turn",
                "uturn",
                "left180",
                "right180",
                "развор",
                "разверн",
            )
        ) {
            return ManeuverCanonicalizer.withSide("context_ra_turn_back", side)
        }
        if (
            side != ManeuverCanonicalizer.SIDE_NONE &&
                ManeuverCanonicalizer.containsAny(
                    text,
                    "left135",
                    "right135",
                    "hard",
                    "sharp",
                    "резк",
                    "круто",
                )
        ) {
            return ManeuverCanonicalizer.withSide("context_ra_hard_turn", side)
        }
        if (
            side != ManeuverCanonicalizer.SIDE_NONE &&
                ManeuverCanonicalizer.containsAny(text, "exit", "ramp", "slip", "съезд", "выезд")
        ) {
            return ManeuverCanonicalizer.withSide("context_ra_exit", side)
        }
        if (
            side != ManeuverCanonicalizer.SIDE_NONE &&
                ManeuverCanonicalizer.containsAny(
                    text,
                    "left45",
                    "right45",
                    "slight",
                    "keep",
                    "take",
                    "fork",
                    "shift",
                    "_from_",
                    "fromleft",
                    "fromright",
                    "держ",
                    "правее",
                    "левее",
                )
        ) {
            return ManeuverCanonicalizer.withSide("context_ra_take", side)
        }
        if (side != ManeuverCanonicalizer.SIDE_NONE) {
            return ManeuverCanonicalizer.withSide("context_ra_turn", side)
        }
        if (straight) {
            return "context_ra_forward"
        }
        return ""
    }

    private fun side(text: String): Int {
        if (ManeuverCanonicalizer.containsAny(text, "left_from_right", "leftfromright")) {
            return ManeuverCanonicalizer.SIDE_LEFT
        }
        if (ManeuverCanonicalizer.containsAny(text, "right_from_left", "rightfromleft")) {
            return ManeuverCanonicalizer.SIDE_RIGHT
        }
        val left: Boolean =
            ManeuverCanonicalizer.containsAny(
                text,
                "left",
                "налев",
                "лево",
                "левее",
                "левой",
                "левую",
            )
        val right: Boolean =
            ManeuverCanonicalizer.containsAny(
                text,
                "right",
                "направ",
                "право",
                "правее",
                "правой",
                "правую",
            )
        if (left && right) return ManeuverCanonicalizer.SIDE_AMBIGUOUS
        if (left) return ManeuverCanonicalizer.SIDE_LEFT
        if (right) return ManeuverCanonicalizer.SIDE_RIGHT
        return ManeuverCanonicalizer.SIDE_NONE
    }

    private fun withSide(prefix: String?, side: Int): String? {
        if (side == ManeuverCanonicalizer.SIDE_LEFT) return prefix.toString() + "_left"
        if (side == ManeuverCanonicalizer.SIDE_RIGHT) return prefix.toString() + "_right"
        return if ("context_ra_turn_back".equals(prefix)) prefix else ""
    }

    private fun containsAny(value: String, vararg needles: String?): Boolean {
        for (needle: String? in needles) {
            if (value.contains(needle!!)) return true
        }
        return false
    }

    private fun normalize(value: String?): String {
        return if (value == null) ""
        else value.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')
    }
}
