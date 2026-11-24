package kia.app.diagnostics

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kia.app.core.AppLog
import kia.app.protocol.adapter.AdapterGateway
import kia.app.tpms.TpmsController

/** Polls adapter health away from the main looper and recovers silent half-open USB sessions. */
class HealthMonitor(context: Context) {
    private val app: Context
    @kotlin.concurrent.Volatile private var running: Boolean = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var sessionGeneration: Long = 0
    private var tick: Runnable? = null

    private class SessionState {
        @JvmField var lastHealthAt: Long = 0
        @JvmField var observedConnectionEpoch: Long = -1L
        @JvmField var observedLastRxAt: Long = 0
        @JvmField var silentProbeBursts: Int = 0
    }

    init {
        this.app = context.applicationContext ?: context
    }

    @kotlin.jvm.Synchronized
    fun start() {
        if (running) return
        val nextThread: HandlerThread = HandlerThread("KiaAdapterHealth")
        nextThread.start()
        thread = nextThread
        handler = Handler(nextThread.getLooper())
        val generation: Long = ++sessionGeneration
        val target: Handler = handler!!
        tick = createTick(generation, target, SessionState())
        running = true
        handler!!.post(tick!!)
    }

    @kotlin.jvm.Synchronized
    fun stop() {
        running = false
        sessionGeneration++
        val oldHandler = handler
        val oldThread = thread
        handler = null
        thread = null
        tick = null
        if (oldHandler != null) oldHandler.removeCallbacksAndMessages(null)
        if (oldThread != null) oldThread.quitSafely()
    }

    private fun runTick(session: SessionState): Long {
        val gateway = AdapterGateway.get(app)
        if (gateway!!.exclusiveUpdateMode()) return HealthMonitor.POLL_MS
        if (!gateway.usbReady()) {
            // connect() is asynchronous and coalesced by UsbTransport.
            gateway.start()
            return HealthMonitor.DISCONNECTED_RETRY_MS
        }

        val now: Long = SystemClock.elapsedRealtime()
        val connectionEpoch: Long = gateway.connectionEpoch()
        val lastRxAt: Long = gateway.lastRxAtElapsedRealtime()
        if (connectionEpoch != session.observedConnectionEpoch) {
            session.observedConnectionEpoch = connectionEpoch
            session.observedLastRxAt = lastRxAt
            session.silentProbeBursts = 0
            session.lastHealthAt = 0L
            TpmsController.get(app).onAdapterConnected(connectionEpoch)
        } else if (lastRxAt > 0L && lastRxAt != session.observedLastRxAt) {
            session.observedLastRxAt = lastRxAt
            session.silentProbeBursts = 0
        }
        if (
            UsbConnectionHealthPolicy.shouldReconnect(
                true,
                gateway.connectedAtElapsedRealtime(),
                lastRxAt,
                now,
                HealthMonitor.RX_STARTUP_GRACE_MS,
                HealthMonitor.RX_STALE_MS,
                session.silentProbeBursts,
                HealthMonitor.REQUIRED_SILENT_PROBE_BURSTS,
            )
        ) {
            AppLog.line(app, "Health monitor: USB открыт, но RX устарел; переподключение")
            gateway.reconnect("нет RX от адаптера")
            return HealthMonitor.DISCONNECTED_RETRY_MS
        }

        gateway.pollVehicleSnapshot()
        if (
            session.lastHealthAt <= 0L ||
                now < session.lastHealthAt ||
                now - session.lastHealthAt > HealthMonitor.INFO_POLL_MS
        ) {
            session.lastHealthAt = now
            gateway.requestHealthQuiet()
            session.silentProbeBursts++
        }
        return HealthMonitor.POLL_MS
    }

    private fun createTick(generation: Long, target: Handler, session: SessionState): Runnable {
        return object : Runnable {
            override fun run() {
                if (!sessionCurrent(generation, target, this)) return
                var nextMs: Long = HealthMonitor.POLL_MS
                try {
                    nextMs = runTick(session)
                } catch (e: RuntimeException) {
                    AppLog.line(app, "Health monitor error " + e.javaClass.getSimpleName())
                    nextMs = HealthMonitor.DISCONNECTED_RETRY_MS
                }
                scheduleNext(generation, target, this, nextMs)
            }
        }
    }

    @kotlin.jvm.Synchronized
    private fun sessionCurrent(generation: Long, target: Handler?, task: Runnable?): Boolean {
        return HealthMonitorSessionPolicy.current(
            running,
            sessionGeneration,
            generation,
            handler === target,
            tick === task,
        )
    }

    private fun scheduleNext(generation: Long, target: Handler, task: Runnable?, delayMs: Long) {
        synchronized(this) {
            if (
                !HealthMonitorSessionPolicy.current(
                    running,
                    sessionGeneration,
                    generation,
                    handler === target,
                    tick === task,
                )
            ) {
                return
            }
        }
        target.postDelayed(task!!, Math.max(1L, delayMs))
    }

    companion object {
        private const val POLL_MS: Long = 750L
        private const val INFO_POLL_MS: Long = 5000L
        private const val DISCONNECTED_RETRY_MS: Long = 2000L
        private const val RX_STARTUP_GRACE_MS: Long = 20000L
        private const val RX_STALE_MS: Long = 15000L
        private const val REQUIRED_SILENT_PROBE_BURSTS: Int = 3
    }
}
