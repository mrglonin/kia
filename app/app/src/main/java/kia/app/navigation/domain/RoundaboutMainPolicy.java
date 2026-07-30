package kia.app.navigation.domain;

import java.util.Locale;

/**
 * Keeps a numbered roundabout exit as a refinement of the current main event.
 *
 * <p>Missing exit metadata must not downgrade an already numbered event, while
 * an explicit exit number may refine a generic roundabout or correct a previous
 * explicit number.</p>
 */
final class RoundaboutMainPolicy {
    private static final String NUMBERED_EXIT_PREFIX = "context_ra_roundabout_exit_";

    private RoundaboutMainPolicy() {
    }

    static boolean shouldRefineMain(String currentManeuver, String incomingManeuver) {
        String current = normalize(currentManeuver);
        String incoming = normalize(incomingManeuver);
        return isRoundabout(current)
                && isNumberedExit(incoming)
                && !incoming.equals(current);
    }

    static boolean shouldKeepNumberedMain(String currentManeuver, String incomingManeuver,
                                          float currentDistanceMeters,
                                          float incomingDistanceMeters) {
        String current = normalize(currentManeuver);
        String incoming = normalize(incomingManeuver);
        return isNumberedExit(current)
                && isRoundabout(incoming)
                && !isNumberedExit(incoming)
                && !looksLikeNextEvent(currentDistanceMeters, incomingDistanceMeters);
    }

    static boolean distanceIdentityMatches(String stateManeuver, String incomingIdentity) {
        String state = normalize(stateManeuver);
        String incoming = normalize(incomingIdentity);
        if (state.isEmpty() || incoming.isEmpty()) return false;
        return state.equals(incoming) || (isRoundabout(state) && isRoundabout(incoming));
    }

    static boolean isNumberedExit(String maneuver) {
        String value = normalize(maneuver);
        if (!value.startsWith(NUMBERED_EXIT_PREFIX)
                || value.length() != NUMBERED_EXIT_PREFIX.length() + 1) {
            return false;
        }
        char exit = value.charAt(NUMBERED_EXIT_PREFIX.length());
        return exit >= '1' && exit <= '4';
    }

    private static boolean looksLikeNextEvent(float currentDistanceMeters,
                                              float incomingDistanceMeters) {
        if (!Float.isFinite(currentDistanceMeters)
                || !Float.isFinite(incomingDistanceMeters)
                || currentDistanceMeters <= 0f
                || incomingDistanceMeters <= 0f) {
            return false;
        }
        float minimumJump = Math.max(100f, currentDistanceMeters * 0.5f);
        return incomingDistanceMeters - currentDistanceMeters >= minimumJump;
    }

    private static boolean isRoundabout(String maneuver) {
        String value = normalize(maneuver);
        return value.contains("roundabout") || value.contains("circular");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
