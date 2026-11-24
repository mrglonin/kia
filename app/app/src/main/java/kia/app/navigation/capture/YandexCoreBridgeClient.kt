package kia.app.navigation.capture

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.TextUtils
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.Locale
import kia.app.core.AppLog
import kia.app.core.settings.AppSettings
import kia.app.navigation.domain.ManeuverProgressRolloverPolicy
import kia.app.navigation.domain.NavigationFeature
import kia.app.navigation.domain.YandexSpeedSnapshotPolicy

class YandexCoreBridgeClient(context: Context) {
    private class SnapshotApplyResult(delayMs: Long, accepted: Boolean) {
        @JvmField val delayMs: Long
        @JvmField val accepted: Boolean

        init {
            this.delayMs = delayMs
            this.accepted = accepted
        }
    }

    private class MainManeuverSnapshot(
        mainEvent: String?,
        maneuver: String?,
        provenance: String?,
        distance: String?,
        annotationManeuver: String?,
        annotationDistance: String?,
        notificationManeuver: String?,
        notificationDistance: String?,
        roundaboutExit: String?,
    ) {
        @JvmField val mainEvent: String?
        @JvmField val maneuver: String?
        @JvmField val provenance: String?
        @JvmField val distance: String?
        @JvmField val annotationManeuver: String?
        @JvmField val annotationDistance: String?
        @JvmField val notificationManeuver: String?
        @JvmField val notificationDistance: String?
        @JvmField val roundaboutExit: String?

        init {
            this.mainEvent = mainEvent
            this.maneuver = maneuver
            this.provenance = provenance
            this.distance = distance
            this.annotationManeuver = annotationManeuver
            this.annotationDistance = annotationDistance
            this.notificationManeuver = notificationManeuver
            this.notificationDistance = notificationDistance
            this.roundaboutExit = roundaboutExit
        }
    }

    private val app: Context?
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var running: Boolean = false
    private val snapshotOrderGuard: YandexSnapshotOrderGuard = YandexSnapshotOrderGuard()
    private val sourceSampleGate: YandexSourceSampleGate = YandexSourceSampleGate()
    private var lastMissingProviderLogAt: Long = 0
    private var lastErrorLogAt: Long = 0
    private var lastState: String? = ""
    private var lastSignature: String? = ""
    private var lastSpeedSignature: String = ""
    private var lastSpeedSnapshotSentAt: Long = 0
    private var lastSourceSampleLogAt: Long = -1L
    private var lastManeuverDistanceKey: String? = ""
    private var lastManeuverProjectionLogKey: String = ""
    private var lastManeuverProjectionLogAt: Long = 0
    private var lastManeuverDistanceMeters: Long = -1L
    private var lastManeuverRouteRemainingMeters: Long = -1L
    private var maneuverDistanceEventBaseMeters: Long = -1L
    private var maneuverDistanceEventClosestMeters: Long = -1L
    private var pendingManeuverRolloverKey: String? = ""
    private var pendingManeuverRolloverClosestMeters: Long = -1L
    private var pendingManeuverRolloverMeters: Long = -1L
    private var pendingManeuverRolloverRouteRemainingMeters: Long = -1L
    private var pendingManeuverRolloverAtElapsed: Long = -1L
    private var maneuverDistanceRolloverForSnapshot: Boolean = false
    private var lastLiveRouteMetricsAt: Long = -1L
    private var lastLiveRouteStartedAt: Long = -1L
    private var lastLiveRouteRemainingMeters: Long = -1L
    private var lastLiveRouteId: String? = ""
    private var lastBroadcastSnapshotAt: Long = -1L
    private val broadcastLock: Object = Object()
    private val pendingBroadcastSnapshots: ArrayDeque<Bundle?> = ArrayDeque()
    private var broadcastApplyPosted: Boolean = false
    private var lastBroadcastApplyAt: Long = -1L
    private var lastHeartbeatBroadcastLogAt: Long = -1L
    private var lastAppliedCoalescingIdentity: String? = ""
    private var lastAppliedCoalescingManeuverMeters: Long = -1L
    private var lastBackgroundSuspensionIdentity: String = ""

    private val poller: Runnable =
        object : Runnable {
            override fun run() {
                if (!running || handler == null) return
                val delay: Long = pollOnce()
                if (running && handler != null) handler!!.postDelayed(this, delay)
            }
        }

    private val broadcastDrainer: Runnable =
        object : Runnable {
            override fun run() {
                drainBroadcastSnapshot()
            }
        }

    init {
        this.app = context.getApplicationContext()
        synchronized(YandexCoreBridgeClient::class.java) { YandexCoreBridgeClient.shared = this }
    }

    @kotlin.jvm.Synchronized
    fun start() {
        if (running) return
        resetBridgeSessionState()
        running = true
        thread = HandlerThread("KiaYandexCoreBridge")
        thread!!.start()
        handler = Handler(thread!!.getLooper())
        snapshotOrderGuard.reset()
        handler!!.post(poller)
        AppLog.line(app, "Yandex Core Bridge: client started")
    }

    @kotlin.jvm.Synchronized
    fun stop() {
        running = false
        if (handler != null) handler!!.removeCallbacksAndMessages(null)
        handler = null
        if (thread != null) thread!!.quitSafely()
        thread = null
        snapshotOrderGuard.reset()
        synchronized(broadcastLock) {
            pendingBroadcastSnapshots.clear()
            broadcastApplyPosted = false
            lastBroadcastApplyAt = -1L
            lastAppliedCoalescingIdentity = ""
            lastAppliedCoalescingManeuverMeters = -1L
        }
        resetBridgeSessionState()
        AppLog.line(app, "Yandex Core Bridge: client stopped")
    }

    fun acceptBroadcastSnapshot(input: Bundle?) {
        if (input == null || input.isEmpty()) return
        if (!sourceIngressAllowed()) return
        try {
            val snapshot: Bundle = Bundle(input)
            YandexCoreBridgeClient.normalizeSnapshotEnvelope(snapshot)
            enqueueBroadcastSnapshot(snapshot)
        } catch (e: Exception) {
            logError("v2_broadcast", e)
        }
    }

    private fun enqueueBroadcastSnapshot(snapshot: Bundle) {
        val target: Handler? = handler
        if (target == null) {
            applyBroadcastSnapshot(snapshot)
            return
        }
        val lifecyclePriority: Boolean = YandexCoreBridgeClient.priorityBroadcastSnapshot(snapshot)
        val incomingIdentity = YandexCoreBridgeClient.coalescingIdentity(snapshot)
        synchronized(broadcastLock) {
            val tail: Bundle? = pendingBroadcastSnapshots.peekLast()
            val tailIdentity = YandexCoreBridgeClient.coalescingIdentity(tail)
            val referenceIdentity =
                if (tail == null) lastAppliedCoalescingIdentity else tailIdentity
            val referenceManeuverMeters: Long =
                if (tail == null) lastAppliedCoalescingManeuverMeters
                else YandexCoreBridgeClient.coalescingManeuverMeters(tail)
            val incomingManeuverMeters: Long =
                YandexCoreBridgeClient.coalescingManeuverMeters(snapshot)
            val distanceTransition: Boolean =
                YandexSnapshotCoalescingPolicy.isForwardManeuverDistanceTransition(
                    referenceManeuverMeters,
                    incomingManeuverMeters,
                )
            if (distanceTransition) {
                snapshot.putBoolean(YandexCoreBridgeClient.INTERNAL_DISTANCE_TRANSITION, true)
            }
            val semanticTransition: Boolean =
                YandexSnapshotCoalescingPolicy.isTransition(referenceIdentity, incomingIdentity) ||
                    distanceTransition
            if (lifecyclePriority) {
                // Keep producer order intact and retain queued route/micro
                // transitions until the order guard has validated this packet.
                if (!makePendingBroadcastRoom(snapshot, true, semanticTransition)) return
                pendingBroadcastSnapshots.addLast(snapshot)
            } else {
                if (
                    tail != null &&
                        !YandexCoreBridgeClient.bool(
                            tail,
                            YandexCoreBridgeClient.INTERNAL_DISTANCE_TRANSITION,
                            false,
                        ) &&
                        YandexSnapshotCoalescingPolicy.canReplaceTail(
                            YandexCoreBridgeClient.priorityBroadcastSnapshot(tail),
                            tailIdentity,
                            incomingIdentity,
                        )
                ) {
                    pendingBroadcastSnapshots.removeLast()
                }
                if (!makePendingBroadcastRoom(snapshot, false, semanticTransition)) return
                pendingBroadcastSnapshots.addLast(snapshot)
            }
            if (lifecyclePriority || semanticTransition) {
                broadcastApplyPosted = true
                target.removeCallbacks(broadcastDrainer)
                target.post(broadcastDrainer)
                return
            }
            if (broadcastApplyPosted) return
            broadcastApplyPosted = true
            val delay: Long = broadcastApplyDelayMs()
            target.postDelayed(broadcastDrainer, delay)
        }
    }

    private fun makePendingBroadcastRoom(
        incoming: Bundle?,
        incomingLifecyclePriority: Boolean,
        incomingSemanticTransition: Boolean,
    ): Boolean {
        if (pendingBroadcastSnapshots.size < YandexCoreBridgeClient.MAX_PENDING_BROADCASTS)
            return true
        val lifecycleFlags: BooleanArray = BooleanArray(pendingBroadcastSnapshots.size)
        var flagIndex: Int = 0
        for (queued: Bundle? in pendingBroadcastSnapshots) {
            lifecycleFlags[flagIndex++] = YandexCoreBridgeClient.priorityBroadcastSnapshot(queued)
        }
        val evictionIndex: Int =
            YandexSnapshotCoalescingPolicy.evictionIndex(
                incomingLifecyclePriority,
                incomingSemanticTransition,
                *lifecycleFlags,
            )
        if (evictionIndex < 0) {
            AppLog.navigation(
                app,
                ("Yandex Core Bridge queue overflow; retained lifecycle/" +
                    "transition history, dropped ordinary incoming seq=" +
                    YandexCoreBridgeClient.longValue(incoming, "seq", -1L)),
            )
            return false
        }
        val iterator: MutableIterator<Bundle?>? = pendingBroadcastSnapshots.iterator()
        var evicted: Bundle? = null
        var i: Int = 0
        while (iterator!!.hasNext()) {
            val candidate: Bundle? = iterator.next()
            if (i != evictionIndex) {
                i++
                continue
            }
            evicted = candidate
            iterator.remove()
            break
            i++
        }
        AppLog.navigation(
            app,
            ("Yandex Core Bridge queue overflow; preserved incoming " +
                (if (incomingLifecyclePriority) "lifecycle" else "transition") +
                " seq=" +
                YandexCoreBridgeClient.longValue(incoming, "seq", -1L) +
                " evictedSeq=" +
                YandexCoreBridgeClient.longValue(evicted, "seq", -1L)),
        )
        return true
    }

    private fun drainBroadcastSnapshot() {
        val snapshot: Bundle?
        synchronized(broadcastLock) {
            snapshot = pendingBroadcastSnapshots.pollFirst()
            broadcastApplyPosted = false
        }
        if (snapshot == null) return

        applyBroadcastSnapshot(snapshot)

        val target: Handler? = handler
        if (target == null) return
        synchronized(broadcastLock) {
            if (pendingBroadcastSnapshots.isEmpty() || broadcastApplyPosted) return
            broadcastApplyPosted = true
            val next: Bundle? = pendingBroadcastSnapshots.peekFirst()
            val transition: Boolean =
                next != null &&
                    YandexSnapshotCoalescingPolicy.isTransition(
                        lastAppliedCoalescingIdentity,
                        YandexCoreBridgeClient.coalescingIdentity(next),
                    )
            if (transition || YandexCoreBridgeClient.priorityBroadcastSnapshot(next)) {
                target.post(broadcastDrainer)
            } else {
                target.postDelayed(broadcastDrainer, broadcastApplyDelayMs())
            }
        }
    }

    private fun broadcastApplyDelayMs(): Long {
        if (lastBroadcastApplyAt <= 0L) return 0L
        val elapsed: Long = SystemClock.elapsedRealtime() - lastBroadcastApplyAt
        return Math.max(0L, YandexCoreBridgeClient.BROADCAST_APPLY_MIN_MS - elapsed)
    }

    private fun applyBroadcastSnapshot(snapshot: Bundle?) {
        try {
            lastBroadcastApplyAt = SystemClock.elapsedRealtime()
            val result: SnapshotApplyResult = applySnapshot(snapshot)
            if (result.accepted) {
                lastBroadcastSnapshotAt = SystemClock.elapsedRealtime()
            }
            val callback =
                YandexCoreBridgeClient.lower(
                    YandexCoreBridgeClient.firstString(
                        snapshot,
                        "last_callback",
                        "callback",
                        "event_type",
                        "bridge_reason",
                        "reason",
                    )
                )
            val now: Long = SystemClock.elapsedRealtime()
            val heartbeat: Boolean = "heartbeat".equals(callback)
            if (
                !heartbeat ||
                    !result.accepted ||
                    lastHeartbeatBroadcastLogAt < 0L ||
                    now - lastHeartbeatBroadcastLogAt >=
                        YandexCoreBridgeClient.HEARTBEAT_LOG_INTERVAL_MS
            ) {
                if (heartbeat) lastHeartbeatBroadcastLogAt = now
                AppLog.line(
                    app,
                    ("Yandex Core Bridge v2 broadcast " +
                        (if (result.accepted) "accepted" else "ignored") +
                        " state=" +
                        YandexCoreBridgeClient.firstString(snapshot, "state", "status") +
                        " seq=" +
                        YandexCoreBridgeClient.longValue(snapshot, "seq", -1L) +
                        " callback=" +
                        callback +
                        " nextPoll=" +
                        result.delayMs),
                )
            }
        } catch (e: Exception) {
            logError("v2_broadcast_apply", e)
        }
    }

    private fun pollOnce(): Long {
        if (!sourceIngressAllowed()) {
            return YandexCoreBridgeClient.IDLE_POLL_MS
        }
        if (
            lastBroadcastSnapshotAt > 0L &&
                (SystemClock.elapsedRealtime() - lastBroadcastSnapshotAt <
                    YandexCoreBridgeClient.BROADCAST_PROVIDER_SUPPRESS_MS)
        ) {
            return YandexCoreBridgeClient.ACTIVE_POLL_MS
        }
        if (!providerAvailable()) {
            logMissingProvider()
            return YandexCoreBridgeClient.MISSING_PROVIDER_POLL_MS
        }
        try {
            val snapshot: Bundle? = readSnapshot()
            if (snapshot == null || snapshot.isEmpty()) return YandexCoreBridgeClient.IDLE_POLL_MS
            return applySnapshot(snapshot).delayMs
        } catch (e: Exception) {
            logError("poll", e)
            return YandexCoreBridgeClient.ERROR_POLL_MS
        }
    }

