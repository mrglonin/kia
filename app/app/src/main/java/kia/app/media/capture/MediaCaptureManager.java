package kia.app.media.capture;

import android.content.Context;

import kia.app.core.settings.AppSettings;

public final class MediaCaptureManager {
    private final Context app;
    private final TeyesWidgetCapture teyesWidgetCapture;
    private final UniversalMediaCapture universalMediaCapture;

    public MediaCaptureManager(Context context) {
        this.app = context.getApplicationContext();
        this.teyesWidgetCapture = new TeyesWidgetCapture(app);
        this.universalMediaCapture = new UniversalMediaCapture(app);
    }

    public void start() {
        if (!AppSettings.mediaEnabled(app)) {
            stop();
            return;
        }
        int profile = AppSettings.mediaProfile(app);
        if (profile == AppSettings.MEDIA_PROFILE_TEYES) {
            universalMediaCapture.stop();
            teyesWidgetCapture.start();
        } else if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID
                || profile == AppSettings.MEDIA_PROFILE_UART_REAL) {
            teyesWidgetCapture.stop();
            universalMediaCapture.start();
        } else {
            stop();
        }
    }

    public void stop() {
        teyesWidgetCapture.stop();
        universalMediaCapture.stop();
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
