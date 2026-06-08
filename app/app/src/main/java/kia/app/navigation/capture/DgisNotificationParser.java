package kia.app.navigation.capture;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DgisNotificationParser {
    private static final Pattern DISTANCE = Pattern.compile(
            "(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*(км|km|м|m|метр(?:ов|а)?|meters?|meter)(?=$|\\s|[,.!?;:])");
    private static final int RAW_LIMIT = 180;

    private DgisNotificationParser() {
    }

    public static boolean isDgisPackage(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase(Locale.US);
        return "ru.dublgis.dgismobile".equals(value)
                || "ru.dublgis.dgismobile4preview".equals(value)
                || value.contains("dublgis")
                || value.contains("2gis");
    }

    public static Parsed parse(StatusBarNotification sbn) {
        if (sbn == null || !isDgisPackage(sbn.getPackageName())) return null;
        Notification notification = sbn.getNotification();
        if (notification == null) return null;
        return parse(sbn.getPackageName(), notification.extras);
    }

    public static String rawSummary(StatusBarNotification sbn) {
        if (sbn == null || !isDgisPackage(sbn.getPackageName())) return "";
        Notification notification = sbn.getNotification();
        if (notification == null) return "";
        ArrayList<String> lines = lines(notification.extras);
        return shortRaw(join(unique(lines)));
    }

    public static Parsed parse(String packageName, String... values) {
        if (!isDgisPackage(packageName)) return null;
        ArrayList<String> lines = new ArrayList<>();
        if (values != null) {
            for (String value : values) addLine(lines, value);
        }
        return parseLines(packageName, lines);
    }

    private static Parsed parse(String packageName, Bundle extras) {
        if (extras == null) return null;
        return parseLines(packageName, lines(extras));
    }

    private static ArrayList<String> lines(Bundle extras) {
        ArrayList<String> lines = new ArrayList<>();
        if (extras == null) return lines;
        addLine(lines, charSequence(extras, Notification.EXTRA_TITLE));
        addLine(lines, charSequence(extras, Notification.EXTRA_TEXT));
        addLine(lines, charSequence(extras, Notification.EXTRA_SUB_TEXT));
        addLine(lines, charSequence(extras, Notification.EXTRA_BIG_TEXT));
        CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (textLines != null) {
            for (CharSequence line : textLines) addLine(lines, line);
        }
        return lines;
    }

    private static Parsed parseLines(String packageName, ArrayList<String> rawLines) {
        ArrayList<String> lines = unique(rawLines);
        if (lines.isEmpty()) return null;
        String combined = join(lines);
        String maneuver = maneuverFromText(combined);
        Distance distance = distance(combined);
        String street = street(lines);
        if (!looksLikeNavigation(combined)) return null;
        if (TextUtils.isEmpty(maneuver) && distance == null) return null;
        String distValue = distance == null ? "" : distance.value;
        String distUnit = distance == null ? "" : distance.unit;
        return new Parsed(packageName, maneuver, distValue, distUnit, street, shortRaw(combined));
    }

    private static CharSequence charSequence(Bundle extras, String key) {
        Object value = extras.get(key);
        return value instanceof CharSequence ? (CharSequence) value : null;
    }

    private static void addLine(ArrayList<String> out, CharSequence value) {
        if (value == null) return;
        String text = clean(String.valueOf(value));
        if (TextUtils.isEmpty(text)) return;
        out.add(text);
    }

    private static ArrayList<String> unique(ArrayList<String> values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String text = clean(value);
                if (!TextUtils.isEmpty(text)) set.add(text);
            }
        }
        return new ArrayList<>(set);
    }

    private static Distance distance(String text) {
        Matcher matcher = DISTANCE.matcher(text);
        if (!matcher.find()) return null;
        String unit = matcher.group(2).toLowerCase(Locale.US);
        boolean km = unit.contains("км") || unit.contains("km");
        return new Distance(matcher.group(1).replace(',', '.'), km ? "км" : "м");
    }

    private static String maneuverFromText(String text) {
        String value = clean(text).toLowerCase(Locale.US);
        if (value.contains("финиш") || value.contains("место назначения")
                || value.contains("destination") || value.contains("arriv")) {
            return "context_ra_finish";
        }
        if (value.contains("кругов") || value.contains("круге") || value.contains("кольц")
                || value.contains("roundabout")) {
            String exit = roundaboutExit(value);
            if (!TextUtils.isEmpty(exit)) return exit;
            return "context_ra_in_circular_movement";
        }
        if (value.contains("развор") || value.contains("u-turn") || value.contains("uturn")) {
            if (value.contains("направо") || value.contains("right")) return "context_ra_turn_back_right";
            if (value.contains("налево") || value.contains("left")) return "context_ra_turn_back_left";
            return "context_ra_turn_back";
        }
        if (value.contains("резко направо") || value.contains("sharp right")) {
            return "context_ra_hard_turn_right";
        }
        if (value.contains("резко налево") || value.contains("sharp left")) {
            return "context_ra_hard_turn_left";
        }
        if ((value.contains("съезд") || value.contains("exit") || value.contains("ramp"))
                && (value.contains("направо") || value.contains("right"))) {
            return "context_ra_exit_right";
        }
        if ((value.contains("съезд") || value.contains("exit") || value.contains("ramp"))
                && (value.contains("налево") || value.contains("left"))) {
            return "context_ra_exit_left";
        }
        if (value.contains("держитесь правее") || value.contains("правее")
                || value.contains("take right") || value.contains("keep right")) {
            return "context_ra_exit_right";
        }
        if (value.contains("держитесь левее") || value.contains("левее")
                || value.contains("take left") || value.contains("keep left")) {
            return "context_ra_exit_left";
        }
        if (value.contains("направо") || value.contains("right")) return "context_ra_turn_right";
        if (value.contains("налево") || value.contains("left")) return "context_ra_turn_left";
        if (value.contains("прямо") || value.contains("straight") || value.contains("forward")) {
            return "context_ra_forward";
        }
        return "";
    }

    private static String roundaboutExit(String value) {
        String p = clean(value).toLowerCase(Locale.US);
        if (p.matches(".*\\b(?:1|1st|first|перв(?:ый|ого|ом)?)\\b.*")) {
            return "context_ra_roundabout_exit_1";
        }
        if (p.matches(".*\\b(?:2|2nd|second|втор(?:ой|ого|ом)?)\\b.*")) {
            return "context_ra_roundabout_exit_2";
        }
        if (p.matches(".*\\b(?:3|3rd|third|трет(?:ий|ьего|ьем)?)\\b.*")) {
            return "context_ra_roundabout_exit_3";
        }
        if (p.matches(".*\\b(?:4|4th|fourth|четв(?:ертый|ертого|ертом|ёртый|ёртого|ёртом)?)\\b.*")) {
            return "context_ra_roundabout_exit_4";
        }
        return "";
    }

    private static String street(ArrayList<String> lines) {
        for (String line : lines) {
            String text = clean(line);
            if (looksLikeStreetLine(text)) return text;
        }
        for (String line : lines) {
            String text = streetFromInstruction(line);
            if (looksLikeStreetLine(text)) return text;
        }
        return "";
    }

    private static String streetFromInstruction(String line) {
        String text = clean(line);
        Matcher matcher = Pattern.compile("(?iu)\\b(?:на|по|к)\\s+((?:ул\\.?|улиц[ауеы]?|проспект|просп\\.?|пр-т|пр-кт|шоссе|бульвар|переулок|площадь|дорога|наб\\.?)[^,.;!?]+)")
                .matcher(text);
        if (matcher.find()) return clean(matcher.group(1));
        return "";
    }

    private static boolean looksLikeStreetLine(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return false;
        String lower = text.toLowerCase(Locale.US);
        if (lower.equals("2гис") || lower.equals("2gis") || lower.contains("навиг")) return false;
        if (lower.contains("через") || lower.contains("повер") || lower.contains("сверн")
                || lower.contains("держитесь") || lower.contains("направо") || lower.contains("налево")
                || lower.contains("прямо") || lower.contains("развор") || lower.contains("круг")
                || lower.contains("route") || lower.contains("destination")) return false;
        if (DISTANCE.matcher(lower).find()) return false;
        if (lower.contains("мин") || lower.contains("час") || lower.contains("min")) return false;
        return lower.matches(".*[a-zа-яё].*");
    }

    private static boolean looksLikeNavigation(String value) {
        String text = clean(value).toLowerCase(Locale.US);
        return text.contains("через") || text.contains("повер") || text.contains("сверн")
                || text.contains("держитесь") || text.contains("направо") || text.contains("налево")
                || text.contains("прямо") || text.contains("развор") || text.contains("круг")
                || text.contains("съезд") || text.contains("exit") || text.contains("ramp")
                || text.contains("route") || text.contains("turn") || text.contains("straight")
                || text.contains("destination") || text.contains("arriv");
    }

    private static String join(ArrayList<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) out.append(" | ");
            out.append(line);
        }
        return out.toString();
    }

    private static String shortRaw(String value) {
        String text = clean(value);
        return text.length() <= RAW_LIMIT ? text : text.substring(0, RAW_LIMIT - 3) + "...";
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private static final class Distance {
        final String value;
        final String unit;

        Distance(String value, String unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    public static final class Parsed {
        public final String packageName;
        public final String maneuver;
        public final String distance;
        public final String unit;
        public final String street;
        public final String raw;

        private Parsed(String packageName, String maneuver, String distance, String unit,
                       String street, String raw) {
            this.packageName = packageName;
            this.maneuver = maneuver;
            this.distance = distance;
            this.unit = unit;
            this.street = street;
            this.raw = raw;
        }
    }
}
