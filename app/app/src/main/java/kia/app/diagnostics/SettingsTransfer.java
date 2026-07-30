package kia.app.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import kia.app.core.settings.AppSettings;

/**
 * User initiated, versioned transfer of durable settings.
 *
 * <p>Runtime state, routes, logs, permission bookkeeping and USB permission tokens are
 * deliberately excluded. Import validates the complete document before touching preferences,
 * accepts only known keys with their exact value types, and rolls both preference files back if
 * either durable commit fails.
 */
public final class SettingsTransfer {
    public static final String FILE_NAME = "kia-settings.json";
    private static final String FORMAT = "kia-settings";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_IMPORT_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES_PER_FILE = 512;
    private static final int MAX_KEY_LENGTH = 160;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final int MAX_STRING_SET_ITEMS = 128;
    private static final String PREFS_APP = "Kia";
    private static final String PREFS_RADIO = "kia_radio_stations";
    private static final String KEY_SETTINGS_SCHEMA = "schema";
    private static final String KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at";
    private static final Object IMPORT_LOCK = new Object();

    private static final Set<String> ALLOWED_FILES = names(PREFS_APP, PREFS_RADIO);
    private static final Set<String> TOP_LEVEL_KEYS =
            names("format", "version", "created_at", "preferences");

    private static final Set<String> APP_BOOLEAN_KEYS = names(
            "auto_start",
            "media_enabled",
            "media_profile_configured",
            "call_enabled",
            "media_overlay",
            "navigation_enabled",
            "compass_enabled",
            "compass_force",
            "nav_tbt",
            "nav_finish_direction",
            "nav_finish_compass_auto",
            "nav_overspeed_text",
            "nav_overlay",
            "nav_micro_maneuvers",
            "nav_straight_until_main",
            "nav_debug_visible",
            "amp_enabled",
            "canbus_debug_visible",
            "tpms_alerts",
            "tpms_sound_alerts",
            "rcta_overlay",
            "rcta_sound",
            "media_source_reassert",
            "media_tab_visible",
            "expert_mode"
    );

    private static final Set<String> APP_INT_KEYS = names(
            "media_profile",
            "nav_output_mode",
            "nav_finish_direction_lead_meters",
            "nav_text_mode",
            "nav_maneuver_text_seconds",
            "nav_source_mode",
            "nav_eta_time_mode",
            "nav_micro_hold_seconds",
            "nav_micro_max_distance_meters",
            "nav_main_reveal_distance_meters",
            "sas_ratio",
            "tpms_low_pressure",
            "tpms_high_pressure",
            "tpms_low_temp",
            "tpms_high_temp",
            "rcta_style",
            "rcta_color",
            "rcta_bg_alpha",
            "rcta_arrow_count",
            "other_media_source_mode",
            "media_text_mode",
            "media_android_source_delay_ms",
            "media_uart_source_delay_ms",
            "media_artist_delay_ms",
            "call_source_mode",
            "canbus_temp_source",
            "firmware_source"
    );

    private static final Set<String> APP_STRING_KEYS =
            names("media_preferred_session_package");
    private static final Set<String> APP_STRING_SET_KEYS =
            names("media_blocked_session_packages");

    private static final Pattern RADIO_KEY = Pattern.compile(
            "^(?:station|manual|origin)_(?:FM|AM)_[0-9]{2,5}(?:\\.[0-9]{1,2})?$");

    private SettingsTransfer() {
    }

