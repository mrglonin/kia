package kia.app.media.domain;

import kia.app.core.settings.AppSettings;

/**
 * Keeps Android/UART media controls visible without leaking them into the legacy TEYES path.
 */
public final class MediaSettingsVisibilityPolicy {
    private MediaSettingsVisibilityPolicy() {
    }

    public static boolean showsUniversalControls(int profile) {
        return profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID
                || profile == AppSettings.MEDIA_PROFILE_UART_REAL;
    }

    public static boolean showsTimingControls(int profile) {
        return showsUniversalControls(profile);
    }

    public static boolean showsSourceReassertControl(int profile, boolean expertMode) {
        return showsUniversalControls(profile) && expertMode;
    }
}
