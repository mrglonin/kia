package kia.app.media.domain;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import kia.app.core.model.MediaState;

public final class RadioStationStore {
    private static final String PREFS = "kia_radio_stations";
    private static final String PREFIX = "station_";

    private RadioStationStore() {
    }

    public static String resolve(Context context, String source, String frequency, String candidateName) {
        String band = band(source, frequency);
        String normalized = normalizeFrequency(source, frequency);
        String learned = cleanStationName(candidateName);
        if (TextUtils.isEmpty(normalized)) return learned;
        if (!TextUtils.isEmpty(learned)) {
            setStationNameForBand(context, band, normalized, learned);
            return learned;
        }
        String stored = stationName(context, band, normalized);
        if (!TextUtils.isEmpty(stored)) return stored;
        String fallback = defaultLabel(band, normalized);
        ensureStation(context, band, normalized, fallback);
        return fallback;
    }

    public static String currentFrequency(MediaState media) {
        if (media == null) return "";
        if (!isRadio(media.source, media.packageName)) return "";
        return normalizeFrequency(media.source, media.artist);
    }

    public static String currentBand(MediaState media) {
        if (media == null) return "FM";
        return band(media.source, media.artist);
    }

    public static void setStationName(Context context, String source, String frequency, String name) {
        String normalized = normalizeFrequency(source, frequency);
        if (TextUtils.isEmpty(normalized)) return;
        setStationNameForBand(context, band(source, frequency), normalized, name);
    }

    public static void setStationNameForBand(Context context, String band, String frequency, String name) {
        if (context == null) return;
        String cleanBand = cleanBand(band);
        String normalized = normalizeFrequency(cleanBand, frequency);
        String cleanName = cleanStationName(name);
        if (TextUtils.isEmpty(normalized)) return;
        if (TextUtils.isEmpty(cleanName)) cleanName = defaultLabel(cleanBand, normalized);
        prefs(context).edit().putString(key(cleanBand, normalized), cleanName).apply();
    }

    public static void clearStationName(Context context, String source, String frequency) {
        if (context == null) return;
        String band = band(source, frequency);
        String normalized = normalizeFrequency(source, frequency);
        if (TextUtils.isEmpty(normalized)) return;
        prefs(context).edit().remove(key(band, normalized)).apply();
    }

    public static List<Entry> entries(Context context) {
        if (context == null) return Collections.emptyList();
        ArrayList<Entry> out = new ArrayList<>();
        for (Map.Entry<String, ?> item : prefs(context).getAll().entrySet()) {
            String key = item.getKey();
            if (key == null || !key.startsWith(PREFIX)) continue;
            Object value = item.getValue();
            if (!(value instanceof String)) continue;
            Entry parsed = parseEntry(key, (String) value);
            if (parsed != null) out.add(parsed);
        }
        Collections.sort(out, (a, b) -> a.sortKey().compareTo(b.sortKey()));
        return out;
    }

    public static String summary(Context context, int maxItems) {
        List<Entry> entries = entries(context);
        if (entries.isEmpty()) return "станции появятся автоматически после включения радио";
        StringBuilder out = new StringBuilder();
        int limit = Math.max(1, Math.min(maxItems, entries.size()));
        for (int i = 0; i < limit; i++) {
            if (out.length() > 0) out.append('\n');
            Entry e = entries.get(i);
            out.append(e.band).append(' ').append(e.frequency).append(" -> ").append(e.name);
        }
        if (entries.size() > limit) out.append("\n+").append(entries.size() - limit);
        return out.toString();
    }

    private static void ensureStation(Context context, String band, String frequency, String name) {
        if (context == null) return;
        String key = key(band, frequency);
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(key)) return;
        prefs.edit().putString(key, name).apply();
    }

    private static String stationName(Context context, String band, String frequency) {
        if (context == null) return "";
        return clean(prefs(context).getString(key(band, frequency), ""));
    }

    private static boolean isRadio(String source, String packageName) {
        String text = clean(source + " " + packageName).toLowerCase(Locale.US);
        return text.contains("fm") || text.contains("am") || text.contains("radio")
                || text.contains("радио") || text.contains("com.spd.radio");
    }

    private static String band(String source, String frequency) {
        String text = clean(source + " " + frequency).toLowerCase(Locale.US);
        if (text.contains("am")) return "AM";
        return "FM";
    }

    private static String cleanBand(String value) {
        return "AM".equalsIgnoreCase(clean(value)) ? "AM" : "FM";
    }

    private static String normalizeFrequency(String source, String value) {
        String band = cleanBand(source);
        String text = clean(value).replace(',', '.');
        if (TextUtils.isEmpty(text)) return "";
        String numeric = firstNumeric(text);
        if (TextUtils.isEmpty(numeric)) return "";
        try {
            if ("AM".equals(band)) {
                String digits = numeric.replaceAll("[^0-9]", "");
                return TextUtils.isEmpty(digits) ? "" : digits;
            }
            double mhz;
            if (numeric.contains(".")) {
                mhz = Double.parseDouble(numeric);
            } else {
                int raw = Integer.parseInt(numeric);
                if (raw >= 65000 && raw <= 108000) mhz = raw / 1000.0;
                else if (raw >= 6500 && raw <= 10800) mhz = raw / 100.0;
                else if (raw >= 650 && raw <= 1080) mhz = raw / 10.0;
                else if (raw >= 65 && raw <= 108) mhz = raw;
                else return "";
            }
            if (mhz < 60.0 || mhz > 120.0) return "";
            String formatted = String.format(Locale.US, "%.1f", mhz);
            return formatted.endsWith(".0") ? formatted : formatted;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstNumeric(String text) {
        if (text == null) return "";
        for (int start = 0; start < text.length(); start++) {
            char first = text.charAt(start);
            if (!Character.isDigit(first)) continue;
            int end = start;
            boolean dot = false;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (Character.isDigit(c)) {
                    end++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    end++;
                } else {
                    break;
                }
            }
            return text.substring(start, end);
        }
        return "";
    }

    private static String cleanStationName(String value) {
        String out = clean(value);
        if (TextUtils.isEmpty(out)) return "";
        if (looksLikeFrequency(out)) return "";
        return out;
    }

    private static boolean looksLikeFrequency(String value) {
        String out = clean(value);
        return out.matches("(?i)^(FM|AM)?\\s*\\d{2,5}([\\.,]\\d{1,2})?\\s*(MHz|kHz)?$");
    }

    private static String defaultLabel(String band, String frequency) {
        return cleanBand(band) + " " + frequency;
    }

    private static Entry parseEntry(String rawKey, String name) {
        String body = rawKey.substring(PREFIX.length());
        int sep = body.indexOf('_');
        if (sep <= 0 || sep >= body.length() - 1) return null;
        String band = cleanBand(body.substring(0, sep));
        String frequency = body.substring(sep + 1);
        return new Entry(band, frequency, clean(name));
    }

    private static String key(String band, String frequency) {
        return PREFIX + cleanBand(band) + "_" + clean(frequency);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String clean(String value) {
        if (value == null) return "";
        String out = value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if ("null".equalsIgnoreCase(out) || "[]".equals(out)) return "";
        return out;
    }

    public static final class Entry {
        public final String band;
        public final String frequency;
        public final String name;

        private Entry(String band, String frequency, String name) {
            this.band = band;
            this.frequency = frequency;
            this.name = name;
        }

        private String sortKey() {
            return band + "|" + frequency;
        }
    }
}
