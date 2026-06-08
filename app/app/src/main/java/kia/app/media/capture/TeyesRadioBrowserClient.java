package kia.app.media.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import kia.app.core.AppLog;

final class TeyesRadioBrowserClient {
    private static final String PACKAGE = "com.spd.home";
    private static final String ACTION = "com.spd.teyes.radio.browser";
    private static final String SERVICE = "com.spd.teyes.TeyesNetFmService";
    private static final String DESCRIPTOR = "com.spd.teyes.ITEyesNetFmBroswerService";
    private static final int TRANSACTION_FM_LOCAL_GET_LIST = 5;
    private static final long REBIND_DELAY_MS = 5000L;
    private static final long READ_DELAY_MS = 5000L;

    private static TeyesRadioBrowserClient instance;

    private final Context app;
    private IBinder binder;
    private boolean binding;
    private boolean bound;
    private long lastBindAt;
    private long lastReadAt;
    private String lastError = "";
    private String lastListKey = "";
    private String lastMatchKey = "";
    private List<Station> stations = new ArrayList<>();

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            binder = null;
            bound = false;
            binding = false;
            AppLog.line(app, "TEYES local radio: binder died");
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
            AppLog.line(app, "TEYES local radio: connected " + name.flattenToShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            bound = false;
            binding = false;
            AppLog.line(app, "TEYES local radio: disconnected " + name.flattenToShortString());
        }
    };

    static synchronized TeyesRadioBrowserClient get(Context context) {
        if (instance == null) instance = new TeyesRadioBrowserClient(context);
        return instance;
    }

    private TeyesRadioBrowserClient(Context context) {
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

    String stationName(String source, String frequencyText) {
        if (!"FM".equals(source)) return "";
        int target = parseFrequency(frequencyText);
        if (target <= 0) return "";
        List<Station> snapshot = readStations();
        for (Station station : snapshot) {
            if (station.frequencyKhz() == target && !TextUtils.isEmpty(station.name)) {
                reportMatch(frequencyText, station.name);
                return station.name;
            }
        }
        reportMatch(frequencyText, "");
        return "";
    }

    private List<Station> readStations() {
        ensureBound();
        long now = System.currentTimeMillis();
        IBinder remote = binder;
        if (remote == null || !remote.isBinderAlive()) return stations;
        if (!stations.isEmpty() && now - lastReadAt < READ_DELAY_MS) return stations;
        lastReadAt = now;

        List<Station> fresh = readLocalList(remote);
        if (!fresh.isEmpty()) {
            stations = fresh;
            reportList(fresh);
        }
        return stations;
    }

    private List<Station> readLocalList(IBinder remote) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(0); // FmLocalGetList(false): local TEYES cache, same path used by the widget.
            if (!remote.transact(TRANSACTION_FM_LOCAL_GET_LIST, data, reply, 0)) return stations;
            reply.readException();
            int size = reply.readInt();
            if (size <= 0 || size > 256) return new ArrayList<>();
            List<Station> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                if (reply.readInt() == 0) continue;
                Station station = Station.read(reply);
                if (station.frequencyKhz() > 0) out.add(station);
            }
            return out;
        } catch (Exception e) {
            reportError("read list failed " + e.getClass().getSimpleName());
            return stations;
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
        AppLog.line(app, "TEYES local radio: " + message);
    }

    private void reportList(List<Station> list) {
        String key = String.valueOf(list.size());
        if (TextUtils.equals(key, lastListKey)) return;
        lastListKey = key;
        AppLog.line(app, "TEYES local radio: local station list " + list.size());
    }

    private void reportMatch(String frequencyText, String stationName) {
        String key = frequencyText + "|" + stationName;
        if (TextUtils.equals(key, lastMatchKey)) return;
        lastMatchKey = key;
        AppLog.line(app, "TEYES local radio: " + clean(frequencyText) + " -> "
                + (TextUtils.isEmpty(stationName) ? "-" : stationName));
    }

    private static void skipBaseInfo(Parcel parcel) {
        parcel.readHashMap(HashMap.class.getClassLoader());
        parcel.readHashMap(HashMap.class.getClassLoader());
    }

    private static int parseFrequency(String value) {
        String clean = clean(value).replace(',', '.');
        if (TextUtils.isEmpty(clean)) return -1;
        try {
            if (clean.contains(".")) {
                return (int) Math.round(Double.parseDouble(clean) * 1000.0);
            }
            String digits = clean.replaceAll("[^0-9]", "");
            if (TextUtils.isEmpty(digits)) return -1;
            int raw = Integer.parseInt(digits);
            if (raw >= 65000 && raw <= 108000) return raw;
            if (raw >= 6500 && raw <= 10800) return raw * 10;
            if (raw >= 650 && raw <= 1080) return raw * 100;
            if (raw >= 65 && raw <= 108) return raw * 1000;
            return raw;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String out = value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if ("null".equalsIgnoreCase(out) || "[]".equals(out)) return "";
        return out;
    }

    private static final class Station {
        final String logo;
        final int id;
        final String name;
        final String rate;
        final String city;
        final String cityword;

        private Station(String logo, int id, String name, String rate, String city, String cityword) {
            this.logo = clean(logo);
            this.id = id;
            this.name = clean(name);
            this.rate = clean(rate);
            this.city = clean(city);
            this.cityword = clean(cityword);
        }

        static Station read(Parcel parcel) {
            skipBaseInfo(parcel);
            String logo = parcel.readString();
            int id = parcel.readInt();
            String name = parcel.readString();
            String rate = parcel.readString();
            String city = parcel.readString();
            String cityword = parcel.readString();
            return new Station(logo, id, name, rate, city, cityword);
        }

        int frequencyKhz() {
            return parseFrequency(rate);
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s %s", rate, name);
        }
    }
}
