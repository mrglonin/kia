package kia.app.navigation.domain;

import java.util.Locale;

/**
 * Converts provider direction names into the semantic maneuver ids understood by the cluster.
 *
 * <p>The distinction between a regular turn, an angled take/fork and an explicit road exit is
 * intentional: NORMAL mode uses different composite bytes for a regular turn, while TBT has
 * separate glyphs for all three families.</p>
 */
final class ManeuverCanonicalizer {
    private static final int SIDE_NONE = 0;
    private static final int SIDE_LEFT = 1;
    private static final int SIDE_RIGHT = 2;
    private static final int SIDE_AMBIGUOUS = 3;

    private ManeuverCanonicalizer() {
    }

    static String canonicalize(String value) {
        return canonicalize(value, SIDE_NONE);
    }

    static String canonicalize(String value, int directionLr) {
        String text = normalize(value);
        if (text.isEmpty() || text.contains("_gray_")
                || text.contains("roundabout") || text.contains("circular")) {
            return "";
        }

        int side = side(text);
        if (side == SIDE_NONE && (directionLr == SIDE_LEFT || directionLr == SIDE_RIGHT)) {
            side = directionLr;
        }
        boolean straight = containsAny(text, "straight", "forward", "ahead", "прям");
        if (side == SIDE_AMBIGUOUS || (straight && side != SIDE_NONE)) {
            return "";
        }

        if (containsAny(text, "turn_back", "u_turn", "uturn", "left180", "right180",
                "развор", "разверн")) {
            return withSide("context_ra_turn_back", side);
        }
        if (side != SIDE_NONE && containsAny(text, "left135", "right135", "hard", "sharp",
                "резк", "круто")) {
            return withSide("context_ra_hard_turn", side);
        }
        if (side != SIDE_NONE && containsAny(text, "exit", "ramp", "slip",
                "съезд", "выезд")) {
            return withSide("context_ra_exit", side);
        }
        if (side != SIDE_NONE && containsAny(text, "left45", "right45", "slight", "keep",
                "take", "fork", "shift", "_from_", "fromleft", "fromright",
                "держ", "правее", "левее")) {
            return withSide("context_ra_take", side);
        }
        if (side != SIDE_NONE) {
            return withSide("context_ra_turn", side);
        }
        if (straight) {
            return "context_ra_forward";
        }
        return "";
    }

    private static int side(String text) {
        if (containsAny(text, "left_from_right", "leftfromright")) {
            return SIDE_LEFT;
        }
        if (containsAny(text, "right_from_left", "rightfromleft")) {
            return SIDE_RIGHT;
        }
        boolean left = containsAny(text, "left", "налев", "лево", "левее", "левой", "левую");
        boolean right = containsAny(text, "right", "направ", "право", "правее", "правой", "правую");
        if (left && right) return SIDE_AMBIGUOUS;
        if (left) return SIDE_LEFT;
        if (right) return SIDE_RIGHT;
        return SIDE_NONE;
    }

    private static String withSide(String prefix, int side) {
        if (side == SIDE_LEFT) return prefix + "_left";
        if (side == SIDE_RIGHT) return prefix + "_right";
        return "context_ra_turn_back".equals(prefix) ? prefix : "";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US)
                .replace('-', '_')
                .replace(' ', '_');
    }
}
