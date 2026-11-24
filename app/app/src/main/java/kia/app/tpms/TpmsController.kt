package kia.app.tpms

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kia.app.core.StateStore
import kia.app.core.model.TpmsState
import kia.app.protocol.adapter.AdapterGateway
import kia.app.protocol.adapter.AdapterProtocol

class TpmsController private constructor(context: Context) {
    private val app: Context
    private val lock: Object = Object()
    private var worker: Handler? = null
    private var polling: Boolean = false
    private var pollingMode: TpmsPollingMode = TpmsPollingMode.BACKGROUND
    private var generation: Long = 0
    private var connectedEpoch: Long = 0
    private var lastPollStartedAt: Long = -1L
    private var nextPollAt: Long = Long.MAX_VALUE
    private var forcedPollAt: Long = Long.MAX_VALUE
    private var pollTick: Runnable? = null

    init {
        this.app = context.applicationContext ?: context
    }

    fun startPolling() {
        synchronized(lock) {
            if (polling) return
            if (worker == null) {
                // Keep one serialized worker for this process, including service restarts.
                // A slow write from an old session cannot overlap a new session's write.
                val thread: HandlerThread = HandlerThread("KiaTpmsPolling")
                thread.start()
                worker = Handler(thread.getLooper())
            }
            polling = true
            val session: Long = ++generation
            pollTick = Runnable { pollOnWorker(session) }
            lastPollStartedAt = -1L
            nextPollAt = Long.MAX_VALUE
            forcedPollAt = SystemClock.elapsedRealtime()
            scheduleNextLocked()
        }
    }

    fun stopPolling() {
        synchronized(lock) {
            polling = false
            generation++
            if (worker != null && pollTick != null) worker!!.removeCallbacks(pollTick!!)
            pollTick = null
            nextPollAt = Long.MAX_VALUE
            forcedPollAt = Long.MAX_VALUE
        }
    }

    fun setForegroundActive(active: Boolean) {
        setForegroundActive(active, false)
    }

    /**
     * Compatibility bridge for existing callers. New UI code should use [.setPollingMode] so screen
     * size cannot accidentally turn a full dashboard into a slow widget.
     */
    fun setForegroundActive(active: Boolean, widgetMode: Boolean) {
        setPollingMode(
            if (!active) TpmsPollingMode.BACKGROUND
            else
                (if (widgetMode) TpmsPollingMode.EMBEDDED_WIDGET
                else TpmsPollingMode.FULL_DASHBOARD)
        )
    }

    fun setPollingMode(mode: TpmsPollingMode?) {
        synchronized(lock) {
            val clean: TpmsPollingMode = if (mode == null) TpmsPollingMode.BACKGROUND else mode
            if (pollingMode == clean) return
            pollingMode = clean
            if (polling) scheduleImmediateLocked()
        }
    }

    fun pollingMode(): TpmsPollingMode? {
        synchronized(lock) {
            return pollingMode
        }
    }

    /** Request a poll on the worker; callers never perform USB I/O on their thread. */
    fun requestNow() {
        synchronized(lock) { if (polling) scheduleImmediateLocked() }
    }

    /** Called once a new USB connection epoch becomes usable. */
    fun onAdapterConnected(connectionEpoch: Long) {
        synchronized(lock) {
            if (connectionEpoch <= 0L || connectionEpoch <= connectedEpoch) return
            connectedEpoch = connectionEpoch
            if (polling) scheduleImmediateLocked()
        }
    }

    /** Re-evaluate thresholds and alert enablement without waiting for another packet. */
    fun onSettingsChanged() {
        synchronized(lock) {
            if (!polling) return
            TpmsAlertController.get(app).apply(StateStore.tpms())
            scheduleNextLocked()
        }
    }

    /** Returns whether the packet contains recognized wheel slots, even if all are empty. */
    fun handleAdapterFrame(frame: ByteArray?): Boolean {
        synchronized(lock) {
            val now: Long = System.currentTimeMillis()
            val next: TpmsState? = TpmsController.parseAdapterFrame(frame, StateStore.tpms(), now)
            if (next != null) {
                // Keep this synchronous: FrameRouter forwards the newly stored wheel
                // values to the native TEYES widget immediately after this method.
                StateStore.setTpms(app, next)
                if (polling) {
                    TpmsAlertController.get(app).apply(next)
                    scheduleNextLocked()
                }
            }
        }
        return TpmsController.observedWheelMask(frame) != 0
    }

    private fun pollOnWorker(session: Long) {
        synchronized(lock) {
            if (!polling || session != generation) return
            val now: Long = SystemClock.elapsedRealtime()
            // A callback already dequeued before a newer reschedule must not
            // consume that newer deadline or create a duplicate timer.
            if (now < nextPollAt) return
            worker!!.removeCallbacks(pollTick!!)
            nextPollAt = Long.MAX_VALUE
            forcedPollAt = Long.MAX_VALUE
            lastPollStartedAt = now
            TpmsAlertController.get(app).apply(StateStore.tpms())
        }
        try {
            AdapterGateway.get(app).pollTpms()
        } finally {
            synchronized(lock) { if (polling && session == generation) scheduleNextLocked() }
        }
    }

    private fun scheduleImmediateLocked() {
        forcedPollAt = Math.min(forcedPollAt, SystemClock.elapsedRealtime())
        scheduleNextLocked()
    }

