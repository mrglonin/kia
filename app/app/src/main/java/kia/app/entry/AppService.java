package kia.app.entry;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import kia.app.R;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.settings.AppSettings;
import kia.app.diagnostics.HealthMonitor;
import kia.app.media.capture.BluetoothCallReceiver;
import kia.app.media.capture.MediaCaptureManager;
import kia.app.media.cluster.MediaClusterSender;
import kia.app.media.domain.CallFeature;
import kia.app.media.overlay.MediaOverlayController;
import kia.app.navigation.capture.DgisDashboardClient;
import kia.app.navigation.capture.LegacyNavBroadcastReceiver;
import kia.app.navigation.capture.NavigationSourceGate;
import kia.app.navigation.capture.YandexCoreBridgeClient;
import kia.app.navigation.compass.CompassMonitor;
import kia.app.navigation.domain.NavigationFeature;
import kia.app.navigation.overlay.NavigationOverlayController;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.rcta.RctaOverlayController;
import kia.app.tpms.TpmsController;

public final class AppService extends Service {
    private static final String CHANNEL = "kia_canbus_connection";
    private static final String CHANNEL_NAME = "Kia CANBUS";
    private static final String SERVICE_TITLE = "Kia CANBUS";
    private static final String SERVICE_TEXT = "CANBUS и датчики работают в фоне";
    private static final String SERVICE_DETAILS = "Kia следит за автомобилем, TPMS, медиа и навигацией.";
    private static final int NOTIFICATION_ID = 51;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 9L * 60L * 1000L;
    private static volatile boolean serviceRunning;

    private AdapterGateway gateway;
    private HealthMonitor healthMonitor;
    private MediaCaptureManager mediaCapture;
    private DgisDashboardClient dgisDashboard;
    private YandexCoreBridgeClient yandexCoreBridge;
    private CompassMonitor compassMonitor;
    private NavigationOverlayController navigationOverlay;
    private MediaOverlayController mediaOverlay;
    private RctaOverlayController rctaOverlay;
    private LegacyNavBroadcastReceiver navReceiver;
    private BluetoothCallReceiver bluetoothCallReceiver;
    private BroadcastReceiver locationProviderReceiver;
    private PowerManager.WakeLock serviceWakeLock;
    private final Handler wakeLockHandler = new Handler(Looper.getMainLooper());
    private final Runnable renewWakeLock = this::acquireServiceWakeLock;
    private boolean navReceiverRegistered;
    private boolean bluetoothCallReceiverRegistered;
    private boolean locationProviderReceiverRegistered;
    private boolean terminalForegroundFailure;
    private long lastLocationProviderRefreshAt;

    public static void start(Context context) {
        Intent intent = new Intent(context, AppService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception e) {
            String stage = "launch " + e.getClass().getSimpleName();
            String health = foregroundFailureHealth(stage);
            StateStore.setAdapter(context, StateStore.adapter().withHealth(health));
            AppLog.line(context, "Service: start blocked " + e.getClass().getSimpleName()
                    + " " + safeMessage(e));
        }
    }

