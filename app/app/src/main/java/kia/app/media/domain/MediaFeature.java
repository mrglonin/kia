package kia.app.media.domain;

import android.content.Context;
import android.text.TextUtils;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.MediaState;
import kia.app.core.settings.AppSettings;
import kia.app.media.cluster.MediaClusterSender;

public final class MediaFeature {
    private static MediaFeature instance;

    private final Context app;
    private final MediaClusterSender clusterSender;
    private String lastKey = "";

    private MediaFeature(Context context) {
        this.app = context.getApplicationContext();
        this.clusterSender = new MediaClusterSender(app);
    }

    public static synchronized MediaFeature get(Context context) {
        if (instance == null) instance = new MediaFeature(context);
        return instance;
    }

    public synchronized void report(String source, String packageName, String artist, String title, long durationMs) {
        report(source, packageName, artist, title, durationMs, true);
    }

    public synchronized void report(String source, String packageName, String artist, String title,
                                    long durationMs, boolean playing) {
        if (!AppSettings.mediaEnabled(app)) return;
        String cleanSource = firstNonEmpty(source, labelFromPackage(packageName));
        String cleanArtist = clean(artist);
        String cleanTitle = clean(title);
        if (isRadioSource(cleanSource, packageName) && !TextUtils.isEmpty(cleanArtist)) {
            cleanTitle = RadioStationStore.resolve(app, cleanSource, cleanArtist, cleanTitle);
        }
        if (TextUtils.isEmpty(cleanSource) && TextUtils.isEmpty(cleanArtist) && TextUtils.isEmpty(cleanTitle)) return;

        long stableDurationMs = stableDuration(cleanSource, packageName, cleanArtist, cleanTitle, durationMs, playing);
        MediaState state = new MediaState(cleanSource, packageName, cleanArtist, cleanTitle,
                stableDurationMs, playing, System.currentTimeMillis());
        StateStore.setMedia(app, state);

        String key = cleanSource + "|" + packageName + "|" + cleanArtist + "|" + cleanTitle + "|" + playing;
        if (!TextUtils.equals(key, lastKey)) {
            lastKey = key;
            if (StateStore.call().active) {
                AppLog.line(app, "Media RX held by call: " + state.summary());
            } else {
                clusterSender.send(state);
                AppLog.line(app, "Media RX: " + state.summary());
            }
        }
    }

    public synchronized void reportSourceOnly(String source, String packageName) {
        reportSourceOnly(source, packageName, -1L);
    }

    public synchronized void reportSourceOnly(String source, String packageName, long durationMs) {
        if (!AppSettings.mediaEnabled(app)) return;
        if (TextUtils.isEmpty(source)) return;
        long stableDurationMs = stableDuration(source, packageName, "", "", durationMs, false);
        MediaState state = new MediaState(source, packageName, "", "", stableDurationMs, false, System.currentTimeMillis());
        StateStore.setMedia(app, state);
        String key = source + "|" + packageName + "|source|false";
        if (!TextUtils.equals(key, lastKey)) {
            lastKey = key;
            if (StateStore.call().active) {
                AppLog.line(app, "Media RX source held by call: " + state.summary());
            } else {
                clusterSender.sendSourceOnly(state);
                AppLog.line(app, "Media RX source: " + state.summary());
            }
        }
    }

    public synchronized void reportRadioSearch(String source, String packageName, String frequency) {
        if (!AppSettings.mediaEnabled(app)) return;
        String cleanSource = firstNonEmpty(source, labelFromPackage(packageName));
        String cleanFrequency = clean(frequency);
        if (TextUtils.isEmpty(cleanSource)) return;

        MediaState state = new MediaState(cleanSource, packageName, cleanFrequency, "", -1L, true,
                System.currentTimeMillis());
        StateStore.setMedia(app, state);
        String key = cleanSource + "|" + packageName + "|radio-search|" + cleanFrequency;
        if (!TextUtils.equals(key, lastKey)) {
            lastKey = key;
            if (StateStore.call().active) {
                AppLog.line(app, "Media RX radio search held by call: " + state.summary());
            } else {
                clusterSender.sendRadioSearch(state);
                AppLog.line(app, "Media RX radio search: " + state.summary());
            }
        }
    }

