package kia.app.media.domain

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.CallState
import kia.app.core.model.MediaState
import kia.app.core.settings.AppSettings
import kia.app.media.cluster.MediaClusterSender

class CallFeature private constructor(context: Context) {
    private val app: Context
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val clusterSender: MediaClusterSender
    private val synchronizedMediaSender: MediaClusterSender?
    private var pendingEndReason: String? = ""
    private val ticker: Runnable =
        object : Runnable {
            override fun run() {
                tick()
            }
        }
    private val endHold: Runnable =
        object : Runnable {
            override fun run() {
                finishPendingEnd()
            }
        }

    init {
        this.app = context.applicationContext ?: context
        // Keep the proven TEYES call sender isolated exactly as before. Only Android/UART share
        // the synchronized media coordinator so delayed metadata can be cancelled during calls.
        this.clusterSender = MediaClusterSender(app)
        this.synchronizedMediaSender = MediaClusterSender.get(app)
    }

    @kotlin.jvm.Synchronized
    fun reportActive(name: String?, phone: String?, source: String?) {
        if (!AppSettings.callEnabled(app)) return
        handler.removeCallbacks(endHold)
        pendingEndReason = ""
        val current: CallState? = StateStore.call()
        val startedAt: Long =
            if (current!!.active) current.startedAt else System.currentTimeMillis()
        val next: CallState =
            CallState.active(
                name,
                phone,
                CallFeature.firstNonEmpty(source, "Bluetooth audio"),
                startedAt,
            )
        StateStore.setCall(app, next)
        synchronizedMediaSender!!.suspendForCall()
        clusterSender.sendCall(next)
        scheduleTick()
        AppLog.line(app, "Call RX active: " + next.displayName() + " | " + next.subtitle())
    }

    @kotlin.jvm.Synchronized
    fun reportEnded(reason: String?) {
        if (!AppSettings.callEnabled(app)) {
            stop()
            return
        }
        if (!StateStore.call().active) return
        pendingEndReason = CallFeature.clean(reason)
        handler.removeCallbacks(endHold)
        handler.postDelayed(endHold, CallFeature.END_HOLD_MS)
        AppLog.line(app, "Call RX ending hold: " + pendingEndReason)
    }

    @kotlin.jvm.Synchronized
    private fun finishPendingEnd() {
        val previous: CallState? = StateStore.call()
        if (previous == null || !previous.active) return
        val reason: String? = pendingEndReason
        pendingEndReason = ""
        handler.removeCallbacks(ticker)
        StateStore.setCall(app, CallState.empty())
        clusterSender.clearCall(previous)
        val media: MediaState? = StateStore.media()
        if (
            media != null &&
                (!TextUtils.isEmpty(media.source) ||
                    !TextUtils.isEmpty(media.artist) ||
                    !TextUtils.isEmpty(media.title))
        ) {
            MediaFeature.get(app).resendCurrent("call ended")
        }
        AppLog.line(app, "Call RX ended: " + CallFeature.clean(reason))
    }

    @kotlin.jvm.Synchronized
    fun tick() {
        if (!AppSettings.callEnabled(app)) {
            stop()
            return
        }
        val current: CallState? = StateStore.call()
        if (current == null || !current.active) {
            handler.removeCallbacks(ticker)
            return
        }
        StateStore.setCall(
            app,
            CallState.active(current.name, current.phone, current.source, current.startedAt),
        )
        clusterSender.sendCall(StateStore.call())
        scheduleTick()
    }

    private fun scheduleTick() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, CallFeature.TICK_MS)
    }

    @kotlin.jvm.Synchronized
    fun stop() {
        val wasActive: Boolean = StateStore.call().active
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(endHold)
        pendingEndReason = ""
        StateStore.setCall(app, CallState.empty())
        if (wasActive && AppSettings.universalMediaProfile(app)) {
            MediaFeature.get(app).resendCurrent("call feature disabled")
        }
        AppLog.line(app, "Call RX disabled")
    }

    companion object {
        private const val TICK_MS: Long = 1000L
        private const val END_HOLD_MS: Long = 1800L
        private var instance: CallFeature? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): CallFeature {
            if (CallFeature.instance == null) CallFeature.instance = CallFeature(context)
            return CallFeature.instance!!
        }

        private fun firstNonEmpty(first: String?, second: String?): String? {
            return if (TextUtils.isEmpty(first)) second else first
        }

        private fun clean(value: String?): String? {
            if (value == null) return ""
            return value.replace('\n', ' ').replace('\r', ' ').trim()
        }
    }
}
