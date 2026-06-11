package kia.app.core.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

import kia.app.navigation.domain.NavigationOutputMode;

public final class AppSettings {
    private static final String NAME = "Kia";
    private static final String LEGACY_NAME = "Kia" + "Clean";
    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_MEDIA = "media_enabled";
    private static final String KEY_MEDIA_PROFILE = "media_profile";
    private static final String KEY_MEDIA_PROFILE_CONFIGURED = "media_profile_configured";
    private static final String KEY_CALL = "call_enabled";
    private static final String KEY_MEDIA_OVERLAY = "media_overlay";
    private static final String KEY_NAVIGATION = "navigation_enabled";
    private static final String KEY_COMPASS = "compass_enabled";
    private static final String KEY_COMPASS_FORCE = "compass_force";
    private static final String KEY_NAV_OUTPUT_MODE = "nav_output_mode";
    private static final String KEY_NAV_TBT = "nav_tbt";
    private static final String KEY_NAV_FINISH_DIRECTION = "nav_finish_direction";
    private static final String KEY_NAV_FINISH_DIRECTION_LEAD_METERS = "nav_finish_direction_lead_meters";
    private static final String KEY_NAV_TEXT_MODE = "nav_text_mode";
    private static final String KEY_NAV_MANEUVER_TEXT_SECONDS = "nav_maneuver_text_seconds";
    private static final String KEY_NAV_SOURCE_MODE = "nav_source_mode";
    private static final String KEY_NAV_ETA_TIME_MODE = "nav_eta_time_mode";
    private static final String KEY_NAV_OVERSPEED_TEXT = "nav_overspeed_text";
    private static final String KEY_NAV_OVERLAY = "nav_overlay";
    private static final String KEY_NAV_MICRO_MANEUVERS = "nav_micro_maneuvers";
    private static final String KEY_NAV_MICRO_HOLD_SECONDS = "nav_micro_hold_seconds";
    private static final String KEY_NAV_MICRO_MAX_DISTANCE_METERS = "nav_micro_max_distance_meters";
    private static final String KEY_NAV_DEBUG_VISIBLE = "nav_debug_visible";
    private static final String KEY_RUNTIME_PERMISSIONS_REQUESTED = "runtime_permissions_requested";
    private static final String KEY_AMP = "amp_enabled";
    private static final String KEY_DIAGNOSTICS = "diagnostics_enabled";
    private static final String KEY_DEBUG_CAN = "debug_can";
    private static final String KEY_CANBUS_DEBUG_VISIBLE = "canbus_debug_visible";
    private static final String KEY_LOGGER_BUS_MODE = "logger_bus_mode";
    private static final String KEY_SAS_RATIO = "sas_ratio";
    private static final String KEY_TPMS_ALERTS = "tpms_alerts";
    private static final String KEY_TPMS_SOUND_ALERTS = "tpms_sound_alerts";
    private static final String KEY_TPMS_LOW_PRESSURE = "tpms_low_pressure";
    private static final String KEY_TPMS_HIGH_PRESSURE = "tpms_high_pressure";
    private static final String KEY_TPMS_LOW_TEMP = "tpms_low_temp";
    private static final String KEY_TPMS_HIGH_TEMP = "tpms_high_temp";
    private static final String KEY_RCTA_OVERLAY = "rcta_overlay";
    private static final String KEY_RCTA_SOUND = "rcta_sound";
    private static final String KEY_RCTA_STYLE = "rcta_style";
    private static final String KEY_RCTA_COLOR = "rcta_color";
    private static final String KEY_RCTA_BG_ALPHA = "rcta_bg_alpha";
    private static final String KEY_OTHER_MEDIA_SOURCE_MODE = "other_media_source_mode";
    private static final String KEY_MEDIA_TEXT_MODE = "media_text_mode";
    private static final String KEY_CALL_SOURCE_MODE = "call_source_mode";
    private static final String KEY_CANBUS_TEMP_SOURCE = "canbus_temp_source";
    private static final String KEY_FIRMWARE_SOURCE = "firmware_source";
    private static final String KEY_USB_PERMISSION_KEY = "usb_permission_key";
    private static final String KEY_USB_PERMISSION_REQUEST_AT = "usb_permission_request_at";
    private static final String KEY_BATTERY_OPTIMIZATION_REQUESTED = "battery_optimization_requested";
    private static final String KEY_MEDIA_TAB_VISIBLE = "media_tab_visible";
    private static final String KEY_LOG_TAB_VISIBLE = "log_tab_visible";
    public static final int OTHER_SOURCE_ANDROID = 0;
    public static final int OTHER_SOURCE_USB = 1;
    public static final int OTHER_SOURCE_BLUETOOTH = 2;
    public static final int OTHER_SOURCE_MY_MUSIC = 3;
    public static final int OTHER_SOURCE_CARPLAY = 4;
    public static final int MEDIA_PROFILE_TEYES = 0;
    public static final int MEDIA_PROFILE_UNIVERSAL_ANDROID = 1;
    public static final int MEDIA_PROFILE_UART_REAL = 2;
    public static final int MEDIA_PROFILE_OFF = 3;
    public static final int MEDIA_TEXT_ARTIST_THEN_TRACK = 0;
    public static final int MEDIA_TEXT_TRACK_ONLY = 1;
    public static final int CALL_SOURCE_ANDROID_AUTO = 0;
    public static final int CALL_SOURCE_CARPLAY = 1;
    public static final int CALL_SOURCE_BLUETOOTH = 2;
    public static final int CALL_SOURCE_MY_MUSIC = 3;
    public static final int CALL_SOURCE_USB = 4;
    public static final int CALL_SOURCE_FM = 5;
    public static final int CANBUS_TEMP_OUTSIDE = 0;
    public static final int CANBUS_TEMP_ENGINE = 1;
    public static final int NAV_SOURCE_AUTO = 0;
    public static final int NAV_SOURCE_YANDEX = 1;
    public static final int NAV_SOURCE_2GIS = 2;
    public static final int NAV_ETA_TIME_ARRIVAL = 0;
    public static final int NAV_ETA_TIME_REMAINING = 1;
    public static final int FIRMWARE_SOURCE_LATEST = 0;
    public static final int FIRMWARE_SOURCE_BUNDLED_03 = 1;
    public static final int FIRMWARE_SOURCE_BUNDLED_02 = 2;
    public static final int FIRMWARE_SOURCE_BUNDLED_04 = 3;
    public static final int LOGGER_BUS_C = 0;
    public static final int LOGGER_BUS_M = 1;
    public static final int LOGGER_BUS_BOTH = 2;
    public static final int RCTA_STYLE_TYPE_1 = 1;
    public static final int RCTA_STYLE_TYPE_2 = 2;
    public static final int RCTA_COLOR_AMBER = 0xffffc43b;
    public static final int RCTA_COLOR_RED = 0xffff5364;
    public static final int RCTA_COLOR_CYAN = 0xff39d3be;
    public static final int RCTA_COLOR_GREEN = 0xff39d38d;
    public static final int RCTA_BACKGROUND_ALPHA_MIN = 0;
    public static final int RCTA_BACKGROUND_ALPHA_MAX = 180;
    public static final int RCTA_BACKGROUND_ALPHA_DEFAULT = RCTA_BACKGROUND_ALPHA_MAX;
    private static final int SCHEMA = 45;
    private static final int DEFAULT_NAV_FINISH_DIRECTION_LEAD_METERS = 0;
    private static final int DEFAULT_NAV_MANEUVER_TEXT_SECONDS = 0;
    private static final int DEFAULT_NAV_MICRO_HOLD_SECONDS = 5;
    private static final int DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS = 150;
    private static final int MAX_NAV_MICRO_MAX_DISTANCE_METERS = 250;
    private static final int DEFAULT_SAS_RATIO = 18;
    private static final int DEFAULT_TPMS_LOW_PRESSURE = 220;
    private static final int DEFAULT_TPMS_HIGH_PRESSURE = 280;
    private static final int DEFAULT_TPMS_LOW_TEMP = -20;
    private static final int DEFAULT_TPMS_HIGH_TEMP = 70;
    private static final long USB_PERMISSION_RETRY_MS = 10L * 60L * 1000L;
    private static boolean legacyPrefsChecked;

