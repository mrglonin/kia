package kia.app.core.settings

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TreeSet
import kia.app.navigation.domain.NavigationOutputMode

object AppSettings {
    private const val NAME: String = "Kia"
    private const val LEGACY_NAME: String = "Kia" + "Clean"
    private const val KEY_SCHEMA: String = "schema"
    private const val KEY_SUNROOF_OVERLAY: String = "sunroof_overlay"
    private const val KEY_SUNROOF_PARKING_WARNING: String = "sunroof_parking_warning"
    private const val KEY_SUNROOF_SOUND: String = "sunroof_sound"
    private const val KEY_SUNROOF_TRANSITION_SOUND: String = "sunroof_transition_sound"
    private const val KEY_AUTO_START: String = "auto_start"
    private const val KEY_MEDIA: String = "media_enabled"
    private const val KEY_MEDIA_PROFILE: String = "media_profile"
    private const val KEY_MEDIA_PROFILE_CONFIGURED: String = "media_profile_configured"
    private const val KEY_CALL: String = "call_enabled"
    private const val KEY_MEDIA_OVERLAY: String = "media_overlay"
    private const val KEY_ADAS_ENABLED: String = "adas_enabled"
    private const val KEY_NAVIGATION: String = "navigation_enabled"
    private const val KEY_COMPASS: String = "compass_enabled"
    private const val KEY_COMPASS_FORCE: String = "compass_force"
    private const val KEY_COMPASS_KIA_NAVI_FORMAT: String = "compass_kia_navi_format"
    private const val KEY_NAV_OUTPUT_MODE: String = "nav_output_mode"
    private const val KEY_NAV_TBT: String = "nav_tbt"
    private const val KEY_NAV_FINISH_DIRECTION: String = "nav_finish_direction"
    private const val KEY_NAV_FINISH_DIRECTION_LEAD_METERS: String =
        "nav_finish_direction_lead_meters"
    private const val KEY_NAV_FINISH_COMPASS_AUTO: String = "nav_finish_compass_auto"
    private const val KEY_NAV_TEXT_MODE: String = "nav_text_mode"
    private const val KEY_NAV_MANEUVER_TEXT_SECONDS: String = "nav_maneuver_text_seconds"
    private const val KEY_NAV_SOURCE_MODE: String = "nav_source_mode"
    private const val KEY_NAV_ETA_TIME_MODE: String = "nav_eta_time_mode"
    private const val KEY_NAV_OVERSPEED_TEXT: String = "nav_overspeed_text"
    private const val KEY_NAV_SPEED_LIMIT_ROUTE_ONLY: String = "nav_speed_limit_route_only"
    private const val KEY_NAV_SPEED_LIMIT_REPEAT_ENABLED: String = "nav_speed_limit_repeat_enabled"
    private const val KEY_NAV_OVERLAY: String = "nav_overlay"
    private const val KEY_NAV_MICRO_MANEUVERS: String = "nav_micro_maneuvers"
    private const val KEY_NAV_MICRO_HOLD_SECONDS: String = "nav_micro_hold_seconds"
    private const val KEY_NAV_MICRO_MAX_DISTANCE_METERS: String = "nav_micro_max_distance_meters"
    private const val KEY_NAV_STRAIGHT_UNTIL_MAIN: String = "nav_straight_until_main"
    private const val KEY_NAV_MAIN_REVEAL_DISTANCE_METERS: String =
        "nav_main_reveal_distance_meters"
    private const val KEY_NAV_DEBUG_VISIBLE: String = "nav_debug_visible"
    private const val KEY_RUNTIME_PERMISSIONS_REQUESTED: String = "runtime_permissions_requested"
    private const val KEY_AMP: String = "amp_enabled"
    private const val KEY_CANBUS_DEBUG_VISIBLE: String = "canbus_debug_visible"
    private const val KEY_SAS_RATIO: String = "sas_ratio"
    private const val KEY_TPMS_ALERTS: String = "tpms_alerts"
    private const val KEY_TPMS_SOUND_ALERTS: String = "tpms_sound_alerts"
    private const val KEY_TEYES_TPMS_WIDGET_FORWARD: String = "teyes_tpms_widget_forward_enabled"
    private const val KEY_TPMS_LOW_PRESSURE: String = "tpms_low_pressure"
    private const val KEY_TPMS_HIGH_PRESSURE: String = "tpms_high_pressure"
    private const val KEY_TPMS_LOW_TEMP: String = "tpms_low_temp"
    private const val KEY_TPMS_HIGH_TEMP: String = "tpms_high_temp"
    private const val KEY_RCTA_OVERLAY: String = "rcta_overlay"
    private const val KEY_RCTA_SOUND: String = "rcta_sound"
    private const val KEY_RCTA_SOUND_MODE: String = "rcta_sound_mode"
    private const val KEY_RCTA_STYLE: String = "rcta_style"
    private const val KEY_RCTA_COLOR: String = "rcta_color"
    private const val KEY_RCTA_BG_ALPHA: String = "rcta_bg_alpha"
    private const val KEY_RCTA_ARROW_COUNT: String = "rcta_arrow_count"
    private const val KEY_OTHER_MEDIA_SOURCE_MODE: String = "other_media_source_mode"
    private const val KEY_MEDIA_TEXT_MODE: String = "media_text_mode"
    private const val KEY_MEDIA_ANDROID_SOURCE_DELAY_MS: String = "media_android_source_delay_ms"
    private const val KEY_MEDIA_UART_SOURCE_DELAY_MS: String = "media_uart_source_delay_ms"
    private const val KEY_MEDIA_ARTIST_DELAY_MS: String = "media_artist_delay_ms"
    private const val KEY_MEDIA_SOURCE_REASSERT: String = "media_source_reassert"
    private const val KEY_MEDIA_PREFERRED_SESSION_PACKAGE: String =
        "media_preferred_session_package"
    private const val KEY_MEDIA_BLOCKED_SESSION_PACKAGES: String = "media_blocked_session_packages"
    private const val KEY_CALL_SOURCE_MODE: String = "call_source_mode"
    private const val KEY_CANBUS_TEMP_SOURCE: String = "canbus_temp_source"
    private const val KEY_FIRMWARE_SOURCE: String = "firmware_source"
    private const val KEY_USB_PERMISSION_KEY: String = "usb_permission_key"
    private const val KEY_USB_PERMISSION_REQUEST_AT: String = "usb_permission_request_at"
    private const val KEY_BATTERY_OPTIMIZATION_REQUESTED: String = "battery_optimization_requested"
    private const val KEY_MEDIA_TAB_VISIBLE: String = "media_tab_visible"
    private const val KEY_EXPERT_MODE: String = "expert_mode"
    private const val KEY_LAST_UPDATE_CHECK_AT: String = "last_update_check_at"
    private const val KEY_LAST_APP_UPDATE_CHECK_AT: String = "last_app_update_check_at"
    private const val KEY_LAST_NAVIGATOR_UPDATE_CHECK_AT: String = "last_navigator_update_check_at"
    const val OTHER_SOURCE_ANDROID: Int = 0
    const val OTHER_SOURCE_USB: Int = 1
    const val OTHER_SOURCE_BLUETOOTH: Int = 2
    const val OTHER_SOURCE_MY_MUSIC: Int = 3
    const val OTHER_SOURCE_CARPLAY: Int = 4
    const val MEDIA_PROFILE_TEYES: Int = 0
    const val MEDIA_PROFILE_UNIVERSAL_ANDROID: Int = 1
    const val MEDIA_PROFILE_UART_REAL: Int = 2
    const val MEDIA_PROFILE_OFF: Int = 3
    const val MEDIA_TEXT_ARTIST_THEN_TRACK: Int = 0
    const val MEDIA_TEXT_TRACK_ONLY: Int = 1
    const val CALL_SOURCE_ANDROID_AUTO: Int = 0
    const val CALL_SOURCE_CARPLAY: Int = 1
    const val CALL_SOURCE_BLUETOOTH: Int = 2
    const val CALL_SOURCE_MY_MUSIC: Int = 3
    const val CALL_SOURCE_USB: Int = 4
    const val CALL_SOURCE_FM: Int = 5
    const val CANBUS_TEMP_OUTSIDE: Int = 0
    const val CANBUS_TEMP_ENGINE: Int = 1
    const val NAV_SOURCE_AUTO: Int = 0
    const val NAV_SOURCE_YANDEX: Int = 1
    const val NAV_SOURCE_2GIS: Int = 2
    const val NAV_SOURCE_MVITE: Int = 3
    const val NAV_ETA_TIME_ARRIVAL: Int = 0
    const val NAV_ETA_TIME_REMAINING: Int = 1
    const val FIRMWARE_SOURCE_LATEST: Int = 0
    const val FIRMWARE_SOURCE_BUNDLED_03: Int = 1
    const val FIRMWARE_SOURCE_BUNDLED_02: Int = 2
    const val FIRMWARE_SOURCE_BUNDLED_04: Int = 3
    const val RCTA_STYLE_TYPE_1: Int = 1
    const val RCTA_STYLE_TYPE_2: Int = 2
    const val RCTA_STYLE_TEYES: Int = 3
    const val RCTA_SOUND_TONE: Int = 0
    const val RCTA_SOUND_VOICE: Int = 1
    const val RCTA_COLOR_AMBER: Int = -0x3bc5
    const val RCTA_COLOR_RED: Int = -0xac9c
    const val RCTA_COLOR_CYAN: Int = -0xc62c42
    const val RCTA_COLOR_GREEN: Int = -0xc62c73
    const val RCTA_BACKGROUND_ALPHA_MIN: Int = 0
    const val RCTA_BACKGROUND_ALPHA_MAX: Int = 180
    const val RCTA_BACKGROUND_ALPHA_DEFAULT: Int = AppSettings.RCTA_BACKGROUND_ALPHA_MAX
    const val RCTA_ARROW_COUNT_MIN: Int = 3
    const val RCTA_ARROW_COUNT_MAX: Int = 6
    const val RCTA_ARROW_COUNT_DEFAULT: Int = AppSettings.RCTA_ARROW_COUNT_MIN
    private const val SCHEMA: Int = 54
    const val MEDIA_SOURCE_DELAY_MIN_MS: Int = 0
    const val MEDIA_SOURCE_DELAY_MAX_MS: Int = 3000
    const val MEDIA_ANDROID_SOURCE_DELAY_DEFAULT_MS: Int = 300
    const val MEDIA_UART_SOURCE_DELAY_DEFAULT_MS: Int = 1000
    const val MEDIA_ARTIST_DELAY_MIN_MS: Int = 0
    const val MEDIA_ARTIST_DELAY_MAX_MS: Int = 8000
    const val MEDIA_ARTIST_DELAY_DEFAULT_MS: Int = 2500
    private const val DEFAULT_NAV_FINISH_DIRECTION_LEAD_METERS: Int = 0
    private const val DEFAULT_NAV_MANEUVER_TEXT_SECONDS: Int = 0
    private const val DEFAULT_NAV_MICRO_HOLD_SECONDS: Int = 5
    private const val MIN_NAV_MICRO_MAX_DISTANCE_METERS: Int = 100
    private const val DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS: Int = 150
    private const val MAX_NAV_MICRO_MAX_DISTANCE_METERS: Int = 500
    private const val DEFAULT_NAV_MAIN_REVEAL_DISTANCE_METERS: Int = 300
    private const val DEFAULT_SAS_RATIO: Int = 18
    private const val DEFAULT_TPMS_LOW_PRESSURE: Int = 220
    private const val DEFAULT_TPMS_HIGH_PRESSURE: Int = 280
    private const val DEFAULT_TPMS_LOW_TEMP: Int = -20
    private const val DEFAULT_TPMS_HIGH_TEMP: Int = 70
    private const val USB_PERMISSION_RETRY_MS: Long = 10L * 60L * 1000L
    private var legacyPrefsChecked: Boolean = false

