package kia.app.tpms;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only maneuver presentation for the in-app dashboard.
 *
 * <p>This class intentionally does not participate in cluster TX. It only
 * chooses a faithful glyph/label from already canonical navigation data.</p>
 */
public final class DashboardManeuverPresentation {
    private static final Pattern ROUNDABOUT_EXIT = Pattern.compile(
            "(?:roundabout|circular)[^0-9]{0,32}(?:exit[^0-9]{0,8})?([1-9])",
            Pattern.CASE_INSENSITIVE);

    public final String glyph;
    public final String fallbackLabel;
    public final int roundaboutExit;

    private DashboardManeuverPresentation(String glyph, String fallbackLabel,
                                          int roundaboutExit) {
        this.glyph = glyph;
        this.fallbackLabel = fallbackLabel;
        this.roundaboutExit = roundaboutExit;
    }

    public static DashboardManeuverPresentation resolve(String primary,
                                                         String mainManeuverId,
                                                         String routeActionId,
                                                         String clusterVisual) {
        String selected = firstNonEmpty(primary, mainManeuverId, routeActionId, clusterVisual);
        String normalized = normalize(selected);

        if (hasAny(normalized, "route_rerouting", "rerouting_text")) {
            return new DashboardManeuverPresentation("↺", "Перестроение", 0);
        }
        if (hasAny(normalized, "route_loading")) {
            return new DashboardManeuverPresentation("…", "Загрузка маршрута", 0);
        }
        if (isRoundabout(normalized)) {
            int exit = roundaboutExit(selected);
            if (exit == 0) exit = roundaboutExit(mainManeuverId);
            if (exit == 0) exit = roundaboutExit(routeActionId);
            if (exit == 0) exit = roundaboutExit(clusterVisual);
            if (exit > 0) {
                return new DashboardManeuverPresentation("↻" + exit,
                        exit + "-й съезд", exit);
            }
            return new DashboardManeuverPresentation("↻", "Круговое движение", 0);
        }

        boolean left = hasAny(normalized, "left", "налев", "лево", "левее");
        boolean right = hasAny(normalized, "right", "направ", "право", "правее");

        // Specific shapes must win before the generic left/right tokens they contain.
        if (hasAny(normalized, "turn_back", "u_turn", "uturn", "left180",
                "right180", "развор")) {
            return new DashboardManeuverPresentation(
                    right ? "↷" : "↶",
                    right ? "Разворот направо" : (left ? "Разворот налево" : "Разворот"),
                    0);
        }
        if (hasAny(normalized, "hard_turn", "sharp", "left135", "right135", "резк")) {
            return new DashboardManeuverPresentation(
                    right ? "↘" : "↙",
                    right ? "Резко направо" : "Резко налево", 0);
        }
        if (hasAny(normalized, "_take_", "slight", "keep", "fork", "left45",
                "right45", "shift", "_from_")) {
            return new DashboardManeuverPresentation(
                    right ? "↗" : "↖",
                    right ? "Держитесь правее" : "Держитесь левее", 0);
        }
        if (hasAny(normalized, "_exit_", "ramp", "slip", "съезд", "выезд")) {
            return new DashboardManeuverPresentation(
                    right ? "↗" : "↖",
                    right ? "Съезд направо" : "Съезд налево", 0);
        }
        if (hasAny(normalized, "finish", "финиш", "destination")) {
            return new DashboardManeuverPresentation("✓", "Финиш", 0);
        }
        if (right) {
            return new DashboardManeuverPresentation("↱", "Поворот направо", 0);
        }
        if (left) {
            return new DashboardManeuverPresentation("↰", "Поворот налево", 0);
        }
        return new DashboardManeuverPresentation("↑", "Двигайтесь прямо", 0);
    }

    static int roundaboutExit(String value) {
        Matcher matcher = ROUNDABOUT_EXIT.matcher(value == null ? "" : value);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean isRoundabout(String value) {
        return hasAny(value, "roundabout", "circular", "кругов");
    }

    private static boolean hasAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US)
                .replace('-', '_')
                .replace(' ', '_');
    }
}
