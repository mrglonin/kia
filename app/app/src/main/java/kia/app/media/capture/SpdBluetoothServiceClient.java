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

final class SpdBluetoothServiceClient {
    static final String PACKAGE = "com.spd.bluetooth";

    private static final String ACTION = "com.spd.bluetooth.service.BluetoothService";
    private static final String SERVICE = "com.spd.bluetooth.service.BluetoothService";
    private static final String DESCRIPTOR = "com.spd.bluetooth.aidl.IBluetoothService";
    private static final int TRANSACTION_GET_MUSIC_INFO = 39;
    private static final int TRANSACTION_GET_PLAYBACK_STATUS = 40;
    private static final int TRANSACTION_GET_DIAL_STATUS = 42;
    private static final int TRANSACTION_GET_DIAL_INFO = 43;
    private static final int PLAYBACK_STATE_PLAYING = 3;
    private static final long REBIND_DELAY_MS = 5000L;

    private static SpdBluetoothServiceClient instance;

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
            AppLog.line(app, "BT service: binder died");
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
            AppLog.line(app, "BT service: connected " + name.flattenToShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            bound = false;
            binding = false;
            AppLog.line(app, "BT service: disconnected " + name.flattenToShortString());
        }
    };

    static synchronized SpdBluetoothServiceClient get(Context context) {
        if (instance == null) instance = new SpdBluetoothServiceClient(context);
        return instance;
    }

    private SpdBluetoothServiceClient(Context context) {
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

    Snapshot readSnapshot(long playPositionMs) {
        ensureBound();
        IBinder remote = binder;
        if (remote == null || !remote.isBinderAlive()) return null;

        Integer playbackStatus = readPlaybackStatus(remote);
        MusicInfo info = readMusicInfo(remote);
        if (playbackStatus == null && info == null) return null;
        int status = playbackStatus == null ? -1 : playbackStatus;
        return new Snapshot(status, playPositionMs, info);
    }

    CallSnapshot readCallSnapshot() {
        ensureBound();
        IBinder remote = binder;
        if (remote == null || !remote.isBinderAlive()) return null;

        Integer status = readDialStatus(remote);
        CallLogInfo[] calls = readDialInfo(remote);
        if (status == null && (calls == null || calls.length == 0)) return null;
        CallLogInfo call = firstCall(calls);
        return new CallSnapshot(status == null ? -1 : status, call);
    }

    private Integer readPlaybackStatus(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_PLAYBACK_STATUS, data, reply, 0)) return null;
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            reportError("read status failed " + e.getClass().getSimpleName());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private MusicInfo readMusicInfo(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_MUSIC_INFO, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            return MusicInfo.read(reply);
        } catch (Exception e) {
            reportError("read info failed " + e.getClass().getSimpleName());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private Integer readDialStatus(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_DIAL_STATUS, data, reply, 0)) return null;
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            reportError("read dial status failed " + e.getClass().getSimpleName());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private CallLogInfo[] readDialInfo(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_DIAL_INFO, data, reply, 0)) return null;
            reply.readException();
            int count = reply.readInt();
            if (count < 0) return null;
            CallLogInfo[] calls = new CallLogInfo[count];
            for (int i = 0; i < count; i++) {
                if (reply.readInt() == 0) continue;
                calls[i] = CallLogInfo.read(reply);
            }
            return calls;
        } catch (Exception e) {
            reportError("read dial info failed " + e.getClass().getSimpleName());
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
        AppLog.line(app, "BT service: " + message);
    }

    static final class Snapshot {
        final int playbackStatus;
        final long playPositionMs;
        final String title;
        final String artist;
        final String album;
        final String duration;

        private Snapshot(int playbackStatus, long playPositionMs, MusicInfo info) {
            this.playbackStatus = playbackStatus;
            this.playPositionMs = playPositionMs;
            this.title = info == null ? "" : info.title;
            this.artist = info == null ? "" : info.artist;
            this.album = info == null ? "" : info.album;
            this.duration = info == null ? "" : info.duration;
        }

        boolean isPlaying() {
            return playbackStatus == PLAYBACK_STATE_PLAYING;
        }

        boolean hasText() {
            return !TextUtils.isEmpty(title) || !TextUtils.isEmpty(artist);
        }

        long remainingMs() {
            long durationMs = parseDurationMs(duration);
            if (durationMs <= 0L || playPositionMs < 0L) return -1L;
            return Math.max(0L, durationMs - playPositionMs);
        }
    }

    static final class CallSnapshot {
        final int dialStatus;
        final String name;
        final String number;
        final int state;
        final int type;
        final long contactId;

        private CallSnapshot(int dialStatus, CallLogInfo call) {
            this.dialStatus = dialStatus;
            this.name = call == null ? "" : call.name;
            this.number = call == null ? "" : call.number;
            this.state = call == null ? -1 : call.state;
            this.type = call == null ? -1 : call.type;
            this.contactId = call == null ? -1L : call.contactId;
        }

        boolean active() {
            return dialStatus > 0 || state >= 0 || !TextUtils.isEmpty(number) || !TextUtils.isEmpty(name);
        }

        boolean hasIdentity() {
            return !TextUtils.isEmpty(name) || !TextUtils.isEmpty(number);
        }
    }

    private static CallLogInfo firstCall(CallLogInfo[] calls) {
        if (calls == null) return null;
        for (CallLogInfo call : calls) {
            if (call != null && (!TextUtils.isEmpty(call.number) || !TextUtils.isEmpty(call.name))) {
                return call;
            }
        }
        for (CallLogInfo call : calls) {
            if (call != null) return call;
        }
        return null;
    }

    private static final class CallLogInfo {
        final int id;
        final long contactId;
        final int type;
        final String name;
        final String number;
        final String date;
        final String duration;
        final int state;
        final String local;

        private CallLogInfo(int id, long contactId, int type, String name, String number,
                            String date, String duration, int state, String local) {
            this.id = id;
            this.contactId = contactId;
            this.type = type;
            this.name = clean(name);
            this.number = clean(number);
            this.date = clean(date);
            this.duration = clean(duration);
            this.state = state;
            this.local = clean(local);
        }

        static CallLogInfo read(Parcel parcel) {
            int id = parcel.readInt();
            long contactId = parcel.readLong();
            int type = parcel.readInt();
            String name = parcel.readString();
            String number = parcel.readString();
            String date = parcel.readString();
            String duration = parcel.readString();
            int state = parcel.readInt();
            String local = parcel.readString();
            return new CallLogInfo(id, contactId, type, name, number, date, duration, state, local);
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private static final class MusicInfo {
        final String title;
        final String artist;
        final String album;
        final String duration;
        final String cover;

        private MusicInfo(String title, String artist, String album, String duration, String cover) {
            this.title = clean(title);
            this.artist = clean(artist);
            this.album = clean(album);
            this.duration = clean(duration);
            this.cover = clean(cover);
        }

        static MusicInfo read(Parcel parcel) {
            String title = parcel.readString();
            String artist = parcel.readString();
            String album = parcel.readString();
            String duration = parcel.readString();
            String cover = parcel.readString();
            return new MusicInfo(title, artist, album, duration, cover);
        }

    }

    private static long parseDurationMs(String value) {
        String text = value == null ? "" : value.trim();
        if (TextUtils.isEmpty(text)) return -1L;
        try {
            if (text.indexOf(':') >= 0) {
                String[] parts = text.split(":");
                long seconds = 0L;
                for (String part : parts) {
                    seconds = seconds * 60L + Long.parseLong(part.trim());
                }
                return seconds * 1000L;
            }
            long raw = Long.parseLong(text.replaceAll("[^0-9]", ""));
            if (raw <= 0L) return -1L;
            return raw > 1000L ? raw : raw * 1000L;
        } catch (Exception ignored) {
            return -1L;
        }
    }
}
