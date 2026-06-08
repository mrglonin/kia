package kia.app.media.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.TextUtils;

import kia.app.core.AppLog;

final class SpdMediaServiceClient {
    static final String PACKAGE = "com.spd.media";

    private static final String ACTION = "android.spd.IMediaService";
    private static final String SERVICE = "com.spd.media.service.MediaService";
    private static final String DESCRIPTOR = "com.spd.media.aidl.IMediaService";
    private static final int TRANSACTION_GET_NOW_PLAYING = 6;
    private static final int MEDIA_PLAYER_STATUS_PLAY = 3;
    private static final long REBIND_DELAY_MS = 5000L;

    private static SpdMediaServiceClient instance;

    private final Context app;
    private IBinder binder;
    private boolean binding;
    private boolean bound;
    private long lastBindAt;
    private String lastError = "";

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            binder = null;
            bound = false;
            binding = false;
            AppLog.line(app, "Media service: com.spd.media binder died");
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            bound = true;
            binding = false;
            lastError = "";
            try {
                service.linkToDeath(deathRecipient, 0);
            } catch (RemoteException ignored) {
                binder = null;
                bound = false;
            }
            AppLog.line(app, "Media service: connected " + name.flattenToShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            bound = false;
            binding = false;
            AppLog.line(app, "Media service: disconnected " + name.flattenToShortString());
        }
    };

    static synchronized SpdMediaServiceClient get(Context context) {
        if (instance == null) instance = new SpdMediaServiceClient(context);
        return instance;
    }

    private SpdMediaServiceClient(Context context) {
        this.app = context.getApplicationContext();
    }

    void start() {
        ensureBound();
    }

    void stop() {
        if (!bound && !binding) return;
        try {
            if (binder != null) binder.unlinkToDeath(deathRecipient, 0);
        } catch (Exception ignored) {
        }
        try {
            app.unbindService(connection);
        } catch (Exception ignored) {
        }
        binder = null;
        bound = false;
        binding = false;
    }

    NowPlaying readNowPlaying() {
        ensureBound();
        IBinder remote = binder;
        if (remote == null || !remote.isBinderAlive()) return null;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_NOW_PLAYING, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            return NowPlaying.read(reply);
        } catch (Exception e) {
            reportError("read failed " + e.getClass().getSimpleName());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void ensureBound() {
        IBinder remote = binder;
        if (remote != null && remote.isBinderAlive()) return;
        if (binding) return;
        long now = System.currentTimeMillis();
        if (now - lastBindAt < REBIND_DELAY_MS) return;
        lastBindAt = now;

        Intent intent = new Intent(ACTION);
        intent.setComponent(new ComponentName(PACKAGE, SERVICE));
        try {
            binding = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!binding) reportError("bind returned false");
        } catch (Exception e) {
            binding = false;
            reportError("bind failed " + e.getClass().getSimpleName());
        }
    }

    private void reportError(String message) {
        if (TextUtils.equals(message, lastError)) return;
        lastError = message;
        AppLog.line(app, "Media service: " + message);
    }

    static final class NowPlaying {
        final int playStatus;
        final int playtimeMs;
        final int durationMs;
        final String title;
        final String artist;
        final String album;
        final String path;

        private NowPlaying(int playStatus, int playtimeMs, int durationMs,
                           String title, String artist, String album, String path) {
            this.playStatus = playStatus;
            this.playtimeMs = playtimeMs;
            this.durationMs = durationMs;
            this.title = clean(title);
            this.artist = clean(artist);
            this.album = clean(album);
            this.path = clean(path);
        }

        static NowPlaying read(Parcel parcel) {
            parcel.readInt(); // fileType
            parcel.readInt(); // list_id
            parcel.readInt(); // listType
            parcel.readInt(); // playIndex
            parcel.readInt(); // playCount
            int playStatus = parcel.readInt();
            parcel.readInt(); // playSpeed
            parcel.readInt(); // repeatMode
            parcel.readInt(); // shuffleMode
            int playtimeMs = parcel.readInt();
            int durationMs = parcel.readInt();
            parcel.readLong(); // file_id
            parcel.readLong(); // artist_id
            parcel.readLong(); // album_id
            parcel.readInt(); // parent_id
            parcel.readInt(); // storage_id
            parcel.readInt(); // ability
            parcel.readInt(); // lyricsOffsetMs
            parcel.readInt(); // select_parent
            parcel.readLong(); // select_artist
            parcel.readLong(); // select_album
            String title = parcel.readString();
            String artist = parcel.readString();
            String album = parcel.readString();
            parcel.readString(); // albumArt
            String path = parcel.readString();
            parcel.readInt(); // audioSessionID
            parcel.readInt(); // mediaType
            parcel.readInt(); // deviceMask
            return new NowPlaying(playStatus, playtimeMs, durationMs, title, artist, album, path);
        }

        boolean hasText() {
            return !TextUtils.isEmpty(title) || !TextUtils.isEmpty(artist);
        }

        boolean isPlaying() {
            return playStatus == MEDIA_PLAYER_STATUS_PLAY;
        }

        long remainingMs() {
            if (playtimeMs < 0 || durationMs <= 0) return -1L;
            return Math.max(0L, (long) durationMs - (long) playtimeMs);
        }

        private static String clean(String value) {
            if (value == null) return "";
            return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        }
    }
}