    public synchronized void reportIdle(String packageName, String reason) {
        if (!AppSettings.mediaEnabled(app)) return;
        MediaState current = StateStore.media();
        MediaState state = new MediaState(current.source, packageName, current.artist, current.title,
                current.durationMs, false, System.currentTimeMillis());
        StateStore.setMedia(app, state);
        String key = state.source + "|" + packageName + "|idle|" + reason;
        if (!TextUtils.equals(key, lastKey)) {
            lastKey = key;
            AppLog.line(app, "Media RX idle: " + state.summary() + " | " + clean(reason));
        }
    }

    public synchronized void resendCurrent(String reason) {
        if (!AppSettings.mediaEnabled(app)) return;
        MediaState current = StateStore.media();
        if (current == null || (TextUtils.isEmpty(current.source) && TextUtils.isEmpty(current.artist)
                && TextUtils.isEmpty(current.title))) {
            return;
        }
        lastKey = "";
        if (StateStore.call().active) {
            AppLog.line(app, "Media resend held by call: " + current.summary()
                    + " | " + clean(reason));
            return;
        }
        clusterSender.send(current);
        AppLog.line(app, "Media resend: " + current.summary() + " | " + clean(reason));
    }

    public synchronized void handleRealMediaStatus(byte[] frame) {
        if (!AppSettings.uartRealMediaProfile(app)) return;
        RealMediaStatus status = parseRealMediaStatus(frame);
        if (status == null || TextUtils.isEmpty(status.frequency)) return;
        report(status.source, "uart.real", status.frequency, "", -1L, true);
    }

    private String labelFromPackage(String packageName) {
        if (TextUtils.isEmpty(packageName)) return "Music";
        String p = packageName.toLowerCase();
        if (p.contains("yandex")) return "Яндекс Музыка";
        if (p.contains("bluetooth") || p.contains("btmusic")) return "Bluetooth";
        if (p.contains("radio")) return "FM радио";
        if (p.contains("spd.media")) return "USB";
        return packageName;
    }

    private static boolean isRadioSource(String source, String packageName) {
        String text = clean(source + " " + packageName).toLowerCase();
        return text.contains("fm") || text.contains("am") || text.contains("radio")
                || text.contains("радио") || text.contains("com.spd.radio");
    }

    private static String firstNonEmpty(String first, String fallback) {
        return TextUtils.isEmpty(first) ? fallback : first;
    }

    private long stableDuration(String source, String packageName, String artist, String title,
                                long durationMs, boolean playing) {
        if (playing) return durationMs;
        MediaState current = StateStore.media();
        if (TextUtils.equals(clean(source), current.source)
                && TextUtils.equals(clean(packageName), current.packageName)
                && TextUtils.equals(clean(artist), current.artist)
                && TextUtils.equals(clean(title), current.title)) {
            return current.durationMs;
        }
        return -1L;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String out = value.replace('\n', ' ').replace('\r', ' ').trim();
        return out.replaceAll("\\s+", " ");
    }

    private static RealMediaStatus parseRealMediaStatus(byte[] frame) {
        if (frame == null || frame.length < 8) return null;
        int source;
        int first;
        int second;
        if (u8(frame, 5) == 0xFD && frame.length >= 13) {
            source = u8(frame, 8);
            first = u8(frame, 10);
            second = u8(frame, 11);
        } else {
            source = u8(frame, 5);
            first = u8(frame, 6);
            second = u8(frame, 7);
        }
        if (source == 0x02) {
            if (first <= 0) return null;
            int decimal = Math.max(0, Math.min(9, second / 10));
            return new RealMediaStatus("FM", first + "." + decimal);
        }
        if (source == 0x09) {
            int khz = (first << 8) | second;
            if (khz <= 0) return null;
            return new RealMediaStatus("AM", String.valueOf(khz));
        }
        return null;
    }

    private static int u8(byte[] frame, int index) {
        return index >= 0 && index < frame.length ? frame[index] & 0xff : 0;
    }

    private static final class RealMediaStatus {
        final String source;
        final String frequency;

        RealMediaStatus(String source, String frequency) {
            this.source = source;
            this.frequency = frequency;
        }
    }
}
