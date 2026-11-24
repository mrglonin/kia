package kia.app.media.capture

import android.content.Context
import kia.app.core.settings.AppSettings

class MediaCaptureManager(context: Context) {
    private val app: Context
    private val teyesWidgetCapture: TeyesWidgetCapture
    private val universalMediaCapture: UniversalMediaCapture
    private var activeProfile: Int = AppSettings.MEDIA_PROFILE_OFF

    init {
        this.app = context.applicationContext ?: context
        this.teyesWidgetCapture = TeyesWidgetCapture(app)
        this.universalMediaCapture = UniversalMediaCapture(app)
    }

    fun start() {
        val desiredProfile: Int =
            if (AppSettings.mediaEnabled(app)) AppSettings.mediaProfile(app)
            else AppSettings.MEDIA_PROFILE_OFF
        if (desiredProfile != activeProfile) {
            stopActiveCapture()
            activeProfile = desiredProfile
        }
        startActiveCapture()
    }

    fun stop() {
        stopActiveCapture()
        activeProfile = AppSettings.MEDIA_PROFILE_OFF
    }

    private fun startActiveCapture() {
        if (activeProfile == AppSettings.MEDIA_PROFILE_TEYES) {
            teyesWidgetCapture.start()
        } else if (
            activeProfile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID ||
                activeProfile == AppSettings.MEDIA_PROFILE_UART_REAL
        ) {
            universalMediaCapture.start()
        }
    }

    private fun stopActiveCapture() {
        if (activeProfile == AppSettings.MEDIA_PROFILE_TEYES) {
            teyesWidgetCapture.stop()
        } else if (
            activeProfile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID ||
                activeProfile == AppSettings.MEDIA_PROFILE_UART_REAL
        ) {
            universalMediaCapture.stop()
        }
    }

    companion object {
        @JvmStatic
        fun scanOnce(context: Context?): Boolean {
            if (context == null) return false
            if (!AppSettings.mediaEnabled(context)) return false
            if (AppSettings.teyesMediaProfile(context)) {
                return TeyesWidgetCapture(context).scanNow()
            }
            if (AppSettings.universalMediaProfile(context)) {
                return UniversalMediaCapture(context).scanNow()
            }
            return false
        }
    }
}