    private AppSettings() {
    }

    public static void applyDefaults(Context context) {
        SharedPreferences prefs = prefs(context);
        int schema = prefs.getInt(KEY_SCHEMA, 0);
        if (schema >= SCHEMA) return;
        SharedPreferences.Editor edit = prefs.edit();
        if (schema == 0) {
            edit.putBoolean(KEY_AUTO_START, true)
                    .putBoolean(KEY_MEDIA, true)
                    .putInt(KEY_MEDIA_PROFILE, MEDIA_PROFILE_TEYES)
                    .putBoolean(KEY_MEDIA_PROFILE_CONFIGURED, false)
                    .putBoolean(KEY_CALL, true)
                    .putBoolean(KEY_MEDIA_OVERLAY, false)
                    .putBoolean(KEY_NAVIGATION, true)
                    .putBoolean(KEY_COMPASS, true)
                    .putBoolean(KEY_COMPASS_FORCE, false)
                    .putInt(KEY_NAV_OUTPUT_MODE, NavigationOutputMode.NORMAL)
                    .putBoolean(KEY_NAV_TBT, false)
                    .putBoolean(KEY_NAV_FINISH_DIRECTION, false)
                    .putInt(KEY_NAV_FINISH_DIRECTION_LEAD_METERS, DEFAULT_NAV_FINISH_DIRECTION_LEAD_METERS)
                    .putInt(KEY_NAV_TEXT_MODE, 0)
                    .putInt(KEY_NAV_MANEUVER_TEXT_SECONDS, DEFAULT_NAV_MANEUVER_TEXT_SECONDS)
                    .putInt(KEY_NAV_SOURCE_MODE, NAV_SOURCE_AUTO)
                    .putInt(KEY_NAV_ETA_TIME_MODE, NAV_ETA_TIME_ARRIVAL)
                    .putBoolean(KEY_NAV_OVERSPEED_TEXT, true)
                    .putBoolean(KEY_NAV_OVERLAY, false)
                    .putBoolean(KEY_NAV_MICRO_MANEUVERS, true)
                    .putInt(KEY_NAV_MICRO_HOLD_SECONDS, DEFAULT_NAV_MICRO_HOLD_SECONDS)
                    .putInt(KEY_NAV_MICRO_MAX_DISTANCE_METERS, DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS)
                    .putBoolean(KEY_NAV_DEBUG_VISIBLE, false)
                    .putBoolean(KEY_AMP, false)
                    .putBoolean(KEY_DIAGNOSTICS, true)
                    .putBoolean(KEY_DEBUG_CAN, false)
                    .putBoolean(KEY_CANBUS_DEBUG_VISIBLE, false)
                    .putInt(KEY_LOGGER_BUS_MODE, LOGGER_BUS_M)
                    .putInt(KEY_SAS_RATIO, DEFAULT_SAS_RATIO)
                    .putBoolean(KEY_TPMS_ALERTS, true)
                    .putBoolean(KEY_TPMS_SOUND_ALERTS, true)
                    .putInt(KEY_TPMS_LOW_PRESSURE, DEFAULT_TPMS_LOW_PRESSURE)
                    .putInt(KEY_TPMS_HIGH_PRESSURE, DEFAULT_TPMS_HIGH_PRESSURE)
                    .putInt(KEY_TPMS_LOW_TEMP, DEFAULT_TPMS_LOW_TEMP)
                    .putInt(KEY_TPMS_HIGH_TEMP, DEFAULT_TPMS_HIGH_TEMP)
                    .putBoolean(KEY_RCTA_OVERLAY, true)
                    .putBoolean(KEY_RCTA_SOUND, true)
                    .putInt(KEY_RCTA_STYLE, RCTA_STYLE_TYPE_2)
                    .putInt(KEY_RCTA_COLOR, RCTA_COLOR_RED)
                    .putInt(KEY_RCTA_BG_ALPHA, RCTA_BACKGROUND_ALPHA_DEFAULT)
                    .putInt(KEY_OTHER_MEDIA_SOURCE_MODE, OTHER_SOURCE_ANDROID)
                    .putInt(KEY_MEDIA_TEXT_MODE, MEDIA_TEXT_ARTIST_THEN_TRACK)
                    .putInt(KEY_CALL_SOURCE_MODE, CALL_SOURCE_BLUETOOTH)
                    .putInt(KEY_CANBUS_TEMP_SOURCE, CANBUS_TEMP_OUTSIDE)
                    .putInt(KEY_FIRMWARE_SOURCE, FIRMWARE_SOURCE_LATEST);
        } else {
            if (schema < 2 || !prefs.contains(KEY_NAV_TEXT_MODE)) {
                edit.putInt(KEY_NAV_TEXT_MODE, 4);
            }
            if (schema < 3 || !prefs.contains(KEY_NAV_MICRO_MANEUVERS)) {
                edit.putBoolean(KEY_NAV_MICRO_MANEUVERS, true);
            }
            if (schema < 4 || !prefs.contains(KEY_SAS_RATIO)) {
                edit.putInt(KEY_SAS_RATIO, DEFAULT_SAS_RATIO);
            }
            if (schema < 5 || !prefs.contains(KEY_OTHER_MEDIA_SOURCE_MODE)) {
                edit.putInt(KEY_OTHER_MEDIA_SOURCE_MODE, OTHER_SOURCE_ANDROID);
            }
            if (schema < 6 || !prefs.contains(KEY_NAV_SOURCE_MODE)) {
                edit.putInt(KEY_NAV_SOURCE_MODE, NAV_SOURCE_AUTO);
            }
            if (schema < 40 || !prefs.contains(KEY_NAV_ETA_TIME_MODE)) {
                edit.putInt(KEY_NAV_ETA_TIME_MODE, NAV_ETA_TIME_ARRIVAL);
            }
            if (schema < 7 || !prefs.contains(KEY_NAV_OVERSPEED_TEXT)) {
                edit.putBoolean(KEY_NAV_OVERSPEED_TEXT, true);
            }
            if (schema < 7 || !prefs.contains(KEY_COMPASS_FORCE)) {
                edit.putBoolean(KEY_COMPASS_FORCE, false);
            }
            if (schema < 7 || !prefs.contains(KEY_FIRMWARE_SOURCE)) {
                edit.putInt(KEY_FIRMWARE_SOURCE, FIRMWARE_SOURCE_LATEST);
            }
            if (schema < 8) {
                edit.putBoolean(KEY_DEBUG_CAN, false);
            }
            if (schema < 9 || !prefs.contains(KEY_LOGGER_BUS_MODE)) {
                edit.putInt(KEY_LOGGER_BUS_MODE, LOGGER_BUS_M);
            }
            if (schema < 10) {
                edit.putBoolean(KEY_COMPASS_FORCE, true);
            }
            if (schema < 11) {
                edit.putBoolean(KEY_LOG_TAB_VISIBLE, false);
            }
            if (schema < 23 || !prefs.contains(KEY_MEDIA_TAB_VISIBLE)) {
                edit.putBoolean(KEY_MEDIA_TAB_VISIBLE, true);
            }
            if (schema < 12 || !prefs.contains(KEY_MEDIA_TEXT_MODE)) {
                edit.putInt(KEY_MEDIA_TEXT_MODE, MEDIA_TEXT_ARTIST_THEN_TRACK);
            }
            if (schema < 37 || !prefs.contains(KEY_MEDIA_PROFILE)) {
                edit.putInt(KEY_MEDIA_PROFILE,
                        prefs.getBoolean(KEY_MEDIA, true) ? MEDIA_PROFILE_TEYES : MEDIA_PROFILE_OFF);
            }
            if (schema < 37 || !prefs.contains(KEY_MEDIA_PROFILE_CONFIGURED)) {
                edit.putBoolean(KEY_MEDIA_PROFILE_CONFIGURED, true);
            }
            if (schema < 44) {
                edit.putBoolean(KEY_MEDIA_PROFILE_CONFIGURED, false);
            }
            if (schema < 13) {
                edit.putBoolean(KEY_COMPASS, true)
                        .putBoolean(KEY_COMPASS_FORCE, false)
                        .putBoolean(KEY_NAV_DEBUG_VISIBLE, false)
                        .putBoolean(KEY_CANBUS_DEBUG_VISIBLE, false);
            }
            if (schema < 14 && prefs.getInt(KEY_NAV_TEXT_MODE, 0) == 4) {
                edit.putInt(KEY_NAV_TEXT_MODE, 0);
            }
            if (schema < 15 || !prefs.contains(KEY_CALL_SOURCE_MODE)) {
                edit.putInt(KEY_CALL_SOURCE_MODE, CALL_SOURCE_BLUETOOTH);
            } else if (schema < 16) {
                int oldCallSource = prefs.getInt(KEY_CALL_SOURCE_MODE, CALL_SOURCE_BLUETOOTH);
                if (oldCallSource != CALL_SOURCE_ANDROID_AUTO
                        && oldCallSource != CALL_SOURCE_CARPLAY
                        && oldCallSource != CALL_SOURCE_BLUETOOTH) {
                    edit.putInt(KEY_CALL_SOURCE_MODE, CALL_SOURCE_BLUETOOTH);
                }
            }
            if (schema < 17 || !prefs.contains(KEY_TPMS_ALERTS)) {
                edit.putBoolean(KEY_TPMS_ALERTS, true);
            }
            if (schema < 17 || !prefs.contains(KEY_TPMS_LOW_PRESSURE)) {
                edit.putInt(KEY_TPMS_LOW_PRESSURE, DEFAULT_TPMS_LOW_PRESSURE);
            }
            if (schema < 17 || !prefs.contains(KEY_TPMS_HIGH_PRESSURE)) {
                edit.putInt(KEY_TPMS_HIGH_PRESSURE, DEFAULT_TPMS_HIGH_PRESSURE);
            }
            if (schema < 17 || !prefs.contains(KEY_TPMS_LOW_TEMP)) {
                edit.putInt(KEY_TPMS_LOW_TEMP, DEFAULT_TPMS_LOW_TEMP);
            }
            if (schema < 17 || !prefs.contains(KEY_TPMS_HIGH_TEMP)) {
                edit.putInt(KEY_TPMS_HIGH_TEMP, DEFAULT_TPMS_HIGH_TEMP);
            }
            if (schema < 18 || !prefs.contains(KEY_TPMS_SOUND_ALERTS)) {
                edit.putBoolean(KEY_TPMS_SOUND_ALERTS, true);
            }
            if (schema < 19 || !prefs.contains(KEY_RCTA_OVERLAY)) {
                edit.putBoolean(KEY_RCTA_OVERLAY, true);
            }
            if (schema < 19 || !prefs.contains(KEY_RCTA_SOUND)) {
                edit.putBoolean(KEY_RCTA_SOUND, true);
            }
            if (schema < 24 || !prefs.contains(KEY_RCTA_STYLE)) {
                edit.putInt(KEY_RCTA_STYLE, RCTA_STYLE_TYPE_1);
            }
            if (schema < 25 || !prefs.contains(KEY_RCTA_COLOR)) {
                edit.putInt(KEY_RCTA_COLOR, RCTA_COLOR_AMBER);
            }
            if (schema < 25 || !prefs.contains(KEY_RCTA_BG_ALPHA)) {
                edit.putInt(KEY_RCTA_BG_ALPHA, RCTA_BACKGROUND_ALPHA_DEFAULT);
            }
            if (schema < 45) {
                edit.putInt(KEY_TPMS_LOW_PRESSURE, DEFAULT_TPMS_LOW_PRESSURE)
                        .putInt(KEY_TPMS_HIGH_PRESSURE, DEFAULT_TPMS_HIGH_PRESSURE)
                        .putInt(KEY_SAS_RATIO, DEFAULT_SAS_RATIO)
                        .putBoolean(KEY_AMP, false)
                        .putInt(KEY_RCTA_STYLE, RCTA_STYLE_TYPE_2)
                        .putInt(KEY_RCTA_COLOR, RCTA_COLOR_RED)
                        .putInt(KEY_RCTA_BG_ALPHA, RCTA_BACKGROUND_ALPHA_DEFAULT);
            }
            if (schema < 26 || !prefs.contains(KEY_NAV_FINISH_DIRECTION)) {
                edit.putBoolean(KEY_NAV_FINISH_DIRECTION, false);
            }
            if (schema < 27 || !prefs.contains(KEY_NAV_MICRO_HOLD_SECONDS)) {
                edit.putInt(KEY_NAV_MICRO_HOLD_SECONDS, DEFAULT_NAV_MICRO_HOLD_SECONDS);
            }
            if (schema < 27 && prefs.getBoolean(KEY_NAV_FINISH_DIRECTION, false)
                    && prefs.getBoolean(KEY_NAV_TBT, false)) {
                edit.putBoolean(KEY_NAV_TBT, false);
            }
            if (schema < 28 || !prefs.contains(KEY_NAV_FINISH_DIRECTION_LEAD_METERS)) {
                edit.putInt(KEY_NAV_FINISH_DIRECTION_LEAD_METERS,
                        DEFAULT_NAV_FINISH_DIRECTION_LEAD_METERS);
            }
            if (schema < 29 || !prefs.contains(KEY_NAV_MANEUVER_TEXT_SECONDS)) {
                edit.putInt(KEY_NAV_MANEUVER_TEXT_SECONDS, DEFAULT_NAV_MANEUVER_TEXT_SECONDS);
            }
            if (schema < 30 || !prefs.contains(KEY_NAV_OUTPUT_MODE)) {
                int migratedMode = legacyNavOutputMode(prefs);
                edit.putInt(KEY_NAV_OUTPUT_MODE, migratedMode)
                        .putBoolean(KEY_NAV_TBT, NavigationOutputMode.isTbt(migratedMode))
                        .putBoolean(KEY_NAV_FINISH_DIRECTION,
                                NavigationOutputMode.isFinishDirection(migratedMode));
            }
            if (schema < 31 || !prefs.contains(KEY_NAV_MICRO_MAX_DISTANCE_METERS)) {
                edit.putInt(KEY_NAV_MICRO_MAX_DISTANCE_METERS, DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS);
            }
            if (schema < 32) {
                edit.putInt(KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                        normalizeNavMicroMaxDistanceMeters(prefs.getInt(KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                                DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS)));
            }
            if (schema < 36) {
                edit.putInt(KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                        normalizeNavMicroMaxDistanceMeters(prefs.getInt(KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                                DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS)));
            }
            if (schema < 20 || !prefs.contains(KEY_CALL)) {
                edit.putBoolean(KEY_CALL, true);
            }
            if (schema < 20 || !prefs.contains(KEY_CANBUS_TEMP_SOURCE)) {
                edit.putInt(KEY_CANBUS_TEMP_SOURCE, CANBUS_TEMP_OUTSIDE);
            }
            if (schema < 21 || !prefs.contains(KEY_MEDIA_OVERLAY)) {
                edit.putBoolean(KEY_MEDIA_OVERLAY, false);
            }
        }
        edit.putInt(KEY_SCHEMA, SCHEMA).apply();
    }

