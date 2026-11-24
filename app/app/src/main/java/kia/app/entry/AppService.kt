package kia.app.entry

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kia.app.R
import kia.app.adas.AdasController
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.settings.AppSettings
import kia.app.diagnostics.HealthMonitor
import kia.app.media.capture.BluetoothCallReceiver
import kia.app.media.capture.MediaCaptureManager
import kia.app.media.cluster.MediaClusterSender
import kia.app.media.domain.CallFeature
import kia.app.media.overlay.MediaOverlayController
import kia.app.navigation.capture.DgisDashboardClient
import kia.app.navigation.capture.LegacyNavBroadcastReceiver
import kia.app.navigation.capture.NavigationSourceGate
import kia.app.navigation.capture.YandexCoreBridgeClient
import kia.app.navigation.compass.CompassMonitor
import kia.app.navigation.domain.NavigationFeature
import kia.app.navigation.overlay.NavigationOverlayController
import kia.app.protocol.adapter.AdapterGateway
import kia.app.rcta.RctaOverlayController
import kia.app.sunroof.SunroofOverlayController
import kia.app.tpms.TeyesTpmsWidgetForwarder
import kia.app.tpms.TpmsAlertController
import kia.app.tpms.TpmsController
import kia.app.update.AppUpdateCheckPolicy
import kia.app.update.AppUpdateController
import kia.app.update.NavigatorUpdateController

class AppService : Service() {
    private var gateway: AdapterGateway? = null
    private var healthMonitor: HealthMonitor? = null
    private var mediaCapture: MediaCaptureManager? = null
    private var dgisDashboard: DgisDashboardClient? = null
    private var yandexCoreBridge: YandexCoreBridgeClient? = null
    private var compassMonitor: CompassMonitor? = null
    private var navigationOverlay: NavigationOverlayController? = null
    private var mediaOverlay: MediaOverlayController? = null
    private var rctaOverlay: RctaOverlayController? = null
    private var sunroofOverlay: SunroofOverlayController? = null
    private var navReceiver: LegacyNavBroadcastReceiver? = null
    private var bluetoothCallReceiver: BluetoothCallReceiver? = null
    private var locationProviderReceiver: BroadcastReceiver? = null
    private var serviceWakeLock: PowerManager.WakeLock? = null
    private val wakeLockHandler: Handler = Handler(Looper.getMainLooper())
    private val renewWakeLock: Runnable = Runnable { this.acquireServiceWakeLock() }
    private val updateCheckHandler: Handler = Handler(Looper.getMainLooper())
    private val backgroundUpdateCheck: Runnable =
        object : Runnable {
            override fun run() {
                if (!AppService.serviceRunning || terminalForegroundFailure) return
                val now: Long = System.currentTimeMillis()
                val appDue: Boolean =
                    AppUpdateCheckPolicy.shouldCheck(
                        now,
                        AppSettings.lastAppUpdateCheckAt(this@AppService),
                    )
                val navigatorDue: Boolean =
                    AppUpdateCheckPolicy.shouldCheck(
                        now,
                        AppSettings.lastNavigatorUpdateCheckAt(this@AppService),
                    )
                if (appDue || navigatorDue) {
                    if (appUpdater == null) appUpdater = AppUpdateController(this@AppService)
                    if (navigatorUpdater == null) {
                        navigatorUpdater = NavigatorUpdateController(this@AppService)
                    }
                    AppLog.line(
                        this@AppService,
                        ("Background update check: Kia=" + appDue + " Yandex=" + navigatorDue),
                    )
                    if (appDue) appUpdater!!.checkAsync()
                    if (navigatorDue) navigatorUpdater!!.checkAsync()
                }
                updateCheckHandler.postDelayed(this, AppUpdateCheckPolicy.BACKGROUND_POLL_MS)
            }
        }
    private var appUpdater: AppUpdateController? = null
    private var navigatorUpdater: NavigatorUpdateController? = null
    private var navReceiverRegistered: Boolean = false
    private var bluetoothCallReceiverRegistered: Boolean = false
    private var locationProviderReceiverRegistered: Boolean = false
    private var terminalForegroundFailure: Boolean = false
    private var lastLocationProviderRefreshAt: Long = 0

