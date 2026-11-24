package kia.app.tpms

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kia.app.core.AppIds
import kia.app.core.AppLog
import kia.app.core.model.TpmsState
import kia.app.core.settings.AppSettings
import kia.app.protocol.adapter.AdapterGateway

/** Optional display bridge. It neither opens USB nor binds the stock TPMS service. */
class TeyesTpmsWidgetForwarder private constructor(context: Context) {
    private val app: Context
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val policy: TeyesTpmsWidgetPolicy = TeyesTpmsWidgetPolicy()
    // Separate from StateStore: QA samples must never leak into the stock widget.
    private var hardwareState: TpmsState = TpmsState.empty()
    private var stateReceiver: BroadcastReceiver? = null
    private var stockReceiver: BroadcastReceiver? = null
    private var started: Boolean = false
    private var enabled: Boolean = false
    private var usbConnected: Boolean = false
    private var reactiveQueued: Boolean = false
    private var failureLogged: Boolean = false
    private var connectionEpoch: Long = -1L
    private var lastBootstrapElapsed: Long = -1L
    @kotlin.concurrent.Volatile private var lifecycleGeneration: Long = 0

    private val replayTick: Runnable = Runnable {
        if (!started || !enabled) return@Runnable
        refreshConnection()
        publishSnapshot()
        scheduleReplay()
    }

    private val reactiveReplay: Runnable = Runnable {
        reactiveQueued = false
        if (!started || !enabled) return@Runnable
        refreshConnection()
        publishSnapshot()
    }

    private val bootstrapReplay: Runnable = Runnable {
        if (!started || !enabled) return@Runnable
        refreshConnection()
        publishSnapshot()
        scheduleReplay()
    }

    init {
        app = context.applicationContext ?: context
    }

    fun start() {
        runOnMain(
            Runnable {
                if (!started) {
                    lifecycleGeneration++
                    started = true
                    val gateway: AdapterGateway? = AdapterGateway.get(app)
                    usbConnected = gateway!!.usbReady()
                    connectionEpoch = gateway.connectionEpoch()
                    registerStateReceiver()
                }
                applySettingsOnMain()
            }
        )
    }

    fun applySettings() {
        runOnMain(Runnable { this.applySettingsOnMain() })
    }

    fun stop() {
        runOnMain(
            Runnable {
                if (enabled) clearAllWheels()
                enabled = false
                started = false
                lifecycleGeneration++
                cancelReplay()
                unregisterStockReceiver()
                unregisterStateReceiver()
                resetHardware()
                usbConnected = false
                connectionEpoch = -1L
                lastBootstrapElapsed = -1L
            }
        )
    }

    /** Called only by FrameRouter after a checksum-validated USB TPMS payload. */
    fun onUsbFrame(frame: ByteArray?, epoch: Long, receivedElapsed: Long) {
        if (frame == null) return
        val copy: ByteArray? = frame.clone()
        val generation: Long = lifecycleGeneration
        val receivedWall: Long = System.currentTimeMillis()
        handler.post({
            if (!started || generation != lifecycleGeneration) return@post
            refreshConnection()
            if (!usbConnected || epoch != connectionEpoch) return@post
            val mask: Int = TpmsController.observedWheelMask(copy)
            if (mask == 0) return@post
            val next: TpmsState? =
                TpmsController.parseAdapterFrame(copy, hardwareState, receivedWall)
            if (next == null) return@post
            hardwareState = next
            policy.observe(next, mask, receivedElapsed)
            if (enabled) {
                publishSnapshot()
                scheduleReplay()
            }
        })
    }

    private fun applySettingsOnMain() {
        if (!started) return
        val nextEnabled: Boolean = AppSettings.teyesTpmsWidgetForwardEnabled(app)
        refreshConnection()
        if (enabled != nextEnabled) {
            if (!nextEnabled) {
                clearAllWheels()
                enabled = false
                cancelReplay()
                unregisterStockReceiver()
            } else {
                enabled = true
                failureLogged = false
                registerStockReceiver()
                bootstrapWidget(true)
                publishSnapshot()
                scheduleReplay()
            }
            AppLog.line(app, "TEYES TPMS widget pressure forwarding: " + enabled)
        } else if (enabled) {
            publishSnapshot()
            scheduleReplay()
        }
    }

    private fun refreshConnection() {
        val gateway: AdapterGateway? = AdapterGateway.get(app)
        val connected: Boolean = gateway!!.usbReady()
        val epoch: Long = gateway.connectionEpoch()
        if (connected == usbConnected && epoch == connectionEpoch) return
        usbConnected = connected
        connectionEpoch = epoch
        resetHardware()
        handler.removeCallbacks(replayTick)
        handler.removeCallbacks(reactiveReplay)
        reactiveQueued = false
        if (enabled) clearAllWheels()
    }

    private fun resetHardware() {
        hardwareState = TpmsState.empty()
        policy.reset()
    }

    private fun publishSnapshot() {
        if (!started || !enabled) return
        val now: Long = SystemClock.elapsedRealtime()
        if (policy.hasFreshData(now)) bootstrapWidget(false)
        val alerts: Boolean = AppSettings.tpmsAlertsEnabled(app)
        val low: Int = AppSettings.tpmsLowPressureKpa(app)
        val high: Int = AppSettings.tpmsHighPressureKpa(app)
        for (wheel in 0..<TpmsState.WHEEL_COUNT) {
            sendWheel(
                if (usbConnected) policy.project(wheel, now, alerts, low, high)
                else TeyesTpmsWidgetPolicy.WheelValue.unknown(wheel)
            )
        }
    }

    private fun clearAllWheels() {
        for (wheel in 0..<TpmsState.WHEEL_COUNT) {
            sendWheel(TeyesTpmsWidgetPolicy.WheelValue.unknown(wheel))
        }
    }

