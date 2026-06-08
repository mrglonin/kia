package kia.app.media.capture;

import android.content.Context;

import kia.app.core.settings.AppSettings;

public final class MediaCaptureManager {
    private final Context app;
    private final TeyesWidgetCapture teyesWidgetCapture;

    public MediaCaptureManager(Context context) {
        this.app = context.getApplicationContext();
        this.teyesWidgetCapture = new TeyesWidgetCapture(app);
    }

    public void start() {
        if (!AppSettings.mediaEnabled(app)) {
            stop();
            return;
        }
        teyesWidgetCapture.start();
    }

    public void stop() {
        teyesWidgetCapture.stop();
    }

    public static boolean scanOnce(Context context) {
        if (context == null) return false;
        if (!AppSettings.mediaEnabled(context)) return false;
        return new TeyesWidgetCapture(context).scanNow();
    }
}