    private fun scheduleNextLocked() {
        if (!polling || pollTick == null) return
        val now: Long = SystemClock.elapsedRealtime()
        val regular: Long =
            if (lastPollStartedAt < 0L) now else lastPollStartedAt + pollIntervalMs()
        val deadline: Long = Math.max(now, Math.min(regular, forcedPollAt))
        if (deadline == nextPollAt) return
        worker!!.removeCallbacks(pollTick!!)
        nextPollAt = deadline
        worker!!.postDelayed(pollTick!!, Math.max(0L, deadline - now))
        // Packet arrivals do not restart the interval. Only the last poll, the
        // current severity/mode, or an explicit immediate request sets its end.
    }

    private fun pollIntervalMs(): Long {
        val severity: Int = maxSeverity(StateStore.tpms())
        if (severity == TpmsAlertController.SEVERITY_CRITICAL)
            return TpmsController.CRITICAL_POLL_MS
        if (severity == TpmsAlertController.SEVERITY_WARNING) return TpmsController.WARNING_POLL_MS
        return pollingMode.intervalMs()
    }

    private fun maxSeverity(state: TpmsState?): Int {
        if (state == null || state.known == null) return TpmsAlertController.SEVERITY_NONE
        var max: Int = TpmsAlertController.SEVERITY_NONE
        for (wheel in 0..<TpmsState.WHEEL_COUNT) {
            val severity: Int = TpmsAlertController.warningSeverity(app, state, wheel)
            if (severity > max) max = severity
        }
        return max
    }

    companion object {
        private const val WARNING_POLL_MS: Long = 5000L
        private const val CRITICAL_POLL_MS: Long = 1000L
        // Adapter 0x51 payload order is FR, FL, RR, RL.
        private val NATIVE_TPMS_WHEEL_ORDER: IntArray =
            intArrayOf(
                TpmsState.WHEEL_FR,
                TpmsState.WHEEL_FL,
                TpmsState.WHEEL_RR,
                TpmsState.WHEEL_RL,
            )

        private var instance: TpmsController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): TpmsController {
            if (TpmsController.instance == null) TpmsController.instance = TpmsController(context)
            return TpmsController.instance!!
        }

        @JvmStatic
        fun observedWheelMask(frame: ByteArray?): Int {
            val len: Int =
                if (frame == null || frame.size < 6) 0
                else Math.min(frame.size, frame.get(3).toInt() and 0xff)
            val id: Int = TpmsController.u8(frame, 4)
            if (id == AdapterProtocol.CMD_TPMS && len >= 14) return 0x0f
            if (id == AdapterProtocol.CMD_TPMS_LEGACY && len >= 23)
                return TpmsController.u8(frame, 5) and 0x0f
            return 0
        }

        @JvmStatic
        fun parseAdapterFrame(
            frame: ByteArray?,
            current: TpmsState?,
            observedAt: Long,
        ): TpmsState? {
            val len: Int =
                if (frame == null || frame.size < 6) 0
                else Math.min(frame.size, frame.get(3).toInt() and 0xff)
            val id: Int = TpmsController.u8(frame, 4)
            if (id == AdapterProtocol.CMD_TPMS && len >= 14) {
                return TpmsController.parseNativeTpms(frame, len, current, observedAt)
            }
            if (id != AdapterProtocol.CMD_TPMS_LEGACY) return null
            if (len < 23) return null
            val mask: Int = TpmsController.u8(frame, 5)
            var next: TpmsState? = if (current == null) TpmsState.empty() else current
            var changed: Boolean = false
            for (wheel in 0..<TpmsState.WHEEL_COUNT) {
                if ((mask and (1 shl wheel)) == 0) continue
                val offset: Int = 6 + wheel * 4
                if (offset + 3 >= len - 1) continue
                val pressureKpa: Int = TpmsController.u16(frame, offset)
                val temperatureC: Int = TpmsController.s8(frame, offset + 2)
                val flags: Int = TpmsController.u8(frame, offset + 3)
                next =
                    next!!.withWheelAt(
                        wheel,
                        pressureKpa,
                        temperatureC,
                        flags,
                        "adapter",
                        observedAt,
                    )
                changed = true
            }
            return if (changed) next else null
        }

        private fun parseNativeTpms(
            frame: ByteArray?,
            len: Int,
            current: TpmsState?,
            observedAt: Long,
        ): TpmsState? {
            if (len < 14) return null
            var next: TpmsState? = if (current == null) TpmsState.empty() else current
            var changed: Boolean = false
            for (index in 0..<TpmsState.WHEEL_COUNT) {
                val wheel: Int = TpmsController.NATIVE_TPMS_WHEEL_ORDER.get(index)
                val pressureRaw: Int = TpmsController.u8(frame, 5 + index)
                if (pressureRaw <= 0) {
                    val stale: TpmsState? = next!!.withWheelStale(wheel, "adapter 0x51", observedAt)
                    if (stale != next) {
                        next = stale
                        changed = true
                    }
                    continue
                }
                val tempRaw: Int = TpmsController.u8(frame, 9 + index)
                val psi: Float = pressureRaw * 0.25f
                val pressureKpa: Int = Math.round(psi * 6.894757f)
                val temperatureC: Int = tempRaw - 55
                next =
                    next!!.withWheelAt(
                        wheel,
                        pressureKpa,
                        temperatureC,
                        0,
                        "adapter 0x51",
                        observedAt,
                    )
                changed = true
            }
            return if (changed) next else null
        }

        private fun u8(data: ByteArray?, offset: Int): Int {
            return if (data == null || offset < 0 || offset >= data.size) 0
            else data.get(offset).toInt() and 0xff
        }

        private fun u16(data: ByteArray?, offset: Int): Int {
            return TpmsController.u8(data, offset) or (TpmsController.u8(data, offset + 1) shl 8)
        }

        private fun s8(data: ByteArray?, offset: Int): Int {
            val value: Int = TpmsController.u8(data, offset)
            return if (value >= 0x80) value - 0x100 else value
        }
    }
}
