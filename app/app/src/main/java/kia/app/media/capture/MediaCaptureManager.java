package kia.app.media.capture;

import android.content.Context;

import kia.app.core.settings.AppSettings;

public final class MediaCaptureManager {
    private final Context app;
    private final TeyesWidgetCapture teyesWidgetCapture;
    private final UniversalMediaCapture universalMediaCapture;
    private int activeProfile = AppSettings.MEDIA_PROFILE_OFF;

    public MediaCaptureManager(Context context) {
        this.app = context.getApplicationContext();
        this.teyesWidgetCapture = new TeyesWidgetCapture(app);
        this.universalMediaCapture = new UniversalMediaCapture(app);
    }

    public void start() {
        int desiredProfile = AppSettings.mediaEnabled(app)
                ? AppSettings.mediaProfile(app)
                : AppSettings.MEDIA_PROFILE_OFF;
        if (desiredProfile != activeProfile) {
            stopActiveCapture();
            activeProfile = desiredProfile;
        }
        startActiveCapture();
    }

    public void stop() {
        stopActiveCapture();
        activeProfile = AppSettings.MEDIA_PROFILE_OFF;
    }

    private void startActiveCapture() {
        if (activeProfile == AppSettings.MEDIA_PROFILE_TEYES) {
            teyesWidgetCapture.start();
        } else if (activeProfile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID
                || activeProfile == AppSettings.MEDIA_PROFILE_UART_REAL) {
            universalMediaCapture.start();
        }
    }

    private void stopActiveCapture() {
        if (activeProfile == AppSettings.MEDIA_PROFILE_TEYES) {
            teyesWidgetCapture.stop();
        } else if (activeProfile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID
                || activeProfile == AppSettings.MEDIA_PROFILE_UART_REAL) {
            universalMediaCapture.stop();
        }
    }

    public static boolean scanOnce(Context context) {
        if (context == null) return false;
        if (!AppSettings.mediaEnabled(context)) return false;
        if (AppSettings.teyesMediaProfile(context)) {
            return new TeyesWidgetCapture(context).scanNow();
        }
        if (AppSettings.universalMediaProfile(context)) {
            return new UniversalMediaCapture(context).scanNow();
        }
        return false;
    }
}