    public static void write(Context context, Uri target) throws Exception {
        if (context == null || target == null) throw new IllegalArgumentException("target");
        try (OutputStream output = context.getContentResolver().openOutputStream(target, "wt")) {
            if (output == null) throw new IllegalStateException("Cannot open output");
            output.write(exportJson(context).getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    public static ImportResult read(Context context, Uri source) throws Exception {
        if (context == null || source == null) throw new IllegalArgumentException("source");
        AppSettings.applyDefaults(context);
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IllegalStateException("Cannot open input");
            bytes = readBounded(input);
        }

        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        ValidatedImport validated = validateJsonDocument(root, currentSettingsSchema(context));
        ImportResult result = applyValidated(context, validated);
        // The imported schema marker is intentionally never written. Keep the running app on its
        // current schema and only let its normal defaults repair any settings absent in the file.
        AppSettings.applyDefaults(context);
        return result;
    }

    public static String exportJson(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("context");
        AppSettings.applyDefaults(context);
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", FORMAT_VERSION);
        root.put("created_at", new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()));
        JSONObject files = new JSONObject();
        for (String fileName : ALLOWED_FILES) {
            files.put(fileName, exportPreferences(context, fileName));
        }
        root.put("preferences", files);
        return root.toString(2);
    }

    private static JSONObject exportPreferences(Context context, String name) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> entry :
                context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            ValueType expected = expectedType(name, key);
            if (expected == null || !matchesStoredType(expected, value)) continue;
            if (value instanceof Set) {
                JSONArray array = new JSONArray();
                for (Object item : (Set<?>) value) {
                    if (item instanceof String) array.put(item);
                }
                out.put(key, array);
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    private static ValidatedImport validateJsonDocument(JSONObject root, int currentSchema)
            throws Exception {
        if (root == null) throw new IllegalArgumentException("Пустой файл настроек");
        rejectUnknownJsonKeys(root, TOP_LEVEL_KEYS, "корне файла");

        Object format = required(root, "format");
        if (!(format instanceof String) || !FORMAT.equals(format)) {
            throw new IllegalArgumentException("Это не файл настроек Kia");
        }
        int version = requireInt(required(root, "version"), "version");
        if (version < 1 || version > FORMAT_VERSION) {
            throw new IllegalArgumentException("Неподдерживаемая версия настроек: " + version);
        }
        if (root.has("created_at")) {
            Object createdAt = root.get("created_at");
            if (!(createdAt instanceof String)
                    || ((String) createdAt).length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("Неверный тип поля created_at");
            }
        }

        Object rawPreferences = required(root, "preferences");
        if (!(rawPreferences instanceof JSONObject)) {
            throw new IllegalArgumentException("В файле нет настроек");
        }
        JSONObject preferenceFiles = (JSONObject) rawPreferences;
        LinkedHashMap<String, Map<String, ?>> rawFiles = new LinkedHashMap<>();
        Iterator<String> fileNames = preferenceFiles.keys();
        while (fileNames.hasNext()) {
            String fileName = fileNames.next();
            if (!ALLOWED_FILES.contains(fileName)) {
                throw new IllegalArgumentException(
                        "Неизвестный раздел настроек: " + safeLabel(fileName));
            }
            Object rawValues = preferenceFiles.get(fileName);
            if (!(rawValues instanceof JSONObject)) {
                throw new IllegalArgumentException(
                        "Неверный раздел настроек: " + safeLabel(fileName));
            }
            rawFiles.put(fileName, jsonValues((JSONObject) rawValues));
        }
        return validatePreferenceMaps(rawFiles, currentSchema);
    }

    /**
     * Pure validation boundary used by the JSON reader and focused local tests.
     */
    static ValidatedImport validatePreferenceMaps(
            Map<String, ? extends Map<String, ?>> preferenceFiles, int currentSchema) {
        if (currentSchema < 1) {
            throw new IllegalArgumentException("Текущая схема настроек повреждена");
        }
        if (preferenceFiles == null) {
            throw new IllegalArgumentException("В файле нет настроек");
        }

        LinkedHashMap<String, Map<String, Object>> acceptedFiles = new LinkedHashMap<>();
        int entries = 0;
        for (Map.Entry<String, ? extends Map<String, ?>> file : preferenceFiles.entrySet()) {
            String fileName = file.getKey();
            if (!ALLOWED_FILES.contains(fileName)) {
                throw new IllegalArgumentException(
                        "Неизвестный раздел настроек: " + safeLabel(fileName));
            }
            Map<String, ?> values = file.getValue();
            if (values == null) {
                throw new IllegalArgumentException(
                        "Неверный раздел настроек: " + safeLabel(fileName));
            }
            if (values.size() > MAX_ENTRIES_PER_FILE) {
                throw new IllegalArgumentException("Слишком много параметров");
            }

            LinkedHashMap<String, Object> accepted = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
                    throw new IllegalArgumentException("Неверное имя параметра");
                }
                Object value = entry.getValue();

                if (PREFS_APP.equals(fileName) && KEY_SETTINGS_SCHEMA.equals(key)) {
                    int sourceSchema = requireInt(value, PREFS_APP + "." + key);
                    if (sourceSchema < 1 || sourceSchema > currentSchema) {
                        throw new IllegalArgumentException(
                                "Несовместимая схема настроек: " + sourceSchema);
                    }
                    continue;
                }
                if (PREFS_APP.equals(fileName) && KEY_LAST_UPDATE_CHECK_AT.equals(key)) {
                    // Older v1 exports contained this runtime timestamp. Validate it for backward
                    // compatibility, but never copy it to another device.
                    long timestamp = requireLong(value, PREFS_APP + "." + key);
                    if (timestamp < 0L) {
                        throw new IllegalArgumentException(
                                "Неверное значение: " + PREFS_APP + "." + key);
                    }
                    continue;
                }

                ValueType expected = expectedType(fileName, key);
                if (expected == null) {
                    throw new IllegalArgumentException(
                            "Неизвестный параметр: " + safeLabel(fileName + "." + key));
                }
                accepted.put(key, validateValue(expected, value, fileName + "." + key));
                entries++;
            }
            if (!accepted.isEmpty()) acceptedFiles.put(fileName, accepted);
        }
        if (entries == 0) throw new IllegalArgumentException("Файл настроек пуст");
        return new ValidatedImport(acceptedFiles, entries);
    }

