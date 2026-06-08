package kia.app.media.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.HashMap;

import kia.app.core.AppLog;

final class TeyesOnlineRadioClient {
    static final String PACKAGE = "com.spd.home";

    private static final String ACTION = "com.spd.teyes.radio.player";
    private static final String SERVICE = "com.spd.teyes.TeyesNetFmService";
    private static final String DESCRIPTOR = "com.spd.teyes.ITeyesNetFmService";
    private static final int TRANSACTION_GET_TRACK_INFO = 3;
    private static final int TRANSACTION_GET_RANK_INFO = 4;
    private static final int TRANSACTION_GET_PLAY_STATE = 5;
    private static final int PLAY_STATE_PLAYING = 1;
    private static final int PLAY_STATE_LOADING = -3;
    private static final int PLAY_STATE_BUFFERING = -2;
    private static final int PLAY_STATE_ERROR = -4;
    private static final long REBIND_DELAY_MS = 5000L;

    private static TeyesOnlineRadioClient instance;

    private final Context app;
    private IBinder binder;
    private boolean binding;
    private boolean bound;
    private long lastBindAt;
    private String lastError = "";
    private String lastInfoKey = "";

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            binder = null;
            bound = false;
            binding = false;
            AppLog.line(app, "TEYES online radio: binder died");
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
            AppLog.line(app, "TEYES online radio: connected " + name.flattenToShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            bound = false;
            binding = false;
            AppLog.line(app, "TEYES online radio: disconnected " + name.flattenToShortString());
        }
    };

    static synchronized TeyesOnlineRadioClient get(Context context) {
        if (instance == null) instance = new TeyesOnlineRadioClient(context);
        return instance;
    }

    private TeyesOnlineRadioClient(Context context) {
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

    Snapshot readSnapshot() {
        ensureBound();
        IBinder remote = binder;
        if (remote == null || !remote.isBinderAlive()) return null;

        int playState = readPlayState(remote);
        OnlineInfo track = readTrackInfo(remote);
        OnlineInfo rank = readRankInfo(remote);
        OnlineInfo info = track != null && track.hasText() ? track : rank;
        if (info == null || !info.hasText()) return null;

        Snapshot snapshot = new Snapshot(playState, info.artist, info.title);
        reportInfo(snapshot);
        return snapshot;
    }

    private int readPlayState(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_PLAY_STATE, data, reply, 0)) return Integer.MIN_VALUE;
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            reportError("read state failed " + e.getClass().getSimpleName());
            return Integer.MIN_VALUE;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private OnlineInfo readTrackInfo(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_TRACK_INFO, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            return OnlineInfo.readTrack(reply);
        } catch (Exception e) {
            reportError("read track failed " + e.getClass().getSimpleName());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private OnlineInfo readRankInfo(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_RANK_INFO, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            return OnlineInfo.readRank(reply);
        } catch (Exception e) {
            reportError("read rank failed " + e.getClass().getSimpleName());
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
        AppLog.line(app, "TEYES online radio: " + message);
    }

    private void reportInfo(Snapshot snapshot) {
        String key = snapshot.playState + "|" + snapshot.artist + "|" + snapshot.title;
        if (TextUtils.equals(key, lastInfoKey)) return;
        lastInfoKey = key;
        AppLog.line(app, "TEYES online radio: state=" + snapshot.playState + " | "
                + (TextUtils.isEmpty(snapshot.artist) ? "-" : snapshot.artist)
                + " | " + snapshot.title);
    }

    static final class Snapshot {
        private final int playState;
        private final String artist;
        private final String title;

        private Snapshot(int playState, String artist, String title) {
            this.playState = playState;
            this.artist = clean(artist);
            this.title = clean(title);
        }

        boolean isCurrent() {
            return hasText() && (isPlaying()
                    || playState == PLAY_STATE_LOADING
                    || playState == PLAY_STATE_BUFFERING
                    || playState == PLAY_STATE_ERROR);
        }

        boolean isPlaying() {
            return playState == PLAY_STATE_PLAYING;
        }

        String source() {
            return "TEYES";
        }

        String artist() {
            return artist;
        }

        String title() {
            return title;
        }

        private boolean hasText() {
            return !TextUtils.isEmpty(title) || !TextUtils.isEmpty(artist);
        }
    }

    private static final class OnlineInfo {
        final String artist;
        final String title;

        private OnlineInfo(String artist, String title) {
            this.artist = clean(artist);
            this.title = clean(title);
        }

        boolean hasText() {
            return !TextUtils.isEmpty(title) || !TextUtils.isEmpty(artist);
        }

        static OnlineInfo readTrack(Parcel parcel) {
            skipBaseInfo(parcel);
            if (parcel.readInt() == 1) skipTrackParent(parcel);
            String logo = parcel.readString();
            parcel.readInt(); // id
            String name = parcel.readString();
            String bit = parcel.readString();
            parcel.readInt(); // favorite
            parcel.readByte(); // isfavorite
            parcel.readInt(); // playcount
            parcel.readInt(); // ranking
            parcel.readString(); // url
            String country = parcel.readString();
            return new OnlineInfo(firstNonEmpty(country, bit, logo), name);
        }

        static OnlineInfo readRank(Parcel parcel) {
            skipBaseInfo(parcel);
            if (parcel.readInt() == 1) skipRankParent(parcel);
            parcel.readInt(); // id
            String title = parcel.readString();
            parcel.readString(); // cover
            parcel.readLong(); // createTime
            parcel.readLong(); // updateTime
            parcel.readInt(); // rid
            parcel.readLong(); // rdate
            parcel.readString(); // locurl
            String status = parcel.readString();
            parcel.readInt(); // weigh
            parcel.readInt(); // fav
            parcel.readInt(); // play
            parcel.readInt(); // vstatus
            parcel.readString(); // localurl
            parcel.readString(); // url
            parcel.readInt(); // vgg
            parcel.readInt(); // cid
            String category = parcel.readString();
            parcel.readInt(); // yq
            parcel.readInt(); // feedback
            parcel.readByte(); // isfavorite
            parcel.readInt(); // ranking
            return new OnlineInfo(firstNonEmpty(category, status), title);
        }
    }

    private static void skipTrackParent(Parcel parcel) {
        skipBaseInfo(parcel);
        parcel.readBoolean(); // favourite flag
        if (parcel.readInt() == 1) skipCategoryInfo(parcel);
        if (parcel.readInt() == 1) skipCategoryInfo(parcel);
        if (parcel.readInt() == 1) skipCityInfo(parcel);
    }

    private static void skipRankParent(Parcel parcel) {
        skipBaseInfo(parcel);
        parcel.readBoolean(); // favourite flag
        if (parcel.readInt() == 1) skipRankCategoryInfo(parcel);
    }

    private static void skipCategoryInfo(Parcel parcel) {
        skipBaseInfo(parcel);
        parcel.readInt(); // id
        parcel.readString(); // name
        parcel.readString(); // img
        parcel.readString(); // bg
        parcel.readString(); // bg2k
        parcel.readString(); // img_c4
        parcel.readString(); // img_cc4white
        parcel.readInt(); // TopID
        parcel.readString(); // TopName
        parcel.readInt(); // TopPlaycount
        parcel.readString(); // TopUrl
        parcel.readString(); // country_code
        parcel.readInt(); // pid
        parcel.readInt(); // iscity
        parcel.readBoolean(); // has child
    }

    private static void skipCityInfo(Parcel parcel) {
        skipBaseInfo(parcel);
        parcel.readInt(); // id
        parcel.readString(); // name
        parcel.readString(); // img
        parcel.readInt(); // pid
        parcel.readString(); // status
        parcel.readInt(); // weigh
        parcel.readString(); // bg
        parcel.readString(); // type
        parcel.readString(); // bg2k
        parcel.readString(); // img2k
        parcel.readString(); // imgc4
        parcel.readString(); // bgc4
        parcel.readString(); // country_code
        parcel.readString(); // active
    }

    private static void skipRankCategoryInfo(Parcel parcel) {
        skipBaseInfo(parcel);
        parcel.readInt(); // id
        parcel.readLong(); // createTime
        parcel.readLong(); // updateTime
        parcel.readString(); // title
        parcel.readString(); // cover
        parcel.readString(); // status
        parcel.readInt(); // weigh
        parcel.readString(); // logo
        parcel.readString(); // cover2k
        parcel.readString(); // logo2k
        parcel.readString(); // logoc4
        parcel.readString(); // logoc4white
    }

    private static void skipBaseInfo(Parcel parcel) {
        parcel.readHashMap(HashMap.class.getClassLoader());
        parcel.readHashMap(HashMap.class.getClassLoader());
    }

    private static String firstNonEmpty(String... values) {
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
}