    @JvmStatic
    fun applyDefaults(context: Context) {
        val prefs = AppSettings.prefs(context)
        val schema: Int = prefs.getInt(AppSettings.KEY_SCHEMA, 0)
        if (schema >= AppSettings.SCHEMA) return
        val edit = prefs.edit()
        if (schema == 0) {
            edit!!
                .putBoolean(AppSettings.KEY_AUTO_START, true)
                .putBoolean(AppSettings.KEY_MEDIA, true)
                .putInt(AppSettings.KEY_MEDIA_PROFILE, AppSettings.MEDIA_PROFILE_TEYES)
                .putBoolean(AppSettings.KEY_MEDIA_PROFILE_CONFIGURED, false)
                .putBoolean(AppSettings.KEY_CALL, true)
                .putBoolean(AppSettings.KEY_MEDIA_OVERLAY, false)
                .putBoolean(AppSettings.KEY_NAVIGATION, true)
                .putBoolean(AppSettings.KEY_COMPASS, true)
                .putBoolean(AppSettings.KEY_COMPASS_FORCE, false)
                .putInt(AppSettings.KEY_NAV_OUTPUT_MODE, NavigationOutputMode.NORMAL)
                .putBoolean(AppSettings.KEY_NAV_TBT, false)
                .putBoolean(AppSettings.KEY_NAV_FINISH_DIRECTION, false)
                .putInt(
                    AppSettings.KEY_NAV_FINISH_DIRECTION_LEAD_METERS,
                    AppSettings.DEFAULT_NAV_FINISH_DIRECTION_LEAD_METERS,
                )
                .putBoolean(AppSettings.KEY_NAV_FINISH_COMPASS_AUTO, true)
                .putInt(AppSettings.KEY_NAV_TEXT_MODE, 0)
                .putInt(
                    AppSettings.KEY_NAV_MANEUVER_TEXT_SECONDS,
                    AppSettings.DEFAULT_NAV_MANEUVER_TEXT_SECONDS,
                )
                .putInt(AppSettings.KEY_NAV_SOURCE_MODE, AppSettings.NAV_SOURCE_YANDEX)
                .putInt(AppSettings.KEY_NAV_ETA_TIME_MODE, AppSettings.NAV_ETA_TIME_ARRIVAL)
                .putBoolean(AppSettings.KEY_NAV_OVERSPEED_TEXT, true)
                .putBoolean(AppSettings.KEY_NAV_OVERLAY, false)
                .putBoolean(AppSettings.KEY_NAV_MICRO_MANEUVERS, true)
                .putInt(
                    AppSettings.KEY_NAV_MICRO_HOLD_SECONDS,
                    AppSettings.DEFAULT_NAV_MICRO_HOLD_SECONDS,
                )
                .putInt(
                    AppSettings.KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                    AppSettings.DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS,
                )
                .putBoolean(AppSettings.KEY_NAV_STRAIGHT_UNTIL_MAIN, false)
                .putInt(
                    AppSettings.KEY_NAV_MAIN_REVEAL_DISTANCE_METERS,
                    AppSettings.DEFAULT_NAV_MAIN_REVEAL_DISTANCE_METERS,
                )
                .putBoolean(AppSettings.KEY_NAV_DEBUG_VISIBLE, false)
                .putBoolean(AppSettings.KEY_AMP, false)
                .putBoolean(AppSettings.KEY_CANBUS_DEBUG_VISIBLE, false)
                .putInt(AppSettings.KEY_SAS_RATIO, AppSettings.DEFAULT_SAS_RATIO)
                .putBoolean(AppSettings.KEY_TPMS_ALERTS, true)
                .putBoolean(AppSettings.KEY_TPMS_SOUND_ALERTS, true)
                .putInt(AppSettings.KEY_TPMS_LOW_PRESSURE, AppSettings.DEFAULT_TPMS_LOW_PRESSURE)
                .putInt(AppSettings.KEY_TPMS_HIGH_PRESSURE, AppSettings.DEFAULT_TPMS_HIGH_PRESSURE)
                .putInt(AppSettings.KEY_TPMS_LOW_TEMP, AppSettings.DEFAULT_TPMS_LOW_TEMP)
                .putInt(AppSettings.KEY_TPMS_HIGH_TEMP, AppSettings.DEFAULT_TPMS_HIGH_TEMP)
                .putBoolean(AppSettings.KEY_RCTA_OVERLAY, true)
                .putBoolean(AppSettings.KEY_RCTA_SOUND, true)
                .putInt(AppSettings.KEY_RCTA_STYLE, AppSettings.RCTA_STYLE_TYPE_2)
                .putInt(AppSettings.KEY_RCTA_COLOR, AppSettings.RCTA_COLOR_RED)
                .putInt(AppSettings.KEY_RCTA_BG_ALPHA, AppSettings.RCTA_BACKGROUND_ALPHA_DEFAULT)
                .putInt(AppSettings.KEY_RCTA_ARROW_COUNT, AppSettings.RCTA_ARROW_COUNT_DEFAULT)
                .putInt(AppSettings.KEY_OTHER_MEDIA_SOURCE_MODE, AppSettings.OTHER_SOURCE_ANDROID)
                .putInt(AppSettings.KEY_MEDIA_TEXT_MODE, AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK)
                .putInt(
                    AppSettings.KEY_MEDIA_ANDROID_SOURCE_DELAY_MS,
                    AppSettings.MEDIA_ANDROID_SOURCE_DELAY_DEFAULT_MS,
                )
                .putInt(
                    AppSettings.KEY_MEDIA_UART_SOURCE_DELAY_MS,
                    AppSettings.MEDIA_UART_SOURCE_DELAY_DEFAULT_MS,
                )
                .putInt(
                    AppSettings.KEY_MEDIA_ARTIST_DELAY_MS,
                    AppSettings.MEDIA_ARTIST_DELAY_DEFAULT_MS,
                )
                .putBoolean(AppSettings.KEY_MEDIA_SOURCE_REASSERT, true)
                .putString(AppSettings.KEY_MEDIA_PREFERRED_SESSION_PACKAGE, "")
                .putStringSet(
                    AppSettings.KEY_MEDIA_BLOCKED_SESSION_PACKAGES,
                    Collections.emptySet(),
                )
                .putInt(AppSettings.KEY_CALL_SOURCE_MODE, AppSettings.CALL_SOURCE_BLUETOOTH)
                .putInt(AppSettings.KEY_CANBUS_TEMP_SOURCE, AppSettings.CANBUS_TEMP_OUTSIDE)
                .putInt(AppSettings.KEY_FIRMWARE_SOURCE, AppSettings.FIRMWARE_SOURCE_LATEST)
                .putBoolean(AppSettings.KEY_EXPERT_MODE, false)
        } else {
            if (schema < 2 || !prefs.contains(AppSettings.KEY_NAV_TEXT_MODE)) {
                edit!!.putInt(AppSettings.KEY_NAV_TEXT_MODE, 4)
            }
            if (schema < 3 || !prefs.contains(AppSettings.KEY_NAV_MICRO_MANEUVERS)) {
                edit!!.putBoolean(AppSettings.KEY_NAV_MICRO_MANEUVERS, true)
            }
            if (schema < 4 || !prefs.contains(AppSettings.KEY_SAS_RATIO)) {
                edit!!.putInt(AppSettings.KEY_SAS_RATIO, AppSettings.DEFAULT_SAS_RATIO)
            }
            if (schema < 5 || !prefs.contains(AppSettings.KEY_OTHER_MEDIA_SOURCE_MODE)) {
                edit!!.putInt(
                    AppSettings.KEY_OTHER_MEDIA_SOURCE_MODE,
                    AppSettings.OTHER_SOURCE_ANDROID,
                )
            }
            if (schema < 6 || !prefs.contains(AppSettings.KEY_NAV_SOURCE_MODE)) {
                edit!!.putInt(AppSettings.KEY_NAV_SOURCE_MODE, AppSettings.NAV_SOURCE_YANDEX)
            } else if (
                schema < 46 &&
                    prefs.getInt(AppSettings.KEY_NAV_SOURCE_MODE, AppSettings.NAV_SOURCE_AUTO) ==
                        AppSettings.NAV_SOURCE_AUTO
            ) {
                edit!!.putInt(AppSettings.KEY_NAV_SOURCE_MODE, AppSettings.NAV_SOURCE_YANDEX)
            }
            if (schema < 40 || !prefs.contains(AppSettings.KEY_NAV_ETA_TIME_MODE)) {
                edit!!.putInt(AppSettings.KEY_NAV_ETA_TIME_MODE, AppSettings.NAV_ETA_TIME_ARRIVAL)
            }
            if (schema < 7 || !prefs.contains(AppSettings.KEY_NAV_OVERSPEED_TEXT)) {
                edit!!.putBoolean(AppSettings.KEY_NAV_OVERSPEED_TEXT, true)
            }
            if (schema < 7 || !prefs.contains(AppSettings.KEY_COMPASS_FORCE)) {
                edit!!.putBoolean(AppSettings.KEY_COMPASS_FORCE, false)
            }
            if (schema < 7 || !prefs.contains(AppSettings.KEY_FIRMWARE_SOURCE)) {
                edit!!.putInt(AppSettings.KEY_FIRMWARE_SOURCE, AppSettings.FIRMWARE_SOURCE_LATEST)
            }
            if (schema < 10) {
                edit!!.putBoolean(AppSettings.KEY_COMPASS_FORCE, true)
            }
            if (schema < 23 || !prefs.contains(AppSettings.KEY_MEDIA_TAB_VISIBLE)) {
                edit!!.putBoolean(AppSettings.KEY_MEDIA_TAB_VISIBLE, true)
            }
            if (schema < 12 || !prefs.contains(AppSettings.KEY_MEDIA_TEXT_MODE)) {
                edit!!.putInt(
                    AppSettings.KEY_MEDIA_TEXT_MODE,
                    AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK,
                )
            }
            if (schema < 48 || !prefs.contains(AppSettings.KEY_MEDIA_ANDROID_SOURCE_DELAY_MS)) {
                edit!!.putInt(
                    AppSettings.KEY_MEDIA_ANDROID_SOURCE_DELAY_MS,
                    AppSettings.MEDIA_ANDROID_SOURCE_DELAY_DEFAULT_MS,
                )
            }
            if (schema < 48 || !prefs.contains(AppSettings.KEY_MEDIA_UART_SOURCE_DELAY_MS)) {
                edit!!.putInt(
                    AppSettings.KEY_MEDIA_UART_SOURCE_DELAY_MS,
                    AppSettings.MEDIA_UART_SOURCE_DELAY_DEFAULT_MS,
                )
            }
            if (schema < 48 || !prefs.contains(AppSettings.KEY_MEDIA_ARTIST_DELAY_MS)) {
                edit!!.putInt(
                    AppSettings.KEY_MEDIA_ARTIST_DELAY_MS,
                    AppSettings.MEDIA_ARTIST_DELAY_DEFAULT_MS,
                )
            }
            if (schema < 48 || !prefs.contains(AppSettings.KEY_MEDIA_SOURCE_REASSERT)) {
                edit!!.putBoolean(AppSettings.KEY_MEDIA_SOURCE_REASSERT, true)
            }
            if (schema < 51 || !prefs.contains(AppSettings.KEY_MEDIA_PREFERRED_SESSION_PACKAGE)) {
                edit!!.putString(AppSettings.KEY_MEDIA_PREFERRED_SESSION_PACKAGE, "")
            }
            if (schema < 51 || !prefs.contains(AppSettings.KEY_MEDIA_BLOCKED_SESSION_PACKAGES)) {
                edit!!.putStringSet(
                    AppSettings.KEY_MEDIA_BLOCKED_SESSION_PACKAGES,
                    Collections.emptySet(),
                )
            }
            if (schema < 37 || !prefs.contains(AppSettings.KEY_MEDIA_PROFILE)) {
                edit!!.putInt(
                    AppSettings.KEY_MEDIA_PROFILE,
                    if (prefs.getBoolean(AppSettings.KEY_MEDIA, true))
                        AppSettings.MEDIA_PROFILE_TEYES
                    else AppSettings.MEDIA_PROFILE_OFF,
                )
            }
            if (schema < 37 || !prefs.contains(AppSettings.KEY_MEDIA_PROFILE_CONFIGURED)) {
                edit!!.putBoolean(AppSettings.KEY_MEDIA_PROFILE_CONFIGURED, true)
            }
            if (schema < 44) {
                edit!!.putBoolean(AppSettings.KEY_MEDIA_PROFILE_CONFIGURED, false)
            }
            if (schema < 13) {
                edit!!
                    .putBoolean(AppSettings.KEY_COMPASS, true)
                    .putBoolean(AppSettings.KEY_COMPASS_FORCE, false)
                    .putBoolean(AppSettings.KEY_NAV_DEBUG_VISIBLE, false)
                    .putBoolean(AppSettings.KEY_CANBUS_DEBUG_VISIBLE, false)
            }
            if (schema < 14 && prefs.getInt(AppSettings.KEY_NAV_TEXT_MODE, 0) == 4) {
                edit!!.putInt(AppSettings.KEY_NAV_TEXT_MODE, 0)
            }
            if (schema < 15 || !prefs.contains(AppSettings.KEY_CALL_SOURCE_MODE)) {
                edit!!.putInt(AppSettings.KEY_CALL_SOURCE_MODE, AppSettings.CALL_SOURCE_BLUETOOTH)
            } else if (schema < 16) {
                val oldCallSource: Int =
                    prefs.getInt(
                        AppSettings.KEY_CALL_SOURCE_MODE,
                        AppSettings.CALL_SOURCE_BLUETOOTH,
                    )
                if (
                    oldCallSource != AppSettings.CALL_SOURCE_ANDROID_AUTO &&
                        oldCallSource != AppSettings.CALL_SOURCE_CARPLAY &&
                        oldCallSource != AppSettings.CALL_SOURCE_BLUETOOTH
                ) {
                    edit!!.putInt(
                        AppSettings.KEY_CALL_SOURCE_MODE,
                        AppSettings.CALL_SOURCE_BLUETOOTH,
                    )
                }
            }
            if (schema < 17 || !prefs.contains(AppSettings.KEY_TPMS_ALERTS)) {
                edit!!.putBoolean(AppSettings.KEY_TPMS_ALERTS, true)
            }
            if (schema < 17 || !prefs.contains(AppSettings.KEY_TPMS_LOW_PRESSURE)) {
                edit!!.putInt(
                    AppSettings.KEY_TPMS_LOW_PRESSURE,
                    AppSettings.DEFAULT_TPMS_LOW_PRESSURE,
                )
            }
            if (schema < 17 || !prefs.contains(AppSettings.KEY_TPMS_HIGH_PRESSURE)) {
                edit!!.putInt(
                    AppSettings.KEY_TPMS_HIGH_PRESSURE,
                    AppSettings.DEFAULT_TPMS_HIGH_PRESSURE,
                )
            }
            if (schema < 17 || !prefs.contains(AppSettings.KEY_TPMS_LOW_TEMP)) {
                edit!!.putInt(AppSettings.KEY_TPMS_LOW_TEMP, AppSettings.DEFAULT_TPMS_LOW_TEMP)
            }
            if (schema < 17 || !prefs.contains(AppSettings.KEY_TPMS_HIGH_TEMP)) {
                edit!!.putInt(AppSettings.KEY_TPMS_HIGH_TEMP, AppSettings.DEFAULT_TPMS_HIGH_TEMP)
            }
            if (schema < 18 || !prefs.contains(AppSettings.KEY_TPMS_SOUND_ALERTS)) {
                edit!!.putBoolean(AppSettings.KEY_TPMS_SOUND_ALERTS, true)
            }
            if (schema < 19 || !prefs.contains(AppSettings.KEY_RCTA_OVERLAY)) {
                edit!!.putBoolean(AppSettings.KEY_RCTA_OVERLAY, true)
            }
            if (schema < 19 || !prefs.contains(AppSettings.KEY_RCTA_SOUND)) {
                edit!!.putBoolean(AppSettings.KEY_RCTA_SOUND, true)
            }
            if (schema < 24 || !prefs.contains(AppSettings.KEY_RCTA_STYLE)) {
                edit!!.putInt(AppSettings.KEY_RCTA_STYLE, AppSettings.RCTA_STYLE_TYPE_1)
            }
            if (schema < 25 || !prefs.contains(AppSettings.KEY_RCTA_COLOR)) {
                edit!!.putInt(AppSettings.KEY_RCTA_COLOR, AppSettings.RCTA_COLOR_AMBER)
            }
            if (schema < 25 || !prefs.contains(AppSettings.KEY_RCTA_BG_ALPHA)) {
                edit!!.putInt(
                    AppSettings.KEY_RCTA_BG_ALPHA,
                    AppSettings.RCTA_BACKGROUND_ALPHA_DEFAULT,
                )
            }
            if (schema < 47 || !prefs.contains(AppSettings.KEY_RCTA_ARROW_COUNT)) {
                edit!!.putInt(
                    AppSettings.KEY_RCTA_ARROW_COUNT,
                    AppSettings.RCTA_ARROW_COUNT_DEFAULT,
                )
            }
            if (schema < 45) {
                edit!!
                    .putInt(
                        AppSettings.KEY_TPMS_LOW_PRESSURE,
                        AppSettings.DEFAULT_TPMS_LOW_PRESSURE,
                    )
                    .putInt(
                        AppSettings.KEY_TPMS_HIGH_PRESSURE,
                        AppSettings.DEFAULT_TPMS_HIGH_PRESSURE,
                    )
                    .putInt(AppSettings.KEY_SAS_RATIO, AppSettings.DEFAULT_SAS_RATIO)
                    .putBoolean(AppSettings.KEY_AMP, false)
                    .putInt(AppSettings.KEY_RCTA_STYLE, AppSettings.RCTA_STYLE_TYPE_2)
                    .putInt(AppSettings.KEY_RCTA_COLOR, AppSettings.RCTA_COLOR_RED)
                    .putInt(
                        AppSettings.KEY_RCTA_BG_ALPHA,
                        AppSettings.RCTA_BACKGROUND_ALPHA_DEFAULT,
                    )
                    .putInt(AppSettings.KEY_RCTA_ARROW_COUNT, AppSettings.RCTA_ARROW_COUNT_DEFAULT)
            }
            if (schema < 26 || !prefs.contains(AppSettings.KEY_NAV_FINISH_DIRECTION)) {
                edit!!.putBoolean(AppSettings.KEY_NAV_FINISH_DIRECTION, false)
            }
            if (schema < 27 || !prefs.contains(AppSettings.KEY_NAV_MICRO_HOLD_SECONDS)) {
                edit!!.putInt(
                    AppSettings.KEY_NAV_MICRO_HOLD_SECONDS,
                    AppSettings.DEFAULT_NAV_MICRO_HOLD_SECONDS,
                )
            }
            if (
                schema < 27 &&
                    prefs.getBoolean(AppSettings.KEY_NAV_FINISH_DIRECTION, false) &&
                    prefs.getBoolean(AppSettings.KEY_NAV_TBT, false)
            ) {
                edit!!.putBoolean(AppSettings.KEY_NAV_TBT, false)
            }
            if (schema < 28 || !prefs.contains(AppSettings.KEY_NAV_FINISH_DIRECTION_LEAD_METERS)) {
                edit!!.putInt(
                    AppSettings.KEY_NAV_FINISH_DIRECTION_LEAD_METERS,
                    AppSettings.DEFAULT_NAV_FINISH_DIRECTION_LEAD_METERS,
                )
            }
            if (schema < 46 || !prefs.contains(AppSettings.KEY_NAV_FINISH_COMPASS_AUTO)) {
                edit!!.putBoolean(AppSettings.KEY_NAV_FINISH_COMPASS_AUTO, true)
            }
            if (schema < 29 || !prefs.contains(AppSettings.KEY_NAV_MANEUVER_TEXT_SECONDS)) {
                edit!!.putInt(
                    AppSettings.KEY_NAV_MANEUVER_TEXT_SECONDS,
                    AppSettings.DEFAULT_NAV_MANEUVER_TEXT_SECONDS,
                )
            }
            if (schema < 30 || !prefs.contains(AppSettings.KEY_NAV_OUTPUT_MODE)) {
                val migratedMode: Int = AppSettings.legacyNavOutputMode(prefs)
                edit!!
                    .putInt(AppSettings.KEY_NAV_OUTPUT_MODE, migratedMode)
                    .putBoolean(AppSettings.KEY_NAV_TBT, NavigationOutputMode.isTbt(migratedMode))
                    .putBoolean(
                        AppSettings.KEY_NAV_FINISH_DIRECTION,
                        NavigationOutputMode.isFinishDirection(migratedMode),
                    )
            }
            if (schema < 31 || !prefs.contains(AppSettings.KEY_NAV_MICRO_MAX_DISTANCE_METERS)) {
                edit!!.putInt(
                    AppSettings.KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                    AppSettings.DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS,
                )
            }
            if (schema < 32) {
                edit!!.putInt(
                    AppSettings.KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                    AppSettings.normalizeNavMicroMaxDistanceMeters(
                        prefs.getInt(
                            AppSettings.KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                            AppSettings.DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS,
                        )
                    ),
                )
            }
            if (schema < 36) {
                edit!!.putInt(
                    AppSettings.KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                    AppSettings.normalizeNavMicroMaxDistanceMeters(
                        prefs.getInt(
                            AppSettings.KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                            AppSettings.DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS,
                        )
                    ),
                )
            }
            if (schema < 49 || !prefs.contains(AppSettings.KEY_NAV_STRAIGHT_UNTIL_MAIN)) {
                edit!!.putBoolean(AppSettings.KEY_NAV_STRAIGHT_UNTIL_MAIN, false)
            }
            if (schema < 49 || !prefs.contains(AppSettings.KEY_NAV_MAIN_REVEAL_DISTANCE_METERS)) {
                edit!!.putInt(
                    AppSettings.KEY_NAV_MAIN_REVEAL_DISTANCE_METERS,
                    AppSettings.DEFAULT_NAV_MAIN_REVEAL_DISTANCE_METERS,
                )
            }
            if (schema < 50 || !prefs.contains(AppSettings.KEY_EXPERT_MODE)) {
                edit!!.putBoolean(AppSettings.KEY_EXPERT_MODE, false)
            }
            if (schema < 20 || !prefs.contains(AppSettings.KEY_CALL)) {
                edit!!.putBoolean(AppSettings.KEY_CALL, true)
            }
            if (schema < 20 || !prefs.contains(AppSettings.KEY_CANBUS_TEMP_SOURCE)) {
                edit!!.putInt(AppSettings.KEY_CANBUS_TEMP_SOURCE, AppSettings.CANBUS_TEMP_OUTSIDE)
            }
            if (schema < 21 || !prefs.contains(AppSettings.KEY_MEDIA_OVERLAY)) {
                edit!!.putBoolean(AppSettings.KEY_MEDIA_OVERLAY, false)
            }
        }
        if (!prefs.contains(AppSettings.KEY_SUNROOF_OVERLAY))
            edit!!.putBoolean(AppSettings.KEY_SUNROOF_OVERLAY, false)
        if (!prefs.contains(AppSettings.KEY_SUNROOF_PARKING_WARNING))
            edit!!.putBoolean(AppSettings.KEY_SUNROOF_PARKING_WARNING, true)
        if (!prefs.contains(AppSettings.KEY_SUNROOF_SOUND))
            edit!!.putBoolean(AppSettings.KEY_SUNROOF_SOUND, true)
        if (!prefs.contains(AppSettings.KEY_SUNROOF_TRANSITION_SOUND))
            edit!!.putBoolean(AppSettings.KEY_SUNROOF_TRANSITION_SOUND, false)
        if (!prefs.contains(AppSettings.KEY_NAV_SPEED_LIMIT_ROUTE_ONLY)) {
            edit!!.putBoolean(AppSettings.KEY_NAV_SPEED_LIMIT_ROUTE_ONLY, false)
        }
        if (!prefs.contains(AppSettings.KEY_NAV_SPEED_LIMIT_REPEAT_ENABLED)) {
            edit!!.putBoolean(AppSettings.KEY_NAV_SPEED_LIMIT_REPEAT_ENABLED, false)
        }
        edit!!.putInt(AppSettings.KEY_SCHEMA, AppSettings.SCHEMA).apply()
    }

