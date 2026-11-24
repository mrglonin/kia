package kia.app.rcta

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.RctaState

class RctaController private constructor(context: Context) {
    private val app: Context
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val freshness: RctaFreshnessPolicy =
        RctaFreshnessPolicy(RctaController.ACTIVE_FRAME_TIMEOUT_MS)
    private val watchdog: Runnable = Runnable { this.handleWatchdog() }

    init {
        this.app = context.applicationContext ?: context
    }

    @kotlin.jvm.Synchronized
    fun handleFrame(frame: ByteArray?) {
        if (frame == null || frame.size < 7) return
        val len: Int =
            if (frame.size > 3) Math.min(frame.size, frame.get(3).toInt() and 0xff) else frame.size
        if (len < 7) return
        val left: Boolean = (frame.get(5).toInt() and 0xff) != 0
        val right: Boolean = (frame.get(6).toInt() and 0xff) != 0
        val active: Boolean = left || right

        // Identical RCTA frames are heartbeats, not duplicates. Observe and re-arm before the
        // state equality check so a live warning can never expire merely because it did not
        // change sides.
        freshness.observeFrame(active, SystemClock.elapsedRealtime())
        if (active) {
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, RctaController.ACTIVE_FRAME_TIMEOUT_MS)
        } else {
            handler.removeCallbacks(watchdog)
        }

        val next: RctaState = RctaState(left, right, System.currentTimeMillis())
        val current: RctaState? = StateStore.rcta()
        val changed: Boolean = current == null || current.left != left || current.right != right
        // Keep updatedAt truthful for health/diagnostic views even when the payload is unchanged.
        StateStore.setRcta(app, next)
        if (!changed) return

        RctaOverlayController.get(app).apply()
        AppLog.line(app, "RCTA: " + next.summary())
    }

    @kotlin.jvm.Synchronized
    private fun handleWatchdog() {
        val remainingMs: Long = freshness.remainingMs(SystemClock.elapsedRealtime())
        if (remainingMs > 0L) {
            handler.postDelayed(watchdog, remainingMs)
            return
        }
        if (!freshness.hasActiveObservation()) return
        freshness.clear()

        val current: RctaState? = StateStore.rcta()
        if (current == null || !current.active()) return

        val clear: RctaState = RctaState(false, false, System.currentTimeMillis())
        StateStore.setRcta(app, clear)
        RctaOverlayController.get(app).apply()
        AppLog.line(
            app,
            ("RCTA: clear (нет свежих кадров " + RctaController.ACTIVE_FRAME_TIMEOUT_MS + " мс)"),
        )
    }

    companion object {
        /**
         * RCTA is delivered as a repeating adapter status frame. Five seconds tolerates a long
         * scheduling/USB pause while still bounding a lost clear frame and its looping warning
         * sound.
         */
        const val ACTIVE_FRAME_TIMEOUT_MS: Long = 5000L

        private var instance: RctaController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): RctaController {
            if (RctaController.instance == null) RctaController.instance = RctaController(context)
            return RctaController.instance!!
        }
    }
}
