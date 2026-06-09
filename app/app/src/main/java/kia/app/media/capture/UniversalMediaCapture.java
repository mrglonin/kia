package kia.app.media.capture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import kia.app.core.settings.AppSettings;
import kia.app.media.domain.MediaFeature;
import kia.app.media.domain.RadioStationStore;

final class UniversalMediaCapture {
    private static final long POLL_MS = 1000L;

    private final Context app;
    private final AndroidMediaSessionClient androidMedia;
    private final SpdRadioServiceClient radioMedia;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            scanNow();
            handler.postDelayed(this, POLL_MS);
        }
    };

    UniversalMediaCapture(Context context) {
        this.app = context.getApplicationContext();
        this.androidMedia = AndroidMediaSessionClient.get(app);
        this.radioMedia = SpdRadioServiceClient.get(app);
    }

    void start() {
        if (running) return;
        running = true;
        radioMedia.start();
        handler.post(poll);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poll);
        radioMedia.stop();
    }

    boolean scanNow() {
        if (!AppSettings.universalMediaProfile(app)) return false;
        AndroidMediaSessionClient.Snapshot session = androidMedia.readUniversalSnapshot();
        if (session != null && session.isPlaying()) {
            reportAndroidMediaSession(session);
            return true;
        }
        SpdRadioServiceClient.Snapshot radio = radioMedia.readSnapshot();
        if (radio != null && radio.hasFrequency() && radio.isCurrent()) {
            reportRadio(radio);
            return true;
        }
        if (session != null) {
            reportAndroidMediaSession(session);
            return true;
        }
        MediaFeature.get(app).reportIdle("", "universal media empty");
        return false;
    }

    private void reportAndroidMediaSession(AndroidMediaSessionClient.Snapshot snapshot) {
        if (snapshot == null) return;
        MediaFeature.get(app).report(null, snapshot.packageName, snapshot.artist, snapshot.title,
                snapshot.remainingMs, snapshot.playing);
    }

    private void reportRadio(SpdRadioServiceClient.Snapshot radio) {
        String frequency = radio.frequencyText();
        if (TextUtils.isEmpty(frequency)) return;
        String source = radio.source();
        String station = RadioStationStore.resolve(app, source, frequency, radio.stationName(""));
        MediaFeature.get(app).report(source, SpdRadioServiceClient.PACKAGE,
                frequency, station, -1L, radio.isPlaying());
    }
}