    private fun sendWheel(value: TeyesTpmsWidgetPolicy.WheelValue) {
        val intent: Intent = Intent(TeyesTpmsWidgetForwarder.ACTION_TIRE_DATA)
        // The receiver is dynamic. Package targeting also keeps our exported observer
        // from receiving our own writes and prevents a reactive replay feedback loop.
        intent.setPackage(TeyesTpmsWidgetForwarder.WIDGET_PACKAGE)
        intent.putExtra("which", value.which)
        intent.putExtra("press", value.pressure)
        intent.putExtra("temp", value.temperature)
        intent.putExtra("press_unit", value.pressureUnit)
        intent.putExtra("temp_unit", value.temperatureUnit)
        intent.putExtra("alert_flag", value.alert)
        intent.putExtra("warning_state", value.warningState)
        try {
            app.sendBroadcast(intent)
        } catch (e: RuntimeException) {
            logFailure(e)
        }
    }

    private fun scheduleReplay() {
        handler.removeCallbacks(replayTick)
        if (!started || !enabled || !usbConnected) return
        val expiry: Long = policy.nextExpiryDelayMs(SystemClock.elapsedRealtime())
        if (expiry < 0L) return
        handler.postDelayed(replayTick, Math.min(TeyesTpmsWidgetForwarder.REPLAY_MS, expiry))
    }

    private fun scheduleReactiveReplay() {
        if (!started || !enabled || reactiveQueued) return
        reactiveQueued = true
        handler.postDelayed(reactiveReplay, TeyesTpmsWidgetForwarder.REACTIVE_REPLAY_MS)
    }

    private fun cancelReplay() {
        handler.removeCallbacks(replayTick)
        handler.removeCallbacks(reactiveReplay)
        handler.removeCallbacks(bootstrapReplay)
        reactiveQueued = false
    }

    private fun bootstrapWidget(force: Boolean) {
        val now: Long = SystemClock.elapsedRealtime()
        if (
            !force &&
                lastBootstrapElapsed >= 0L &&
                now - lastBootstrapElapsed < TeyesTpmsWidgetForwarder.BOOTSTRAP_INTERVAL_MS
        )
            return
        lastBootstrapElapsed = now
        val intent: Intent = Intent(TeyesTpmsWidgetForwarder.ACTION_START_WIDGET)
        intent.setComponent(
            ComponentName(
                TeyesTpmsWidgetForwarder.WIDGET_PACKAGE,
                TeyesTpmsWidgetForwarder.WIDGET_BOOT_RECEIVER,
            )
        )
        try {
            // Original BootReceiver checks that appWidgetIds already exist and that
            // its service is not running. Do not create widgets or start/bind TPMS here.
            app.sendBroadcast(intent)
            handler.removeCallbacks(bootstrapReplay)
            handler.postDelayed(bootstrapReplay, TeyesTpmsWidgetForwarder.BOOTSTRAP_REPLAY_MS)
        } catch (e: RuntimeException) {
            logFailure(e)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerStateReceiver() {
        if (stateReceiver != null) return
        stateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (started) refreshConnection()
                }
            }
        val filter: IntentFilter = IntentFilter(AppIds.ACTION_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(stateReceiver, filter)
            }
        } catch (e: RuntimeException) {
            stateReceiver = null
            logFailure(e)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerStockReceiver() {
        if (stockReceiver != null) return
        stockReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // Stock status messages can overwrite the widget. Observe only their
                    // occurrence: no vendor payload ever becomes a KIA source value.
                    scheduleReactiveReplay()
                }
            }
        val filter: IntentFilter = IntentFilter(TeyesTpmsWidgetForwarder.ACTION_TIRE_DATA)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(stockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(stockReceiver, filter)
            }
        } catch (e: RuntimeException) {
            stockReceiver = null
            logFailure(e)
        }
    }

    private fun unregisterStockReceiver() {
        if (stockReceiver == null) return
        try {
            app.unregisterReceiver(stockReceiver)
        } catch (ignored: IllegalArgumentException) {}
        stockReceiver = null
    }

    private fun unregisterStateReceiver() {
        if (stateReceiver == null) return
        try {
            app.unregisterReceiver(stateReceiver)
        } catch (ignored: IllegalArgumentException) {}
        stateReceiver = null
    }

    private fun logFailure(exception: RuntimeException) {
        if (failureLogged) return
        failureLogged = true
        AppLog.line(
            app,
            "TEYES TPMS widget forwarding failed " + exception.javaClass.getSimpleName(),
        )
    }

    private fun runOnMain(action: Runnable) {
        if (Looper.myLooper() === Looper.getMainLooper()) action.run() else handler.post(action)
    }

    companion object {
        private val WIDGET_PACKAGE: String = "com.teyes.tpms.widget"
        private val WIDGET_BOOT_RECEIVER: String = "com.teyes.tpms.receiver.BootReceiver"
        private val ACTION_START_WIDGET: String = "com.teyes.widget.action.START_SERVICE"
        private val ACTION_TIRE_DATA: String = "com.spd.tpms.teyes.service.TIREDATARECEIVER"
        private const val REPLAY_MS: Long = 5000L
        private const val REACTIVE_REPLAY_MS: Long = 200L
        private const val BOOTSTRAP_INTERVAL_MS: Long = 30000L
        private const val BOOTSTRAP_REPLAY_MS: Long = 1000L

        private var instance: TeyesTpmsWidgetForwarder? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): TeyesTpmsWidgetForwarder {
            if (TeyesTpmsWidgetForwarder.instance == null)
                TeyesTpmsWidgetForwarder.instance = TeyesTpmsWidgetForwarder(context)
            return TeyesTpmsWidgetForwarder.instance!!
        }
    }
}