    /**
     * Applies a fully validated plan. Android has no transaction spanning two SharedPreferences
     * files, so both snapshots are retained and restored if either atomic per-file commit fails.
     */
    static ImportResult applyValidated(Context context, ValidatedImport validated) {
        if (context == null || validated == null) throw new IllegalArgumentException("settings");
        synchronized (IMPORT_LOCK) {
            LinkedHashMap<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
            for (String fileName : validated.files.keySet()) {
                snapshots.put(fileName, copyValues(
                        context.getSharedPreferences(fileName, Context.MODE_PRIVATE).getAll()));
            }

            try {
                for (Map.Entry<String, Map<String, Object>> file : validated.files.entrySet()) {
                    SharedPreferences prefs =
                            context.getSharedPreferences(file.getKey(), Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    putValues(editor, file.getValue());
                    if (!editor.commit()) {
                        throw new IllegalStateException(
                                "Не удалось сохранить раздел " + file.getKey());
                    }
                }
            } catch (RuntimeException error) {
                boolean restored = restoreSnapshots(context, snapshots);
                if (!restored) {
                    throw new IllegalStateException(
                            "Импорт отменён, но не удалось восстановить исходные настройки",
                            error);
                }
                throw error;
            }
            return new ImportResult(validated.files.size(), validated.entries);
        }
    }

    private static Map<String, Object> jsonValues(JSONObject values) throws Exception {
        if (values.length() > MAX_ENTRIES_PER_FILE) {
            throw new IllegalArgumentException("Слишком много параметров");
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = values.get(key);
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                ArrayList<Object> items = new ArrayList<>(array.length());
                for (int index = 0; index < array.length(); index++) {
                    items.add(array.get(index));
                }
                value = items;
            }
            out.put(key, value == JSONObject.NULL ? null : value);
        }
        return out;
    }

    private static Object validateValue(ValueType expected, Object value, String label) {
        switch (expected) {
            case BOOLEAN:
                if (value instanceof Boolean) return value;
                break;
            case INT:
                return requireInt(value, label);
            case STRING:
                if (value instanceof String && ((String) value).length() <= MAX_STRING_LENGTH) {
                    if ("media_preferred_session_package".equals(keyPart(label))
                            && !validPackageName((String) value)) {
                        throw new IllegalArgumentException("Неверное значение: " + safeLabel(label));
                    }
                    if (PREFS_RADIO.equals(filePart(label))
                            && keyPart(label).startsWith("origin_")
                            && !validRadioOrigin((String) value)) {
                        throw new IllegalArgumentException("Неверное значение: " + safeLabel(label));
                    }
                    return value;
                }
                break;
            case STRING_SET:
                return requireStringSet(value, label);
            default:
                break;
        }
        throw new IllegalArgumentException("Неверный тип параметра: " + safeLabel(label));
    }