    public static boolean autoStart(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_START, true);
    }

    public static void setAutoStart(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, value).apply();
    }

    public static boolean mediaEnabled(Context context) {
        return mediaProfile(context) != MEDIA_PROFILE_OFF;
    }

    public static void setMediaEnabled(Context context, boolean value) {
        if (value) {
            int current = mediaProfile(context);
            setMediaProfile(context, current == MEDIA_PROFILE_OFF ? MEDIA_PROFILE_TEYES : current);
        } else {
            setMediaProfile(context, MEDIA_PROFILE_OFF);
        }
    }

    public static int mediaProfile(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_MEDIA_PROFILE)) {
            return p.getBoolean(KEY_MEDIA, true) ? MEDIA_PROFILE_TEYES : MEDIA_PROFILE_OFF;
        }
        return normalizeMediaProfile(p.getInt(KEY_MEDIA_PROFILE, MEDIA_PROFILE_TEYES));
    }

    public static void setMediaProfile(Context context, int value) {
        int normalized = normalizeMediaProfile(value);
        prefs(context).edit()
                .putInt(KEY_MEDIA_PROFILE, normalized)
                .putBoolean(KEY_MEDIA, normalized != MEDIA_PROFILE_OFF)
                .putBoolean(KEY_MEDIA_PROFILE_CONFIGURED, true)
                .apply();
    }

    public static boolean teyesMediaProfile(Context context) {
        return mediaProfile(context) == MEDIA_PROFILE_TEYES;
    }

    public static boolean universalMediaProfile(Context context) {
        int profile = mediaProfile(context);
        return profile == MEDIA_PROFILE_UNIVERSAL_ANDROID || profile == MEDIA_PROFILE_UART_REAL;
    }

    public static boolean uartRealMediaProfile(Context context) {
        return mediaProfile(context) == MEDIA_PROFILE_UART_REAL;
    }

    public static boolean mediaProfileConfigured(Context context) {
        return prefs(context).getBoolean(KEY_MEDIA_PROFILE_CONFIGURED, true);
    }

    public static void setMediaProfileConfigured(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_MEDIA_PROFILE_CONFIGURED, value).apply();
    }

    public static boolean callEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CALL, true);
    }

    public static void setCallEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_CALL, value).apply();
    }

    public static boolean mediaOverlayEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MEDIA_OVERLAY, false);
    }

    public static void setMediaOverlayEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_MEDIA_OVERLAY, value).apply();
    }

    public static boolean navigationEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NAVIGATION, true);
    }

    public static void setNavigationEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_NAVIGATION, value).apply();
    }

    public static boolean compassEnabled(Context context) {
        return true;
    }

    public static void setCompassEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_COMPASS, true).apply();
    }

    public static boolean compassForceEnabled(Context context) {
        return false;
    }

    public static void setCompassForceEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_COMPASS_FORCE, false).apply();
    }

    public static boolean navTbt(Context context) {
        return NavigationOutputMode.isTbt(navOutputMode(context));
    }

    public static void setNavTbt(Context context, boolean value) {
        if (value) {
            setNavOutputMode(context, NavigationOutputMode.TBT);
        } else if (navTbt(context)) {
            setNavOutputMode(context, NavigationOutputMode.NORMAL);
        }
    }

    public static boolean navFinishDirectionMode(Context context) {
        return NavigationOutputMode.isFinishDirection(navOutputMode(context));
    }

    public static void setNavFinishDirectionMode(Context context, boolean value) {
        if (value) {
            setNavOutputMode(context, NavigationOutputMode.FINISH_DIRECTION);
        } else if (navFinishDirectionMode(context)) {
            setNavOutputMode(context, NavigationOutputMode.NORMAL);
        }
    }

    public static int navOutputMode(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_NAV_OUTPUT_MODE)) return legacyNavOutputMode(p);
        return NavigationOutputMode.normalize(p.getInt(KEY_NAV_OUTPUT_MODE, NavigationOutputMode.NORMAL));
    }

    public static void setNavOutputMode(Context context, int mode) {
        int normalized = NavigationOutputMode.normalize(mode);
        prefs(context).edit()
                .putInt(KEY_NAV_OUTPUT_MODE, normalized)
                .putBoolean(KEY_NAV_TBT, NavigationOutputMode.isTbt(normalized))
                .putBoolean(KEY_NAV_FINISH_DIRECTION, NavigationOutputMode.isFinishDirection(normalized))
                .apply();
    }

    public static int navFinishDirectionLeadMeters(Context context) {
        return 0;
    }

    public static void setNavFinishDirectionLeadMeters(Context context, int value) {
        prefs(context).edit().putInt(KEY_NAV_FINISH_DIRECTION_LEAD_METERS, 0).apply();
    }

    public static int navTextMode(Context context) {
        int mode = prefs(context).getInt(KEY_NAV_TEXT_MODE, 0);
        if (mode >= 3) return 0;
        return clamp(mode, 0, 2);
    }

    public static void setNavTextMode(Context context, int value) {
        prefs(context).edit().putInt(KEY_NAV_TEXT_MODE, clamp(value, 0, 2)).apply();
    }

    public static int navManeuverTextSeconds(Context context) {
        return clamp(prefs(context).getInt(KEY_NAV_MANEUVER_TEXT_SECONDS,
                DEFAULT_NAV_MANEUVER_TEXT_SECONDS), 0, 15);
    }

    public static void setNavManeuverTextSeconds(Context context, int value) {
        prefs(context).edit().putInt(KEY_NAV_MANEUVER_TEXT_SECONDS, clamp(value, 0, 15)).apply();
    }

    public static boolean navOverspeedTextEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NAV_OVERSPEED_TEXT, true);
    }

    public static void setNavOverspeedTextEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_NAV_OVERSPEED_TEXT, value).apply();
    }

    public static int navSourceMode(Context context) {
        return clamp(prefs(context).getInt(KEY_NAV_SOURCE_MODE, NAV_SOURCE_AUTO),
                NAV_SOURCE_AUTO, NAV_SOURCE_2GIS);
    }

    public static void setNavSourceMode(Context context, int value) {
        prefs(context).edit().putInt(KEY_NAV_SOURCE_MODE,
                clamp(value, NAV_SOURCE_AUTO, NAV_SOURCE_2GIS)).apply();
    }

    public static boolean yandexNavigationEnabled(Context context) {
        int mode = navSourceMode(context);
        return mode == NAV_SOURCE_AUTO || mode == NAV_SOURCE_YANDEX;
    }

    public static boolean dgisNavigationEnabled(Context context) {
        int mode = navSourceMode(context);
        return mode == NAV_SOURCE_AUTO || mode == NAV_SOURCE_2GIS;
    }

    public static String navSourceLabel(Context context) {
        switch (navSourceMode(context)) {
            case NAV_SOURCE_YANDEX:
                return "Yandex";
            case NAV_SOURCE_2GIS:
                return "2GIS";
            case NAV_SOURCE_AUTO:
            default:
                return "Auto";
        }
    }

    public static int navEtaTimeMode(Context context) {
        return clamp(prefs(context).getInt(KEY_NAV_ETA_TIME_MODE, NAV_ETA_TIME_ARRIVAL),
                NAV_ETA_TIME_ARRIVAL, NAV_ETA_TIME_REMAINING);
    }

    public static void setNavEtaTimeMode(Context context, int value) {
        prefs(context).edit().putInt(KEY_NAV_ETA_TIME_MODE,
                value == NAV_ETA_TIME_REMAINING
                        ? NAV_ETA_TIME_REMAINING : NAV_ETA_TIME_ARRIVAL).apply();
    }

    public static String navEtaTimeModeLabel(Context context) {
        return navEtaTimeMode(context) == NAV_ETA_TIME_REMAINING
                ? "Осталось" : "Прибытие";
    }

    public static boolean navOverlayEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NAV_OVERLAY, false);
    }

    public static void setNavOverlayEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_NAV_OVERLAY, value).apply();
    }

    public static boolean navMicroManeuvers(Context context) {
        return prefs(context).getBoolean(KEY_NAV_MICRO_MANEUVERS, false);
    }

    public static void setNavMicroManeuvers(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_NAV_MICRO_MANEUVERS, value).apply();
    }

    public static int navMicroHoldSeconds(Context context) {
        return clamp(prefs(context).getInt(KEY_NAV_MICRO_HOLD_SECONDS, DEFAULT_NAV_MICRO_HOLD_SECONDS),
                5, 15);
    }

    public static void setNavMicroHoldSeconds(Context context, int value) {
        prefs(context).edit().putInt(KEY_NAV_MICRO_HOLD_SECONDS, clamp(value, 5, 15)).apply();
    }

    public static int navMicroMaxDistanceMeters(Context context) {
        return normalizeNavMicroMaxDistanceMeters(prefs(context).getInt(KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS));
    }

    public static void setNavMicroMaxDistanceMeters(Context context, int value) {
        prefs(context).edit().putInt(KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                normalizeNavMicroMaxDistanceMeters(value)).apply();
    }

    private static int normalizeNavMicroMaxDistanceMeters(int value) {
        int clamped = clamp(value, DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS,
                MAX_NAV_MICRO_MAX_DISTANCE_METERS);
        if (clamped <= 150) return 150;
        if (clamped <= 200) return 200;
        return 250;
    }

    public static boolean navDebugVisible(Context context) {
        return prefs(context).getBoolean(KEY_NAV_DEBUG_VISIBLE, false);
    }

    public static void setNavDebugVisible(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_NAV_DEBUG_VISIBLE, value).apply();
    }

    public static boolean ampEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AMP, false);
    }

    public static void setAmpEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_AMP, value).apply();
    }

    public static boolean diagnosticsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DIAGNOSTICS, true);
    }

    public static void setDiagnosticsEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_DIAGNOSTICS, value).apply();
    }

    public static boolean debugCan(Context context) {
        return prefs(context).getBoolean(KEY_DEBUG_CAN, false);
    }

    public static void setDebugCan(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_DEBUG_CAN, value).apply();
    }

    public static boolean canbusDebugVisible(Context context) {
        return prefs(context).getBoolean(KEY_CANBUS_DEBUG_VISIBLE, false);
    }

    public static void setCanbusDebugVisible(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_CANBUS_DEBUG_VISIBLE, value).apply();
    }

    public static int loggerBusMode(Context context) {
        return clamp(prefs(context).getInt(KEY_LOGGER_BUS_MODE, LOGGER_BUS_M),
                LOGGER_BUS_C, LOGGER_BUS_BOTH);
    }

    public static void setLoggerBusMode(Context context, int value) {
        prefs(context).edit().putInt(KEY_LOGGER_BUS_MODE,
                clamp(value, LOGGER_BUS_C, LOGGER_BUS_BOTH)).apply();
    }

    public static String loggerBusLabel(Context context) {
        switch (loggerBusMode(context)) {
            case LOGGER_BUS_C:
                return "C-CAN";
            case LOGGER_BUS_BOTH:
                return "C-CAN + M-CAN";
            case LOGGER_BUS_M:
            default:
                return "M-CAN";
        }
    }

    public static int sasRatio(Context context) {
        return clamp(prefs(context).getInt(KEY_SAS_RATIO, DEFAULT_SAS_RATIO), 1, 255);
    }

    public static void setSasRatio(Context context, int value) {
        prefs(context).edit().putInt(KEY_SAS_RATIO, clamp(value, 1, 255)).apply();
    }

    public static boolean tpmsAlertsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TPMS_ALERTS, true);
    }

    public static void setTpmsAlertsEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_TPMS_ALERTS, value).apply();
    }

    public static boolean tpmsSoundAlertsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TPMS_SOUND_ALERTS, true);
    }

    public static void setTpmsSoundAlertsEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_TPMS_SOUND_ALERTS, value).apply();
    }

    public static int tpmsLowPressureKpa(Context context) {
        return clamp(prefs(context).getInt(KEY_TPMS_LOW_PRESSURE, DEFAULT_TPMS_LOW_PRESSURE), 80, 450);
    }

    public static void setTpmsLowPressureKpa(Context context, int value) {
        prefs(context).edit().putInt(KEY_TPMS_LOW_PRESSURE, clamp(value, 80, 450)).apply();
    }

    public static int tpmsHighPressureKpa(Context context) {
        return clamp(prefs(context).getInt(KEY_TPMS_HIGH_PRESSURE, DEFAULT_TPMS_HIGH_PRESSURE), 120, 600);
    }

    public static void setTpmsHighPressureKpa(Context context, int value) {
        prefs(context).edit().putInt(KEY_TPMS_HIGH_PRESSURE, clamp(value, 120, 600)).apply();
    }

    public static int tpmsLowTempC(Context context) {
        return clamp(prefs(context).getInt(KEY_TPMS_LOW_TEMP, DEFAULT_TPMS_LOW_TEMP), -60, 80);
    }

    public static void setTpmsLowTempC(Context context, int value) {
        prefs(context).edit().putInt(KEY_TPMS_LOW_TEMP, clamp(value, -60, 80)).apply();
    }

    public static int tpmsHighTempC(Context context) {
        return clamp(prefs(context).getInt(KEY_TPMS_HIGH_TEMP, DEFAULT_TPMS_HIGH_TEMP), -20, 160);
    }

    public static void setTpmsHighTempC(Context context, int value) {
        prefs(context).edit().putInt(KEY_TPMS_HIGH_TEMP, clamp(value, -20, 160)).apply();
    }

    public static boolean rctaOverlayEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RCTA_OVERLAY, true);
    }

    public static void setRctaOverlayEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_RCTA_OVERLAY, value).apply();
    }

    public static boolean rctaSoundEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RCTA_SOUND, true);
    }

    public static void setRctaSoundEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_RCTA_SOUND, value).apply();
    }

    public static int rctaStyle(Context context) {
        return normalizeRctaStyle(prefs(context).getInt(KEY_RCTA_STYLE, RCTA_STYLE_TYPE_2));
    }

    public static void setRctaStyle(Context context, int value) {
        prefs(context).edit().putInt(KEY_RCTA_STYLE, normalizeRctaStyle(value)).apply();
    }

    public static String rctaStyleLabel(Context context) {
        return rctaStyle(context) == RCTA_STYLE_TYPE_2 ? "Тип 2" : "Тип 1";
    }

    public static int rctaColor(Context context) {
        return normalizeRctaColor(prefs(context).getInt(KEY_RCTA_COLOR, RCTA_COLOR_RED));
    }

    public static void setRctaColor(Context context, int value) {
        prefs(context).edit().putInt(KEY_RCTA_COLOR, normalizeRctaColor(value)).apply();
    }

    public static String rctaColorLabel(Context context) {
        switch (rctaColor(context)) {
            case RCTA_COLOR_RED:
                return "красный";
            case RCTA_COLOR_CYAN:
                return "голубой";
            case RCTA_COLOR_GREEN:
                return "зелёный";
            case RCTA_COLOR_AMBER:
            default:
                return "янтарный";
        }
    }

    public static int rctaBackgroundAlpha(Context context) {
        return clamp(prefs(context).getInt(KEY_RCTA_BG_ALPHA, RCTA_BACKGROUND_ALPHA_DEFAULT),
                RCTA_BACKGROUND_ALPHA_MIN, RCTA_BACKGROUND_ALPHA_MAX);
    }

    public static void setRctaBackgroundAlpha(Context context, int value) {
        prefs(context).edit().putInt(KEY_RCTA_BG_ALPHA, clamp(value,
                RCTA_BACKGROUND_ALPHA_MIN, RCTA_BACKGROUND_ALPHA_MAX)).apply();
    }

    public static int otherMediaSourceMode(Context context) {
        return normalizeOtherSource(prefs(context).getInt(KEY_OTHER_MEDIA_SOURCE_MODE, OTHER_SOURCE_ANDROID));
    }

    public static void setOtherMediaSourceMode(Context context, int value) {
        prefs(context).edit().putInt(KEY_OTHER_MEDIA_SOURCE_MODE,
                normalizeOtherSource(value)).apply();
    }

    public static String otherMediaSourceLabel(Context context) {
        switch (otherMediaSourceMode(context)) {
            case OTHER_SOURCE_USB:
                return "USB";
            case OTHER_SOURCE_BLUETOOTH:
                return "Bluetooth";
            case OTHER_SOURCE_MY_MUSIC:
                return "My Music";
            case OTHER_SOURCE_CARPLAY:
                return "CarPlay";
            case OTHER_SOURCE_ANDROID:
            default:
                return "Android";
        }
    }

    public static int mediaTextMode(Context context) {
        return clamp(prefs(context).getInt(KEY_MEDIA_TEXT_MODE, MEDIA_TEXT_ARTIST_THEN_TRACK),
                MEDIA_TEXT_ARTIST_THEN_TRACK, MEDIA_TEXT_TRACK_ONLY);
    }

    public static void setMediaTextMode(Context context, int value) {
        prefs(context).edit().putInt(KEY_MEDIA_TEXT_MODE,
                clamp(value, MEDIA_TEXT_ARTIST_THEN_TRACK, MEDIA_TEXT_TRACK_ONLY)).apply();
    }

    public static String mediaTextModeLabel(Context context) {
        return mediaTextMode(context) == MEDIA_TEXT_TRACK_ONLY ? "Только трек" : "Автор + трек";
    }

    public static String mediaProfileLabel(Context context) {
        return mediaProfileLabel(mediaProfile(context));
    }

    public static String mediaProfileLabel(int profile) {
        switch (normalizeMediaProfile(profile)) {
            case MEDIA_PROFILE_OFF:
                return "Выкл";
            case MEDIA_PROFILE_UNIVERSAL_ANDROID:
                return "Universal Android";
            case MEDIA_PROFILE_UART_REAL:
                return "UART real + Android";
            case MEDIA_PROFILE_TEYES:
            default:
                return "TEYES / CC4 Pro";
        }
    }

    public static int callSourceMode(Context context) {
        return normalizeCallSource(prefs(context).getInt(KEY_CALL_SOURCE_MODE, CALL_SOURCE_CARPLAY));
    }

    public static void setCallSourceMode(Context context, int value) {
        prefs(context).edit().putInt(KEY_CALL_SOURCE_MODE, normalizeCallSource(value)).apply();
    }

    public static String callSourceLabel(Context context) {
        switch (callSourceMode(context)) {
            case CALL_SOURCE_ANDROID_AUTO:
                return "Android Auto";
            case CALL_SOURCE_BLUETOOTH:
                return "BT Audio";
            case CALL_SOURCE_MY_MUSIC:
                return "My Music";
            case CALL_SOURCE_USB:
                return "USB Music";
            case CALL_SOURCE_FM:
                return "FM Radio";
            case CALL_SOURCE_CARPLAY:
            default:
                return "CarPlay";
        }
    }

    public static int canbusTemperatureSource(Context context) {
        return clamp(prefs(context).getInt(KEY_CANBUS_TEMP_SOURCE, CANBUS_TEMP_OUTSIDE),
                CANBUS_TEMP_OUTSIDE, CANBUS_TEMP_ENGINE);
    }

    public static void setCanbusTemperatureSource(Context context, int value) {
        prefs(context).edit().putInt(KEY_CANBUS_TEMP_SOURCE,
                clamp(value, CANBUS_TEMP_OUTSIDE, CANBUS_TEMP_ENGINE)).apply();
    }

    public static String canbusTemperatureSourceLabel(Context context) {
        return canbusTemperatureSource(context) == CANBUS_TEMP_ENGINE ? "двигатель" : "улица";
    }

    public static int firmwareSource(Context context) {
        return clamp(prefs(context).getInt(KEY_FIRMWARE_SOURCE, FIRMWARE_SOURCE_LATEST),
                FIRMWARE_SOURCE_LATEST, FIRMWARE_SOURCE_BUNDLED_04);
    }

    public static void setFirmwareSource(Context context, int value) {
        prefs(context).edit().putInt(KEY_FIRMWARE_SOURCE,
                clamp(value, FIRMWARE_SOURCE_LATEST, FIRMWARE_SOURCE_BUNDLED_04)).apply();
    }

    public static String firmwareSourceLabel(Context context) {
        switch (firmwareSource(context)) {
            case FIRMWARE_SOURCE_BUNDLED_04:
                return "FW 04 / 04.35.02.04";
            case FIRMWARE_SOURCE_BUNDLED_03:
                return "FW 03 / 04.35.02.03";
            case FIRMWARE_SOURCE_BUNDLED_02:
                return "FW 02 / 04.35.02.02";
            case FIRMWARE_SOURCE_LATEST:
            default:
                return "GitHub latest";
        }
    }

    public static boolean runtimePermissionsRequested(Context context) {
        return prefs(context).getBoolean(KEY_RUNTIME_PERMISSIONS_REQUESTED, false);
    }

    public static void setRuntimePermissionsRequested(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_RUNTIME_PERMISSIONS_REQUESTED, value).apply();
    }

    public static boolean shouldRequestUsbPermission(Context context, String key) {
        SharedPreferences prefs = prefs(context);
        String lastKey = prefs.getString(KEY_USB_PERMISSION_KEY, "");
        long lastAt = prefs.getLong(KEY_USB_PERMISSION_REQUEST_AT, 0L);
        long now = System.currentTimeMillis();
        return !key.equals(lastKey) || now - lastAt > USB_PERMISSION_RETRY_MS;
    }

    public static void markUsbPermissionRequested(Context context, String key) {
        prefs(context).edit()
                .putString(KEY_USB_PERMISSION_KEY, key)
                .putLong(KEY_USB_PERMISSION_REQUEST_AT, System.currentTimeMillis())
                .apply();
    }

    public static void clearUsbPermissionRequest(Context context, String key) {
        SharedPreferences prefs = prefs(context);
        if (!key.equals(prefs.getString(KEY_USB_PERMISSION_KEY, ""))) return;
        prefs.edit()
                .remove(KEY_USB_PERMISSION_KEY)
                .remove(KEY_USB_PERMISSION_REQUEST_AT)
                .apply();
    }

    public static boolean batteryOptimizationRequested(Context context) {
        return prefs(context).getBoolean(KEY_BATTERY_OPTIMIZATION_REQUESTED, false);
    }

    public static void setBatteryOptimizationRequested(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_BATTERY_OPTIMIZATION_REQUESTED, value).apply();
    }

    public static boolean mediaTabVisible(Context context) {
        return prefs(context).getBoolean(KEY_MEDIA_TAB_VISIBLE, true);
    }

    public static void setMediaTabVisible(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_MEDIA_TAB_VISIBLE, value).apply();
    }

    public static boolean logTabVisible(Context context) {
        return prefs(context).getBoolean(KEY_LOG_TAB_VISIBLE, false);
    }

    public static void setLogTabVisible(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_LOG_TAB_VISIBLE, value).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int normalizeOtherSource(int value) {
        if (value == OTHER_SOURCE_USB || value == OTHER_SOURCE_BLUETOOTH
                || value == OTHER_SOURCE_MY_MUSIC || value == OTHER_SOURCE_CARPLAY) {
            return value;
        }
        return OTHER_SOURCE_ANDROID;
    }

    private static int normalizeCallSource(int value) {
        if (value == CALL_SOURCE_ANDROID_AUTO
                || value == CALL_SOURCE_CARPLAY
                || value == CALL_SOURCE_BLUETOOTH) {
            return value;
        }
        return CALL_SOURCE_BLUETOOTH;
    }

    private static int normalizeMediaProfile(int value) {
        if (value == MEDIA_PROFILE_UNIVERSAL_ANDROID
                || value == MEDIA_PROFILE_UART_REAL
                || value == MEDIA_PROFILE_OFF) {
            return value;
        }
        return MEDIA_PROFILE_TEYES;
    }

    private static int normalizeRctaStyle(int value) {
        return value == RCTA_STYLE_TYPE_2 ? RCTA_STYLE_TYPE_2 : RCTA_STYLE_TYPE_1;
    }

    private static int normalizeRctaColor(int value) {
        if (value == RCTA_COLOR_RED || value == RCTA_COLOR_CYAN || value == RCTA_COLOR_GREEN) {
            return value;
        }
        return RCTA_COLOR_AMBER;
    }

    private static int legacyNavOutputMode(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_NAV_FINISH_DIRECTION, false)) {
            return NavigationOutputMode.FINISH_DIRECTION;
        }
        if (prefs.getBoolean(KEY_NAV_TBT, false)) {
            return NavigationOutputMode.TBT;
        }
        return NavigationOutputMode.NORMAL;
    }

    private static SharedPreferences prefs(Context context) {
        migrateLegacyPrefs(context);
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static void migrateLegacyPrefs(Context context) {
        if (legacyPrefsChecked) return;
        synchronized (AppSettings.class) {
            if (legacyPrefsChecked) return;
            SharedPreferences current = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
            SharedPreferences legacy = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE);
            if (!current.contains(KEY_SCHEMA) && legacy.contains(KEY_SCHEMA)) {
                SharedPreferences.Editor edit = current.edit();
                for (Map.Entry<String, ?> entry : legacy.getAll().entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Boolean) {
                        edit.putBoolean(entry.getKey(), (Boolean) value);
                    } else if (value instanceof Integer) {
                        edit.putInt(entry.getKey(), (Integer) value);
                    } else if (value instanceof Long) {
                        edit.putLong(entry.getKey(), (Long) value);
                    } else if (value instanceof Float) {
                        edit.putFloat(entry.getKey(), (Float) value);
                    } else if (value instanceof String) {
                        edit.putString(entry.getKey(), (String) value);
                    }
                }
                edit.apply();
            }
            legacyPrefsChecked = true;
        }
    }
}
