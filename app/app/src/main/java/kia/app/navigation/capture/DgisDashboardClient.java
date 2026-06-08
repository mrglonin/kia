package kia.app.navigation.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
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

public final class DgisDashboardClient implements ServiceConnection {
    private static final String ACTION = "ru.dublgis.api.ACTION_BIND_DASHBOARD_INFORMATION_SERVICE";
    private static final String PACKAGE = "ru.dublgis.dgismobile";
    private static final String PACKAGE_PREVIEW = "ru.dublgis.dgismobile4preview";
    private static final String DESCRIPTOR = "ru.dublgis.api.IDashboardInformationService";
    private static final int TRANSACTION_GET_JSON = 1;
    private static final int TRANSACTION_GET_API_VERSION = 4;
    private static final int TRANSACTION_SET_CLIENT = 5;
    private static final int TRANSACTION_IS_MAP_ACTIVE = 6;
    private static final long POLL_MS = 1000L;
    private static final long QUIET_LOG_MS = 15000L;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private IBinder remote;
    private String boundPackage = "";
    private String lastJson = "";
    private long lastQuietLogAt;
    private boolean running;
    private boolean bound;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            readDashboard();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public DgisDashboardClient(Context context) {
        this.app = context.getApplicationContext();
    }

    public void start() {
        if (running) return;
        running = true;
        bind();
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(poll);
        if (bound) {
            try {
                app.unbindService(this);
            } catch (Exception ignored) {
            }
        }
        bound = false;
        remote = null;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        remote = service;
        boundPackage = name == null ? "" : name.getPackageName();
        AppLog.line(app, "2GIS dashboard: connected " + boundPackage
                + " api=" + transactInt(TRANSACTION_GET_API_VERSION, 0)
                + " mapActive=" + transactBoolean(TRANSACTION_IS_MAP_ACTIVE, false));
        transactSetClient("kia.app");
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        remote = null;
        AppLog.line(app, "2GIS dashboard: disconnected");
        if (running) {
            bound = false;
            handler.removeCallbacks(poll);
            handler.postDelayed(this::bind, 2000L);
        }
    }

