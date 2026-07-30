package kia.app.navigation.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

import kia.app.core.AppLog;
import kia.app.core.settings.AppSettings;
import kia.app.navigation.domain.NavigationFeature;

public final class DgisTripClient implements ServiceConnection {
    private static final String PACKAGE = "ru.dublgis.dgismobile";
    private static final String PACKAGE_PREVIEW = "ru.dublgis.dgismobile4preview";
    private static final String SERVICE_CLASS = "ru.dublgis.car.NavigationService";
    private static final String SERVICE_DESCRIPTOR = "ru.dublgis.car.INavigationService";
    private static final String CALLBACK_DESCRIPTOR = "ru.dublgis.car.ICarServiceCallback";
    private static final int TRANSACTION_REGISTER_CALLBACK = 1;
    private static final int TRANSACTION_GET_IS_NAVIGATING = 9;
    private static final int CALLBACK_SCREEN_PUSH = 1;
    private static final int CALLBACK_SCREEN_INVALIDATE = 2;
    private static final int CALLBACK_START_NAVIGATION = 7;
    private static final int CALLBACK_STOP_NAVIGATION = 8;
    private static final int CALLBACK_GET_DENSITY = 17;
    private static final int CALLBACK_UPDATE_TRIP = 16;
    private static final int INTERFACE_TRANSACTION = 0x5f4e5446;
    private static final long RECONNECT_MS = 5000L;
    private static final long QUIET_LOG_MS = 15000L;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TripCallback callback = new TripCallback();
    private IBinder remote;
    private boolean running;
    private boolean bound;
    private String boundPackage = "";
    private String lastTrip = "";
    private long lastQuietLogAt;

    public DgisTripClient(Context context) {
        this.app = context.getApplicationContext();
    }