    public static boolean isRunning() {
        return serviceRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppSettings.applyDefaults(this);
        StateStore.restoreNavigation(this);
        if (!startForegroundCompat(SERVICE_TEXT)) {
            stopAfterForegroundFailure("create");
            return;
        }
        registerLocationProviderReceiver();
        acquireServiceWakeLock();
        gateway = AdapterGateway.get(this);
        gateway.start();
        gateway.requestAdapterInfoQuiet();
        TpmsController.get(this).startPolling();

        mediaCapture = new MediaCaptureManager(this);
        syncMediaCapture();

        healthMonitor = new HealthMonitor(this);
        healthMonitor.start();

        compassMonitor = new CompassMonitor(this);
        if (AppSettings.compassEnabled(this)) {
            compassMonitor.start();
        }
        registerNavReceiver();
        syncBluetoothCallReceiver();
        dgisDashboard = new DgisDashboardClient(this);
        yandexCoreBridge = new YandexCoreBridgeClient(this);
        syncNavigationCapture();
        navigationOverlay = NavigationOverlayController.get(this);
        navigationOverlay.start();
        mediaOverlay = MediaOverlayController.get(this);
        mediaOverlay.start();
        rctaOverlay = RctaOverlayController.get(this);
        rctaOverlay.start();
        serviceRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (terminalForegroundFailure) {
            stopSelf(startId);
            return foregroundStartMode(false);
        }
        if (!startForegroundCompat(SERVICE_TEXT)) {
            stopAfterForegroundFailure("start");
            stopSelf(startId);
            return foregroundStartMode(false);
        }
        acquireServiceWakeLock();
        if (gateway == null) gateway = AdapterGateway.get(this);
        gateway.start();
        TpmsController.get(this).startPolling();
        if (healthMonitor == null) healthMonitor = new HealthMonitor(this);
        healthMonitor.start();
        if (mediaCapture == null) mediaCapture = new MediaCaptureManager(this);
        syncMediaCapture();
        if (compassMonitor == null) compassMonitor = new CompassMonitor(this);
        if (AppSettings.compassEnabled(this)) {
            compassMonitor.start();
        } else {
            compassMonitor.stop();
        }
        registerNavReceiver();
        syncBluetoothCallReceiver();
        if (dgisDashboard == null) dgisDashboard = new DgisDashboardClient(this);
        if (yandexCoreBridge == null) yandexCoreBridge = new YandexCoreBridgeClient(this);
        syncNavigationCapture();
        if (navigationOverlay == null) navigationOverlay = NavigationOverlayController.get(this);
        navigationOverlay.start();
        if (mediaOverlay == null) mediaOverlay = MediaOverlayController.get(this);
        mediaOverlay.start();
        if (rctaOverlay == null) rctaOverlay = RctaOverlayController.get(this);
        rctaOverlay.start();
        return foregroundStartMode(true);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!terminalForegroundFailure && AppSettings.autoStart(this)) AppService.start(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        serviceRunning = false;
        if (healthMonitor != null) healthMonitor.stop();
        if (mediaCapture != null) mediaCapture.stop();
        MediaClusterSender.get(this).stopSynchronizedPath();
        if (dgisDashboard != null) dgisDashboard.stop();
        if (yandexCoreBridge != null) yandexCoreBridge.stop();
        if (compassMonitor != null) compassMonitor.stop();
        if (navigationOverlay != null) navigationOverlay.stop();
        if (mediaOverlay != null) mediaOverlay.stop();
        if (rctaOverlay != null) rctaOverlay.stop();
        unregisterNavReceiver();
        unregisterBluetoothCallReceiver();
        unregisterLocationProviderReceiver();
        releaseServiceWakeLock();
        TpmsController.get(this).stopPolling();
        if (gateway != null) gateway.stop();
        AppLog.line(this, "Service: stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean startForegroundCompat(String text) {
        Notification note;
        try {
            note = notification(text);
        } catch (Exception e) {
            AppLog.line(this, "Service: notification failed "
                    + e.getClass().getSimpleName() + " " + safeMessage(e));
            return false;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            int types = foregroundTypes();
            try {
                startForeground(NOTIFICATION_ID, note, types);
                return true;
            } catch (Exception e) {
                AppLog.line(this, "Service: foreground fallback "
                        + e.getClass().getSimpleName() + " " + safeMessage(e));
                try {
                    startForeground(
                            NOTIFICATION_ID,
                            note,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    );
                    return true;
                } catch (Exception fallback) {
                    AppLog.line(this, "Service: foreground failed "
                            + fallback.getClass().getSimpleName() + " " + safeMessage(fallback));
                    return false;
                }
            }
        }
        try {
            startForeground(NOTIFICATION_ID, note);
            return true;
        } catch (Exception e) {
            AppLog.line(this, "Service: foreground failed "
                    + e.getClass().getSimpleName() + " " + safeMessage(e));
            return false;
        }
    }

    private void stopAfterForegroundFailure(String stage) {
        serviceRunning = false;
        terminalForegroundFailure = true;
        String health = foregroundFailureHealth(stage);
        StateStore.setAdapter(this, StateStore.adapter().withHealth(health));
        AppLog.line(this, "Service: stopping after " + health);
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    static String foregroundFailureHealth(String stage) {
        String clean = stage == null ? "" : stage.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) clean = "unknown";
        if (clean.length() > 40) clean = clean.substring(0, 40);
        return "service foreground failed: " + clean;
    }

    static int foregroundStartMode(boolean foregroundReady) {
        return foregroundReady ? START_STICKY : START_NOT_STICKY;
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(SERVICE_DETAILS);
            nm.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_kia)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher))
                .setContentTitle(SERVICE_TITLE)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(SERVICE_DETAILS))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private int foregroundTypes() {
        if (Build.VERSION.SDK_INT < 29) return 0;
        return foregroundTypeMask(locationForegroundAllowed());
    }

    static int foregroundTypeMask(boolean locationAllowed) {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (locationAllowed) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        return types;
    }

    private boolean locationForegroundAllowed() {
        boolean foregroundLocation =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        if (!foregroundLocation) return false;
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null) return "";
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLocationProviderReceiver() {
        if (locationProviderReceiverRegistered) return;
        try {
            locationProviderReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null
                            || !LocationManager.PROVIDERS_CHANGED_ACTION.equals(intent.getAction())) {
                        return;
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastLocationProviderRefreshAt < 750L) return;
                    lastLocationProviderRefreshAt = now;
                    if (!startForegroundCompat(SERVICE_TEXT)) {
                        stopAfterForegroundFailure("location refresh");
                        return;
                    }
                    if (compassMonitor != null && AppSettings.compassEnabled(AppService.this)) {
                        compassMonitor.start();
                    }
                    AppLog.line(AppService.this,
                            "Service: foreground types refreshed after location provider change");
                }
            };
            IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(locationProviderReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(locationProviderReceiver, filter);
            }
            locationProviderReceiverRegistered = true;
        } catch (Exception e) {
            locationProviderReceiver = null;
            locationProviderReceiverRegistered = false;
            AppLog.line(this, "Service: location provider receiver failed "
                    + e.getClass().getSimpleName());
        }
    }

    private void unregisterLocationProviderReceiver() {
        if (!locationProviderReceiverRegistered) return;
        try {
            unregisterReceiver(locationProviderReceiver);
        } catch (Exception ignored) {
        }
        locationProviderReceiverRegistered = false;
        locationProviderReceiver = null;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerNavReceiver() {
        if (navReceiverRegistered) return;
        try {
            navReceiver = new LegacyNavBroadcastReceiver();
            IntentFilter filter = new IntentFilter();
            LegacyNavBroadcastReceiver.addActions(filter);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(navReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(navReceiver, filter);
            }
            navReceiverRegistered = true;
            AppLog.line(this, "Navigation: receiver registered");
        } catch (Exception e) {
            navReceiver = null;
            navReceiverRegistered = false;
            AppLog.line(this, "Navigation receiver failed: " + e.getClass().getSimpleName());
        }
    }

    private void unregisterNavReceiver() {
        if (!navReceiverRegistered) return;
        try {
            unregisterReceiver(navReceiver);
        } catch (Exception ignored) {
        }
        navReceiverRegistered = false;
        navReceiver = null;
    }

    private void acquireServiceWakeLock() {
        try {
            wakeLockHandler.removeCallbacks(renewWakeLock);
            if (serviceWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kia:Service");
                    serviceWakeLock.setReferenceCounted(false);
                }
            }
            if (serviceWakeLock != null) {
                if (serviceWakeLock.isHeld()) serviceWakeLock.release();
                serviceWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
                AppLog.line(this, "Service: wakelock acquired");
                wakeLockHandler.postDelayed(renewWakeLock, WAKE_LOCK_RENEW_MS);
            }
        } catch (Exception e) {
            AppLog.line(this, "Service: wakelock failed " + e.getClass().getSimpleName());
        }
    }

    private void releaseServiceWakeLock() {
        wakeLockHandler.removeCallbacks(renewWakeLock);
        try {
            if (serviceWakeLock != null && serviceWakeLock.isHeld()) serviceWakeLock.release();
        } catch (Exception ignored) {
        }
        serviceWakeLock = null;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerBluetoothCallReceiver() {
        if (bluetoothCallReceiverRegistered) return;
        try {
            bluetoothCallReceiver = new BluetoothCallReceiver();
            IntentFilter filter = new IntentFilter();
            BluetoothCallReceiver.addActions(filter);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(bluetoothCallReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(bluetoothCallReceiver, filter);
            }
            bluetoothCallReceiverRegistered = true;
            AppLog.line(this, "HFP call receiver registered");
        } catch (Exception e) {
            bluetoothCallReceiver = null;
            bluetoothCallReceiverRegistered = false;
            AppLog.line(this, "HFP call receiver failed: " + e.getClass().getSimpleName());
        }
    }

    private void unregisterBluetoothCallReceiver() {
        if (!bluetoothCallReceiverRegistered) return;
        try {
            unregisterReceiver(bluetoothCallReceiver);
        } catch (Exception ignored) {
        }
        bluetoothCallReceiverRegistered = false;
        bluetoothCallReceiver = null;
    }

    private void syncMediaCapture() {
        if (mediaCapture == null) return;
        MediaClusterSender.get(this).onProfileChanged();
        if (AppSettings.mediaEnabled(this)) {
            mediaCapture.start();
        } else {
            mediaCapture.stop();
        }
    }

    private void syncBluetoothCallReceiver() {
        if (AppSettings.callEnabled(this)) {
            registerBluetoothCallReceiver();
        } else {
            unregisterBluetoothCallReceiver();
            CallFeature.get(this).stop();
        }
    }

    private void syncNavigationCapture() {
        boolean navigationEnabled = AppSettings.navigationEnabled(this);
        NavigationFeature.get(this).syncNavigationEnabled(navigationEnabled);
        boolean yandexEnabled = NavigationSourceGate.yandexEnabled(
                navigationEnabled, AppSettings.yandexNavigationEnabled(this));
        boolean dgisEnabled = NavigationSourceGate.dgisEnabled(
                navigationEnabled, AppSettings.dgisNavigationEnabled(this));

        if (yandexCoreBridge != null) {
            if (yandexEnabled) yandexCoreBridge.start();
            else yandexCoreBridge.stop();
        }
        if (dgisDashboard != null) {
            if (dgisEnabled) dgisDashboard.start();
            else dgisDashboard.stop();
        }
    }
}
