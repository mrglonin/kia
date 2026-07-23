package kia.app.navigation.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure spatial arbitration for the yellow maneuver shown in the cluster.
 *
 * <p>A maneuver category is deliberately not a priority.  The next trustworthy
 * route position wins.  This is important for a lane/micro maneuver located
 * before a roundabout that Yandex has already announced several kilometres
 * ahead.</p>
 */
final class ManeuverArbiter {
    private static final float POSITION_TOLERANCE_METERS = 12f;
    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?\\d(?:[\\d\\s\\u00a0\\u202f]*\\d)?(?:[\\.,]\\d+)?");

    enum Choice {
        MAIN,
        MICRO
    }

    static final class Decision {
        final Choice choice;
        final String reason;
        final float mainMeters;
        final float microMeters;

        Decision(Choice choice, String reason, float mainMeters, float microMeters) {
            this.choice = choice;
            this.reason = clean(reason);
            this.mainMeters = mainMeters;
            this.microMeters = microMeters;
        }

        boolean microWins() {
            return choice == Choice.MICRO;
        }
    }

    private ManeuverArbiter() {
    }

    static Decision decide(String mainManeuver, String mainDistance,
                           String microManeuver, String microDistance,
                           boolean trustedMicro, boolean sameFamily) {
        float mainMeters = distanceMeters(mainDistance);
        float microMeters = distanceMeters(microDistance);
        if (!trustedMicro || !usable(microManeuver)) {
            return main("micro_missing_or_untrusted", mainMeters, microMeters);
        }
        if (microMeters <= 0f) {
            return main("micro_distance_unknown", mainMeters, microMeters);
        }
        if (!usable(mainManeuver)) {
            return micro("micro_only_candidate", mainMeters, microMeters);
        }
        if (mainMeters <= 0f) {
            return micro("explicit_micro_before_unknown_main", mainMeters, microMeters);
        }
        if (microMeters + POSITION_TOLERANCE_METERS < mainMeters) {
            return micro(sameFamily ? "micro_before_same_family_main" : "micro_before_main",
                    mainMeters, microMeters);
        }
        if (sameFamily) {
            return main("same_route_event", mainMeters, microMeters);
        }
        return main("main_is_next", mainMeters, microMeters);
    }

    static float distanceMeters(String value) {
        float number = distanceValue(value);
        if (number < 0f) return -1f;
        if (number == 0f) return 0f;
        String text = clean(value).toLowerCase(Locale.US);
        return containsKm(text) ? number * 1000f : number;
    }

    static float distanceValue(String value) {
        String text = clean(value);
        if (text.isEmpty()) return -1f;
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) return -1f;
        String numeric = matcher.group()
                .replace(" ", "")
                .replace("\u00a0", "")
                .replace("\u202f", "")
                .replace(',', '.');
        try {
            return Float.parseFloat(numeric);
        } catch (RuntimeException ignored) {
            return -1f;
        }
    }

    private static boolean containsKm(String value) {
        return value.contains("км") || value.contains("km");
    }

    private static boolean usable(String value) {
        return !clean(value).isEmpty();
    }

    private static Decision main(String reason, float mainMeters, float microMeters) {
        return new Decision(Choice.MAIN, reason, mainMeters, microMeters);
    }

    private static Decision micro(String reason, float mainMeters, float microMeters) {
        return new Decision(Choice.MICRO, reason, mainMeters, microMeters);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
