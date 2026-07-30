package kia.app.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public final class SettingsTransferTest {
    private static final int CURRENT_SCHEMA = 51;

    @Test
    public void validDocumentProducesTypedPlanAndSkipsRuntimeMarkers() {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("schema", CURRENT_SCHEMA);
        app.put("last_update_check_at", 1234L);
        app.put("expert_mode", true);
        app.put("nav_main_reveal_distance_meters", 300);
        app.put("media_preferred_session_package", "ru.yandex.music");
        app.put("media_blocked_session_packages",
                new LinkedHashSet<>(Arrays.asList("com.example.player", "ru.example.radio")));

        Map<String, Object> radio = new LinkedHashMap<>();
        radio.put("station_FM_101.7", "Наше радио");
        radio.put("origin_FM_101.7", "manual");

        SettingsTransfer.ValidatedImport result = SettingsTransfer.validatePreferenceMaps(
                files(app, radio), CURRENT_SCHEMA);

        assertEquals(6, result.entries);
        assertEquals(2, result.files.size());
        assertFalse(result.files.get("Kia").containsKey("schema"));
        assertFalse(result.files.get("Kia").containsKey("last_update_check_at"));
        assertEquals(Boolean.TRUE, result.files.get("Kia").get("expert_mode"));
        assertEquals(300, result.files.get("Kia").get("nav_main_reveal_distance_meters"));
        assertTrue(result.files.get("Kia").get("media_blocked_session_packages")
                instanceof java.util.Set);
    }

    @Test
    public void schemaMustBeIntegerAndNotNewerThanRunningApp() {
        Map<String, Object> wrongType = new LinkedHashMap<>();
        wrongType.put("schema", "51");
        wrongType.put("expert_mode", true);
        assertThrows(IllegalArgumentException.class,
                () -> SettingsTransfer.validatePreferenceMaps(
                        files(wrongType, null), CURRENT_SCHEMA));

        Map<String, Object> future = new LinkedHashMap<>();
        future.put("schema", CURRENT_SCHEMA + 1);
        future.put("expert_mode", true);
        assertThrows(IllegalArgumentException.class,
                () -> SettingsTransfer.validatePreferenceMaps(
                        files(future, null), CURRENT_SCHEMA));
    }

    @Test
    public void exactPreferenceTypesAreRequired() {
        assertInvalidAppValue("expert_mode", "true");
        assertInvalidAppValue("nav_main_reveal_distance_meters", "300");
        assertInvalidAppValue("nav_main_reveal_distance_meters", 300.0d);
        assertInvalidAppValue("media_preferred_session_package", 42);
        assertInvalidAppValue("media_blocked_session_packages",
                Arrays.asList("com.example.player", 42));
    }

    @Test
    public void unknownKeysAndPreferenceFilesAreRejectedBeforePlanCreation() {
        assertInvalidAppValue("unknown_setting", true);

        Map<String, Map<String, ?>> unknownFile = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", true);
        unknownFile.put("unexpected", values);
        assertThrows(IllegalArgumentException.class,
                () -> SettingsTransfer.validatePreferenceMaps(
                        unknownFile, CURRENT_SCHEMA));
    }

    @Test
    public void radioKeysAndOriginValuesAreWhitelisted() {
        Map<String, Object> invalidKey = new LinkedHashMap<>();
        invalidKey.put("station_DAB_101.7", "Station");
        assertThrows(IllegalArgumentException.class,
                () -> SettingsTransfer.validatePreferenceMaps(
                        files(null, invalidKey), CURRENT_SCHEMA));

        Map<String, Object> invalidOrigin = new LinkedHashMap<>();
        invalidOrigin.put("origin_FM_101.7", "remote");
        assertThrows(IllegalArgumentException.class,
                () -> SettingsTransfer.validatePreferenceMaps(
                        files(null, invalidOrigin), CURRENT_SCHEMA));
    }

    @Test
    public void invalidLaterEntryCannotReturnPartiallyValidatedPlan() {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("expert_mode", true);
        app.put("nav_main_reveal_distance_meters", "broken");

        assertThrows(IllegalArgumentException.class,
                () -> SettingsTransfer.validatePreferenceMaps(
                        files(app, null), CURRENT_SCHEMA));
        assertEquals(Boolean.TRUE, app.get("expert_mode"));
        assertEquals("broken", app.get("nav_main_reveal_distance_meters"));
    }

    private static void assertInvalidAppValue(String key, Object value) {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put(key, value);
        assertThrows(IllegalArgumentException.class,
                () -> SettingsTransfer.validatePreferenceMaps(
                        files(app, null), CURRENT_SCHEMA));
    }

    private static Map<String, Map<String, ?>> files(
            Map<String, Object> app, Map<String, Object> radio) {
        Map<String, Map<String, ?>> files = new LinkedHashMap<>();
        if (app != null) files.put("Kia", app);
        if (radio != null) files.put("kia_radio_stations", radio);
        return files;
    }
}
