package kia.app.media.domain;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import kia.app.core.model.MediaState;

public final class RadioStationStore {
    private static final String PREFS = "kia_radio_stations";
    private static final String PREFIX = "station_";
    private static final String MANUAL_PREFIX = "manual_";
    private static final String ORIGIN_PREFIX = "origin_";
    private static final String ORIGIN_MANUAL = "manual";
    private static final String ORIGIN_LEARNED = "learned";
    private static final String ORIGIN_FALLBACK = "fallback";

    private RadioStationStore() {
    }

    public static String resolve(Context context, String source, String frequency, String candidateName) {
        String band = band(source, frequency);
        String normalized = normalizeFrequency(source, frequency);
        String learned = cleanStationName(candidateName);
        if (empty(normalized)) return learned;
        if (!empty(learned)) {
            setLegacyStationName(context, band, normalized, learned);
            return learned;
        }
        String stored = stationName(context, band, normalized);
        if (!empty(stored)) return stored;
        String fallback = defaultLabel(band, normalized);
        ensureLegacyStation(context, band, normalized, fallback);
        return fallback;
    }

    public static String resolveUniversal(Context context, String source, String frequency,
                                          String candidateName) {
        String band = band(source, frequency);
        String normalized = normalizeFrequency(source, frequency);
        String learned = cleanStationName(candidateName);
        if (empty(normalized)) return learned;
        if (context == null) {
            return empty(learned) ? defaultLabel(band, normalized) : learned;
        }

        StoredStation stored = storedStation(context, band, normalized);
        if (stored != null && ORIGIN_MANUAL.equals(stored.origin)) {
            return stored.name;
        }
        if (!empty(learned)) {
            if (stored == null || !same(stored.name, learned)
                    || !ORIGIN_LEARNED.equals(stored.origin)) {
                writeStation(context, band, normalized, learned, ORIGIN_LEARNED);
            }
            return learned;
        }
        if (stored != null && !empty(stored.name)) return stored.name;

        String fallback = defaultLabel(band, normalized);
        ensureStation(context, band, normalized, fallback, ORIGIN_FALLBACK);
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
        if (empty(normalized)) return;
        setStationNameForBand(context, band(source, frequency), normalized, name);
    }

    public static void setStationNameForBand(Context context, String band, String frequency, String name) {
        if (context == null) return;
        String cleanBand = cleanBand(band);
        String normalized = normalizeFrequency(cleanBand, frequency);
        String cleanName = cleanStationName(name);
        if (empty(normalized)) return;
        if (empty(cleanName)) {
            writeStation(context, cleanBand, normalized,
                    defaultLabel(cleanBand, normalized), ORIGIN_FALLBACK);
            return;
        }
        writeStation(context, cleanBand, normalized, cleanName, ORIGIN_MANUAL);
    }

    public static boolean saveManualStation(Context context, String band, String frequency, String name) {
        if (context == null) return false;
        String cleanBand = cleanBand(band);
        String normalized = normalizeFrequency(cleanBand, frequency);
        String cleanName = cleanStationName(name);
        if (empty(normalized) || empty(cleanName)) return false;
        writeStation(context, cleanBand, normalized, cleanName, ORIGIN_MANUAL);
        return true;
    }

    public static String normalizeFrequencyInput(String band, String frequency) {
        return normalizeFrequency(cleanBand(band), frequency);
    }

    public static void clearStationName(Context context, String source, String frequency) {
        if (context == null) return;
        String band = band(source, frequency);
        String normalized = normalizeFrequency(source, frequency);
        if (empty(normalized)) return;
        prefs(context).edit()
                .remove(key(band, normalized))
                .remove(manualKey(band, normalized))
                .remove(originKey(band, normalized))
                .apply();
    }

    public static List<Entry> entries(Context context) {
        if (context == null) return Collections.emptyList();
        ArrayList<Entry> out = new ArrayList<>();
        for (Map.Entry<String, ?> item : prefs(context).getAll().entrySet()) {
            String key = item.getKey();
            if (key == null || !key.startsWith(PREFIX)) continue;
            Object value = item.getValue();
            if (!(value instanceof String)) continue;
            Entry parsed = parseEntry(context, key, (String) value);
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

    private static void ensureStation(Context context, String band, String frequency, String name,
                                      String origin) {
        if (context == null) return;
        String key = key(band, frequency);
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(key)) return;
        prefs.edit()
                .putString(key, name)
                .putString(originKey(band, frequency), origin)
                .apply();
    }

    private static void ensureLegacyStation(Context context, String band, String frequency, String name) {
        if (context == null) return;
        String key = key(band, frequency);
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(key)) return;
        prefs.edit().putString(key, name).apply();
    }

    private static void setLegacyStationName(Context context, String band, String frequency, String name) {
        if (context == null) return;
        String cleanBand = cleanBand(band);
        String normalized = normalizeFrequency(cleanBand, frequency);
        String cleanName = cleanStationName(name);
        if (empty(normalized)) return;
        if (empty(cleanName)) cleanName = defaultLabel(cleanBand, normalized);
        prefs(context).edit().putString(key(cleanBand, normalized), cleanName).apply();
    }

