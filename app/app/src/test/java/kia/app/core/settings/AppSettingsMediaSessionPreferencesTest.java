package kia.app.core.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AppSettingsMediaSessionPreferencesTest {
    @Test
    public void packageNormalizerAcceptsAndroidIdsAndRejectsUnsafeValues() {
        assertEquals("com.example.player",
                AppSettings.normalizeMediaSessionPackage(" Com.Example.Player "));
        assertEquals("", AppSettings.normalizeMediaSessionPackage("com.example/player"));
        assertEquals("", AppSettings.normalizeMediaSessionPackage("bad package"));
        assertEquals("", AppSettings.normalizeMediaSessionPackage(null));
    }

    @Test
    public void blockingPreferredPackageClearsPreference() {
        TestContext context = new TestContext();
        AppSettings.setMediaPreferredSessionPackage(context, "Com.Example.Player");

        assertEquals("com.example.player",
                AppSettings.mediaPreferredSessionPackage(context));
        assertTrue(AppSettings.setMediaSessionPackageBlocked(
                context, "com.example.player", true));

        assertEquals("", AppSettings.mediaPreferredSessionPackage(context));
        assertTrue(AppSettings.isMediaSessionPackageBlocked(
                context, "COM.EXAMPLE.PLAYER"));
    }

    @Test
    public void selectingPreferredPackageRemovesItFromDenylist() {
        TestContext context = new TestContext();
        AppSettings.setMediaSessionPackageBlocked(context, "com.example.player", true);

        AppSettings.setMediaPreferredSessionPackage(context, "com.example.player");

        assertEquals("com.example.player",
                AppSettings.mediaPreferredSessionPackage(context));
        assertFalse(AppSettings.isMediaSessionPackageBlocked(
                context, "com.example.player"));
    }

    @Test
    public void summariesAreStableAndBlockedPackagesAreSorted() {
        TestContext context = new TestContext();
        AppSettings.setMediaPreferredSessionPackage(context, "com.music.main");
        AppSettings.setMediaSessionPackageBlocked(context, "com.video.z", true);
        AppSettings.setMediaSessionPackageBlocked(context, "com.audio.a", true);

        assertEquals("com.audio.a, com.video.z",
                AppSettings.mediaBlockedSessionsSummary(context));
        assertEquals("Приоритет: com.music.main · исключено: 2",
                AppSettings.mediaSessionSelectionSummary(context));
    }

    @Test
    public void androidAndUartKeepIndependentSourceDelays() {
        TestContext context = new TestContext();

        assertEquals(300, AppSettings.mediaSourceDelayMs(
                context, AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID));
        assertEquals(1000, AppSettings.mediaSourceDelayMs(
                context, AppSettings.MEDIA_PROFILE_UART_REAL));

        AppSettings.setMediaSourceDelayMs(
                context, AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID, 600);
        AppSettings.setMediaSourceDelayMs(
                context, AppSettings.MEDIA_PROFILE_UART_REAL, 1500);

        assertEquals(600, AppSettings.mediaSourceDelayMs(
                context, AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID));
        assertEquals(1500, AppSettings.mediaSourceDelayMs(
                context, AppSettings.MEDIA_PROFILE_UART_REAL));
    }

    private static final class TestContext extends ContextWrapper {
        private final Map<String, InMemoryPreferences> preferences = new HashMap<>();

        private TestContext() {
            super(null);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            InMemoryPreferences existing = preferences.get(name);
            if (existing != null) return existing;
            InMemoryPreferences created = new InMemoryPreferences();
            preferences.put(name, created);
            return created;
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