    public void start() {
        if (running) return;
        running = true;
        bind();
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            try {
                app.unbindService(this);
            } catch (Exception ignored) {
            }
        }
        bound = false;
        remote = null;
        boundPackage = "";
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        remote = service;
        boundPackage = name == null ? "" : name.getPackageName();
        AppLog.line(app, "2GIS trip: connected " + boundPackage);
        registerCallback();
        boolean navigating = transactBoolean(TRANSACTION_GET_IS_NAVIGATING, false);
        AppLog.line(app, "2GIS trip: navigating=" + navigating);
        if (!navigating) NavigationFeature.get(app).handleDgisStopped("2gis_car_trip_connected_idle");
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        remote = null;
        bound = false;
        AppLog.line(app, "2GIS trip: disconnected");
        if (running) handler.postDelayed(this::bind, RECONNECT_MS);
    }

    private void bind() {
        if (!running || bound) return;
        if (!sourceEnabled()) {
            logQuiet("2GIS trip: disabled by source mode " + AppSettings.navSourceLabel(app));
            handler.postDelayed(this::bind, RECONNECT_MS);
            return;
        }
        String pkg = installedPackage();
        if (TextUtils.isEmpty(pkg)) {
            logQuiet("2GIS trip: package not installed");
            handler.postDelayed(this::bind, RECONNECT_MS);
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(pkg, SERVICE_CLASS));
        try {
            bound = app.bindService(intent, this, Context.BIND_AUTO_CREATE);
            if (!bound) {
                logQuiet("2GIS trip: bind returned false for " + pkg);
                handler.postDelayed(this::bind, RECONNECT_MS);
            }
        } catch (Exception e) {
            bound = false;
            logQuiet("2GIS trip: bind failed " + e.getClass().getSimpleName());
            handler.postDelayed(this::bind, RECONNECT_MS);
        }
    }

    private boolean sourceEnabled() {
        return NavigationSourceGate.dgisEnabled(
                AppSettings.navigationEnabled(app),
                AppSettings.dgisNavigationEnabled(app));
    }

    private String installedPackage() {
        PackageManager pm = app.getPackageManager();
        if (isInstalled(pm, PACKAGE)) return PACKAGE;
        if (isInstalled(pm, PACKAGE_PREVIEW)) return PACKAGE_PREVIEW;
        return "";
    }

    private static boolean isInstalled(PackageManager pm, String packageName) {
        try {
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void registerCallback() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (remote != null && remote.transact(TRANSACTION_REGISTER_CALLBACK, data, reply, 0)) {
                reply.readException();
                AppLog.line(app, "2GIS trip: callback registered");
            }
        } catch (Exception e) {
            AppLog.line(app, "2GIS trip register failed " + e.getClass().getSimpleName());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean transactBoolean(int code, boolean fallback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            if (remote == null || !remote.transact(code, data, reply, 0)) return fallback;
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) {
            logQuiet("2GIS trip transact " + code + " failed " + e.getClass().getSimpleName());
            return fallback;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void handleTrip(String json) {
        String text = clean(json);
        if (TextUtils.isEmpty(text) || text.equals(lastTrip)) return;
        lastTrip = text;
        try {
            JSONObject root = new JSONObject(text);
            JSONObject state = root.optJSONObject("State");
            boolean started = root.optBoolean("NavigationStarted", false);
            String currentRoad = root.optString("CurrentRoad", "");
            String maneuverRoad = state == null ? "" : state.optString("ManeuverRoad", "");
            String destinationName = root.optString("DestinationName", "");
            String destinationAddress = root.optString("DestinationAddress", "");
            double[] destinationPoint = destinationPoint(root);
            if (!started && TextUtils.isEmpty(clean(currentRoad))
                    && TextUtils.isEmpty(clean(maneuverRoad))
                    && TextUtils.isEmpty(clean(destinationName))
                    && TextUtils.isEmpty(clean(destinationAddress))) {
                logQuiet("2GIS trip: ignored non-trip payload " + shortText(text));
                return;
            }
            NavigationFeature.get(app).handleDgisTrip(currentRoad, maneuverRoad,
                    destinationName, destinationAddress, started,
                    destinationPoint == null ? Double.NaN : destinationPoint[0],
                    destinationPoint == null ? Double.NaN : destinationPoint[1], text);
            AppLog.line(app, "2GIS trip: started=" + started
                    + " current=" + clean(currentRoad)
                    + " next=" + clean(maneuverRoad)
                    + " dest=" + clean(first(destinationAddress, destinationName)));
        } catch (Exception e) {
            AppLog.line(app, "2GIS trip JSON failed: " + e.getClass().getSimpleName()
                    + " " + shortText(text));
        }
    }

    private void logQuiet(String value) {
        long now = System.currentTimeMillis();
        if (now - lastQuietLogAt < QUIET_LOG_MS) return;
        lastQuietLogAt = now;
        AppLog.line(app, value);
    }

    private static String first(String... values) {
        for (String value : values) {
            String text = clean(value);
            if (!TextUtils.isEmpty(text)) return text;
        }
        return "";
    }

    private static String shortText(String value) {
        String text = clean(value);
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    private static double[] destinationPoint(JSONObject root) {
        Point point = new Point();
        scanDestination(root, "", point);
        return point.valid() ? new double[]{point.lat, point.lon} : null;
    }

    private static void scanDestination(Object value, String path, Point out) {
        if (value == null || out.valid()) return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext() && !out.valid()) {
                String key = keys.next();
                scanDestination(object.opt(key), path + " " + key, out);
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && !out.valid(); i++) {
                scanDestination(array.opt(i), path, out);
            }
            return;
        }
        String lower = clean(path).toLowerCase(Locale.US);
        if (!(lower.contains("destination") || lower.contains("finish")
                || lower.contains("target") || lower.contains("end"))) return;
        double number = doubleValue(value);
        if (Double.isNaN(number)) return;
        if (lower.contains("lat")) out.lat = number;
        if (lower.contains("lon") || lower.contains("lng")) out.lon = number;
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(clean(String.valueOf(value)).replace(',', '.'));
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private static final class Point {
        double lat = Double.NaN;
        double lon = Double.NaN;

        boolean valid() {
            return !Double.isNaN(lat) && !Double.isNaN(lon)
                    && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d
                    && !(lat == 0d && lon == 0d);
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private final class TripCallback extends Binder implements IInterface {
        TripCallback() {
            attachInterface(this, CALLBACK_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                    return true;
                }
                if (data != null) data.enforceInterface(CALLBACK_DESCRIPTOR);
                switch (code) {
                    case CALLBACK_UPDATE_TRIP:
                        handleTrip(data == null ? "" : data.readString());
                        writeNoException(reply);
                        return true;
                    case CALLBACK_GET_DENSITY:
                        writeNoException(reply);
                        if (reply != null) reply.writeFloat(app.getResources().getDisplayMetrics().density);
                        return true;
                    case CALLBACK_START_NAVIGATION:
                        AppLog.line(app, "2GIS trip: startNavigation");
                        writeNoException(reply);
                        return true;
                    case CALLBACK_STOP_NAVIGATION:
                        AppLog.line(app, "2GIS trip: stopNavigation");
                        lastTrip = "";
                        NavigationFeature.get(app).handleDgisStopped("2gis_car_trip_stop_callback");
                        writeNoException(reply);
                        return true;
                    case CALLBACK_SCREEN_PUSH:
                    case CALLBACK_SCREEN_INVALIDATE:
                        writeNoException(reply);
                        return true;
                    default:
                        writeNoException(reply);
                        return true;
                }
            } catch (Exception e) {
                AppLog.line(app, "2GIS trip callback failed " + code + " "
                        + e.getClass().getSimpleName());
                writeNoException(reply);
                return true;
            }
        }

        private void writeNoException(Parcel reply) {
            if (reply != null) reply.writeNoException();
        }
    }
}
