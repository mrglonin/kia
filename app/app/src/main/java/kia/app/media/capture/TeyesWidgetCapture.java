package kia.app.media.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.Locale;

import kia.app.media.domain.MediaFeature;

final class TeyesWidgetCapture {
    private static final String PACKAGE = "com.teyes.music.widget";
    private static final String SERVICE = "com.teyes.music.service.MediaService";
    private static final String START_ACTION = "com.teyes.widget.action.START_SERVICE";
    private static final Uri PROGRESS_URI = Uri.parse("content://com.teyes.music.provider/progress");
    private static final long POLL_MS = 1000L;
    private static final long RADIO_SEARCH_POLL_MS = 100L;
    private static final long RADIO_SEARCH_FAST_HOLD_MS = 1500L;
    private static final long AM_STABLE_MS = 1500L;

    private final Context app;
    private final SpdMediaServiceClient spdMedia;
    private final SpdBluetoothServiceClient btMedia;
    private final SpdRadioServiceClient radioMedia;
    private final TeyesRadioBrowserClient teyesRadio;
    private final TeyesOnlineRadioClient onlineRadio;
    private final AndroidMediaSessionClient androidMedia;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastPokeAt;
    private long radioSearchFastUntil;
    private String pendingAmFrequency = "";
    private long pendingAmFrequencyAt;
    private String reportedAmFrequency = "";
    private boolean running;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            scanNow();
            handler.postDelayed(this, nextPollDelay());
        }
    };

    TeyesWidgetCapture(Context context) {
        this.app = context.getApplicationContext();
        this.spdMedia = SpdMediaServiceClient.get(app);
        this.btMedia = SpdBluetoothServiceClient.get(app);
        this.radioMedia = SpdRadioServiceClient.get(app);
        this.teyesRadio = TeyesRadioBrowserClient.get(app);
        this.onlineRadio = TeyesOnlineRadioClient.get(app);
        this.androidMedia = AndroidMediaSessionClient.get(app);
    }

    void start() {
        if (running) return;
        running = true;
        spdMedia.start();
        btMedia.start();
        radioMedia.start();
        teyesRadio.start();
        onlineRadio.start();
        handler.post(poll);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poll);
        spdMedia.stop();
        btMedia.stop();
        radioMedia.stop();
        teyesRadio.stop();
        onlineRadio.stop();
    }

    boolean scanNow() {
        pokeWidget();
        Cursor cursor = null;
        try {
            cursor = app.getContentResolver().query(PROGRESS_URI, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return reportFallback();
            }
            WidgetState best = null;
            do {
                WidgetState state = read(cursor);
                if (state != null && !TextUtils.isEmpty(state.source)) best = state;
            } while (cursor.moveToNext());
            if (best == null) {
                return reportFallback();
            }
            if (isRadioSource(best.source) && best.isSearching()) {
                if (isAmSource(best.source)) {
                    markRadioSearchFast();
                    resetPendingAmFrequency();
                    MediaFeature.get(app).reportRadioSearch(best.source, PACKAGE, best.frequency);
                    return true;
                }
                SpdRadioServiceClient.Snapshot currentRadio = radioMedia.readSnapshot();
                if (currentRadio != null && currentRadio.hasFrequency()) {
                    if (reportRadio(currentRadio)) return true;
                } else if (!TextUtils.isEmpty(best.frequency)) {
                    markRadioSearchFast();
                    MediaFeature.get(app).reportRadioSearch(best.source, PACKAGE, best.frequency);
                }
                return true;
            }
            if (isRadioSource(best.source)) {
                if (isAmSource(best.source)) {
                    if (!TextUtils.isEmpty(best.frequency) && stableAmFrequency(best.source, best.frequency)) {
                        long displayTimeMs = remainingMs(best.positionMs, best.totalDurationMs);
                        MediaFeature.get(app).report(best.source, PACKAGE, best.frequency, "", displayTimeMs);
                        return true;
                    }
                    SpdRadioServiceClient.Snapshot currentRadio = radioMedia.readSnapshot();
                    if (reportAmRadioFallback(currentRadio)) return true;
                    return true;
                }
                SpdRadioServiceClient.Snapshot currentRadio = radioMedia.readSnapshot();
                if (currentRadio != null && currentRadio.hasFrequency()) {
                    if (reportRadio(currentRadio)) return true;
                }
                if (!TextUtils.isEmpty(best.frequency)) {
                    if (!stableAmFrequency(best.source, best.frequency)) return true;
                    long displayTimeMs = remainingMs(best.positionMs, best.totalDurationMs);
                    MediaFeature.get(app).report(best.source, PACKAGE, best.frequency, "", displayTimeMs);
                    return true;
                }
                return true;
            }
            if ("TEYES".equals(best.source)) {
                if (reportTeyesOnline()) return true;
            }
            if ("USB".equals(best.source)) {
                if (reportUsb()) return true;
            }
            if (isBluetoothSource(best.source)) {
                if (reportBluetooth(best.positionMs)) return true;
            }
            if (isGenericAppSource(best)) {
                AndroidMediaSessionClient.Snapshot appMedia = androidMedia.readSnapshot();
                if (appMedia != null && appMedia.isPlaying()) {
                    if (reportAndroidMediaSession(appMedia)) return true;
                }
                if (reportConcreteSourceIfCurrent()) return true;
                return false;
            }
            long displayTimeMs = remainingMs(best.positionMs, best.totalDurationMs);
            if (best.hasText()) {
                MediaFeature.get(app).report(best.source, PACKAGE, best.displayArtist(), best.displayTitle(), displayTimeMs);
            } else {
                MediaFeature.get(app).reportSourceOnly(best.source, PACKAGE, displayTimeMs);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private boolean reportFallback() {
        SpdRadioServiceClient.Snapshot radio = radioMedia.readSnapshot();
        if (radio != null && radio.hasFrequency() && radio.isCurrent()) {
            if (reportRadio(radio)) return true;
        }
        TeyesOnlineRadioClient.Snapshot online = onlineRadio.readSnapshot();
        if (online != null && online.isCurrent()) {
            reportTeyesOnline(online);
            return true;
        }
        if (reportAndroidMediaSession(androidMedia.readSnapshot())) return true;
        MediaFeature.get(app).reportIdle(PACKAGE, "teyes provider empty");
        return false;
    }

    private boolean reportConcreteSourceIfCurrent() {
        SpdRadioServiceClient.Snapshot radio = radioMedia.readSnapshot();
        if (radio != null && radio.hasFrequency() && radio.isCurrent()) {
            if (reportRadio(radio)) return true;
        }
        TeyesOnlineRadioClient.Snapshot online = onlineRadio.readSnapshot();
        if (online != null && online.isCurrent()) {
            reportTeyesOnline(online);
            return true;
        }
        SpdMediaServiceClient.NowPlaying usb = spdMedia.readNowPlaying();
        if (usb != null && usb.hasText() && usb.isPlaying()) {
            MediaFeature.get(app).report("USB", SpdMediaServiceClient.PACKAGE,
                    usb.artist, usb.title, usb.remainingMs(), true);
            return true;
        }
        SpdBluetoothServiceClient.Snapshot bt = btMedia.readSnapshot(-1L);
        if (bt != null && bt.hasText() && bt.isPlaying()) {
            MediaFeature.get(app).report("Bluetooth", SpdBluetoothServiceClient.PACKAGE,
                    bt.artist, bt.title, bt.remainingMs(), true);
            return true;
        }
        return false;
    }

    private boolean reportRadio(SpdRadioServiceClient.Snapshot radio) {
        String frequency = radio.frequencyText();
        if ("AM".equals(radio.source())) return false;
        if (radio.isSearching()) {
            markRadioSearchFast();
            MediaFeature.get(app).reportRadioSearch(radio.source(), SpdRadioServiceClient.PACKAGE, frequency);
            return true;
        }
        String teyesStation = teyesRadio.stationName(radio.source(), frequency);
        MediaFeature.get(app).report(radio.source(), SpdRadioServiceClient.PACKAGE,
                frequency, radio.stationName(teyesStation), -1L, radio.isPlaying());
        return true;
    }

    private boolean reportAmRadioFallback(SpdRadioServiceClient.Snapshot radio) {
        if (radio == null || !radio.hasFrequency() || !"AM".equals(radio.source())) return false;
        if (radio.isSearching() || System.currentTimeMillis() < radioSearchFastUntil) {
            markRadioSearchFast();
            resetPendingAmFrequency();
            MediaFeature.get(app).reportRadioSearch("AM", SpdRadioServiceClient.PACKAGE, "");
            return true;
        }
        String frequency = radio.frequencyText();
        if (!stableAmFrequency("AM", frequency)) return true;
        MediaFeature.get(app).report("AM", SpdRadioServiceClient.PACKAGE, frequency, "", -1L,
                radio.isPlaying());
        return true;
    }

    private boolean stableAmFrequency(String source, String frequency) {
        if (!"AM".equalsIgnoreCase(clean(source))) return true;
        String cleanFrequency = clean(frequency);
        if (TextUtils.isEmpty(cleanFrequency)) return false;
        long now = System.currentTimeMillis();
        if (!TextUtils.equals(cleanFrequency, pendingAmFrequency)) {
            pendingAmFrequency = cleanFrequency;
            pendingAmFrequencyAt = now;
            return TextUtils.equals(cleanFrequency, reportedAmFrequency);
        }
        if (TextUtils.equals(cleanFrequency, reportedAmFrequency)
                || now - pendingAmFrequencyAt >= AM_STABLE_MS) {
            reportedAmFrequency = cleanFrequency;
            return true;
        }
        return false;
    }

    private void resetPendingAmFrequency() {
        pendingAmFrequency = "";
        pendingAmFrequencyAt = 0L;
    }

    private boolean reportUsb() {
        SpdMediaServiceClient.NowPlaying nowPlaying = spdMedia.readNowPlaying();
        if (nowPlaying == null || !nowPlaying.hasText()) return false;
        MediaFeature.get(app).report("USB", SpdMediaServiceClient.PACKAGE,
                nowPlaying.artist, nowPlaying.title, nowPlaying.remainingMs(), nowPlaying.isPlaying());
        return true;
    }

    private boolean reportBluetooth(long fallbackPositionMs) {
        SpdBluetoothServiceClient.Snapshot bt = btMedia.readSnapshot(fallbackPositionMs);
        if (bt == null || !bt.hasText()) {
            AndroidMediaSessionClient.Snapshot session = androidMedia.readPackageSnapshot("com.android.bluetooth");
            if (session == null) return false;
            MediaFeature.get(app).report("Bluetooth", session.packageName,
                    session.artist, session.title, session.remainingMs, session.playing);
            return true;
        }
        MediaFeature.get(app).report("Bluetooth", SpdBluetoothServiceClient.PACKAGE,
                bt.artist, bt.title, bt.remainingMs(), bt.isPlaying());
        return true;
    }

    private boolean reportTeyesOnline() {
        TeyesOnlineRadioClient.Snapshot online = onlineRadio.readSnapshot();
        if (online == null || !online.isCurrent()) return false;
        reportTeyesOnline(online);
        return true;
    }

    private void reportTeyesOnline(TeyesOnlineRadioClient.Snapshot online) {
        MediaFeature.get(app).report(online.source(), TeyesOnlineRadioClient.PACKAGE,
                online.artist(), online.title(), -1L, online.isPlaying());
    }

    private long nextPollDelay() {
        return System.currentTimeMillis() < radioSearchFastUntil ? RADIO_SEARCH_POLL_MS : POLL_MS;
    }

    private void markRadioSearchFast() {
        radioSearchFastUntil = System.currentTimeMillis() + RADIO_SEARCH_FAST_HOLD_MS;
    }

    private boolean reportAndroidMediaSession(AndroidMediaSessionClient.Snapshot snapshot) {
        if (snapshot == null) return false;
        if (!snapshot.playing) return false;
        MediaFeature.get(app).report(null, snapshot.packageName, snapshot.artist, snapshot.title,
                snapshot.remainingMs, snapshot.playing);
        return true;
    }

    private WidgetState read(Cursor cursor) {
        String songType = value(cursor, "songType");
        String appName = firstValue(cursor, "app_name", "appName", "player", "sourceName");
        String source = sourceFromSongType(songType, appName);
        if (TextUtils.isEmpty(source)) return null;
        WidgetState state = new WidgetState();
        state.source = source;
        state.songType = clean(songType);
        state.title = firstValue(cursor, "song_title", "songTitle", "title", "name", "track", "track_title", "music_title");
        state.artist = firstValue(cursor, "song_artist", "songArtist", "artist", "singer", "author", "station", "station_name");
        state.frequency = firstValue(cursor, "freq", "frequency", "radio_freq", "fm", "radio_frequency",
                "freq_text", "frequency_text", "radio_text", "radioText", "station_freq", "stationFrequency");
        state.totalDurationMs = longValue(cursor, -1L, "duration", "durationMs", "duration_ms", "total", "totalTime",
                "total_time");
        state.positionMs = longValue(cursor, -1L, "progress", "position", "current", "currentTime", "current_time");
        state.searchState = firstValue(cursor, "search", "scan", "seek", "isSearching", "isSeeking",
                "isScanning", "searching", "seeking", "scanning", "search_state", "seek_state", "scan_state");
        state.appName = clean(appName);
        return state;
    }

    private void pokeWidget() {
        long now = System.currentTimeMillis();
        if (now - lastPokeAt < 30000L) return;
        lastPokeAt = now;
        try {
            Intent broadcast = new Intent(START_ACTION);
            broadcast.setPackage(PACKAGE);
            app.sendBroadcast(broadcast);
        } catch (Exception ignored) {
        }
        try {
            Intent service = new Intent();
            service.setComponent(new ComponentName(PACKAGE, SERVICE));
            app.startService(service);
        } catch (Exception ignored) {
        }
    }

    private static String sourceFromSongType(String songType, String appName) {
        String type = clean(songType);
        String appSource = sourceFromAppName(appName);
        if (TextUtils.isEmpty(type)) return appSource;
        String t = type.toLowerCase(Locale.US);
        if (isBluetoothText(t)) return "Bluetooth";
        if (t.contains("local_radio")) return "FM радио";
        if (t.contains("dab") || t.contains("dts")) return "DTS радио";
        if (t.contains("network_radio")) return "TEYES";
        if (t.contains("local_music")) return "USB";
        if (t.contains("cloud_music") || t.contains("network_music") || t.contains("net_music")) return "OTHER";
        if (t.contains("carplay")) return "CarPlay";
        if (t.contains("android_auto") || t.contains("androidauto")) return "Android Auto";
        if (t.contains("radio")) return t.contains("am") ? "AM" : "FM радио";
        if (t.contains("music")) return "TEYES Media";
        return appSource;
    }

    private static String sourceFromAppName(String appName) {
        String app = clean(appName);
        if (TextUtils.isEmpty(app)) return null;
        String p = app.toLowerCase(Locale.US);
        if (isBluetoothText(p)) return "Bluetooth";
        if (p.contains("янд") || p.contains("yandex")) return "Яндекс Музыка";
        if (p.contains("radio") || p.contains("радио")) return "FM радио";
        if (p.contains("usb") || p.contains("local")) return "USB";
        return app;
    }

    private static boolean isBluetoothText(String value) {
        String p = value == null ? "" : value.toLowerCase(Locale.US);
        return p.contains("bluetooth") || p.contains("bt") || p.contains("a2dp") || p.contains("avrcp");
    }

    private static boolean isBluetoothSource(String value) {
        return isBluetoothText(value);
    }

    private static boolean isRadioSource(String value) {
        String p = value == null ? "" : value.toLowerCase(Locale.US);
        return p.contains("radio") || p.contains("радио") || p.contains("fm") || p.contains("am");
    }

    private static boolean isAmSource(String value) {
        String p = value == null ? "" : value.toLowerCase(Locale.US);
        return p.equals("am") || p.contains("am ");
    }

    private static boolean isGenericAppSource(WidgetState state) {
        if (state == null) return false;
        String source = state.source == null ? "" : state.source.toLowerCase(Locale.US);
        String type = state.songType == null ? "" : state.songType.toLowerCase(Locale.US);
        return source.equals("other")
                || source.contains("янд")
                || source.contains("yandex")
                || source.contains("spotify")
                || type.contains("cloud_music")
                || type.contains("network_music")
                || type.contains("net_music");
    }

    private static String value(Cursor cursor, String column) {
        int idx = cursor.getColumnIndex(column);
        if (idx < 0) return null;
        try {
            return cursor.getString(idx);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstValue(Cursor cursor, String... columns) {
        for (String column : columns) {
            String value = clean(value(cursor, column));
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private static long longValue(Cursor cursor, long fallback, String... columns) {
        for (String column : columns) {
            int idx = cursor.getColumnIndex(column);
            if (idx < 0) continue;
            try {
                long value = cursor.getLong(idx);
                if (value >= 0L) return value;
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static long remainingMs(long positionMs, long totalDurationMs) {
        if (positionMs < 0L || totalDurationMs <= 0L) return -1L;
        return Math.max(0L, totalDurationMs - positionMs);
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private static final class WidgetState {
        String source;
        String songType;
        String title;
        String artist;
        String frequency;
        long positionMs = -1L;
        long totalDurationMs = -1L;
        String searchState;
        String appName;

        boolean hasText() {
            return !TextUtils.isEmpty(displayTitle()) || !TextUtils.isEmpty(displayArtist());
        }

        String displayTitle() {
            if (!TextUtils.isEmpty(frequency)) return frequency;
            return title;
        }

        String displayArtist() {
            return artist;
        }

        boolean isSearching() {
            String value = searchState == null ? "" : searchState.trim().toLowerCase(Locale.US);
            return !TextUtils.isEmpty(value)
                    && !"0".equals(value)
                    && !"false".equals(value)
                    && !"stopped".equals(value)
                    && !"idle".equals(value)
                    && !"none".equals(value);
        }
    }

}
