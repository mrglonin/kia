package kia.app.navigation.compass;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Surface;
import android.view.WindowManager;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.NavigationState;
import kia.app.core.settings.AppSettings;
import kia.app.navigation.cluster.NavigationClusterSender;
import kia.app.navigation.domain.NavigationFeature;
import kia.app.protocol.adapter.AdapterGateway;

public final class CompassMonitor implements LocationListener, SensorEventListener {
    private static final long MIN_TIME_MS = 500L;
    private static final float MIN_DISTANCE_M = 0f;
    private static final long ANIMATION_STEP_MS = 120L;
    private static final long WATCHDOG_MS = 1000L;
    private static final long REREGISTER_MS = 15000L;
    private static final long STARTUP_NAV_GRACE_MS = 3000L;
    private static final long STALE_LOCATION_MS = 2500L;
    private static final long SENSOR_STALE_MS = 1500L;
    private static final long SENSOR_PUBLISH_MS = 120L;
    private static final String FUSED_PROVIDER = "fused";

    private final Context app;
    private final NavigationClusterSender sender;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor headingSensor;
    private PowerManager.WakeLock wakeLock;
    private final float[] rotationMatrix = new float[9];
    private final float[] displayMatrix = new float[9];
    private final float[] orientation = new float[3];
    private int displayedStep = -1;
    private int targetStep = -1;
    private long lastLocationAt;
    private long lastAnyLocationAt;
    private long lastSensorHeadingAt;
    private long lastSensorPublishAt;
    private long lastRegisterAt;
    private long startedAt;
    private int lastTransmittedStep = -1;
    private float smoothedSensorHeading = Float.NaN;
    private float lastPublishedSensorHeading = Float.NaN;
    private int sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
    private boolean running;
    private boolean registered;
    private boolean sensorRegistered;
    private boolean animationRunning;
    private boolean lastUsbReady;
    private Location lastLocation;

