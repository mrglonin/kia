package kia.app.media.capture;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.List;

import kia.app.core.AppLog;

final class AndroidMediaSessionClient {
    private static AndroidMediaSessionClient instance;

    private final Context app;
    private final ComponentName listenerComponent;
    private String lastError = "";
    private String lastInfoKey = "";

    static synchronized AndroidMediaSessionClient get(Context context) {
        if (instance == null) instance = new AndroidMediaSessionClient(context);
        return instance;
    }

    private AndroidMediaSessionClient(Context context) {
        this.app = context.getApplicationContext();
        this.listenerComponent = new ComponentName(app, MediaNotificationListener.class);
    }

    Snapshot readSnapshot() {
        return readSnapshot(null, true, false);
    }

    Snapshot readPackageSnapshot(String packageName) {
        return readSnapshot(packageName, false, true);
    }

    private Snapshot readSnapshot(String packageName, boolean applyIgnore, boolean allowStopped) {
        MediaSessionManager manager = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null) return null;
        List<MediaController> controllers;
        try {
            controllers = manager.getActiveSessions(listenerComponent);
        } catch (SecurityException e) {
            reportError("notification listener permission missing");
            return null;
        } catch (Exception e) {
            reportError("read sessions failed " + e.getClass().getSimpleName());
            return null;
        }
        if (controllers == null || controllers.isEmpty()) return null;

        Snapshot fallback = null;
        for (MediaController controller : controllers) {
            if (!TextUtils.isEmpty(packageName) && !TextUtils.equals(packageName, controller.getPackageName())) continue;
            Snapshot snapshot = snapshot(controller, applyIgnore, allowStopped);
            if (snapshot == null) continue;
            if (snapshot.isPlaying()) {
                reportInfo(snapshot);
                return snapshot;
            }
            if (fallback == null) fallback = snapshot;
        }
        reportInfo(fallback);
        return fallback;
    }

    private Snapshot snapshot(MediaController controller) {
        return snapshot(controller, true, false);
    }

    private Snapshot snapshot(MediaController controller, boolean applyIgnore, boolean allowStopped) {
        if (controller == null || (applyIgnore && ignore(controller.getPackageName()))) return null;
        PlaybackState state = controller.getPlaybackState();
        MediaMetadata metadata = controller.getMetadata();
        if (state == null || metadata == null) return null;
        int stateCode = state.getState();
        if (stateCode == PlaybackState.STATE_NONE
                || (stateCode == PlaybackState.STATE_STOPPED && !allowStopped)) return null;

        MediaDescription description = metadata.getDescription();
        String title = first(
                text(metadata, MediaMetadata.METADATA_KEY_TITLE),
                description == null || description.getTitle() == null ? "" : description.getTitle().toString());
        String artist = first(
                text(metadata, MediaMetadata.METADATA_KEY_ARTIST),
                text(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                text(metadata, MediaMetadata.METADATA_KEY_AUTHOR),
                description == null || description.getSubtitle() == null ? "" : description.getSubtitle().toString());
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(artist)) return null;

        long durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long remainingMs = remainingMs(durationMs, state);
        return new Snapshot(controller.getPackageName(), artist, title, remainingMs,
                stateCode == PlaybackState.STATE_PLAYING);
    }

    private boolean ignore(String pkg) {
        if (TextUtils.isEmpty(pkg)) return true;
        return pkg.equals(app.getPackageName())
                || pkg.startsWith("com.android.")
                || pkg.startsWith("android")
                || pkg.startsWith("com.spd.")
                || pkg.startsWith("com.teyes.")
                || pkg.startsWith("com.alink.")
                || pkg.startsWith("com.yf.");
    }

    private void reportError(String message) {
        if (TextUtils.equals(message, lastError)) return;
        lastError = message;
        AppLog.line(app, "Android media session: " + message);
    }

    private void reportInfo(Snapshot snapshot) {
        if (snapshot == null) return;
        String key = snapshot.packageName + "|" + snapshot.artist + "|" + snapshot.title + "|"
                + snapshot.remainingMs + "|" + snapshot.playing;
        if (TextUtils.equals(key, lastInfoKey)) return;
        lastInfoKey = key;
        AppLog.line(app, "Android media session: " + snapshot.packageName + " | "
                + (TextUtils.isEmpty(snapshot.artist) ? "-" : snapshot.artist)
                + " | " + snapshot.title + " | " + snapshot.playing);
    }

    private static long remainingMs(long durationMs, PlaybackState state) {
        if (durationMs <= 0L || state == null) return -1L;
        long positionMs = Math.max(0L, state.getPosition());
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            long deltaMs = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            if (deltaMs > 0L) {
                positionMs += (long) (deltaMs * Math.max(0f, state.getPlaybackSpeed()));
            }
        }
        return Math.max(0L, durationMs - positionMs);
    }

    private static String text(MediaMetadata metadata, String key) {
        CharSequence value = metadata.getText(key);
        return value == null ? "" : clean(value.toString());
    }

    private static String first(String... values) {
        for (String value : values) {
            String clean = clean(value);
            if (!TextUtils.isEmpty(clean)) return clean;
        }
        return "";
    }

    private static String clean(String value) {
        if (value == null) return "";
        String out = value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if ("null".equalsIgnoreCase(out) || "[]".equals(out)) return "";
        return out;
    }

    static final class Snapshot {
        final String packageName;
        final String artist;
        final String title;
        final long remainingMs;
        final boolean playing;

        private Snapshot(String packageName, String artist, String title, long remainingMs, boolean playing) {
            this.packageName = clean(packageName);
            this.artist = clean(artist);
            this.title = clean(title);
            this.remainingMs = remainingMs;
            this.playing = playing;
        }

        boolean isPlaying() {
            return playing;
        }
    }
}
