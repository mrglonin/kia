package kia.app.entry;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import kia.app.R;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.settings.AppSettings;
import kia.app.diagnostics.GsUsbCanLogger;
import kia.app.diagnostics.HealthMonitor;
import kia.app.media.capture.BluetoothCallReceiver;
import kia.app.media.capture.MediaCaptureManager;
import kia.app.media.domain.CallFeature;
import kia.app.media.overlay.MediaOverlayController;
import kia.app.navigation.capture.DgisDashboardClient;
import kia.app.navigation.capture.NavBroadcastReceiver;
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

    private AdapterGateway gateway;
    private HealthMonitor healthMonitor;
    private MediaCaptureManager mediaCapture;
    private DgisDashboardClient dgisDashboard;
    private YandexCoreBridgeClient yandexCoreBridge;
    private CompassMonitor compassMonitor;
    private NavigationOverlayController navigationOverlay;
    private MediaOverlayController mediaOverlay;
    private RctaOverlayController rctaOverlay;
    private NavBroadcastReceiver navReceiver;
    private BluetoothCallReceiver bluetoothCallReceiver;
    private PowerManager.WakeLock serviceWakeLock;
    private boolean navReceiverRegistered;
    private boolean bluetoothCallReceiverRegistered;

    public static void start(Context context) {
        Intent intent = new Intent(context, AppService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception e) {
            AppLog.line(context, "Service: start blocked " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppSettings.applyDefaults(this);
        StateStore.restoreNavigation(this);
        startForegroundCompat(SERVICE_TEXT);
        acquireServiceWakeLock();
        gateway = AdapterGateway.get(this);
        gateway.start();
        gateway.requestAdapterInfoQuiet();
        TpmsController.get(this).startPolling();

        mediaCapture = new MediaCaptureManager(this);
        syncMediaCapture();

        healthMonitor = new HealthMonitor(this);
        if (AppSettings.diagnosticsEnabled(this)) healthMonitor.start();

        compassMonitor = new CompassMonitor(this);
        if (AppSettings.compassEnabled(this)) {
            startForegroundCompat(SERVICE_TEXT);
            compassMonitor.start();
        }
        registerNavReceiver();
        syncBluetoothCallReceiver();
        dgisDashboard = new DgisDashboardClient(this);
        dgisDashboard.start();
        yandexCoreBridge = new YandexCoreBridgeClient(this);
        yandexCoreBridge.start();
        navigationOverlay = NavigationOverlayController.get(this);
        navigationOverlay.start();
        mediaOverlay = MediaOverlayController.get(this);
        mediaOverlay.start();
        rctaOverlay = RctaOverlayController.get(this);
        rctaOverlay.start();
        if (AppSettings.debugCan(this)) GsUsbCanLogger.get(this).setRecording(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(SERVICE_TEXT);
        acquireServiceWakeLock();
        if (gateway == null) gateway = AdapterGateway.get(this);
        gateway.start();
        TpmsController.get(this).startPolling();
        if (healthMonitor == null) healthMonitor = new HealthMonitor(this);
        if (AppSettings.diagnosticsEnabled(this)) healthMonitor.start();
        if (mediaCapture == null) mediaCapture = new MediaCaptureManager(this);
        syncMediaCapture();
        if (compassMonitor == null) compassMonitor = new CompassMonitor(this);
        if (AppSettings.compassEnabled(this)) {
            startForegroundCompat(SERVICE_TEXT);
            compassMonitor.start();
        } else {
            compassMonitor.stop();
            startForegroundCompat(SERVICE_TEXT);
        }
        registerNavReceiver();
        syncBluetoothCallReceiver();
        if (dgisDashboard == null) dgisDashboard = new DgisDashboardClient(this);
        dgisDashboard.start();
        if (yandexCoreBridge == null) yandexCoreBridge = new YandexCoreBridgeClient(this);
        yandexCoreBridge.start();
        if (navigationOverlay == null) navigationOverlay = NavigationOverlayController.get(this);
        navigationOverlay.start();
        if (mediaOverlay == null) mediaOverlay = MediaOverlayController.get(this);
        mediaOverlay.start();
        if (rctaOverlay == null) rctaOverlay = RctaOverlayController.get(this);
        rctaOverlay.start();
        if (AppSettings.debugCan(this)) GsUsbCanLogger.get(this).setRecording(true);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (AppSettings.autoStart(this)) AppService.start(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (healthMonitor != null) healthMonitor.stop();
        if (mediaCapture != null) mediaCapture.stop();
        if (dgisDashboard != null) dgisDashboard.stop();
        if (yandexCoreBridge != null) yandexCoreBridge.stop();
        if (compassMonitor != null) compassMonitor.stop();
        if (navigationOverlay != null) navigationOverlay.stop();
        if (mediaOverlay != null) mediaOverlay.stop();
        if (rctaOverlay != null) rctaOverlay.stop();
        unregisterNavReceiver();
        unregisterBluetoothCallReceiver();
        releaseServiceWakeLock();
        TpmsController.get(this).stopPolling();
        GsUsbCanLogger.get(this).setRecording(false);
        if (gateway != null) gateway.stop();
        AppLog.line(this, "Service: stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundCompat(String text) {
        Notification note = notification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            int types = foregroundTypes();
            try {
                startForeground(NOTIFICATION_ID, note, types);
            } catch (Exception e) {
                AppLog.line(this, "Service: foreground fallback " + e.getClass().getSimpleName());
                try {
                    startForeground(NOTIFICATION_ID, note, 0);
                } catch (Exception fallback) {
                    AppLog.line(this, "Service: foreground failed "
                            + fallback.getClass().getSimpleName());
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, note);
        }
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
                .setSmallIcon(R.drawable.ic_launcher)
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
        int types = connectedDeviceForegroundAllowed()
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                : 0;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        return types;
    }

    private boolean connectedDeviceForegroundAllowed() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void registerNavReceiver() {
        if (navReceiverRegistered) return;
        try {
            navReceiver = new NavBroadcastReceiver();
            IntentFilter filter = new IntentFilter();
            NavBroadcastReceiver.addActions(filter);
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
            if (serviceWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kia:Service");
                    serviceWakeLock.setReferenceCounted(false);
                }
            }
            if (serviceWakeLock != null && !serviceWakeLock.isHeld()) {
                serviceWakeLock.acquire();
                AppLog.line(this, "Service: wakelock acquired");
            }
        } catch (Exception e) {
            AppLog.line(this, "Service: wakelock failed " + e.getClass().getSimpleName());
        }
    }

    private void releaseServiceWakeLock() {
        try {
            if (serviceWakeLock != null && serviceWakeLock.isHeld()) serviceWakeLock.release();
        } catch (Exception ignored) {
        }
        serviceWakeLock = null;
    }

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
}
