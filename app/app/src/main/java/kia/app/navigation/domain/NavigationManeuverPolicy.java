package kia.app.navigation.domain;

import android.content.Context;
import android.text.TextUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kia.app.core.settings.AppSettings;

final class NavigationManeuverPolicy {
    static final float MAIN_MANEUVER_PROTECT_METERS = 250f;

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:[\\.,]\\d+)?");

    private NavigationManeuverPolicy() {
    }

    static boolean priorityBlocksMicro(String priorityManeuver, String priorityDistance) {
        if (!isPriorityEvent(priorityManeuver)) return false;
        float meters = distanceMeters(priorityDistance);
        return meters <= 0f || meters <= MAIN_MANEUVER_PROTECT_METERS;
    }

    static boolean mainBlocksMicro(String mainManeuver, String mainDistance,
                                   String microManeuver, boolean sameFamily) {
        if (!isUsable(mainManeuver) || !isUsable(microManeuver)) return false;
        if (sameFamily) return false;
        float meters = distanceMeters(mainDistance);
        return meters > 0f && meters <= MAIN_MANEUVER_PROTECT_METERS;
    }

    static boolean microDistanceAllowed(Context context, String distanceText) {
        int maxMeters = AppSettings.navMicroMaxDistanceMeters(context);
        if (maxMeters <= 0) return true;
        float meters = distanceMeters(distanceText);
        return meters > 0f && meters <= maxMeters;
    }

    static long microPostPassHoldMs(Context context) {
        return Math.max(5, AppSettings.navMicroHoldSeconds(context)) * 1000L;
    }

    static String blockedStatus(String priorityManeuver, boolean blockedByMain) {
        if (isPriorityEvent(priorityManeuver)) return "не перебивает приоритет";
        if (blockedByMain) return "не перебивает основной манёвр";
        return "";
    }

    static boolean isPriorityEvent(String maneuver) {
        String value = clean(maneuver);
        if (TextUtils.isEmpty(value)) return false;
        return isFinish(value) || isRoundabout(value)
                || value.contains("turn_back") || value.contains("uturn")
                || value.contains("hard_turn")
                || value.contains("exit_")
                || value.contains("take_");
    }

    private static boolean isUsable(String maneuver) {
        return !TextUtils.isEmpty(clean(maneuver));
    }

    private static boolean isFinish(String maneuver) {
        String value = clean(maneuver).toLowerCase(Locale.US);
        return value.contains("finish") || value.contains("arriv")
                || value.contains("destination") || value.contains("финиш");
    }

    private static boolean isRoundabout(String maneuver) {
        String value = clean(maneuver).toLowerCase(Locale.US);
        return value.contains("round") || value.contains("circular") || value.contains("круг");
    }

    private static float distanceMeters(String value) {
        String text = clean(value).toLowerCase(Locale.US).replace(',', '.');
        if (TextUtils.isEmpty(text)) return -1f;
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) return -1f;
        float number;
        try {
            number = Float.parseFloat(matcher.group());
        } catch (RuntimeException ignored) {
            return -1f;
        }
        if (text.contains("км") || text.contains("km")) return number * 1000f;
        return number;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
