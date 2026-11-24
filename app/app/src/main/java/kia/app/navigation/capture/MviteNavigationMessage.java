package kia.app.navigation.capture;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** One event from the YNavi MVite navigation broadcast. */
public record MviteNavigationMessage(Kind kind, String maneuver, double distanceMeters,
                                    String text, int value, boolean active) {
    public static final String ACTION = "ssa.labs.monjaro.YN_HOOK";

    private static final int MAX_PAYLOAD_CHARS = 4096;
    private static final int MAX_JSON_DEPTH = 8;
    private static final int MAX_ROAD_CODE_POINTS = 160;
    private static final double MAX_DISTANCE_METERS = 100_000_000;
    private static final String MANEUVER_PREFIX = "context_ra_";
    private static final Pattern ROAD_CONTROLS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");
    private static final Pattern CAMERA_DISTANCE = Pattern.compile(
            "([0-9]{1,7}(?:[.,][0-9]{1,4})?)[ \\p{Zs}]*(km|км|m|м)");

    public enum Kind {
        ROUTE_ACTIVE, MANEUVER, EXIT_NUMBER, ROUTE_DISTANCE, REMAINING_TIME, ARRIVAL_TIME,
        SPEED_LIMIT, OVERSPEED, CAMERA
    }

    public static MviteNavigationMessage parse(String payload) {
        if (payload == null || payload.length() > MAX_PAYLOAD_CHARS || payload.indexOf('\0') >= 0) return null;
        String input = payload.trim();
        if (!input.startsWith("{") || !input.endsWith("}") || !hasBoundedNesting(input)) return null;

        try {
            JSONTokener tokens = new JSONTokener(input);
            if (!(tokens.nextValue() instanceof JSONObject event) || tokens.nextClean() != 0) {
                return null;
            }
            if (!(event.opt("event") instanceof String name)) return null;

            return switch (name) {
                case "RouteActive" -> booleanEvent(Kind.ROUTE_ACTIVE, event);
                case "Maneuver" -> maneuverEvent(event);
                case "Maneuver.ExitNumber" -> integerEvent(Kind.EXIT_NUMBER, event, -1, 99);
                case "Eta.DistanceLeft" -> routeDistance(event);
                case "Eta.TimeLeft" -> timeEvent(Kind.REMAINING_TIME, event, 999);
                case "Eta.ArrivalTime" -> timeEvent(Kind.ARRIVAL_TIME, event, 23);
                case "SpeedLimit" -> integerEvent(Kind.SPEED_LIMIT, event, 0, 255);
                case "SpeedLimit.Alarm" -> booleanEvent(Kind.OVERSPEED, event);
                case "Camera" -> cameraEvent(event);
                default -> null;
            };
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static MviteNavigationMessage maneuverEvent(JSONObject event) {
        if (!(event.opt("sign") instanceof String sign)) return null;
        String name = sign.startsWith(MANEUVER_PREFIX) ? sign.substring(MANEUVER_PREFIX.length()) : sign;
        String maneuver = switch (name) {
            case "forward", "finish", "boardferry", "turn_left", "turn_right", "hard_turn_left",
                    "hard_turn_right", "take_left", "take_right", "exit_left", "exit_right",
                    "turn_back_left", "turn_back_right", "in_circular_movement",
                    "out_circular_movement" -> MANEUVER_PREFIX + name;
            default -> "";
        };
        double distance = distanceInMeters(event.opt("distance"), event.opt("metric"));
        if (maneuver.isEmpty() || !Double.isFinite(distance)) return null;

        return new MviteNavigationMessage(Kind.MANEUVER, maneuver, distance,
                roadText(event.opt("nextRoad")), 0, false);
    }

    private static MviteNavigationMessage routeDistance(JSONObject event) {
        double distance = distanceInMeters(event.opt("value"), event.opt("metric"));
        return Double.isFinite(distance)
                ? new MviteNavigationMessage(Kind.ROUTE_DISTANCE, "", distance, "", 0, false) : null;
    }

    private static MviteNavigationMessage booleanEvent(Kind kind, JSONObject event) {
        if (!(event.opt("value") instanceof Boolean active)) return null;
        return new MviteNavigationMessage(kind, "", 0, "", 0, active);
    }

    private static MviteNavigationMessage integerEvent(Kind kind, JSONObject event, int min, int max) {
        Integer value = integerInRange(event.opt("value"), min, max);
        if (value == null) return null;
        return new MviteNavigationMessage(kind, "", 0, "", value, false);
    }

    private static MviteNavigationMessage timeEvent(Kind kind, JSONObject event, int maxHour) {
        Integer hour = integerInRange(event.opt("hour"), 0, maxHour);
        Integer minute = integerInRange(event.opt("minute"), 0, 59);
        if (hour == null || minute == null) return null;
        String time = String.format(Locale.ROOT, "%02d:%02d", hour, minute);
        return new MviteNavigationMessage(kind, "", 0, time, hour * 60 + minute, false);
    }

    private static MviteNavigationMessage cameraEvent(JSONObject event) {
        if (!(event.opt("action") instanceof String action)) return null;
        if (action.equals("hide")) return new MviteNavigationMessage(Kind.CAMERA, "", 0, "", 0, false);
        if ((!action.equals("show") && !action.equals("update"))
                || !(event.opt("dist") instanceof String rawDistance)) return null;

        Matcher match = CAMERA_DISTANCE.matcher(rawDistance.trim());
        if (!match.matches()) return null;
        double number = Double.parseDouble(match.group(1).replace(',', '.'));
        String unit = match.group(2);
        double meters = distanceInMeters(number, unit);
        if (!Double.isFinite(meters)) return null;

        return new MviteNavigationMessage(Kind.CAMERA, "", meters,
                match.group(1) + " " + unit, 0, true);
    }

    private static Integer integerInRange(Object raw, int min, int max) {
        if (!(raw instanceof Number number)) return null;
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < min || value > max || value != Math.rint(value)) return null;
        return (int) value;
    }

    private static double distanceInMeters(Object raw, Object metric) {
        if (!(raw instanceof Number number) || !(metric instanceof String unit)) return Double.NaN;
        double distance = switch (unit) {
            case "m", "м" -> number.doubleValue();
            case "km", "км" -> number.doubleValue() * 1000;
            default -> Double.NaN;
        };
        return distance >= 0 && distance <= MAX_DISTANCE_METERS ? distance : Double.NaN;
    }

    private static String roadText(Object raw) {
        if (!(raw instanceof String road)) return "";
        String cleaned = ROAD_CONTROLS.matcher(road).replaceAll(" ").trim();
        if (cleaned.equals("null")) return "";
        if (cleaned.codePointCount(0, cleaned.length()) <= MAX_ROAD_CODE_POINTS) return cleaned;
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_ROAD_CODE_POINTS)).trim();
    }

    // Bound recursion in the platform JSON parser before it processes unknown fields.
    private static boolean hasBoundedNesting(String input) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < input.length(); i++) {
            char character = input.charAt(i);
            if (inString && character == '\\') {
                i++;
            } else if (character == '"') {
                inString = !inString;
            } else if (!inString && (character == '[' || character == '{')) {
                if (++depth > MAX_JSON_DEPTH) return false;
            } else if (!inString && (character == ']' || character == '}')) {
                if (--depth < 0) return false;
            }
        }
        return !inString && depth == 0;
    }
}