    private static String stationName(Context context, String band, String frequency) {
        if (context == null) return "";
        return clean(prefs(context).getString(key(band, frequency), ""));
    }

    private static StoredStation storedStation(Context context, String band, String frequency) {
        SharedPreferences prefs = prefs(context);
        String manualName = clean(prefs.getString(manualKey(band, frequency), ""));
        if (!empty(manualName)) {
            if (!same(manualName, stationName(context, band, frequency))
                    || !ORIGIN_MANUAL.equals(cleanOrigin(
                    prefs.getString(originKey(band, frequency), "")))) {
                prefs.edit()
                        .putString(key(band, frequency), manualName)
                        .putString(originKey(band, frequency), ORIGIN_MANUAL)
                        .apply();
            }
            return new StoredStation(manualName, ORIGIN_MANUAL);
        }

        String name = stationName(context, band, frequency);
        if (empty(name)) return null;
        String origin = cleanOrigin(prefs.getString(originKey(band, frequency), ""));
        if (empty(origin)) {
            origin = same(name, defaultLabel(band, frequency))
                    ? ORIGIN_FALLBACK : ORIGIN_MANUAL;
            SharedPreferences.Editor edit = prefs.edit()
                    .putString(originKey(band, frequency), origin);
            if (ORIGIN_MANUAL.equals(origin)) edit.putString(manualKey(band, frequency), name);
            edit.apply();
        } else if (ORIGIN_MANUAL.equals(origin)) {
            prefs.edit().putString(manualKey(band, frequency), name).apply();
        }
        return new StoredStation(name, origin);
    }

    private static void writeStation(Context context, String band, String frequency, String name,
                                     String origin) {
        if (context == null) return;
        String cleanBand = cleanBand(band);
        String normalized = normalizeFrequency(cleanBand, frequency);
        String cleanName = clean(name);
        if (empty(normalized) || empty(cleanName)) return;
        SharedPreferences.Editor edit = prefs(context).edit()
                .putString(key(cleanBand, normalized), cleanName)
                .putString(originKey(cleanBand, normalized), cleanOrigin(origin));
        if (ORIGIN_MANUAL.equals(origin)) {
            edit.putString(manualKey(cleanBand, normalized), cleanName);
        } else {
            edit.remove(manualKey(cleanBand, normalized));
        }
        edit.apply();
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
        if (empty(text)) return "";
        String numeric = firstNumeric(text);
        if (empty(numeric)) return "";
        try {
            if ("AM".equals(band)) {
                String digits = numeric.replaceAll("[^0-9]", "");
                return empty(digits) ? "" : digits;
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
        if (empty(out)) return "";
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

    private static Entry parseEntry(Context context, String rawKey, String name) {
        String body = rawKey.substring(PREFIX.length());
        int sep = body.indexOf('_');
        if (sep <= 0 || sep >= body.length() - 1) return null;
        String band = cleanBand(body.substring(0, sep));
        String frequency = body.substring(sep + 1);
        SharedPreferences prefs = prefs(context);
        String cleanName = clean(prefs.getString(manualKey(band, frequency), ""));
        if (empty(cleanName)) cleanName = clean(name);
        if (empty(cleanName)) return null;
        String origin = cleanOrigin(prefs(context).getString(originKey(band, frequency), ""));
        if (!empty(prefs.getString(manualKey(band, frequency), ""))) {
            origin = ORIGIN_MANUAL;
        }
        if (empty(origin)) {
            origin = same(cleanName, defaultLabel(band, frequency))
                    ? ORIGIN_FALLBACK : ORIGIN_MANUAL;
        }
        return new Entry(band, frequency, cleanName, origin);
    }

    private static String key(String band, String frequency) {
        return PREFIX + cleanBand(band) + "_" + clean(frequency);
    }

    private static String originKey(String band, String frequency) {
        return ORIGIN_PREFIX + cleanBand(band) + "_" + clean(frequency);
    }

    private static String manualKey(String band, String frequency) {
        return MANUAL_PREFIX + cleanBand(band) + "_" + clean(frequency);
    }

    private static String cleanOrigin(String value) {
        String origin = clean(value).toLowerCase(Locale.US);
        if (ORIGIN_MANUAL.equals(origin) || ORIGIN_LEARNED.equals(origin)
                || ORIGIN_FALLBACK.equals(origin)) {
            return origin;
        }
        return "";
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
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
        public final boolean manual;

        private Entry(String band, String frequency, String name, String origin) {
            this.band = band;
            this.frequency = frequency;
            this.name = name;
            this.manual = ORIGIN_MANUAL.equals(origin);
        }

        private String sortKey() {
            return band + "|" + frequency;
        }
    }

    private static final class StoredStation {
        final String name;
        final String origin;

        private StoredStation(String name, String origin) {
            this.name = name;
            this.origin = origin;
        }
    }
}
