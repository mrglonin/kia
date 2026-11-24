package kia.app.media.domain

import kia.app.core.settings.AppSettings

/** Keeps Android/UART media controls visible without leaking them into the legacy TEYES path. */
object MediaSettingsVisibilityPolicy {
    @JvmStatic
    fun showsUniversalControls(profile: Int): Boolean {
        return profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID ||
            profile == AppSettings.MEDIA_PROFILE_UART_REAL
    }

    @JvmStatic
    fun showsTimingControls(profile: Int): Boolean {
        return MediaSettingsVisibilityPolicy.showsUniversalControls(profile)
    }

    @JvmStatic
    fun showsSourceReassertControl(profile: Int, expertMode: Boolean): Boolean {
        return MediaSettingsVisibilityPolicy.showsUniversalControls(profile) && expertMode
    }
}
