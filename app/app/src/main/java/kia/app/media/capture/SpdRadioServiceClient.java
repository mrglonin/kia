package kia.app.media.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.Locale;

import kia.app.core.AppLog;

final class SpdRadioServiceClient {
    static final String PACKAGE = "com.spd.radio";

    private static final String ACTION = "com.spd.radio.service";
    private static final String SERVICE = "com.spd.radio.service.RadioService";
    private static final String DESCRIPTOR = "com.spd.radio.IRadioAidlInterface";
    private static final int TRANSACTION_GET_FREQ_INFO = 5;
    private static final int TRANSACTION_GET_RADIO_RDS_PS = 11;
    private static final int TRANSACTION_GET_RADIO_RDS_RT = 12;
    private static final int TRANSACTION_GET_RADIO_LIST = 18;
    private static final int TRANSACTION_GET_RADIO_STATUS = 13;
    private static final int PLAY_STATE_PLAYING = 1;
    private static final long REBIND_DELAY_MS = 5000L;

    private static SpdRadioServiceClient instance;

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
            AppLog.line(app, "Radio service: binder died");
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
            AppLog.line(app, "Radio service: connected " + name.flattenToShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            bound = false;
            binding = false;
            AppLog.line(app, "Radio service: disconnected " + name.flattenToShortString());
        }
    };

    static synchronized SpdRadioServiceClient get(Context context) {
        if (instance == null) instance = new SpdRadioServiceClient(context);
        return instance;
    }

    private SpdRadioServiceClient(Context context) {
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

        RadioFreqInfo freqInfo = readFreqInfo(remote);
        RadioStatus status = readRadioStatus(remote);
        String listName = readListStationName(remote, freqInfo);
        String rdsPs = readRdsText(remote, TRANSACTION_GET_RADIO_RDS_PS);
        String rdsRt = readRdsText(remote, TRANSACTION_GET_RADIO_RDS_RT);
        if (freqInfo == null && status == null && TextUtils.isEmpty(listName)) {
            return null;
        }
        return new Snapshot(freqInfo, status, listName, rdsPs, rdsRt);
    }

    private RadioFreqInfo readFreqInfo(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_FREQ_INFO, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            return RadioFreqInfo.read(reply);
        } catch (Exception e) {
            reportError("read freq failed " + e.getClass().getSimpleName());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private RadioStatus readRadioStatus(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(TRANSACTION_GET_RADIO_STATUS, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            return RadioStatus.read(reply);
        } catch (Exception e) {
            reportError("read status failed " + e.getClass().getSimpleName());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private String readListStationName(IBinder remote, RadioFreqInfo current) {
        if (current == null || current.freq <= 0) return "";
        String source = sourceFor(current);
        String name = readListStationName(remote, source, current.freq);
        if (!TextUtils.isEmpty(name)) return name;
        if (!"FM".equals(source)) return "";
        return readListStationName(remote, "FM_FAVORITES", current.freq);
    }

    private String readListStationName(IBinder remote, String band, int freq) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(band);
            if (!remote.transact(TRANSACTION_GET_RADIO_LIST, data, reply, 0)) return "";
            reply.readException();
            int size = reply.readInt();
            if (size <= 0 || size > 256) return "";
            for (int i = 0; i < size; i++) {
                if (reply.readInt() == 0) continue;
                RadioFreqInfo item = RadioFreqInfo.read(reply);
                if (item.freq == freq && !TextUtils.isEmpty(item.ps)) return item.ps;
            }
        } catch (Exception e) {
            reportError("read list failed " + e.getClass().getSimpleName());
        } finally {
            reply.recycle();
            data.recycle();
        }
        return "";
    }

    private String readRdsText(IBinder remote, int transaction) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(transaction, data, reply, 0)) return "";
            reply.readException();
            return clean(reply.readString());
        } catch (Exception e) {
            reportError("read rds failed " + e.getClass().getSimpleName());
            return "";
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
        AppLog.line(app, "Radio service: " + message);
    }

    static final class Snapshot {
        private final RadioFreqInfo freqInfo;
        private final RadioStatus status;
        private final String listName;
        private final String rdsPs;
        private final String rdsRt;

        private Snapshot(RadioFreqInfo freqInfo, RadioStatus status, String listName, String rdsPs, String rdsRt) {
            this.freqInfo = freqInfo;
            this.status = status;
            this.listName = clean(listName);
            this.rdsPs = clean(rdsPs);
            this.rdsRt = clean(rdsRt);
        }

        boolean isPlaying() {
            return status != null && status.playState == PLAY_STATE_PLAYING;
        }

        boolean isCurrent() {
            return isPlaying() || (status != null && status.isSearching());
        }

        boolean isSearching() {
            return status != null && status.isSearching();
        }

        boolean hasFrequency() {
            return freqInfo != null && freqInfo.freq > 0;
        }

        String source() {
            return sourceFor(freqInfo);
        }

        String frequencyText() {
            if (freqInfo == null) return "";
            return formatFrequency(source(), freqInfo.freq);
        }

        String stationName(String teyesLocalName) {
            return firstNonEmpty(teyesLocalName, listName, freqInfo == null ? "" : freqInfo.ps, rdsPs, rdsRt);
        }
    }

    private static final class RadioFreqInfo {
        final String band;
        final int freq;
        final String ps;

        private RadioFreqInfo(String band, int freq, String ps) {
            this.band = clean(band);
            this.freq = freq;
            this.ps = clean(ps);
        }

        static RadioFreqInfo read(Parcel parcel) {
            String band = parcel.readString();
            int freq = parcel.readInt();
            parcel.readInt(); // min
            parcel.readInt(); // max
            parcel.readInt(); // step
            parcel.readInt(); // pi
            parcel.readInt(); // signal
            String ps = parcel.readString();
            return new RadioFreqInfo(band, freq, ps);
        }
    }

    private static final class RadioStatus {
        final boolean seeking;
        final boolean previewScanning;
        final boolean autoSearching;
        final int playState;

        private RadioStatus(boolean seeking, boolean previewScanning, boolean autoSearching, int playState) {
            this.seeking = seeking;
            this.previewScanning = previewScanning;
            this.autoSearching = autoSearching;
            this.playState = playState;
        }

        boolean isSearching() {
            return seeking || previewScanning || autoSearching;
        }

        static RadioStatus read(Parcel parcel) {
            parcel.readInt(); // signalLevel
            parcel.readInt(); // stereo
            parcel.readInt(); // rdsTPInfo
            parcel.readInt(); // rdsTAInfo
            boolean seeking = parcel.readInt() != 0;
            boolean previewScanning = parcel.readInt() != 0;
            boolean autoSearching = parcel.readInt() != 0;
            parcel.readInt(); // local
            parcel.readInt(); // rdsPTY
            int playState = parcel.readInt();
            return new RadioStatus(seeking, previewScanning, autoSearching, playState);
        }
    }

    private static String formatFrequency(String source, int freq) {
        if (freq <= 0) return "";
        if ("AM".equals(source)) return String.valueOf(freq);
        double mhz = freq > 20000 ? freq / 1000.0 : freq / 100.0;
        String text = String.format(Locale.US, "%.2f", mhz);
        while (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static String sourceFor(RadioFreqInfo freqInfo) {
        if (freqInfo == null) return "FM";
        String band = clean(freqInfo.band).toUpperCase(Locale.US);
        if (band.contains("AM")) return "AM";
        if (band.contains("FM") || band.contains("OIRT")) return "FM";
        return freqInfo.freq > 30000 ? "FM" : "AM";
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
