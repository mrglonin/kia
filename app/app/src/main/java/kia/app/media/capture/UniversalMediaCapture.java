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
        if (!running) return;
        running = false;
        handler.removeCallbacks(poll);
        radioMedia.stop();
    }

    boolean scanNow() {
        if (!AppSettings.universalMediaProfile(app)) return false;
        MediaFeature mediaFeature = MediaFeature.get(app);
        boolean uartHasRealSource = AppSettings.uartRealMediaProfile(app)
                && mediaFeature.hasRealSource();
        if (uartHasRealSource && mediaFeature.hasRealRadioSource()) {
            SpdRadioServiceClient.Snapshot radio = radioMedia.readSnapshot();
            if (radio != null && radio.hasFrequency() && radio.isCurrent()
                    && matchesRealRadioFrequency(radio.source(),
                    mediaFeature.realRadioFrequency(), radio.frequencyText())) {
                reportRadio(radio);
            }
            // The head unit's real FM/AM source remains authoritative until another 0x7A source
            // arrives. A stale Android PLAYING session must not overwrite it on the next poll.
            return true;
        }

        if (!uartHasRealSource) {
            SpdRadioServiceClient.Snapshot radio = radioMedia.readSnapshot();
            if (radio != null && radio.hasFrequency() && radio.isCurrent()) {
                reportRadio(radio);
                return true;
            }
        }
        AndroidMediaSessionClient.Snapshot session = androidMedia.readUniversalSnapshot();
        if (session != null && session.isPlaying()) {
            reportAndroidMediaSession(session);
            return true;
        }
        if (session != null) {
            reportAndroidMediaSession(session);
            return true;
        }
        MediaFeature.get(app).reportIdle("", "universal media empty");
        return false;
    }

    static boolean matchesRealRadioFrequency(String source, String realFrequency,
                                             String snapshotFrequency) {
        String expected = RadioStationStore.normalizeFrequencyInput(source, realFrequency);
        String actual = RadioStationStore.normalizeFrequencyInput(source, snapshotFrequency);
        return expected.isEmpty() || expected.equals(actual);
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
        String station = RadioStationStore.resolveUniversal(app, source, frequency,
                radio.stationNameForUniversal());
        MediaFeature.get(app).report(source, SpdRadioServiceClient.PACKAGE,
                frequency, station, -1L, radio.isPlaying());
    }
}