    private void bind() {
        if (!running || bound) return;
        String pkg = installedPackage();
        if (TextUtils.isEmpty(pkg)) {
            logQuiet("2GIS dashboard: package not installed");
            handler.postDelayed(this::bind, 5000L);
            return;
        }
        Intent intent = new Intent(ACTION);
        intent.setPackage(pkg);
        try {
            bound = app.bindService(intent, this, Context.BIND_AUTO_CREATE);
            if (!bound) {
                logQuiet("2GIS dashboard: bind returned false for " + pkg);
                handler.postDelayed(this::bind, 5000L);
            }
        } catch (Exception e) {
            bound = false;
            logQuiet("2GIS dashboard: bind failed " + e.getClass().getSimpleName());
            handler.postDelayed(this::bind, 5000L);
        }
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

    private void readDashboard() {
        if (remote == null || !remote.isBinderAlive()) {
            remote = null;
            bound = false;
            bind();
            return;
        }
        if (!AppSettings.dgisNavigationEnabled(app)) {
            lastJson = "";
            return;
        }
        String json = transactString(TRANSACTION_GET_JSON);
        if (json == null) return;
        json = json.trim();
        if (json.equals(lastJson)) return;
        lastJson = json;
        handleJson(json);
    }

    private void handleJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            String mode = obj.optString("activeNavigationMode", "");
            String maneuverDistance = obj.optString("maneuverDistance", "");
            String maneuverIcon = obj.optString("maneuverIcon", "");
            String maneuverDescription = obj.optString("maneuverDescription", "");
            String totalDistance = obj.optString("totalDistance", "");
            String remainingTime = obj.optString("remainingTime", "");
            String arrivalTime = obj.optString("arrivalTime", "");
            double speedLimit = obj.optDouble("speedLimit", 0d);
            boolean exceeded = obj.optBoolean("exceedingMaxSpeedLimit", false);
            double[] destinationPoint = destinationPoint(obj);
            NavigationFeature.get(app).handleDgisDashboard(mode, maneuverIcon, maneuverDescription,
                    maneuverDistance, totalDistance, remainingTime, arrivalTime,
                    dashboardSpeed(speedLimit), exceeded,
                    destinationPoint == null ? Double.NaN : destinationPoint[0],
                    destinationPoint == null ? Double.NaN : destinationPoint[1], json);
            logDashboard(mode, maneuverIcon, maneuverDescription, maneuverDistance,
                    totalDistance, remainingTime, arrivalTime, speedLimit);
        } catch (Exception e) {
            AppLog.line(app, "2GIS dashboard JSON failed: " + e.getClass().getSimpleName()
                    + " " + shortText(json));
        }
    }

    private void logDashboard(String mode, String icon, String description, String maneuverDistance,
                              String totalDistance, String remainingTime, String arrivalTime,
                              double speedLimit) {
        String text = "2GIS dashboard: mode=" + clean(mode)
                + " icon=" + clean(icon)
                + " desc=" + clean(description)
                + " manDist=" + clean(maneuverDistance)
                + " total=" + clean(totalDistance)
                + " time=" + clean(remainingTime)
                + " arrival=" + clean(arrivalTime)
                + " speedLimit=" + speedLimit;
        if (hasUsefulDashboardData(mode, icon, description, maneuverDistance, totalDistance,
                remainingTime, arrivalTime, speedLimit)) {
            AppLog.line(app, text);
        } else {
            logQuiet(text);
        }
    }

    private static boolean hasUsefulDashboardData(String mode, String icon, String description,
                                                  String maneuverDistance, String totalDistance,
                                                  String remainingTime, String arrivalTime,
                                                  double speedLimit) {
        return !TextUtils.isEmpty(clean(mode))
                || !TextUtils.isEmpty(clean(icon))
                || !TextUtils.isEmpty(clean(description))
                || !TextUtils.isEmpty(clean(maneuverDistance))
                || !TextUtils.isEmpty(clean(totalDistance))
                || !TextUtils.isEmpty(clean(remainingTime))
                || !TextUtils.isEmpty(clean(arrivalTime))
                || speedLimit > 0d;
    }

    private String transactString(int code) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(code, data, reply, 0)) return null;
            reply.readException();
            return reply.readString();
        } catch (Exception e) {
            logQuiet("2GIS dashboard transact " + code + " failed " + e.getClass().getSimpleName());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
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

    private int transactInt(int code, int fallback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(code, data, reply, 0)) return fallback;
            reply.readException();
            return reply.readInt();
        } catch (Exception ignored) {
            return fallback;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean transactBoolean(int code, boolean fallback) {
        return transactInt(code, fallback ? 1 : 0) != 0;
    }

    private void transactSetClient(String client) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(client);
            if (remote.transact(TRANSACTION_SET_CLIENT, data, reply, 0)) {
                reply.readException();
                AppLog.line(app, "2GIS dashboard: setClient=" + (reply.readInt() != 0));
            }
        } catch (Exception e) {
            AppLog.line(app, "2GIS dashboard setClient failed " + e.getClass().getSimpleName());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void logQuiet(String value) {
        long now = System.currentTimeMillis();
        if (now - lastQuietLogAt < QUIET_LOG_MS) return;
        lastQuietLogAt = now;
        AppLog.line(app, value);
    }

    private static String dashboardSpeed(double value) {
        if (value <= 0d || Double.isNaN(value) || Double.isInfinite(value)) return "";
        double rounded = Math.rint(value);
        double kmh = value < 30d && Math.abs(value - rounded) > 0.05d ? value * 3.6d : value;
        if (kmh <= 0d || kmh > 160d) return "";
        return String.valueOf(Math.round(kmh));
    }

    private static String shortText(String value) {
        String text = clean(value);
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }
}