    @JvmStatic
    fun autoStart(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_AUTO_START, true)
    }

    @JvmStatic
    fun setAutoStart(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_AUTO_START, value).apply()
    }

    @JvmStatic
    fun mediaEnabled(context: Context): Boolean {
        return AppSettings.mediaProfile(context) != AppSettings.MEDIA_PROFILE_OFF
    }

    @JvmStatic
    fun setMediaEnabled(context: Context, value: Boolean) {
        if (value) {
            val current: Int = AppSettings.mediaProfile(context)
            AppSettings.setMediaProfile(
                context,
                if (current == AppSettings.MEDIA_PROFILE_OFF) AppSettings.MEDIA_PROFILE_TEYES
                else current,
            )
        } else {
            AppSettings.setMediaProfile(context, AppSettings.MEDIA_PROFILE_OFF)
        }
    }

    @JvmStatic
    fun mediaProfile(context: Context): Int {
        val p = AppSettings.prefs(context)
        if (!p.contains(AppSettings.KEY_MEDIA_PROFILE)) {
            return if (p.getBoolean(AppSettings.KEY_MEDIA, true)) AppSettings.MEDIA_PROFILE_TEYES
            else AppSettings.MEDIA_PROFILE_OFF
        }
        return AppSettings.normalizeMediaProfile(
            p.getInt(AppSettings.KEY_MEDIA_PROFILE, AppSettings.MEDIA_PROFILE_TEYES)
        )
    }

    @JvmStatic
    fun setMediaProfile(context: Context, value: Int) {
        val normalized: Int = AppSettings.normalizeMediaProfile(value)
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_MEDIA_PROFILE, normalized)
            .putBoolean(AppSettings.KEY_MEDIA, normalized != AppSettings.MEDIA_PROFILE_OFF)
            .putBoolean(AppSettings.KEY_MEDIA_PROFILE_CONFIGURED, true)
            .apply()
    }

    @JvmStatic
    fun teyesMediaProfile(context: Context): Boolean {
        return AppSettings.mediaProfile(context) == AppSettings.MEDIA_PROFILE_TEYES
    }

    @JvmStatic
    fun universalMediaProfile(context: Context): Boolean {
        val profile: Int = AppSettings.mediaProfile(context)
        return profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID ||
            profile == AppSettings.MEDIA_PROFILE_UART_REAL
    }

    @JvmStatic
    fun uartRealMediaProfile(context: Context): Boolean {
        return AppSettings.mediaProfile(context) == AppSettings.MEDIA_PROFILE_UART_REAL
    }

    @JvmStatic
    fun mediaProfileConfigured(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_MEDIA_PROFILE_CONFIGURED, true)
    }

    @JvmStatic
    fun setMediaProfileConfigured(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_MEDIA_PROFILE_CONFIGURED, value)
            .apply()
    }

    @JvmStatic
    fun callEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_CALL, true)
    }

    @JvmStatic
    fun setCallEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_CALL, value).apply()
    }

    @JvmStatic
    fun mediaOverlayEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_MEDIA_OVERLAY, false)
    }

    @JvmStatic
    fun setMediaOverlayEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_MEDIA_OVERLAY, value).apply()
    }

    @JvmStatic
    fun navigationEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_NAVIGATION, true)
    }

    @JvmStatic
    fun setNavigationEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_NAVIGATION, value).apply()
    }

    @JvmStatic
    fun adasEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_ADAS_ENABLED, false)
    }

    @JvmStatic
    fun setAdasEnabled(context: Context, enabled: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_ADAS_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun compassEnabled(context: Context?): Boolean {
        return true
    }

    @JvmStatic
    fun setCompassEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_COMPASS, true).apply()
    }

    @JvmStatic
    fun compassForceEnabled(context: Context?): Boolean {
        return false
    }

    @JvmStatic
    fun setCompassForceEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_COMPASS_FORCE, false).apply()
    }

    @JvmStatic
    fun compassKiaNaviFormat(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_COMPASS_KIA_NAVI_FORMAT, false)
    }

    @JvmStatic
    fun setCompassKiaNaviFormat(context: Context, enabled: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_COMPASS_KIA_NAVI_FORMAT, enabled)
            .apply()
    }

    @JvmStatic
    fun navTbt(context: Context): Boolean {
        return NavigationOutputMode.isTbt(AppSettings.navOutputMode(context))
    }

    @JvmStatic
    fun setNavTbt(context: Context, value: Boolean) {
        if (value) {
            AppSettings.setNavOutputMode(context, NavigationOutputMode.TBT)
        } else if (AppSettings.navTbt(context)) {
            AppSettings.setNavOutputMode(context, NavigationOutputMode.NORMAL)
        }
    }

    @JvmStatic
    fun navFinishDirectionMode(context: Context): Boolean {
        return NavigationOutputMode.isFinishDirection(AppSettings.navOutputMode(context))
    }

    @JvmStatic
    fun setNavFinishDirectionMode(context: Context, value: Boolean) {
        if (value) {
            AppSettings.setNavOutputMode(context, NavigationOutputMode.FINISH_DIRECTION)
        } else if (AppSettings.navFinishDirectionMode(context)) {
            AppSettings.setNavOutputMode(context, NavigationOutputMode.NORMAL)
        }
    }

    @JvmStatic
    fun navOutputMode(context: Context): Int {
        val p = AppSettings.prefs(context)
        if (!p.contains(AppSettings.KEY_NAV_OUTPUT_MODE)) return AppSettings.legacyNavOutputMode(p)
        return NavigationOutputMode.normalize(
            p.getInt(AppSettings.KEY_NAV_OUTPUT_MODE, NavigationOutputMode.NORMAL)
        )
    }

    @JvmStatic
    fun setNavOutputMode(context: Context, mode: Int) {
        val normalized: Int = NavigationOutputMode.normalize(mode)
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_NAV_OUTPUT_MODE, normalized)
            .putBoolean(AppSettings.KEY_NAV_TBT, NavigationOutputMode.isTbt(normalized))
            .putBoolean(
                AppSettings.KEY_NAV_FINISH_DIRECTION,
                NavigationOutputMode.isFinishDirection(normalized),
            )
            .apply()
    }

    @JvmStatic
    fun navFinishDirectionLeadMeters(context: Context?): Int {
        return 0
    }

    @JvmStatic
    fun setNavFinishDirectionLeadMeters(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_NAV_FINISH_DIRECTION_LEAD_METERS, 0)
            .apply()
    }

    @JvmStatic
    fun navFinishCompassAuto(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_NAV_FINISH_COMPASS_AUTO, true)
    }

    @JvmStatic
    fun setNavFinishCompassAuto(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_NAV_FINISH_COMPASS_AUTO, value)
            .apply()
    }

    @JvmStatic
    fun navTextMode(context: Context): Int {
        val mode: Int = AppSettings.prefs(context).getInt(AppSettings.KEY_NAV_TEXT_MODE, 0)
        if (mode >= 3) return 0
        return AppSettings.clamp(mode, 0, 2)
    }

    @JvmStatic
    fun setNavTextMode(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_NAV_TEXT_MODE, AppSettings.clamp(value, 0, 2))
            .apply()
    }

    @JvmStatic
    fun navManeuverTextSeconds(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(
                    AppSettings.KEY_NAV_MANEUVER_TEXT_SECONDS,
                    AppSettings.DEFAULT_NAV_MANEUVER_TEXT_SECONDS,
                ),
            0,
            15,
        )
    }

    @JvmStatic
    fun setNavManeuverTextSeconds(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_NAV_MANEUVER_TEXT_SECONDS, AppSettings.clamp(value, 0, 15))
            .apply()
    }

    @JvmStatic
    fun navSpeedLimitRouteOnly(context: Context): Boolean {
        return AppSettings.prefs(context)
            .getBoolean(AppSettings.KEY_NAV_SPEED_LIMIT_ROUTE_ONLY, false)
    }

    @JvmStatic
    fun setNavSpeedLimitRouteOnly(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_NAV_SPEED_LIMIT_ROUTE_ONLY, value)
            .apply()
    }

    @JvmStatic
    fun navSpeedLimitRepeatEnabled(context: Context): Boolean {
        return AppSettings.prefs(context)
            .getBoolean(AppSettings.KEY_NAV_SPEED_LIMIT_REPEAT_ENABLED, false)
    }

    @JvmStatic
    fun setNavSpeedLimitRepeatEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_NAV_SPEED_LIMIT_REPEAT_ENABLED, value)
            .apply()
    }

    @JvmStatic
    fun navOverspeedTextEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_NAV_OVERSPEED_TEXT, true)
    }

    @JvmStatic
    fun setNavOverspeedTextEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_NAV_OVERSPEED_TEXT, value)
            .apply()
    }

    @JvmStatic
    fun navSourceMode(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_NAV_SOURCE_MODE, AppSettings.NAV_SOURCE_YANDEX),
            AppSettings.NAV_SOURCE_AUTO,
            AppSettings.NAV_SOURCE_MVITE,
        )
    }

    @JvmStatic
    fun setNavSourceMode(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_NAV_SOURCE_MODE,
                AppSettings.clamp(value, AppSettings.NAV_SOURCE_AUTO, AppSettings.NAV_SOURCE_MVITE),
            )
            .apply()
    }

    @JvmStatic
    fun yandexNavigationEnabled(context: Context): Boolean {
        val mode: Int = AppSettings.navSourceMode(context)
        return mode == AppSettings.NAV_SOURCE_AUTO || mode == AppSettings.NAV_SOURCE_YANDEX
    }

    @JvmStatic
    fun dgisNavigationEnabled(context: Context): Boolean {
        val mode: Int = AppSettings.navSourceMode(context)
        return mode == AppSettings.NAV_SOURCE_AUTO || mode == AppSettings.NAV_SOURCE_2GIS
    }

    @JvmStatic
    fun navSourceLabel(context: Context): String {
        when (AppSettings.navSourceMode(context)) {
            AppSettings.NAV_SOURCE_YANDEX -> return "Yandex"
            AppSettings.NAV_SOURCE_2GIS -> return "2GIS"
            AppSettings.NAV_SOURCE_MVITE -> return "MVite"
            AppSettings.NAV_SOURCE_AUTO -> return "Auto"
            else -> return "Auto"
        }
    }

    @JvmStatic
    fun navEtaTimeMode(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_NAV_ETA_TIME_MODE, AppSettings.NAV_ETA_TIME_ARRIVAL),
            AppSettings.NAV_ETA_TIME_ARRIVAL,
            AppSettings.NAV_ETA_TIME_REMAINING,
        )
    }

    @JvmStatic
    fun setNavEtaTimeMode(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_NAV_ETA_TIME_MODE,
                if (value == AppSettings.NAV_ETA_TIME_REMAINING) AppSettings.NAV_ETA_TIME_REMAINING
                else AppSettings.NAV_ETA_TIME_ARRIVAL,
            )
            .apply()
    }

    @JvmStatic
    fun navEtaTimeModeLabel(context: Context): String {
        return if (AppSettings.navEtaTimeMode(context) == AppSettings.NAV_ETA_TIME_REMAINING)
            "Осталось"
        else "Прибытие"
    }

    @JvmStatic
    fun navOverlayEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_NAV_OVERLAY, false)
    }

    @JvmStatic
    fun setNavOverlayEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_NAV_OVERLAY, value).apply()
    }

    @JvmStatic
    fun navMicroManeuvers(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_NAV_MICRO_MANEUVERS, false)
    }

    @JvmStatic
    fun setNavMicroManeuvers(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_NAV_MICRO_MANEUVERS, value)
            .apply()
    }

    @JvmStatic
    fun navMicroHoldSeconds(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(
                    AppSettings.KEY_NAV_MICRO_HOLD_SECONDS,
                    AppSettings.DEFAULT_NAV_MICRO_HOLD_SECONDS,
                ),
            5,
            15,
        )
    }

    @JvmStatic
    fun setNavMicroHoldSeconds(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_NAV_MICRO_HOLD_SECONDS, AppSettings.clamp(value, 5, 15))
            .apply()
    }

    @JvmStatic
    fun navMicroMaxDistanceMeters(context: Context): Int {
        return AppSettings.normalizeNavMicroMaxDistanceMeters(
            AppSettings.prefs(context)
                .getInt(
                    AppSettings.KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                    AppSettings.DEFAULT_NAV_MICRO_MAX_DISTANCE_METERS,
                )
        )
    }

    @JvmStatic
    fun setNavMicroMaxDistanceMeters(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_NAV_MICRO_MAX_DISTANCE_METERS,
                AppSettings.normalizeNavMicroMaxDistanceMeters(value),
            )
            .apply()
    }

    @JvmStatic
    fun normalizeNavMicroMaxDistanceMeters(value: Int): Int {
        val clamped: Int =
            AppSettings.clamp(
                value,
                AppSettings.MIN_NAV_MICRO_MAX_DISTANCE_METERS,
                AppSettings.MAX_NAV_MICRO_MAX_DISTANCE_METERS,
            )
        if (clamped <= 100) return 100
        if (clamped <= 150) return 150
        if (clamped <= 200) return 200
        if (clamped <= 250) return 250
        if (clamped <= 300) return 300
        if (clamped <= 400) return 400
        return 500
    }

    @JvmStatic
    fun navStraightUntilMain(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_NAV_STRAIGHT_UNTIL_MAIN, false)
    }

    @JvmStatic
    fun setNavStraightUntilMain(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_NAV_STRAIGHT_UNTIL_MAIN, value)
            .apply()
    }

    @JvmStatic
    fun navMainRevealDistanceMeters(context: Context): Int {
        return AppSettings.normalizeNavMainRevealDistanceMeters(
            AppSettings.prefs(context)
                .getInt(
                    AppSettings.KEY_NAV_MAIN_REVEAL_DISTANCE_METERS,
                    AppSettings.DEFAULT_NAV_MAIN_REVEAL_DISTANCE_METERS,
                )
        )
    }

    @JvmStatic
    fun setNavMainRevealDistanceMeters(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_NAV_MAIN_REVEAL_DISTANCE_METERS,
                AppSettings.normalizeNavMainRevealDistanceMeters(value),
            )
            .apply()
    }

    @JvmStatic
    fun normalizeNavMainRevealDistanceMeters(value: Int): Int {
        val clamped: Int = AppSettings.clamp(value, 100, 500)
        if (clamped <= 100) return 100
        if (clamped <= 200) return 200
        if (clamped <= 300) return 300
        if (clamped <= 400) return 400
        return 500
    }

    @JvmStatic
    fun navDebugVisible(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_NAV_DEBUG_VISIBLE, false)
    }

    @JvmStatic
    fun setNavDebugVisible(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_NAV_DEBUG_VISIBLE, value)
            .apply()
    }

    @JvmStatic
    fun ampEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_AMP, false)
    }

    @JvmStatic
    fun setAmpEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_AMP, value).apply()
    }

    @JvmStatic
    fun canbusDebugVisible(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_CANBUS_DEBUG_VISIBLE, false)
    }

    @JvmStatic
    fun setCanbusDebugVisible(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_CANBUS_DEBUG_VISIBLE, value)
            .apply()
    }

    @JvmStatic
    fun sasRatio(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_SAS_RATIO, AppSettings.DEFAULT_SAS_RATIO),
            1,
            255,
        )
    }

    @JvmStatic
    fun setSasRatio(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_SAS_RATIO, AppSettings.clamp(value, 1, 255))
            .apply()
    }

    @JvmStatic
    fun tpmsAlertsEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_TPMS_ALERTS, true)
    }

    @JvmStatic
    fun setTpmsAlertsEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_TPMS_ALERTS, value).apply()
    }

    @JvmStatic
    fun tpmsSoundAlertsEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_TPMS_SOUND_ALERTS, true)
    }

    @JvmStatic
    fun setTpmsSoundAlertsEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_TPMS_SOUND_ALERTS, value)
            .apply()
    }

    @JvmStatic
    fun teyesTpmsWidgetForwardEnabled(context: Context): Boolean {
        return AppSettings.prefs(context)
            .getBoolean(AppSettings.KEY_TEYES_TPMS_WIDGET_FORWARD, false)
    }

    @JvmStatic
    fun setTeyesTpmsWidgetForwardEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_TEYES_TPMS_WIDGET_FORWARD, value)
            .apply()
    }

    @JvmStatic
    fun tpmsLowPressureKpa(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_TPMS_LOW_PRESSURE, AppSettings.DEFAULT_TPMS_LOW_PRESSURE),
            80,
            450,
        )
    }

    @JvmStatic
    fun setTpmsLowPressureKpa(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_TPMS_LOW_PRESSURE, AppSettings.clamp(value, 80, 450))
            .apply()
    }

    @JvmStatic
    fun tpmsHighPressureKpa(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_TPMS_HIGH_PRESSURE, AppSettings.DEFAULT_TPMS_HIGH_PRESSURE),
            120,
            600,
        )
    }

    @JvmStatic
    fun setTpmsHighPressureKpa(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_TPMS_HIGH_PRESSURE, AppSettings.clamp(value, 120, 600))
            .apply()
    }

    @JvmStatic
    fun tpmsLowTempC(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_TPMS_LOW_TEMP, AppSettings.DEFAULT_TPMS_LOW_TEMP),
            -60,
            80,
        )
    }

    @JvmStatic
    fun setTpmsLowTempC(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_TPMS_LOW_TEMP, AppSettings.clamp(value, -60, 80))
            .apply()
    }

    @JvmStatic
    fun tpmsHighTempC(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_TPMS_HIGH_TEMP, AppSettings.DEFAULT_TPMS_HIGH_TEMP),
            -20,
            160,
        )
    }

    @JvmStatic
    fun setTpmsHighTempC(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_TPMS_HIGH_TEMP, AppSettings.clamp(value, -20, 160))
            .apply()
    }

    @JvmStatic
    fun rctaOverlayEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_RCTA_OVERLAY, true)
    }

    @JvmStatic
    fun sunroofOverlayEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_SUNROOF_OVERLAY, false)
    }

    @JvmStatic
    fun setSunroofOverlayEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_SUNROOF_OVERLAY, value).apply()
    }

    @JvmStatic
    fun sunroofParkingWarningEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_SUNROOF_PARKING_WARNING, true)
    }

    @JvmStatic
    fun setSunroofParkingWarningEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_SUNROOF_PARKING_WARNING, value)
            .apply()
    }

    @JvmStatic
    fun sunroofSoundEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_SUNROOF_SOUND, true)
    }

    @JvmStatic
    fun setSunroofSoundEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_SUNROOF_SOUND, value).apply()
    }

    @JvmStatic
    fun sunroofTransitionSoundEnabled(context: Context): Boolean {
        return AppSettings.prefs(context)
            .getBoolean(AppSettings.KEY_SUNROOF_TRANSITION_SOUND, false)
    }

    @JvmStatic
    fun setSunroofTransitionSoundEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_SUNROOF_TRANSITION_SOUND, value)
            .apply()
    }

    @JvmStatic
    fun setRctaOverlayEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_RCTA_OVERLAY, value).apply()
    }

    @JvmStatic
    fun rctaSoundEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_RCTA_SOUND, true)
    }

    @JvmStatic
    fun setRctaSoundEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_RCTA_SOUND, value).apply()
    }

    @JvmStatic
    fun rctaSoundMode(context: Context): Int {
        return AppSettings.normalizeRctaSoundMode(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_RCTA_SOUND_MODE, AppSettings.RCTA_SOUND_TONE)
        )
    }

    @JvmStatic
    fun setRctaSoundMode(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_RCTA_SOUND_MODE, AppSettings.normalizeRctaSoundMode(value))
            .apply()
    }

    @JvmStatic
    fun rctaSoundModeLabel(context: Context): String {
        return if (AppSettings.rctaSoundMode(context) == AppSettings.RCTA_SOUND_VOICE) "Голос"
        else "Сигнал"
    }

    @JvmStatic
    fun rctaStyle(context: Context): Int {
        return AppSettings.normalizeRctaStyle(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_RCTA_STYLE, AppSettings.RCTA_STYLE_TYPE_2)
        )
    }

    @JvmStatic
    fun setRctaStyle(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_RCTA_STYLE, AppSettings.normalizeRctaStyle(value))
            .apply()
    }

    @JvmStatic
    fun rctaStyleLabel(context: Context): String {
        when (AppSettings.rctaStyle(context)) {
            AppSettings.RCTA_STYLE_TEYES -> return "TEYES"
            AppSettings.RCTA_STYLE_TYPE_2 -> return "Тип 2"
            else -> return "Тип 1"
        }
    }

    @JvmStatic
    fun rctaColor(context: Context): Int {
        return AppSettings.normalizeRctaColor(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_RCTA_COLOR, AppSettings.RCTA_COLOR_RED)
        )
    }

    @JvmStatic
    fun setRctaColor(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_RCTA_COLOR, AppSettings.normalizeRctaColor(value))
            .apply()
    }

    @JvmStatic
    fun rctaColorLabel(context: Context): String {
        when (AppSettings.rctaColor(context)) {
            AppSettings.RCTA_COLOR_RED -> return "красный"
            AppSettings.RCTA_COLOR_CYAN -> return "голубой"
            AppSettings.RCTA_COLOR_GREEN -> return "зелёный"
            AppSettings.RCTA_COLOR_AMBER -> return "янтарный"
            else -> return "янтарный"
        }
    }

    @JvmStatic
    fun rctaBackgroundAlpha(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_RCTA_BG_ALPHA, AppSettings.RCTA_BACKGROUND_ALPHA_DEFAULT),
            AppSettings.RCTA_BACKGROUND_ALPHA_MIN,
            AppSettings.RCTA_BACKGROUND_ALPHA_MAX,
        )
    }

    @JvmStatic
    fun setRctaBackgroundAlpha(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_RCTA_BG_ALPHA,
                AppSettings.clamp(
                    value,
                    AppSettings.RCTA_BACKGROUND_ALPHA_MIN,
                    AppSettings.RCTA_BACKGROUND_ALPHA_MAX,
                ),
            )
            .apply()
    }

    @JvmStatic
    fun rctaArrowCount(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_RCTA_ARROW_COUNT, AppSettings.RCTA_ARROW_COUNT_DEFAULT),
            AppSettings.RCTA_ARROW_COUNT_MIN,
            AppSettings.RCTA_ARROW_COUNT_MAX,
        )
    }

    @JvmStatic
    fun setRctaArrowCount(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_RCTA_ARROW_COUNT,
                AppSettings.clamp(
                    value,
                    AppSettings.RCTA_ARROW_COUNT_MIN,
                    AppSettings.RCTA_ARROW_COUNT_MAX,
                ),
            )
            .apply()
    }

    @JvmStatic
    fun otherMediaSourceMode(context: Context): Int {
        return AppSettings.normalizeOtherSource(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_OTHER_MEDIA_SOURCE_MODE, AppSettings.OTHER_SOURCE_ANDROID)
        )
    }

    @JvmStatic
    fun setOtherMediaSourceMode(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_OTHER_MEDIA_SOURCE_MODE,
                AppSettings.normalizeOtherSource(value),
            )
            .apply()
    }

    @JvmStatic
    fun otherMediaSourceLabel(context: Context): String {
        when (AppSettings.otherMediaSourceMode(context)) {
            AppSettings.OTHER_SOURCE_USB -> return "USB"
            AppSettings.OTHER_SOURCE_BLUETOOTH -> return "Bluetooth"
            AppSettings.OTHER_SOURCE_MY_MUSIC -> return "My Music"
            AppSettings.OTHER_SOURCE_CARPLAY -> return "CarPlay"
            AppSettings.OTHER_SOURCE_ANDROID -> return "Android"
            else -> return "Android"
        }
    }

    @JvmStatic
    fun mediaTextMode(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_MEDIA_TEXT_MODE, AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK),
            AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK,
            AppSettings.MEDIA_TEXT_TRACK_ONLY,
        )
    }

    @JvmStatic
    fun setMediaTextMode(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_MEDIA_TEXT_MODE,
                AppSettings.clamp(
                    value,
                    AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK,
                    AppSettings.MEDIA_TEXT_TRACK_ONLY,
                ),
            )
            .apply()
    }

    @JvmStatic
    fun mediaTextModeLabel(context: Context): String {
        return if (AppSettings.mediaTextMode(context) == AppSettings.MEDIA_TEXT_TRACK_ONLY)
            "Только трек"
        else "Автор + трек"
    }

    @JvmStatic
    fun mediaSourceDelayMs(context: Context): Int {
        return AppSettings.mediaSourceDelayMs(context, AppSettings.mediaProfile(context))
    }

    @JvmStatic
    fun mediaSourceDelayMs(context: Context, profile: Int): Int {
        val key: String =
            if (profile == AppSettings.MEDIA_PROFILE_UART_REAL)
                AppSettings.KEY_MEDIA_UART_SOURCE_DELAY_MS
            else AppSettings.KEY_MEDIA_ANDROID_SOURCE_DELAY_MS
        val fallback: Int =
            if (profile == AppSettings.MEDIA_PROFILE_UART_REAL)
                AppSettings.MEDIA_UART_SOURCE_DELAY_DEFAULT_MS
            else AppSettings.MEDIA_ANDROID_SOURCE_DELAY_DEFAULT_MS
        return AppSettings.clamp(
            AppSettings.prefs(context).getInt(key, fallback),
            AppSettings.MEDIA_SOURCE_DELAY_MIN_MS,
            AppSettings.MEDIA_SOURCE_DELAY_MAX_MS,
        )
    }

    @JvmStatic
    fun setMediaSourceDelayMs(context: Context, profile: Int, value: Int) {
        val key: String =
            if (profile == AppSettings.MEDIA_PROFILE_UART_REAL)
                AppSettings.KEY_MEDIA_UART_SOURCE_DELAY_MS
            else AppSettings.KEY_MEDIA_ANDROID_SOURCE_DELAY_MS
        AppSettings.prefs(context)
            .edit()
            .putInt(
                key,
                AppSettings.clamp(
                    value,
                    AppSettings.MEDIA_SOURCE_DELAY_MIN_MS,
                    AppSettings.MEDIA_SOURCE_DELAY_MAX_MS,
                ),
            )
            .apply()
    }

    @JvmStatic
    fun mediaArtistDelayMs(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(
                    AppSettings.KEY_MEDIA_ARTIST_DELAY_MS,
                    AppSettings.MEDIA_ARTIST_DELAY_DEFAULT_MS,
                ),
            AppSettings.MEDIA_ARTIST_DELAY_MIN_MS,
            AppSettings.MEDIA_ARTIST_DELAY_MAX_MS,
        )
    }

    @JvmStatic
    fun setMediaArtistDelayMs(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_MEDIA_ARTIST_DELAY_MS,
                AppSettings.clamp(
                    value,
                    AppSettings.MEDIA_ARTIST_DELAY_MIN_MS,
                    AppSettings.MEDIA_ARTIST_DELAY_MAX_MS,
                ),
            )
            .apply()
    }

    @JvmStatic
    fun mediaSourceReassertEnabled(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_MEDIA_SOURCE_REASSERT, true)
    }

    @JvmStatic
    fun setMediaSourceReassertEnabled(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_MEDIA_SOURCE_REASSERT, value)
            .apply()
    }

    @JvmStatic
    fun mediaPreferredSessionPackage(context: Context): String {
        return AppSettings.normalizeMediaSessionPackage(
            AppSettings.prefs(context)
                .getString(AppSettings.KEY_MEDIA_PREFERRED_SESSION_PACKAGE, "")
        )
    }

    @JvmStatic
    fun setMediaPreferredSessionPackage(context: Context, packageName: String?) {
        val clean: String = AppSettings.normalizeMediaSessionPackage(packageName)
        val blocked: MutableSet<String?> =
            LinkedHashSet(AppSettings.mediaBlockedSessionPackages(context))
        blocked.remove(clean)
        AppSettings.prefs(context)
            .edit()
            .putString(AppSettings.KEY_MEDIA_PREFERRED_SESSION_PACKAGE, clean)
            .putStringSet(AppSettings.KEY_MEDIA_BLOCKED_SESSION_PACKAGES, blocked)
            .apply()
    }

    @JvmStatic
    fun clearMediaPreferredSessionPackage(context: Context) {
        AppSettings.setMediaPreferredSessionPackage(context, "")
    }

    @JvmStatic
    fun mediaBlockedSessionPackages(context: Context): Set<String?>? {
        val stored: Set<String?>? =
            AppSettings.prefs(context)
                .getStringSet(
                    AppSettings.KEY_MEDIA_BLOCKED_SESSION_PACKAGES,
                    Collections.emptySet(),
                )
        val sorted: TreeSet<String?> = TreeSet()
        if (stored != null) {
            for (packageName: String? in stored) {
                val clean: String = AppSettings.normalizeMediaSessionPackage(packageName)
                if (!clean.isEmpty()) sorted.add(clean)
            }
        }
        return Collections.unmodifiableSet(LinkedHashSet(sorted))
    }

    @JvmStatic
    fun isMediaSessionPackageBlocked(context: Context, packageName: String?): Boolean {
        val clean: String = AppSettings.normalizeMediaSessionPackage(packageName)
        return !clean.isEmpty() &&
            AppSettings.mediaBlockedSessionPackages(context)!!.contains(clean)
    }

    @JvmStatic
    fun setMediaSessionPackageBlocked(
        context: Context,
        packageName: String?,
        blocked: Boolean,
    ): Boolean {
        val clean: String = AppSettings.normalizeMediaSessionPackage(packageName)
        if (clean.isEmpty()) return false
        val packages: MutableSet<String?> =
            LinkedHashSet(AppSettings.mediaBlockedSessionPackages(context))
        var changed: Boolean = if (blocked) packages.add(clean) else packages.remove(clean)
        val edit =
            AppSettings.prefs(context)
                .edit()
                .putStringSet(AppSettings.KEY_MEDIA_BLOCKED_SESSION_PACKAGES, packages)
        if (blocked && clean.equals(AppSettings.mediaPreferredSessionPackage(context))) {
            edit!!.putString(AppSettings.KEY_MEDIA_PREFERRED_SESSION_PACKAGE, "")
            changed = true
        }
        edit!!.apply()
        return changed
    }

    @JvmStatic
    fun mediaPreferredSessionSummary(context: Context): String {
        val packageName: String = AppSettings.mediaPreferredSessionPackage(context)
        return if (packageName.isEmpty()) "Автовыбор" else packageName
    }

    @JvmStatic
    fun mediaBlockedSessionsSummary(context: Context): String {
        val packages = AppSettings.mediaBlockedSessionPackages(context)
        return if (packages!!.isEmpty()) "Нет" else java.lang.String.join(", ", packages)
    }

    @JvmStatic
    fun mediaSessionSelectionSummary(context: Context): String {
        return ("Приоритет: " +
            AppSettings.mediaPreferredSessionSummary(context) +
            " · исключено: " +
            AppSettings.mediaBlockedSessionPackages(context)!!.size)
    }

    @JvmStatic
    fun mediaProfileLabel(context: Context): String {
        return AppSettings.mediaProfileLabel(AppSettings.mediaProfile(context))
    }

    @JvmStatic
    fun mediaProfileLabel(profile: Int): String {
        when (AppSettings.normalizeMediaProfile(profile)) {
            AppSettings.MEDIA_PROFILE_OFF -> return "Выкл"
            AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID -> return "Universal Android"
            AppSettings.MEDIA_PROFILE_UART_REAL -> return "UART real + Android"
            AppSettings.MEDIA_PROFILE_TEYES -> return "TEYES / CC4 Pro"
            else -> return "TEYES / CC4 Pro"
        }
    }

    @JvmStatic
    fun callSourceMode(context: Context): Int {
        return AppSettings.normalizeCallSource(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_CALL_SOURCE_MODE, AppSettings.CALL_SOURCE_CARPLAY)
        )
    }

    @JvmStatic
    fun setCallSourceMode(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(AppSettings.KEY_CALL_SOURCE_MODE, AppSettings.normalizeCallSource(value))
            .apply()
    }

    @JvmStatic
    fun callSourceLabel(context: Context): String {
        when (AppSettings.callSourceMode(context)) {
            AppSettings.CALL_SOURCE_ANDROID_AUTO -> return "Android Auto"
            AppSettings.CALL_SOURCE_BLUETOOTH -> return "BT Audio"
            AppSettings.CALL_SOURCE_MY_MUSIC -> return "My Music"
            AppSettings.CALL_SOURCE_USB -> return "USB Music"
            AppSettings.CALL_SOURCE_FM -> return "FM Radio"
            AppSettings.CALL_SOURCE_CARPLAY -> return "CarPlay"
            else -> return "CarPlay"
        }
    }

    @JvmStatic
    fun canbusTemperatureSource(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_CANBUS_TEMP_SOURCE, AppSettings.CANBUS_TEMP_OUTSIDE),
            AppSettings.CANBUS_TEMP_OUTSIDE,
            AppSettings.CANBUS_TEMP_ENGINE,
        )
    }

    @JvmStatic
    fun setCanbusTemperatureSource(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_CANBUS_TEMP_SOURCE,
                AppSettings.clamp(
                    value,
                    AppSettings.CANBUS_TEMP_OUTSIDE,
                    AppSettings.CANBUS_TEMP_ENGINE,
                ),
            )
            .apply()
    }

    @JvmStatic
    fun canbusTemperatureSourceLabel(context: Context): String {
        return if (AppSettings.canbusTemperatureSource(context) == AppSettings.CANBUS_TEMP_ENGINE)
            "двигатель"
        else "улица"
    }

    @JvmStatic
    fun firmwareSource(context: Context): Int {
        return AppSettings.clamp(
            AppSettings.prefs(context)
                .getInt(AppSettings.KEY_FIRMWARE_SOURCE, AppSettings.FIRMWARE_SOURCE_LATEST),
            AppSettings.FIRMWARE_SOURCE_LATEST,
            AppSettings.FIRMWARE_SOURCE_BUNDLED_04,
        )
    }

    @JvmStatic
    fun setFirmwareSource(context: Context, value: Int) {
        AppSettings.prefs(context)
            .edit()
            .putInt(
                AppSettings.KEY_FIRMWARE_SOURCE,
                AppSettings.clamp(
                    value,
                    AppSettings.FIRMWARE_SOURCE_LATEST,
                    AppSettings.FIRMWARE_SOURCE_BUNDLED_04,
                ),
            )
            .apply()
    }

    @JvmStatic
    fun firmwareSourceLabel(context: Context): String {
        when (AppSettings.firmwareSource(context)) {
            AppSettings.FIRMWARE_SOURCE_BUNDLED_04 -> return "FW 04 / 04.35.02.04"
            AppSettings.FIRMWARE_SOURCE_BUNDLED_03 -> return "FW 03 / 04.35.02.03"
            AppSettings.FIRMWARE_SOURCE_BUNDLED_02 -> return "FW 02 / 04.35.02.02"
            AppSettings.FIRMWARE_SOURCE_LATEST -> return "GitHub latest"
            else -> return "GitHub latest"
        }
    }

    @JvmStatic
    fun runtimePermissionsRequested(context: Context): Boolean {
        return AppSettings.prefs(context)
            .getBoolean(AppSettings.KEY_RUNTIME_PERMISSIONS_REQUESTED, false)
    }

    @JvmStatic
    fun setRuntimePermissionsRequested(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_RUNTIME_PERMISSIONS_REQUESTED, value)
            .apply()
    }

    @JvmStatic
    fun shouldRequestUsbPermission(context: Context, key: String): Boolean {
        val prefs = AppSettings.prefs(context)
        val lastKey = prefs.getString(AppSettings.KEY_USB_PERMISSION_KEY, "")
        val lastAt: Long = prefs.getLong(AppSettings.KEY_USB_PERMISSION_REQUEST_AT, 0L)
        val now: Long = System.currentTimeMillis()
        return !key.equals(lastKey) || now - lastAt > AppSettings.USB_PERMISSION_RETRY_MS
    }

    @JvmStatic
    fun markUsbPermissionRequested(context: Context, key: String?) {
        AppSettings.prefs(context)
            .edit()
            .putString(AppSettings.KEY_USB_PERMISSION_KEY, key)
            .putLong(AppSettings.KEY_USB_PERMISSION_REQUEST_AT, System.currentTimeMillis())
            .apply()
    }

    @JvmStatic
    fun clearUsbPermissionRequest(context: Context, key: String) {
        val prefs = AppSettings.prefs(context)
        if (!key.equals(prefs.getString(AppSettings.KEY_USB_PERMISSION_KEY, ""))) return
        prefs
            .edit()
            .remove(AppSettings.KEY_USB_PERMISSION_KEY)
            .remove(AppSettings.KEY_USB_PERMISSION_REQUEST_AT)
            .apply()
    }

    @JvmStatic
    fun batteryOptimizationRequested(context: Context): Boolean {
        return AppSettings.prefs(context)
            .getBoolean(AppSettings.KEY_BATTERY_OPTIMIZATION_REQUESTED, false)
    }

    @JvmStatic
    fun setBatteryOptimizationRequested(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_BATTERY_OPTIMIZATION_REQUESTED, value)
            .apply()
    }

    @JvmStatic
    fun mediaTabVisible(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_MEDIA_TAB_VISIBLE, true)
    }

    @JvmStatic
    fun setMediaTabVisible(context: Context, value: Boolean) {
        AppSettings.prefs(context)
            .edit()
            .putBoolean(AppSettings.KEY_MEDIA_TAB_VISIBLE, value)
            .apply()
    }

    @JvmStatic
    fun expertMode(context: Context): Boolean {
        return AppSettings.prefs(context).getBoolean(AppSettings.KEY_EXPERT_MODE, false)
    }

    @JvmStatic
    fun setExpertMode(context: Context, value: Boolean) {
        AppSettings.prefs(context).edit().putBoolean(AppSettings.KEY_EXPERT_MODE, value).apply()
    }

    @JvmStatic
    fun lastUpdateCheckAt(context: Context): Long {
        return Math.max(
            0L,
            AppSettings.prefs(context).getLong(AppSettings.KEY_LAST_UPDATE_CHECK_AT, 0L),
        )
    }

    @JvmStatic
    fun setLastUpdateCheckAt(context: Context, value: Long) {
        AppSettings.prefs(context)
            .edit()
            .putLong(AppSettings.KEY_LAST_UPDATE_CHECK_AT, Math.max(0L, value))
            .apply()
    }

    @JvmStatic
    fun lastAppUpdateCheckAt(context: Context): Long {
        val prefs: SharedPreferences = AppSettings.updateCheckPrefs(context)!!
        return Math.max(0L, prefs.getLong(AppSettings.KEY_LAST_APP_UPDATE_CHECK_AT, 0L))
    }

    @JvmStatic
    fun setLastAppUpdateCheckAt(context: Context, value: Long) {
        val clean: Long = Math.max(0L, value)
        AppSettings.updateCheckPrefs(context)!!.edit()
            .putLong(AppSettings.KEY_LAST_APP_UPDATE_CHECK_AT, clean)
            .apply()
    }

    @JvmStatic
    fun lastNavigatorUpdateCheckAt(context: Context): Long {
        val prefs: SharedPreferences = AppSettings.updateCheckPrefs(context)!!
        return Math.max(0L, prefs.getLong(AppSettings.KEY_LAST_NAVIGATOR_UPDATE_CHECK_AT, 0L))
    }

    @JvmStatic
    fun setLastNavigatorUpdateCheckAt(context: Context, value: Long) {
        val clean: Long = Math.max(0L, value)
        AppSettings.updateCheckPrefs(context)!!.edit()
            .putLong(AppSettings.KEY_LAST_NAVIGATOR_UPDATE_CHECK_AT, clean)
            .apply()
    }

    @JvmStatic
    fun resetUpdateCheckTimes(context: Context) {
        AppSettings.prefs(context)
            .edit()
            .putLong(AppSettings.KEY_LAST_UPDATE_CHECK_AT, 0L)
            .putLong(AppSettings.KEY_LAST_APP_UPDATE_CHECK_AT, 0L)
            .putLong(AppSettings.KEY_LAST_NAVIGATOR_UPDATE_CHECK_AT, 0L)
            .apply()
    }

    @kotlin.jvm.Synchronized
    private fun updateCheckPrefs(context: Context): SharedPreferences? {
        val prefs = AppSettings.prefs(context)
        val appMigrated: Boolean = prefs.contains(AppSettings.KEY_LAST_APP_UPDATE_CHECK_AT)
        val navigatorMigrated: Boolean =
            prefs.contains(AppSettings.KEY_LAST_NAVIGATOR_UPDATE_CHECK_AT)
        if (appMigrated && navigatorMigrated) return prefs
        val legacy: Long = Math.max(0L, prefs.getLong(AppSettings.KEY_LAST_UPDATE_CHECK_AT, 0L))
        val editor = prefs.edit()
        if (!appMigrated) editor!!.putLong(AppSettings.KEY_LAST_APP_UPDATE_CHECK_AT, legacy)
        if (!navigatorMigrated)
            editor!!.putLong(AppSettings.KEY_LAST_NAVIGATOR_UPDATE_CHECK_AT, legacy)
        editor!!.apply()
        return prefs
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.max(min, Math.min(max, value))
    }

    private fun normalizeOtherSource(value: Int): Int {
        if (
            value == AppSettings.OTHER_SOURCE_USB ||
                value == AppSettings.OTHER_SOURCE_BLUETOOTH ||
                value == AppSettings.OTHER_SOURCE_MY_MUSIC ||
                value == AppSettings.OTHER_SOURCE_CARPLAY
        ) {
            return value
        }
        return AppSettings.OTHER_SOURCE_ANDROID
    }

    private fun normalizeCallSource(value: Int): Int {
        if (
            value == AppSettings.CALL_SOURCE_ANDROID_AUTO ||
                value == AppSettings.CALL_SOURCE_CARPLAY ||
                value == AppSettings.CALL_SOURCE_BLUETOOTH
        ) {
            return value
        }
        return AppSettings.CALL_SOURCE_BLUETOOTH
    }

    private fun normalizeMediaProfile(value: Int): Int {
        if (
            value == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID ||
                value == AppSettings.MEDIA_PROFILE_UART_REAL ||
                value == AppSettings.MEDIA_PROFILE_OFF
        ) {
            return value
        }
        return AppSettings.MEDIA_PROFILE_TEYES
    }

    @JvmStatic
    fun normalizeMediaSessionPackage(value: String?): String {
        if (value == null) return ""
        val clean = value.trim().lowercase(Locale.US)
        if (clean.isEmpty() || clean.length > 200) return ""
        for (index in 0..<clean.length) {
            val c: Char = clean.get(index)
            val allowed: Boolean =
                c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '_'
            if (!allowed) return ""
        }
        return clean
    }

    private fun normalizeRctaStyle(value: Int): Int {
        if (value == AppSettings.RCTA_STYLE_TEYES) return AppSettings.RCTA_STYLE_TEYES
        return if (value == AppSettings.RCTA_STYLE_TYPE_2) AppSettings.RCTA_STYLE_TYPE_2
        else AppSettings.RCTA_STYLE_TYPE_1
    }

    private fun normalizeRctaSoundMode(value: Int): Int {
        return if (value == AppSettings.RCTA_SOUND_VOICE) AppSettings.RCTA_SOUND_VOICE
        else AppSettings.RCTA_SOUND_TONE
    }

    private fun normalizeRctaColor(value: Int): Int {
        if (
            value == AppSettings.RCTA_COLOR_RED ||
                value == AppSettings.RCTA_COLOR_CYAN ||
                value == AppSettings.RCTA_COLOR_GREEN
        ) {
            return value
        }
        return AppSettings.RCTA_COLOR_AMBER
    }

    private fun legacyNavOutputMode(prefs: SharedPreferences): Int {
        if (prefs.getBoolean(AppSettings.KEY_NAV_FINISH_DIRECTION, false)) {
            return NavigationOutputMode.FINISH_DIRECTION
        }
        if (prefs.getBoolean(AppSettings.KEY_NAV_TBT, false)) {
            return NavigationOutputMode.TBT
        }
        return NavigationOutputMode.NORMAL
    }

    private fun prefs(context: Context): SharedPreferences {
        AppSettings.migrateLegacyPrefs(context)
        return context.getSharedPreferences(AppSettings.NAME, Context.MODE_PRIVATE)
    }

    private fun migrateLegacyPrefs(context: Context) {
        if (AppSettings.legacyPrefsChecked) return
        synchronized(AppSettings::class.java) {
            if (AppSettings.legacyPrefsChecked) return
            val current = context.getSharedPreferences(AppSettings.NAME, Context.MODE_PRIVATE)
            val legacy = context.getSharedPreferences(AppSettings.LEGACY_NAME, Context.MODE_PRIVATE)
            if (
                !current!!.contains(AppSettings.KEY_SCHEMA) &&
                    legacy!!.contains(AppSettings.KEY_SCHEMA)
            ) {
                val edit = current.edit()
                for (entry in legacy.getAll().entries) {
                    val value = entry.value
                    if (value is Boolean) {
                        edit!!.putBoolean(entry.key, value)
                    } else if (value is Int) {
                        edit!!.putInt(entry.key, value)
                    } else if (value is Long) {
                        edit!!.putLong(entry.key, (value as Long?)!!)
                    } else if (value is Float) {
                        edit!!.putFloat(entry.key, (value as Float?)!!)
                    } else if (value is String) {
                        edit!!.putString(entry.key, value as String?)
                    }
                }
                edit!!.apply()
            }
            AppSettings.legacyPrefsChecked = true
        }
    }
}
