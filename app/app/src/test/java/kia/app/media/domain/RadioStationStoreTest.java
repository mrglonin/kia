package kia.app.media.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RadioStationStoreTest {
    @Test
    public void manualNameSurvivesAutomaticLearningAndLegacyTeyesUpdate() {
        TestContext context = new TestContext();

        assertTrue(RadioStationStore.saveManualStation(
                context, "FM", "101.0", "Моя станция"));
        assertEquals("Моя станция", RadioStationStore.resolveUniversal(
                context, "FM", "101.0", "RDS AUTO"));

        // The legacy resolver deliberately keeps TEYES behaviour: its live candidate wins.
        assertEquals("TEYES LIVE", RadioStationStore.resolve(
                context, "FM", "101.0", "TEYES LIVE"));

        // The universal resolver restores the separately protected manual value.
        assertEquals("Моя станция", RadioStationStore.resolveUniversal(
                context, "FM", "101.0", "RDS NEW"));
        assertEquals("Моя станция", RadioStationStore.entries(context).get(0).name);
        assertTrue(RadioStationStore.entries(context).get(0).manual);
    }

    @Test
    public void learnedNameCanBeUpdatedByNewStationIdentity() {
        TestContext context = new TestContext();

        assertEquals("Station One", RadioStationStore.resolveUniversal(
                context, "FM", "99.9", "Station One"));
        assertEquals("Station Two", RadioStationStore.resolveUniversal(
                context, "FM", "99.9", "Station Two"));
        assertEquals("Station Two", RadioStationStore.entries(context).get(0).name);
        assertFalse(RadioStationStore.entries(context).get(0).manual);
    }

    @Test
    public void legacyCustomNameIsMigratedConservativelyAsManual() {
        TestContext context = new TestContext();
        context.preferences.edit().putString("station_FM_88.8", "Legacy custom").apply();

        assertEquals("Legacy custom", RadioStationStore.resolveUniversal(
                context, "FM", "88.8", "Fresh RDS"));
        assertEquals("Legacy custom",
                context.preferences.getString("manual_FM_88.8", ""));
        assertTrue(RadioStationStore.entries(context).get(0).manual);
    }

    @Test
    public void deletingStationRemovesValueAndProvenance() {
        TestContext context = new TestContext();
        RadioStationStore.saveManualStation(context, "AM", "1584", "AM Test");

        RadioStationStore.clearStationName(context, "AM", "1584");

        assertTrue(RadioStationStore.entries(context).isEmpty());
        assertFalse(context.preferences.contains("station_AM_1584"));
        assertFalse(context.preferences.contains("manual_AM_1584"));
        assertFalse(context.preferences.contains("origin_AM_1584"));
    }

    @Test
    public void legacyResolverDoesNotCreateUniversalMetadata() {
        TestContext context = new TestContext();

        assertEquals("TEYES Station", RadioStationStore.resolve(
                context, "FM", "100.5", "TEYES Station"));

        assertEquals("TEYES Station",
                context.preferences.getString("station_FM_100.5", ""));
        assertFalse(context.preferences.contains("manual_FM_100.5"));
        assertFalse(context.preferences.contains("origin_FM_100.5"));
    }

    @Test
    public void stationListSortsFmThenAmByNumericFrequency() {
        TestContext context = new TestContext();
        RadioStationStore.saveManualStation(context, "FM", "101.0", "FM High");
        RadioStationStore.saveManualStation(context, "AM", "1584", "AM Station");
        RadioStationStore.saveManualStation(context, "FM", "88.8", "FM Low");

        List<RadioStationStore.Entry> entries = RadioStationStore.entries(context);

        assertEquals(3, entries.size());
        assertEquals("FM", entries.get(0).band);
        assertEquals("88.8", entries.get(0).frequency);
        assertEquals("FM", entries.get(1).band);
        assertEquals("101.0", entries.get(1).frequency);
        assertEquals("AM", entries.get(2).band);
        assertEquals("1584", entries.get(2).frequency);
    }

    @Test
    public void displayNameHidesOnlyTheGeneratedFrequencyFallback() {
        assertEquals("", RadioStationStore.displayName(
                "FM", "101.0", "FM 101.0"));
        assertEquals("", RadioStationStore.displayName(
                "fm", "101", "  fm   101.0  "));
        assertEquals("Europa Plus", RadioStationStore.displayName(
                "FM", "101.0", "  Europa   Plus  "));
    }

    private static final class TestContext extends ContextWrapper {
        final InMemoryPreferences preferences = new InMemoryPreferences();

        private TestContext() {
            super(null);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    private static final class InMemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> updates = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> value) {
                updates.put(key, value == null ? null : new HashSet<>(value));
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                updates.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(updates);
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