    private fun providerAvailable(): Boolean {
        try {
            val provider: ProviderInfo? =
                app!!
                    .getPackageManager()
                    .resolveContentProvider(YandexCoreBridgeContract.AUTHORITY, 0)
            return provider != null
        } catch (e: Exception) {
            return false
        }
    }

    private fun readSnapshot(): Bundle? {
        try {
            val bundle: Bundle? =
                app!!
                    .getContentResolver()
                    .call(
                        YandexCoreBridgeContract.STATE_URI!!,
                        YandexCoreBridgeContract.METHOD_GET_STATE,
                        null,
                        null,
                    )
            if (bundle != null && !bundle.isEmpty()) return bundle
        } catch (e: Exception) {
            logError("call", e)
        }
        app!!
            .getContentResolver()
            .query(YandexCoreBridgeContract.STATE_URI!!, null, null, null, null)
            .use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return null
                val out: Bundle = Bundle()
                for (i in 0..<cursor.getColumnCount()) {
                    val key = cursor.getColumnName(i)
                    if (TextUtils.isEmpty(key) || cursor.isNull(i)) continue
                    val type: Int = cursor.getType(i)
                    if (type == Cursor.FIELD_TYPE_INTEGER) out.putLong(key, cursor.getLong(i))
                    else if (type == Cursor.FIELD_TYPE_FLOAT)
                        out.putDouble(key, cursor.getDouble(i))
                    else out.putString(key, cursor.getString(i))
                }
                return out
            }
    }

    @kotlin.jvm.Synchronized
    private fun applySnapshot(snapshot: Bundle?): SnapshotApplyResult {
        if (!sourceIngressAllowed()) {
            return YandexCoreBridgeClient.snapshotResult(YandexCoreBridgeClient.IDLE_POLL_MS, false)
        }
        var state: String? =
            YandexCoreBridgeClient.lower(
                YandexCoreBridgeClient.firstString(snapshot, "state", "route_state", "status")
            )
        if (TextUtils.isEmpty(state)) {
            state =
                if (YandexCoreBridgeClient.bool(snapshot, "active", false))
                    YandexCoreBridgeContract.STATE_ACTIVE
                else YandexCoreBridgeContract.STATE_OFF
        }
        val callback =
            YandexCoreBridgeClient.firstString(
                snapshot,
                "last_callback",
                "callback",
                "event_type",
                "bridge_reason",
                "reason",
            )
        val seq: Long = YandexCoreBridgeClient.longValue(snapshot, "seq", "sequence", -1L)
        val timestampElapsed: Long =
            YandexCoreBridgeClient.longValue(
                snapshot,
                "timestamp_elapsed_ms",
                "elapsed_realtime_ms",
                -1L,
            )
        val freshness: Long = YandexCoreBridgeClient.freshnessMs(snapshot, timestampElapsed)
        val routePresentKnown: Boolean = snapshot != null && snapshot.containsKey("route_present")
        val routePresent: Boolean = YandexCoreBridgeClient.bool(snapshot, "route_present", false)
        val nowElapsed: Long = SystemClock.elapsedRealtime()
        val recentLiveRoute: Boolean =
            lastLiveRouteMetricsAt > 0L &&
                (nowElapsed - lastLiveRouteMetricsAt <=
                    YandexCoreBridgeClient.BACKGROUND_SUSPENSION_ROUTE_EVIDENCE_MS)
        val preserveBackgroundRoute: Boolean =
            YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                state,
                callback,
                recentLiveRoute,
                YandexCoreBridgeClient.hasRouteMetrics(snapshot),
                routePresentKnown,
                routePresent,
            )
        if (preserveBackgroundRoute) {
            val backgroundOrder: kia.app.navigation.capture.YandexSnapshotOrderGuard.Result? =
                snapshotOrderGuard.evaluate(
                    YandexCoreBridgeClient.firstString(snapshot, "route_id", "routeId"),
                    seq,
                    timestampElapsed,
                )
            if (
                backgroundOrder !=
                    kia.app.navigation.capture.YandexSnapshotOrderGuard.Result.ACCEPT &&
                    (backgroundOrder !=
                        kia.app.navigation.capture.YandexSnapshotOrderGuard.Result
                            .ACCEPT_UNTRUSTED_INITIAL)
            ) {
                clearPendingManeuverRollover()
                AppLog.line(
                    app,
                    ("Yandex Core Bridge: out-of-order background snapshot ignored" +
                        " reason=" +
                        backgroundOrder +
                        " seq=" +
                        seq +
                        " timestamp=" +
                        timestampElapsed),
                )
                return YandexCoreBridgeClient.snapshotResult(
                    YandexCoreBridgeClient.ACTIVE_POLL_MS,
                    false,
                )
            }
            clearPendingManeuverRollover()
            val suspensionIdentity: String =
                (YandexCoreBridgeClient.lower(callback) +
                    "|" +
                    (if (routePresentKnown) java.lang.String.valueOf(routePresent) else "unknown") +
                    "|" +
                    YandexCoreBridgeClient.firstString(snapshot, "route_id", "routeId"))
            val suspensionTransition: Boolean =
                !suspensionIdentity.equals(lastBackgroundSuspensionIdentity)
            lastBackgroundSuspensionIdentity = suspensionIdentity
            NavigationFeature.get(app!!)
                .onYandexBackgroundGuidanceSuspended(
                    callback,
                    routePresentKnown && routePresent,
                    suspensionTransition,
                )
            // Resolve route ownership before the sign's route-only gate reads it.
            if (freshness <= YandexCoreBridgeClient.MAX_ACTIVE_FRESHNESS_MS) {
                sendSpeedSnapshot(snapshot, freshness)
            }
            if (suspensionTransition) {
                AppLog.line(
                    app,
                    ("Yandex Core Bridge: held route during background suspension" +
                        " callback=" +
                        callback +
                        " routePresent=" +
                        (if (routePresentKnown) java.lang.String.valueOf(routePresent)
                        else "unknown") +
                        " freshness=" +
                        freshness),
                )
            }
            return YandexCoreBridgeClient.snapshotResult(
                YandexCoreBridgeClient.ACTIVE_POLL_MS,
                true,
            )
        }
        lastBackgroundSuspensionIdentity = ""
        val routeLive: Boolean = YandexCoreBridgeClient.routeLive(state)
        if (routeLive && freshness > YandexCoreBridgeClient.MAX_ACTIVE_FRESHNESS_MS) {
            AppLog.line(app, "Yandex Core Bridge: stale snapshot ignored freshness=" + freshness)
            return YandexCoreBridgeClient.snapshotResult(
                YandexCoreBridgeClient.ACTIVE_POLL_MS,
                false,
            )
        }
        val orderResult: kia.app.navigation.capture.YandexSnapshotOrderGuard.Result? =
            snapshotOrderGuard.evaluate(
                YandexCoreBridgeClient.firstString(snapshot, "route_id", "routeId"),
                seq,
                timestampElapsed,
            )
        if (
            orderResult != kia.app.navigation.capture.YandexSnapshotOrderGuard.Result.ACCEPT &&
                orderResult !=
                    kia.app.navigation.capture.YandexSnapshotOrderGuard.Result
                        .ACCEPT_UNTRUSTED_INITIAL
        ) {
            clearPendingManeuverRollover()
            AppLog.line(
                app,
                ("Yandex Core Bridge: out-of-order snapshot ignored" +
                    " reason=" +
                    orderResult +
                    " state=" +
                    state +
                    " seq=" +
                    seq +
                    " timestamp=" +
                    timestampElapsed),
            )
            return YandexCoreBridgeClient.snapshotResult(
                if (YandexCoreBridgeClient.routeLive(state)) YandexCoreBridgeClient.ACTIVE_POLL_MS
                else YandexCoreBridgeClient.IDLE_POLL_MS,
                false,
            )
        }
        if (
            orderResult ==
                kia.app.navigation.capture.YandexSnapshotOrderGuard.Result.ACCEPT_UNTRUSTED_INITIAL
        ) {
            AppLog.line(
                app,
                ("Yandex Core Bridge: accepted initial untrusted envelope" + " state=" + state),
            )
        }
        rememberAppliedCoalescingIdentity(snapshot)
        snapshot!!.remove(YandexCoreBridgeClient.INTERNAL_DISTANCE_TRANSITION)
        if (YandexCoreBridgeContract.STATE_ACTIVE.equals(state)) {
            resetManeuverGuardForRouteChange(snapshot)
            normalizeActiveSnapshot(snapshot, freshness)
            rememberLiveRoute(snapshot)
        } else if (!routeLive) {
            resetManeuverDistanceGuard()
        }
        val signature = YandexCoreBridgeClient.snapshotSignature(state, snapshot)
        // Apply the current route state before road-sign TX, including OFF/FINISHED
        // and the first ACTIVE snapshot. Road context still updates in free drive.
        if (
            YandexCoreBridgeContract.STATE_OFF.equals(state) &&
                YandexCoreBridgeContract.STATE_OFF.equals(lastState)
        ) {
            sendSpeedSnapshot(snapshot, freshness)
            lastSignature = signature
            return YandexCoreBridgeClient.snapshotResult(YandexCoreBridgeClient.IDLE_POLL_MS, true)
        }
        if (state.equals(lastState) && signature.equals(lastSignature)) {
            if (routeLive) {
                val replay: Boolean =
                    NavigationFeature.get(app!!)
                        .touchYandexCoreBridgeHeartbeat(
                            YandexCoreBridgeClient.hasRouteMetrics(snapshot)
                        )
                if (replay) sendActiveSnapshot(freshness, snapshot)
            }
            sendSpeedSnapshot(snapshot, freshness)
            return YandexCoreBridgeClient.snapshotResult(
                if (routeLive) YandexCoreBridgeClient.ACTIVE_POLL_MS
                else YandexCoreBridgeClient.IDLE_POLL_MS,
                true,
            )
        }
        if (
            YandexCoreBridgeContract.STATE_FINISHED.equals(state) &&
                !finishedSnapshotConfirmed(snapshot, freshness)
        ) {
            lastSignature = ""
            val remaining: Long = YandexCoreBridgeClient.routeRemainingMeters(snapshot)
            AppLog.line(
                app,
                ("Yandex Core Bridge: ignored unconfirmed finished state" +
                    " remaining=" +
                    remaining +
                    " lastRemaining=" +
                    lastLiveRouteRemainingMeters +
                    " activeAge=" +
                    liveRouteAgeMs() +
                    " freshness=" +
                    freshness),
            )
            if (YandexCoreBridgeClient.hasRouteMetrics(snapshot)) {
                normalizeActiveSnapshot(snapshot, freshness)
                sendActiveSnapshot(freshness, snapshot)
            } else {
                sendLoading("finished_unconfirmed", freshness, snapshot)
            }
            sendSpeedSnapshot(snapshot, freshness)
            return YandexCoreBridgeClient.snapshotResult(
                YandexCoreBridgeClient.ACTIVE_POLL_MS,
                true,
            )
        }
        lastState = state
        lastSignature = signature

        if (YandexCoreBridgeContract.STATE_OFF.equals(state)) {
            sendOff("off", freshness, snapshot)
            sendSpeedSnapshot(snapshot, freshness)
            return YandexCoreBridgeClient.snapshotResult(YandexCoreBridgeClient.IDLE_POLL_MS, true)
        }
        if (YandexCoreBridgeContract.STATE_FINISHED.equals(state)) {
            sendFinished(freshness, snapshot)
            sendSpeedSnapshot(snapshot, freshness)
            return YandexCoreBridgeClient.snapshotResult(YandexCoreBridgeClient.IDLE_POLL_MS, true)
        }
        if (
            YandexCoreBridgeContract.STATE_LOADING.equals(state) ||
                YandexCoreBridgeContract.STATE_REROUTING.equals(state)
        ) {
            sendLoading(state, freshness, snapshot)
            sendSpeedSnapshot(snapshot, freshness)
            return YandexCoreBridgeClient.snapshotResult(
                YandexCoreBridgeClient.ACTIVE_POLL_MS,
                true,
            )
        }
        sendActiveSnapshot(freshness, snapshot)
        sendSpeedSnapshot(snapshot, freshness)
        return YandexCoreBridgeClient.snapshotResult(YandexCoreBridgeClient.ACTIVE_POLL_MS, true)
    }

    private fun sourceIngressAllowed(): Boolean {
        return NavigationSourcePolicy.ingressAllowed(
            AppSettings.navigationEnabled(app!!),
            AppSettings.navSourceMode(app),
            NavigationSourcePolicy.SOURCE_YANDEX,
        )
    }

    private fun rememberAppliedCoalescingIdentity(snapshot: Bundle?) {
        synchronized(broadcastLock) {
            lastAppliedCoalescingIdentity = YandexCoreBridgeClient.coalescingIdentity(snapshot)
            lastAppliedCoalescingManeuverMeters =
                YandexCoreBridgeClient.coalescingManeuverMeters(snapshot)
        }
    }

    private fun sendLoading(state: String?, freshness: Long, snapshot: Bundle?) {
        resetDistanceGuards()
        val active: Intent = baseIntent(NavigationFeature.KIA_ACTION_NAVI_ON, snapshot, freshness)
        active.putExtra("navi_on", true)
        active.putExtra("active", true)
        active.putExtra("bridge_state", state)
        NavigationFeature.get(app!!).handle(active)
    }

    private fun sendActiveSnapshot(freshness: Long, snapshot: Bundle?) {
        val active: Intent = baseIntent(NavigationFeature.KIA_ACTION_NAVI_ON, snapshot, freshness)
        active.putExtra("navi_on", true)
        active.putExtra("active", true)
        putRoute(active, snapshot)
        NavigationFeature.get(app!!).handle(active)

        val selected: MainManeuverSnapshot = YandexCoreBridgeClient.selectMainManeuver(snapshot)
        val mainEvent = selected.mainEvent
        val maneuver = selected.maneuver
        val maneuverProvenance = selected.provenance
        var maneuverDistance: String? = selected.distance
        val annotationManeuver = selected.annotationManeuver
        val annotationDistance = selected.annotationDistance
        val notificationManeuver = selected.notificationManeuver
        val notificationDistance = selected.notificationDistance
        val selectedRoundaboutExit = selected.roundaboutExit
        val semanticMicroPresent: Boolean = YandexCoreBridgeClient.hasSemanticMicroSignal(snapshot)
        val mainDistanceExplicitlyUnknown: Boolean =
            YandexSnapshotSemantics.distanceMeters(maneuverDistance) <= 0L && semanticMicroPresent
        if (TextUtils.isEmpty(maneuverDistance) && semanticMicroPresent) {
            maneuverDistance = "0 м"
        }
        if (YandexCoreBridgeClient.earlyFinishManeuver(maneuver, snapshot)) {
            AppLog.line(
                app,
                ("Yandex Core Bridge: suppressed early finish maneuver" +
                    " remaining=" +
                    YandexCoreBridgeClient.routeRemainingMeters(snapshot) +
                    " maneuver=" +
                    YandexCoreBridgeClient.maneuverMeters(snapshot)),
            )
        } else if (
            YandexSnapshotSemantics.shouldEmitManeuver(
                maneuver,
                maneuverDistance,
                semanticMicroPresent,
            )
        ) {
            val man: Intent = baseIntent(NavigationFeature.KIA_ACTION_MANEUVER, snapshot, freshness)
            man.putExtra("imageId", maneuver)
            man.putExtra("maneuver", maneuver)
            man.putExtra("direction", maneuver)
            man.putExtra("distance", maneuverDistance)
            man.putExtra("maneuver_provenance", maneuverProvenance)
            if (
                YandexCoreBridgeClient.bool(
                    snapshot,
                    YandexCoreBridgeContract.EXTRA_MAIN_MANEUVER_ROLLOVER,
                    false,
                )
            ) {
                man.putExtra(YandexCoreBridgeContract.EXTRA_MAIN_MANEUVER_ROLLOVER, true)
            }
            if (mainDistanceExplicitlyUnknown) {
                man.putExtra("main_distance_explicitly_unknown", true)
            }
            YandexCoreBridgeClient.putString(man, "annotation_maneuver", annotationManeuver)
            YandexCoreBridgeClient.putString(
                man,
                "annotation_maneuver_distance",
                annotationDistance,
            )
            YandexCoreBridgeClient.putString(man, "notification_maneuver", notificationManeuver)
            YandexCoreBridgeClient.putString(
                man,
                "notification_maneuver_distance",
                notificationDistance,
            )
            YandexCoreBridgeClient.putString(
                man,
                "maneuver_text",
                YandexCoreBridgeClient.firstString(snapshot, "maneuver_text", "voice_hint"),
            )
            YandexCoreBridgeClient.putString(
                man,
                "voice_hint",
                YandexCoreBridgeClient.firstString(snapshot, "voice_hint", "maneuver_text"),
            )
            YandexCoreBridgeClient.putString(
                man,
                "street_after_maneuver",
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "street_after_maneuver",
                    "next_street",
                    "next_turn_street",
                    "maneuver_toponym",
                ),
            )
            YandexCoreBridgeClient.putString(man, "main_event", mainEvent)
            YandexCoreBridgeClient.putString(
                man,
                "voice_main_event",
                YandexCoreBridgeClient.firstString(snapshot, "voice_main_event"),
            )
            YandexCoreBridgeClient.putString(
                man,
                "voice_prompt",
                YandexCoreBridgeClient.firstString(snapshot, "voice_prompt"),
            )
            YandexCoreBridgeClient.putString(man, "exit_number", selectedRoundaboutExit)
            YandexCoreBridgeClient.putString(man, "roundabout_exit", selectedRoundaboutExit)
            if (YandexCoreBridgeClient.isRoundaboutManeuverValue(maneuver)) {
                man.putExtra("roundabout_scope", "current")
                man.putExtra("roundabout_provenance", maneuverProvenance)
            }
            YandexCoreBridgeClient.putString(
                man,
                "route_action",
                YandexCoreBridgeClient.firstString(snapshot, "route_action", "routeAction"),
            )
            YandexCoreBridgeClient.putString(
                man,
                "highlighted_direction",
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "highlighted_direction",
                    "highlightedDirection",
                ),
            )
            YandexCoreBridgeClient.putString(
                man,
                "route_road_options",
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "road_options",
                    "route_road_options",
                    "available_directions",
                ),
            )
            YandexCoreBridgeClient.putString(
                man,
                "gray_road_options",
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "gray_road_options",
                    "road_options",
                    "available_directions",
                ),
            )
            putLaneExtras(man, snapshot)
            NavigationFeature.get(app).handle(man)
        }

        sendEventSnapshot(snapshot, freshness)

        val eta: Intent = baseIntent(NavigationFeature.KIA_ACTION_ETA, snapshot, freshness)
        putRoute(eta, snapshot)
        NavigationFeature.get(app).handle(eta)
    }

    private fun sendEventSnapshot(snapshot: Bundle?, freshness: Long) {
        val event =
            YandexCoreBridgeClient.firstString(
                snapshot,
                "first_event_text",
                "event",
                "main_event",
                "voice_main_event",
                "route_event",
            )
        if (TextUtils.isEmpty(event)) return
        val out: Intent = baseIntent(NavigationFeature.KIA_ACTION_MANEUVER, snapshot, freshness)
        YandexCoreBridgeClient.putString(out, "event", event)
        YandexCoreBridgeClient.putString(out, "first_event_text", event)
        YandexCoreBridgeClient.putString(
            out,
            "first_event_type",
            YandexCoreBridgeClient.firstString(snapshot, "first_event_type", "event_type"),
        )
        YandexCoreBridgeClient.putString(
            out,
            "camera_speed_limit",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "camera_speed_limit",
                "first_camera_speed_limit_kmh",
            ),
        )
        NavigationFeature.get(app!!).handle(out)
    }

    private fun sendSpeedSnapshot(snapshot: Bundle?, freshness: Long): Boolean {
        val speed: Long =
            YandexCoreBridgeClient.longValue(snapshot, "current_speed_kmh", "speed_kmh", -1L)
        val limit: Long =
            YandexCoreBridgeClient.longValue(
                snapshot,
                "speed_limit_kmh",
                "speed_limit",
                "road_speed_limit",
                -1L,
            )
        val sourceHasRoadLimit: Boolean =
            YandexCoreBridgeClient.containsAnyKey(
                snapshot,
                "speed_limit_kmh",
                "speed_limit",
                "road_speed_limit",
            )
        val sourceCameraLimit =
            YandexCoreBridgeClient.firstString(
                snapshot,
                "camera_speed_limit",
                "first_camera_speed_limit_kmh",
            )
        val sourceHasExceeded: Boolean =
            snapshot != null &&
                (snapshot.containsKey("speed_exceeded") || snapshot.containsKey("exceeded"))
        val callback =
            YandexCoreBridgeClient.firstString(
                snapshot,
                "last_callback",
                "callback",
                "event_type",
                "bridge_reason",
                "reason",
            )
        val relativeTimestamp: Long =
            YandexCoreBridgeClient.firstLong(
                snapshot,
                -1L,
                "location_relative_timestamp_ms",
                "location_relative_timestamp",
                "location_elapsed_realtime_ms",
            )
        val absoluteTimestamp: Long =
            YandexCoreBridgeClient.firstLong(
                snapshot,
                -1L,
                "location_absolute_timestamp_ms",
                "location_absolute_timestamp",
                "location_timestamp_ms",
            )
        val now: Long = SystemClock.elapsedRealtime()
        val sourceDecision: kia.app.navigation.capture.YandexSourceSampleGate.Decision =
            sourceSampleGate.evaluate(
                callback,
                relativeTimestamp,
                absoluteTimestamp,
                now,
                System.currentTimeMillis(),
                YandexCoreBridgeClient.SOURCE_SAMPLE_MAX_AGE_MS,
            )
        logSourceSampleDecision(callback, relativeTimestamp, absoluteTimestamp, sourceDecision, now)
        if (sourceDecision.roadLimitFresh && !sourceHasRoadLimit) {
            NavigationFeature.get(app!!).observeYandexRoadSpeedLimitMissing()
        }

        val currentSpeedFresh: Boolean = sourceDecision.currentSpeedFresh && speed >= 0L
        val roadLimitFresh: Boolean = sourceDecision.roadLimitFresh && sourceHasRoadLimit
        val cameraFresh: Boolean =
            sourceDecision.anyFreshData() && !TextUtils.isEmpty(sourceCameraLimit)
        val speedKmh: Int =
            if (currentSpeedFresh) Math.min(Int.MAX_VALUE.toLong(), speed).toInt() else -1
        val limitKmh: Int =
            if (roadLimitFresh) Math.max(-1L, Math.min(Int.MAX_VALUE.toLong(), limit)).toInt()
            else -1
        val cameraLimit = if (cameraFresh) sourceCameraLimit else ""
        val hasExceeded: Boolean = currentSpeedFresh && sourceHasExceeded
        // The bridge always carries exceeded=false, including completely empty free-drive
        // snapshots. That flag alone must not create a one-second StateStore/UI update loop.
        if (!YandexSpeedSnapshotPolicy.meaningfulPayload(speedKmh, roadLimitFresh, cameraLimit)) {
            NavigationFeature.get(app!!).observeYandexTransportHeartbeat()
            return false
        }

        val exceeded: Boolean =
            YandexCoreBridgeClient.bool(
                snapshot,
                "speed_exceeded",
                YandexCoreBridgeClient.bool(snapshot, "exceeded", false),
            )
        val signature: String =
            (speedKmh.toString() +
                "|" +
                limitKmh +
                "|" +
                roadLimitFresh +
                "|" +
                cameraLimit +
                "|" +
                (if (hasExceeded) java.lang.String.valueOf(exceeded) else "-"))
        if (signature.equals(lastSpeedSignature)) {
            if (
                NavigationFeature.get(app!!)
                    .refreshMatchingYandexSpeedContext(
                        speedKmh,
                        currentSpeedFresh,
                        roadLimitFresh,
                        limitKmh,
                        hasExceeded,
                        exceeded,
                    )
            ) {
                return false
            }
            if (now - lastSpeedSnapshotSentAt < YandexCoreBridgeClient.SPEED_SNAPSHOT_MIN_MS)
                return false
        }
        lastSpeedSignature = signature
        lastSpeedSnapshotSentAt = now

        val speedIntent: Intent =
            baseIntent(NavigationFeature.KIA_ACTION_SPEED, snapshot, freshness)
        speedIntent.putExtra("bridge_source_freshness_enforced", true)
        speedIntent.putExtra("yandex_current_speed_sample_fresh", currentSpeedFresh)
        speedIntent.putExtra("yandex_road_limit_sample_fresh", roadLimitFresh)
        speedIntent.putExtra("yandex_camera_sample_fresh", cameraFresh)
        if (currentSpeedFresh) {
            speedIntent.putExtra("current_speed", java.lang.String.valueOf(speedKmh))
        }
        speedIntent.putExtra("road_speed_limit_present", roadLimitFresh)
        if (roadLimitFresh && limitKmh > 0) {
            speedIntent.putExtra("speed_limit", java.lang.String.valueOf(limitKmh))
            speedIntent.putExtra("road_speed_limit", java.lang.String.valueOf(limitKmh))
            speedIntent.putExtra("speed_limit_source", "guide")
        } else if (roadLimitFresh) {
            speedIntent.putExtra("speed_limit_clear", true)
        }
        YandexCoreBridgeClient.putString(speedIntent, "camera_speed_limit", cameraLimit)
        if (hasExceeded) speedIntent.putExtra("exceeded", exceeded)
        NavigationFeature.get(app!!).handle(speedIntent)
        return true
    }

    private fun logSourceSampleDecision(
        callback: String?,
        relativeTimestamp: Long,
        absoluteTimestamp: Long,
        decision: kia.app.navigation.capture.YandexSourceSampleGate.Decision,
        nowElapsed: Long,
    ) {
        if (
            lastSourceSampleLogAt >= 0L &&
                nowElapsed - lastSourceSampleLogAt <
                    YandexCoreBridgeClient.SOURCE_SAMPLE_LOG_INTERVAL_MS
        ) {
            return
        }
        lastSourceSampleLogAt = nowElapsed
        AppLog.navigation(
            app,
            ("NAV_YANDEX_SOURCE_SAMPLE" +
                " callback=" +
                YandexCoreBridgeClient.lower(callback) +
                " relative=" +
                relativeTimestamp +
                " absolute=" +
                absoluteTimestamp +
                " currentFresh=" +
                decision.currentSpeedFresh +
                " limitFresh=" +
                decision.roadLimitFresh +
                " decision=" +
                decision.reason),
        )
    }

    private fun sendFinished(freshness: Long, snapshot: Bundle?) {
        resetRouteGuards()
        val off: Intent = baseIntent(NavigationFeature.KIA_ACTION_NAVI_ON, snapshot, freshness)
        off.putExtra("navi_on", false)
        off.putExtra("active", false)
        off.putExtra("finish_reached", true)
        off.putExtra("route_finished", true)
        NavigationFeature.get(app!!).handle(off)
    }

    private fun sendOff(reason: String?, freshness: Long, snapshot: Bundle?) {
        resetRouteGuards()
        val off: Intent = baseIntent(NavigationFeature.KIA_ACTION_NAVI_ON, snapshot, freshness)
        off.putExtra("navi_on", false)
        off.putExtra("active", false)
        off.putExtra("route_removed", true)
        off.putExtra("bridge_reason", reason)
        NavigationFeature.get(app!!).handle(off)
    }

    private fun baseIntent(action: String?, snapshot: Bundle?, freshness: Long): Intent {
        val intent: Intent = Intent(action)
        intent.setPackage(app!!.getPackageName())
        intent.putExtra("source", YandexCoreBridgeContract.SOURCE)
        intent.putExtra("bridge_freshness_ms", freshness)
        intent.putExtra("bridge_full_snapshot", true)
        val hasMicro: Boolean =
            !YandexSnapshotSemantics.MICRO_ABSENT_IDENTITY.equals(
                YandexCoreBridgeClient.microStateIdentity(snapshot)
            )
        intent.putExtra("micro_snapshot_present", hasMicro)
        intent.putExtra("micro_clear", !hasMicro)
        YandexCoreBridgeClient.putString(
            intent,
            "bridge_state",
            YandexCoreBridgeClient.firstString(snapshot, "state", "route_state", "status"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "bridge_original_state",
            YandexCoreBridgeClient.firstString(snapshot, "bridge_original_state"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "last_callback",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "last_callback",
                "callback",
                "event_type",
                "bridge_reason",
                "reason",
            ),
        )
        if (YandexCoreBridgeClient.bool(snapshot, "background_guidance_suspended", false)) {
            intent.putExtra("background_guidance_suspended", true)
        }
        YandexCoreBridgeClient.putBooleanIfPresent(intent, snapshot, "route_present")
        YandexCoreBridgeClient.putBooleanIfPresent(intent, snapshot, "process_alive")
        YandexCoreBridgeClient.putBooleanIfPresent(intent, snapshot, "guidance_resumed")
        YandexCoreBridgeClient.putString(
            intent,
            "main_event",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "main_event",
                "voice_main_event",
                "route_event",
            ),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "voice_main_event",
            YandexCoreBridgeClient.firstString(snapshot, "voice_main_event"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "voice_prompt",
            YandexCoreBridgeClient.firstString(snapshot, "voice_prompt"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "route_id",
            YandexCoreBridgeClient.firstString(snapshot, "route_id", "routeId"),
        )
        putRoutePose(intent, snapshot)
        val seq: Long = YandexCoreBridgeClient.longValue(snapshot, "seq", "sequence", -1L)
        if (seq >= 0) intent.putExtra("seq", seq)
        return intent
    }

    private fun putRoutePose(intent: Intent, snapshot: Bundle?) {
        YandexCoreBridgeClient.putString(
            intent,
            "current_point",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "current_point",
                "currentPoint",
                "vehicle_point",
                "vehiclePoint",
                "car_point",
                "carPoint",
                "position_point",
                "positionPoint",
                "route_position_point",
                "routePositionPoint",
                "location_point",
                "locationPoint",
                "gps_point",
                "gpsPoint",
            ),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "current_lat",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "current_lat",
                "currentLat",
                "vehicle_lat",
                "vehicleLat",
                "car_lat",
                "carLat",
                "position_lat",
                "positionLat",
                "route_position_lat",
                "routePositionLat",
                "location_lat",
                "locationLat",
                "gps_lat",
                "gpsLat",
                "latitude",
            ),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "current_lon",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "current_lon",
                "currentLon",
                "current_lng",
                "currentLng",
                "vehicle_lon",
                "vehicleLon",
                "vehicle_lng",
                "vehicleLng",
                "car_lon",
                "carLon",
                "car_lng",
                "carLng",
                "position_lon",
                "positionLon",
                "position_lng",
                "positionLng",
                "route_position_lon",
                "routePositionLon",
                "route_position_lng",
                "routePositionLng",
                "location_lon",
                "locationLon",
                "location_lng",
                "locationLng",
                "gps_lon",
                "gpsLon",
                "gps_lng",
                "gpsLng",
                "longitude",
            ),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "yandex_heading",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "yandex_heading",
                "yandexHeading",
                "vehicle_heading",
                "vehicleHeading",
                "car_heading",
                "carHeading",
                "map_heading",
                "mapHeading",
                "navigation_heading",
                "navigationHeading",
                "route_position_heading",
                "routePositionHeading",
                "location_heading",
                "locationHeading",
                "location_bearing",
                "locationBearing",
                "gps_bearing",
                "gpsBearing",
                "bearing",
                "heading",
                "azimuth",
                "course",
            ),
        )
    }

    private fun putRoute(intent: Intent, snapshot: Bundle?) {
        var remainingMeters: Long = YandexCoreBridgeClient.routeRemainingMeters(snapshot)
        var totalMeters: Long = YandexCoreBridgeClient.routeTotalMeters(snapshot)
        val selectedMain: MainManeuverSnapshot = YandexCoreBridgeClient.selectMainManeuver(snapshot)
        val maneuverMeters: Long = YandexSnapshotSemantics.distanceMeters(selectedMain.distance)
        remainingMeters =
            YandexCoreBridgeClient.correctedRemainingMeters(remainingMeters, totalMeters)
        remainingMeters =
            YandexCoreBridgeClient.correctedRemainingByGuidance(remainingMeters, maneuverMeters)
        if (remainingMeters >= 0L && totalMeters >= 0L && totalMeters < remainingMeters) {
            totalMeters = remainingMeters
        }
        var remaining: String? = YandexCoreBridgeClient.metersText(remainingMeters)
        var total: String? = YandexCoreBridgeClient.metersText(totalMeters)
        var eta: String? =
            YandexCoreBridgeClient.secondsText(
                YandexCoreBridgeClient.longValue(
                    snapshot,
                    "eta_seconds",
                    "remaining_seconds",
                    "time_left_seconds",
                    -1L,
                )
            )
        if (TextUtils.isEmpty(remaining)) {
            remaining =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "remaining_distance",
                    "route_distance",
                    "edistance",
                    "distance_left",
                    "eta_distance_left",
                )
        }
        if (TextUtils.isEmpty(total)) {
            total =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "route_total_len",
                    "route_full_len",
                    "route_total_distance",
                    "full_route_distance",
                    "routeTotalDistance",
                    "totalRouteDistance",
                )
        }
        if (TextUtils.isEmpty(eta)) {
            eta =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "route_time",
                    "remaining_time",
                    "time_left",
                    "eta_time_left",
                    "etime",
                )
        }
        val arrival =
            YandexCoreBridgeClient.firstString(snapshot, "arrival_time", "arrival", "eta", "atime")
        YandexCoreBridgeClient.putString(intent, "route_distance", remaining)
        YandexCoreBridgeClient.putString(intent, "remaining_distance", remaining)
        YandexCoreBridgeClient.putString(intent, "edistance", remaining)
        YandexCoreBridgeClient.putString(intent, "route_total_len", total)
        YandexCoreBridgeClient.putMeters(
            intent,
            remainingMeters,
            "remaining_meters",
            "remaining_distance_meters",
            "distance_left_meters",
        )
        YandexCoreBridgeClient.putMeters(
            intent,
            totalMeters,
            "route_total_meters",
            "total_route_meters",
            "route_total_distance_meters",
        )
        YandexCoreBridgeClient.putString(intent, "route_time", eta)
        YandexCoreBridgeClient.putString(intent, "time_left", eta)
        YandexCoreBridgeClient.putString(intent, "etime", eta)
        YandexCoreBridgeClient.putString(intent, "arrival_time", arrival)
        YandexCoreBridgeClient.putString(intent, "atime", arrival)
        YandexCoreBridgeClient.putString(
            intent,
            "finish_point",
            YandexCoreBridgeClient.firstString(snapshot, "finish_point", "finishPoint"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "finish_street",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "finish_street",
                "finishStreet",
                "finish_address",
                "destination_name",
            ),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "current_street",
            YandexCoreBridgeClient.firstString(snapshot, "current_street", "currentStreet"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "next_street",
            YandexCoreBridgeClient.firstString(snapshot, "next_street", "nextStreet"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "street_after_maneuver",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "street_after_maneuver",
                "next_turn_street",
                "maneuver_toponym",
            ),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "main_event",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "main_event",
                "voice_main_event",
                "route_event",
            ),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "voice_prompt",
            YandexCoreBridgeClient.firstString(snapshot, "voice_prompt"),
        )
        if (YandexCoreBridgeClient.isRoundaboutManeuverValue(selectedMain.maneuver)) {
            YandexCoreBridgeClient.putString(intent, "exit_number", selectedMain.roundaboutExit)
            YandexCoreBridgeClient.putString(intent, "roundabout_exit", selectedMain.roundaboutExit)
            intent.putExtra("roundabout_scope", "current")
        }
        putManeuverDistance(intent, snapshot)
        putLaneExtras(intent, snapshot)
    }

    private fun putManeuverDistance(intent: Intent, snapshot: Bundle?) {
        val selected: MainManeuverSnapshot = YandexCoreBridgeClient.selectMainManeuver(snapshot)
        YandexCoreBridgeClient.putString(intent, "current_maneuver", selected.maneuver)
        YandexCoreBridgeClient.putString(intent, "maneuver_provenance", selected.provenance)
        YandexCoreBridgeClient.putString(intent, "maneuver_distance_identity", selected.maneuver)
        YandexCoreBridgeClient.putString(
            intent,
            "maneuver_distance_provenance",
            selected.provenance,
        )
        val meters: Long = YandexSnapshotSemantics.distanceMeters(selected.distance)
        if (meters <= 0L) return
        val distance = selected.distance
        YandexCoreBridgeClient.putString(intent, "maneuver_distance", distance)
        YandexCoreBridgeClient.putString(intent, "current_maneuver_distance", distance)
        YandexCoreBridgeClient.putString(intent, "distance_to_maneuver", distance)
        YandexCoreBridgeClient.putString(intent, "next_maneuver_distance", distance)
        YandexCoreBridgeClient.putString(intent, "last_maneuver_distance", distance)
        YandexCoreBridgeClient.putMeters(
            intent,
            meters,
            "maneuver_distance_meters",
            "current_maneuver_distance_meters",
            "distance_to_maneuver_meters",
        )
    }

    private fun putLaneExtras(intent: Intent, snapshot: Bundle?) {
        val laneMeters: Long =
            YandexCoreBridgeClient.firstLong(
                snapshot,
                -1L,
                "lane_distance_meters",
                "micro_distance_meters",
                "lane_sign_distance_meters",
                "upcoming_lane_distance_meters",
                "laneDistanceMeters",
                "microDistanceMeters",
            )
        var laneDistance: String? = YandexCoreBridgeClient.metersText(laneMeters)
        if (TextUtils.isEmpty(laneDistance)) {
            laneDistance =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "lane_distance",
                    "laneDistance",
                    "micro_distance",
                    "microDistance",
                )
        }
        YandexCoreBridgeClient.putString(intent, "lane_distance", laneDistance)
        YandexCoreBridgeClient.putString(intent, "micro_distance", laneDistance)
        YandexCoreBridgeClient.putMeters(
            intent,
            laneMeters,
            "lane_distance_meters",
            "micro_distance_meters",
        )
        YandexCoreBridgeClient.putString(
            intent,
            "lane_distance_unit",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "lane_distance_unit",
                "micro_distance_unit",
            ),
        )
        var laneItems: String? =
            YandexCoreBridgeClient.firstString(
                snapshot,
                "ignored_lane_items",
                "lane_items",
                "lane_topology",
            )
        var rawLaneItems: String? =
            YandexCoreBridgeClient.firstString(
                snapshot,
                "ignored_raw_lane_items",
                "raw_lane_items",
                "lane_topology",
                "lane_topology_json",
            )
        var routeDirections: String? =
            YandexCoreBridgeClient.firstString(
                snapshot,
                "route_road_options",
                "road_options",
                "gray_road_options",
                "available_directions",
            )
        var grayDirections: String? =
            YandexCoreBridgeClient.firstString(
                snapshot,
                "gray_road_options",
                "route_road_options",
                "road_options",
            )
        var highlight: String? =
            YandexCoreBridgeClient.firstString(
                snapshot,
                "lane_highlighted_direction",
                "highlighted_direction",
                "highlighted_directions",
                "lane_highlight",
                "recommended_lanes",
                "ignored_recommended_lanes",
                "micro_maneuver",
                "lane_maneuver",
                "ignored_lane_maneuver",
            )
        val staleMainConflict: Boolean =
            YandexCoreBridgeClient.staleLaneGuidanceConflictsWithMain(
                snapshot,
                highlight,
                laneDistance,
                laneMeters,
            )
        val positionedMicro: Boolean =
            laneMeters > 0L || YandexSnapshotSemantics.distanceMeters(laneDistance) > 0L
        if (
            (!positionedMicro &&
                YandexCoreBridgeClient.laneGuidanceConflicts(
                    routeDirections,
                    YandexCoreBridgeClient.first(rawLaneItems, laneItems, highlight),
                )) || staleMainConflict
        ) {
            if (staleMainConflict) {
                AppLog.line(
                    app,
                    ("Yandex Core Bridge: ignored stale lane topology main=" +
                        YandexCoreBridgeClient.firstString(
                            snapshot,
                            "direction",
                            "maneuver",
                            "imageId",
                            "route_action",
                        ) +
                        " highlight=" +
                        YandexCoreBridgeClient.lower(highlight) +
                        " route=" +
                        YandexCoreBridgeClient.lower(routeDirections)),
                )
            }
            laneItems = ""
            rawLaneItems = ""
            routeDirections = ""
            grayDirections = ""
            highlight = ""
            YandexCoreBridgeClient.clearLaneExtras(intent)
        }
        val allowedDirections =
            YandexCoreBridgeClient.first(
                routeDirections,
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "ignored_allowed_directions",
                    "allowed_directions",
                ),
            )
        YandexCoreBridgeClient.putString(intent, "ignored_lane_items", laneItems)
        YandexCoreBridgeClient.putString(intent, "ignored_raw_lane_items", rawLaneItems)
        YandexCoreBridgeClient.putString(intent, "ignored_allowed_directions", allowedDirections)
        YandexCoreBridgeClient.putString(
            intent,
            "lane_items",
            YandexCoreBridgeClient.first(rawLaneItems, laneItems),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "raw_lane_items",
            YandexCoreBridgeClient.first(rawLaneItems, laneItems),
        )
        YandexCoreBridgeClient.putString(intent, "allowed_directions", allowedDirections)
        YandexCoreBridgeClient.putString(intent, "ignored_recommended_lanes", highlight)
        YandexCoreBridgeClient.putString(intent, "ignored_lane_maneuver", highlight)
        YandexCoreBridgeClient.putString(intent, "recommended_lanes", highlight)
        YandexCoreBridgeClient.putString(intent, "lane_highlight", highlight)
        YandexCoreBridgeClient.putString(intent, "lane_highlighted_direction", highlight)
        YandexCoreBridgeClient.putString(intent, "highlighted_direction", highlight)
        YandexCoreBridgeClient.putString(intent, "highlighted_directions", highlight)
        YandexCoreBridgeClient.putString(intent, "lane_maneuver", highlight)
        YandexCoreBridgeClient.putString(
            intent,
            "lane_topology",
            YandexCoreBridgeClient.firstString(snapshot, "lane_topology", "raw_lane_topology"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "lane_topology_json",
            YandexCoreBridgeClient.firstString(snapshot, "lane_topology_json"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "lane_sign_position",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "lane_sign_position",
                "lane_position",
                "upcoming_lane_position",
            ),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "lane_count",
            YandexCoreBridgeClient.firstString(snapshot, "lane_count", "route_lane_count"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "road_scheme_raw",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "road_scheme_raw",
                "direction_sign_items",
                "raw_direction_sign_items",
            ),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "direction_sign_items",
            YandexCoreBridgeClient.firstString(
                snapshot,
                "direction_sign_items",
                "raw_direction_sign_items",
            ),
        )
        YandexCoreBridgeClient.putString(intent, "route_road_options", routeDirections)
        YandexCoreBridgeClient.putString(intent, "gray_road_options", grayDirections)
        YandexCoreBridgeClient.putString(
            intent,
            "upcoming_lane_signs",
            YandexCoreBridgeClient.firstString(snapshot, "upcoming_lane_signs"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "upcoming_direction_signs",
            YandexCoreBridgeClient.firstString(snapshot, "upcoming_direction_signs"),
        )
        YandexCoreBridgeClient.putString(
            intent,
            "upcoming_road_events",
            YandexCoreBridgeClient.firstString(snapshot, "upcoming_road_events"),
        )
        YandexCoreBridgeClient.putLaneDirectionFlags(
            intent,
            YandexCoreBridgeClient.first(rawLaneItems, laneItems, allowedDirections),
        )
        if (
            YandexCoreBridgeClient.bool(snapshot, "lane_guidance", false) ||
                !TextUtils.isEmpty(laneDistance) ||
                !TextUtils.isEmpty(
                    YandexCoreBridgeClient.first(
                        rawLaneItems,
                        laneItems,
                        allowedDirections,
                        highlight,
                    )
                )
        ) {
            intent.putExtra("lane_guidance", true)
        }
    }

    private fun normalizeActiveSnapshot(snapshot: Bundle?, freshness: Long) {
        if (snapshot == null || snapshot.isEmpty()) return
        var remainingMeters: Long = YandexCoreBridgeClient.routeRemainingMeters(snapshot)
        val totalMeters: Long = YandexCoreBridgeClient.routeTotalMeters(snapshot)
        remainingMeters =
            YandexCoreBridgeClient.correctedRemainingMeters(remainingMeters, totalMeters)
        val selectedMain: MainManeuverSnapshot = YandexCoreBridgeClient.selectMainManeuver(snapshot)
        var maneuverMeters: Long = YandexSnapshotSemantics.distanceMeters(selectedMain.distance)
        val travelDelta: Long = YandexCoreBridgeClient.travelDeltaMeters(snapshot, freshness)
        if (travelDelta > 0L) {
            if (remainingMeters >= 0L) remainingMeters = Math.max(0L, remainingMeters - travelDelta)
            if (maneuverMeters >= 0L) maneuverMeters = Math.max(0L, maneuverMeters - travelDelta)
        }
        val correctedRemaining: Long =
            YandexCoreBridgeClient.correctedRemainingByGuidance(remainingMeters, maneuverMeters)
        if (correctedRemaining != remainingMeters) {
            AppLog.line(
                app,
                ("Yandex Core Bridge: route remaining corrected by maneuver" +
                    " route=" +
                    remainingMeters +
                    " maneuver=" +
                    maneuverMeters),
            )
            remainingMeters = correctedRemaining
        }
        YandexCoreBridgeClient.putMeters(
            snapshot,
            remainingMeters,
            "remaining_meters",
            "remaining_distance_meters",
            "distance_left_meters",
        )
        if (remainingMeters >= 0L && totalMeters >= 0L && totalMeters < remainingMeters) {
            YandexCoreBridgeClient.putMeters(
                snapshot,
                remainingMeters,
                "route_total_meters",
                "total_route_meters",
                "route_total_distance_meters",
            )
        }

        val maneuverKey: String =
            YandexCoreBridgeClient.mainManeuverDistanceIdentity(snapshot, selectedMain)!!
        val semanticMicroPresent: Boolean = YandexCoreBridgeClient.hasSemanticMicroSignal(snapshot)
        val correctedManeuver: Long =
            correctedManeuverMeters(
                maneuverKey,
                maneuverMeters,
                remainingMeters,
                semanticMicroPresent,
            )
        YandexCoreBridgeClient.putMeters(
            snapshot,
            correctedManeuver,
            *YandexMainDistanceContinuityPolicy.synchronizedDistanceMeterKeys(
                selectedMain.provenance
            ),
        )
        snapshot.remove(YandexCoreBridgeContract.EXTRA_MAIN_MANEUVER_ROLLOVER)
        if (maneuverDistanceRolloverForSnapshot) {
            snapshot.putBoolean(YandexCoreBridgeContract.EXTRA_MAIN_MANEUVER_ROLLOVER, true)
        }
    }

    private fun correctedManeuverMeters(
        maneuverIdentity: String?,
        meters: Long,
        routeRemainingMeters: Long,
        semanticMicroPresent: Boolean,
    ): Long {
        maneuverDistanceRolloverForSnapshot = false
        if (meters < 0L || TextUtils.isEmpty(maneuverIdentity)) {
            resetManeuverDistanceGuard()
            return meters
        }
        val key = YandexCoreBridgeClient.lower(maneuverIdentity)
        if (!key.equals(lastManeuverDistanceKey) || lastManeuverDistanceMeters < 0L) {
            startManeuverDistanceEvent(key, meters, routeRemainingMeters)
            return meters
        }
        val routeRemainingKnown: Boolean =
            routeRemainingMeters >= 0L && lastManeuverRouteRemainingMeters >= 0L
        val routeDelta: Long =
            if (routeRemainingKnown) lastManeuverRouteRemainingMeters - routeRemainingMeters else 0L
        val routeGrew: Boolean =
            routeRemainingKnown &&
                routeRemainingMeters >
                    lastManeuverRouteRemainingMeters +
                        YandexCoreBridgeClient.REROUTE_ROUTE_GROWTH_METERS
        if (routeGrew) {
            val previousRouteRemaining: Long = lastManeuverRouteRemainingMeters
            val previousDistance: Long = lastManeuverDistanceMeters
            val restartThreshold: Long =
                Math.max(80L, Math.round(Math.max(0L, previousDistance) * 0.25))
            val mainDistanceRestarted: Boolean =
                ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                    maneuverDistanceEventBaseMeters.toFloat(),
                    maneuverDistanceEventClosestMeters.toFloat(),
                    meters.toFloat(),
                ) ||
                    (previousDistance > YandexCoreBridgeClient.MANEUVER_ZERO_METERS &&
                        meters > previousDistance + restartThreshold)
            if (mainDistanceRestarted) {
                startManeuverDistanceEvent(key, meters, routeRemainingMeters)
                maneuverDistanceRolloverForSnapshot = true
            } else {
                rememberManeuverDistance(key, meters, routeRemainingMeters)
                clearPendingManeuverRollover()
            }
            AppLog.line(
                app,
                ("Yandex Core Bridge: maneuver route growth" +
                    " lastRoute=" +
                    previousRouteRemaining +
                    " route=" +
                    routeRemainingMeters +
                    " previous=" +
                    previousDistance +
                    " meters=" +
                    meters +
                    " rollover=" +
                    mainDistanceRestarted),
            )
            return meters
        }
        val zeroContinuity: YandexMainDistanceContinuityPolicy.ZeroContinuity =
            YandexMainDistanceContinuityPolicy.resolveTransientZero(
                lastManeuverDistanceKey,
                key,
                lastManeuverDistanceMeters,
                meters,
                lastManeuverRouteRemainingMeters,
                routeRemainingMeters,
                semanticMicroPresent,
                YandexCoreBridgeClient.MANEUVER_ROUTE_DELTA_MAX_METERS,
                YandexCoreBridgeClient.REROUTE_ROUTE_GROWTH_METERS,
                YandexCoreBridgeClient.MANEUVER_ZERO_METERS,
            )
        if (zeroContinuity.handled) {
            val previousManeuverMeters: Long = lastManeuverDistanceMeters
            rememberManeuverDistance(
                key,
                zeroContinuity.stateMeters,
                zeroContinuity.stateRouteRemaining,
            )
            logManeuverProjection(
                key,
                zeroContinuity.mode,
                previousManeuverMeters,
                meters,
                zeroContinuity.stateMeters,
                zeroContinuity.outputMeters,
                routeRemainingMeters,
            )
            return zeroContinuity.outputMeters
        }
        if (pendingManeuverRolloverAtElapsed > 0L) {
            val pendingAge: Long = SystemClock.elapsedRealtime() - pendingManeuverRolloverAtElapsed
            if (
                pendingAge < 0L ||
                    pendingAge > YandexCoreBridgeClient.MANEUVER_ROLLOVER_PENDING_TTL_MS
            ) {
                clearPendingManeuverRollover()
            }
        }
        if (
            YandexMainDistanceContinuityPolicy.confirmsPendingSameIdentityPostPass(
                pendingManeuverRolloverKey,
                key,
                pendingManeuverRolloverClosestMeters,
                pendingManeuverRolloverMeters,
                meters,
                pendingManeuverRolloverRouteRemainingMeters,
                routeRemainingMeters,
                YandexCoreBridgeClient.MANEUVER_ROUTE_DELTA_MAX_METERS,
            )
        ) {
            val previousDistance: Long = lastManeuverDistanceMeters
            val previousClosest: Long = maneuverDistanceEventClosestMeters
            startManeuverDistanceEvent(key, meters, routeRemainingMeters)
            maneuverDistanceRolloverForSnapshot = true
            AppLog.navigation(
                app,
                ("NAV_MAIN_DISTANCE_ROLLOVER" +
                    " identity=" +
                    key +
                    " previous=" +
                    previousDistance +
                    " closest=" +
                    previousClosest +
                    " incoming=" +
                    meters +
                    " route=" +
                    routeRemainingMeters +
                    " confirmation=second_snapshot"),
            )
            return meters
        }
        val postPassCandidate: Boolean =
            YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                lastManeuverDistanceKey,
                key,
                maneuverDistanceEventBaseMeters,
                maneuverDistanceEventClosestMeters,
                meters,
                lastManeuverRouteRemainingMeters,
                routeRemainingMeters,
                YandexCoreBridgeClient.MANEUVER_ROUTE_DELTA_MAX_METERS,
            )
        if (postPassCandidate) {
            val previousDistance: Long = lastManeuverDistanceMeters
            val heldDistance: Long = heldManeuverDistanceForCandidate(routeRemainingMeters)
            rememberManeuverDistance(key, heldDistance, routeRemainingMeters)
            rememberPendingManeuverRollover(
                key,
                maneuverDistanceEventClosestMeters,
                meters,
                routeRemainingMeters,
            )
            AppLog.navigation(
                app,
                ("NAV_MAIN_DISTANCE_ROLLOVER_CANDIDATE" +
                    " identity=" +
                    key +
                    " previous=" +
                    previousDistance +
                    " closest=" +
                    maneuverDistanceEventClosestMeters +
                    " candidate=" +
                    meters +
                    " held=" +
                    heldDistance +
                    " route=" +
                    routeRemainingMeters),
            )
            return heldDistance
        }
        clearPendingManeuverRollover()
        if (
            lastManeuverDistanceMeters <= YandexCoreBridgeClient.MANEUVER_ZERO_METERS &&
                meters > YandexCoreBridgeClient.MANEUVER_ZERO_METERS
        ) {
            rememberManeuverDistance(key, meters, routeRemainingMeters)
            AppLog.line(
                app,
                ("Yandex Core Bridge: maneuver distance recovered from zero" +
                    " meters=" +
                    meters +
                    " route=" +
                    routeRemainingMeters),
            )
            return meters
        }
        val routeDidNotGrow: Boolean =
            routeRemainingKnown && routeRemainingMeters <= lastManeuverRouteRemainingMeters + 10L
        val noisyBackstep: Boolean = routeDidNotGrow && meters > lastManeuverDistanceMeters + 5L
        val jumpThreshold: Long = Math.max(80L, Math.round(lastManeuverDistanceMeters * 0.45))
        val largeUnknownJump: Boolean =
            !routeRemainingKnown && meters > lastManeuverDistanceMeters + jumpThreshold
        if (noisyBackstep || largeUnknownJump) {
            var corrected: Long = lastManeuverDistanceMeters
            if (
                routeDelta > 0L &&
                    routeDelta <= YandexCoreBridgeClient.MANEUVER_ROUTE_DELTA_MAX_METERS
            ) {
                corrected = Math.max(0L, lastManeuverDistanceMeters - routeDelta)
                rememberManeuverDistance(key, corrected, routeRemainingMeters)
            } else {
                lastManeuverRouteRemainingMeters = routeRemainingMeters
            }
            return corrected
        }
        if (
            routeDelta > 0L &&
                routeDelta <= YandexCoreBridgeClient.MANEUVER_ROUTE_DELTA_MAX_METERS &&
                lastManeuverDistanceMeters > 0L
        ) {
            val projected: Long = Math.max(0L, lastManeuverDistanceMeters - routeDelta)
            if (projected < meters) {
                rememberManeuverDistance(key, projected, routeRemainingMeters)
                return projected
            }
        }
        rememberManeuverDistance(key, meters, routeRemainingMeters)
        return meters
    }

    private fun rememberManeuverDistance(key: String, meters: Long, routeRemainingMeters: Long) {
        if (!key.equals(lastManeuverDistanceKey) || maneuverDistanceEventBaseMeters <= 0L) {
            startManeuverDistanceEvent(key, meters, routeRemainingMeters)
            return
        }
        lastManeuverDistanceKey = key
        lastManeuverDistanceMeters = meters
        lastManeuverRouteRemainingMeters = routeRemainingMeters
        if (meters > YandexCoreBridgeClient.MANEUVER_ZERO_METERS) {
            if (maneuverDistanceEventClosestMeters <= 0L) {
                maneuverDistanceEventClosestMeters = meters
            } else {
                maneuverDistanceEventClosestMeters =
                    Math.min(maneuverDistanceEventClosestMeters, meters)
            }
        }
    }

    private fun startManeuverDistanceEvent(key: String?, meters: Long, routeRemainingMeters: Long) {
        lastManeuverDistanceKey = key
        lastManeuverDistanceMeters = meters
        lastManeuverRouteRemainingMeters = routeRemainingMeters
        maneuverDistanceEventBaseMeters =
            if (meters > YandexCoreBridgeClient.MANEUVER_ZERO_METERS) meters else -1L
        maneuverDistanceEventClosestMeters =
            if (meters > YandexCoreBridgeClient.MANEUVER_ZERO_METERS) meters else -1L
        clearPendingManeuverRollover()
    }

    private fun heldManeuverDistanceForCandidate(routeRemainingMeters: Long): Long {
        var held: Long =
            if (lastManeuverDistanceMeters > YandexCoreBridgeClient.MANEUVER_ZERO_METERS)
                lastManeuverDistanceMeters
            else maneuverDistanceEventClosestMeters
        if (held <= YandexCoreBridgeClient.MANEUVER_ZERO_METERS) return Math.max(0L, held)
        if (lastManeuverRouteRemainingMeters >= 0L && routeRemainingMeters >= 0L) {
            val routeDelta: Long = lastManeuverRouteRemainingMeters - routeRemainingMeters
            if (
                routeDelta > 0L &&
                    routeDelta <= YandexCoreBridgeClient.MANEUVER_ROUTE_DELTA_MAX_METERS
            ) {
                held = Math.max(YandexCoreBridgeClient.MANEUVER_ZERO_METERS + 1L, held - routeDelta)
            }
        }
        return held
    }

    private fun rememberPendingManeuverRollover(
        key: String?,
        closestMeters: Long,
        meters: Long,
        routeRemainingMeters: Long,
    ) {
        pendingManeuverRolloverKey = key
        pendingManeuverRolloverClosestMeters = closestMeters
        pendingManeuverRolloverMeters = meters
        pendingManeuverRolloverRouteRemainingMeters = routeRemainingMeters
        pendingManeuverRolloverAtElapsed = SystemClock.elapsedRealtime()
    }

    private fun clearPendingManeuverRollover() {
        pendingManeuverRolloverKey = ""
        pendingManeuverRolloverClosestMeters = -1L
        pendingManeuverRolloverMeters = -1L
        pendingManeuverRolloverRouteRemainingMeters = -1L
        pendingManeuverRolloverAtElapsed = -1L
    }

    private fun logManeuverProjection(
        identity: String?,
        mode: String?,
        previousMeters: Long,
        incomingMeters: Long,
        stateMeters: Long,
        outputMeters: Long,
        routeRemainingMeters: Long,
    ) {
        val now: Long = SystemClock.elapsedRealtime()
        val key: String =
            YandexCoreBridgeClient.lower(identity) + "|" + YandexCoreBridgeClient.lower(mode)
        if (key.equals(lastManeuverProjectionLogKey) && now - lastManeuverProjectionLogAt < 5000L) {
            return
        }
        lastManeuverProjectionLogKey = key
        lastManeuverProjectionLogAt = now
        AppLog.line(
            app,
            ("Yandex Core Bridge: maneuver continuity" +
                " mode=" +
                (if (mode == null) "" else mode.trim()) +
                " previous=" +
                previousMeters +
                " incoming=" +
                incomingMeters +
                " state=" +
                stateMeters +
                " tx=" +
                (if (outputMeters <= YandexCoreBridgeClient.MANEUVER_ZERO_METERS) "hold"
                else java.lang.String.valueOf(outputMeters)) +
                " route=" +
                routeRemainingMeters),
        )
    }

    private fun resetDistanceGuards() {
        resetManeuverDistanceGuard()
    }

    private fun resetBridgeSessionState() {
        resetRouteGuards()
        sourceSampleGate.reset()
        lastState = ""
        lastSignature = ""
        lastSpeedSignature = ""
        lastSpeedSnapshotSentAt = 0L
        lastSourceSampleLogAt = -1L
        lastBroadcastSnapshotAt = -1L
        lastHeartbeatBroadcastLogAt = -1L
        synchronized(broadcastLock) {
            lastAppliedCoalescingIdentity = ""
            lastAppliedCoalescingManeuverMeters = -1L
        }
    }

    private fun resetRouteGuards() {
        resetManeuverDistanceGuard()
        resetLiveRouteGuard()
    }

    private fun resetManeuverDistanceGuard() {
        lastManeuverDistanceKey = ""
        lastManeuverProjectionLogKey = ""
        lastManeuverProjectionLogAt = 0L
        lastManeuverDistanceMeters = -1L
        lastManeuverRouteRemainingMeters = -1L
        maneuverDistanceEventBaseMeters = -1L
        maneuverDistanceEventClosestMeters = -1L
        clearPendingManeuverRollover()
        maneuverDistanceRolloverForSnapshot = false
    }

    private fun resetManeuverGuardForRouteChange(snapshot: Bundle?) {
        if (snapshot == null) return
        val routeId = YandexCoreBridgeClient.firstString(snapshot, "route_id", "routeId")
        val marker =
            YandexCoreBridgeClient.lower(
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "last_callback",
                    "callback",
                    "event_type",
                    "bridge_reason",
                    "reason",
                )
            )
        val rerouting: Boolean = marker.contains("rerout") || marker.contains("перестро")
        val routeChanged: Boolean =
            !TextUtils.isEmpty(routeId) &&
                !TextUtils.isEmpty(lastLiveRouteId) &&
                !routeId.equals(lastLiveRouteId)
        if (!rerouting && !routeChanged) return
        if (!TextUtils.isEmpty(lastManeuverDistanceKey)) {
            AppLog.line(
                app,
                ("Yandex Core Bridge: maneuver guard reset by " +
                    (if (routeChanged) "route_id" else "reroute") +
                    " lastRouteId=" +
                    lastLiveRouteId +
                    " routeId=" +
                    routeId),
            )
        }
        resetManeuverDistanceGuard()
    }

    private fun resetLiveRouteGuard() {
        lastLiveRouteMetricsAt = -1L
        lastLiveRouteStartedAt = -1L
        lastLiveRouteRemainingMeters = -1L
        lastLiveRouteId = ""
    }

    private fun rememberLiveRoute(snapshot: Bundle?) {
        val remaining: Long = YandexCoreBridgeClient.routeRemainingMeters(snapshot)
        if (remaining < 0L && !YandexCoreBridgeClient.hasRouteMetrics(snapshot)) return
        val now: Long = SystemClock.elapsedRealtime()
        val routeId = YandexCoreBridgeClient.firstString(snapshot, "route_id", "routeId")
        val newRoute: Boolean =
            lastLiveRouteMetricsAt <= 0L ||
                (!TextUtils.isEmpty(routeId) && !routeId.equals(lastLiveRouteId))
        if (newRoute) {
            lastLiveRouteStartedAt = now
            lastLiveRouteId = routeId
        }
        lastLiveRouteMetricsAt = now
        if (remaining >= 0L) lastLiveRouteRemainingMeters = remaining
    }

    private fun finishedSnapshotConfirmed(snapshot: Bundle?, freshness: Long): Boolean {
        val now: Long = SystemClock.elapsedRealtime()
        if (lastLiveRouteMetricsAt <= 0L || lastLiveRouteStartedAt <= 0L) return false
        if (now - lastLiveRouteMetricsAt > YandexCoreBridgeClient.FINISH_RECENT_ROUTE_MS)
            return false
        if (now - lastLiveRouteStartedAt < YandexCoreBridgeClient.FINISH_MIN_ROUTE_ALIVE_MS)
            return false
        val remaining: Long = YandexCoreBridgeClient.routeRemainingMeters(snapshot)
        val snapshotNear: Boolean =
            remaining >= 0L && remaining <= YandexCoreBridgeClient.FINISH_MAX_REMAINING_METERS
        val previousNear: Boolean =
            lastLiveRouteRemainingMeters >= 0L &&
                lastLiveRouteRemainingMeters <=
                    YandexCoreBridgeClient.FINISH_PREVIOUS_REMAINING_METERS
        return snapshotNear &&
            previousNear &&
            freshness <= YandexCoreBridgeClient.FINISH_RECENT_ROUTE_MS
    }

    private fun liveRouteAgeMs(): Long {
        return if (lastLiveRouteStartedAt <= 0L) -1L
        else Math.max(0L, SystemClock.elapsedRealtime() - lastLiveRouteStartedAt)
    }

    private fun logMissingProvider() {
        val now: Long = SystemClock.elapsedRealtime()
        if (now - lastMissingProviderLogAt < YandexCoreBridgeClient.MISSING_PROVIDER_LOG_MS) return
        lastMissingProviderLogAt = now
        AppLog.line(app, "Yandex Core Bridge: provider not found")
    }

    private fun logError(stage: String?, e: Exception) {
        val now: Long = SystemClock.elapsedRealtime()
        if (now - lastErrorLogAt < YandexCoreBridgeClient.MISSING_PROVIDER_LOG_MS) return
        lastErrorLogAt = now
        AppLog.line(
            app,
            ("Yandex Core Bridge " + stage + ": " + e.javaClass.getSimpleName() + " " + e.message),
        )
    }

    companion object {
        private const val ACTIVE_POLL_MS: Long = 250L
        private const val IDLE_POLL_MS: Long = 1000L
        private const val MISSING_PROVIDER_POLL_MS: Long = 5000L
        private const val ERROR_POLL_MS: Long = 3000L
        private const val MAX_ACTIVE_FRESHNESS_MS: Long = 2500L
        private const val MISSING_PROVIDER_LOG_MS: Long = 30000L
        private const val SPEED_SNAPSHOT_MIN_MS: Long = 1000L
        private const val SOURCE_SAMPLE_MAX_AGE_MS: Long = 15000L
        private const val SOURCE_SAMPLE_LOG_INTERVAL_MS: Long = 10000L
        private const val HEARTBEAT_LOG_INTERVAL_MS: Long = 10000L
        private const val BROADCAST_PROVIDER_SUPPRESS_MS: Long = 5000L
        private const val BROADCAST_APPLY_MIN_MS: Long = 200L
        private const val FINISH_MAX_REMAINING_METERS: Long = 35L
        private const val FINISH_PREVIOUS_REMAINING_METERS: Long = 80L
        private const val FINISH_RECENT_ROUTE_MS: Long = 45000L
        private const val BACKGROUND_SUSPENSION_ROUTE_EVIDENCE_MS: Long = 45000L
        private const val FINISH_MIN_ROUTE_ALIVE_MS: Long = 3000L
        private const val ROUTE_REMAINING_MANEUVER_CONFLICT_METERS: Long = 30L
        private const val MANEUVER_ROUTE_DELTA_MAX_METERS: Long = 350L
        private const val REROUTE_ROUTE_GROWTH_METERS: Long = 25L
        private const val MANEUVER_ZERO_METERS: Long = 1L
        private const val MANEUVER_ROLLOVER_PENDING_TTL_MS: Long = 3000L
        private val INTERNAL_DISTANCE_TRANSITION: String = "_kia_main_distance_transition"
        private var shared: YandexCoreBridgeClient? = null

        private const val MAX_PENDING_BROADCASTS: Int = 64

        @JvmStatic
        fun get(context: Context): YandexCoreBridgeClient {
            synchronized(YandexCoreBridgeClient::class.java) {
                if (YandexCoreBridgeClient.shared == null)
                    YandexCoreBridgeClient.shared = YandexCoreBridgeClient(context)
                return YandexCoreBridgeClient.shared!!
            }
        }

        private fun snapshotResult(delayMs: Long, accepted: Boolean): SnapshotApplyResult {
            return SnapshotApplyResult(delayMs, accepted)
        }

        private fun mainManeuverDistanceIdentity(
            snapshot: Bundle?,
            selected: MainManeuverSnapshot?,
        ): String? {
            if (snapshot == null || selected == null || TextUtils.isEmpty(selected.maneuver)) {
                return ""
            }
            val routeId =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "route_id",
                    "routeId",
                    "route_uuid",
                    "routeUuid",
                )
            if (TextUtils.isEmpty(routeId)) return ""
            return YandexMainDistanceContinuityPolicy.identity(
                routeId,
                selected.provenance,
                selected.maneuver,
            )
        }

        private fun routeLive(state: String?): Boolean {
            return YandexCoreBridgeContract.STATE_ACTIVE.equals(state) ||
                YandexCoreBridgeContract.STATE_LOADING.equals(state) ||
                YandexCoreBridgeContract.STATE_REROUTING.equals(state)
        }

        private fun priorityBroadcastSnapshot(snapshot: Bundle?): Boolean {
            val state =
                YandexCoreBridgeClient.lower(
                    YandexCoreBridgeClient.firstString(snapshot, "state", "route_state", "status")
                )
            if (
                YandexCoreBridgeContract.STATE_OFF.equals(state) ||
                    YandexCoreBridgeContract.STATE_LOADING.equals(state) ||
                    YandexCoreBridgeContract.STATE_REROUTING.equals(state) ||
                    YandexCoreBridgeContract.STATE_FINISHED.equals(state)
            ) {
                return true
            }
            if (
                snapshot != null &&
                    snapshot.containsKey("active") &&
                    !YandexCoreBridgeClient.bool(snapshot, "active", true)
            ) {
                return true
            }
            if (
                snapshot != null &&
                    snapshot.containsKey("navi_on") &&
                    !YandexCoreBridgeClient.bool(snapshot, "navi_on", true)
            ) {
                return true
            }
            val marker =
                YandexCoreBridgeClient.lower(
                    YandexCoreBridgeClient.firstString(
                        snapshot,
                        "last_callback",
                        "callback",
                        "event_type",
                        "bridge_reason",
                        "reason",
                    )
                )
            return marker.contains("finish") ||
                marker.contains("off") ||
                marker.contains("remove") ||
                marker.contains("task_removed") ||
                marker.contains("loading") ||
                marker.contains("rerout")
        }

        private fun travelDeltaMeters(snapshot: Bundle?, freshness: Long): Long {
            if (snapshot == null || freshness <= 300L) return 0L
            val speed: Long =
                YandexCoreBridgeClient.longValue(snapshot, "current_speed_kmh", "speed_kmh", -1L)
            if (speed <= 0L || speed > 220L) return 0L
            val cappedFreshness: Long =
                Math.min(freshness, YandexCoreBridgeClient.MAX_ACTIVE_FRESHNESS_MS)
            return Math.min(120L, Math.round(speed * cappedFreshness / 3600.0))
        }

        private fun normalizeSnapshotEnvelope(snapshot: Bundle?) {
            if (snapshot == null) return
            if (!snapshot.containsKey("source")) {
                snapshot.putString("source", YandexCoreBridgeContract.SOURCE)
            }
            if (!snapshot.containsKey("state") && snapshot.containsKey("status")) {
                snapshot.putString("state", java.lang.String.valueOf(snapshot.get("status")))
            }
            if (!snapshot.containsKey("route_state") && snapshot.containsKey("state")) {
                snapshot.putString("route_state", java.lang.String.valueOf(snapshot.get("state")))
            }
        }

        private fun freshnessMs(snapshot: Bundle?, timestampElapsed: Long): Long {
            val explicit: Long =
                YandexCoreBridgeClient.longValue(snapshot, "freshness_ms", "freshnessMs", -1L)
            if (explicit >= 0L) return explicit
            if (timestampElapsed <= 0L) return 0L
            return Math.max(0L, SystemClock.elapsedRealtime() - timestampElapsed)
        }

        private fun hasRouteMetrics(snapshot: Bundle?): Boolean {
            val distance =
                YandexCoreBridgeClient.first(
                    YandexCoreBridgeClient.metersText(
                        YandexCoreBridgeClient.firstLong(
                            snapshot,
                            -1L,
                            "remaining_meters",
                            "remainingMeters",
                            "remaining_distance_meters",
                            "distance_left_meters",
                        )
                    ),
                    YandexCoreBridgeClient.firstString(
                        snapshot,
                        "remaining_distance",
                        "route_distance",
                        "edistance",
                        "distance_left",
                        "eta_distance_left",
                    ),
                )
            val time =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "route_time",
                    "remaining_time",
                    "time_left",
                    "eta_time_left",
                    "etime",
                )
            val arrival =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "arrival_time",
                    "arrival",
                    "eta",
                    "atime",
                )
            return !TextUtils.isEmpty(distance) &&
                (!TextUtils.isEmpty(time) || !TextUtils.isEmpty(arrival))
        }

        private fun routeRemainingMeters(snapshot: Bundle?): Long {
            val numeric: Long =
                YandexCoreBridgeClient.firstLong(
                    snapshot,
                    -1L,
                    "remaining_meters",
                    "remainingMeters",
                    "remaining_distance_meters",
                    "distance_left_meters",
                )
            val text: Long =
                YandexCoreBridgeClient.firstDistanceMeters(
                    snapshot,
                    "remaining_distance",
                    "route_distance",
                    "edistance",
                    "distance_left",
                    "eta_distance_left",
                )
            return YandexCoreBridgeClient.correctedRemainingMeters(
                if (numeric >= 0L) numeric else text,
                YandexCoreBridgeClient.routeTotalMeters(snapshot),
            )
        }

        private fun routeTotalMeters(snapshot: Bundle?): Long {
            val numeric: Long =
                YandexCoreBridgeClient.firstLong(
                    snapshot,
                    -1L,
                    "total_route_meters",
                    "route_total_meters",
                    "routeTotalMeters",
                    "route_total_distance_meters",
                )
            if (numeric >= 0L) return numeric
            return YandexCoreBridgeClient.firstDistanceMeters(
                snapshot,
                "route_total_len",
                "route_full_len",
                "route_total_distance",
                "full_route_distance",
                "routeTotalDistance",
                "totalRouteDistance",
            )
        }

        private fun snapshotSignature(state: String?, snapshot: Bundle?): String {
            if (snapshot == null || snapshot.isEmpty()) return YandexCoreBridgeClient.lower(state)
            val keys: ArrayList<String> = ArrayList(snapshot.keySet())
            Collections.sort(keys)
            val out: StringBuilder = StringBuilder(YandexCoreBridgeClient.lower(state))
            for (key: String? in keys) {
                if (YandexCoreBridgeClient.volatileKey(key)) continue
                val value: Any? = snapshot.get(key)
                if (value == null) continue
                out.append('|')
                    .append(key)
                    .append('=')
                    .append(java.lang.String.valueOf(value).trim())
            }
            return out.toString()
        }

        private fun volatileKey(key: String?): Boolean {
            return YandexSnapshotSemantics.isEnvelopeKey(key)
        }

        private fun hasMicroEnvelope(snapshot: Bundle?): Boolean {
            if (snapshot == null || snapshot.isEmpty()) return false
            for (key: String? in snapshot.keySet()) {
                if (YandexSnapshotSemantics.isMicroKey(key)) return true
            }
            return false
        }

        private fun microIdentity(snapshot: Bundle?): String? {
            if (!YandexCoreBridgeClient.hasMicroEnvelope(snapshot)) return ""
            return YandexSnapshotSemantics.microIdentity(
                true,
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "lane_maneuver",
                    "micro_maneuver",
                    "ignored_lane_maneuver",
                ),
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "lane_highlight",
                    "lane_highlighted_direction",
                    "highlighted_direction",
                    "highlighted_directions",
                    "recommended_lanes",
                    "ignored_recommended_lanes",
                ),
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "lane_topology_json",
                    "lane_topology",
                    "raw_lane_topology",
                    "lane_items",
                    "raw_lane_items",
                ),
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "lane_sign_position",
                    "lane_position",
                    "upcoming_lane_position",
                ),
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "direction_sign_items",
                    "raw_direction_sign_items",
                    "road_scheme_raw",
                    "upcoming_direction_signs",
                    "upcoming_lane_signs",
                ),
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "route_road_options",
                    "gray_road_options",
                    "road_options",
                ),
            )
        }

        private fun microStateIdentity(snapshot: Bundle?): String? {
            if (snapshot == null) return YandexSnapshotSemantics.MICRO_ABSENT_IDENTITY
            if (!YandexCoreBridgeClient.hasSemanticMicroSignal(snapshot)) {
                return YandexSnapshotSemantics.MICRO_ABSENT_IDENTITY
            }
            val identity = YandexCoreBridgeClient.microIdentity(snapshot)
            if (TextUtils.isEmpty(identity) || "micro|none".equals(identity)) {
                return "micro|distance_only"
            }
            return identity
        }

        private fun coalescingIdentity(snapshot: Bundle?): String? {
            if (snapshot == null) return ""
            var state: String? =
                YandexCoreBridgeClient.lower(
                    YandexCoreBridgeClient.firstString(snapshot, "state", "route_state", "status")
                )
            if (TextUtils.isEmpty(state)) {
                state =
                    if (YandexCoreBridgeClient.bool(snapshot, "active", false))
                        YandexCoreBridgeContract.STATE_ACTIVE
                    else YandexCoreBridgeContract.STATE_OFF
            }
            val selected: MainManeuverSnapshot = YandexCoreBridgeClient.selectMainManeuver(snapshot)
            return YandexSnapshotSemantics.coalescingIdentity(
                state,
                YandexCoreBridgeClient.firstString(snapshot, "route_id", "routeId"),
                selected.maneuver,
                selected.provenance,
                selected.mainEvent,
                selected.roundaboutExit,
                YandexCoreBridgeClient.firstString(snapshot, "route_action", "routeAction"),
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "road_options",
                    "route_road_options",
                    "gray_road_options",
                    "available_directions",
                ),
                YandexCoreBridgeClient.microStateIdentity(snapshot),
            )
        }

        private fun coalescingManeuverMeters(snapshot: Bundle?): Long {
            if (snapshot == null) return -1L
            val selected: MainManeuverSnapshot = YandexCoreBridgeClient.selectMainManeuver(snapshot)
            return if (selected == null) -1L
            else YandexSnapshotSemantics.distanceMeters(selected.distance)
        }

        private fun hasSemanticMicroSignal(snapshot: Bundle?): Boolean {
            if (snapshot == null || snapshot.isEmpty()) return false
            val meters: Long =
                YandexCoreBridgeClient.firstLong(
                    snapshot,
                    -1L,
                    "lane_distance_meters",
                    "micro_distance_meters",
                    "lane_sign_distance_meters",
                    "upcoming_lane_distance_meters",
                )
            if (meters > 0L) return true
            val distance =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "lane_distance",
                    "micro_distance",
                    "lane_sign_distance",
                    "upcoming_lane_distance",
                )
            if (YandexSnapshotSemantics.distanceMeters(distance) > 0L) return true
            return !TextUtils.isEmpty(
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "lane_maneuver",
                    "micro_maneuver",
                    "ignored_lane_maneuver",
                    "lane_highlight",
                    "lane_highlighted_direction",
                    "highlighted_direction",
                    "highlighted_directions",
                    "recommended_lanes",
                    "ignored_recommended_lanes",
                    "lane_topology_json",
                    "lane_topology",
                    "raw_lane_topology",
                    "lane_items",
                    "raw_lane_items",
                    "direction_sign_items",
                    "raw_direction_sign_items",
                    "road_scheme_raw",
                    "upcoming_direction_signs",
                    "upcoming_lane_signs",
                )
            )
        }

        private fun candidateDistance(snapshot: Bundle?, vararg prefixes: String?): String {
            if (snapshot == null || prefixes == null) return ""
            for (prefix: String? in prefixes) {
                val meters: Long =
                    YandexCoreBridgeClient.firstLong(
                        snapshot,
                        -1L,
                        prefix.toString() + "_maneuver_distance_meters",
                        prefix.toString() + "_distance_to_maneuver_meters",
                        prefix.toString() + "_distance_meters",
                    )
                val numeric = YandexCoreBridgeClient.metersText(meters)
                if (!TextUtils.isEmpty(numeric)) return numeric
                val text =
                    YandexCoreBridgeClient.firstString(
                        snapshot,
                        prefix.toString() + "_maneuver_distance",
                        prefix.toString() + "_distance_to_maneuver",
                        prefix.toString() + "_distance",
                    )
                if (!TextUtils.isEmpty(text)) return text
            }
            return ""
        }

        private fun selectMainManeuver(snapshot: Bundle?): MainManeuverSnapshot {
            val mainEvent =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "main_event",
                    "voice_main_event",
                    "route_event",
                    "first_event_text",
                    "event",
                )
            val eventManeuver = YandexCoreBridgeClient.clusterManeuverFromEvent(mainEvent)
            val annotationManeuver =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "annotation_maneuver",
                    "guide_maneuver",
                    "displayed_annotation_maneuver",
                    "current_annotation_maneuver",
                )
            val rawManeuver =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "maneuver",
                    "maneuver_type",
                    "maneuver_action",
                    "current_maneuver",
                    "image_id",
                    "imageId",
                    "direction",
                )
            val notificationManeuver =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "notification_maneuver",
                    "notification_maneuver_action",
                    "notification_direction",
                )
            val voiceManeuver =
                YandexCoreBridgeClient.maneuverFromVoiceText(
                    YandexCoreBridgeClient.firstString(
                        snapshot,
                        "maneuver_text",
                        "voice_hint",
                        "voice_prompt",
                    )
                )
            val maneuver =
                YandexCoreBridgeClient.first(
                    annotationManeuver,
                    rawManeuver,
                    eventManeuver,
                    notificationManeuver,
                    voiceManeuver,
                )
            val provenance: String =
                if (!TextUtils.isEmpty(annotationManeuver)) "annotation"
                else if (!TextUtils.isEmpty(rawManeuver)) "bridge_main"
                else if (!TextUtils.isEmpty(eventManeuver)) "route_event"
                else if (!TextUtils.isEmpty(notificationManeuver)) "notification" else "voice"
            val annotationDistance =
                YandexCoreBridgeClient.candidateDistance(
                    snapshot,
                    "annotation",
                    "guide",
                    "displayed_annotation",
                    "current_annotation",
                )
            val notificationDistance =
                YandexCoreBridgeClient.candidateDistance(snapshot, "notification")
            val genericDistance = YandexCoreBridgeClient.maneuverDistance(snapshot)
            var distance: String? =
                YandexMainDistanceContinuityPolicy.selectDistanceForProvenance(
                    provenance,
                    YandexCoreBridgeClient.sameManeuverValue(annotationManeuver, rawManeuver),
                    annotationDistance,
                    notificationDistance,
                    genericDistance,
                )
            if (TextUtils.isEmpty(distance) && !TextUtils.isEmpty(eventManeuver)) {
                distance = "0 м"
            }
            return MainManeuverSnapshot(
                mainEvent,
                maneuver,
                provenance,
                distance,
                annotationManeuver,
                annotationDistance,
                notificationManeuver,
                notificationDistance,
                YandexCoreBridgeClient.selectedRoundaboutExit(snapshot, provenance),
            )
        }

        private fun isRoundaboutManeuverValue(value: String?): Boolean {
            val text = YandexCoreBridgeClient.lower(value)
            return text.contains("roundabout") ||
                text.contains("circular") ||
                text.contains("круг") ||
                text.contains("кольц")
        }

        private fun sameManeuverValue(left: String?, right: String?): Boolean {
            val first = YandexCoreBridgeClient.lower(left).replace('-', '_')
            val second = YandexCoreBridgeClient.lower(right).replace('-', '_')
            return !TextUtils.isEmpty(first) && first.equals(second)
        }

        private fun selectedRoundaboutExit(snapshot: Bundle?, provenance: String?): String? {
            val annotationExit =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "annotation_roundabout_exit",
                    "annotation_roundabout_exit_number",
                    "annotation_exit_number",
                    "guide_roundabout_exit",
                    "guide_roundabout_exit_number",
                    "guide_exit_number",
                )
            val notificationExit =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "notification_roundabout_exit",
                    "notification_roundabout_exit_number",
                    "notification_exit_number",
                )
            val genericExit =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "roundabout_exit",
                    "roundabout_exit_number",
                    "maneuver_exit_number",
                    "exit_number",
                )
            return YandexSnapshotSemantics.roundaboutExitForProvenance(
                provenance,
                annotationExit,
                notificationExit,
                genericExit,
            )
        }

        private fun putString(intent: Intent, key: String?, value: String?) {
            if (!TextUtils.isEmpty(value)) intent.putExtra(key, value)
        }

        private fun putMeters(intent: Intent?, meters: Long, vararg keys: String?) {
            if (intent == null || meters < 0L || keys == null) return
            val value = java.lang.String.valueOf(meters)
            for (key: String? in keys) {
                if (!TextUtils.isEmpty(key)) intent.putExtra(key, value)
            }
        }

        private fun putMeters(bundle: Bundle?, meters: Long, vararg keys: String?) {
            if (bundle == null || meters < 0L || keys == null) return
            for (key: String? in keys) {
                if (!TextUtils.isEmpty(key)) bundle.putLong(key, meters)
            }
        }

        private fun first(vararg values: String?): String? {
            if (values == null) return ""
            for (value: String? in values) {
                if (!TextUtils.isEmpty(value)) return value
            }
            return ""
        }

        private fun putLaneDirectionFlags(intent: Intent, value: String?) {
            if (TextUtils.isEmpty(value)) return
            intent.putExtra(
                "lane_has_straight",
                YandexCoreBridgeClient.laneTextHas(value, "straight"),
            )
            intent.putExtra("lane_has_right", YandexCoreBridgeClient.laneTextHas(value, "right"))
            intent.putExtra("lane_has_left", YandexCoreBridgeClient.laneTextHas(value, "left"))
            intent.putExtra(
                "lane_has_uturn_left",
                YandexCoreBridgeClient.laneTextHas(value, "uturn_left"),
            )
            intent.putExtra(
                "lane_has_uturn_right",
                YandexCoreBridgeClient.laneTextHas(value, "uturn_right"),
            )
        }

        private fun laneTextHas(value: String?, token: String?): Boolean {
            val text = YandexCoreBridgeClient.lower(value).replace('-', '_')
            if (TextUtils.isEmpty(text) || TextUtils.isEmpty(token)) return false
            val parts = java.util.regex.Pattern.compile("[,;\\s=:@#|()\\[\\]{}]+").split(text)
            val needle = YandexCoreBridgeClient.lower(token).replace('-', '_')
            for (part: String? in parts) {
                if (needle.equals(part)) return true
            }
            if ("straight".equals(needle)) {
                return text.contains("straight") ||
                    text.contains("forward") ||
                    text.contains("ahead") ||
                    text.contains("прям")
            }
            if ("right".equals(needle)) {
                return text.contains("right90") ||
                    text.contains("right45") ||
                    text.contains("right135") ||
                    text.contains("turn_right") ||
                    text.contains("направо") ||
                    text.contains("право")
            }
            if ("left".equals(needle)) {
                return text.contains("left90") ||
                    text.contains("left45") ||
                    text.contains("left135") ||
                    text.contains("turn_left") ||
                    text.contains("налево") ||
                    text.contains("лево")
            }
            if ("uturn_left".equals(needle)) {
                return text.contains("left180") ||
                    text.contains("turn_back_left") ||
                    text.contains("uturn_left") ||
                    text.contains("u_turn_left")
            }
            if ("uturn_right".equals(needle)) {
                return text.contains("right180") ||
                    text.contains("turn_back_right") ||
                    text.contains("uturn_right") ||
                    text.contains("u_turn_right")
            }
            return false
        }

        private fun laneGuidanceConflicts(routeDirections: String?, laneText: String?): Boolean {
            if (TextUtils.isEmpty(routeDirections) || TextUtils.isEmpty(laneText)) return false
            val routeLeft: Boolean = YandexCoreBridgeClient.directionLeft(routeDirections)
            val routeRight: Boolean = YandexCoreBridgeClient.directionRight(routeDirections)
            val laneLeft: Boolean = YandexCoreBridgeClient.directionLeft(laneText)
            val laneRight: Boolean = YandexCoreBridgeClient.directionRight(laneText)
            if (routeLeft && laneRight && !laneLeft) return true
            return routeRight && laneLeft && !laneRight
        }

        private fun staleLaneGuidanceConflictsWithMain(
            snapshot: Bundle?,
            highlight: String?,
            laneDistance: String?,
            laneMeters: Long,
        ): Boolean {
            if (
                !TextUtils.isEmpty(YandexCoreBridgeClient.metersText(laneMeters)) ||
                    !TextUtils.isEmpty(laneDistance)
            ) {
                return false
            }
            val main =
                YandexCoreBridgeClient.firstString(
                    snapshot,
                    "direction",
                    "maneuver",
                    "imageId",
                    "route_action",
                    "maneuver_text",
                    "voice_hint",
                )
            if (TextUtils.isEmpty(main) || TextUtils.isEmpty(highlight)) return false
            val mainLeft: Boolean = YandexCoreBridgeClient.directionLeft(main)
            val mainRight: Boolean = YandexCoreBridgeClient.directionRight(main)
            val highlightLeft: Boolean = YandexCoreBridgeClient.directionLeft(highlight)
            val highlightRight: Boolean = YandexCoreBridgeClient.directionRight(highlight)
            if (mainLeft && highlightRight && !highlightLeft) return true
            return mainRight && highlightLeft && !highlightRight
        }

        private fun clearLaneExtras(intent: Intent?) {
            if (intent == null) return
            val keys: kotlin.Array<String?> =
                arrayOf<String?>(
                    "ignored_lane_items",
                    "ignored_raw_lane_items",
                    "ignored_allowed_directions",
                    "lane_items",
                    "raw_lane_items",
                    "allowed_directions",
                    "ignored_recommended_lanes",
                    "ignored_lane_maneuver",
                    "recommended_lanes",
                    "lane_highlight",
                    "lane_highlighted_direction",
                    "highlighted_direction",
                    "highlighted_directions",
                    "lane_maneuver",
                    "lane_topology",
                    "lane_topology_json",
                    "road_scheme_raw",
                    "direction_sign_items",
                    "route_road_options",
                    "gray_road_options",
                )
            for (key: String? in keys) {
                intent.removeExtra(key)
            }
        }

        private fun directionLeft(value: String?): Boolean {
            return YandexCoreBridgeClient.laneTextHas(value, "left") ||
                YandexCoreBridgeClient.laneTextHas(value, "uturn_left")
        }

        private fun directionRight(value: String?): Boolean {
            return YandexCoreBridgeClient.laneTextHas(value, "right") ||
                YandexCoreBridgeClient.laneTextHas(value, "uturn_right")
        }

        private fun firstString(bundle: Bundle?, vararg keys: String?): String {
            if (bundle == null || keys == null) return ""
            for (key: String? in keys) {
                if (TextUtils.isEmpty(key) || !bundle.containsKey(key)) continue
                val value: Any? = bundle.get(key)
                val text = if (value == null) "" else java.lang.String.valueOf(value).trim()
                if (!TextUtils.isEmpty(text)) return text
            }
            return ""
        }

        private fun maneuverDistance(snapshot: Bundle?): String {
            var distance =
                YandexCoreBridgeClient.metersText(YandexCoreBridgeClient.maneuverMeters(snapshot))
            if (TextUtils.isEmpty(distance)) {
                distance =
                    YandexCoreBridgeClient.firstString(
                        snapshot,
                        "maneuver_distance",
                        "current_maneuver_distance",
                        "distance_to_maneuver",
                        "next_maneuver_distance",
                        "last_maneuver_distance",
                    )
            }
            return distance
        }

        private fun earlyFinishManeuver(maneuver: String?, snapshot: Bundle?): Boolean {
            if (!YandexCoreBridgeClient.isFinishManeuver(maneuver)) return false
            val remaining: Long = YandexCoreBridgeClient.routeRemainingMeters(snapshot)
            val maneuverMeters: Long = YandexCoreBridgeClient.maneuverMeters(snapshot)
            if (remaining > YandexCoreBridgeClient.FINISH_MAX_REMAINING_METERS) return true
            return maneuverMeters > YandexCoreBridgeClient.FINISH_MAX_REMAINING_METERS
        }

        private fun isFinishManeuver(value: String?): Boolean {
            val text = YandexCoreBridgeClient.lower(value)
            return text.contains("finish") ||
                text.contains("arriv") ||
                text.contains("destination") ||
                text.contains("финиш")
        }

        private fun clusterManeuverFromEvent(event: String?): String {
            val value = YandexCoreBridgeClient.lower(event)
            if (TextUtils.isEmpty(value)) return ""
            return if (value.startsWith("context_ra_")) value else ""
        }

        private fun maneuverFromVoiceText(value: String?): String {
            val text = YandexCoreBridgeClient.lower(value).replace('-', '_')
            if (TextUtils.isEmpty(text)) return ""
            if (
                text.contains("камер") ||
                    text.contains("контроль") ||
                    text.contains("скорост") ||
                    text.contains("speed")
            ) {
                return ""
            }
            if (
                text.contains("разворот") ||
                    text.contains("разверн") ||
                    text.contains("u_turn") ||
                    text.contains("uturn")
            ) {
                if (text.contains("прав")) return "context_ra_turn_back_right"
                return "context_ra_turn_back_left"
            }
            if (
                text.contains("круг") ||
                    text.contains("кольц") ||
                    text.contains("roundabout") ||
                    text.contains("circular")
            ) {
                return "context_ra_in_circular_movement"
            }
            if (
                text.contains("съезд") ||
                    text.contains("выезд") ||
                    text.contains("exit") ||
                    text.contains("ramp")
            ) {
                if (text.contains("лев") || text.contains("left")) return "context_ra_exit_left"
                if (text.contains("прав") || text.contains("right")) return "context_ra_exit_right"
            }
            if (text.contains("держ") || text.contains("keep")) {
                if (text.contains("лев") || text.contains("left")) return "context_ra_exit_left"
                if (text.contains("прав") || text.contains("right")) return "context_ra_exit_right"
            }
            if (
                text.contains("налево") ||
                    text.contains("левее") ||
                    text.contains("turn left") ||
                    text.contains("left")
            ) {
                return "context_ra_turn_left"
            }
            if (
                text.contains("направо") ||
                    text.contains("правее") ||
                    text.contains("turn right") ||
                    text.contains("right")
            ) {
                return "context_ra_turn_right"
            }
            if (
                text.contains("прямо") ||
                    text.contains("straight") ||
                    text.contains("forward") ||
                    text.contains("ahead")
            ) {
                return "context_ra_forward"
            }
            return ""
        }

        private fun maneuverMeters(snapshot: Bundle?): Long {
            return YandexCoreBridgeClient.firstLong(
                snapshot,
                -1L,
                "maneuver_distance_meters",
                "current_maneuver_distance_meters",
                "distance_to_maneuver_meters",
            )
        }

        private fun bool(bundle: Bundle?, key: String?, fallback: Boolean): Boolean {
            if (bundle == null || !bundle.containsKey(key)) return fallback
            val value: Any? = bundle.get(key)
            if (value is Boolean) return value as Boolean
            if (value is Number) return value.toInt() != 0
            if (value == null) return fallback
            val text = YandexCoreBridgeClient.lower(java.lang.String.valueOf(value))
            if ("1".equals(text) || "true".equals(text) || "yes".equals(text) || "on".equals(text))
                return true
            if ("0".equals(text) || "false".equals(text) || "no".equals(text) || "off".equals(text))
                return false
            return fallback
        }

        private fun containsAnyKey(bundle: Bundle?, vararg keys: String?): Boolean {
            if (bundle == null || keys == null) return false
            for (key: String? in keys) {
                if (!TextUtils.isEmpty(key) && bundle.containsKey(key)) return true
            }
            return false
        }

        private fun putBooleanIfPresent(intent: Intent?, bundle: Bundle?, key: String?) {
            if (
                intent == null ||
                    bundle == null ||
                    TextUtils.isEmpty(key) ||
                    !bundle.containsKey(key)
            ) {
                return
            }
            intent.putExtra(key, YandexCoreBridgeClient.bool(bundle, key, false))
        }

        private fun longValue(bundle: Bundle?, key1: String?, key2: String?, fallback: Long): Long {
            val value: Long = YandexCoreBridgeClient.longValue(bundle, key1, fallback)
            return if (value != fallback) value
            else YandexCoreBridgeClient.longValue(bundle, key2, fallback)
        }

        private fun longValue(
            bundle: Bundle?,
            key1: String?,
            key2: String?,
            key3: String?,
            fallback: Long,
        ): Long {
            var value: Long = YandexCoreBridgeClient.longValue(bundle, key1, fallback)
            if (value != fallback) return value
            value = YandexCoreBridgeClient.longValue(bundle, key2, fallback)
            return if (value != fallback) value
            else YandexCoreBridgeClient.longValue(bundle, key3, fallback)
        }

        private fun longValue(bundle: Bundle?, key: String?, fallback: Long): Long {
            if (bundle == null || TextUtils.isEmpty(key) || !bundle.containsKey(key))
                return fallback
            val value: Any? = bundle.get(key)
            if (value is Number) return value.toLong()
            if (value == null) return fallback
            try {
                return Math.round(
                    java.lang.Double.parseDouble(
                        java.lang.String.valueOf(value).trim().replace(',', '.')
                    )
                )
            } catch (e: Exception) {
                return fallback
            }
        }

        private fun firstLong(bundle: Bundle?, fallback: Long, vararg keys: String?): Long {
            if (keys == null) return fallback
            for (key: String? in keys) {
                val value: Long = YandexCoreBridgeClient.longValue(bundle, key, fallback)
                if (value != fallback) return value
            }
            return fallback
        }

        private fun firstDistanceMeters(bundle: Bundle?, vararg keys: String?): Long {
            if (bundle == null || keys == null) return -1L
            for (key: String? in keys) {
                if (TextUtils.isEmpty(key) || !bundle.containsKey(key)) continue
                val meters: Long = YandexCoreBridgeClient.distanceTextMeters(bundle.get(key))
                if (meters >= 0L) return meters
            }
            return -1L
        }

        private fun distanceTextMeters(value: Any?): Long {
            if (value == null) return -1L
            if (value is Number) return Math.max(0L, value.toLong())
            return YandexSnapshotSemantics.distanceMeters(java.lang.String.valueOf(value))
        }

        private fun correctedRemainingMeters(remainingMeters: Long, totalMeters: Long): Long {
            if (remainingMeters < 0L || totalMeters < 0L) return remainingMeters
            if (totalMeters == 0L) return remainingMeters
            if (totalMeters < 100L && remainingMeters >= 1000L) return remainingMeters
            val margin: Long = Math.max(25L, Math.round(totalMeters * 0.05))
            return if (remainingMeters > totalMeters + margin) totalMeters else remainingMeters
        }

        private fun correctedRemainingByGuidance(
            remainingMeters: Long,
            maneuverMeters: Long,
        ): Long {
            if (remainingMeters < 0L || maneuverMeters < 0L) return remainingMeters
            if (
                maneuverMeters <=
                    remainingMeters +
                        YandexCoreBridgeClient.ROUTE_REMAINING_MANEUVER_CONFLICT_METERS
            ) {
                return remainingMeters
            }
            return maneuverMeters
        }

        private fun metersText(meters: Long): String {
            if (meters < 0) return ""
            if (meters < 1000) return meters.toString() + " м"
            val km: Double = meters / 1000.0
            if (km < 10.0) return String.format(Locale.US, "%.1f км", km)
            return Math.round(km).toString() + " км"
        }

        private fun secondsText(seconds: Long): String {
            if (seconds < 0) return ""
            val minutes: Long = Math.max(1L, Math.round(seconds / 60.0))
            if (minutes < 60) return minutes.toString() + " мин"
            val hours: Long = minutes / 60
            val rest: Long = minutes % 60
            return if (rest == 0L) hours.toString() + " ч"
            else hours.toString() + " ч " + rest + " мин"
        }

        private fun lower(value: String?): String {
            return if (value == null) "" else value.trim().lowercase(Locale.US)
        }
    }
}