    private final Runnable animate = new Runnable() {
        @Override
        public void run() {
            animationRunning = false;
            if (!running || targetStep < 0) return;
            if (displayedStep < 0) {
                sendStep(targetStep);
                return;
            }
            if (displayedStep == targetStep) return;
            sendStep(nextStep(displayedStep, targetStep));
            if (displayedStep != targetStep) {
                animationRunning = true;
                handler.postDelayed(this, ANIMATION_STEP_MS);
            }
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            if (!locationNeeded()) {
                unregisterLocation();
                releaseWakeLock();
                updateHeadingSensorRegistration();
                handler.postDelayed(this, WATCHDOG_MS);
                return;
            }
            acquireWakeLock();
            updateHeadingSensorRegistration();
            if (!registered || now - lastRegisterAt > REREGISTER_MS
                    || (lastAnyLocationAt > 0L && now - lastAnyLocationAt > STALE_LOCATION_MS)) {
                registerLocation(true);
            }
            Location last = bestLastKnownLocation();
            if (last != null) onLocationChanged(last);
            boolean usbReady = AdapterGateway.get(app).usbReady();
            if (usbReady && !lastUsbReady && displayedStep >= 0 && canSendCompass()) {
                sendStep(displayedStep);
            }
            lastUsbReady = usbReady;
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    public CompassMonitor(Context context) {
        this.app = context.getApplicationContext();
        this.sender = new NavigationClusterSender(app);
    }

    public void start() {
        if (running) {
            registerLocation(false);
            return;
        }
        if (!hasLocationPermission()) {
            AppLog.line(app, "Compass: location permission not granted");
            return;
        }
        locationManager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        if (locationManager == null) {
            AppLog.line(app, "Compass: location manager unavailable");
            return;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        lastUsbReady = AdapterGateway.get(app).usbReady();
        if (locationNeeded()) acquireWakeLock();
        registerLocation(true);
        updateHeadingSensorRegistration();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
        AppLog.line(app, "Compass: GPS + sensor heading monitor started");
    }

    public void stop() {
        if (!running && !registered) return;
        unregisterLocation();
        running = false;
        displayedStep = -1;
        targetStep = -1;
        lastLocationAt = 0L;
        lastAnyLocationAt = 0L;
        startedAt = 0L;
        lastTransmittedStep = -1;
        animationRunning = false;
        lastUsbReady = false;
        lastSensorHeadingAt = 0L;
        lastSensorPublishAt = 0L;
        smoothedSensorHeading = Float.NaN;
        lastPublishedSensorHeading = Float.NaN;
        lastLocation = null;
        handler.removeCallbacks(animate);
        handler.removeCallbacks(watchdog);
        unregisterHeadingSensor();
        releaseWakeLock();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        lastAnyLocationAt = System.currentTimeMillis();
        lastLocation = new Location(location);
        NavigationFeature.get(app).updateGpsLocation(location);
        if (!location.hasBearing()) return;
        lastLocationAt = lastAnyLocationAt;
        if (!canSendCompass()) return;
        if (sensorHeadingFresh()) return;
        updateTargetStep(normalize(location.getBearing()));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null) return;
        int type = event.sensor.getType();
        if (type != Sensor.TYPE_ROTATION_VECTOR
                && type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) {
            return;
        }
        float heading = headingFromRotationVector(event.values);
        if (Float.isNaN(heading)) return;
        heading = trueNorthHeading(heading);
        long now = System.currentTimeMillis();
        smoothedSensorHeading = Float.isNaN(smoothedSensorHeading)
                ? heading : blendDegrees(smoothedSensorHeading, heading, 0.32f);
        lastSensorHeadingAt = now;
        float delta = Float.isNaN(lastPublishedSensorHeading)
                ? 999f : Math.abs(signedDelta(smoothedSensorHeading, lastPublishedSensorHeading));
        if (now - lastSensorPublishAt >= SENSOR_PUBLISH_MS || delta >= 1.2f) {
            lastSensorPublishAt = now;
            lastPublishedSensorHeading = smoothedSensorHeading;
            NavigationFeature.get(app).updateDeviceHeading(smoothedSensorHeading,
                    sensorAccuracyDegrees(), event.sensor.getName());
        }
        if (canSendCompass()) updateTargetStep(smoothedSensorHeading);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        sensorAccuracy = accuracy;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (running) registerLocation(true);
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    private void registerLocation(boolean force) {
        if (!running || locationManager == null || !hasLocationPermission()) return;
        if (!locationNeeded()) {
            unregisterLocation();
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && registered && now - lastRegisterAt < REREGISTER_MS) return;
        unregisterLocation();
        try {
            boolean any = requestProvider(LocationManager.GPS_PROVIDER);
            any |= requestProvider(FUSED_PROVIDER);
            any |= requestProvider(LocationManager.NETWORK_PROVIDER);
            any |= requestProvider(LocationManager.PASSIVE_PROVIDER);
            registered = any;
            lastRegisterAt = now;
            Location last = bestLastKnownLocation();
            if (last != null) onLocationChanged(last);
        } catch (Exception e) {
            registered = false;
            AppLog.line(app, "Compass: GPS start failed " + e.getClass().getSimpleName());
        }
    }

    private boolean requestProvider(String provider) {
        try {
            if (locationManager.getProvider(provider) == null) return false;
            if (!locationManager.isProviderEnabled(provider)) return false;
            locationManager.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, this, Looper.getMainLooper());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateHeadingSensorRegistration() {
        if (!running || !headingSensorNeeded()) {
            unregisterHeadingSensor();
            return;
        }
        registerHeadingSensor(false);
    }

    private boolean headingSensorNeeded() {
        return compassDirectionNeeded() || NavigationFeature.get(app).finishDirectionHeadingNeeded();
    }

    private void registerHeadingSensor(boolean force) {
        if (!running || sensorManager == null) return;
        if (!force && sensorRegistered) return;
        unregisterHeadingSensor();
        headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (headingSensor == null) {
            headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        }
        if (headingSensor == null) {
            AppLog.line(app, "Compass: rotation vector sensor unavailable");
            return;
        }
        try {
            sensorRegistered = sensorManager.registerListener(this, headingSensor,
                    SensorManager.SENSOR_DELAY_GAME, handler);
            if (sensorRegistered) {
                AppLog.line(app, "Compass: heading sensor " + headingSensor.getName());
            }
        } catch (Exception e) {
            sensorRegistered = false;
            AppLog.line(app, "Compass: heading sensor failed " + e.getClass().getSimpleName());
        }
    }

    private void unregisterHeadingSensor() {
        if (sensorManager == null || !sensorRegistered) return;
        try {
            sensorManager.unregisterListener(this);
        } catch (Exception ignored) {
        }
        sensorRegistered = false;
    }

    private Location bestLastKnownLocation() {
        Location best = lastKnown(LocationManager.GPS_PROVIDER);
        Location fused = lastKnown(FUSED_PROVIDER);
        if (newerUsefulLocation(fused, best)) best = fused;
        Location passive = lastKnown(LocationManager.PASSIVE_PROVIDER);
        if (newerUsefulLocation(passive, best)) best = passive;
        Location network = lastKnown(LocationManager.NETWORK_PROVIDER);
        if (newerUsefulLocation(network, best)) best = network;
        return best != null && (best.hasBearing() || best.hasSpeed()) ? best : null;
    }

    private Location lastKnown(String provider) {
        try {
            return locationManager == null ? null : locationManager.getLastKnownLocation(provider);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean newerUsefulLocation(Location candidate, Location current) {
        if (candidate == null || (!candidate.hasBearing() && !candidate.hasSpeed())) return false;
        return current == null
                || (!current.hasBearing() && !current.hasSpeed())
                || candidate.getTime() >= current.getTime();
    }

    private void unregisterLocation() {
        if (locationManager == null || !registered) return;
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
        registered = false;
    }

    private boolean hasLocationPermission() {
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private float headingFromRotationVector(float[] values) {
        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values);
            if (!remapForDisplay(rotationMatrix, displayMatrix)) return Float.NaN;
            SensorManager.getOrientation(displayMatrix, orientation);
            return normalize((float) Math.toDegrees(orientation[0]));
        } catch (Exception ignored) {
            return Float.NaN;
        }
    }

    private boolean remapForDisplay(float[] in, float[] out) {
        int rotation = displayRotation();
        switch (rotation) {
            case Surface.ROTATION_90:
                return SensorManager.remapCoordinateSystem(in,
                        SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out);
            case Surface.ROTATION_180:
                return SensorManager.remapCoordinateSystem(in,
                        SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out);
            case Surface.ROTATION_270:
                return SensorManager.remapCoordinateSystem(in,
                        SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out);
            case Surface.ROTATION_0:
            default:
                System.arraycopy(in, 0, out, 0, in.length);
                return true;
        }
    }

    private int displayRotation() {
        try {
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            return wm == null ? Surface.ROTATION_0 : wm.getDefaultDisplay().getRotation();
        } catch (Exception ignored) {
            return Surface.ROTATION_0;
        }
    }

    private float trueNorthHeading(float magneticHeading) {
        Location location = lastLocation;
        if (location == null) return normalize(magneticHeading);
        try {
            GeomagneticField field = new GeomagneticField((float) location.getLatitude(),
                    (float) location.getLongitude(), (float) location.getAltitude(),
                    location.getTime() > 0L ? location.getTime() : System.currentTimeMillis());
            return normalize(magneticHeading + field.getDeclination());
        } catch (Exception ignored) {
            return normalize(magneticHeading);
        }
    }

    private boolean sensorHeadingFresh() {
        return lastSensorHeadingAt > 0L
                && System.currentTimeMillis() - lastSensorHeadingAt <= SENSOR_STALE_MS;
    }

    private float sensorAccuracyDegrees() {
        if (sensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH) return 6f;
        if (sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) return 18f;
        if (sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) return 35f;
        return 60f;
    }

    private void updateTargetStep(float heading) {
        int step = Math.round(normalize(heading) / 30f) * 3;
        if (step == 36) step = 0;
        restoreDisplayedStepFromStore(step);
        if (step == targetStep && displayedStep >= 0) {
            sendCurrentStepIfNeeded();
            return;
        }
        targetStep = step;
        if (displayedStep < 0) {
            sendStep(step);
            return;
        }
        if (displayedStep == targetStep) {
            sendCurrentStepIfNeeded();
            return;
        }
        if (!animationRunning) {
            animationRunning = true;
            handler.post(animate);
        }
    }

    private float normalize(float value) {
        float out = value % 360f;
        return out < 0f ? out + 360f : out;
    }

    private static float signedDelta(float to, float from) {
        float out = (to - from) % 360f;
        if (out > 180f) out -= 360f;
        if (out < -180f) out += 360f;
        return out;
    }

    private float blendDegrees(float from, float to, float toWeight) {
        return normalize(from + signedDelta(to, from) * Math.max(0f, Math.min(1f, toWeight)));
    }

    private void restoreDisplayedStepFromStore(int target) {
        if (displayedStep >= 0) return;
        int stored = lastCompassStepFromStore();
        if (stored < 0) return;
        displayedStep = stored;
        AppLog.line(app, "Compass: restored step=" + displayedStep
                + " target=" + normalizeStep(target));
    }

    private static int lastCompassStepFromStore() {
        NavigationState navigation = StateStore.navigation();
        if (navigation == null) return -1;
        String line = lastLineStarting(navigation.clusterTx, "compass step=");
        String value = tokenAfter(line, "compass step=");
        if (value.isEmpty()) return -1;
        try {
            return normalizeStep(Integer.parseInt(value));
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String lastLineStarting(String value, String prefix) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || prefix == null || prefix.isEmpty()) return "";
        String[] lines = text.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.startsWith(prefix)) return line;
        }
        return "";
    }

    private static String tokenAfter(String value, String marker) {
        String text = value == null ? "" : value.trim();
        String needle = marker == null ? "" : marker;
        int start = text.indexOf(needle);
        if (start < 0) return "";
        String tail = text.substring(start + needle.length()).trim();
        int space = tail.indexOf(' ');
        return space >= 0 ? tail.substring(0, space).trim() : tail;
    }

    private void sendStep(int step) {
        if (!canSendCompass()) return;
        displayedStep = normalizeStep(step);
        lastTransmittedStep = displayedStep;
        sender.sendCompassStep(displayedStep);
    }

    private void sendCurrentStepIfNeeded() {
        if (displayedStep < 0 || normalizeStep(displayedStep) == lastTransmittedStep) return;
        sendStep(displayedStep);
    }

    private boolean canSendCompass() {
        long now = System.currentTimeMillis();
        if (startedAt > 0L && now - startedAt < STARTUP_NAV_GRACE_MS) return false;
        return compassDirectionNeeded();
    }

    private boolean locationNeeded() {
        return speedFallbackNeeded() || compassDirectionNeeded();
    }

    private boolean speedFallbackNeeded() {
        return AppSettings.navigationEnabled(app);
    }

    private boolean compassDirectionNeeded() {
        NavigationFeature navigation = NavigationFeature.get(app);
        return (!navigation.active() && !navigation.finishCompassSuppressed())
                || AppSettings.compassForceEnabled(app);
    }

    private static int nextStep(int current, int target) {
        int c = normalizeStep(current);
        int t = normalizeStep(target);
        int forward = (t - c + 36) % 36;
        int backward = (c - t + 36) % 36;
        if (forward == 0) return t;
        return normalizeStep(c + (forward <= backward ? 3 : -3));
    }

    private static int normalizeStep(int step) {
        int out = ((step % 36) + 36) % 36;
        out = Math.round(out / 3f) * 3;
        return out == 36 ? 0 : out;
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kia:Compass");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception e) {
            AppLog.line(app, "Compass: wakelock failed " + e.getClass().getSimpleName());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }
}