    override fun onCreate() {
        super.onCreate()
        AppSettings.applyDefaults(this)
        StateStore.restoreNavigation(this)
        if (!startForegroundCompat(AppService.SERVICE_TEXT)) {
            stopAfterForegroundFailure("create")
            return
        }
        registerLocationProviderReceiver()
        acquireServiceWakeLock()
        gateway = AdapterGateway.get(this)
        TeyesTpmsWidgetForwarder.get(this).start()
        gateway!!.start()
        TpmsController.get(this).startPolling()

        mediaCapture = MediaCaptureManager(this)
        syncMediaCapture()

        healthMonitor = HealthMonitor(this)
        healthMonitor!!.start()

        compassMonitor = CompassMonitor(this)
        if (AppSettings.compassEnabled(this)) {
            compassMonitor!!.start()
        }
        registerNavReceiver()
        syncBluetoothCallReceiver()
        dgisDashboard = DgisDashboardClient(this)
        yandexCoreBridge = YandexCoreBridgeClient(this)
        syncNavigationCapture()
        navigationOverlay = NavigationOverlayController.get(this)
        navigationOverlay!!.start()
        mediaOverlay = MediaOverlayController.get(this)
        mediaOverlay!!.start()
        rctaOverlay = RctaOverlayController.get(this)
        rctaOverlay!!.start()
        sunroofOverlay = SunroofOverlayController.get(this)
        sunroofOverlay!!.start()
        AppService.serviceRunning = true
        AdasController.get(this).applySettings()
        updateCheckHandler.postDelayed(backgroundUpdateCheck, 10000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (terminalForegroundFailure) {
            stopSelf(startId)
            return AppService.foregroundStartMode(false)
        }
        if (!startForegroundCompat(AppService.SERVICE_TEXT)) {
            stopAfterForegroundFailure("start")
            stopSelf(startId)
            return AppService.foregroundStartMode(false)
        }
        acquireServiceWakeLock()
        if (gateway == null) gateway = AdapterGateway.get(this)
        TeyesTpmsWidgetForwarder.get(this).start()
        gateway!!.start()
        TpmsController.get(this).startPolling()
        if (healthMonitor == null) healthMonitor = HealthMonitor(this)
        healthMonitor!!.start()
        if (mediaCapture == null) mediaCapture = MediaCaptureManager(this)
        syncMediaCapture()
        if (compassMonitor == null) compassMonitor = CompassMonitor(this)
        if (AppSettings.compassEnabled(this)) {
            compassMonitor!!.start()
        } else {
            compassMonitor!!.stop()
        }
        registerNavReceiver()
        syncBluetoothCallReceiver()
        if (dgisDashboard == null) dgisDashboard = DgisDashboardClient(this)
        if (yandexCoreBridge == null) yandexCoreBridge = YandexCoreBridgeClient(this)
        syncNavigationCapture()
        if (navigationOverlay == null) navigationOverlay = NavigationOverlayController.get(this)
        navigationOverlay!!.start()
        if (mediaOverlay == null) mediaOverlay = MediaOverlayController.get(this)
        mediaOverlay!!.start()
        if (rctaOverlay == null) rctaOverlay = RctaOverlayController.get(this)
        rctaOverlay!!.start()
        if (sunroofOverlay == null) sunroofOverlay = SunroofOverlayController.get(this)
        sunroofOverlay!!.start()
        AdasController.get(this).applySettings()
        return AppService.foregroundStartMode(true)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!terminalForegroundFailure && AppSettings.autoStart(this)) AppService.start(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppService.serviceRunning = false
        updateCheckHandler.removeCallbacks(backgroundUpdateCheck)
        AdasController.get(this).stop()
        if (healthMonitor != null) healthMonitor!!.stop()
        if (mediaCapture != null) mediaCapture!!.stop()
        MediaClusterSender.get(this).stopSynchronizedPath()
        if (dgisDashboard != null) dgisDashboard!!.stop()
        if (yandexCoreBridge != null) yandexCoreBridge!!.stop()
        if (compassMonitor != null) compassMonitor!!.stop()
        if (navigationOverlay != null) navigationOverlay!!.stop()
        if (mediaOverlay != null) mediaOverlay!!.stop()
        if (rctaOverlay != null) rctaOverlay!!.stop()
        if (sunroofOverlay != null) sunroofOverlay!!.stop()
        unregisterNavReceiver()
        unregisterBluetoothCallReceiver()
        unregisterLocationProviderReceiver()
        releaseServiceWakeLock()
        TeyesTpmsWidgetForwarder.get(this).stop()
        TpmsController.get(this).stopPolling()
        TpmsAlertController.get(this).clear()
        if (gateway != null) gateway!!.stop()
        AppLog.line(this, "Service: stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundCompat(text: String?): Boolean {
        val note: Notification?
        try {
            note = notification(text)
        } catch (e: Exception) {
            AppLog.line(
                this,
                ("Service: notification failed " +
                    e.javaClass.getSimpleName() +
                    " " +
                    AppService.safeMessage(e)),
            )
            return false
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val types: Int = foregroundTypes()
            try {
                startForeground(AppService.NOTIFICATION_ID, note!!, types)
                return true
            } catch (e: Exception) {
                AppLog.line(
                    this,
                    ("Service: foreground fallback " +
                        e.javaClass.getSimpleName() +
                        " " +
                        AppService.safeMessage(e)),
                )
                try {
                    startForeground(
                        AppService.NOTIFICATION_ID,
                        note!!,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                    return true
                } catch (fallback: Exception) {
                    AppLog.line(
                        this,
                        ("Service: foreground failed " +
                            fallback.javaClass.getSimpleName() +
                            " " +
                            AppService.safeMessage(fallback)),
                    )
                    return false
                }
            }
        }
        try {
            startForeground(AppService.NOTIFICATION_ID, note)
            return true
        } catch (e: Exception) {
            AppLog.line(
                this,
                ("Service: foreground failed " +
                    e.javaClass.getSimpleName() +
                    " " +
                    AppService.safeMessage(e)),
            )
            return false
        }
    }

    private fun stopAfterForegroundFailure(stage: String?) {
        AppService.serviceRunning = false
        terminalForegroundFailure = true
        val health: String = AppService.foregroundFailureHealth(stage)
        StateStore.setAdapter(this, StateStore.adapter()!!.withHealth(health))
        AppLog.line(this, "Service: stopping after " + health)
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        } catch (ignored: Exception) {}
        stopSelf()
    }

    private fun notification(text: String?): Notification? {
        val nm: NotificationManager? =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            val channel: NotificationChannel =
                NotificationChannel(
                    AppService.CHANNEL,
                    AppService.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                )
            channel.setDescription(AppService.SERVICE_DETAILS)
            nm.createNotificationChannel(channel)
        }
        val open: PendingIntent? =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, AppService.CHANNEL)
            else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_stat_kia)
            .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher))
            .setContentTitle(AppService.SERVICE_TITLE)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(AppService.SERVICE_DETAILS))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        return AppService.foregroundTypeMask(locationForegroundAllowed())
    }

    private fun locationForegroundAllowed(): Boolean {
        val foregroundLocation: Boolean =
            (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED) ||
                (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED)
        if (!foregroundLocation) return false
        return (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLocationProviderReceiver() {
        if (locationProviderReceiverRegistered) return
        try {
            locationProviderReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (
                            intent == null ||
                                !LocationManager.PROVIDERS_CHANGED_ACTION.equals(intent.getAction())
                        ) {
                            return
                        }
                        val now: Long = SystemClock.elapsedRealtime()
                        if (now - lastLocationProviderRefreshAt < 750L) return
                        lastLocationProviderRefreshAt = now
                        if (!startForegroundCompat(AppService.SERVICE_TEXT)) {
                            stopAfterForegroundFailure("location refresh")
                            return
                        }
                        if (compassMonitor != null && AppSettings.compassEnabled(this@AppService)) {
                            compassMonitor!!.start()
                        }
                        AppLog.line(
                            this@AppService,
                            "Service: foreground types refreshed after location provider change",
                        )
                    }
                }
            val filter: IntentFilter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(locationProviderReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(locationProviderReceiver, filter)
            }
            locationProviderReceiverRegistered = true
        } catch (e: Exception) {
            locationProviderReceiver = null
            locationProviderReceiverRegistered = false
            AppLog.line(
                this,
                "Service: location provider receiver failed " + e.javaClass.getSimpleName(),
            )
        }
    }

    private fun unregisterLocationProviderReceiver() {
        if (!locationProviderReceiverRegistered) return
        try {
            unregisterReceiver(locationProviderReceiver)
        } catch (ignored: Exception) {}
        locationProviderReceiverRegistered = false
        locationProviderReceiver = null
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerNavReceiver() {
        if (navReceiverRegistered) return
        try {
            navReceiver = LegacyNavBroadcastReceiver()
            val filter: IntentFilter = IntentFilter()
            LegacyNavBroadcastReceiver.addActions(filter)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(navReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(navReceiver, filter)
            }
            navReceiverRegistered = true
            AppLog.line(this, "Navigation: receiver registered")
        } catch (e: Exception) {
            navReceiver = null
            navReceiverRegistered = false
            AppLog.line(this, "Navigation receiver failed: " + e.javaClass.getSimpleName())
        }
    }

    private fun unregisterNavReceiver() {
        if (!navReceiverRegistered) return
        try {
            unregisterReceiver(navReceiver)
        } catch (ignored: Exception) {}
        navReceiverRegistered = false
        navReceiver = null
    }

    private fun acquireServiceWakeLock() {
        try {
            wakeLockHandler.removeCallbacks(renewWakeLock)
            if (serviceWakeLock == null) {
                val pm: PowerManager? = getSystemService(POWER_SERVICE) as PowerManager?
                if (pm != null) {
                    serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kia:Service")
                    serviceWakeLock!!.setReferenceCounted(false)
                }
            }
            if (serviceWakeLock != null) {
                if (serviceWakeLock!!.isHeld()) serviceWakeLock!!.release()
                serviceWakeLock!!.acquire(AppService.WAKE_LOCK_TIMEOUT_MS)
                AppLog.line(this, "Service: wakelock acquired")
                wakeLockHandler.postDelayed(renewWakeLock, AppService.WAKE_LOCK_RENEW_MS)
            }
        } catch (e: Exception) {
            AppLog.line(this, "Service: wakelock failed " + e.javaClass.getSimpleName())
        }
    }

    private fun releaseServiceWakeLock() {
        wakeLockHandler.removeCallbacks(renewWakeLock)
        try {
            if (serviceWakeLock != null && serviceWakeLock!!.isHeld()) serviceWakeLock!!.release()
        } catch (ignored: Exception) {}
        serviceWakeLock = null
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBluetoothCallReceiver() {
        if (bluetoothCallReceiverRegistered) return
        try {
            bluetoothCallReceiver = BluetoothCallReceiver()
            val filter: IntentFilter = IntentFilter()
            BluetoothCallReceiver.addActions(filter)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(bluetoothCallReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(bluetoothCallReceiver, filter)
            }
            bluetoothCallReceiverRegistered = true
            AppLog.line(this, "HFP call receiver registered")
        } catch (e: Exception) {
            bluetoothCallReceiver = null
            bluetoothCallReceiverRegistered = false
            AppLog.line(this, "HFP call receiver failed: " + e.javaClass.getSimpleName())
        }
    }

    private fun unregisterBluetoothCallReceiver() {
        if (!bluetoothCallReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothCallReceiver)
        } catch (ignored: Exception) {}
        bluetoothCallReceiverRegistered = false
        bluetoothCallReceiver = null
    }

    private fun syncMediaCapture() {
        if (mediaCapture == null) return
        MediaClusterSender.get(this).onProfileChanged()
        if (AppSettings.mediaEnabled(this)) {
            mediaCapture!!.start()
        } else {
            mediaCapture!!.stop()
        }
    }

    private fun syncBluetoothCallReceiver() {
        if (AppSettings.callEnabled(this)) {
            registerBluetoothCallReceiver()
        } else {
            unregisterBluetoothCallReceiver()
            CallFeature.get(this).stop()
        }
    }

    private fun syncNavigationCapture() {
        val navigationEnabled: Boolean = AppSettings.navigationEnabled(this)
        NavigationFeature.get(this).syncNavigationEnabled(navigationEnabled)
        val yandexEnabled: Boolean =
            NavigationSourceGate.yandexEnabled(
                navigationEnabled,
                AppSettings.yandexNavigationEnabled(this),
            )
        val dgisEnabled: Boolean =
            NavigationSourceGate.dgisEnabled(
                navigationEnabled,
                AppSettings.dgisNavigationEnabled(this),
            )

        if (yandexCoreBridge != null) {
            if (yandexEnabled) yandexCoreBridge!!.start() else yandexCoreBridge!!.stop()
        }
        if (dgisDashboard != null) {
            if (dgisEnabled) dgisDashboard!!.start() else dgisDashboard!!.stop()
        }
    }

    companion object {
        private val CHANNEL: String = "kia_canbus_connection"
        private val CHANNEL_NAME: String = "Kia CANBUS"
        private val SERVICE_TITLE: String = "Kia CANBUS"
        private val SERVICE_TEXT: String = "CANBUS и датчики работают в фоне"
        private val SERVICE_DETAILS: String = "Kia следит за автомобилем, TPMS, медиа и навигацией."
        private const val NOTIFICATION_ID: Int = 51
        private val WAKE_LOCK_TIMEOUT_MS: Long = 10L * 60L * 1000L
        private val WAKE_LOCK_RENEW_MS: Long = 9L * 60L * 1000L
        @kotlin.concurrent.Volatile private var serviceRunning: Boolean = false

        @JvmStatic
        /** Returns whether Android accepted the service launch request. */
        fun start(context: Context): Boolean {
            val intent: Intent = Intent(context, AppService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    return context.startForegroundService(intent) != null
                }
                return context.startService(intent) != null
            } catch (e: Exception) {
                val stage: String = "launch " + e.javaClass.getSimpleName()
                val health: String = AppService.foregroundFailureHealth(stage)
                StateStore.setAdapter(context, StateStore.adapter()!!.withHealth(health))
                AppLog.line(
                    context,
                    ("Service: start blocked " +
                        e.javaClass.getSimpleName() +
                        " " +
                        AppService.safeMessage(e)),
                )
                return false
            }
        }

        @JvmStatic
        fun isRunning(): Boolean {
            return AppService.serviceRunning
        }

        @JvmStatic
        fun foregroundFailureHealth(stage: String?): String {
            var clean: String? =
                if (stage == null) "" else stage.replace('\n', ' ').replace('\r', ' ').trim()
            if (clean!!.isEmpty()) clean = "unknown"
            if (clean.length > 40) clean = clean.substring(0, 40)
            return "service foreground failed: " + clean
        }

        @JvmStatic
        fun foregroundStartMode(foregroundReady: Boolean): Int {
            return if (foregroundReady) START_STICKY else START_NOT_STICKY
        }

        @JvmStatic
        fun foregroundTypeMask(locationAllowed: Boolean): Int {
            var types: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (locationAllowed) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            return types
        }

        private fun safeMessage(error: Exception): String? {
            var message: String? = error.message
            if (message == null) return ""
            message = message.replace('\n', ' ').replace('\r', ' ').trim()
            return if (message.length <= 180) message else message.substring(0, 180)
        }
    }
}