    private static Set<String> requireStringSet(Object value, String label) {
        Iterable<?> items;
        int size;
        if (value instanceof List) {
            items = (List<?>) value;
            size = ((List<?>) value).size();
        } else if (value instanceof Set) {
            items = (Set<?>) value;
            size = ((Set<?>) value).size();
        } else {
            throw new IllegalArgumentException("Неверный тип параметра: " + safeLabel(label));
        }
        if (size > MAX_STRING_SET_ITEMS) {
            throw new IllegalArgumentException("Слишком много значений: " + safeLabel(label));
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String)
                    || ((String) item).length() > MAX_STRING_LENGTH
                    || !validPackageName((String) item)) {
                throw new IllegalArgumentException("Неверное значение: " + safeLabel(label));
            }
            out.add((String) item);
        }
        return Collections.unmodifiableSet(out);
    }

    private static int requireInt(Object value, String label) {
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException("Неверный тип параметра: " + safeLabel(label));
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Значение вне диапазона: " + safeLabel(label));
        }
        return (int) number;
    }

    private static long requireLong(Object value, String label) {
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException("Неверный тип параметра: " + safeLabel(label));
        }
        return ((Number) value).longValue();
    }

    private static ValueType expectedType(String fileName, String key) {
        if (fileName == null || key == null) return null;
        if (PREFS_RADIO.equals(fileName)) {
            return RADIO_KEY.matcher(key).matches() ? ValueType.STRING : null;
        }
        if (!PREFS_APP.equals(fileName)) return null;
        if (APP_BOOLEAN_KEYS.contains(key)) return ValueType.BOOLEAN;
        if (APP_INT_KEYS.contains(key)) return ValueType.INT;
        if (APP_STRING_KEYS.contains(key)) return ValueType.STRING;
        if (APP_STRING_SET_KEYS.contains(key)) return ValueType.STRING_SET;
        return null;
    }

    private static boolean matchesStoredType(ValueType expected, Object value) {
        if (value == null) return false;
        switch (expected) {
            case BOOLEAN:
                return value instanceof Boolean;
            case INT:
                return value instanceof Integer;
            case STRING:
                return value instanceof String && ((String) value).length() <= MAX_STRING_LENGTH;
            case STRING_SET:
                if (!(value instanceof Set) || ((Set<?>) value).size() > MAX_STRING_SET_ITEMS) {
                    return false;
                }
                for (Object item : (Set<?>) value) {
                    if (!(item instanceof String)
                            || ((String) item).length() > MAX_STRING_LENGTH) return false;
                }
                return true;
            default:
                return false;
        }
    }

    private static int currentSettingsSchema(Context context) {
        Object value = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .getAll().get(KEY_SETTINGS_SCHEMA);
        if (!(value instanceof Integer) || (Integer) value < 1) {
            throw new IllegalStateException("Текущая схема настроек повреждена");
        }
        return (Integer) value;
    }

    private static void rejectUnknownJsonKeys(JSONObject object, Set<String> allowed, String where)
            throws Exception {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                        "Неизвестное поле " + safeLabel(key) + " в " + where);
            }
        }
    }

    private static Object required(JSONObject object, String key) throws Exception {
        if (!object.has(key)) throw new IllegalArgumentException("Отсутствует поле " + key);
        return object.get(key);
    }

    private static Map<String, Object> copyValues(Map<String, ?> values) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (values == null) return out;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set) {
                value = new LinkedHashSet<>((Set<?>) value);
            }
            out.put(entry.getKey(), value);
        }
        return out;
    }

    private static void putValues(SharedPreferences.Editor editor, Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            putValue(editor, entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static void putValue(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, new LinkedHashSet<>((Set<String>) value));
        } else {
            throw new IllegalArgumentException("Неподдерживаемый тип: " + safeLabel(key));
        }
    }

    private static boolean restoreSnapshots(Context context,
                                            Map<String, Map<String, Object>> snapshots) {
        boolean restored = true;
        for (Map.Entry<String, Map<String, Object>> snapshot : snapshots.entrySet()) {
            try {
                SharedPreferences.Editor editor =
                        context.getSharedPreferences(snapshot.getKey(), Context.MODE_PRIVATE)
                                .edit().clear();
                putValues(editor, snapshot.getValue());
                if (!editor.commit()) restored = false;
            } catch (RuntimeException error) {
                restored = false;
            }
        }
        return restored;
    }

    private static boolean validPackageName(String value) {
        if (value == null) return false;
        String clean = value.trim().toLowerCase(Locale.US);
        if (clean.isEmpty()) return value.trim().isEmpty();
        if (clean.length() > 200 || !clean.equals(value.trim().toLowerCase(Locale.US))) return false;
        for (int index = 0; index < clean.length(); index++) {
            char c = clean.charAt(index);
            boolean allowed = c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9'
                    || c == '.' || c == '_';
            if (!allowed) return false;
        }
        return true;
    }

    private static boolean validRadioOrigin(String value) {
        return "manual".equals(value) || "learned".equals(value) || "fallback".equals(value);
    }

    private static String filePart(String label) {
        int split = label == null ? -1 : label.indexOf('.');
        return split < 0 ? "" : label.substring(0, split);
    }

    private static String keyPart(String label) {
        int split = label == null ? -1 : label.indexOf('.');
        return split < 0 ? "" : label.substring(split + 1);
    }

    private static String safeLabel(String value) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 180 ? clean : clean.substring(0, 180);
    }

    private static Set<String> names(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_IMPORT_BYTES) {
                throw new IllegalArgumentException("Файл настроек больше 1 МБ");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    enum ValueType {
        BOOLEAN,
        INT,
        STRING,
        STRING_SET
    }

    static final class ValidatedImport {
        final Map<String, Map<String, Object>> files;
        final int entries;

        private ValidatedImport(Map<String, Map<String, Object>> files, int entries) {
            LinkedHashMap<String, Map<String, Object>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> file : files.entrySet()) {
                copy.put(file.getKey(), Collections.unmodifiableMap(
                        new LinkedHashMap<>(file.getValue())));
            }
            this.files = Collections.unmodifiableMap(copy);
            this.entries = entries;
        }
    }

    public static final class ImportResult {
        public final int files;
        public final int entries;

        ImportResult(int files, int entries) {
            this.files = files;
            this.entries = entries;
        }

        public String summary() {
            return "Импортировано параметров: " + entries;
        }
    }
}
