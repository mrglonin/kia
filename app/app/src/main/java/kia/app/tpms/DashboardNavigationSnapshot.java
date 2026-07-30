package kia.app.tpms;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kia.app.core.model.NavigationState;

/**
 * Read-only projection of the maneuver that was actually saved for cluster TX.
 * Provider/main values are used only when neither clusterVisual nor clusterTx
 * contains a maneuver frame.
 */
public final class DashboardNavigationSnapshot {
    private static final Pattern DISTANCE_TOKEN = Pattern.compile(
            "([-+]?\\d+(?:[\\.,]\\d+)?)\\s*(км|km|м|m)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public final String maneuverId;
    public final String distance;
    public final String grayRoad;
    public final String clusterFrame;
    public final boolean clusterBacked;
    public final DashboardManeuverPresentation presentation;

    private DashboardNavigationSnapshot(String maneuverId, String distance,
                                        String grayRoadId, String clusterFrame,
                                        boolean clusterBacked,
                                        DashboardManeuverPresentation presentation) {
        this.maneuverId = clean(maneuverId);
        this.distance = clean(distance);
        this.grayRoad = clean(grayRoadId);
        this.clusterFrame = clean(clusterFrame);
        this.clusterBacked = clusterBacked;
        this.presentation = presentation;
    }

    public static DashboardNavigationSnapshot resolve(NavigationState state) {
        NavigationState safe = state == null ? NavigationState.empty() : state;
        ParsedFrame visual = parseClusterVisual(safe.clusterVisual);
        ParsedFrame tx = parseLatestClusterTx(safe.clusterTx);
        ParsedFrame actual;
        if (visual.available()) {
            actual = visual.sameManeuver(tx) ? visual.fillMissingFrom(tx) : visual;
        } else {
            actual = tx;
        }
        if (actual.available()) {
            String presentationId = actual.raw.toLowerCase(Locale.ROOT)
                    .contains("rerouting_text")
                    ? "route_rerouting"
                    : actual.maneuverId;
            DashboardManeuverPresentation presentation =
                    DashboardManeuverPresentation.resolve(
                            presentationId, "", "", "");
            return new DashboardNavigationSnapshot(
                    actual.maneuverId, actual.distance, actual.grayRoadId,
                    actual.raw, true, presentation);
        }

        String providerManeuver = firstNonEmpty(
                safe.maneuver, safe.mainManeuverId, safe.routeActionId,
                safe.maneuverText);
        String providerDistance = firstNonEmpty(
                safe.maneuverDistance, safe.distance, safe.routeDistance);
        String providerGray = firstNonEmpty(safe.grayRoadScheme, safe.grayRoadId);
        DashboardManeuverPresentation presentation =
                DashboardManeuverPresentation.resolve(
                        providerManeuver,
                        safe.mainManeuverId,
                        safe.routeActionId,
                        "");
        return new DashboardNavigationSnapshot(
                providerManeuver, providerDistance, providerGray,
                "", false, presentation);
    }

    private static ParsedFrame parseClusterVisual(String value) {
        String raw = clean(value);
        if (raw.isEmpty()) return ParsedFrame.empty();
        String[] parts = raw.split("/");
        String visual = clean(parts.length == 0 ? raw : parts[0]);
        int plus = visual.indexOf(" + ");
        String maneuver = clean(plus >= 0 ? visual.substring(0, plus) : visual);
        String gray = clean(plus >= 0 ? visual.substring(plus + 3) : "");
        String distance = "";
        for (int index = 1; index < parts.length && distance.isEmpty(); index++) {
            distance = normalizeDistance(parts[index]);
        }
        return isManeuverFrame(maneuver)
                ? new ParsedFrame(maneuver, distance, gray, raw)
                : ParsedFrame.empty();
    }

    private static ParsedFrame parseLatestClusterTx(String value) {
        String raw = clean(value);
        if (raw.isEmpty()) return ParsedFrame.empty();
        String[] lines = raw.split("\\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            ParsedFrame parsed = parseClusterTxLine(lines[index]);
            if (parsed.available()) return parsed;
        }
        return ParsedFrame.empty();
    }

    private static ParsedFrame parseClusterTxLine(String value) {
        String raw = clean(value);
        if (raw.startsWith("maneuver+gray ")) {
            String maneuver = before(tokenAfter(raw, "maneuver+gray "), " gray=");
            String gray = before(tokenAfter(raw, " gray="), " dist=");
            String distance = normalizeDistance(tokenAfter(raw, " dist="));
            return new ParsedFrame(maneuver, distance, gray, raw);
        }
        if (raw.startsWith("maneuver ")) {
            String maneuver = before(tokenAfter(raw, "maneuver "), " dist=");
            String distance = normalizeDistance(tokenAfter(raw, " dist="));
            return new ParsedFrame(maneuver, distance, "", raw);
        }
        if (raw.startsWith("finish direction ")) {
            String distance = normalizeDistance(tokenAfter(raw, " dist="));
            return new ParsedFrame("finish direction", distance, "", raw);
        }
        return ParsedFrame.empty();
    }

    private static boolean isManeuverFrame(String value) {
        String text = clean(value).toLowerCase(Locale.ROOT);
        return text.startsWith("context_ra_")
                || text.startsWith("finish direction")
                || text.startsWith("direction_to_finish")
                || text.startsWith("route_loading")
                || text.startsWith("route_rerouting");
    }

    private static String normalizeDistance(String value) {
        Matcher matcher = DISTANCE_TOKEN.matcher(clean(value));
        if (!matcher.find()) return "";
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        return trimNumber(matcher.group(1))
                + (unit.equals("km") || unit.equals("км") ? " км" : " м");
    }

    private static String trimNumber(String value) {
        String text = clean(value).replace(',', '.');
        while (text.endsWith("0") && text.contains(".")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text.replace('.', ',');
    }

    private static String tokenAfter(String value, String marker) {
        String text = clean(value);
        int start = text.indexOf(marker);
        return start < 0 ? "" : clean(text.substring(start + marker.length()));
    }

    private static String before(String value, String marker) {
        String text = clean(value);
        int end = text.indexOf(marker);
        return end < 0 ? text : clean(text.substring(0, end));
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String clean = clean(value);
            if (!clean.isEmpty()) return clean;
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class ParsedFrame {
        final String maneuverId;
        final String distance;
        final String grayRoadId;
        final String raw;

        ParsedFrame(String maneuverId, String distance, String grayRoadId, String raw) {
            this.maneuverId = clean(maneuverId);
            this.distance = clean(distance);
            this.grayRoadId = clean(grayRoadId);
            this.raw = clean(raw);
        }

        static ParsedFrame empty() {
            return new ParsedFrame("", "", "", "");
        }

        boolean available() {
            return !maneuverId.isEmpty();
        }

        boolean sameManeuver(ParsedFrame other) {
            return other != null && available() && other.available()
                    && maneuverId.equals(other.maneuverId);
        }

        ParsedFrame fillMissingFrom(ParsedFrame fallback) {
            if (fallback == null) return this;
            return new ParsedFrame(
                    maneuverId,
                    distance.isEmpty() ? fallback.distance : distance,
                    grayRoadId.isEmpty() ? fallback.grayRoadId : grayRoadId,
                    raw);
        }
    }
}
