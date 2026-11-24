package kia.app.navigation.domain

import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.NavigationState
import kia.app.core.settings.AppSettings
import kia.app.navigation.capture.MviteNavigationMessage
import kia.app.navigation.capture.NavigationSourceArbitrator
import kia.app.navigation.capture.NavigationSourcePolicy
import kia.app.navigation.capture.TeyesIngressPolicy
import kia.app.navigation.capture.YandexCoreBridgeContract
import kia.app.navigation.cluster.NavigationClusterSender
import kia.app.protocol.adapter.AdapterGateway
import kia.app.protocol.adapter.AdapterTxOutcome

class NavigationFeature private constructor(context: Context) {
    private val app: Context
    private val sender: NavigationClusterSender
    private val clusterTx: NavigationClusterTxController
    private val sourceArbitrator: NavigationSourceArbitrator =
        NavigationSourceArbitrator(NavigationFeature.AUTO_SOURCE_OWNER_FRESH_MS)
    private var navigationEnabledSynced: Boolean = false
    private var lastNavigationEnabled: Boolean = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var mviteExitNumber: Int = 0
    private var mviteRoundabout: MviteNavigationMessage? = null
    private var mviteOverspeed: Boolean = false
    private var mviteCameraDistance: String? = ""
    private val mviteCameraExpiry: Runnable = Runnable {
        synchronized(this@NavigationFeature) {
            mviteCameraDistance = ""
            sendConfiguredText()
        }
    }
    private var state: NavigationState = NavigationState.empty()
    private var lastDirectManeuverAt: Long = 0
    private var lastRouteFinishEtaAt: Long = 0
    private var routeStartedAt: Long = 0
    private var overspeedTextUntil: Long = 0
    private var speedLimitTextUntil: Long = 0
    private var finishHoldUntil: Long = 0
    private var finishRecoverySuppressUntil: Long = 0
    private var laneGuidanceUntil: Long = 0
    private var overspeedGeneration: Int = 0
    private var finishGeneration: Int = 0
    private var maneuverTextGeneration: Int = 0
    private var pendingInactiveGeneration: Int = 0
    private var routeLoadingFallbackGeneration: Int = 0
    private var routeReroutingGeneration: Int = 0
    private var yandexWatchdogGeneration: Int = 0
    private var currentSpeedExpiryGeneration: Int = 0
    private var speedLimitExpiryGeneration: Int = 0
    private var restoredRouteConfirmationGeneration: Int = 0
    // Local presentation cache only; successful serial delivery is tracked separately below.
    private var lastSentSpeedLimit: Int = -1
    private val roadSpeedLimitTx: RoadSpeedLimitTxPolicy = RoadSpeedLimitTxPolicy()
    private val roadSpeedLimitRepeat: RoadSpeedLimitRepeatPolicy = RoadSpeedLimitRepeatPolicy()
    private var dashboardRoadLimit: DashboardRoadSpeedLimit = DashboardRoadSpeedLimit.NONE
    private var knownRoadSpeedLimitSource: Int = 0
    private var knownRoadSpeedLimitSourceMode: Int = 0
    private var pendingRoadSpeedLimitTrigger: String = "fresh"
    private var lastRoadSpeedLimitDecision: String = ""
    private var lastRoadSpeedLimitDecisionAt: Long = 0
    private val roadSpeedLimitRepeatTask: Runnable = Runnable {
        synchronized(this@NavigationFeature) { repeatRoadSpeedLimit() }
    }
    private var pendingRoadSpeedLimitSource: Int = 0
    private var pendingRoadSpeedLimitSourceMode: Int = 0
    private val roadSpeedLimitRetry: Runnable = Runnable {
        synchronized(this@NavigationFeature) { attemptPendingRoadSpeedLimit(true) }
    }
    private val maneuverProgressTracker: ManeuverProgressTracker = ManeuverProgressTracker()
    private val maneuverProgressRolloverMarker: ManeuverProgressRolloverMarker =
        ManeuverProgressRolloverMarker(NavigationFeature.MANEUVER_PROGRESS_MARKER_TTL_MS)
    private var lastSentEtaKey: String = ""
    private var lastSentEtaTimeKey: String = ""
    private var lastSentNavigationText: String = ""
    private var activeManeuverHintText: String = ""
    private var lastSentFinishDirectionKey: String = ""
    private var lastFinishDirectionReason: String? = ""
    private var staleFinishStreetAfterPointChange: String? = ""
    private var pendingInactiveSource: String? = ""
    private var lastRoundaboutExitManeuver: String? = ""
    private var lastRoundaboutExitText: String? = ""
    private var lastRoundaboutExitKey: String = ""
    private var lastNavigationDecisionKey: String = ""
    private var lastTxMicroGuardKey: String = ""
    private var lastMicroTxHoldKey: String = ""
    private var lastMicroTxLogKey: String = ""
    private var lastSourceGateLogKey: String = ""
    private var lastSourceGateLogAt: Long = 0
    private var pendingMicroRestoreSource: String? = ""
    private var activeLaneHint: String? = ""
    private var activeLaneSource: String? = ""
    private var activeEventHint: String? = ""
    private var activeEventSource: String? = ""
    private var activeLaneRaw: String = ""
    private var activeLanePosition: String = ""
    private var activeRoadSchemeRaw: String = ""
    private var activeUpcomingRaw: String = ""
    private var activeGrayRoadId: String? = ""
    private var activeGrayRoadKey: String? = ""
    private var activeCompleteLaneGrayRoad: String? = ""
    private var activeMicroManeuver: String? = ""
    private var activeMicroDistance: String? = ""
    private var activeMicroDebugDistance: String? = ""
    private var activeMicroStatus: String? = ""
    private var activeMicroSource: String? = ""
    private var activeMicroTxAllowed: Boolean = true
    private var activeMicroPostPassUntil: Long = 0
    private var activeLaneDistance: String? = ""
    private var routeReroutingMinUntil: Long = 0
    private var activeClusterVisual: String? = ""
    private var learnedRoadKey: String? = ""
    private var learnedRoadMask: Int = 0
    private var learnedRoadUntil: Long = 0
    private var lastGpsCurrentSpeed: Int = -1
    private var lastGpsCurrentSpeedAt: Long = 0
    private var lastGpsSpeedLogAt: Long = 0
    private var lastYandexCurrentSpeedAt: Long = 0
    private var lastYandexRoadSpeedLimitAt: Long = 0
    private var yandexSpeedLimitClearCandidateAt: Long = 0
    private var lastYandexNavigationPacketAt: Long = 0
    private var lastYandexWatchdogHoldLogAt: Long = 0
    private var restoredRouteAwaitingConfirmation: Boolean = false
    private var lastFinishDirectionAt: Long = 0
    private var lastRoundaboutExitUntil: Long = 0
    private var lastRouteGuidanceAt: Long = 0
    private var lastRouteMetricsAt: Long = 0
    private var routeLoadingMinUntil: Long = 0
    private var routeReroutingUntil: Long = 0
    private var lastBackgroundResendAt: Long = 0
    private var laneHintUntil: Long = 0
    private var eventHintUntil: Long = 0
    private var navigationRawUntil: Long = 0
    private var grayRoadUntil: Long = 0
    private var completeLaneGrayUntil: Long = 0
    private var laneDistanceUntil: Long = 0
    private var finishCompassSuppressUntil: Long = 0
    private var activeRouteTotalDistance: String = ""
    private var activeRouteId: String = ""
    private var mainPreviewIdentity: String = ""
    private var lastMainPreviewDecisionKey: String = ""
    private var microHintUntil: Long = 0
    private var maneuverTextUntil: Long = 0
    private var mainPreviewLastPositiveMeters: Float = -1f
    private var mainPreviewRevealLatched: Boolean = false
    private var yandexFinishSuppressed: Boolean = false
    private var lastGpsLatitude: Double = Double.NaN
    private var lastGpsLongitude: Double = Double.NaN
    private var lastGpsPointAt: Long = 0
    private var lastGpsBearing: Float = Float.NaN
    private var lastGpsBearingAt: Long = 0
    private var lastGpsSpeedMetersPerSecond: Float = Float.NaN
    private var lastGpsCourseBearing: Float = Float.NaN
    private var lastGpsCourseBearingAt: Long = 0
    private var lastYandexHeading: Float = Float.NaN
    private var lastYandexHeadingAt: Long = 0
    private var lastYandexHeadingLogAt: Long = 0
    private var lastDeviceHeading: Float = Float.NaN
    private var lastDeviceHeadingAccuracy: Float = Float.NaN
    private var lastDeviceHeadingAt: Long = 0
    private var finishLatitude: Double = Double.NaN
    private var finishLongitude: Double = Double.NaN
    private var finishGeocodeKey: String = ""
    private var lastFinishGeocodeAt: Long = 0
    private var finishGeocodeGeneration: Int = 0
    private var currentGeocodeKey: String = ""
    private var lastCurrentGeocodeAt: Long = 0
    private var currentGeocodeGeneration: Int = 0
    private var waitingForRoute: Boolean = false
    private var finalSegmentFinishDirectionActive: Boolean = false
    private var finalSegmentManeuver: String? = ""
    private var displayedFinishDirectionStep: Int = -1
    private var lastKnownFinishDirectionStep: Int = -1
    private var targetFinishDirectionStep: Int = -1
    private var activeFinishDirectionDistance: Float = 0f
    private var activeFinishDirectionKm: Boolean = false
    private var finishDirectionAnimationRunning: Boolean = false

    private val finishDirectionAnimator: Runnable =
        object : Runnable {
            override fun run() {
                synchronized(this@NavigationFeature) {
                    finishDirectionAnimationRunning = false
                    if (!finishDirectionAnimationAllowed()) return
                    if (displayedFinishDirectionStep == targetFinishDirectionStep) return
                    val next: Int =
                        NavigationFeature.nextCompassStep(
                            displayedFinishDirectionStep,
                            targetFinishDirectionStep,
                        )
                    sendFinishDirectionStep(
                        next,
                        targetFinishDirectionStep,
                        activeFinishDirectionDistance,
                        activeFinishDirectionKm,
                        NavigationFeature.first(
                            lastFinishDirectionReason,
                            "finish_direction_animation",
                        ),
                        false,
                    )
                    scheduleFinishDirectionAnimation()
                }
            }
        }

    private val microRestoreTask: Runnable =
        object : Runnable {
            override fun run() {
                synchronized(this@NavigationFeature) {
                    val now: Long = System.currentTimeMillis()
                    if (
                        !state.active || state.finishReached || activeMicroManeuver.isNullOrEmpty()
                    ) {
                        return
                    }
                    if (microHintUntil > now) {
                        scheduleMicroRestore(pendingMicroRestoreSource)
                        return
                    }
                    val expiredMicro: String? = activeMicroManeuver
                    val restoreSource =
                        NavigationFeature.first(
                            pendingMicroRestoreSource,
                            activeMicroSource,
                            "micro_expired",
                        )
                    clearMicroHintHold()
                    clearLaneDistanceHold()
                    state =
                        state.withNavigationDebug(
                            state.mainManeuverId,
                            state.routeActionId,
                            "",
                            "",
                            "expired",
                            state.grayRoadId,
                            state.grayRoadScheme,
                            now,
                        )
                    publishNavigationState()
                    restoreMainVisualAfterMicroEnd("expiry:" + restoreSource, false)
                    AppLog.navigation(
                        app,
                        ("NAV_MICRO_EXPIRED" +
                            " route=" +
                            NavigationFeature.first(activeRouteId, "-") +
                            " micro=" +
                            NavigationFeature.clean(expiredMicro) +
                            " source=" +
                            NavigationFeature.clean(restoreSource)),
                    )
                }
            }
        }

    init {
        this.app = context.getApplicationContext()
        this.sender = NavigationClusterSender(app)
        this.clusterTx =
            NavigationClusterTxController(
                sender,
                object : NavigationClusterTxController.VisualSink {
                    override fun onClusterVisual(visual: String?) {
                        applyClusterVisual(visual)
                    }
                },
            )
        syncRouteStateFromStore()
        syncNavigationEnabled(AppSettings.navigationEnabled(app))
        scheduleRestoredRouteConfirmationIfNeeded()
    }

    /**
     * Applies the global navigation switch to both capture ownership and the cluster state.
     * Background clients call this while synchronizing their lifecycle, and ingress calls it
     * defensively so a disabled feature cannot leave a previously active route on the cluster.
     */
    @kotlin.jvm.Synchronized
    fun syncNavigationEnabled(enabled: Boolean) {
        val changed: Boolean = !navigationEnabledSynced || lastNavigationEnabled != enabled
        navigationEnabledSynced = true
        lastNavigationEnabled = enabled
        if (!enabled) {
            dashboardRoadLimit = DashboardRoadSpeedLimit.NONE
            cancelRoadSpeedLimitDelivery()
            if (
                changed ||
                    state.active ||
                    state.finishReached ||
                    state.speedExceeded ||
                    !state.speedLimit.isNullOrEmpty() ||
                    lastSentSpeedLimit > 0
            ) {
                forceInactive("navigation_disabled")
            }
            sourceArbitrator.reset()
            if (changed) {
                AppLog.navigation(app, "NAV_GLOBAL_GATE disabled; cluster state cleared")
            }
            return
        }
        if (changed) {
            seedSourceOwnerFromState()
            AppLog.navigation(app, "NAV_GLOBAL_GATE enabled")
        }
    }

    private fun seedSourceOwnerFromState() {
        val source: Int =
            NavigationFeature.navigationSourceFamily(if (state == null) "" else state.source)
        if (
            state != null &&
                state.active &&
                NavigationSourcePolicy.ingressAllowed(
                    AppSettings.navigationEnabled(app),
                    AppSettings.navSourceMode(app),
                    source,
                )
        ) {
            sourceArbitrator.seed(source, SystemClock.elapsedRealtime())
        } else {
            sourceArbitrator.reset()
        }
    }

    private fun acceptNavigationSource(source: Int, event: Int, reason: String?): Boolean {
        val navigationEnabled: Boolean = AppSettings.navigationEnabled(app)
        syncNavigationEnabled(navigationEnabled)
        val result: kia.app.navigation.capture.NavigationSourceArbitrator.Result =
            sourceArbitrator.accept(
                navigationEnabled,
                AppSettings.navSourceMode(app),
                source,
                event,
                SystemClock.elapsedRealtime(),
            )
        if (!result.accepted) {
            logSourceGate(source, result.decision, reason)
            return false
        }
        val stateSource: Int =
            NavigationFeature.navigationSourceFamily(if (state == null) "" else state.source)
        val stateOwnedByOther: Boolean =
            state != null &&
                state.active &&
                stateSource != NavigationSourcePolicy.SOURCE_NONE &&
                stateSource != source
        if (
            event == NavigationSourcePolicy.EVENT_ROUTE_ACTIVE &&
                (result.switchedOwner || stateOwnedByOther)
        ) {
            dashboardRoadLimit = DashboardRoadSpeedLimit.NONE
            cancelRoadSpeedLimitDelivery()
            AppLog.navigation(
                app,
                ("NAV_SOURCE_HANDOFF " +
                    NavigationSourcePolicy.sourceName(
                        if (stateSource != NavigationSourcePolicy.SOURCE_NONE) stateSource
                        else result.previousOwner
                    ) +
                    " -> " +
                    NavigationSourcePolicy.sourceName(source) +
                    " reason=" +
                    NavigationFeature.clean(reason)),
            )
            forceInactive("auto_source_handoff_" + NavigationSourcePolicy.sourceName(source))
        }
        return true
    }

    private fun logSourceGate(source: Int, decision: Int, reason: String?) {
        val key: String = source.toString() + "|" + decision + "|" + NavigationFeature.clean(reason)
        val now: Long = SystemClock.elapsedRealtime()
        if (key.equals(lastSourceGateLogKey) && now - lastSourceGateLogAt < 10000L) return
        lastSourceGateLogKey = key
        lastSourceGateLogAt = now
        AppLog.navigation(
            app,
            ("NAV_SOURCE_IGNORED source=" +
                NavigationSourcePolicy.sourceName(source) +
                " reason=" +
                NavigationSourcePolicy.decisionName(decision) +
                " event=" +
                NavigationFeature.clean(reason) +
                " owner=" +
                NavigationSourcePolicy.sourceName(sourceArbitrator.ownerSource())),
        )
    }

    @kotlin.jvm.Synchronized
    fun dashboardRoadSpeedLimit(): DashboardRoadSpeedLimit {
        return if (
            NavigationSourcePolicy.ingressAllowed(
                AppSettings.navigationEnabled(app),
                AppSettings.navSourceMode(app),
                dashboardRoadLimit.source,
            )
        )
            dashboardRoadLimit
        else DashboardRoadSpeedLimit.NONE
    }

    @kotlin.jvm.Synchronized
    fun active(): Boolean {
        return state.active
    }

    /**
     * A route restored from disk must not suppress the compass until a live producer confirms it.
     * The logical route snapshot is retained briefly so a running navigator can resume atomically.
     */
    @kotlin.jvm.Synchronized
    fun activeForCompassSuppression(): Boolean {
        return state.active && !restoredRouteAwaitingConfirmation
    }

    @kotlin.jvm.Synchronized
    fun finishCompassSuppressed(): Boolean {
        return System.currentTimeMillis() < finishCompassSuppressUntil
    }

    @kotlin.jvm.Synchronized
    fun finishDirectionOverlayStep(): Int {
        if (!finishDirectionShouldOverride()) {
            return Integer.MIN_VALUE
        }
        if (!finishPointKnown() || !gpsPointKnown()) {
            return Integer.MIN_VALUE
        }
        val absoluteBearing: Float = bearingToFinish()
        var heading: Float = finishReferenceHeading()
        if (java.lang.Float.isNaN(heading)) heading = absoluteBearing
        return NavigationFeature.compassDirectionStep(absoluteBearing - heading)
    }

    @kotlin.jvm.Synchronized
    fun finishDirectionHeadingNeeded(): Boolean {
        return state.active &&
            !state.finishReached &&
            finishPointKnown() &&
            gpsPointKnown() &&
            (finishDirectionShouldOverride() || state.loading() || waitingForRoute)
    }

    @kotlin.jvm.Synchronized
    fun updateGpsSpeed(metersPerSecond: Float) {
        if (
            java.lang.Float.isNaN(metersPerSecond) ||
                java.lang.Float.isInfinite(metersPerSecond) ||
                metersPerSecond < 0f
        ) {
            return
        }
        val kmh: Int = Math.max(0, Math.round(metersPerSecond * 3.6f))
        val nowElapsed: Long = SystemClock.elapsedRealtime()
        val nowWall: Long = System.currentTimeMillis()
        val previousGpsSpeed: Int = lastGpsCurrentSpeed
        val previousGpsSpeedAt: Long = lastGpsCurrentSpeedAt
        lastGpsCurrentSpeed = kmh
        lastGpsCurrentSpeedAt = nowElapsed
        if (yandexCurrentSpeedFresh(nowElapsed)) {
            if (nowElapsed - lastGpsSpeedLogAt >= NavigationFeature.GPS_SPEED_LOG_MS) {
                lastGpsSpeedLogAt = nowElapsed
                AppLog.line(app, "Navigation GPS speed ignored; Yandex speed is active")
            }
            return
        }
        lastYandexCurrentSpeedAt = 0L
        scheduleGpsCurrentSpeedExpiry(nowElapsed)
        if (
            kmh == previousGpsSpeed &&
                nowElapsed - previousGpsSpeedAt < NavigationFeature.GPS_SPEED_MIN_INTERVAL_MS &&
                NavigationFeature.speedNumber(state.currentSpeed) == kmh
        ) {
            return
        }

        var exceeded: Boolean = state.speedExceeded
        val limit: Int = NavigationFeature.speedNumber(state.speedLimit)
        if (limit > 0) {
            exceeded = kmh > limit
        }
        val exceededChanged: Boolean = exceeded != state.speedExceeded
        if (exceeded) startOverspeedTextWindow() else clearOverspeedTextWindow()

        val source = stableSourceForSpeed("gps_speed")
        state =
            NavigationState(
                state.active,
                state.finishReached,
                exceeded,
                state.maneuver,
                state.maneuverText,
                state.maneuverDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                state.finishStreet,
                state.speedLimit,
                java.lang.String.valueOf(kmh),
                source,
                nowWall,
            )
        publishNavigationState()

        if (
            nowElapsed - lastGpsSpeedLogAt >= NavigationFeature.GPS_SPEED_LOG_MS ||
                Math.abs(kmh - previousGpsSpeed) >= 5
        ) {
            lastGpsSpeedLogAt = nowElapsed
            AppLog.line(app, "Navigation GPS speed: " + kmh)
        }
        if (exceededChanged) sendConfiguredText()
    }

    @kotlin.jvm.Synchronized
    fun updateGpsLocation(location: Location?) {
        updateGpsLocation(location, true, true)
    }

    @kotlin.jvm.Synchronized
    fun updateGpsLocation(location: Location?, updateSpeed: Boolean, updateBearing: Boolean) {
        if (location == null) return
        val now: Long = System.currentTimeMillis()
        rememberCurrentPoint(
            location.getLatitude(),
            location.getLongitude(),
            now,
            if (location.hasAccuracy()) location.getAccuracy() else Float.NaN,
            "gps_location",
        )
        applyGpsMotion(location, updateSpeed, updateBearing, now)
        onGpsUpdated(now)
    }

    /** Accept independently ordered motion fields without rewinding the current position. */
    @kotlin.jvm.Synchronized
    fun updateGpsMotion(location: Location?, updateSpeed: Boolean, updateBearing: Boolean) {
        if (location == null || (!updateSpeed && !updateBearing)) return
        val now: Long = System.currentTimeMillis()
        applyGpsMotion(location, updateSpeed, updateBearing, now)
        onGpsUpdated(now)
    }

    private fun applyGpsMotion(
        location: Location,
        updateSpeed: Boolean,
        updateBearing: Boolean,
        now: Long,
    ) {
        val speed: Float = if (location.hasSpeed()) Math.max(0f, location.getSpeed()) else Float.NaN
        if (updateBearing && location.hasBearing() && NavigationFeature.gpsBearingReliable(speed)) {
            lastGpsBearing = NavigationFeature.normalizeDegrees(location.getBearing())
            lastGpsBearingAt = now
        }
        if (updateSpeed && location.hasSpeed()) {
            lastGpsSpeedMetersPerSecond = speed
            updateGpsSpeed(location.getSpeed())
        }
    }

    private fun onGpsUpdated(now: Long) {
        expireStaleYandexRoadSpeedLimit(now, "gps")
        maybeRecoverStaleFinishFromMovement()
        if (maybeAutoFinishStaleNearDestination(now)) return
        if (routeLoadingFallbackReady() && state.maneuver.isNullOrEmpty()) {
            if (sendDirectionToFinishForLoading(false)) return
        }
        sendDirectionToFinishIfNeeded(false)
    }

    @kotlin.jvm.Synchronized
    fun updateDeviceHeading(headingDegrees: Float, accuracyDegrees: Float, source: String?) {
        if (java.lang.Float.isNaN(headingDegrees) || java.lang.Float.isInfinite(headingDegrees))
            return
        val now: Long = System.currentTimeMillis()
        var cleanHeading: Float = NavigationFeature.normalizeDegrees(headingDegrees)
        if (!java.lang.Float.isNaN(lastDeviceHeading) && now - lastDeviceHeadingAt <= 2500L) {
            val alpha: Float = if (accuracyDegrees > 35f) 0.22f else 0.38f
            cleanHeading = NavigationFeature.blendDegrees(lastDeviceHeading, cleanHeading, alpha)
            if (
                Math.abs(
                    NavigationFeature.normalizeSignedDegrees(cleanHeading - lastDeviceHeading)
                ) < 0.8f && now - lastDeviceHeadingAt < 250L
            ) {
                return
            }
        }
        lastDeviceHeading = cleanHeading
        lastDeviceHeadingAccuracy = accuracyDegrees
        lastDeviceHeadingAt = now
        if (routeLoadingFallbackReady() && state.maneuver.isNullOrEmpty()) {
            if (sendDirectionToFinishForLoading(false)) return
        }
        sendDirectionToFinishIfNeeded(false)
    }

    @kotlin.jvm.Synchronized
    fun handle(intent: Intent?) {
        if (intent == null || intent.getAction() == null) return
        val action = intent.getAction()
        val raw = NavigationFeature.rawExtras(intent)
        Log.i(NavigationFeature.TAG, action.toString() + " " + raw)
        AppLog.line(app, "Navigation raw: " + NavigationFeature.shortRaw(action, raw))
        if (!NavigationFeature.isYandexNavigationIntent(intent)) {
            AppLog.line(
                app,
                "Navigation ignored: non-Yandex source " +
                    NavigationFeature.first(NavigationFeature.text(intent, "source"), action),
            )
            return
        }
        val source = NavigationFeature.first(NavigationFeature.text(intent, "source"), action)
        if (!YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source))) {
            AppLog.line(
                app,
                "Navigation ignored legacy Yandex packet; Core Bridge required: " +
                    NavigationFeature.clean(source),
            )
            return
        }
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationFeature.yandexIngressEvent(intent),
                action,
            )
        ) {
            return
        }
        rememberYandexRoutePose(intent, source)
        if (yandexRouteHeartbeatIntent(intent)) {
            touchYandexWatchdog(source)
        }
        rememberNavigationRawDebug(intent, System.currentTimeMillis())
        if (NavigationFeature.routeReroutingIntent(intent)) {
            if (state.active && !state.finishReached) {
                clearRouteChangeVisualHold("route rerouting " + NavigationFeature.clean(source))
            }
            startRouteReroutingHold(source)
        }
        var fullSnapshotMicroCleared: Boolean = false
        if (
            NavigationFeature.bool(intent, "bridge_full_snapshot", false) &&
                NavigationFeature.bool(intent, "micro_clear", false)
        ) {
            fullSnapshotMicroCleared = clearMicroForFullBridgeSnapshot(intent, source)
        }
        if (
            NavigationFeature.ACTION_MANEUVER.equals(action) ||
                NavigationFeature.KIA_ACTION_MANEUVER.equals(action)
        ) {
            handleManeuver(intent)
        } else if (
            NavigationFeature.ACTION_ETA.equals(action) ||
                NavigationFeature.KIA_ACTION_ETA.equals(action)
        ) {
            handleEta(intent)
        } else if (
            NavigationFeature.ACTION_NAVI_ON.equals(action) ||
                NavigationFeature.KIA_ACTION_NAVI_ON.equals(action)
        ) {
            handleActive(intent)
        } else if (
            NavigationFeature.ACTION_SPEED.equals(action) ||
                NavigationFeature.KIA_ACTION_SPEED.equals(action)
        ) {
            handleSpeed(intent)
        } else if (
            NavigationFeature.ACTION_EXCEEDED.equals(action) ||
                NavigationFeature.KIA_ACTION_EXCEEDED.equals(action)
        ) {
            applyNavigationExceeded(NavigationFeature.bool(intent, "exceeded", false), source)
        }
        if (
            fullSnapshotMicroCleared &&
                !incomingRouteIdChangesActiveRoute(intent) &&
                !shouldTransmitActiveMicroToCluster()
        ) {
            restoreMainVisualAfterMicroEnd(
                "full_snapshot:" + NavigationFeature.clean(source),
                false,
            )
        }
    }

    @kotlin.jvm.Synchronized
    fun handleMvite(message: MviteNavigationMessage) {
        val kind = message.kind!!
        val event: Int =
            when (kind) {
                kia.app.navigation.capture.MviteNavigationMessage.Kind.ROUTE_ACTIVE ->
                    if (message.active) NavigationSourcePolicy.EVENT_ROUTE_ACTIVE
                    else NavigationSourcePolicy.EVENT_ROUTE_END
                kia.app.navigation.capture.MviteNavigationMessage.Kind.MANEUVER ->
                    NavigationSourcePolicy.EVENT_ROUTE_ACTIVE
                else -> NavigationSourcePolicy.EVENT_PASSIVE
            }
        if (!acceptNavigationSource(NavigationSourcePolicy.SOURCE_MVITE, event, kind.name)) return

        when (kind) {
            kia.app.navigation.capture.MviteNavigationMessage.Kind.ROUTE_ACTIVE ->
                if (message.active) {
                    if (!state.active) setActive(true, NavigationFeature.MVITE_SOURCE)
                    touchYandexWatchdog(NavigationFeature.MVITE_SOURCE)
                } else {
                    resetMviteRouteContext()
                    if (state.active) setActive(false, NavigationFeature.MVITE_SOURCE)
                }
            kia.app.navigation.capture.MviteNavigationMessage.Kind.MANEUVER -> {
                if (NavigationFeature.isRoundaboutManeuver(message.maneuver)) {
                    mviteRoundabout = message
                } else if (mviteRoundabout != null) {
                    mviteRoundabout = null
                    mviteExitNumber = 0
                }
                applyMviteManeuver(message)
            }
            kia.app.navigation.capture.MviteNavigationMessage.Kind.EXIT_NUMBER -> {
                if (mviteExitNumber == message.value) return
                mviteExitNumber = message.value
                if (state.active && mviteRoundabout != null) applyMviteManeuver(mviteRoundabout!!)
            }
            kia.app.navigation.capture.MviteNavigationMessage.Kind.ROUTE_DISTANCE,
            kia.app.navigation.capture.MviteNavigationMessage.Kind.REMAINING_TIME,
            kia.app.navigation.capture.MviteNavigationMessage.Kind.ARRIVAL_TIME -> {
                if (!state.active || state.finishReached) return
                touchYandexWatchdog(NavigationFeature.MVITE_SOURCE)
                val eta: Intent =
                    Intent(NavigationFeature.ACTION_ETA)
                        .putExtra("source", NavigationFeature.MVITE_SOURCE)
                if (kind == kia.app.navigation.capture.MviteNavigationMessage.Kind.ROUTE_DISTANCE) {
                    eta.putExtra(
                        "remaining_meters",
                        java.lang.String.valueOf(message.distanceMeters),
                    )
                } else if (
                    kind == kia.app.navigation.capture.MviteNavigationMessage.Kind.REMAINING_TIME
                ) {
                    val hours: Int = message.value / 60
                    val minutes: Int = message.value % 60
                    eta.putExtra(
                        "route_time",
                        if (hours > 0) hours.toString() + " ч " + minutes + " мин"
                        else minutes.toString() + " мин",
                    )
                    eta.putExtra("arrival_time", state.arrivalTime)
                } else {
                    eta.putExtra("arrival_time", message.text)
                }
                handleEta(eta)
            }
            kia.app.navigation.capture.MviteNavigationMessage.Kind.SPEED_LIMIT ->
                handleSpeed(
                    Intent(NavigationFeature.ACTION_SPEED)
                        .putExtra("source", NavigationFeature.MVITE_SOURCE)
                        .putExtra("speed_limit", message.value)
                        .putExtra("exceeded", mviteOverspeed)
                )
            kia.app.navigation.capture.MviteNavigationMessage.Kind.OVERSPEED -> {
                mviteOverspeed = message.active
                applyNavigationExceeded(mviteOverspeed, NavigationFeature.MVITE_SOURCE)
            }
            kia.app.navigation.capture.MviteNavigationMessage.Kind.CAMERA -> {
                handler.removeCallbacks(mviteCameraExpiry)
                mviteCameraDistance = if (message.active && state.active) message.text else ""
                if (!mviteCameraDistance!!.isEmpty()) {
                    handler.postDelayed(mviteCameraExpiry, NavigationFeature.MVITE_CAMERA_FRESH_MS)
                }
                sendConfiguredText()
            }
        }
    }

    private fun applyMviteManeuver(message: MviteNavigationMessage) {
        if (!state.active && !NavigationFeature.isFinishManeuver(message.maneuver))
            setActive(true, NavigationFeature.MVITE_SOURCE)
        if (!state.active) return
        touchYandexWatchdog(NavigationFeature.MVITE_SOURCE)
        val guidance: Intent =
            Intent(NavigationFeature.ACTION_MANEUVER)
                .putExtra("source", NavigationFeature.MVITE_SOURCE)
                .putExtra("imageId", message.maneuver)
                .putExtra("distance", java.lang.String.valueOf(message.distanceMeters))
                .putExtra("unit", "m")
                .putExtra("next_street", message.text)
        if (mviteRoundabout == message && mviteExitNumber > 0) {
            guidance.putExtra("roundabout_exit", mviteExitNumber.toString() + " съезд")
        }
        handleManeuver(guidance)
    }

    private fun resetMviteRouteContext() {
        mviteExitNumber = 0
        mviteRoundabout = null
        mviteOverspeed = false
        mviteCameraDistance = ""
        handler.removeCallbacks(mviteCameraExpiry)
    }

    private fun applyNavigationExceeded(exceeded: Boolean, source: String?) {
        if (exceeded) startOverspeedTextWindow() else clearOverspeedTextWindow()
        state =
            NavigationState(
                state.active,
                state.finishReached,
                exceeded,
                state.maneuver,
                state.maneuverText,
                state.maneuverDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                state.finishStreet,
                state.speedLimit,
                state.currentSpeed,
                source,
                System.currentTimeMillis(),
            )
        publishNavigationState()
        sendConfiguredText()
        if (state.active) AppLog.line(app, "Navigation exceeded=" + exceeded)
    }

    private fun clearMicroForFullBridgeSnapshot(intent: Intent?, source: String?): Boolean {
        val hadMicro: Boolean =
            !activeMicroManeuver.isNullOrEmpty() ||
                !activeMicroDistance.isNullOrEmpty() ||
                !state.microManeuverId.isNullOrEmpty() ||
                !state.microDistance.isNullOrEmpty()
        clearMicroHintHold()
        clearLaneDistanceHold()
        lastTxMicroGuardKey = ""
        if (!hadMicro) return false
        val now: Long = System.currentTimeMillis()
        state =
            state.withNavigationDebug(
                state.mainManeuverId,
                state.routeActionId,
                "",
                "",
                "cleared_by_full_snapshot",
                state.grayRoadId,
                state.grayRoadScheme,
                now,
            )
        publishNavigationState()
        AppLog.navigation(
            app,
            ("NAV_MICRO_CLEAR" +
                " route=" +
                NavigationFeature.first(
                    NavigationFeature.routeIdFromIntent(intent),
                    activeRouteId,
                    "-",
                ) +
                " seq=" +
                NavigationFeature.first(NavigationFeature.text(intent, "seq"), "-") +
                " source=" +
                NavigationFeature.clean(source)),
        )
        return true
    }

    @kotlin.jvm.Synchronized
    fun handleTeyes(intent: Intent?) {
        if (intent == null) return
        val stateText = NavigationFeature.text(intent, "state")
        val direction =
            NavigationFeature.first(
                NavigationFeature.text(intent, "direction"),
                NavigationFeature.text(intent, "maneuver"),
                "forward",
            )
        val passiveText = NavigationFeature.passiveRoadEventText(intent, direction!!)
        val fallbackManeuver =
            NavigationFeature.normalizeManeuver(direction, intent.getIntExtra("direction_lr", 0))
        val yandexRouteContext: Boolean =
            NavigationFeature.navigationSourceFamily(state.source) !=
                NavigationSourcePolicy.SOURCE_DGIS
        val waitingForManeuver: Boolean =
            state.loading() || routeWaiting() || clusterVisualIsLoading()
        val ingressEvent: Int =
            TeyesIngressPolicy.event(
                NavigationFeature.isOffState(stateText!!),
                !passiveText.isNullOrEmpty(),
                state.active,
                yandexRouteContext,
                waitingForManeuver,
                NavigationFeature.isUsableManeuver(fallbackManeuver!!),
            )
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_YANDEX,
                ingressEvent,
                "teyes:" + NavigationFeature.clean(intent.getAction()),
            )
        ) {
            return
        }
        val raw = NavigationFeature.rawExtras(intent)
        Log.i(NavigationFeature.TAG, intent.getAction() + " " + raw)
        AppLog.line(app, "Navigation raw: " + NavigationFeature.shortRaw(intent.getAction(), raw))
        if (NavigationFeature.isOffState(stateText)) {
            AppLog.line(app, "Navigation: ignored TEYES close state")
            return
        }
        if (!passiveText.isNullOrEmpty()) {
            state =
                NavigationState(
                    state.active,
                    state.finishReached,
                    state.speedExceeded,
                    state.maneuver,
                    state.maneuverText,
                    state.maneuverDistance,
                    state.routeDistance,
                    state.routeTime,
                    state.arrivalTime,
                    state.currentStreet,
                    state.nextStreet,
                    state.finishStreet,
                    state.speedLimit,
                    state.currentSpeed,
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "source"),
                        "teyes_passive",
                    ),
                    System.currentTimeMillis(),
                )
            publishNavigationState()
            if (!state.active) sendNavigationText(passiveText)
            AppLog.line(
                app,
                "Navigation passive event: " +
                    passiveText +
                    " | " +
                    NavigationFeature.clean(direction),
            )
            val limit =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "speed_limit"),
                    NavigationFeature.text(intent, "speedLimit"),
                    NavigationFeature.text(intent, "limit"),
                )
            if (!limit.isNullOrEmpty()) sendSpeed(limit)
            return
        }
        if (
            state.active &&
                yandexRouteContext &&
                (state.loading() || routeWaiting() || clusterVisualIsLoading()) &&
                NavigationFeature.isUsableManeuver(fallbackManeuver)
        ) {
            val now: Long = System.currentTimeMillis()
            waitingForRoute = false
            routeStartedAt = 0L
            lastRouteGuidanceAt = now
            cancelPendingInactive("teyes fallback")
            val fallbackDistance =
                NavigationFeature.first(
                    state.maneuverDistance,
                    NavigationFeature.text(intent, "distance"),
                    NavigationFeature.text(intent, "dist"),
                )
            state =
                NavigationState(
                    true,
                    false,
                    state.speedExceeded,
                    fallbackManeuver,
                    NavigationFeature.maneuverLabel(fallbackManeuver),
                    fallbackDistance,
                    state.routeDistance,
                    state.routeTime,
                    state.arrivalTime,
                    state.currentStreet,
                    state.nextStreet,
                    state.finishStreet,
                    state.speedLimit,
                    state.currentSpeed,
                    "teyes_route_fallback",
                    now,
                )
            publishNavigationState()
            sendManeuverWithFallbackGray(
                fallbackManeuver,
                fallbackDistance,
                maneuverProgressBucket(fallbackManeuver, fallbackDistance),
                true,
            )
            sendConfiguredText()
            AppLog.line(
                app,
                ("Navigation TEYES fallback route event: " +
                    fallbackManeuver +
                    " raw=" +
                    NavigationFeature.shortRaw(intent.getAction(), raw)),
            )
            return
        }
        AppLog.line(
            app,
            "Navigation ignored TEYES route event; Yandex source owns route data: " +
                NavigationFeature.clean(direction),
        )
    }

    @kotlin.jvm.Synchronized
    fun resendForBackgroundDelivery(reason: String?) {
        val now: Long = System.currentTimeMillis()
        if (now - lastBackgroundResendAt < NavigationFeature.BACKGROUND_RESEND_MIN_MS) return
        syncRouteStateFromStore()
        lastBackgroundResendAt = now
        expireStaleYandexRoadSpeedLimit(now, "background_resend")
        if (!state.active || state.finishReached) {
            return
        }
        resetNavigationSendCache()
        resetNavigationTextCache()
        sender.resetNavigationRoute()
        sender.sendActive(true)
        resendKnownRouteData()
        resendCurrentVisual()
        sendConfiguredText()
        AppLog.line(
            app,
            ("Navigation background resend: " +
                NavigationFeature.clean(reason) +
                " | " +
                state.summary()),
        )
    }

    /**
     * Replays the complete resolved navigation state after an exclusive transport operation (for
     * example, a firmware update). Unlike the background-delivery path this is deliberately not
     * rate-limited.
     */
    @kotlin.jvm.Synchronized
    fun resendAfterTransportRecovery(reason: String?) {
        syncRouteStateFromStore()
        if (!state.active || state.finishReached) {
            expireStaleYandexRoadSpeedLimit(System.currentTimeMillis(), "transport_recovery")
            AppLog.navigation(
                app,
                ("NAV_TRANSPORT_RESYNC skipped reason=" +
                    NavigationFeature.clean(reason) +
                    " active=" +
                    state.active +
                    " finished=" +
                    state.finishReached),
            )
            return
        }
        resetNavigationSendCache()
        resetNavigationTextCache()
        sender.resetNavigationRoute()
        sender.sendActive(true)
        resendKnownRouteData()
        resendCurrentVisual()
        sendConfiguredText()
        AppLog.navigation(
            app,
            ("NAV_TRANSPORT_RESYNC sent reason=" +
                NavigationFeature.clean(reason) +
                " visual=" +
                NavigationFeature.clean(activeClusterVisual) +
                " state=" +
                state.summary()),
        )
    }

    @kotlin.jvm.Synchronized
    fun handleDgisNotification(
        imageId: String?,
        distance: String?,
        unit: String?,
        street: String?,
        rawText: String?,
    ) {
        val maneuver = NavigationFeature.normalizeManeuver(NavigationFeature.clean(imageId), 0)
        val normalizedDistance = NavigationFeature.distanceText(distance, unit)
        val normalizedStreet = NavigationFeature.normalizeStreetLabel(street)
        if (
            !NavigationFeature.isUsableManeuver(maneuver!!) &&
                normalizedStreet.isNullOrEmpty() &&
                normalizedDistance.isNullOrEmpty()
        ) {
            AppLog.line(app, "Navigation 2GIS ignored: " + NavigationFeature.clean(rawText))
            return
        }
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_DGIS,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE,
                "2gis_notification",
            )
        ) {
            return
        }
        setActive(true, "2gis_notification")
        waitingForRoute = false
        routeStartedAt = 0L
        val now: Long = System.currentTimeMillis()
        lastRouteGuidanceAt = now
        if (!NavigationFeature.isUsableManeuver(maneuver)) {
            state =
                NavigationState(
                    true,
                    false,
                    state.speedExceeded,
                    state.maneuver,
                    state.maneuverText,
                    state.maneuverDistance,
                    state.routeDistance,
                    state.routeTime,
                    state.arrivalTime,
                    state.currentStreet,
                    NavigationFeature.first(normalizedStreet, state.nextStreet),
                    state.finishStreet,
                    state.speedLimit,
                    state.currentSpeed,
                    "2gis_notification",
                    now,
                )
            publishNavigationState()
            sendConfiguredText()
            AppLog.line(
                app,
                "Navigation 2GIS text: " +
                    state.summary() +
                    " raw=" +
                    NavigationFeature.clean(rawText),
            )
            return
        }
        val finishRequested: Boolean = NavigationFeature.isFinishManeuver(maneuver)
        val finish: Boolean =
            finishRequested &&
                NavigationFeature.finishAllowedByDistance(
                    normalizedDistance,
                    state.routeDistance,
                    state.maneuverDistance,
                )
        if (finishRequested && !finish) {
            AppLog.line(
                app,
                ("Navigation 2GIS ignored early finish: distance=" +
                    NavigationFeature.first(normalizedDistance, state.routeDistance, "-") +
                    " raw=" +
                    NavigationFeature.clean(rawText)),
            )
            return
        }
        val micro: Boolean =
            NavigationFeature.dgisMicroManeuver(maneuver, rawText, normalizedDistance)
        if (micro && NavigationModeSettings.isTbt(app)) {
            sendConfiguredText()
            AppLog.line(
                app,
                "Navigation 2GIS ignored micro in TBT: " + NavigationFeature.clean(rawText),
            )
            return
        }
        if (!micro && laneGuidanceUntil > now && !finish) {
            sendConfiguredText()
            AppLog.line(
                app,
                ("Navigation 2GIS held main maneuver during micro: " +
                    state.summary() +
                    " raw=" +
                    NavigationFeature.clean(rawText)),
            )
            return
        }
        if (micro) laneGuidanceUntil = now + (AppSettings.navMicroHoldSeconds(app) * 1000L)
        state =
            NavigationState(
                true,
                finish,
                state.speedExceeded,
                maneuver,
                NavigationFeature.maneuverLabel(maneuver),
                normalizedDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                NavigationFeature.first(normalizedStreet, state.nextStreet),
                state.finishStreet,
                state.speedLimit,
                state.currentSpeed,
                "2gis_notification",
                now,
            )
        publishNavigationState()
        if (!micro) startManeuverTextHint(maneuver, "")
        sendManeuverIfChanged(
            maneuver,
            NavigationFeature.distanceValue(normalizedDistance),
            NavigationFeature.isKm(normalizedDistance),
            if (micro) 9 else maneuverProgressBucket(maneuver, normalizedDistance),
            micro,
        )
        if (finish) startFinishHold() else sendConfiguredText()
        AppLog.line(
            app,
            ((if (micro) "Navigation 2GIS micro maneuver: " else "Navigation 2GIS maneuver: ") +
                state.summary() +
                " raw=" +
                NavigationFeature.clean(rawText)),
        )
    }

    @kotlin.jvm.Synchronized
    fun handleDgisNotificationRemoved(packageName: String?) {
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_DGIS,
                NavigationSourcePolicy.EVENT_PASSIVE,
                "2gis_notification_removed",
            )
        ) {
            return
        }
        val source = NavigationFeature.clean(state.source).lowercase(Locale.US)
        if (!source.contains("2gis")) return
        setActive(false, "2gis_notification_removed")
        AppLog.line(
            app,
            "Navigation 2GIS notification removed: " + NavigationFeature.clean(packageName),
        )
    }

    @kotlin.jvm.Synchronized
    fun touchDgisDashboardHeartbeat(): Boolean {
        val previousOwner: Int = sourceArbitrator.ownerSource()
        val replayNeeded: Boolean =
            !state.active ||
                NavigationFeature.navigationSourceFamily(state.source) !=
                    NavigationSourcePolicy.SOURCE_DGIS ||
                previousOwner != NavigationSourcePolicy.SOURCE_DGIS
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_DGIS,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE,
                "2gis_dashboard_heartbeat",
            )
        ) {
            return false
        }
        return replayNeeded
    }

    @kotlin.jvm.Synchronized
    fun handleDgisDashboard(
        activeMode: String?,
        maneuverIcon: String?,
        maneuverDescription: String?,
        maneuverDistance: String?,
        totalDistance: String?,
        remainingTime: String?,
        arrivalTime: String?,
        speedLimit: String?,
        exceeded: Boolean,
        rawJson: String?,
    ) {
        handleDgisDashboard(
            activeMode,
            maneuverIcon,
            maneuverDescription,
            maneuverDistance,
            totalDistance,
            remainingTime,
            arrivalTime,
            speedLimit,
            exceeded,
            Double.NaN,
            Double.NaN,
            rawJson,
            true,
        )
    }

    @kotlin.jvm.Synchronized
    fun handleDgisDashboard(
        activeMode: String?,
        maneuverIcon: String?,
        maneuverDescription: String?,
        maneuverDistance: String?,
        totalDistance: String?,
        remainingTime: String?,
        arrivalTime: String?,
        speedLimit: String?,
        exceeded: Boolean,
        destinationLat: Double,
        destinationLon: Double,
        rawJson: String?,
        newSnapshot: Boolean,
    ) {
        val mode = NavigationFeature.clean(activeMode).lowercase(Locale.US)
        val routeDistance = NavigationFeature.dashboardDistance(totalDistance)
        val routeTime = NavigationFeature.clean(remainingTime)
        val arrival = NavigationFeature.clean(arrivalTime)
        val distance = NavigationFeature.dashboardDistance(maneuverDistance)
        val finish: Boolean =
            NavigationFeature.dashboardFinishReached(maneuverIcon, routeDistance, distance)
        val maneuver: String? =
            if (finish) "context_ra_finish"
            else NavigationFeature.dashboardManeuver(maneuverIcon, maneuverDescription)
        val routeData: Boolean =
            !routeDistance.isNullOrEmpty() ||
                !routeTime.isNullOrEmpty() ||
                !arrival.isNullOrEmpty() ||
                !distance.isNullOrEmpty() ||
                NavigationFeature.isUsableManeuver(maneuver!!)
        val routeMode: Boolean =
            !mode.isNullOrEmpty() && !"unknown".equals(mode) && !"freeroam".equals(mode)
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_DGIS,
                if (routeMode || routeData) NavigationSourcePolicy.EVENT_ROUTE_ACTIVE
                else NavigationSourcePolicy.EVENT_PASSIVE,
                "2gis_dashboard:" + NavigationFeature.first(mode, "empty"),
            )
        ) {
            return
        }
        rememberFinishPoint(destinationLat, destinationLon, "2gis_dashboard")
        if (
            !routeMode &&
                !routeData &&
                state.source.lowercase(Locale.US).contains("2gis") &&
                state.active
        ) {
            forceInactive("2gis_dashboard_idle")
        }
        if (newSnapshot) {
            dashboardRoadLimit =
                dashboardRoadLimit.observe(
                    NavigationSourcePolicy.SOURCE_DGIS,
                    speedLimit,
                    SystemClock.elapsedRealtime(),
                )
        }
        if (!routeMode && !routeData) {
            applyDgisRoadSpeedLimit(speedLimit, exceeded, (routeMode || routeData) && !finish)
            return
        }
        setActive(true, "2gis_dashboard")
        rememberFinishPoint(destinationLat, destinationLon, "2gis_dashboard")
        waitingForRoute = false
        routeStartedAt = 0L
        val now: Long = System.currentTimeMillis()
        lastRouteGuidanceAt = now
        cancelPendingInactive("2gis_dashboard")
        if (
            !routeDistance.isNullOrEmpty() || !routeTime.isNullOrEmpty() || !arrival.isNullOrEmpty()
        ) {
            mergeEta(
                routeDistance,
                routeTime,
                NavigationFeature.first(arrival, NavigationFeature.arrivalFromRouteTime(routeTime)),
                "",
                "",
                "2gis_dashboard",
                "",
                false,
                false,
            )
        }
        if (!NavigationFeature.isUsableManeuver(maneuver!!)) {
            sendConfiguredText()
            AppLog.line(
                app,
                ("Navigation 2GIS dashboard route: " +
                    state.summary() +
                    " raw=" +
                    NavigationFeature.shortRaw("2gis_dashboard", rawJson)),
            )
            applyDgisRoadSpeedLimit(speedLimit, exceeded, (routeMode || routeData) && !finish)
            return
        }
        val nextStreet =
            streetCandidate(
                maneuverDescription,
                state.nextStreet,
                state.currentStreet,
                state.finishStreet,
            )
        val micro: Boolean =
            NavigationFeature.dgisMicroManeuver(
                maneuver,
                maneuverDescription.toString() + " " + maneuverIcon,
                distance,
            )
        if (micro && NavigationModeSettings.isTbt(app)) {
            sendConfiguredText()
            AppLog.line(
                app,
                "Navigation 2GIS dashboard ignored micro in TBT: " +
                    NavigationFeature.shortRaw("2gis_dashboard", rawJson),
            )
            applyDgisRoadSpeedLimit(speedLimit, exceeded, (routeMode || routeData) && !finish)
            return
        }
        if (!micro && laneGuidanceUntil > now && !finish) {
            sendConfiguredText()
            AppLog.line(
                app,
                ("Navigation 2GIS dashboard held main maneuver during micro: " +
                    state.summary() +
                    " raw=" +
                    NavigationFeature.shortRaw("2gis_dashboard", rawJson)),
            )
            applyDgisRoadSpeedLimit(speedLimit, exceeded, (routeMode || routeData) && !finish)
            return
        }
        if (micro) laneGuidanceUntil = now + (AppSettings.navMicroHoldSeconds(app) * 1000L)
        state =
            NavigationState(
                true,
                finish,
                state.speedExceeded,
                maneuver,
                NavigationFeature.maneuverLabel(maneuver),
                distance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                NavigationFeature.first(nextStreet, state.nextStreet),
                state.finishStreet,
                state.speedLimit,
                state.currentSpeed,
                "2gis_dashboard",
                System.currentTimeMillis(),
            )
        publishNavigationState()
        if (!micro) startManeuverTextHint(maneuver, "")
        if (!distance.isNullOrEmpty()) {
            sendManeuverIfChanged(
                maneuver,
                NavigationFeature.distanceValue(distance),
                NavigationFeature.isKm(distance),
                if (micro) 9 else maneuverProgressBucket(maneuver, distance),
                micro,
            )
        }
        if (finish) startFinishHold() else sendConfiguredText()
        applyDgisRoadSpeedLimit(speedLimit, exceeded, !finish)
        AppLog.line(
            app,
            ((if (micro) "Navigation 2GIS dashboard micro maneuver: "
            else "Navigation 2GIS dashboard maneuver: ") +
                state.summary() +
                " raw=" +
                NavigationFeature.shortRaw("2gis_dashboard", rawJson)),
        )
    }

    private fun applyDgisRoadSpeedLimit(value: String?, exceeded: Boolean, routeActive: Boolean) {
        if (NavigationFeature.speedNumber(value) <= 0) {
            cancelRoadSpeedLimitDelivery()
            if (value.isNullOrEmpty()) return
        }
        if (exceeded) startOverspeedTextWindow() else clearOverspeedTextWindow()
        state =
            NavigationState(
                state.active,
                state.finishReached,
                exceeded,
                state.maneuver,
                state.maneuverText,
                state.maneuverDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                state.finishStreet,
                value,
                state.currentSpeed,
                "2gis_dashboard",
                System.currentTimeMillis(),
            )
        publishNavigationState()
        if (!AppSettings.navSpeedLimitRouteOnly(app) || routeActive) sendSpeed(value)
    }

    @kotlin.jvm.Synchronized
    fun handleDgisTrip(
        currentRoad: String?,
        maneuverRoad: String?,
        destinationName: String?,
        destinationAddress: String,
        navigationStarted: Boolean,
        rawJson: String?,
    ) {
        handleDgisTrip(
            currentRoad,
            maneuverRoad,
            destinationName,
            destinationAddress,
            navigationStarted,
            Double.NaN,
            Double.NaN,
            rawJson,
        )
    }

    @kotlin.jvm.Synchronized
    fun handleDgisTrip(
        currentRoad: String?,
        maneuverRoad: String?,
        destinationName: String?,
        destinationAddress: String,
        navigationStarted: Boolean,
        destinationLat: Double,
        destinationLon: Double,
        rawJson: String?,
    ) {
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_DGIS,
                if (navigationStarted) NavigationSourcePolicy.EVENT_ROUTE_ACTIVE
                else NavigationSourcePolicy.EVENT_PASSIVE,
                if (navigationStarted) "2gis_car_trip_active" else "2gis_car_trip_stop",
            )
        ) {
            return
        }
        rememberFinishPoint(destinationLat, destinationLon, "2gis_car_trip")
        val acceptedCurrent = streetCandidate(currentRoad, state.currentStreet, "", "")
        val acceptedNext =
            streetCandidate(
                maneuverRoad,
                state.nextStreet,
                NavigationFeature.first(acceptedCurrent, state.currentStreet),
                "",
            )
        val acceptedFinish =
            finishCandidate(NavigationFeature.first(destinationAddress, destinationName))
        val hasTripText: Boolean =
            !acceptedCurrent.isNullOrEmpty() ||
                !acceptedNext.isNullOrEmpty() ||
                !acceptedFinish.isNullOrEmpty()
        if (!navigationStarted) {
            if (state.active && state.source.lowercase(Locale.US).contains("2gis")) {
                forceInactive("2gis_car_trip_stop")
            }
            if (hasTripText) {
                AppLog.line(
                    app,
                    "Navigation 2GIS car trip stale after stop: " +
                        NavigationFeature.shortRaw("2gis_car_trip", rawJson),
                )
            }
            return
        }
        if (!state.active) {
            setActive(true, "2gis_car_trip")
        }
        rememberFinishPoint(destinationLat, destinationLon, "2gis_car_trip")
        if (!state.active && !hasTripText) return
        lastRouteGuidanceAt = System.currentTimeMillis()
        cancelPendingInactive("2gis_car_trip")
        val current = NavigationFeature.first(acceptedCurrent, state.currentStreet)
        val next = NavigationFeature.first(acceptedNext, state.nextStreet)
        val finish =
            NavigationFeature.first(
                acceptedFinish,
                NavigationFeature.validFinishStreet(state.finishStreet),
            )
        if (
            current.equals(state.currentStreet) &&
                next.equals(state.nextStreet) &&
                finish.equals(state.finishStreet) &&
                state.active == navigationStarted
        ) {
            return
        }
        state =
            NavigationState(
                navigationStarted || state.active,
                state.finishReached,
                state.speedExceeded,
                state.maneuver,
                state.maneuverText,
                state.maneuverDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                current,
                next,
                finish,
                state.speedLimit,
                state.currentSpeed,
                "2gis_car_trip",
                System.currentTimeMillis(),
            )
        publishNavigationState()
        sendConfiguredText()
        AppLog.line(
            app,
            ("Navigation 2GIS car trip: " +
                state.summary() +
                " raw=" +
                NavigationFeature.shortRaw("2gis_car_trip", rawJson)),
        )
    }

    @kotlin.jvm.Synchronized
    fun handleDgisStopped(source: String?) {
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_DGIS,
                NavigationSourcePolicy.EVENT_PASSIVE,
                "2gis_stopped:" + NavigationFeature.clean(source),
            )
        ) {
            return
        }
        if (!state.source.lowercase(Locale.US).contains("2gis") && !state.active) return
        forceInactive(source)
    }

    @kotlin.jvm.Synchronized
    fun setActive(active: Boolean) {
        setActive(active, "manual")
    }

    @kotlin.jvm.Synchronized
    fun setActive(active: Boolean, source: String?) {
        val wasActive: Boolean = state.active
        val now: Long = System.currentTimeMillis()
        var reassertRoadSpeedLimit: Boolean = false
        var clearRoadSpeedLimit: Boolean = false
        var inactiveReason: String? = ""
        if (active) confirmRestoredRoute(source)
        if (!active) {
            val cleanSource = NavigationFeature.clean(source)
            if (NavigationFeature.yandexContextHardReset(cleanSource)) {
                lastYandexCurrentSpeedAt = 0L
            }
            inactiveReason = cleanSource
            val preserveRoadContext: Boolean = shouldPreserveRoadContext(cleanSource, now)
            if (!preserveRoadContext) dashboardRoadLimit = DashboardRoadSpeedLimit.NONE
            val retainedSpeedLimit: String? = if (preserveRoadContext) state.speedLimit else ""
            val retainedSource: String? = if (preserveRoadContext) state.source else cleanSource
            val retainedExceeded: Boolean = preserveRoadContext && state.speedExceeded
            clearRoadSpeedLimit =
                !preserveRoadContext &&
                    (!state.speedLimit.isNullOrEmpty() || lastSentSpeedLimit > 0)
            reassertRoadSpeedLimit = preserveRoadContext
            cancelPendingInactive("off " + cleanSource)
            if (
                state.finishReached && finishHoldUntil > now && !"finish_hold".equals(cleanSource)
            ) {
                sendNavigationText("Финиш")
                AppLog.line(app, "Navigation finish hold ignores off: " + cleanSource)
                return
            }
            sourceArbitrator.release(NavigationFeature.navigationSourceFamily(state.source))
            yandexWatchdogGeneration++
            cancelFinishHold()
            routeLoadingFallbackGeneration++
            routeReroutingGeneration++
            clearFinalSegmentFinishDirection("off " + cleanSource)
            waitingForRoute = false
            routeStartedAt = 0L
            routeReroutingUntil = 0L
            routeReroutingMinUntil = 0L
            activeRouteTotalDistance = ""
            activeRouteId = ""
            resetMainManeuverPreview("route_off")
            routeLoadingMinUntil = 0L
            lastDirectManeuverAt = 0L
            clearLaneHintHold()
            clearEventHintHold()
            lastRouteGuidanceAt = 0L
            lastRouteMetricsAt = 0L
            clearRoundaboutExitHold()
            clearFinishPoint()
            resetManeuverProgress()
            resetNavigationTextCache()
            resetNavigationSendCache()
            if (!preserveRoadContext) {
                speedLimitExpiryGeneration++
                lastYandexRoadSpeedLimitAt = 0L
                yandexSpeedLimitClearCandidateAt = 0L
                clearOverspeedTextWindow()
            }
            clusterTx.clear()
            lastNavigationDecisionKey = ""
            lastTxMicroGuardKey = ""
            state =
                NavigationState(
                    false,
                    false,
                    retainedExceeded,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    state.currentStreet,
                    "",
                    "",
                    retainedSpeedLimit,
                    state.currentSpeed,
                    retainedSource,
                    now,
                )
            cancelRestoredRouteConfirmation()
        } else if (!wasActive) {
            yandexFinishSuppressed = false
            cancelPendingInactive("active " + NavigationFeature.clean(source))
            cancelFinishHold()
            finishCompassSuppressUntil = 0L
            routeLoadingFallbackGeneration++
            routeReroutingGeneration++
            clearFinalSegmentFinishDirection("active " + NavigationFeature.clean(source))
            waitingForRoute = true
            routeStartedAt = now
            routeReroutingUntil = 0L
            routeReroutingMinUntil = 0L
            activeRouteTotalDistance = ""
            activeRouteId = ""
            resetMainManeuverPreview("route_started")
            routeLoadingMinUntil = now + NavigationFeature.ROUTE_WAIT_MIN_MS
            clearLaneHintHold()
            clearEventHintHold()
            lastRouteGuidanceAt = 0L
            clearRoundaboutExitHold()
            clearFinishPoint()
            resetManeuverProgress()
            resetNavigationTextCache()
            resetNavigationSendCache()
            clusterTx.clear()
            lastNavigationDecisionKey = ""
            lastTxMicroGuardKey = ""
            state =
                NavigationState(
                    true,
                    false,
                    state.speedExceeded,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    state.currentStreet,
                    "",
                    "",
                    state.speedLimit,
                    state.currentSpeed,
                    NavigationFeature.clean(source),
                    now,
                )
        } else {
            cancelPendingInactive("active " + NavigationFeature.clean(source))
            state =
                NavigationState(
                    true,
                    false,
                    state.speedExceeded,
                    state.maneuver,
                    state.maneuverText,
                    state.maneuverDistance,
                    state.routeDistance,
                    state.routeTime,
                    state.arrivalTime,
                    state.currentStreet,
                    state.nextStreet,
                    state.finishStreet,
                    state.speedLimit,
                    state.currentSpeed,
                    NavigationFeature.clean(source),
                    now,
                )
        }
        publishNavigationState()
        sender.sendActive(active)
        if (!active && reassertRoadSpeedLimit) {
            AppLog.navigation(
                app,
                ("NAV_SPEED_LIMIT_PRESERVED reason=route_off_" +
                    NavigationFeature.clean(inactiveReason) +
                    " value=" +
                    NavigationFeature.clean(state.speedLimit)),
            )
        } else if (!active && clearRoadSpeedLimit) {
            lastSentSpeedLimit = 0
            AppLog.navigation(
                app,
                "NAV_SPEED_LIMIT_UNAVAILABLE reason=route_off_" +
                    NavigationFeature.clean(inactiveReason),
            )
        }
        if (active && !wasActive) {
            sendRouteLoadingVisual()
            scheduleRouteLoadingFallback()
        }
        AppLog.line(app, "Navigation: " + (if (active) state.summary() else "off"))
    }

    private fun startRouteLoadingWithoutMetrics(source: String?) {
        val cleanSource = NavigationFeature.clean(source)
        if (routeReroutingActive(System.currentTimeMillis())) {
            sendRouteReroutingVisual()
            AppLog.line(app, "Navigation loading suppressed by rerouting: " + cleanSource)
            return
        }
        if (state.active && !state.loading()) {
            forceInactive(cleanSource + "_restart_loading")
        }
        if (!state.active) {
            setActive(true, cleanSource)
            AppLog.line(app, "Navigation loading started without route metrics: " + cleanSource)
        }
    }

    private fun handleActive(intent: Intent?) {
        val active: Boolean =
            NavigationFeature.bool(intent, "navi_on", false) ||
                NavigationFeature.bool(intent, "active", false) ||
                NavigationFeature.bool(intent, "is_active", false)
        val source =
            NavigationFeature.first(
                NavigationFeature.text(intent, "source"),
                NavigationFeature.ACTION_NAVI_ON,
            )
        if (!active) {
            if (confirmedCoreBridgeFinish(intent, source)) {
                AppLog.line(
                    app,
                    "Navigation confirmed Core Bridge finish: " + NavigationFeature.clean(source),
                )
                markFinishReached("core_bridge_finish_" + NavigationFeature.clean(source))
                return
            }
            if (unconfirmedCoreBridgeFinish(intent, source)) {
                return
            }
            if (NavigationFeature.preConfirmRouteStopClick(source)) {
                AppLog.line(
                    app,
                    "Navigation ignored pre-confirm route stop click: " +
                        NavigationFeature.clean(source),
                )
                return
            }
            if (NavigationFeature.confirmedRouteStop(intent, source)) {
                AppLog.line(
                    app,
                    "Navigation confirmed route stop: " + NavigationFeature.clean(source),
                )
                if (routeStopLooksLikeFinish(intent, source)) {
                    markFinishReached("route_stop_finish_" + NavigationFeature.clean(source))
                    return
                }
                forceInactive(source)
                return
            }
            requestInactiveAfterConfirm(source)
            return
        }

        val currentStreet =
            NavigationFeature.first(
                NavigationFeature.text(intent, "current_street"),
                NavigationFeature.text(intent, "currentStreet"),
                NavigationFeature.text(intent, "current_road"),
                NavigationFeature.text(intent, "currentRoad"),
                NavigationFeature.text(intent, "current_road_name"),
                NavigationFeature.text(intent, "currentRoadName"),
                NavigationFeature.text(intent, "current_street_name"),
                NavigationFeature.text(intent, "currentStreetName"),
                NavigationFeature.text(intent, "position"),
            )
        val nextStreet =
            NavigationFeature.first(
                NavigationFeature.text(intent, "next_street"),
                NavigationFeature.text(intent, "nextStreet"),
                NavigationFeature.text(intent, "street_after_maneuver"),
                NavigationFeature.text(intent, "streetAfterManeuver"),
                NavigationFeature.text(intent, "next_road"),
                NavigationFeature.text(intent, "nextRoad"),
                NavigationFeature.text(intent, "next_road_name"),
                NavigationFeature.text(intent, "nextRoadName"),
                NavigationFeature.text(intent, "next_street_name"),
                NavigationFeature.text(intent, "nextStreetName"),
                NavigationFeature.text(intent, "next_turn_street"),
                NavigationFeature.text(intent, "nextTurnStreet"),
            )
        val finishStreet = NavigationFeature.finishTextFromIntent(intent)
        val routeDistance = NavigationFeature.routeDistanceFromIntent(intent)
        val routeTime = NavigationFeature.routeTimeFromIntent(intent)
        val arrivalTime = NavigationFeature.arrivalFromIntent(intent)
        val routeTotalDistance =
            NavigationFeature.first(
                NavigationFeature.metersDistanceText(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "route_total_meters"),
                        NavigationFeature.text(intent, "total_route_meters"),
                        NavigationFeature.text(intent, "route_total_distance_meters"),
                        NavigationFeature.text(intent, "route_total_initial_meters"),
                    )
                ),
                NavigationFeature.text(intent, "route_total_len"),
                NavigationFeature.text(intent, "route_full_len"),
                NavigationFeature.text(intent, "route_total_distance"),
                NavigationFeature.text(intent, "full_route_distance"),
                NavigationFeature.text(intent, "routeTotalDistance"),
                NavigationFeature.text(intent, "totalRouteDistance"),
            )
        val activeManeuver =
            NavigationFeature.first(
                NavigationFeature.text(intent, "imageId"),
                NavigationFeature.text(intent, "image_id"),
                NavigationFeature.text(intent, "direction"),
                NavigationFeature.text(intent, "maneuver"),
                NavigationFeature.text(intent, "maneuver_action"),
                NavigationFeature.text(intent, "current_maneuver"),
                NavigationFeature.text(intent, "maneuver_type"),
            )
        val normalizedActiveManeuver =
            NavigationFeature.normalizeManeuver(
                NavigationFeature.clean(activeManeuver),
                intent!!.getIntExtra("direction_lr", 0),
            )
        val rawActiveManeuverDistance = NavigationFeature.explicitManeuverDistanceFromIntent(intent)
        var routeIdChanged: Boolean = incomingRouteIdChangesActiveRoute(intent)
        if (routeIdChanged) {
            startRouteReroutingHold(NavigationFeature.clean(source) + "_route_id_pending", true)
        }
        val activeManeuverDistance = mainManeuverDistanceFromIntent(intent, source, routeIdChanged)
        val clearCoreBridgeManeuver: Boolean =
            YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source)) &&
                activeManeuver.isNullOrEmpty() &&
                rawActiveManeuverDistance.isNullOrEmpty() &&
                !NavigationFeature.laneGuidanceDistancePacket(intent, source)
        val now: Long = System.currentTimeMillis()
        val incomingRouteMetrics: Boolean =
            NavigationFeature.hasConfirmedRouteMetrics(routeDistance, routeTime, arrivalTime)
        if (!incomingRouteMetrics) {
            if (state.active && routeMetricsFresh(now)) {
                AppLog.line(
                    app,
                    "Navigation ignored active refresh without route metrics: " +
                        NavigationFeature.clean(source),
                )
            } else if (state.active && state.loading() && waitingForRoute) {
                AppLog.line(
                    app,
                    "Navigation loading waits for route metrics: " + NavigationFeature.clean(source),
                )
            } else {
                startRouteLoadingWithoutMetrics(source)
            }
            return
        }
        if (
            finishSuppressesYandex(
                source,
                NavigationFeature.first(routeDistance, routeTotalDistance),
                routeTime!!,
                finishStreet,
            )
        )
            return
        setActive(true, source)

        val finishPointChanged: Boolean = rememberFinishPoint(intent, source)
        lastRouteMetricsAt = now
        routeIdChanged = routeIdChanged or syncActiveRouteId(intent, source)
        val acceptedCurrent = etaStreetCandidate(currentStreet, state.currentStreet)
        val acceptedNext = streetCandidate(nextStreet, state.nextStreet, state.currentStreet, "")
        val acceptedFinish = finishForState(finishStreet, finishPointChanged)
        if (
            acceptedCurrent.isNullOrEmpty() &&
                acceptedNext.isNullOrEmpty() &&
                acceptedFinish.isNullOrEmpty() &&
                routeTotalDistance.isNullOrEmpty() &&
                !NavigationFeature.hasConfirmedRouteMetrics(routeDistance, routeTime, arrivalTime)
        )
            return
        if (
            clearCoreBridgeManeuver &&
                (!state.maneuver.isNullOrEmpty() || !state.maneuverDistance.isNullOrEmpty())
        ) {
            resetManeuverProgress()
            AppLog.line(app, "Navigation cleared stale Core Bridge maneuver without live distance")
        }
        val retainedManeuverDistance: String? =
            if (clearCoreBridgeManeuver) ""
            else NavigationFeature.first(activeManeuverDistance, state.maneuverDistance)
        val previousManeuver: String? = state.maneuver
        val previousManeuverDistance: String? = state.maneuverDistance
        val retainedManeuver: String? =
            if (clearCoreBridgeManeuver) ""
            else
                NavigationFeature.first(
                    state.maneuver,
                    if (NavigationFeature.isUsableManeuver(normalizedActiveManeuver!!))
                        normalizedActiveManeuver
                    else "",
                )
        val retainedManeuverText: String? =
            if (clearCoreBridgeManeuver) ""
            else
                NavigationFeature.first(
                    state.maneuverText,
                    if (NavigationFeature.isUsableManeuver(retainedManeuver!!))
                        NavigationFeature.maneuverLabel(retainedManeuver)
                    else "",
                )
        state =
            NavigationState(
                true,
                state.finishReached,
                state.speedExceeded,
                retainedManeuver,
                retainedManeuverText,
                retainedManeuverDistance,
                NavigationFeature.first(
                    NavigationFeature.normalizeDistanceText(routeDistance),
                    state.routeDistance,
                ),
                NavigationFeature.first(NavigationFeature.clean(routeTime), state.routeTime),
                NavigationFeature.first(NavigationFeature.clean(arrivalTime), state.arrivalTime),
                NavigationFeature.first(acceptedCurrent, state.currentStreet),
                NavigationFeature.first(acceptedNext, state.nextStreet),
                NavigationFeature.first(acceptedFinish, state.finishStreet),
                state.speedLimit,
                state.currentSpeed,
                source,
                now,
            )
        if (
            !activeManeuverDistance.isNullOrEmpty() &&
                !state.maneuver.isNullOrEmpty() &&
                !activeManeuverDistance.equals(rawActiveManeuverDistance)
        ) {
            AppLog.line(
                app,
                ("Navigation active snapshot main distance accepted: " +
                    NavigationFeature.clean(state.maneuver) +
                    " distance=" +
                    NavigationFeature.clean(activeManeuverDistance) +
                    " raw=" +
                    NavigationFeature.clean(rawActiveManeuverDistance) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
        rememberRouteTotalDistance(routeTotalDistance, now)
        if (
            maybeStartFinalSegmentFinishDirection(
                routeDistance,
                previousManeuver!!,
                previousManeuverDistance,
                state.maneuver,
                state.maneuverDistance,
                source,
                now,
            )
        ) {
            if (!sendDirectionToFinishIfNeeded(true)) sendFinishDirectionPlaceholder(true)
            sendConfiguredText()
            AppLog.line(app, "Navigation active final segment: " + state.summary())
            return
        }
        publishNavigationState()
        sendConfiguredText()
        AppLog.line(app, "Navigation active street: " + state.summary())
    }

    private fun unconfirmedCoreBridgeFinish(intent: Intent?, source: String?): Boolean {
        if (!YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source))) return false
        if (!NavigationFeature.coreBridgeFinishRequested(intent, source)) return false
        if (coreBridgeFinishConfirmed(intent)) return false
        AppLog.line(
            app,
            ("Navigation ignored unconfirmed Core Bridge finish: " +
                "route=" +
                NavigationFeature.clean(NavigationFeature.routeDistanceFromIntent(intent)) +
                " maneuver=" +
                NavigationFeature.clean(
                    NavigationFeature.explicitManeuverDistanceFromIntent(intent)
                ) +
                " stateRoute=" +
                NavigationFeature.clean(state.routeDistance) +
                " stateManeuver=" +
                NavigationFeature.clean(state.maneuverDistance) +
                " waiting=" +
                waitingForRoute +
                " loading=" +
                state.loading() +
                " source=" +
                NavigationFeature.clean(source)),
        )
        return true
    }

    private fun confirmedCoreBridgeFinish(intent: Intent?, source: String?): Boolean {
        return YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source)) &&
            NavigationFeature.coreBridgeFinishRequested(intent, source) &&
            coreBridgeFinishConfirmed(intent)
    }

    private fun coreBridgeFinishConfirmed(intent: Intent?): Boolean {
        if (!state.active || state.finishReached) return false
        val now: Long = System.currentTimeMillis()
        if (NavigationFeature.confirmedCoreBridgeFinishedState(intent)) {
            return routeGuidanceFresh() || routeMetricsFresh(now) || routeDataReady()
        }
        if (routeStartedAt > 0L && now - routeStartedAt < NavigationFeature.ROUTE_WAIT_MIN_MS)
            return false
        if (!routeGuidanceFresh() && !routeMetricsFresh(now)) return false
        if (waitingForRoute || state.loading()) return false
        val routeDistance = NavigationFeature.routeDistanceFromIntent(intent)
        val maneuverDistance = NavigationFeature.explicitManeuverDistanceFromIntent(intent)
        return NavigationFeature.finishAllowedByDistance(
            routeDistance,
            maneuverDistance,
            state.routeDistance,
            state.maneuverDistance,
        ) &&
            finishPositionAllowsHold(
                "core_bridge_finish",
                NavigationFeature.first(
                    routeDistance,
                    maneuverDistance,
                    state.routeDistance,
                    state.maneuverDistance,
                ),
            ) &&
            !NavigationFeature.finishContradictedByGuidance(
                routeDistance,
                NavigationFeature.first(maneuverDistance, state.maneuverDistance),
                (NavigationFeature.first(
                    NavigationFeature.routeTimeFromIntent(intent),
                    state.routeTime,
                ))!!,
            )
    }

    private fun routeStopLooksLikeFinish(intent: Intent?, source: String?): Boolean {
        if (
            NavigationFeature.bool(intent, "finish_reached", false) ||
                NavigationFeature.bool(intent, "arrived", false) ||
                NavigationFeature.bool(intent, "destination_reached", false) ||
                NavigationFeature.bool(intent, "route_finished", false)
        ) {
            return true
        }
        val sourceLower = NavigationFeature.clean(source).lowercase(Locale.US)
        if (
            sourceLower.contains("finish") ||
                sourceLower.contains("arrived") ||
                sourceLower.contains("destination")
        ) {
            return true
        }
        val routeDistance =
            NavigationFeature.first(
                NavigationFeature.text(intent, "distance"),
                NavigationFeature.text(intent, "route_distance"),
                NavigationFeature.text(intent, "remaining_distance"),
                NavigationFeature.text(intent, "edistance"),
                state.routeDistance,
                state.maneuverDistance,
            )
        if (
            NavigationFeature.finishAllowedByDistance(routeDistance) &&
                !NavigationFeature.finishContradictedByGuidance(
                    routeDistance,
                    state.maneuverDistance,
                    state.routeTime,
                )
        ) {
            return true
        }
        val directMeters: Float = distanceToFinishMeters()
        return directMeters > 0f &&
            directMeters <=
                NavigationFeature.AUTO_FINISH_GPS_METERS +
                    NavigationFeature.FINISH_STALE_RECOVERY_MARGIN_METERS
    }

    private fun requestInactiveAfterConfirm(source: String?) {
        val cleanSource = NavigationFeature.clean(source)
        if (!state.active || state.finishReached || !hasRouteSnapshot()) {
            setActive(false, cleanSource)
            return
        }
        val generation: Int = ++pendingInactiveGeneration
        pendingInactiveSource = cleanSource
        AppLog.line(app, "Navigation pending off until route confirmation: " + cleanSource)
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (generation != pendingInactiveGeneration) return@postDelayed
                    if (!state.active || state.finishReached) return@postDelayed
                    if (routeGuidanceFresh()) {
                        AppLog.line(
                            app,
                            "Navigation pending off canceled by live route: " +
                                pendingInactiveSource,
                        )
                        pendingInactiveSource = ""
                        return@postDelayed
                    }
                    val pendingSource: String? = pendingInactiveSource
                    pendingInactiveSource = ""
                    setActive(false, pendingSource)
                }
            },
            NavigationFeature.ROUTE_STOP_CONFIRM_MS,
        )
    }

    private fun cancelPendingInactive(reason: String?) {
        if (pendingInactiveSource.isNullOrEmpty()) return
        pendingInactiveGeneration++
        if (!pendingInactiveSource.isNullOrEmpty()) {
            AppLog.line(app, "Navigation pending off canceled: " + NavigationFeature.clean(reason))
        }
        pendingInactiveSource = ""
    }

    private fun hasRouteSnapshot(): Boolean {
        return !state.routeDistance.isNullOrEmpty() ||
            !state.routeTime.isNullOrEmpty() ||
            !state.arrivalTime.isNullOrEmpty() ||
            !state.maneuver.isNullOrEmpty() ||
            !state.maneuverText.isNullOrEmpty() ||
            !state.maneuverDistance.isNullOrEmpty() ||
            !state.nextStreet.isNullOrEmpty() ||
            !state.finishStreet.isNullOrEmpty()
    }

    private fun routeGuidanceFresh(): Boolean {
        val now: Long = System.currentTimeMillis()
        return lastRouteGuidanceAt > 0L &&
            now - lastRouteGuidanceAt <= NavigationFeature.ROUTE_STOP_CONFIRM_MS
    }

    private fun routeMetricsFresh(now: Long): Boolean {
        return lastRouteMetricsAt > 0L &&
            now - lastRouteMetricsAt <= NavigationFeature.YANDEX_WATCHDOG_STALE_MS
    }

    private fun touchYandexWatchdog(source: String?) {
        if (!NavigationFeature.isYandexSource(source)) return
        confirmRestoredRoute(source)
        lastYandexNavigationPacketAt = System.currentTimeMillis()
        val generation: Int = ++yandexWatchdogGeneration
        scheduleYandexWatchdog(generation, NavigationFeature.YANDEX_WATCHDOG_STALE_MS)
    }

    private fun scheduleRestoredRouteConfirmationIfNeeded() {
        if (!state.active || state.finishReached) return
        restoredRouteAwaitingConfirmation = true
        val generation: Int = ++restoredRouteConfirmationGeneration
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (
                        generation != restoredRouteConfirmationGeneration ||
                            !restoredRouteAwaitingConfirmation
                    ) {
                        return@postDelayed
                    }
                    restoredRouteAwaitingConfirmation = false
                    if (!state.active || state.finishReached) return@postDelayed
                    AppLog.navigation(
                        app,
                        "NAV_RESTORED_ROUTE_EXPIRED source=" + NavigationFeature.clean(state.source),
                    )
                    forceInactive("restored_route_unconfirmed")
                }
            },
            NavigationFeature.RESTORED_ROUTE_CONFIRM_MS,
        )
    }

    private fun confirmRestoredRoute(source: String?) {
        if (!restoredRouteAwaitingConfirmation) return
        restoredRouteAwaitingConfirmation = false
        restoredRouteConfirmationGeneration++
        AppLog.navigation(
            app,
            "NAV_RESTORED_ROUTE_CONFIRMED source=" + NavigationFeature.clean(source),
        )
    }

    private fun cancelRestoredRouteConfirmation() {
        if (!restoredRouteAwaitingConfirmation) return
        restoredRouteAwaitingConfirmation = false
        restoredRouteConfirmationGeneration++
    }

    private fun shouldPreserveRoadContext(reason: String?, ignoredNow: Long): Boolean {
        val cleanReason = NavigationFeature.clean(reason).lowercase(Locale.US)
        val hardReset: Boolean = NavigationFeature.yandexContextHardReset(cleanReason)
        val nowElapsed: Long = SystemClock.elapsedRealtime()
        return YandexRoadContextPolicy.shouldPreserve(
            state.speedLimit,
            NavigationFeature.isYandexSource(state.source),
            lastYandexRoadSpeedLimitAt,
            nowElapsed,
            NavigationFeature.YANDEX_ROAD_SPEED_LIMIT_HOLD_MS,
            hardReset,
            freshGpsSaysStationary(nowElapsed),
        )
    }

    private fun scheduleYandexWatchdog(generation: Int, delayMs: Long) {
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (generation != yandexWatchdogGeneration) return@postDelayed
                    if (
                        !state.active ||
                            state.finishReached ||
                            !NavigationFeature.isYandexSource(state.source)
                    )
                        return@postDelayed
                    val now: Long = System.currentTimeMillis()
                    val staleFor: Long =
                        if (lastYandexNavigationPacketAt <= 0L) Long.MAX_VALUE
                        else Math.max(0L, now - lastYandexNavigationPacketAt)
                    val gpsAge: Long =
                        if (lastGpsPointAt <= 0L) -1L else Math.max(0L, now - lastGpsPointAt)
                    val decision: YandexRouteWatchdogPolicy.Decision? =
                        YandexRouteWatchdogPolicy.decide(
                            staleFor,
                            gpsAge,
                            hasRouteSnapshot(),
                            NavigationFeature.YANDEX_WATCHDOG_STALE_MS,
                            NavigationFeature.YANDEX_WATCHDOG_GPS_FRESH_MS,
                            NavigationFeature.YANDEX_WATCHDOG_NO_GPS_GRACE_MS,
                        )
                    if (
                        decision ==
                            kia.app.navigation.domain.YandexRouteWatchdogPolicy.Decision.WAIT
                    ) {
                        scheduleYandexWatchdog(
                            generation,
                            Math.max(250L, NavigationFeature.YANDEX_WATCHDOG_STALE_MS - staleFor),
                        )
                        return@postDelayed
                    }
                    expireStaleYandexRoadSpeedLimit(now, "watchdog")
                    if (
                        decision ==
                            kia.app.navigation.domain.YandexRouteWatchdogPolicy.Decision.HOLD_ACTIVE
                    ) {
                        if (
                            now - lastYandexWatchdogHoldLogAt >=
                                NavigationFeature.YANDEX_WATCHDOG_RETRY_MS
                        ) {
                            lastYandexWatchdogHoldLogAt = now
                            AppLog.navigation(
                                app,
                                ("NAV_YANDEX_BACKGROUND_HOLD" +
                                    " packetAge=" +
                                    staleFor +
                                    " gpsAge=" +
                                    gpsAge +
                                    " route=" +
                                    NavigationFeature.clean(state.routeDistance) +
                                    " maneuver=" +
                                    NavigationFeature.clean(state.maneuverDistance)),
                            )
                        }
                        scheduleYandexWatchdog(
                            generation,
                            NavigationFeature.YANDEX_WATCHDOG_RETRY_MS,
                        )
                        return@postDelayed
                    }
                    forceInactive("yandex_watchdog_stale")
                }
            },
            Math.max(250L, delayMs),
        )
    }

    @kotlin.jvm.Synchronized
    fun touchYandexCoreBridgeHeartbeat(routeMetrics: Boolean): Boolean {
        val previousOwner: Int = sourceArbitrator.ownerSource()
        val replayNeeded: Boolean =
            !state.active ||
                NavigationFeature.navigationSourceFamily(state.source) !=
                    NavigationSourcePolicy.SOURCE_YANDEX ||
                previousOwner != NavigationSourcePolicy.SOURCE_YANDEX
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE,
                "core_bridge_heartbeat",
            )
        ) {
            return false
        }
        val now: Long = System.currentTimeMillis()
        touchYandexWatchdog(YandexCoreBridgeContract.SOURCE)
        if (routeMetrics) {
            lastRouteGuidanceAt = now
            lastRouteMetricsAt = now
            cancelPendingInactive("core_bridge_heartbeat")
        }
        return replayNeeded
    }

    @kotlin.jvm.Synchronized
    fun onYandexBackgroundGuidanceSuspended(
        callback: String?,
        routePresent: Boolean,
        semanticTransition: Boolean,
    ) {
        if (
            !acceptNavigationSource(
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_PASSIVE,
                "background_guidance_suspended",
            )
        ) {
            return
        }
        if (!state.active || state.finishReached) return
        cancelPendingInactive("background_guidance_suspended")
        touchYandexWatchdog(YandexCoreBridgeContract.SOURCE)
        expireStaleYandexRoadSpeedLimit(System.currentTimeMillis(), "background_guidance_suspended")
        if (semanticTransition) {
            AppLog.navigation(
                app,
                ("NAV_YANDEX_BACKGROUND_SUSPENDED" +
                    " callback=" +
                    NavigationFeature.clean(callback) +
                    " routePresent=" +
                    routePresent +
                    " route=" +
                    NavigationFeature.clean(state.routeDistance) +
                    " maneuver=" +
                    NavigationFeature.clean(state.maneuver) +
                    " distance=" +
                    NavigationFeature.clean(state.maneuverDistance)),
            )
        }
    }

    private fun forceInactive(source: String?) {
        val now: Long = System.currentTimeMillis()
        val cleanSource = NavigationFeature.clean(source)
        if (NavigationFeature.MVITE_SOURCE.equals(state.source)) resetMviteRouteContext()
        if (NavigationFeature.yandexContextHardReset(cleanSource)) {
            lastYandexCurrentSpeedAt = 0L
        }
        sourceArbitrator.release(NavigationFeature.navigationSourceFamily(state.source))
        val preserveRoadContext: Boolean = shouldPreserveRoadContext(cleanSource, now)
        if (!preserveRoadContext) dashboardRoadLimit = DashboardRoadSpeedLimit.NONE
        val retainedSpeedLimit: String? = if (preserveRoadContext) state.speedLimit else ""
        val retainedSource: String? = if (preserveRoadContext) state.source else cleanSource
        val retainedExceeded: Boolean = preserveRoadContext && state.speedExceeded
        val clearRoadSpeedLimit: Boolean =
            !preserveRoadContext && (!state.speedLimit.isNullOrEmpty() || lastSentSpeedLimit > 0)
        cancelPendingInactive("force " + cleanSource)
        cancelFinishHold()
        yandexWatchdogGeneration++
        waitingForRoute = false
        routeStartedAt = 0L
        routeReroutingGeneration++
        routeReroutingUntil = 0L
        routeReroutingMinUntil = 0L
        activeRouteTotalDistance = ""
        activeRouteId = ""
        resetMainManeuverPreview("force_inactive")
        lastDirectManeuverAt = 0L
        clearLaneHintHold()
        clearEventHintHold()
        lastRouteGuidanceAt = 0L
        lastRouteMetricsAt = 0L
        clearRoundaboutExitHold()
        clearFinishPoint()
        resetManeuverProgress()
        resetNavigationTextCache()
        resetNavigationSendCache()
        if (!preserveRoadContext) {
            speedLimitExpiryGeneration++
            lastYandexRoadSpeedLimitAt = 0L
            yandexSpeedLimitClearCandidateAt = 0L
            clearOverspeedTextWindow()
        }
        clusterTx.clear()
        clearFinalSegmentFinishDirection("force inactive " + cleanSource)
        state =
            NavigationState(
                false,
                false,
                retainedExceeded,
                "",
                "",
                "",
                "",
                "",
                "",
                state.currentStreet,
                "",
                "",
                retainedSpeedLimit,
                state.currentSpeed,
                retainedSource,
                now,
            )
        cancelRestoredRouteConfirmation()
        publishNavigationState()
        sender.sendActive(false)
        if (preserveRoadContext) {
            AppLog.navigation(
                app,
                ("NAV_SPEED_LIMIT_PRESERVED reason=force_inactive_" +
                    cleanSource +
                    " value=" +
                    NavigationFeature.clean(state.speedLimit)),
            )
        } else if (clearRoadSpeedLimit) {
            lastSentSpeedLimit = 0
            AppLog.navigation(
                app,
                "NAV_SPEED_LIMIT_UNAVAILABLE reason=force_inactive_" + cleanSource,
            )
        }
        AppLog.line(app, "Navigation forced off: " + cleanSource)
    }

    @kotlin.jvm.Synchronized
    fun sendManeuver(imageId: String?, distance: String?, unit: String?, street: String?) {
        setActive(true)
        val normalizedStreet = NavigationFeature.normalizeStreetLabel(street)
        val normalizedDistance = NavigationFeature.distanceText(distance, unit)
        val maneuver = NavigationFeature.clean(imageId)
        lastRouteGuidanceAt = System.currentTimeMillis()
        cancelPendingInactive("manual_maneuver")
        state =
            NavigationState(
                true,
                NavigationFeature.isFinishManeuver(maneuver),
                state.speedExceeded,
                maneuver,
                NavigationFeature.maneuverLabel(maneuver),
                normalizedDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                NavigationFeature.first(normalizedStreet, state.nextStreet),
                state.finishStreet,
                state.speedLimit,
                state.currentSpeed,
                "manual_maneuver",
                System.currentTimeMillis(),
            )
        publishNavigationState()
        startManeuverTextHint(maneuver, "")
        sendManeuverIfChanged(
            imageId,
            NavigationFeature.distanceValue(distance),
            NavigationFeature.isKm(unit),
            maneuverProgressBucket(maneuver, normalizedDistance),
        )
        sendConfiguredText()
        AppLog.line(app, "Navigation maneuver: " + state.summary())
    }

    private fun handleManeuver(intent: Intent) {
        val trustedRollover: Boolean =
            YandexCoreBridgeContract.SOURCE.equals(
                NavigationFeature.clean(NavigationFeature.text(intent, "source"))
            ) &&
                NavigationFeature.bool(
                    intent,
                    YandexCoreBridgeContract.EXTRA_MAIN_MANEUVER_ROLLOVER,
                    false,
                )
        handleManeuverInternal(intent, trustedRollover)
    }

    private fun handleManeuverInternal(intent: Intent, rolloverPacket: Boolean) {
        val source =
            NavigationFeature.first(
                NavigationFeature.text(intent, "source"),
                NavigationFeature.ACTION_MANEUVER,
            )
        val sourceLower = source!!.lowercase(Locale.US)
        if (NavigationFeature.ignoredLaneDebugSource(sourceLower)) {
            AppLog.line(app, "Navigation ignored lane debug packet: " + source)
            return
        }
        val routeRoadOnly: Boolean = NavigationFeature.routeRoadOnlySource(intent, sourceLower)
        var maneuver: String? =
            NavigationFeature.first(
                NavigationFeature.text(intent, "imageId"),
                NavigationFeature.text(intent, "image_id"),
                NavigationFeature.text(intent, "direction"),
                NavigationFeature.text(intent, "maneuver"),
                NavigationFeature.text(intent, "maneuver_action"),
                NavigationFeature.textByKeyPart(intent, "image"),
            )
        if (maneuver.isNullOrEmpty() && !sourceLower.contains("direction_sign") && !routeRoadOnly) {
            maneuver =
                NavigationFeature.first(
                    NavigationFeature.textByKeyPart(intent, "direction"),
                    NavigationFeature.textByKeyPart(intent, "maneuver"),
                )
        }
        val directionSignManeuver = NavigationFeature.directionSignManeuver(intent)
        if (
            maneuver.isNullOrEmpty() && NavigationFeature.isUsableManeuver(directionSignManeuver!!)
        ) {
            maneuver = directionSignManeuver
        }
        val unit =
            NavigationFeature.first(
                NavigationFeature.text(intent, "unit"),
                NavigationFeature.text(intent, "distance_unit"),
            )
        val rawManeuverDistance =
            NavigationFeature.first(
                NavigationFeature.text(intent, "distance"),
                NavigationFeature.text(intent, "distance_val_str"),
                NavigationFeature.textByKeyPart(intent, "distance"),
            )
        val maneuverDistance = NavigationFeature.distanceText(rawManeuverDistance, unit)
        val mainDistanceExplicitlyUnknown: Boolean =
            NavigationFeature.bool(intent, "main_distance_explicitly_unknown", false) &&
                YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source))
        val fullSnapshotMicroClear: Boolean =
            NavigationFeature.bool(intent, "bridge_full_snapshot", false) &&
                NavigationFeature.bool(intent, "micro_clear", false)
        if (mainDistanceExplicitlyUnknown && !state.maneuverDistance.isNullOrEmpty()) {
            state = state.withManeuverDistance("", System.currentTimeMillis())
        }
        val laneUnit =
            NavigationFeature.first(
                NavigationFeature.text(intent, "lane_distance_unit"),
                NavigationFeature.text(intent, "micro_distance_unit"),
            )
        val laneMetersDistance =
            NavigationFeature.metersDistanceText(
                NavigationFeature.first(
                    NavigationFeature.text(intent, "lane_distance_meters"),
                    NavigationFeature.text(intent, "micro_distance_meters"),
                )
            )
        val explicitLaneDistance =
            NavigationFeature.first(
                laneMetersDistance,
                NavigationFeature.distanceText(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "lane_distance"),
                        NavigationFeature.text(intent, "laneDistance"),
                        NavigationFeature.text(intent, "micro_distance"),
                        NavigationFeature.text(intent, "microDistance"),
                    ),
                    NavigationFeature.first(laneUnit, unit),
                ),
            )
        val explicitMicroDistance = NavigationFeature.nonZeroDistance(explicitLaneDistance)
        var laneDistanceOnlySource: Boolean = NavigationFeature.laneDistanceOnlySource(sourceLower)
        val currentStreet =
            NavigationFeature.first(
                NavigationFeature.text(intent, "current_street"),
                NavigationFeature.text(intent, "currentStreet"),
                NavigationFeature.text(intent, "current_road"),
                NavigationFeature.text(intent, "currentRoad"),
                NavigationFeature.text(intent, "current_road_name"),
                NavigationFeature.text(intent, "currentRoadName"),
                NavigationFeature.text(intent, "current_street_name"),
                NavigationFeature.text(intent, "currentStreetName"),
                NavigationFeature.text(intent, "position"),
            )
        val explicitAfterStreet =
            NavigationFeature.first(
                NavigationFeature.text(intent, "road_after"),
                NavigationFeature.text(intent, "roadAfter"),
                NavigationFeature.text(intent, "road_after_name"),
                NavigationFeature.text(intent, "street_after"),
                NavigationFeature.text(intent, "streetAfter"),
                NavigationFeature.text(intent, "street_after_maneuver"),
                NavigationFeature.text(intent, "streetAfterManeuver"),
                NavigationFeature.text(intent, "maneuver_toponym"),
            )
        val nextStreet =
            NavigationFeature.first(
                explicitAfterStreet,
                NavigationFeature.text(intent, "next_street"),
                NavigationFeature.text(intent, "nextStreet"),
                NavigationFeature.text(intent, "next_road"),
                NavigationFeature.text(intent, "nextRoad"),
                NavigationFeature.text(intent, "next_road_name"),
                NavigationFeature.text(intent, "nextRoadName"),
                NavigationFeature.text(intent, "next_street_name"),
                NavigationFeature.text(intent, "nextStreetName"),
                NavigationFeature.text(intent, "next_turn_street"),
                NavigationFeature.text(intent, "nextTurnStreet"),
                NavigationFeature.text(intent, "street"),
                NavigationFeature.text(intent, "raw_street"),
                NavigationFeature.text(intent, "road"),
            )
        val finishStreet = NavigationFeature.finishTextFromIntent(intent)
        val routeDistanceForFinalSegment = NavigationFeature.routeDistanceForFinalSegment(intent)
        if (finishSuppressesYandex(source, maneuverDistance, "", finishStreet)) return
        val signEvent: String = NavigationFeature.yandexSignEventText(intent, sourceLower)
        val signEventNow: Long = System.currentTimeMillis()
        if (!signEvent.isNullOrEmpty()) {
            rememberEventHint(signEvent, source, signEventNow)
        }
        val signHasMicroSeed: Boolean =
            !signEvent.isNullOrEmpty() &&
                (!explicitMicroDistance.isNullOrEmpty() ||
                    NavigationFeature.isUsableManeuver(directionSignManeuver!!))
        if (!signEvent.isNullOrEmpty() && maneuver.isNullOrEmpty() && !signHasMicroSeed) {
            rememberLaneHint(signEvent, source, signEventNow)
            state =
                state
                    .withLaneHint(activeLaneHint, activeLaneSource, signEventNow)
                    .withEventHint(activeEventHint, activeEventSource, signEventNow)
            publishNavigationState()
            AppLog.line(app, ("Navigation Yandex sign event: " + signEvent + " source=" + source))
            return
        }
        val finishPointChanged: Boolean = rememberFinishPoint(intent, source)
        val routeIdChanged: Boolean = syncActiveRouteId(intent, source)
        var routeActionManeuver: String? = NavigationFeature.routeActionManeuver(intent)
        var contradictedRouteActionManeuver: String? = ""
        var laneHighlightManeuver: String? =
            NavigationFeature.laneHighlightedManeuver(intent, sourceLower)
        if (
            routeRoadOnly &&
                NavigationFeature.routeRoadActionContradictedByLaneTruth(
                    intent,
                    sourceLower,
                    routeActionManeuver!!,
                    laneHighlightManeuver!!,
                )
        ) {
            contradictedRouteActionManeuver = routeActionManeuver
            AppLog.line(
                app,
                ("Navigation ignored contradictory route-road route_action: action=" +
                    NavigationFeature.clean(routeActionManeuver) +
                    " highlight=" +
                    NavigationFeature.first(laneHighlightManeuver, "-") +
                    " raw=" +
                    NavigationFeature.shortRaw(source, NavigationFeature.rawExtras(intent))),
            )
            routeActionManeuver = ""
        }
        var routeRoadActionRejectedAsMain: Boolean = false
        if (
            routeRoadOnly &&
                routeRoadActionBlockedByCloseMain(routeActionManeuver!!, maneuverDistance)
        ) {
            AppLog.line(
                app,
                ("Navigation ignored close route-road route_action as main: current=" +
                    NavigationFeature.clean(state.maneuver) +
                    " action=" +
                    NavigationFeature.clean(routeActionManeuver) +
                    " currentDistance=" +
                    NavigationFeature.clean(state.maneuverDistance) +
                    " incomingDistance=" +
                    NavigationFeature.clean(maneuverDistance) +
                    " source=" +
                    source),
            )
            routeRoadActionRejectedAsMain = true
        }
        val imageManeuver =
            NavigationFeature.normalizeManeuver(
                NavigationFeature.clean(maneuver),
                intent.getIntExtra("direction_lr", 0),
            )
        var cleanManeuver: String? = imageManeuver
        if (
            !routeRoadActionRejectedAsMain &&
                NavigationFeature.shouldPreferRouteActionManeuver(
                    intent,
                    cleanManeuver!!,
                    routeActionManeuver!!,
                )
        ) {
            AppLog.line(
                app,
                ("Navigation route_action overrides image_id: " +
                    cleanManeuver +
                    " -> " +
                    routeActionManeuver +
                    " source=" +
                    source),
            )
            cleanManeuver = routeActionManeuver
        } else if (
            !routeRoadActionRejectedAsMain &&
                !NavigationFeature.isUsableManeuver(cleanManeuver!!) &&
                !routeActionManeuver.isNullOrEmpty()
        ) {
            cleanManeuver = routeActionManeuver
        }
        var highlightedMicroManeuver: String? =
            if (routeRoadOnly) NavigationFeature.first(laneHighlightManeuver, directionSignManeuver)
            else NavigationFeature.first(directionSignManeuver, laneHighlightManeuver)
        val staleProviderLaneTopology: Boolean =
            NavigationFeature.staleProviderLaneTopologyConflict(
                intent,
                sourceLower,
                cleanManeuver!!,
                laneHighlightManeuver!!,
                explicitLaneDistance,
            )
        if (staleProviderLaneTopology) {
            AppLog.line(
                app,
                ("Navigation ignored stale provider lane topology: main=" +
                    NavigationFeature.clean(cleanManeuver) +
                    " highlight=" +
                    NavigationFeature.first(laneHighlightManeuver, "-") +
                    " raw=" +
                    NavigationFeature.shortRaw(source, NavigationFeature.rawExtras(intent))),
            )
            laneHighlightManeuver = ""
            highlightedMicroManeuver = ""
        }
        if (
            routeRoadOnly &&
                !NavigationFeature.isUsableManeuver(cleanManeuver) &&
                NavigationFeature.isUsableManeuver(state.maneuver) &&
                NavigationFeature.isUsableManeuver(highlightedMicroManeuver!!)
        ) {
            cleanManeuver = state.maneuver
        }
        if (
            laneDistanceOnlySource &&
                (NavigationFeature.isUsableManeuver(routeActionManeuver!!) ||
                    NavigationFeature.isUsableManeuver(highlightedMicroManeuver!!))
        ) {
            laneDistanceOnlySource = false
        }
        if (
            routeRoadOnly &&
                NavigationFeature.isUsableManeuver(state.maneuver) &&
                NavigationFeature.isUsableManeuver(routeActionManeuver!!) &&
                !NavigationFeature.sameManeuverFamily(state.maneuver, routeActionManeuver) &&
                !NavigationFeature.routeActionHighlightMatches(intent, routeActionManeuver) &&
                !NavigationFeature.routeRoadPriorityActionConfirmed(
                    intent,
                    routeActionManeuver,
                    maneuverDistance,
                )
        ) {
            val rejectedRouteAction: String? = routeActionManeuver
            AppLog.line(
                app,
                ("Navigation ignored route-road route_action as main: current=" +
                    NavigationFeature.clean(state.maneuver) +
                    " action=" +
                    NavigationFeature.clean(routeActionManeuver) +
                    " source=" +
                    source),
            )
            routeRoadActionRejectedAsMain = true
            routeActionManeuver = ""
            if (
                !NavigationFeature.isUsableManeuver(cleanManeuver!!) ||
                    cleanManeuver.equals(rejectedRouteAction)
            ) {
                cleanManeuver = NavigationFeature.first(state.maneuver, laneHighlightManeuver)
            }
        }
        if (
            !routeRoadOnly &&
                !NavigationFeature.isUsableManeuver(cleanManeuver!!) &&
                NavigationFeature.isUsableManeuver(highlightedMicroManeuver!!)
        ) {
            cleanManeuver = highlightedMicroManeuver
            AppLog.line(
                app,
                ("Navigation lane highlight micro: " + cleanManeuver + " source=" + source),
            )
        }
        var roundaboutSeedManeuver: String? =
            NavigationFeature.first(cleanManeuver, routeActionManeuver)
        val currentRoundaboutClaim: Boolean =
            NavigationFeature.isRoundaboutManeuver(roundaboutSeedManeuver) ||
                "current"
                    .equals(NavigationFeature.text(intent, "roundabout_scope"), ignoreCase = true)
        var roundaboutExit: String? =
            if (currentRoundaboutClaim)
                NavigationFeature.roundaboutExit(intent, roundaboutSeedManeuver)
            else ""
        val roundaboutFromRouteData: Boolean =
            NavigationFeature.isRoundaboutManeuver(roundaboutSeedManeuver)
        if (
            roundaboutFromRouteData && !NavigationFeature.isUsableManeuver(roundaboutSeedManeuver!!)
        ) {
            roundaboutSeedManeuver = "context_ra_in_circular_movement"
        }
        if (
            roundaboutFromRouteData && NavigationFeature.isUsableManeuver(roundaboutSeedManeuver!!)
        ) {
            val routeRoundaboutManeuver =
                NavigationFeature.roundaboutManeuver(
                    roundaboutSeedManeuver,
                    roundaboutExit,
                    intent,
                    sourceLower,
                )
            if (
                NavigationFeature.isUsableManeuver(routeRoundaboutManeuver!!) &&
                    !routeRoundaboutManeuver.equals(cleanManeuver)
            ) {
                AppLog.line(
                    app,
                    ("Navigation route roundabout overrides maneuver: " +
                        NavigationFeature.clean(cleanManeuver) +
                        " -> " +
                        routeRoundaboutManeuver +
                        " exit=" +
                        NavigationFeature.first(roundaboutExit, "-") +
                        " source=" +
                        source),
                )
                cleanManeuver = routeRoundaboutManeuver
                routeActionManeuver = routeRoundaboutManeuver
                laneDistanceOnlySource = false
            }
        }
        var explicitGrayRoad: String? = NavigationFeature.grayRoadManeuver(intent, sourceLower)
        if (staleProviderLaneTopology) {
            explicitGrayRoad = ""
            clearGrayRoadHold()
            clearLearnedRoadOptions()
        }
        if (rolloverPacket) {
            resetMainManeuverPreview("confirmed_main_rollover")
            resetNavigationSendCache()
        }
        if (routeRoadOnly) {
            explicitGrayRoad =
                NavigationFeature.adjustRouteRoadGrayForCurrentManeuver(
                    intent,
                    explicitGrayRoad,
                    NavigationFeature.first(state.maneuver, cleanManeuver),
                )
            val trustedGrayRoad: String =
                NavigationFeature.grayRoadForManeuver(
                    NavigationFeature.first(
                        contradictedRouteActionManeuver,
                        routeActionManeuver,
                        cleanManeuver,
                        state.maneuver,
                    )
                )
            if (
                !contradictedRouteActionManeuver.isNullOrEmpty() && !trustedGrayRoad.isNullOrEmpty()
            ) {
                AppLog.line(
                    app,
                    ("Navigation clamped contradictory route-road gray: " +
                        NavigationFeature.clean(explicitGrayRoad) +
                        " -> " +
                        trustedGrayRoad +
                        " action=" +
                        contradictedRouteActionManeuver +
                        " highlight=" +
                        NavigationFeature.first(laneHighlightManeuver, "-") +
                        " source=" +
                        source),
                )
                explicitGrayRoad = trustedGrayRoad
            }
        }
        if (
            !explicitGrayRoad.isNullOrEmpty() &&
                NavigationFeature.providerLaneSchemeBeyondMain(
                    intent,
                    sourceLower,
                    NavigationFeature.first(cleanManeuver, state.maneuver)!!,
                    laneHighlightManeuver,
                    explicitGrayRoad,
                    NavigationFeature.first(
                        NavigationFeature.nonZeroDistance(maneuverDistance),
                        NavigationFeature.nonZeroDistance(state.maneuverDistance),
                        currentMainTxDistance(),
                    ),
                    explicitLaneDistance,
                )
        ) {
            AppLog.line(
                app,
                ("Navigation ignored future provider lane road scheme: main=" +
                    NavigationFeature.clean(
                        NavigationFeature.first(cleanManeuver, state.maneuver)
                    ) +
                    " mainDistance=" +
                    NavigationFeature.clean(
                        NavigationFeature.first(
                            NavigationFeature.nonZeroDistance(maneuverDistance),
                            NavigationFeature.nonZeroDistance(state.maneuverDistance),
                            currentMainTxDistance(),
                        )
                    ) +
                    " highlight=" +
                    NavigationFeature.first(laneHighlightManeuver, "-") +
                    " laneDistance=" +
                    NavigationFeature.clean(explicitLaneDistance) +
                    " gray=" +
                    NavigationFeature.clean(explicitGrayRoad) +
                    " source=" +
                    source),
            )
            explicitGrayRoad = ""
            laneHighlightManeuver = ""
            highlightedMicroManeuver = ""
            clearGrayRoadHold()
            clearLearnedRoadOptions()
        }
        var rolloverProgressPrimed: Boolean = false
        if (rolloverPacket) {
            if (explicitGrayRoad.isNullOrEmpty()) {
                clearGrayRoadHold()
                clearLearnedRoadOptions()
            }
            AppLog.navigation(
                app,
                ("NAV_PROGRESS_ROLLOVER_CONFIRMED" +
                    " routeId=" +
                    NavigationFeature.first(activeRouteId, "-") +
                    " maneuver=" +
                    NavigationFeature.first(NavigationFeature.clean(cleanManeuver), "-") +
                    " distance=" +
                    NavigationFeature.first(NavigationFeature.clean(maneuverDistance), "-") +
                    " gray=" +
                    NavigationFeature.first(NavigationFeature.clean(explicitGrayRoad), "-")),
            )
            armManeuverProgressRollover(
                activeRouteId,
                NavigationFeature.maneuverFamily(cleanManeuver),
            )
            if (!NavigationFeature.nonZeroDistance(maneuverDistance).isNullOrEmpty()) {
                val rolloverProgress: Int = maneuverProgressBucket(cleanManeuver, maneuverDistance)
                rolloverProgressPrimed =
                    rolloverProgress == ManeuverProgressTracker.FULL_BUCKET &&
                        !maneuverProgressRolloverMarker.pending()
            }
        }
        var now: Long = System.currentTimeMillis()
        if (
            !state.active &&
                NavigationFeature.packetClaimsActiveRoute(intent) &&
                NavigationFeature.canResumeFromManeuverPacket(
                    routeRoadOnly,
                    cleanManeuver!!,
                    routeActionManeuver!!,
                    explicitGrayRoad,
                )
        ) {
            if (!hasConfirmedRouteMetrics()) {
                AppLog.line(
                    app,
                    "Navigation ignored active maneuver without route metrics: " + source,
                )
                return
            }
            AppLog.line(app, "Navigation resumed active from maneuver packet: " + source)
            setActive(true, source)
            rememberFinishPoint(intent, source)
            now = System.currentTimeMillis()
        }
        if (laneDistanceOnlySource) {
            if (
                NavigationFeature.mainGuidanceDistanceTick(
                    sourceLower,
                    intent,
                    explicitLaneDistance,
                    maneuverDistance,
                )
            ) {
                applyMainGuidanceDistanceTick(maneuverDistance, source, now)
                return
            }
            if (!state.active) {
                AppLog.line(app, "Navigation ignored lane distance while inactive: " + source)
                return
            }
            applyLaneDistanceOnly(intent, explicitLaneDistance, source, sourceLower, now)
            return
        }
        if (!NavigationFeature.isUsableManeuver(cleanManeuver!!)) {
            if (!explicitGrayRoad.isNullOrEmpty()) {
                applyGrayRoadOnly(explicitGrayRoad, source, maneuverDistance)
                return
            }
            AppLog.line(
                app,
                ("Navigation ignored maneuver: " +
                    cleanManeuver +
                    " no road scheme/topology source=" +
                    source +
                    " raw=" +
                    NavigationFeature.shortRaw(source, NavigationFeature.rawExtras(intent))),
            )
            return
        }
        val laneData: Boolean = !staleProviderLaneTopology && NavigationFeature.hasLaneData(intent)
        val laneOnlySource: Boolean =
            routeRoadOnly ||
                NavigationFeature.laneMicroSource(sourceLower) ||
                sourceLower.contains("route_options")
        val providerLaneData: Boolean =
            !fullSnapshotMicroClear &&
                sourceLower.contains(YandexCoreBridgeContract.SOURCE) &&
                (NavigationFeature.bool(intent, "lane_guidance", false) || laneData)
        val directSource: Boolean =
            !providerLaneData &&
                !laneOnlySource &&
                (sourceLower.contains("setnextmaneuver") ||
                    sourceLower.contains("context_maneuver") ||
                    sourceLower.contains("fullscreen_maneuver") ||
                    sourceLower.contains("auto_widget_state") ||
                    sourceLower.contains(YandexCoreBridgeContract.SOURCE) ||
                    sourceLower.contains("roundabout_exit_late"))
        val highlightedMicro: Boolean =
            NavigationFeature.isUsableManeuver(highlightedMicroManeuver!!) &&
                !highlightedMicroManeuver.equals(routeActionManeuver) &&
                !highlightedMicroManeuver.equals(cleanManeuver) &&
                !NavigationFeature.sameManeuverFamily(
                    highlightedMicroManeuver,
                    routeActionManeuver,
                ) &&
                !NavigationFeature.sameManeuverFamily(highlightedMicroManeuver, cleanManeuver)
        val routeRoadHighlightedMicro: Boolean = routeRoadOnly && highlightedMicro
        var microSource: Boolean =
            !directSource &&
                !laneDistanceOnlySource &&
                ((!routeRoadOnly && NavigationFeature.laneMicroSource(sourceLower)) ||
                    routeRoadHighlightedMicro ||
                    (!routeRoadOnly && !explicitMicroDistance.isNullOrEmpty()))
        val laneGuidance: Boolean =
            !fullSnapshotMicroClear &&
                !staleProviderLaneTopology &&
                !directSource &&
                (NavigationFeature.bool(intent, "lane_guidance", false) ||
                    laneOnlySource ||
                    laneData)
        val direct: Boolean = directSource
        val microEnabled: Boolean = AppSettings.navMicroManeuvers(app)
        if (!microEnabled && laneGuidance) {
            AppLog.line(
                app,
                ("Navigation lane/lana disabled for main test: maneuver=" +
                    NavigationFeature.clean(cleanManeuver) +
                    " distance=" +
                    NavigationFeature.clean(maneuverDistance) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
        val acceptedCurrent: String = ""
        val acceptedNext: String? =
            if (explicitAfterStreet.isNullOrEmpty())
                streetCandidate(nextStreet, state.nextStreet, state.currentStreet, "")
            else nextStreetCandidate(explicitAfterStreet, state.nextStreet)
        if (!explicitAfterStreet.isNullOrEmpty()) {
            val normalizedAfter = NavigationFeature.normalizeStreetLabel(explicitAfterStreet)
            AppLog.line(
                app,
                ("Navigation after street: raw=" +
                    NavigationFeature.clean(explicitAfterStreet) +
                    " normalized=" +
                    normalizedAfter +
                    " accepted=" +
                    NavigationFeature.first(acceptedNext, "-") +
                    " current=" +
                    NavigationFeature.first(state.currentStreet, "-") +
                    " nextBefore=" +
                    NavigationFeature.first(state.nextStreet, "-") +
                    " looks=" +
                    NavigationFeature.looksLikeStreetText(normalizedAfter) +
                    " sameNext=" +
                    NavigationFeature.sameStreet(normalizedAfter, state.nextStreet) +
                    " source=" +
                    source),
            )
        }
        val acceptedFinish = finishForState(finishStreet, finishPointChanged)
        val finishRequested: Boolean =
            NavigationFeature.isFinishManeuver(cleanManeuver) ||
                NavigationFeature.bool(intent, "finish_reached", false) ||
                NavigationFeature.bool(intent, "arrived", false)
        val finish: Boolean =
            finishRequested &&
                NavigationFeature.finishAllowedByDistance(
                    rawManeuverDistance,
                    maneuverDistance,
                    state.routeDistance,
                    state.maneuverDistance,
                ) &&
                finishPositionAllowsHold(
                    source,
                    NavigationFeature.first(maneuverDistance, state.routeDistance),
                )
        if (finishRequested && !finish) {
            AppLog.line(
                app,
                ("Navigation ignored early finish: distance=" +
                    NavigationFeature.first(maneuverDistance, state.routeDistance, "-") +
                    " source=" +
                    source),
            )
            return
        }
        val roundabout: Boolean =
            NavigationFeature.isRoundaboutManeuver(cleanManeuver) || !roundaboutExit.isNullOrEmpty()
        if (finishPointChanged && state.active) {
            clearStaleRouteVisual("finish point changed " + source)
        }
        var displayManeuver: String? =
            if (roundabout)
                NavigationFeature.roundaboutManeuver(
                    cleanManeuver,
                    roundaboutExit,
                    intent,
                    sourceLower,
                )
            else cleanManeuver
        var currentRoundaboutMeters: Float =
            NavigationFeature.distanceMeters(state.maneuverDistance)
        if (currentRoundaboutMeters <= 0f) {
            val cachedMain: NavigationTxKey? = clusterTx.lastMainManeuverKey()
            if (
                cachedMain != null &&
                    NavigationFeature.sameManeuverFamily(state.maneuver, cachedMain.maneuver)
            ) {
                currentRoundaboutMeters =
                    if (cachedMain.km) cachedMain.distance * 1000f else cachedMain.distance
            }
        }
        val incomingRoundaboutMeters: Float = NavigationFeature.distanceMeters(maneuverDistance)
        if (
            RoundaboutMainPolicy.shouldKeepNumberedMain(
                state.maneuver,
                displayManeuver,
                currentRoundaboutMeters,
                incomingRoundaboutMeters,
            )
        ) {
            AppLog.navigation(
                app,
                ("NAV_ROUNDABOUT_MAIN_HELD" +
                    " current=" +
                    NavigationFeature.clean(state.maneuver) +
                    " incoming=" +
                    NavigationFeature.clean(displayManeuver) +
                    " currentDistance=" +
                    currentRoundaboutMeters +
                    " incomingDistance=" +
                    incomingRoundaboutMeters +
                    " distance=" +
                    NavigationFeature.clean(maneuverDistance) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            displayManeuver = state.maneuver
            roundaboutExit =
                NavigationFeature.first(
                    roundaboutExit,
                    NavigationFeature.roundaboutExitLabelFromId(displayManeuver),
                )
        }
        val visibleRouteActionManeuver =
            NavigationFeature.visibleRouteActionManeuver(routeActionManeuver!!, displayManeuver!!)
        if (roundabout) {
            if (NavigationFeature.isRoundaboutExitManeuver(displayManeuver)) {
                val initialRoundaboutDecision: ManeuverArbiter.Decision? =
                    NavigationManeuverPolicy.decide(
                        displayManeuver,
                        maneuverDistance,
                        highlightedMicroManeuver,
                        explicitMicroDistance,
                        !explicitMicroDistance.isNullOrEmpty(),
                        NavigationFeature.sameManeuverFamily(
                            displayManeuver,
                            highlightedMicroManeuver,
                        ),
                    )
                if (initialRoundaboutDecision!!.microWins()) {
                    clearRoundaboutExitHold()
                } else if (
                    !routeRoadOnly ||
                        !NavigationFeature.nonZeroDistance(maneuverDistance).isNullOrEmpty()
                ) {
                    rememberRoundaboutExit(displayManeuver, roundaboutExit, maneuverDistance, now)
                }
            } else {
                AppLog.line(
                    app,
                    "Navigation roundabout without explicit exit number: " +
                        NavigationFeature.shortRaw(source, NavigationFeature.rawExtras(intent)),
                )
            }
        } else if (!laneGuidance) {
            clearRoundaboutExitHold()
        }
        val stateRoundabout: Boolean = NavigationFeature.isRoundaboutManeuver(state.maneuver)
        if (!state.active) {
            if (!direct) {
                AppLog.line(app, "Navigation ignored maneuver while inactive: " + source)
                return
            }
            if (!hasConfirmedRouteMetrics()) {
                AppLog.line(
                    app,
                    "Navigation ignored direct maneuver without route metrics: " + source,
                )
                return
            }
            AppLog.line(app, "Navigation activating from maneuver: " + source)
            setActive(true, source)
            rememberFinishPoint(intent, source)
            now = System.currentTimeMillis()
        }
        if (
            routeRoadOnly &&
                roundabout &&
                NavigationFeature.nonZeroDistance(maneuverDistance).isNullOrEmpty() &&
                NavigationFeature.isUsableManeuver(state.maneuver) &&
                !NavigationFeature.isRoundaboutManeuver(state.maneuver)
        ) {
            AppLog.line(
                app,
                ("Navigation ignored stale route-road roundabout: current=" +
                    state.maneuver +
                    " source=" +
                    source),
            )
            return
        }
        val heldRoundabout = heldRoundaboutExitManeuver(now)
        if (
            laneGuidance &&
                !heldRoundabout.isNullOrEmpty() &&
                !explicitMicroDistance.isNullOrEmpty() &&
                NavigationManeuverPolicy.decide(
                        NavigationFeature.first(displayManeuver, state.maneuver),
                        NavigationFeature.first(maneuverDistance, state.maneuverDistance),
                        highlightedMicroManeuver,
                        explicitMicroDistance,
                        true,
                        NavigationFeature.sameManeuverFamily(
                            NavigationFeature.first(displayManeuver, state.maneuver),
                            highlightedMicroManeuver,
                        ),
                    )
                    .microWins()
        ) {
            clearRoundaboutExitHold()
            AppLog.line(
                app,
                ("Navigation cleared roundabout hold for earlier micro: held=" +
                    NavigationFeature.clean(heldRoundabout) +
                    " micro=" +
                    NavigationFeature.clean(highlightedMicroManeuver) +
                    " microDistance=" +
                    NavigationFeature.clean(explicitMicroDistance) +
                    " mainDistance=" +
                    NavigationFeature.clean(
                        NavigationFeature.first(maneuverDistance, state.maneuverDistance)
                    ) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
        if (laneGuidance) {
            var laneHint: String?
            var incomingMainDistance: String? = NavigationFeature.nonZeroDistance(maneuverDistance)
            var incomingMainDistanceLooksMicro: Boolean =
                laneMainDistanceLooksLikeMicro(incomingMainDistance, explicitMicroDistance)
            if (
                (routeIdChanged || rolloverPacket) &&
                    incomingMainDistanceLooksMicro &&
                    NavigationFeature.hasMainManeuverDistanceMeters(intent)
            ) {
                AppLog.line(
                    app,
                    ("Navigation accepted trusted main distance despite lane match: main=" +
                        NavigationFeature.clean(incomingMainDistance) +
                        " lane=" +
                        NavigationFeature.clean(explicitMicroDistance) +
                        " reason=" +
                        (if (rolloverPacket) "confirmed_rollover" else "route_changed") +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                incomingMainDistanceLooksMicro = false
            }
            if (incomingMainDistanceLooksMicro) {
                AppLog.line(
                    app,
                    ("Navigation kept main distance over lane micro distance: main=" +
                        NavigationFeature.clean(currentMainTxDistance()) +
                        " incoming=" +
                        NavigationFeature.clean(incomingMainDistance) +
                        " micro=" +
                        NavigationFeature.clean(explicitMicroDistance) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                incomingMainDistance = ""
            }
            if (!incomingMainDistance.isNullOrEmpty()) {
                state = state.withManeuverDistance(incomingMainDistance, now)
            }
            val incomingMainManeuver = NavigationFeature.first(displayManeuver, cleanManeuver)
            val heldMainDistance: String? =
                if (NavigationFeature.sameManeuverFamily(incomingMainManeuver, state.maneuver))
                    currentMainTxDistance()
                else ""
            val mainDistance = NavigationFeature.first(incomingMainDistance, heldMainDistance)
            val previousMainManeuver: String? = state.maneuver
            val roundaboutMainRefinement: Boolean =
                RoundaboutMainPolicy.shouldRefineMain(previousMainManeuver, incomingMainManeuver)
            if (
                YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source)) &&
                    NavigationFeature.isUsableManeuver(incomingMainManeuver!!) &&
                    !NavigationFeature.nonZeroDistance(mainDistance).isNullOrEmpty() &&
                    (state.maneuver.isNullOrEmpty() ||
                        NavigationFeature.maneuverFamilyChanged(
                            state.maneuver,
                            incomingMainManeuver,
                        ) ||
                        roundaboutMainRefinement ||
                        rolloverPacket)
            ) {
                state =
                    state.withMainManeuver(
                        incomingMainManeuver,
                        NavigationFeature.maneuverLabel(incomingMainManeuver, roundaboutExit!!),
                        mainDistance,
                        source,
                        now,
                    )
                if (roundaboutMainRefinement) {
                    AppLog.navigation(
                        app,
                        ("NAV_ROUNDABOUT_MAIN_REFINED" +
                            " previous=" +
                            NavigationFeature.clean(previousMainManeuver) +
                            " incoming=" +
                            NavigationFeature.clean(incomingMainManeuver) +
                            " distance=" +
                            NavigationFeature.clean(mainDistance) +
                            " source=" +
                            NavigationFeature.clean(source)),
                    )
                }
            }
            val yandexLanaGuidance: Boolean =
                YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source)) &&
                    laneGuidance &&
                    NavigationFeature.hasLaneData(intent)
            var laneDistanceText: String? = explicitMicroDistance
            val lanaDistanceFallback: Boolean =
                yandexLanaGuidance &&
                    NavigationFeature.nonZeroDistance(laneDistanceText).isNullOrEmpty() &&
                    !NavigationFeature.nonZeroDistance(mainDistance).isNullOrEmpty()
            if (lanaDistanceFallback) {
                laneDistanceText = NavigationFeature.nonZeroDistance(mainDistance)
            }
            var laneDebugDistance: String? = laneDistanceText
            val lanePassed: Boolean = handleLaneDistancePassed(explicitLaneDistance, now, source)
            if (lanePassed) {
                laneDistanceText = ""
                laneDebugDistance = ""
            }
            var activePostPassMicro: Boolean = activeMicroPostPassActive(now)
            val lanePostPassBlockedByNearMain: Boolean =
                (lanePassed || activePostPassMicro) && nextMainManeuverCloseForMicroPostPass()
            if (!lanePassed && lanePostPassBlockedByNearMain && activePostPassMicro) {
                AppLog.line(
                    app,
                    ("Navigation lane micro post-pass cleared by close main: micro=" +
                        NavigationFeature.clean(activeMicroManeuver) +
                        " main=" +
                        NavigationFeature.clean(state.maneuver) +
                        " mainDistance=" +
                        NavigationFeature.clean(currentMainTxDistance()) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                clearMicroHintHold()
                activePostPassMicro = false
            }
            var routeRoadDisplayMicro: Boolean = false
            var keepActiveMicroTxAllowed: Boolean = false
            var laneMicroSuppressedByMain: Boolean = false
            var microManeuverFromFallbackMain: Boolean = false
            var microManeuver: String? = ""
            if (microSource) {
                microManeuver =
                    NavigationFeature.first(highlightedMicroManeuver, visibleRouteActionManeuver)
                if (microManeuver.isNullOrEmpty()) {
                    microManeuver = cleanManeuver
                    microManeuverFromFallbackMain = true
                }
            } else if (yandexLanaGuidance) {
                microManeuver =
                    NavigationFeature.first(
                        highlightedMicroManeuver,
                        cleanManeuver,
                        visibleRouteActionManeuver,
                    )
                microSource = !microManeuver.isNullOrEmpty()
            }
            if (lanePostPassBlockedByNearMain && !microManeuver.isNullOrEmpty()) {
                AppLog.line(
                    app,
                    ("Navigation lane micro post-pass cleared by close main: micro=" +
                        NavigationFeature.clean(microManeuver) +
                        " main=" +
                        NavigationFeature.clean(state.maneuver) +
                        " mainDistance=" +
                        NavigationFeature.clean(currentMainTxDistance()) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                clearMicroHintHold()
                microManeuver = ""
                microSource = false
                routeRoadDisplayMicro = false
                laneDistanceText = ""
                laneDebugDistance = ""
            }
            var inferredForwardMicro: Boolean = false
            if (
                shouldInferForwardMicro(
                    intent,
                    sourceLower,
                    microManeuver,
                    microManeuverFromFallbackMain,
                    mainDistance,
                    explicitMicroDistance,
                    routeRoadOnly,
                )
            ) {
                microManeuver = "context_ra_forward"
                laneDistanceText = explicitMicroDistance
                laneDebugDistance = explicitMicroDistance
                microSource = true
                inferredForwardMicro = true
                AppLog.line(
                    app,
                    ("Navigation inferred forward lane micro: distance=" +
                        NavigationFeature.clean(explicitMicroDistance) +
                        " main=" +
                        NavigationFeature.clean(mainDistance) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
            }
            if (
                !microManeuver.isNullOrEmpty() &&
                    NavigationFeature.sameManeuverFamily(microManeuver, state.maneuver) &&
                    (laneDistanceText.isNullOrEmpty() || microManeuverFromFallbackMain)
            ) {
                microManeuver = ""
            }
            val priorityLaneGuard = NavigationFeature.first(cleanManeuver, state.maneuver)
            val priorityLaneDistance =
                NavigationFeature.first(
                    NavigationFeature.nonZeroDistance(mainDistance),
                    NavigationFeature.nonZeroDistance(maneuverDistance),
                    NavigationFeature.nonZeroDistance(state.maneuverDistance),
                )
            val laneDecision: ManeuverArbiter.Decision? =
                NavigationManeuverPolicy.decide(
                    priorityLaneGuard,
                    priorityLaneDistance,
                    microManeuver,
                    laneDistanceText,
                    microSource && !laneDistanceText.isNullOrEmpty(),
                    NavigationFeature.sameManeuverFamily(microManeuver, priorityLaneGuard),
                )
            val blockedBySpatialMain: Boolean =
                !microManeuver.isNullOrEmpty() && !laneDecision!!.microWins()
            if (blockedBySpatialMain) {
                logNavigationDecision(
                    intent,
                    source,
                    priorityLaneGuard,
                    priorityLaneDistance,
                    microManeuver,
                    laneDistanceText,
                    priorityLaneGuard,
                    laneDecision.reason,
                )
                AppLog.line(
                    app,
                    ("Navigation ignored lane micro by spatial order: micro=" +
                        NavigationFeature.clean(microManeuver) +
                        " main=" +
                        NavigationFeature.first(priorityLaneGuard, "-") +
                        " stateMain=" +
                        NavigationFeature.clean(state.maneuver) +
                        " mainDistance=" +
                        NavigationFeature.clean(mainDistance) +
                        " microDistance=" +
                        NavigationFeature.clean(laneDistanceText) +
                        " reason=" +
                        laneDecision.reason +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                clearMicroHintHold()
                microManeuver = ""
                laneDistanceText = ""
                laneDebugDistance = ""
                microSource = false
                routeRoadDisplayMicro = false
                laneMicroSuppressedByMain = true
            }
            val explicitLaneDistanceAvailable: Boolean =
                !lanePassed && !explicitMicroDistance.isNullOrEmpty()
            val microDistanceSource: String? =
                if (lanaDistanceFallback) source + ":lana_fallback" else source
            laneDistanceText =
                validMicroDistance(
                    laneDistanceText,
                    mainDistance,
                    microManeuver,
                    microSource && (explicitLaneDistanceAvailable || lanaDistanceFallback),
                    microDistanceSource,
                )
            val rejectedLaneDistance: Boolean =
                microSource &&
                    explicitLaneDistanceAvailable &&
                    !microManeuver.isNullOrEmpty() &&
                    laneDistanceText.isNullOrEmpty()
            if (rejectedLaneDistance) {
                val trustedIncomingDiffersFromActive: Boolean =
                    NavigationFeature.trustedLaneDirectionSource(sourceLower) &&
                        NavigationFeature.isUsableManeuver(microManeuver) &&
                        !activeMicroManeuver.isNullOrEmpty() &&
                        microHintUntil > now &&
                        !NavigationFeature.sameManeuverFamily(microManeuver, activeMicroManeuver)
                val trustedExplicitDistanceRejected: Boolean =
                    NavigationFeature.trustedLaneDirectionSource(sourceLower) &&
                        explicitLaneDistanceAvailable
                if (trustedIncomingDiffersFromActive) {
                    AppLog.line(
                        app,
                        ("Navigation cleared stale lane micro after trusted highlight: active=" +
                            NavigationFeature.clean(activeMicroManeuver) +
                            " incoming=" +
                            NavigationFeature.clean(microManeuver) +
                            " badDistance=" +
                            NavigationFeature.clean(explicitMicroDistance) +
                            " source=" +
                            NavigationFeature.clean(source)),
                    )
                    clearMicroHintHold()
                    laneDebugDistance = explicitMicroDistance
                } else if (trustedExplicitDistanceRejected) {
                    AppLog.line(
                        app,
                        ("Navigation cleared stale lane micro after rejected trusted distance: active=" +
                            NavigationFeature.clean(activeMicroManeuver) +
                            " incoming=" +
                            NavigationFeature.clean(microManeuver) +
                            " badDistance=" +
                            NavigationFeature.clean(explicitMicroDistance) +
                            " main=" +
                            NavigationFeature.clean(mainDistance) +
                            " source=" +
                            NavigationFeature.clean(source)),
                    )
                    clearMicroHintHold()
                    laneDistanceText = ""
                    laneDebugDistance = explicitMicroDistance
                    keepActiveMicroTxAllowed = false
                } else if (
                    !activeMicroManeuver.isNullOrEmpty() &&
                        microHintUntil > now &&
                        !activeMicroDistanceToPublish().isNullOrEmpty()
                ) {
                    microManeuver = activeMicroManeuver
                    laneDistanceText = activeMicroDistanceToPublish()
                    laneDebugDistance = activeMicroDebugDistanceToPublish()
                    keepActiveMicroTxAllowed = true
                    AppLog.line(
                        app,
                        ("Navigation kept active lane micro over bad distance: " +
                            NavigationFeature.clean(microManeuver) +
                            " distance=" +
                            NavigationFeature.clean(laneDistanceText) +
                            " source=" +
                            NavigationFeature.clean(source)),
                    )
                } else {
                    microManeuver = ""
                }
            }
            if (!lanePassed && routeRoadOnly && microSource && laneDistanceText.isNullOrEmpty()) {
                laneDistanceText = activeLaneDistanceToPublish(now)
                laneDebugDistance = laneDistanceText
            }
            if (
                microManeuver.isNullOrEmpty() &&
                    routeRoadOnly &&
                    !laneMicroSuppressedByMain &&
                    !explicitGrayRoad.isNullOrEmpty()
            ) {
                val routeRoadMicroManeuver =
                    NavigationFeature.fallbackLaneMicroManeuver(
                        intent,
                        sourceLower,
                        explicitGrayRoad,
                    )
                val routeRoadMicroDistance =
                    NavigationFeature.first(
                        NavigationFeature.nonZeroDistance(explicitMicroDistance),
                        activeLaneDistanceToPublish(now),
                    )
                if (
                    NavigationFeature.isUsableManeuver(routeRoadMicroManeuver!!) &&
                        !routeRoadMicroDistance.isNullOrEmpty()
                ) {
                    if (
                        mainManeuverBlocksLaneMicro(
                            routeRoadMicroManeuver,
                            routeRoadMicroDistance,
                            mainDistance,
                        )
                    ) {
                        AppLog.line(
                            app,
                            ("Navigation ignored route-road fallback micro by main guard: micro=" +
                                NavigationFeature.clean(routeRoadMicroManeuver) +
                                " main=" +
                                NavigationFeature.clean(state.maneuver) +
                                " mainDistance=" +
                                NavigationFeature.clean(mainDistance) +
                                " source=" +
                                NavigationFeature.clean(source)),
                        )
                        laneMicroSuppressedByMain = true
                    } else {
                        microManeuver = routeRoadMicroManeuver
                        laneDistanceText = routeRoadMicroDistance
                        laneDebugDistance = routeRoadMicroDistance
                        routeRoadDisplayMicro = true
                    }
                }
            }
            if (microManeuver.isNullOrEmpty()) {
                laneDistanceText = ""
                laneDebugDistance = ""
            }
            val incomingHasTrustedMicroDistance: Boolean =
                explicitLaneDistanceAvailable &&
                    !NavigationFeature.nonZeroDistance(laneDistanceText).isNullOrEmpty()
            val incomingDifferentTrustedMicro: Boolean =
                activePostPassMicro &&
                    incomingHasTrustedMicroDistance &&
                    !microManeuver.isNullOrEmpty() &&
                    !highlightedMicroManeuver.isNullOrEmpty() &&
                    !microManeuverFromFallbackMain &&
                    !NavigationFeature.sameManeuverFamily(microManeuver, activeMicroManeuver)
            if (incomingDifferentTrustedMicro) {
                AppLog.line(
                    app,
                    ("Navigation lane micro post-pass released by new micro: active=" +
                        NavigationFeature.clean(activeMicroManeuver) +
                        " incoming=" +
                        NavigationFeature.clean(microManeuver) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                clearMicroHintHold()
                activePostPassMicro = false
            }
            val postPassMicroHolding: Boolean =
                activePostPassMicro &&
                    !lanePostPassBlockedByNearMain &&
                    (lanePassed ||
                        microManeuver.isNullOrEmpty() ||
                        (NavigationFeature.sameManeuverFamily(microManeuver, activeMicroManeuver) &&
                            !incomingHasTrustedMicroDistance))
            if (postPassMicroHolding) {
                microManeuver = activeMicroManeuver
                microSource = true
                routeRoadDisplayMicro = false
                laneDistanceText = ""
                laneDebugDistance = ""
            }
            val microDistance: String? = if (microManeuver.isNullOrEmpty()) "" else laneDistanceText
            val microDebugDistance: String? =
                if (microManeuver.isNullOrEmpty()) "" else laneDebugDistance
            val microDebugSource: Boolean = microSource || routeRoadDisplayMicro
            val providerLaneMicro: Boolean =
                NavigationFeature.providerVisualLaneSource(sourceLower) && microDebugSource
            val microTxSource: Boolean = microDebugSource
            val microHoldTxAllowed: Boolean =
                if (keepActiveMicroTxAllowed) activeMicroTxAllowed else microTxSource
            val microHintDistance = NavigationFeature.first(microDebugDistance, microDistance)
            val microHintAllowed: Boolean =
                microDebugSource &&
                    !microManeuver.isNullOrEmpty() &&
                    !microHintDistance.isNullOrEmpty() &&
                    microDistanceAllowedForTx(
                        microManeuver,
                        microHintDistance,
                        inferredForwardMicro,
                        microDistanceSource,
                    )
            val hintDistance: String? = if (microHintAllowed) microHintDistance else mainDistance
            laneHint =
                NavigationFeature.laneHintText(
                    intent,
                    if (microHintAllowed) microManeuver else cleanManeuver,
                    hintDistance,
                    sourceLower,
                )
            if (routeRoadOnly && !explicitGrayRoad.isNullOrEmpty()) {
                laneHint =
                    NavigationFeature.grayRoadHintText(
                        explicitGrayRoad,
                        intent,
                        sourceLower,
                        hintDistance,
                    )
            }
            val microHintSource: String =
                microDistanceSource.toString() +
                    (if (inferredForwardMicro) ":inferred_forward" else "")
            var microStatus: String? =
                if (postPassMicroHolding) activeMicroStatusToPublish(now)
                else
                    microDecisionStatus(
                        microManeuver,
                        microDistance,
                        microDebugSource,
                        microHintSource,
                    )
            if (!postPassMicroHolding) {
                rememberMicroHint(
                    microManeuver,
                    microDistance,
                    microDebugDistance,
                    microStatus,
                    microHintSource,
                    now,
                    false,
                    microHoldTxAllowed,
                )
            }
            rememberLaneHint(laneHint, source, now)
            state =
                state
                    .withLaneHint(activeLaneHint, activeLaneSource, now)
                    .withNavigationDebug(
                        imageManeuver,
                        visibleRouteActionManeuver,
                        microManeuver,
                        microDebugDistance,
                        microStatus,
                        explicitGrayRoad,
                        NavigationFeature.grayRoadSchemeText(explicitGrayRoad, intent, sourceLower),
                        now,
                    )
            publishNavigationState()
            AppLog.line(app, "Navigation lane hint only: " + laneHint + " source=" + source)
            if (!AppSettings.navMicroManeuvers(app)) {
                sendConfirmedMainRolloverBeforeLaneReturn(
                    rolloverProgressPrimed,
                    incomingMainDistance,
                    "micro_disabled",
                    source,
                )
                sendRoundaboutMainRefinementBeforeLaneReturn(
                    roundaboutMainRefinement,
                    mainDistance,
                    "micro_disabled",
                    source,
                )
                AppLog.line(app, "Navigation ignored micro maneuver by setting: " + source)
                return
            }
            if (NavigationModeSettings.isTbt(app)) {
                sendConfirmedMainRolloverBeforeLaneReturn(
                    rolloverProgressPrimed,
                    incomingMainDistance,
                    "tbt",
                    source,
                )
                sendRoundaboutMainRefinementBeforeLaneReturn(
                    roundaboutMainRefinement,
                    mainDistance,
                    "tbt",
                    source,
                )
                AppLog.line(app, "Navigation ignored micro maneuver in TBT: " + source)
                return
            }
            val grayRoad: String? = explicitGrayRoad
            if (!grayRoad.isNullOrEmpty()) {
                if (stateRoundabout && !roundabout)
                    clearStaleManeuverVisual("lane replaced roundabout " + source)
                val priorityLaneManeuver = NavigationFeature.first(displayManeuver, state.maneuver)
                val priorityLaneTxDistance =
                    NavigationFeature.first(
                        NavigationFeature.nonZeroDistance(mainDistance),
                        NavigationFeature.nonZeroDistance(maneuverDistance),
                        NavigationFeature.nonZeroDistance(state.maneuverDistance),
                    )
                val grayDecision: ManeuverArbiter.Decision? =
                    NavigationManeuverPolicy.decide(
                        priorityLaneManeuver,
                        priorityLaneTxDistance,
                        microManeuver,
                        microDistance,
                        microTxSource && !microDistance.isNullOrEmpty(),
                        NavigationFeature.sameManeuverFamily(microManeuver, priorityLaneManeuver),
                    )
                val lanePriorityActive: Boolean = microTxSource && !grayDecision!!.microWins()
                val stateGrayRoad: String? = if (lanePriorityActive) "" else grayRoad
                val stateGrayScheme: String? =
                    if (lanePriorityActive) ""
                    else NavigationFeature.grayRoadSchemeText(grayRoad, intent, sourceLower)
                val grayKey: String =
                    NavigationFeature.grayRoadKey(
                        NavigationFeature.first(state.maneuver, cleanManeuver),
                        NavigationFeature.first(
                            state.nextStreet,
                            acceptedNext,
                            acceptedCurrent,
                            state.currentStreet,
                        ),
                    )
                if (lanePriorityActive) {
                    clearGrayRoadHold()
                } else {
                    rememberLearnedRoadOptions(
                        NavigationFeature.roadOptionKey(
                            NavigationFeature.first(acceptedCurrent, state.currentStreet),
                            NavigationFeature.first(acceptedNext, state.nextStreet),
                        ),
                        NavigationFeature.grayRoadMask(grayRoad),
                        now,
                        source,
                    )
                    rememberGrayRoad(
                        grayRoad,
                        grayKey,
                        NavigationFeature.first(
                            stateGrayScheme,
                            NavigationFeature.laneDebugText(grayRoad, laneHint),
                        ),
                        source,
                        now,
                    )
                }
                state =
                    state
                        .withLaneHint(activeLaneHint, activeLaneSource, now)
                        .withNavigationDebug(
                            imageManeuver,
                            visibleRouteActionManeuver,
                            microManeuver,
                            microDebugDistance,
                            microStatus,
                            stateGrayRoad,
                            stateGrayScheme,
                            now,
                        )
                publishNavigationState()
                lastRouteGuidanceAt = now
                cancelPendingInactive("lane " + source)
                val microAllowed: Boolean =
                    !postPassMicroHolding &&
                        !lanePriorityActive &&
                        (!microTxSource ||
                            (!microDistance.isNullOrEmpty() &&
                                microDistanceAllowedForTx(
                                    microManeuver,
                                    microDistance,
                                    inferredForwardMicro,
                                    microDistanceSource,
                                )))
                val sendingMicro: Boolean = !postPassMicroHolding && microTxSource && microAllowed
                val providerMicroTx: Boolean = sendingMicro && providerLaneMicro
                val routeActionIsMain: Boolean =
                    NavigationFeature.shouldPromoteRouteRoadActionAsMain(
                        routeRoadOnly,
                        visibleRouteActionManeuver!!,
                        displayManeuver,
                        maneuverDistance,
                        routeRoadActionRejectedAsMain,
                        intent,
                    )
                val routeActionAllowedAsMain: Boolean =
                    !lanePriorityActive || visibleRouteActionManeuver.equals(priorityLaneManeuver)
                if (
                    routeActionIsMain &&
                        routeActionAllowedAsMain &&
                        !visibleRouteActionManeuver.isNullOrEmpty() &&
                        !visibleRouteActionManeuver.equals(state.maneuver)
                ) {
                    if (
                        NavigationFeature.maneuverFamilyChanged(
                            state.maneuver,
                            visibleRouteActionManeuver,
                        )
                    ) {
                        clearClusterVisualHold()
                        resetNavigationSendCache()
                    }
                    state =
                        NavigationState(
                                true,
                                finish,
                                state.speedExceeded,
                                visibleRouteActionManeuver,
                                NavigationFeature.maneuverLabel(
                                    visibleRouteActionManeuver,
                                    roundaboutExit!!,
                                ),
                                mainDistance,
                                state.routeDistance,
                                state.routeTime,
                                state.arrivalTime,
                                NavigationFeature.first(acceptedCurrent, state.currentStreet),
                                NavigationFeature.first(acceptedNext, state.nextStreet),
                                acceptedFinish,
                                state.speedLimit,
                                state.currentSpeed,
                                source,
                                now,
                            )
                            .withLaneHint(activeLaneHint, activeLaneSource, now)
                            .withNavigationDebug(
                                imageManeuver,
                                visibleRouteActionManeuver,
                                microManeuver,
                                microDebugDistance,
                                microStatus,
                                stateGrayRoad,
                                stateGrayScheme,
                                now,
                            )
                    publishNavigationState()
                    AppLog.line(
                        app,
                        ("Navigation route-road promoted priority main: " +
                            visibleRouteActionManeuver +
                            " distance=" +
                            NavigationFeature.clean(mainDistance) +
                            " source=" +
                            source),
                    )
                }
                if (microTxSource && !microAllowed && !postPassMicroHolding) {
                    if (lanePriorityActive) {
                        AppLog.line(
                            app,
                            ("Navigation micro held by priority: " +
                                NavigationFeature.clean(microManeuver) +
                                " priority=" +
                                priorityLaneManeuver +
                                " source=" +
                                source),
                        )
                    } else {
                        AppLog.line(
                            app,
                            ("Navigation micro held by distance threshold: " +
                                NavigationFeature.clean(microManeuver) +
                                " distance=" +
                                NavigationFeature.clean(microDistance) +
                                " max=" +
                                AppSettings.navMicroMaxDistanceMeters(app) +
                                " source=" +
                                source),
                        )
                    }
                }
                if (microDebugSource) {
                    microStatus =
                        if (postPassMicroHolding) activeMicroStatusToPublish(now)
                        else if (lanePriorityActive)
                            NavigationFeature.microMainHoldStatus(
                                NavigationFeature.isRoundaboutManeuver(priorityLaneManeuver),
                                priorityLaneManeuver,
                            )
                        else if (sendingMicro) microSentStatus(microDistance)
                        else
                            microDecisionStatus(microManeuver, microDistance, true, microHintSource)
                    if (!microManeuver.isNullOrEmpty()) {
                        if (lanePriorityActive) {
                            clearMicroHintHold()
                        } else if (!postPassMicroHolding) {
                            rememberMicroHint(
                                microManeuver,
                                microDistance,
                                microDebugDistance,
                                microStatus,
                                microHintSource,
                                now,
                                false,
                                microHoldTxAllowed,
                            )
                        }
                    }
                    state =
                        state.withNavigationDebug(
                            imageManeuver,
                            visibleRouteActionManeuver,
                            microManeuver,
                            microDebugDistance,
                            microStatus,
                            stateGrayRoad,
                            stateGrayScheme,
                            now,
                        )
                    publishNavigationState()
                }
                if (postPassMicroHolding) {
                    AppLog.line(
                        app,
                        ("Navigation lane post-pass debug only: " +
                            NavigationFeature.clean(activeMicroManeuver) +
                            " status=" +
                            activeMicroStatusToPublish(now) +
                            " source=" +
                            source),
                    )
                }
                var laneMainManeuver: String? =
                    NavigationFeature.firstMergeableManeuver(
                        arrayOf(state.maneuver, visibleRouteActionManeuver, cleanManeuver)
                    )
                if (laneMainManeuver.isNullOrEmpty()) {
                    laneMainManeuver =
                        NavigationFeature.first(
                            state.maneuver,
                            visibleRouteActionManeuver,
                            cleanManeuver,
                        )
                }
                val routeManeuverCandidate =
                    NavigationFeature.first(
                        priorityLaneManeuver,
                        laneMainManeuver,
                        state.maneuver,
                        visibleRouteActionManeuver,
                        cleanManeuver,
                    )
                val routeMainTx: MicroTxDistancePolicy.MainSnapshot =
                    MicroTxDistancePolicy.selectMainSnapshot(
                        routeManeuverCandidate,
                        mainDistance,
                        state.maneuver,
                        state.maneuverDistance,
                    )
                val routeManeuverForTx: String? = routeMainTx.maneuver
                val routeDistanceForTx: String? = routeMainTx.distance
                val routeProgressForTx: Int =
                    currentMainTxProgress(routeManeuverForTx!!, routeDistanceForTx)
                val keepActiveMicroOnRouteRoadGray: Boolean =
                    routeRoadOnly &&
                        !sendingMicro &&
                        !lanePriorityActive &&
                        shouldTransmitActiveMicroToCluster()
                val resolvedMicroTx: Boolean = sendingMicro || keepActiveMicroOnRouteRoadGray
                val laneManeuver: String? =
                    if (lanePriorityActive) priorityLaneManeuver
                    else if (sendingMicro) microManeuver
                    else if (keepActiveMicroOnRouteRoadGray) activeMicroManeuver
                    else laneMainManeuver
                val laneSendDistance: String? = routeDistanceForTx
                val grayDecisionReason: String? =
                    if (sendingMicro) grayDecision!!.reason
                    else if (keepActiveMicroOnRouteRoadGray) "active_micro_before_main"
                    else if (lanePriorityActive) grayDecision.reason
                    else if (microTxSource) "micro_filtered_by_mode_or_distance"
                    else "main_selected"
                if (!laneMicroSuppressedByMain) {
                    logNavigationDecision(
                        intent,
                        source,
                        priorityLaneManeuver,
                        priorityLaneTxDistance,
                        if (sendingMicro) microManeuver else activeMicroManeuver,
                        if (sendingMicro) microDistance else activeMicroDistanceToPublish(),
                        laneManeuver,
                        grayDecisionReason,
                    )
                }
                val grayRoadForTx: String? =
                    if (sendingMicro || keepActiveMicroOnRouteRoadGray)
                        stableGrayRoadForLaneTx(grayRoad, laneManeuver, now)
                    else grayRoad
                if (!grayRoadForTx.equals(grayRoad)) {
                    val stableGrayKey: String =
                        NavigationFeature.grayRoadKey(
                            laneManeuver,
                            NavigationFeature.first(
                                state.nextStreet,
                                acceptedNext,
                                acceptedCurrent,
                                state.currentStreet,
                            ),
                        )
                    rememberGrayRoad(
                        grayRoadForTx,
                        stableGrayKey,
                        NavigationFeature.first(
                            NavigationFeature.grayRoadSchemeText(
                                grayRoadForTx,
                                intent,
                                sourceLower,
                            ),
                            NavigationFeature.laneDebugText(grayRoadForTx, laneHint),
                        ),
                        source + ":stable_micro_gray",
                        now,
                    )
                }
                val currentClusterYellow = currentClusterYellowManeuver()
                val routeRoadMayForceYellow: Boolean =
                    lanePriorityActive || sendingMicro || keepActiveMicroOnRouteRoadGray
                val forceLaneSnapshot: Boolean =
                    routeRoadOnly &&
                        routeRoadMayForceYellow &&
                        !laneManeuver.isNullOrEmpty() &&
                        NavigationFeature.clusterYellowDiffersFrom(
                            laneManeuver,
                            currentClusterYellow,
                        )
                val forceLaneTx: Boolean = forceLaneSnapshot
                if (forceLaneSnapshot) {
                    AppLog.line(
                        app,
                        ("Navigation forced lane TX over stale cluster: expected=" +
                            laneManeuver +
                            " cluster=" +
                            currentClusterYellow +
                            " source=" +
                            source),
                    )
                }
                val laneTxProgressBucket: Int = routeProgressForTx
                if (resolvedMicroTx) {
                    sendResolvedMicroVisual(
                        laneManeuver!!,
                        if (NavigationFeature.canMergeGrayRoad(laneManeuver)) grayRoadForTx else "",
                        routeManeuverForTx,
                        laneSendDistance,
                        laneTxProgressBucket,
                        forceLaneTx,
                        source + ":lane_gray",
                    )
                } else if (
                    !laneManeuver.isNullOrEmpty() &&
                        NavigationFeature.canMergeGrayRoad(laneManeuver)
                ) {
                    if (
                        routeRoadOnly &&
                            NavigationFeature.nonZeroDistance(laneSendDistance).isNullOrEmpty()
                    ) {
                        AppLog.line(
                            app,
                            ("Navigation lane gray road held without distance: " +
                                laneManeuver +
                                " gray=" +
                                grayRoad +
                                " source=" +
                                source),
                        )
                    } else {
                        sendManeuverWithGrayRoadIfChanged(
                            laneManeuver,
                            grayRoadForTx,
                            NavigationFeature.distanceValue(laneSendDistance),
                            NavigationFeature.isKm(laneSendDistance),
                            laneTxProgressBucket,
                            forceLaneTx,
                        )
                    }
                } else if (!laneManeuver.isNullOrEmpty()) {
                    if (
                        routeRoadOnly &&
                            NavigationFeature.nonZeroDistance(laneSendDistance).isNullOrEmpty()
                    ) {
                        AppLog.line(
                            app,
                            ("Navigation lane maneuver held without distance: " +
                                laneManeuver +
                                " source=" +
                                source),
                        )
                    } else {
                        sendManeuverIfChanged(
                            laneManeuver,
                            NavigationFeature.distanceValue(laneSendDistance),
                            NavigationFeature.isKm(laneSendDistance),
                            laneTxProgressBucket,
                            forceLaneTx,
                        )
                    }
                } else {
                    if (
                        routeRoadOnly &&
                            NavigationFeature.nonZeroDistance(laneSendDistance).isNullOrEmpty()
                    ) {
                        AppLog.line(
                            app,
                            ("Navigation gray road held without distance: " +
                                grayRoad +
                                " source=" +
                                source),
                        )
                    } else {
                        sendManeuverIfChanged(
                            grayRoad,
                            NavigationFeature.distanceValue(laneSendDistance),
                            NavigationFeature.isKm(laneSendDistance),
                            laneTxProgressBucket,
                            forceLaneTx,
                        )
                    }
                }
                if (providerMicroTx && !microManeuver.isNullOrEmpty()) {
                    AppLog.line(
                        app,
                        ("Navigation provider micro TX with main distance/progress: " +
                            NavigationFeature.clean(microManeuver) +
                            " mainDistance=" +
                            NavigationFeature.clean(routeDistanceForTx) +
                            " laneDistance=" +
                            NavigationFeature.clean(microDistance) +
                            " progress=" +
                            laneTxProgressBucket +
                            " gray=" +
                            NavigationFeature.first(grayRoadForTx, "-") +
                            " source=" +
                            source),
                    )
                }
                AppLog.line(
                    app,
                    ("Navigation lane gray road merge: " +
                        grayRoad +
                        " hint=" +
                        laneHint +
                        " raw=" +
                        NavigationFeature.shortRaw(source, NavigationFeature.rawExtras(intent))),
                )
                if (sendingMicro && !microManeuver.isNullOrEmpty()) {
                    // keep micro hint active through periodic micro overlay refresh
                }
                return
            }
            var heldGrayRoad: String? = if (grayRoadUntil > now) activeGrayRoadId else ""
            if (
                NavigationFeature.providerVisualLaneSource(sourceLower) &&
                    !heldGrayRoad.isNullOrEmpty()
            ) {
                AppLog.line(
                    app,
                    ("Navigation cleared stale provider gray road without topology: held=" +
                        NavigationFeature.clean(heldGrayRoad) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                clearGrayRoadHold()
                clearLearnedRoadOptions()
                heldGrayRoad = ""
            }
            AppLog.line(
                app,
                ("Navigation lane gray road skipped: no topology held=" +
                    NavigationFeature.first(heldGrayRoad, "-") +
                    " " +
                    NavigationFeature.shortRaw(source, NavigationFeature.rawExtras(intent))),
            )
            state =
                state.withNavigationDebug(
                    imageManeuver,
                    visibleRouteActionManeuver,
                    microManeuver,
                    microDebugDistance,
                    microStatus,
                    heldGrayRoad,
                    NavigationFeature.grayRoadLabel(heldGrayRoad),
                    now,
                )
            publishNavigationState()
            val providerMicroWithoutGray: Boolean =
                NavigationFeature.providerVisualLaneSource(sourceLower) &&
                    (microSource || routeRoadDisplayMicro)
            val noGrayDecision: ManeuverArbiter.Decision? =
                NavigationManeuverPolicy.decide(
                    NavigationFeature.first(state.maneuver, cleanManeuver),
                    NavigationFeature.first(mainDistance, state.maneuverDistance),
                    microManeuver,
                    microDistance,
                    (microSource || routeRoadDisplayMicro) && !microDistance.isNullOrEmpty(),
                    NavigationFeature.sameManeuverFamily(
                        microManeuver,
                        NavigationFeature.first(state.maneuver, cleanManeuver),
                    ),
                )
            val microWithoutGrayCanSend: Boolean =
                (microSource || routeRoadDisplayMicro) &&
                    !microManeuver.isNullOrEmpty() &&
                    !microDistance.isNullOrEmpty() &&
                    microDistanceAllowedForTx(
                        microManeuver,
                        microDistance,
                        inferredForwardMicro,
                        source,
                    ) &&
                    AppSettings.navMicroManeuvers(app) &&
                    !NavigationModeSettings.isTbt(app) &&
                    noGrayDecision!!.microWins()
            val noGrayReason: String? =
                if (noGrayDecision!!.microWins() && !microWithoutGrayCanSend)
                    "micro_filtered_by_mode_or_distance"
                else noGrayDecision.reason
            if (!laneMicroSuppressedByMain) {
                logNavigationDecision(
                    intent,
                    source,
                    NavigationFeature.first(state.maneuver, cleanManeuver),
                    NavigationFeature.first(mainDistance, state.maneuverDistance),
                    microManeuver,
                    microDistance,
                    if (microWithoutGrayCanSend) microManeuver
                    else NavigationFeature.first(state.maneuver, cleanManeuver),
                    noGrayReason,
                )
            }
            val mainCandidateForTx =
                NavigationFeature.first(displayManeuver, state.maneuver, cleanManeuver)
            val noGrayMainTx: MicroTxDistancePolicy.MainSnapshot =
                MicroTxDistancePolicy.selectMainSnapshot(
                    mainCandidateForTx,
                    NavigationFeature.first(mainDistance, maneuverDistance),
                    state.maneuver,
                    state.maneuverDistance,
                )
            val mainForTx: String? = noGrayMainTx.maneuver
            val mainDistanceForTx: String? = noGrayMainTx.distance
            if (microWithoutGrayCanSend) {
                lastRouteGuidanceAt = now
                cancelPendingInactive("lane micro " + source)
                val forceMicroTx: Boolean =
                    NavigationFeature.clusterYellowDiffersFrom(
                        microManeuver,
                        currentClusterYellowManeuver(),
                    )
                val sentMicro: Boolean =
                    sendResolvedMicroVisual(
                        microManeuver,
                        "",
                        mainForTx!!,
                        mainDistanceForTx,
                        currentMainTxProgress(mainForTx, mainDistanceForTx),
                        forceMicroTx,
                        source + ":lane_no_gray",
                    )
                if (sentMicro) {
                    AppLog.line(
                        app,
                        ((if (providerMicroWithoutGray)
                            "Navigation provider micro TX without gray road: "
                        else "Navigation lane micro TX without gray road: ") +
                            NavigationFeature.clean(microManeuver) +
                            " mainDistance=" +
                            NavigationFeature.clean(mainDistance) +
                            " laneDistance=" +
                            NavigationFeature.clean(microDistance) +
                            " txDistance=" +
                            NavigationFeature.clean(mainDistanceForTx) +
                            " heldGray=" +
                            NavigationFeature.first(heldGrayRoad, "-") +
                            " source=" +
                            source),
                    )
                }
            } else {
                if (
                    NavigationFeature.isUsableManeuver(mainForTx!!) &&
                        (!routeRoadOnly || !mainDistanceForTx.isNullOrEmpty())
                ) {
                    sendManeuverIfChanged(
                        mainForTx,
                        NavigationFeature.distanceValue(mainDistanceForTx),
                        NavigationFeature.isKm(mainDistanceForTx),
                        currentMainTxProgress(mainForTx, mainDistanceForTx),
                        false,
                    )
                }
            }
            return
        } else if (roundabout) {
            clearLaneHintHold(true)
        } else if (
            laneGuidanceUntil > now &&
                !finish &&
                !NavigationFeature.sameManeuverFamily(state.maneuver, cleanManeuver)
        ) {
            laneGuidanceUntil = 0L
            if (stateRoundabout)
                clearStaleManeuverVisual("maneuver family changed after roundabout " + source)
        }
        if (
            !direct &&
                lastDirectManeuverAt > 0L &&
                now - lastDirectManeuverAt < 1800L &&
                !state.maneuver.isNullOrEmpty()
        ) {
            AppLog.line(app, "Navigation ignored weak maneuver source: " + source)
            return
        }
        if (direct) lastDirectManeuverAt = now
        lastRouteGuidanceAt = now
        waitingForRoute = false
        routeStartedAt = 0L
        routeLoadingFallbackGeneration++
        cancelPendingInactive("maneuver " + source)

        if (NavigationFeature.maneuverFamilyChanged(state.maneuver, displayManeuver)) {
            val preserveMicro: Boolean =
                shouldPreserveMicroAfterRoundaboutTransition(
                    state.maneuver,
                    displayManeuver,
                    now,
                ) ||
                    NavigationManeuverPolicy.decide(
                            displayManeuver,
                            maneuverDistance,
                            highlightedMicroManeuver,
                            explicitMicroDistance,
                            !explicitMicroDistance.isNullOrEmpty(),
                            NavigationFeature.sameManeuverFamily(
                                displayManeuver,
                                highlightedMicroManeuver,
                            ),
                        )
                        .microWins()
            clearClusterVisualHold()
            resetNavigationSendCache()
            clearLaneHintHold(preserveMicro)
        } else if (
            explicitGrayRoad.isNullOrEmpty() &&
                NavigationFeature.sameFamilyDistanceResetForNewManeuver(
                    state.maneuver,
                    displayManeuver,
                    state.maneuverDistance,
                    maneuverDistance,
                ) &&
                !activeGrayRoadId.isNullOrEmpty()
        ) {
            AppLog.line(
                app,
                ("Navigation cleared stale gray road on same-family distance reset: maneuver=" +
                    NavigationFeature.clean(displayManeuver) +
                    " previous=" +
                    NavigationFeature.clean(state.maneuverDistance) +
                    " incoming=" +
                    NavigationFeature.clean(maneuverDistance) +
                    " gray=" +
                    NavigationFeature.clean(activeGrayRoadId) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            clearGrayRoadHold()
            clearLearnedRoadOptions()
        }
        val highlightedMicroForOverlay: Boolean =
            NavigationFeature.isUsableManeuver(highlightedMicroManeuver) && !direct
        val highlightedMicroDiffersFromMain: Boolean =
            highlightedMicroForOverlay && !highlightedMicroManeuver.equals(displayManeuver)
        val highlightedMicroDistance: String? =
            if (highlightedMicroForOverlay)
                validMicroDistance(
                    explicitMicroDistance,
                    NavigationFeature.first(
                        NavigationFeature.nonZeroDistance(maneuverDistance),
                        NavigationFeature.nonZeroDistance(state.maneuverDistance),
                    ),
                    highlightedMicroManeuver,
                    !explicitMicroDistance.isNullOrEmpty(),
                    source,
                )
            else ""
        val highlightedMicroDebugDistance: String? =
            if (highlightedMicroForOverlay) explicitMicroDistance else ""
        val spatialMainDistance =
            NavigationFeature.first(
                NavigationFeature.nonZeroDistance(maneuverDistance),
                NavigationFeature.nonZeroDistance(state.maneuverDistance),
            )
        val highlightedDecision: ManeuverArbiter.Decision? =
            NavigationManeuverPolicy.decide(
                displayManeuver,
                spatialMainDistance,
                highlightedMicroManeuver,
                highlightedMicroDistance,
                highlightedMicroForOverlay && !highlightedMicroDistance.isNullOrEmpty(),
                NavigationFeature.sameManeuverFamily(highlightedMicroManeuver, displayManeuver),
            )
        val highlightedMicroCanSend: Boolean =
            highlightedMicroForOverlay &&
                highlightedMicroDiffersFromMain &&
                !highlightedMicroDistance.isNullOrEmpty() &&
                microDistanceAllowed(highlightedMicroDistance, source) &&
                highlightedDecision!!.microWins() &&
                AppSettings.navMicroManeuvers(app) &&
                !NavigationModeSettings.isTbt(app)
        val heldMicroTrusted: Boolean =
            AppSettings.navMicroManeuvers(app) &&
                !NavigationModeSettings.isTbt(app) &&
                !NavigationModeSettings.isFinishDirection(app) &&
                microHintUntil > now &&
                !activeMicroDistanceToPublish().isNullOrEmpty()
        val heldMicroDecision: ManeuverArbiter.Decision? =
            NavigationManeuverPolicy.decide(
                displayManeuver,
                spatialMainDistance,
                activeMicroManeuver,
                activeMicroDistanceToPublish(),
                heldMicroTrusted,
                NavigationFeature.sameManeuverFamily(activeMicroManeuver, displayManeuver),
            )
        val mainStandaloneFrame: Boolean =
            finish || roundabout || NavigationFeature.isStandaloneManeuverFrame(displayManeuver)
        val standaloneFrame: Boolean =
            mainStandaloneFrame && !highlightedMicroCanSend && !heldMicroDecision!!.microWins()
        if (standaloneFrame) {
            clearLaneHintHold(false)
        }
        val highlightedMicroStatus: String? =
            if (highlightedMicroForOverlay)
                (if (!highlightedMicroDiffersFromMain) "совпадает с основным"
                else if (highlightedMicroCanSend) microSentStatus(highlightedMicroDistance)
                else
                    microDecisionStatus(
                        highlightedMicroManeuver,
                        highlightedMicroDistance,
                        true,
                        source,
                    ))
            else ""
        if (highlightedMicroForOverlay) {
            rememberMicroHint(
                highlightedMicroManeuver,
                highlightedMicroDistance,
                highlightedMicroDebugDistance,
                highlightedMicroStatus,
                source,
                now,
                false,
                true,
            )
            AppLog.line(
                app,
                ("Navigation micro from lane highlight: " +
                    NavigationFeature.clean(highlightedMicroManeuver) +
                    " distance=" +
                    NavigationFeature.clean(highlightedMicroDistance) +
                    " status=" +
                    NavigationFeature.clean(highlightedMicroStatus) +
                    " main=" +
                    NavigationFeature.clean(displayManeuver) +
                    " source=" +
                    source),
            )
        }
        val routeRoadStandaloneWithoutDistance: Boolean =
            routeRoadOnly &&
                standaloneFrame &&
                NavigationFeature.nonZeroDistance(maneuverDistance).isNullOrEmpty()
        if (
            roundabout &&
                routeRoadStandaloneWithoutDistance &&
                lastDirectManeuverAt > 0L &&
                now - lastDirectManeuverAt > NavigationFeature.ROUNDABOUT_EXIT_HOLD_MS
        ) {
            clearStaleManeuverVisual("stale roundabout route-road poll " + source)
            return
        }
        var displayDistance: String? = maneuverDistance
        if (routeRoadStandaloneWithoutDistance && displayManeuver.equals(state.maneuver)) {
            displayDistance = state.maneuverDistance
        }
        val debugGrayRoad: String? = if (standaloneFrame) "" else explicitGrayRoad
        val debugGrayScheme: String = NavigationFeature.grayRoadLabel(debugGrayRoad)
        val previousManeuver: String? = state.maneuver
        val previousManeuverDistance: String? = state.maneuverDistance
        state =
            NavigationState(
                    true,
                    finish,
                    state.speedExceeded,
                    displayManeuver,
                    NavigationFeature.maneuverLabel(displayManeuver, roundaboutExit!!),
                    displayDistance,
                    state.routeDistance,
                    state.routeTime,
                    state.arrivalTime,
                    NavigationFeature.first(acceptedCurrent, state.currentStreet),
                    NavigationFeature.first(acceptedNext, state.nextStreet),
                    acceptedFinish,
                    state.speedLimit,
                    state.currentSpeed,
                    source,
                    now,
                )
                .withLaneHint(activeLaneHint, activeLaneSource, now)
                .withNavigationDebug(
                    imageManeuver,
                    visibleRouteActionManeuver,
                    if (highlightedMicroForOverlay) highlightedMicroManeuver else "",
                    if (highlightedMicroForOverlay) highlightedMicroDebugDistance else "",
                    if (highlightedMicroForOverlay) highlightedMicroStatus else "",
                    debugGrayRoad,
                    debugGrayScheme,
                    now,
                )
        if (
            maybeStartFinalSegmentFinishDirection(
                routeDistanceForFinalSegment,
                previousManeuver!!,
                previousManeuverDistance,
                displayManeuver,
                displayDistance,
                source,
                now,
            )
        ) {
            if (!sendDirectionToFinishIfNeeded(true)) sendFinishDirectionPlaceholder(true)
            sendConfiguredText()
            AppLog.line(app, "Navigation maneuver final segment: " + state.summary())
            return
        }
        var fallbackGrayRoad: String? = ""
        if (!laneGuidance && !standaloneFrame) {
            val roadOptionKey: String =
                NavigationFeature.roadOptionKey(state.currentStreet, state.nextStreet)
            val explicitGray: Boolean = !explicitGrayRoad.isNullOrEmpty()
            fallbackGrayRoad = if (explicitGray) explicitGrayRoad else ""
            if (!fallbackGrayRoad.isNullOrEmpty()) {
                if (explicitGray) {
                    val grayKey: String =
                        NavigationFeature.grayRoadKey(
                            displayManeuver,
                            NavigationFeature.first(
                                state.nextStreet,
                                acceptedNext,
                                acceptedCurrent,
                                state.currentStreet,
                            ),
                        )
                    rememberLearnedRoadOptions(
                        roadOptionKey,
                        NavigationFeature.grayRoadMask(fallbackGrayRoad),
                        now,
                        source,
                    )
                    rememberGrayRoad(
                        fallbackGrayRoad,
                        grayKey,
                        NavigationFeature.grayRoadLabel(fallbackGrayRoad),
                        source,
                        now,
                    )
                }
                state =
                    state
                        .withLaneHint(activeLaneHint, activeLaneSource, now)
                        .withNavigationDebug(
                            imageManeuver,
                            visibleRouteActionManeuver,
                            if (highlightedMicroForOverlay) highlightedMicroManeuver else "",
                            if (highlightedMicroForOverlay) highlightedMicroDebugDistance else "",
                            if (highlightedMicroForOverlay) highlightedMicroStatus else "",
                            fallbackGrayRoad,
                            NavigationFeature.grayRoadLabel(fallbackGrayRoad),
                            now,
                        )
            }
        }
        AppLog.line(
            app,
            ("Navigation gray fallback decision: maneuver=" +
                displayManeuver +
                " gray=" +
                NavigationFeature.first(fallbackGrayRoad, "-") +
                " lane=" +
                laneGuidance +
                " roundabout=" +
                roundabout +
                " finish=" +
                finish +
                " mode=" +
                NavigationModeSettings.label(app) +
                " finishOverride=" +
                finishDirectionShouldOverride() +
                " source=" +
                source),
        )
        publishNavigationState()
        if (!laneGuidance) startManeuverTextHint(displayManeuver, roundaboutExit)
        val progressBucket: Int = maneuverProgressBucket(displayManeuver, displayDistance)
        val sendHighlightedMicro: Boolean = highlightedMicroCanSend
        val sendActiveMicro: Boolean = !sendHighlightedMicro && shouldTransmitActiveMicroToCluster()
        val isMicroTx: Boolean = sendHighlightedMicro || sendActiveMicro
        val waitingMicroDistance: Boolean =
            !sendHighlightedMicro &&
                !sendActiveMicro &&
                microHintUntil > now &&
                !activeMicroManeuver.isNullOrEmpty() &&
                activeMicroDistanceToPublish().isNullOrEmpty()
        val txManeuver: String?
        val txDistance: String?
        val txProgressBucket: Int
        if (sendHighlightedMicro) {
            txManeuver = highlightedMicroManeuver
            txDistance = displayDistance
            txProgressBucket = progressBucket
        } else if (sendActiveMicro) {
            txManeuver = activeMicroManeuver
            txDistance = displayDistance
            txProgressBucket = progressBucket
        } else {
            txManeuver = displayManeuver
            txDistance = displayDistance
            txProgressBucket = progressBucket
        }
        if (waitingMicroDistance) {
            if (rolloverProgressPrimed) {
                sendConfirmedMainRolloverBeforeLaneReturn(
                    true,
                    displayDistance,
                    "waiting_micro_distance",
                    source,
                )
                AppLog.line(
                    app,
                    ("Navigation released empty-distance micro for confirmed main: " +
                        NavigationFeature.clean(activeMicroManeuver) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
            } else {
                AppLog.line(
                    app,
                    ("Navigation wait micro distance, keep current tx: " +
                        NavigationFeature.clean(activeMicroManeuver) +
                        " status=" +
                        NavigationFeature.clean(activeMicroStatus)),
                )
            }
        } else {
            val forceRouteSnapshot: Boolean =
                routeRoadOnly && !state.clusterTx.contains(txManeuver!!)
            val decisionReason: String? =
                if (sendHighlightedMicro) highlightedDecision.reason
                else if (sendActiveMicro) heldMicroDecision!!.reason else "main_selected"
            logNavigationDecision(
                intent,
                source,
                displayManeuver,
                displayDistance,
                if (sendHighlightedMicro) highlightedMicroManeuver else activeMicroManeuver,
                if (sendHighlightedMicro) highlightedMicroDistance
                else activeMicroDistanceToPublish(),
                txManeuver,
                decisionReason,
            )
            val txDistanceForTx: String? = txDistance
            val txProgressForTx: Int = txProgressBucket
            if (routeRoadStandaloneWithoutDistance) {
                AppLog.line(
                    app,
                    ("Navigation route-road standalone held from TX: " +
                        displayManeuver +
                        " source=" +
                        source),
                )
            } else if (isMicroTx) {
                sendResolvedMicroVisual(
                    txManeuver!!,
                    "",
                    displayManeuver,
                    txDistanceForTx,
                    txProgressForTx,
                    forceRouteSnapshot,
                    source + ":highlighted_or_held",
                )
            } else if (!fallbackGrayRoad.isNullOrEmpty() && !isMicroTx) {
                sendManeuverWithGrayRoadIfChanged(
                    txManeuver,
                    fallbackGrayRoad,
                    NavigationFeature.distanceValue(txDistance),
                    NavigationFeature.isKm(txDistance),
                    txProgressBucket,
                    forceRouteSnapshot,
                )
            } else {
                sendManeuverIfChanged(
                    txManeuver,
                    NavigationFeature.distanceValue(txDistanceForTx),
                    NavigationFeature.isKm(txDistanceForTx),
                    txProgressForTx,
                    forceRouteSnapshot,
                )
            }
        }
        if (sendHighlightedMicro) {
            AppLog.line(
                app,
                ("Navigation micro TX from lane highlight: " +
                    NavigationFeature.clean(highlightedMicroManeuver) +
                    " over main=" +
                    NavigationFeature.clean(displayManeuver) +
                    " txDistance=main txProgress=main" +
                    " gray=" +
                    NavigationFeature.first(
                        NavigationFeature.grayRoadLabel(fallbackGrayRoad),
                        "-",
                    ) +
                    " source=" +
                    source),
            )
        }
        if (finish) startFinishHold() else sendConfiguredText()
        AppLog.line(
            app,
            (if (laneGuidance) "Navigation micro maneuver: " else "Navigation maneuver: ") +
                state.summary(),
        )
    }

    private fun applyGrayRoadOnly(grayRoad: String?, source: String?, distanceText: String?) {
        val now: Long = System.currentTimeMillis()
        val maneuver = NavigationFeature.clean(state.maneuver)
        val grayKey: String =
            if (maneuver.isNullOrEmpty()) ""
            else
                NavigationFeature.grayRoadKey(
                    maneuver,
                    NavigationFeature.first(state.nextStreet, state.currentStreet),
                )
        rememberGrayRoad(grayRoad, grayKey, NavigationFeature.grayRoadLabel(grayRoad), source, now)
        rememberLearnedRoadOptions(
            NavigationFeature.roadOptionKey(state.currentStreet, state.nextStreet),
            NavigationFeature.grayRoadMask(grayRoad),
            now,
            source,
        )
        state = state.withLaneHint(activeLaneHint, activeLaneSource, now)
        state =
            state.withNavigationDebug(
                state.mainManeuverId,
                state.routeActionId,
                state.microManeuverId,
                state.microDistance,
                state.microStatus,
                grayRoad,
                NavigationFeature.grayRoadLabel(grayRoad),
                now,
            )
        publishNavigationState()
        lastRouteGuidanceAt = now
        cancelPendingInactive("gray road " + source)
        if (
            state.active &&
                !maneuver.isNullOrEmpty() &&
                NavigationFeature.canMergeGrayRoad(maneuver)
        ) {
            val distance = currentMainTxDistance()
            if (distance.isNullOrEmpty()) {
                AppLog.line(
                    app,
                    ("Navigation gray road kept without main TX distance: " +
                        grayRoad +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
            } else {
                sendManeuverWithGrayRoadIfChanged(
                    maneuver,
                    grayRoad,
                    NavigationFeature.distanceValue(distance),
                    NavigationFeature.isKm(distance),
                    currentMainTxProgress(maneuver, distance),
                    false,
                )
            }
        }
        AppLog.line(
            app,
            ("Navigation gray road only: " +
                grayRoad +
                " source=" +
                NavigationFeature.clean(source) +
                " stateManeuver=" +
                NavigationFeature.first(maneuver, "-")),
        )
    }

    private fun applyMainGuidanceDistanceTick(distanceText: String?, source: String?, now: Long) {
        val distance = NavigationFeature.nonZeroDistance(distanceText)
        if (distance.isNullOrEmpty()) return
        val maneuver =
            NavigationFeature.first(state.maneuver, state.routeActionId, state.mainManeuverId)
        if (
            !state.active || state.finishReached || !NavigationFeature.isUsableManeuver(maneuver!!)
        ) {
            AppLog.line(
                app,
                ("Navigation ignored main guidance distance: " +
                    NavigationFeature.clean(distance) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            return
        }
        lastRouteGuidanceAt = now
        waitingForRoute = false
        routeStartedAt = 0L
        cancelPendingInactive("guidance distance " + source)
        state =
            NavigationState(
                    true,
                    false,
                    state.speedExceeded,
                    maneuver,
                    NavigationFeature.maneuverLabel(maneuver),
                    distance,
                    state.routeDistance,
                    state.routeTime,
                    state.arrivalTime,
                    state.currentStreet,
                    state.nextStreet,
                    state.finishStreet,
                    state.speedLimit,
                    state.currentSpeed,
                    source,
                    now,
                )
                .withLaneHint(activeLaneHint, activeLaneSource, now)
                .withNavigationDebug(
                    state.mainManeuverId,
                    state.routeActionId,
                    state.microManeuverId,
                    state.microDistance,
                    state.microStatus,
                    state.grayRoadId,
                    state.grayRoadScheme,
                    now,
                )
                .withClusterVisualText(state.clusterVisual, now)
                .withClusterTxText(state.clusterTx, now)
        publishNavigationState()
        val progress: Int = maneuverProgressBucket(maneuver, distance)
        sendManeuverWithFallbackGray(maneuver, distance, progress, false)
        AppLog.line(
            app,
            ("Navigation main guidance distance TX: " +
                NavigationFeature.clean(maneuver) +
                " distance=" +
                NavigationFeature.clean(distance) +
                " source=" +
                NavigationFeature.clean(source)),
        )
    }

    private fun handleEta(intent: Intent) {
        val routeDistance =
            NavigationFeature.first(
                NavigationFeature.metersDistanceText(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "remaining_meters"),
                        NavigationFeature.text(intent, "remaining_distance_meters"),
                        NavigationFeature.text(intent, "distance_left_meters"),
                    )
                ),
                NavigationFeature.text(intent, "edistance"),
                NavigationFeature.text(intent, "route_distance"),
                NavigationFeature.text(intent, "total_distance"),
                NavigationFeature.text(intent, "distance"),
                NavigationFeature.textByKeyPart(intent, "distance"),
            )
        val routeTotalDistance =
            NavigationFeature.first(
                NavigationFeature.metersDistanceText(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "route_total_meters"),
                        NavigationFeature.text(intent, "total_route_meters"),
                        NavigationFeature.text(intent, "route_total_distance_meters"),
                        NavigationFeature.text(intent, "route_total_initial_meters"),
                    )
                ),
                NavigationFeature.text(intent, "route_total_len"),
                NavigationFeature.text(intent, "route_full_len"),
                NavigationFeature.text(intent, "route_total_distance"),
                NavigationFeature.text(intent, "full_route_distance"),
                NavigationFeature.text(intent, "routeTotalDistance"),
                NavigationFeature.text(intent, "totalRouteDistance"),
            )
        val routeTime =
            NavigationFeature.first(
                NavigationFeature.text(intent, "route_time"),
                NavigationFeature.text(intent, "time_left"),
                NavigationFeature.text(intent, "etime"),
                NavigationFeature.text(intent, "duration"),
                NavigationFeature.text(intent, "remaining_time"),
                NavigationFeature.textByKeyPart(intent, "time_left"),
            )
        val arrival =
            NavigationFeature.first(
                NavigationFeature.text(intent, "arrival_time"),
                NavigationFeature.text(intent, "arrival"),
                NavigationFeature.text(intent, "atime"),
                NavigationFeature.text(intent, "eta"),
            )
        val finishStreet = NavigationFeature.finishTextFromIntent(intent)
        val currentStreet = NavigationFeature.etaCurrentStreetFromIntent(intent)
        val source =
            NavigationFeature.first(
                NavigationFeature.text(intent, "source"),
                NavigationFeature.ACTION_ETA,
            )
        var routeIdChanged: Boolean = incomingRouteIdChangesActiveRoute(intent)
        val maneuverDistance = mainManeuverDistanceFromIntent(intent, source, routeIdChanged)
        val finishPointChanged: Boolean = rememberFinishPoint(intent, source)
        routeIdChanged = routeIdChanged or syncActiveRouteId(intent, source)
        mergeEta(
            routeDistance,
            routeTime,
            arrival,
            finishStreet,
            currentStreet,
            source,
            maneuverDistance,
            finishPointChanged,
            NavigationFeature.packetClaimsActiveRoute(intent),
        )
        if (!routeTotalDistance.isNullOrEmpty()) {
            rememberRouteTotalDistance(routeTotalDistance, System.currentTimeMillis())
            publishNavigationState()
        }
    }

    private fun rememberRouteTotalDistance(routeTotalDistance: String?, now: Long) {
        var total: String? =
            NavigationFeature.normalizeRouteTotalDistance(routeTotalDistance, state.routeDistance)
        if (total.isNullOrEmpty()) return
        if (!activeRouteTotalDistance.isNullOrEmpty()) {
            val incomingMeters: Float = NavigationFeature.distanceMetersForCompare(total)
            val activeMeters: Float =
                NavigationFeature.distanceMetersForCompare(activeRouteTotalDistance)
            if (
                incomingMeters > 0f &&
                    activeMeters > 0f &&
                    incomingMeters + Math.max(25f, activeMeters * 0.03f) < activeMeters
            ) {
                total = activeRouteTotalDistance
            }
        }
        if (
            total.equals(activeRouteTotalDistance) &&
                NavigationFeature.clean(state.clusterTx).contains("route total=" + total)
        ) {
            return
        }
        activeRouteTotalDistance = total
        state =
            state.withClusterTxText(
                NavigationFeature.withRouteTotalLine(state.clusterTx, total),
                now,
            )
    }

    private fun incomingRouteIdChangesActiveRoute(intent: Intent?): Boolean {
        val routeId = NavigationFeature.routeIdFromIntent(intent)
        return !routeId.isNullOrEmpty() &&
            !activeRouteId.isNullOrEmpty() &&
            !routeId.equals(activeRouteId)
    }

    private fun syncActiveRouteId(intent: Intent?, source: String?): Boolean {
        val routeId = NavigationFeature.routeIdFromIntent(intent)
        if (routeId.isNullOrEmpty() || routeId.equals(activeRouteId)) return false
        val changed: Boolean = !activeRouteId.isNullOrEmpty()
        activeRouteId = routeId
        activeRouteTotalDistance = ""
        resetMainManeuverPreview("route_id_" + NavigationFeature.clean(source))
        if (state.active) {
            sender.resetNavigationRoute()
            clusterTx.clear()
            lastNavigationDecisionKey = ""
            lastTxMicroGuardKey = ""
        }
        if (changed) {
            clearFinalSegmentFinishDirection("route id changed " + NavigationFeature.clean(source))
            clearRouteChangeVisualHold("route id changed " + NavigationFeature.clean(source))
            startRouteReroutingHold(NavigationFeature.clean(source) + "_route_id_changed")
        }
        AppLog.line(
            app,
            ("Navigation route id changed: " +
                routeId +
                " source=" +
                NavigationFeature.clean(source)),
        )
        return changed
    }

    private fun sendEta(value: String?) {
        mergeEta(value, "", "", "", "", "manual_eta", "", false, false)
    }

    private fun mainManeuverDistanceFromIntent(intent: Intent?, source: String?): String? {
        return mainManeuverDistanceFromIntent(intent, source, false)
    }

    private fun mainManeuverDistanceFromIntent(
        intent: Intent?,
        source: String?,
        routeIdChanged: Boolean,
    ): String? {
        val distance = NavigationFeature.explicitManeuverDistanceFromIntent(intent)
        if (NavigationFeature.nonZeroDistance(distance).isNullOrEmpty()) return distance
        if (YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source))) {
            val identityRaw =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "maneuver_distance_identity"),
                    NavigationFeature.text(intent, "main_maneuver_identity"),
                )
            val identity =
                NavigationFeature.normalizeManeuver(
                    identityRaw,
                    if (intent == null) 0 else intent.getIntExtra("direction_lr", 0),
                )
            if (
                NavigationFeature.isUsableManeuver(identity!!) &&
                    NavigationFeature.isUsableManeuver(state.maneuver) &&
                    !RoundaboutMainPolicy.distanceIdentityMatches(state.maneuver, identity)
            ) {
                AppLog.navigation(
                    app,
                    ("NAV_DISTANCE_REJECT" +
                        " identity=" +
                        identity +
                        " stateMain=" +
                        NavigationFeature.clean(state.maneuver) +
                        " distance=" +
                        NavigationFeature.clean(distance) +
                        " provenance=" +
                        NavigationFeature.first(
                            NavigationFeature.text(intent, "maneuver_distance_provenance"),
                            "unknown",
                        ) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                return ""
            }
        }
        if (!NavigationFeature.laneGuidanceDistancePacket(intent, source)) return distance
        val laneDistance = NavigationFeature.explicitLaneDistanceFromIntent(intent)
        if (
            !NavigationFeature.nonZeroDistance(laneDistance).isNullOrEmpty() &&
                NavigationFeature.sameMicroAndMainDistance(
                    NavigationFeature.distanceMeters(distance),
                    NavigationFeature.distanceMeters(laneDistance),
                )
        ) {
            if (routeIdChanged && NavigationFeature.hasMainManeuverDistanceMeters(intent)) {
                AppLog.line(
                    app,
                    ("Navigation accepted new route main distance matching lane: distance=" +
                        NavigationFeature.clean(distance) +
                        " lane=" +
                        NavigationFeature.clean(laneDistance) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                return distance
            }
            AppLog.line(
                app,
                ("Navigation ignored lane distance as main maneuver: distance=" +
                    NavigationFeature.clean(distance) +
                    " lane=" +
                    NavigationFeature.clean(laneDistance) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            return ""
        }
        if (
            YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source)) &&
                (NavigationFeature.bool(intent, "lane_guidance", false) ||
                    NavigationFeature.hasLaneData(intent))
        ) {
            if (!NavigationFeature.coreBridgeMainDistancePacket(intent, distance)) {
                AppLog.line(
                    app,
                    ("Navigation ignored guidance packet maneuver distance: distance=" +
                        NavigationFeature.clean(distance) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
                return ""
            }
            AppLog.line(
                app,
                ("Navigation accepted Core Bridge main distance with lane data: distance=" +
                    NavigationFeature.clean(distance) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
        return distance
    }

    private fun mergeEta(
        routeDistance: String?,
        routeTime: String?,
        arrivalTime: String?,
        finishStreet: String?,
        currentStreet: String?,
        source: String?,
        maneuverDistance: String?,
        finishPointChanged: Boolean,
        packetClaimsActiveRoute: Boolean,
    ) {
        val rawDistanceText = NavigationFeature.clean(routeDistance)
        val distanceText = NavigationFeature.normalizeDistanceText(rawDistanceText)
        val acceptedManeuverDistance = NavigationFeature.normalizeDistanceText(maneuverDistance)
        val timeText = NavigationFeature.clean(routeTime)
        var arrivalText: String? = NavigationFeature.clean(arrivalTime)
        val finishText = finishForState(finishStreet, finishPointChanged)
        val acceptedCurrent = etaStreetCandidate(currentStreet, state.currentStreet)
        val cleanSource = NavigationFeature.clean(source)
        if (finishSuppressesYandex(cleanSource, distanceText, timeText, finishText)) return
        if (NavigationFeature.isRouteSuggestionSource(cleanSource)) {
            AppLog.line(app, "Navigation ignored ETA suggestion: " + cleanSource)
            return
        }
        if (NavigationFeature.isCachedFinishSource(cleanSource)) {
            if (finishText.isNullOrEmpty()) {
                AppLog.line(app, "Navigation ignored cached ETA: " + cleanSource)
                return
            }
            if (!state.active) {
                state =
                    NavigationState(
                        false,
                        false,
                        state.speedExceeded,
                        state.maneuver,
                        state.maneuverText,
                        state.maneuverDistance,
                        state.routeDistance,
                        state.routeTime,
                        state.arrivalTime,
                        state.currentStreet,
                        state.nextStreet,
                        finishText,
                        state.speedLimit,
                        state.currentSpeed,
                        cleanSource,
                        System.currentTimeMillis(),
                    )
                publishNavigationState()
                AppLog.line(app, "Navigation cached finish stored: " + state.finishStreet)
                return
            }
            state =
                NavigationState(
                    true,
                    state.finishReached,
                    state.speedExceeded,
                    state.maneuver,
                    state.maneuverText,
                    state.maneuverDistance,
                    state.routeDistance,
                    state.routeTime,
                    state.arrivalTime,
                    state.currentStreet,
                    state.nextStreet,
                    finishText,
                    state.speedLimit,
                    state.currentSpeed,
                    cleanSource,
                    System.currentTimeMillis(),
                )
            publishNavigationState()
            sendConfiguredText()
            AppLog.line(app, "Navigation cached finish merged: " + state.finishStreet)
            return
        }
        val now: Long = System.currentTimeMillis()
        if (
            NavigationFeature.isWeakOverviewRouteSource(cleanSource) &&
                lastRouteFinishEtaAt > 0L &&
                now - lastRouteFinishEtaAt < 5000L &&
                !state.routeDistance.isNullOrEmpty()
        ) {
            AppLog.line(app, "Navigation ignored weak ETA: " + cleanSource)
            return
        }
        if (NavigationFeature.isRouteFinishSource(cleanSource)) lastRouteFinishEtaAt = now
        var wasActive: Boolean = state.active
        if (
            !wasActive &&
                packetClaimsActiveRoute &&
                NavigationFeature.canResumeFromEtaPacket(
                    cleanSource,
                    distanceText,
                    timeText,
                    arrivalText,
                    finishText,
                )
        ) {
            AppLog.line(app, "Navigation resumed active from ETA packet: " + cleanSource)
            setActive(true, cleanSource)
            wasActive = true
        }
        if (
            !wasActive &&
                !NavigationFeature.isRouteFinishSource(cleanSource) &&
                !NavigationFeature.isGuidanceEtaSource(cleanSource)
        ) {
            AppLog.line(app, "Navigation ignored inactive ETA: " + cleanSource)
            return
        }
        lastRouteGuidanceAt = now
        cancelPendingInactive("eta " + cleanSource)
        if (arrivalText.isNullOrEmpty()) {
            arrivalText = NavigationFeature.arrivalFromRouteTime(timeText)
        }
        if (
            distanceText.isNullOrEmpty() &&
                timeText.isNullOrEmpty() &&
                arrivalText.isNullOrEmpty() &&
                finishText.isNullOrEmpty()
        )
            return
        if (NavigationFeature.hasConfirmedRouteMetrics(distanceText, timeText, arrivalText)) {
            lastRouteMetricsAt = now
        }
        val finishDistance: Boolean =
            NavigationFeature.finishAllowedByDistance(rawDistanceText, distanceText)
        val finishConflict: Boolean =
            NavigationFeature.finishContradictedByGuidance(
                distanceText,
                acceptedManeuverDistance,
                timeText,
            )
        val finish: Boolean =
            finishDistance &&
                !finishConflict &&
                finishPositionAllowsHold(
                    cleanSource,
                    NavigationFeature.first(distanceText, rawDistanceText),
                )
        if (finishDistance && finishConflict) {
            AppLog.line(
                app,
                ("Navigation ignored ETA finish by contradictory guidance: route=" +
                    NavigationFeature.first(distanceText, rawDistanceText) +
                    " maneuver=" +
                    NavigationFeature.clean(acceptedManeuverDistance) +
                    " time=" +
                    NavigationFeature.clean(timeText) +
                    " source=" +
                    cleanSource),
            )
        }
        val carryFinish: String? =
            if (finishPointChanged) "" else NavigationFeature.validFinishStreet(state.finishStreet)
        val routeChanged: Boolean =
            wasActive &&
                NavigationFeature.isRouteFinishSource(cleanSource) &&
                routeLikelyChanged(distanceText, timeText)
        if (routeChanged) {
            clearRouteChangeVisualHold("route metrics changed " + cleanSource)
            clearFinalSegmentFinishDirection("route metrics changed " + cleanSource)
            waitingForRoute = true
            routeStartedAt = now
            routeLoadingMinUntil = now + NavigationFeature.ROUTE_WAIT_MIN_MS
            lastDirectManeuverAt = 0L
            resetNavigationTextCache()
            startRouteReroutingHold(cleanSource + "_route_metrics_changed", true)
            scheduleRouteLoadingFallback()
            AppLog.line(app, "Navigation route changed: " + cleanSource)
        }
        if (
            finishText.isNullOrEmpty() &&
                NavigationFeature.isRouteFinishSource(cleanSource) &&
                routeChanged &&
                !carryFinish.isNullOrEmpty()
        ) {
            AppLog.line(app, "Navigation finish kept for new route without text: " + cleanSource)
        }
        val previousManeuver: String? = state.maneuver
        val previousManeuverDistance: String? = state.maneuverDistance
        state =
            NavigationState(
                true,
                finish,
                state.speedExceeded,
                if (routeChanged) "" else state.maneuver,
                if (routeChanged) "" else state.maneuverText,
                if (routeChanged) ""
                else NavigationFeature.first(acceptedManeuverDistance, state.maneuverDistance),
                NavigationFeature.first(distanceText, state.routeDistance),
                NavigationFeature.first(timeText, state.routeTime),
                NavigationFeature.first(arrivalText, state.arrivalTime),
                NavigationFeature.first(acceptedCurrent, state.currentStreet),
                state.nextStreet,
                NavigationFeature.first(finishText, carryFinish),
                state.speedLimit,
                state.currentSpeed,
                cleanSource,
                now,
            )
        if (finish) {
            publishNavigationState()
            markFinishReached("auto_finish_eta_" + cleanSource)
            AppLog.line(
                app,
                "Navigation finish by ETA distance: " +
                    NavigationFeature.first(distanceText, rawDistanceText),
            )
            return
        }
        maybeStartFinalSegmentFinishDirection(
            state.routeDistance,
            previousManeuver!!,
            previousManeuverDistance,
            state.maneuver,
            state.maneuverDistance,
            cleanSource,
            now,
        )
        if (
            !routeChanged &&
                !acceptedManeuverDistance.isNullOrEmpty() &&
                !acceptedManeuverDistance.equals(previousManeuverDistance) &&
                !state.maneuver.isNullOrEmpty() &&
                !finishDirectionShouldOverride()
        ) {
            sendManeuverWithFallbackGray(
                state.maneuver,
                acceptedManeuverDistance,
                maneuverProgressBucket(state.maneuver, acceptedManeuverDistance),
                false,
            )
        }
        if (
            !routeChanged &&
                NavigationFeature.staleManeuverBeyondRoute(
                    state.routeDistance,
                    state.maneuverDistance,
                )
        ) {
            clearStaleManeuverVisual("maneuver beyond route eta " + cleanSource)
        } else {
            publishNavigationState()
        }
        finishRouteReroutingIfReady(System.currentTimeMillis(), cleanSource + "_eta_ready")
        if (!wasActive) sender.sendActive(true)
        sendEtaTimeIfChanged()
        if (finishDirectionShouldOverride()) {
            if (!sendDirectionToFinishIfNeeded(false)) sendFinishDirectionPlaceholder(false)
        }
        if (!state.loading() && !state.maneuver.isNullOrEmpty() && clusterVisualIsLoading()) {
            resendCurrentVisual()
        }
        if (!routeChanged) {
            refreshFallbackGrayRoadVisualIfNeeded(now)
        }

        val value = NavigationFeature.first(distanceText, state.routeDistance)
        if (value.isNullOrEmpty()) {
            if (finish) startFinishHold() else sendConfiguredText()
            return
        }
        val km: Boolean =
            value.lowercase(Locale.US).contains("км") || value.lowercase(Locale.US).contains("km")
        val distance: Float = NavigationFeature.distanceValue(value)
        if (distance <= 0f) {
            if (finish) startFinishHold() else sendConfiguredText()
            return
        }
        sendEtaIfChanged(distance, km)
        if (finish) startFinishHold() else sendConfiguredText()
        AppLog.line(app, "Navigation eta: " + state.summary())
    }

    private fun handleSpeed(intent: Intent) {
        var limit: String? =
            NavigationFeature.first(
                NavigationFeature.text(intent, "speed_limit"),
                NavigationFeature.text(intent, "road_speed_limit"),
                NavigationFeature.text(intent, "limit"),
                NavigationFeature.text(intent, "speedLimit"),
            )
        var cameraLimit: String? =
            NavigationFeature.first(
                NavigationFeature.text(intent, "camera_speed_limit"),
                NavigationFeature.text(intent, "first_camera_speed_limit_kmh"),
            )
        val speedSign = NavigationFeature.text(intent, "speed_sign")
        var current: String? =
            NavigationFeature.first(
                NavigationFeature.text(intent, "current_speed"),
                NavigationFeature.text(intent, "speed"),
                NavigationFeature.text(intent, "speed_value"),
            )
        val source =
            NavigationFeature.first(
                NavigationFeature.text(intent, "source"),
                NavigationFeature.ACTION_SPEED,
            )
        val now: Long = System.currentTimeMillis()
        val nowElapsed: Long = SystemClock.elapsedRealtime()
        val yandexSource: Boolean = NavigationFeature.isYandexSource(source)
        val sourceFreshnessEnforced: Boolean =
            NavigationFeature.bool(intent, "bridge_source_freshness_enforced", false)
        val currentSampleFresh: Boolean =
            !sourceFreshnessEnforced ||
                NavigationFeature.bool(intent, "yandex_current_speed_sample_fresh", false)
        val roadLimitSampleFresh: Boolean =
            !sourceFreshnessEnforced ||
                NavigationFeature.bool(intent, "yandex_road_limit_sample_fresh", false)
        val cameraSampleFresh: Boolean =
            !sourceFreshnessEnforced ||
                NavigationFeature.bool(intent, "yandex_camera_sample_fresh", false)
        if (!currentSampleFresh) current = ""
        if (!roadLimitSampleFresh) limit = ""
        if (!cameraSampleFresh) cameraLimit = ""
        var roadLimitPresent: Boolean =
            NavigationFeature.bool(intent, "road_speed_limit_present", !limit.isNullOrEmpty())
        if (!roadLimitSampleFresh) roadLimitPresent = false
        val positiveRoadLimit: Boolean =
            yandexSource && roadLimitPresent && NavigationFeature.speedNumber(limit) > 0
        val roadLimitClearSignal: Boolean =
            yandexSource &&
                roadLimitPresent &&
                (NavigationFeature.bool(intent, "speed_limit_clear", false) ||
                    NavigationFeature.speedNumber(limit) <= 0)
        // A fresh missing/zero sample revokes an unsent sign immediately, before UI debounce.
        // Cached transport heartbeats have roadLimitSampleFresh=false and do not renew the lease.
        if (
            roadLimitSampleFresh &&
                (roadLimitClearSignal || NavigationFeature.speedNumber(limit) <= 0)
        ) {
            cancelRoadSpeedLimitDelivery()
        }
        if (roadLimitPresent) {
            dashboardRoadLimit =
                dashboardRoadLimit.observe(
                    NavigationFeature.navigationSourceFamily(source),
                    if (roadLimitClearSignal) "" else limit,
                    nowElapsed,
                )
        }
        var confirmedRoadLimitClear: Boolean = false
        if (!current.isNullOrEmpty() && yandexSource && currentSampleFresh) {
            lastYandexCurrentSpeedAt = nowElapsed
            scheduleYandexCurrentSpeedExpiry()
        }
        if (positiveRoadLimit) {
            lastYandexRoadSpeedLimitAt = nowElapsed
            yandexSpeedLimitClearCandidateAt = 0L
            scheduleYandexRoadSpeedLimitExpiry()
        } else if (roadLimitClearSignal) {
            if (yandexSpeedLimitClearCandidateAt <= 0L) {
                yandexSpeedLimitClearCandidateAt = nowElapsed
                scheduleYandexZeroSpeedLimitConfirmation()
            }
            val hadRoadLimit: Boolean = !state.speedLimit.isNullOrEmpty() || lastSentSpeedLimit > 0
            confirmedRoadLimitClear =
                hadRoadLimit &&
                    (lastYandexRoadSpeedLimitAt <= 0L ||
                        (nowElapsed - lastYandexRoadSpeedLimitAt >
                            NavigationFeature.YANDEX_ROAD_SPEED_LIMIT_HOLD_MS) ||
                        (nowElapsed - yandexSpeedLimitClearCandidateAt >=
                            NavigationFeature.YANDEX_ZERO_SPEED_LIMIT_CONFIRM_MS))
            if (confirmedRoadLimitClear) {
                lastYandexRoadSpeedLimitAt = 0L
                yandexSpeedLimitClearCandidateAt = 0L
                speedLimitExpiryGeneration++
            }
        } else if (yandexSource) {
            expireStaleYandexRoadSpeedLimit(now, "snapshot_without_limit")
        }
        val nextStreet =
            NavigationFeature.first(
                NavigationFeature.text(intent, "next_street"),
                NavigationFeature.text(intent, "nextStreet"),
                NavigationFeature.text(intent, "next_road"),
                NavigationFeature.text(intent, "nextRoad"),
                NavigationFeature.text(intent, "next_road_name"),
                NavigationFeature.text(intent, "nextRoadName"),
                NavigationFeature.text(intent, "next_street_name"),
                NavigationFeature.text(intent, "nextStreetName"),
                NavigationFeature.text(intent, "next_turn_street"),
                NavigationFeature.text(intent, "nextTurnStreet"),
            )
        val currentStreet =
            NavigationFeature.first(
                NavigationFeature.text(intent, "current_street"),
                NavigationFeature.text(intent, "currentStreet"),
                NavigationFeature.text(intent, "current_road"),
                NavigationFeature.text(intent, "currentRoad"),
                NavigationFeature.text(intent, "current_road_name"),
                NavigationFeature.text(intent, "currentRoadName"),
                NavigationFeature.text(intent, "current_street_name"),
                NavigationFeature.text(intent, "currentStreetName"),
                NavigationFeature.text(intent, "position"),
            )
        // Speed packets are partial updates. They may carry cached/geocoded finish text,
        // but the real destination label belongs to route/guidance packets.
        rememberFinishPoint(intent, source, false)
        var exceeded: Boolean =
            if (currentSampleFresh) NavigationFeature.bool(intent, "exceeded", state.speedExceeded)
            else state.speedExceeded
        if (!speedSign.isNullOrEmpty() && speedSign.lowercase(Locale.US).contains("alarm")) {
            exceeded = true
        }
        val cameraOnlyLimit: Boolean =
            yandexSource && limit.isNullOrEmpty() && !cameraLimit.isNullOrEmpty()
        val acceptedRoadLimit: String? = if (roadLimitClearSignal) "" else limit
        var nextSpeedLimit: String? =
            if (confirmedRoadLimitClear) ""
            else NavigationFeature.first(acceptedRoadLimit, state.speedLimit)
        if (cameraOnlyLimit && yandexRoadSpeedLimitStale(nowElapsed)) {
            nextSpeedLimit = ""
            AppLog.line(
                app,
                ("Navigation kept camera limit out of road speed limit: camera=" +
                    NavigationFeature.clean(cameraLimit) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
        val effectiveCurrent = NavigationFeature.first(current, state.currentSpeed)
        val effectiveLimit: String? = nextSpeedLimit
        if (!effectiveCurrent.isNullOrEmpty() && !effectiveLimit.isNullOrEmpty()) {
            exceeded =
                NavigationFeature.speedNumber(effectiveCurrent) >
                    NavigationFeature.speedNumber(effectiveLimit)
        } else if (effectiveLimit.isNullOrEmpty()) {
            exceeded = false
        }
        if (exceeded) startOverspeedTextWindow() else clearOverspeedTextWindow()
        val acceptedCurrent: String? =
            if (NavigationFeature.isTrustedCurrentStreetSpeedSource(source))
                etaStreetCandidate(currentStreet, state.currentStreet)
            else ""
        val acceptedNext: String = ""
        val acceptedFinish: String? =
            if (state.active) NavigationFeature.validFinishStreet(state.finishStreet) else ""
        state =
            NavigationState(
                state.active,
                state.finishReached,
                exceeded,
                state.maneuver,
                state.maneuverText,
                state.maneuverDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                NavigationFeature.first(acceptedCurrent, state.currentStreet),
                state.nextStreet,
                acceptedFinish,
                nextSpeedLimit,
                NavigationFeature.first(current, state.currentSpeed),
                stableSourceForSpeed(source),
                now,
            )
        if (!acceptedCurrent.isNullOrEmpty()) {
            AppLog.line(
                app,
                "Navigation speed current street: " + acceptedCurrent + " source=" + source,
            )
        }
        if (
            NavigationFeature.staleManeuverBeyondRoute(state.routeDistance, state.maneuverDistance)
        ) {
            clearStaleManeuverVisual("maneuver beyond route speed " + source)
        } else {
            publishNavigationState()
        }
        if (confirmedRoadLimitClear) {
            forgetRoadSpeedLimit("authoritative_zero")
        } else {
            sendSpeed(acceptedRoadLimit)
        }
    }

    /**
     * Refreshes volatile Yandex freshness without rewriting StateStore or rebroadcasting an
     * identical dashboard state. Value changes deliberately fail this check and take the normal
     * [.handle] path immediately.
     */
    @kotlin.jvm.Synchronized
    fun refreshMatchingYandexSpeedContext(
        currentSpeedKmh: Int,
        currentSpeedFresh: Boolean,
        roadLimitPresent: Boolean,
        roadLimitKmh: Int,
        exceededPresent: Boolean,
        exceeded: Boolean,
    ): Boolean {
        if (
            !YandexSpeedSnapshotPolicy.matchesStoredState(
                NavigationFeature.isYandexSource(state.source),
                NavigationFeature.speedNumber(state.currentSpeed),
                currentSpeedKmh,
                NavigationFeature.speedNumber(state.speedLimit),
                roadLimitPresent,
                roadLimitKmh,
                state.speedExceeded,
                exceededPresent,
                exceeded,
            )
        ) {
            return false
        }
        val nowElapsed: Long = SystemClock.elapsedRealtime()
        if (currentSpeedFresh && currentSpeedKmh >= 0) {
            lastYandexCurrentSpeedAt = nowElapsed
            scheduleYandexCurrentSpeedExpiry()
        }
        if (
            roadLimitPresent &&
                NavigationFeature.navigationSourceFamily(state.source) ==
                    NavigationSourcePolicy.SOURCE_YANDEX
        ) {
            dashboardRoadLimit =
                dashboardRoadLimit.observe(
                    NavigationSourcePolicy.SOURCE_YANDEX,
                    roadLimitKmh,
                    nowElapsed,
                )
        }
        if (roadLimitPresent && roadLimitKmh > 0) {
            lastYandexRoadSpeedLimitAt = nowElapsed
            yandexSpeedLimitClearCandidateAt = 0L
            scheduleYandexRoadSpeedLimitExpiry()
        }
        expireStaleYandexCurrentSpeed("snapshot_refresh")
        expireStaleYandexRoadSpeedLimit(System.currentTimeMillis(), "snapshot_refresh")
        if (roadLimitPresent && roadLimitKmh > 0) {
            sendSpeedLimitFrame(roadLimitKmh)
        }
        return true
    }

    /** An empty heartbeat only expires local data; it never sends a sign. */
    @kotlin.jvm.Synchronized
    fun observeYandexTransportHeartbeat() {
        expireStaleYandexCurrentSpeed("empty_heartbeat")
        expireStaleYandexRoadSpeedLimit(System.currentTimeMillis(), "empty_heartbeat")
    }

    /** A new source sample explicitly lacking a road limit revokes only pending delivery. */
    @kotlin.jvm.Synchronized
    fun observeYandexRoadSpeedLimitMissing() {
        if (dashboardRoadLimit.source == NavigationSourcePolicy.SOURCE_YANDEX) {
            dashboardRoadLimit = DashboardRoadSpeedLimit.NONE
        }
        if (
            roadSpeedLimitRepeat.currentValue() > 0 &&
                knownRoadSpeedLimitSource == NavigationSourcePolicy.SOURCE_YANDEX
        ) {
            cancelRoadSpeedLimitDelivery()
        }
    }

    @kotlin.jvm.Synchronized
    fun freshMotionState(movingThresholdKmh: Int): NavigationMotionPolicy.State {
        val nowElapsed: Long = SystemClock.elapsedRealtime()
        val primarySpeed: Int =
            if (state.currentSpeed.isNullOrEmpty()) -1
            else NavigationFeature.speedNumber(state.currentSpeed)
        val primaryObservedAt: Long =
            if (NavigationFeature.isYandexSource(state.source)) lastYandexCurrentSpeedAt else 0L
        return NavigationMotionPolicy.evaluate(
            primarySpeed,
            primaryObservedAt,
            lastGpsCurrentSpeed,
            lastGpsCurrentSpeedAt,
            nowElapsed,
            NavigationFeature.YANDEX_CURRENT_SPEED_HOLD_MS,
            NavigationFeature.GPS_CURRENT_SPEED_FALLBACK_MAX_AGE_MS,
            Math.max(0, movingThresholdKmh),
        )
    }

    private fun yandexCurrentSpeedFresh(nowElapsed: Long): Boolean {
        return YandexCurrentSpeedFreshnessPolicy.isFresh(
            lastYandexCurrentSpeedAt,
            nowElapsed,
            NavigationFeature.YANDEX_CURRENT_SPEED_HOLD_MS,
        )
    }

    private fun yandexRoadSpeedLimitStale(ignoredNow: Long): Boolean {
        val nowElapsed: Long = SystemClock.elapsedRealtime()
        return YandexSpeedLimitFreshnessPolicy.shouldClear(
            state.speedLimit,
            lastYandexRoadSpeedLimitAt,
            nowElapsed,
            NavigationFeature.YANDEX_ROAD_SPEED_LIMIT_HOLD_MS,
            freshGpsSaysStationary(nowElapsed),
        )
    }

    private fun scheduleYandexCurrentSpeedExpiry() {
        scheduleCurrentSpeedExpiry(NavigationFeature.YANDEX_CURRENT_SPEED_HOLD_MS + 50L)
    }

    private fun scheduleGpsCurrentSpeedExpiry(nowElapsed: Long) {
        val gpsAge: Long =
            if (lastGpsCurrentSpeedAt <= 0L || nowElapsed < lastGpsCurrentSpeedAt)
                NavigationFeature.GPS_CURRENT_SPEED_FALLBACK_MAX_AGE_MS
            else nowElapsed - lastGpsCurrentSpeedAt
        val remaining: Long =
            Math.max(50L, NavigationFeature.GPS_CURRENT_SPEED_FALLBACK_MAX_AGE_MS - gpsAge + 50L)
        scheduleCurrentSpeedExpiry(remaining)
    }

    private fun scheduleCurrentSpeedExpiry(delayMs: Long) {
        val generation: Int = ++currentSpeedExpiryGeneration
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (generation != currentSpeedExpiryGeneration) return@postDelayed
                    expireStaleYandexCurrentSpeed("ttl")
                }
            },
            Math.max(50L, delayMs),
        )
    }

    private fun expireStaleYandexCurrentSpeed(reason: String?): Boolean {
        if (
            NavigationFeature.navigationSourceFamily(state.source) ==
                NavigationSourcePolicy.SOURCE_DGIS
        ) {
            currentSpeedExpiryGeneration++
            lastYandexCurrentSpeedAt = 0L
            return false
        }
        val nowElapsed: Long = SystemClock.elapsedRealtime()
        val action: YandexCurrentSpeedFreshnessPolicy.Action? =
            YandexCurrentSpeedFreshnessPolicy.expiryAction(
                lastYandexCurrentSpeedAt,
                lastGpsCurrentSpeedAt,
                nowElapsed,
                NavigationFeature.YANDEX_CURRENT_SPEED_HOLD_MS,
                NavigationFeature.GPS_CURRENT_SPEED_FALLBACK_MAX_AGE_MS,
            )
        if (
            action == kia.app.navigation.domain.YandexCurrentSpeedFreshnessPolicy.Action.KEEP_YANDEX
        )
            return false

        lastYandexCurrentSpeedAt = 0L
        if (action == kia.app.navigation.domain.YandexCurrentSpeedFreshnessPolicy.Action.USE_GPS) {
            scheduleGpsCurrentSpeedExpiry(nowElapsed)
        } else {
            currentSpeedExpiryGeneration++
        }
        val nextCurrent: String? =
            if (
                action ==
                    kia.app.navigation.domain.YandexCurrentSpeedFreshnessPolicy.Action.USE_GPS &&
                    lastGpsCurrentSpeed >= 0
            )
                java.lang.String.valueOf(lastGpsCurrentSpeed)
            else ""
        val limit: Int = NavigationFeature.speedNumber(state.speedLimit)
        val exceeded: Boolean =
            !nextCurrent.isNullOrEmpty() &&
                limit > 0 &&
                NavigationFeature.speedNumber(nextCurrent) > limit
        if (nextCurrent.equals(state.currentSpeed) && exceeded == state.speedExceeded) {
            return false
        }
        if (exceeded) startOverspeedTextWindow() else clearOverspeedTextWindow()
        val exceededChanged: Boolean = exceeded != state.speedExceeded
        val expired: String? = state.currentSpeed
        state =
            NavigationState(
                state.active,
                state.finishReached,
                exceeded,
                state.maneuver,
                state.maneuverText,
                state.maneuverDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                state.finishStreet,
                state.speedLimit,
                nextCurrent,
                if (
                    action ==
                        kia.app.navigation.domain.YandexCurrentSpeedFreshnessPolicy.Action.USE_GPS
                )
                    stableSourceForSpeed("gps_speed")
                else state.source,
                System.currentTimeMillis(),
            )
        publishNavigationState()
        if (exceededChanged) sendConfiguredText()
        AppLog.navigation(
            app,
            ("NAV_CURRENT_SPEED_EXPIRED" +
                " value=" +
                NavigationFeature.clean(expired) +
                " fallback=" +
                NavigationFeature.clean(nextCurrent) +
                " reason=" +
                NavigationFeature.clean(reason)),
        )
        return true
    }

    private fun scheduleYandexRoadSpeedLimitExpiry() {
        val generation: Int = ++speedLimitExpiryGeneration
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (generation != speedLimitExpiryGeneration) return@postDelayed
                    expireStaleYandexRoadSpeedLimit(System.currentTimeMillis(), "ttl")
                }
            },
            NavigationFeature.YANDEX_ROAD_SPEED_LIMIT_HOLD_MS + 50L,
        )
    }

    private fun scheduleYandexZeroSpeedLimitConfirmation() {
        val generation: Int = ++speedLimitExpiryGeneration
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (
                        generation != speedLimitExpiryGeneration ||
                            yandexSpeedLimitClearCandidateAt <= 0L ||
                            !NavigationFeature.isYandexSource(state.source)
                    ) {
                        return@postDelayed
                    }
                    val hadRoadLimit: Boolean =
                        !state.speedLimit.isNullOrEmpty() || lastSentSpeedLimit > 0
                    if (!hadRoadLimit) {
                        yandexSpeedLimitClearCandidateAt = 0L
                        return@postDelayed
                    }
                    val now: Long = System.currentTimeMillis()
                    lastYandexRoadSpeedLimitAt = 0L
                    yandexSpeedLimitClearCandidateAt = 0L
                    speedLimitExpiryGeneration++
                    clearOverspeedTextWindow()
                    state =
                        NavigationState(
                            state.active,
                            state.finishReached,
                            false,
                            state.maneuver,
                            state.maneuverText,
                            state.maneuverDistance,
                            state.routeDistance,
                            state.routeTime,
                            state.arrivalTime,
                            state.currentStreet,
                            state.nextStreet,
                            state.finishStreet,
                            "",
                            state.currentSpeed,
                            state.source,
                            now,
                        )
                    publishNavigationState()
                    forgetRoadSpeedLimit("authoritative_zero_debounced")
                }
            },
            NavigationFeature.YANDEX_ZERO_SPEED_LIMIT_CONFIRM_MS + 25L,
        )
    }

    private fun expireStaleYandexRoadSpeedLimit(ignoredNow: Long, reason: String?): Boolean {
        if (!NavigationFeature.isYandexSource(state.source)) return false
        val nowElapsed: Long = SystemClock.elapsedRealtime()
        if (
            !YandexSpeedLimitFreshnessPolicy.shouldClear(
                state.speedLimit,
                lastYandexRoadSpeedLimitAt,
                nowElapsed,
                NavigationFeature.YANDEX_ROAD_SPEED_LIMIT_HOLD_MS,
                freshGpsSaysStationary(nowElapsed),
            )
        ) {
            return false
        }
        val expired: String? = state.speedLimit
        speedLimitExpiryGeneration++
        lastYandexRoadSpeedLimitAt = 0L
        yandexSpeedLimitClearCandidateAt = 0L
        clearOverspeedTextWindow()
        state =
            NavigationState(
                state.active,
                state.finishReached,
                false,
                state.maneuver,
                state.maneuverText,
                state.maneuverDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                state.finishStreet,
                "",
                state.currentSpeed,
                state.source,
                System.currentTimeMillis(),
            )
        publishNavigationState()
        forgetRoadSpeedLimit("stale_" + NavigationFeature.clean(reason))
        AppLog.navigation(
            app,
            ("NAV_SPEED_LIMIT_EXPIRED" +
                " value=" +
                NavigationFeature.clean(expired) +
                " reason=" +
                NavigationFeature.clean(reason)),
        )
        return true
    }

    private fun freshGpsSaysStationary(nowElapsed: Long): Boolean {
        return lastGpsCurrentSpeed == 0 &&
            YandexCurrentSpeedFreshnessPolicy.isFresh(
                lastGpsCurrentSpeedAt,
                nowElapsed,
                NavigationFeature.GPS_CURRENT_SPEED_FALLBACK_MAX_AGE_MS,
            )
    }

    private fun forgetRoadSpeedLimit(reason: String?) {
        cancelRoadSpeedLimitDelivery()
        speedLimitTextUntil = 0L
        clearOverspeedTextWindow()
        if (lastSentSpeedLimit != 0) {
            lastSentSpeedLimit = 0
            AppLog.navigation(
                app,
                "NAV_SPEED_LIMIT_UNAVAILABLE reason=" + NavigationFeature.clean(reason),
            )
        }
        sendConfiguredText()
    }

    private fun sendSpeed(value: String?) {
        val kmh: Int = NavigationFeature.speedNumber(value)
        if (kmh <= 0) return
        val now: Long = System.currentTimeMillis()
        val changed: Boolean = kmh != lastSentSpeedLimit
        state =
            NavigationState(
                state.active,
                state.finishReached,
                state.speedExceeded,
                state.maneuver,
                state.maneuverText,
                state.maneuverDistance,
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                state.finishStreet,
                java.lang.String.valueOf(kmh),
                state.currentSpeed,
                state.source,
                now,
            )
        publishNavigationState()
        if (changed) {
            lastSentSpeedLimit = kmh
            speedLimitTextUntil = now + NavigationFeature.SPEED_LIMIT_TEXT_MS
        }
        // Successful delivery owns deduplication, independently of UI/cache resets.
        if (sendSpeedLimitFrame(kmh) == AdapterTxOutcome.WRITTEN) {
            AppLog.line(app, "Navigation speed limit: " + kmh)
        }
        sendConfiguredText()
    }

    private fun maybeResolveCurrentStreet(lat: Double, lon: Double, source: String?) {
        if (!NavigationFeature.validCoordinate(lat, lon)) return
        val now: Long = System.currentTimeMillis()
        val key = String.format(Locale.US, "%.5f,%.5f", lat, lon)
        if (key.equals(currentGeocodeKey) && now - lastCurrentGeocodeAt < 15000L) return
        currentGeocodeKey = key
        lastCurrentGeocodeAt = now
        val generation: Int = ++currentGeocodeGeneration
        val cleanSource = NavigationFeature.clean(source)
        Thread(
                label@{
                    val resolved = reverseCurrentStreet(lat, lon, cleanSource)
                    if (resolved.isNullOrEmpty()) return@label
                    synchronized(this@NavigationFeature) {
                        if (generation != currentGeocodeGeneration) return@label
                        state =
                            NavigationState(
                                state.active,
                                state.finishReached,
                                state.speedExceeded,
                                state.maneuver,
                                state.maneuverText,
                                state.maneuverDistance,
                                state.routeDistance,
                                state.routeTime,
                                state.arrivalTime,
                                resolved,
                                state.nextStreet,
                                state.finishStreet,
                                state.speedLimit,
                                state.currentSpeed,
                                NavigationFeature.first(cleanSource, "current_geocoder"),
                                System.currentTimeMillis(),
                            )
                        publishNavigationState()
                        if (state.active) sendConfiguredText()
                        AppLog.line(
                            app,
                            ("Navigation current street geocoded: " + resolved + " " + key),
                        )
                    }
                },
                "KiaCurrentGeocoder",
            )
            .start()
    }

    private fun reverseCurrentStreet(lat: Double, lon: Double, source: String?): String {
        try {
            if (!Geocoder.isPresent()) return ""
            val geocoder: Geocoder = Geocoder(app, Locale.getDefault())
            val addresses: List<Address?>? = geocoder.getFromLocation(lat, lon, 1)
            if (addresses == null || addresses.isEmpty()) return ""
            val address = addresses.get(0)
            return NavigationFeature.normalizeStreetLabel(first(address!!.getThoroughfare()))
        } catch (e: Exception) {
            AppLog.line(
                app,
                ("Navigation current geocode failed: " +
                    e.javaClass.getSimpleName() +
                    " " +
                    NavigationFeature.clean(source)),
            )
            return ""
        }
    }

    private fun rememberFinishPoint(intent: Intent, source: String?): Boolean {
        return rememberFinishPoint(intent, source, true)
    }

    private fun rememberFinishPoint(
        intent: Intent,
        source: String?,
        clearFinishText: Boolean,
    ): Boolean {
        var lat: Double =
            NavigationFeature.coordinate(
                intent,
                "finish_lat",
                "finishLat",
                "destination_lat",
                "destinationLat",
                "target_lat",
                "targetLat",
                "to_lat",
                "toLat",
                "end_lat",
                "endLat",
            )
        var lon: Double =
            NavigationFeature.coordinate(
                intent,
                "finish_lon",
                "finishLon",
                "finish_lng",
                "finishLng",
                "destination_lon",
                "destinationLon",
                "destination_lng",
                "destinationLng",
                "target_lon",
                "targetLon",
                "target_lng",
                "targetLng",
                "to_lon",
                "toLon",
                "to_lng",
                "toLng",
                "end_lon",
                "endLon",
                "end_lng",
                "endLng",
            )
        if (!NavigationFeature.validCoordinate(lat, lon)) {
            val parsed: DoubleArray? =
                NavigationFeature.coordinatePair(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "finish_point"),
                        NavigationFeature.text(intent, "finishPoint"),
                        NavigationFeature.text(intent, "destination_point"),
                        NavigationFeature.text(intent, "destinationPoint"),
                        NavigationFeature.text(intent, "target_point"),
                        NavigationFeature.text(intent, "targetPoint"),
                        NavigationFeature.text(intent, "finish"),
                        NavigationFeature.text(intent, "destination"),
                        NavigationFeature.text(intent, "target"),
                    )
                )
            if (parsed != null) {
                lat = parsed.get(0)
                lon = parsed.get(1)
            }
        }
        if (!NavigationFeature.validCoordinate(lat, lon)) return false
        val incomingRouteMeters: Float =
            NavigationFeature.distanceMeters(NavigationFeature.routeDistanceFromIntent(intent))
        if (suspiciousFinishPointNearGps(lat, lon, incomingRouteMeters)) {
            AppLog.line(
                app,
                ("Navigation ignored suspicious finish point near GPS: " +
                    String.format(Locale.US, "%.6f,%.6f", lat, lon) +
                    " route=" +
                    NavigationFeature.clean(NavigationFeature.routeDistanceFromIntent(intent)) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            return false
        }
        return rememberFinishPoint(lat, lon, source, clearFinishText)
    }

    private fun rememberFinishPoint(lat: Double, lon: Double, source: String?): Boolean {
        return rememberFinishPoint(lat, lon, source, true)
    }

    private fun rememberFinishPoint(
        lat: Double,
        lon: Double,
        source: String?,
        clearFinishText: Boolean,
    ): Boolean {
        if (!NavigationFeature.validCoordinate(lat, lon)) return false
        if (suspiciousFinishPointNearGps(lat, lon)) {
            AppLog.line(
                app,
                ("Navigation ignored suspicious finish point near GPS: " +
                    String.format(Locale.US, "%.6f,%.6f", lat, lon) +
                    " route=" +
                    NavigationFeature.clean(state.routeDistance) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            return false
        }
        val changed: Boolean =
            java.lang.Double.isNaN(finishLatitude) ||
                java.lang.Double.isNaN(finishLongitude) ||
                Math.abs(finishLatitude - lat) > 0.00001 ||
                Math.abs(finishLongitude - lon) > 0.00001
        finishLatitude = lat
        finishLongitude = lon
        if (changed) {
            lastSentFinishDirectionKey = ""
            staleFinishStreetAfterPointChange =
                if (clearFinishText) NavigationFeature.validFinishStreet(state.finishStreet) else ""
            if (clearFinishText && !staleFinishStreetAfterPointChange.isNullOrEmpty()) {
                AppLog.line(
                    app,
                    ("Navigation finish street kept until replacement: " +
                        staleFinishStreetAfterPointChange +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
            }
            AppLog.line(
                app,
                ("Navigation finish point: " +
                    String.format(Locale.US, "%.6f,%.6f", lat, lon) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            if (finishDirectionShouldOverride() && state.active) {
                sendDirectionToFinishIfNeeded(true)
            }
        }
        maybeResolveFinishStreetFromPoint(source)
        return changed
    }

    private fun suspiciousFinishPointNearGps(
        lat: Double,
        lon: Double,
        routeMeters: Float = NavigationFeature.distanceMeters(state.routeDistance),
    ): Boolean {
        if (
            routeMeters <= NavigationFeature.SUSPICIOUS_FINISH_POINT_ROUTE_METERS ||
                !gpsPointKnown()
        )
            return false
        val from: Location = Location("kia_nav_current")
        from.setLatitude(lastGpsLatitude)
        from.setLongitude(lastGpsLongitude)
        val to: Location = Location("kia_nav_finish_candidate")
        to.setLatitude(lat)
        to.setLongitude(lon)
        return from.distanceTo(to) <= NavigationFeature.SUSPICIOUS_FINISH_POINT_GPS_METERS
    }

    private fun clearFinishPoint() {
        finishLatitude = Double.NaN
        finishLongitude = Double.NaN
        resetFinishDirectionAnimation()
        finishGeocodeKey = ""
        lastFinishGeocodeAt = 0L
        finishGeocodeGeneration++
    }

    private fun maybeResolveFinishStreetFromPoint(source: String?) {
        if (!state.active || state.finishReached || !finishPointKnown()) return
        if (
            !NavigationFeature.validFinishStreet(state.finishStreet).isNullOrEmpty() &&
                staleFinishStreetAfterPointChange.isNullOrEmpty()
        )
            return
        val now: Long = System.currentTimeMillis()
        val key = String.format(Locale.US, "%.5f,%.5f", finishLatitude, finishLongitude)
        if (key.equals(finishGeocodeKey) && now - lastFinishGeocodeAt < 60000L) return
        finishGeocodeKey = key
        lastFinishGeocodeAt = now
        val generation: Int = ++finishGeocodeGeneration
        val lat: Double = finishLatitude
        val lon: Double = finishLongitude
        val cleanSource = NavigationFeature.clean(source)
        Thread(
                label@{
                    val resolved = reverseFinishStreet(lat, lon)
                    if (resolved.isNullOrEmpty()) {
                        AppLog.line(
                            app,
                            ("Navigation finish geocode empty: " +
                                String.format(Locale.US, "%.6f,%.6f", lat, lon) +
                                " source=" +
                                cleanSource),
                        )
                        return@label
                    }
                    handler.post({
                        synchronized(this@NavigationFeature) {
                            if (generation != finishGeocodeGeneration) return@post
                            if (!state.active || state.finishReached || !finishPointKnown())
                                return@post
                            if (
                                Math.abs(finishLatitude - lat) > 0.00001 ||
                                    Math.abs(finishLongitude - lon) > 0.00001
                            )
                                return@post
                            if (
                                !NavigationFeature.validFinishStreet(state.finishStreet)
                                    .isNullOrEmpty() &&
                                    staleFinishStreetAfterPointChange.isNullOrEmpty()
                            )
                                return@post
                            staleFinishStreetAfterPointChange = ""
                            state =
                                NavigationState(
                                    state.active,
                                    state.finishReached,
                                    state.speedExceeded,
                                    state.maneuver,
                                    state.maneuverText,
                                    state.maneuverDistance,
                                    state.routeDistance,
                                    state.routeTime,
                                    state.arrivalTime,
                                    state.currentStreet,
                                    state.nextStreet,
                                    resolved,
                                    state.speedLimit,
                                    state.currentSpeed,
                                    NavigationFeature.first(cleanSource, "finish_geocoder"),
                                    System.currentTimeMillis(),
                                )
                            publishNavigationState()
                            sendConfiguredText()
                            AppLog.line(
                                app,
                                ("Navigation finish geocoded: " +
                                    resolved +
                                    " source=" +
                                    cleanSource),
                            )
                        }
                    })
                },
                "KiaFinishGeocoder",
            )
            .start()
    }

    private fun reverseFinishStreet(lat: Double, lon: Double): String {
        try {
            if (!Geocoder.isPresent()) return ""
            val geocoder: Geocoder = Geocoder(app, Locale.getDefault())
            val addresses: List<Address?>? = geocoder.getFromLocation(lat, lon, 1)
            if (addresses == null || addresses.isEmpty()) return ""
            return NavigationFeature.finishTextFromAddress(addresses.get(0))
        } catch (e: Exception) {
            AppLog.line(app, "Navigation finish geocode failed: " + e.javaClass.getSimpleName())
            return ""
        }
    }

    private fun publishNavigationState() {
        roadSpeedLimitTx.setAllowed(roadSpeedLimitOutputAllowed())
        if (
            !roadSpeedLimitOutputAllowed() ||
                NavigationFeature.speedNumber(state.speedLimit) <= 0 ||
                (roadSpeedLimitRepeat.currentValue() > 0 &&
                    !roadSpeedLimitContextMatches(
                        roadSpeedLimitRepeat.currentValue(),
                        knownRoadSpeedLimitSource,
                        knownRoadSpeedLimitSourceMode,
                    ))
        ) {
            cancelRoadSpeedLimitDelivery()
        }
        val now: Long = System.currentTimeMillis()
        if (!NavigationModeSettings.isFinishDirection(app)) {
            var droppedFinishDirectionVisual: Boolean = false
            if (NavigationFeature.isFinishDirectionVisual(activeClusterVisual)) {
                activeClusterVisual = ""
                droppedFinishDirectionVisual = true
            }
            if (NavigationFeature.isFinishDirectionVisual(state.clusterVisual)) {
                state = state.withClusterVisualText("", state.updatedAt)
                droppedFinishDirectionVisual = true
            }
            if (droppedFinishDirectionVisual) {
                lastSentFinishDirectionKey = ""
            }
        }
        val stored = StateStore.navigation()
        if (
            stored != null &&
                !stored.clusterTx.isNullOrEmpty() &&
                !stored.clusterTx.equals(state.clusterTx)
        ) {
            state = state.withClusterTxText(stored.clusterTx, state.updatedAt)
        }
        if (
            state.active &&
                stored != null &&
                stored.active &&
                ((state.routeDistance.isNullOrEmpty() && !stored.routeDistance.isNullOrEmpty()) ||
                    (state.routeTime.isNullOrEmpty() && !stored.routeTime.isNullOrEmpty()) ||
                    (state.arrivalTime.isNullOrEmpty() && !stored.arrivalTime.isNullOrEmpty()))
        ) {
            state =
                NavigationState(
                        state.active,
                        state.finishReached,
                        state.speedExceeded,
                        state.maneuver,
                        state.maneuverText,
                        state.maneuverDistance,
                        NavigationFeature.first(state.routeDistance, stored.routeDistance),
                        NavigationFeature.first(state.routeTime, stored.routeTime),
                        NavigationFeature.first(state.arrivalTime, stored.arrivalTime),
                        state.currentStreet,
                        state.nextStreet,
                        state.finishStreet,
                        state.speedLimit,
                        state.currentSpeed,
                        state.source,
                        state.updatedAt,
                    )
                    .withLaneHint(state.laneHint, state.laneSource, state.updatedAt)
                    .withNavigationDebug(
                        state.mainManeuverId,
                        state.routeActionId,
                        state.microManeuverId,
                        state.microDistance,
                        state.microStatus,
                        state.grayRoadId,
                        state.grayRoadScheme,
                        state.updatedAt,
                    )
                    .withClusterVisualText(state.clusterVisual, state.updatedAt)
                    .withClusterTxText(state.clusterTx, state.updatedAt)
        }
        val routeTotalForTx =
            NavigationFeature.first(
                activeRouteTotalDistance,
                NavigationFeature.latestRouteTotalDistance(state.clusterTx),
            )
        if (!routeTotalForTx.isNullOrEmpty()) {
            state =
                state.withClusterTxText(
                    NavigationFeature.withRouteTotalLine(state.clusterTx, routeTotalForTx),
                    state.updatedAt,
                )
        }
        if (
            state.active &&
                !NavigationFeature.hasNavigationDebug(state) &&
                NavigationFeature.hasNavigationDebug(stored)
        ) {
            state =
                state.withNavigationDebug(
                    stored.mainManeuverId,
                    stored.routeActionId,
                    stored.microManeuverId,
                    stored.microDistance,
                    stored.microStatus,
                    stored.grayRoadId,
                    stored.grayRoadScheme,
                    state.updatedAt,
                )
        }
        if (
            state.active &&
                state.grayRoadId.isNullOrEmpty() &&
                !activeGrayRoadId.isNullOrEmpty() &&
                grayRoadUntil > now
        ) {
            state =
                state.withNavigationDebug(
                    state.mainManeuverId,
                    state.routeActionId,
                    state.microManeuverId,
                    state.microDistance,
                    state.microStatus,
                    activeGrayRoadId,
                    NavigationFeature.grayRoadLabel(activeGrayRoadId),
                    state.updatedAt,
                )
        }
        if (
            state.active &&
                !activeMicroManeuver.isNullOrEmpty() &&
                microHintUntil > now &&
                shouldApplyActiveMicroForPublish()
        ) {
            var debugDistance: String? = activeMicroDebugDistanceToPublish()
            if (debugDistance.isNullOrEmpty()) {
                debugDistance = activeMicroDistanceToPublish()
            }
            val debugStatus = activeMicroStatusToPublish(now)
            if (!debugDistance.isNullOrEmpty() || !debugStatus.isNullOrEmpty()) {
                state =
                    state.withNavigationDebug(
                        state.mainManeuverId,
                        state.routeActionId,
                        activeMicroManeuver,
                        debugDistance,
                        debugStatus,
                        state.grayRoadId,
                        state.grayRoadScheme,
                        state.updatedAt,
                    )
            }
        }
        if (!activeLaneHint.isNullOrEmpty() && laneHintUntil > now) {
            state = state.withLaneHint(activeLaneHint, activeLaneSource, state.updatedAt)
        }
        if (!activeEventHint.isNullOrEmpty() && eventHintUntil > now) {
            state = state.withEventHint(activeEventHint, activeEventSource, state.updatedAt)
        } else if (!state.eventHint.isNullOrEmpty()) {
            state = state.withEventHint("", "", state.updatedAt)
        }
        if (navigationRawUntil > now && hasActiveNavigationRaw()) {
            state =
                state.withNavigationRaw(
                    activeLaneRaw,
                    activeLanePosition,
                    activeRoadSchemeRaw,
                    activeUpcomingRaw,
                    state.updatedAt,
                )
        } else if (
            !state.laneRaw.isNullOrEmpty() ||
                !state.lanePosition.isNullOrEmpty() ||
                !state.roadSchemeRaw.isNullOrEmpty() ||
                !state.upcomingRaw.isNullOrEmpty()
        ) {
            state = state.withNavigationRaw("", "", "", "", state.updatedAt)
        }
        if (!activeClusterVisual.isNullOrEmpty()) {
            state = state.withClusterVisualText(activeClusterVisual, state.updatedAt)
        }
        StateStore.setNavigation(app, state)
    }

    private fun scheduleMicroRestore(source: String?) {
        pendingMicroRestoreSource = NavigationFeature.clean(source)
        handler.removeCallbacks(microRestoreTask)
        val delayMs: Long = Math.max(250L, microHintUntil - System.currentTimeMillis() + 25L)
        handler.postDelayed(microRestoreTask, delayMs)
    }

    private fun microDistanceAllowed(distanceText: String?): Boolean {
        return NavigationManeuverPolicy.microDistanceAllowed(app, distanceText)
    }

    private fun microDistanceAllowed(distanceText: String?, source: String?): Boolean {
        return microDistanceAllowed(distanceText)
    }

    private fun microDistanceAllowedForTx(
        maneuver: String?,
        distanceText: String?,
        inferredForward: Boolean,
        source: String?,
    ): Boolean {
        if (
            (inferredForward || NavigationFeature.clean(source).contains("inferred_forward")) &&
                "context_ra_forward".equals(NavigationFeature.clean(maneuver))
        ) {
            val meters: Float = NavigationFeature.distanceMeters(distanceText)
            val configuredMax: Int = AppSettings.navMicroMaxDistanceMeters(app)
            val allowedMax: Float =
                Math.min(
                    NavigationFeature.INFERRED_FORWARD_MICRO_MAX_METERS,
                    configuredMax.toFloat(),
                )
            if (meters > 0f && meters <= allowedMax) return true
        }
        return microDistanceAllowed(distanceText, source)
    }

    private fun microDecisionStatus(
        microManeuver: String?,
        distanceText: String?,
        microSource: Boolean,
    ): String? {
        return microDecisionStatus(microManeuver, distanceText, microSource, "")
    }

    private fun microDecisionStatus(
        microManeuver: String?,
        distanceText: String?,
        microSource: Boolean,
        source: String?,
    ): String? {
        if (!microSource || microManeuver.isNullOrEmpty()) return "нет подсказки"
        if (!AppSettings.navMicroManeuvers(app)) return "выключена"
        if (NavigationModeSettings.isTbt(app)) return "выключена в TBT"
        val maxMeters: Int = AppSettings.navMicroMaxDistanceMeters(app)
        if (maxMeters <= 0) return "готова без порога"
        val meters: Float = NavigationFeature.distanceMeters(distanceText)
        if (meters <= 0f) return "ждёт дистанцию"
        if (meters > maxMeters) return "ждёт " + maxMeters + " м"
        return "готова"
    }

    private fun microSentStatus(distanceText: String?): String {
        if ("сейчас".equals(NavigationFeature.clean(distanceText), ignoreCase = true))
            return "отправлена, сейчас"
        val distance = NavigationFeature.nonZeroDistance(distanceText)
        return if (distance.isNullOrEmpty()) "отправлена" else "отправлена, " + distance
    }

    private fun shouldApplyActiveMicroForPublish(): Boolean {
        if (activeMicroPostPassActive(System.currentTimeMillis())) return true
        if (state.microManeuverId.isNullOrEmpty()) return true
        if (!NavigationFeature.sameManeuverFamily(activeMicroManeuver, state.maneuver)) return true
        if (activeMicroManeuver.equals(state.microManeuverId)) return true
        return state.microDistance.isNullOrEmpty()
    }

    private fun activeMicroDistanceToPublish(): String {
        val distance = NavigationFeature.clean(activeMicroDistance)
        return distance
    }

    private fun activeMicroDebugDistanceToPublish(): String {
        return NavigationFeature.clean(activeMicroDebugDistance)
    }

    private fun activeMicroStatusToPublish(now: Long): String {
        if (activeMicroPostPassUntil > now) {
            val remainingMs: Long = activeMicroPostPassUntil - now
            val seconds: Int = Math.max(1, Math.ceil(remainingMs / 1000.0).toInt())
            return "проехали, удержание " + seconds + " сек"
        }
        return NavigationFeature.clean(activeMicroStatus)
    }

    private fun activeMicroPostPassActive(now: Long): Boolean {
        return activeMicroPostPassUntil > now && !activeMicroManeuver.isNullOrEmpty()
    }

    private fun activeMicroFromTrustedLaneDirection(now: Long): Boolean {
        return !activeMicroManeuver.isNullOrEmpty() &&
            microHintUntil > now &&
            NavigationFeature.trustedLaneDirectionSource(activeMicroSource)
    }

    private fun activeMicroMatchesVisualGrayRoad(visualGrayRoad: String?): Boolean {
        val visualMask: Int = NavigationFeature.grayRoadMask(visualGrayRoad)
        val microMask: Int = NavigationFeature.maneuverRoadMask(activeMicroManeuver)
        return visualMask == 0 || microMask == 0 || (visualMask and microMask) == microMask
    }

    private fun shouldTransmitActiveMicroToCluster(): Boolean {
        if (!activeMicroTxEligibleWithoutSpatial()) return false
        val microDistance = activeMicroDistanceToPublish()
        return NavigationManeuverPolicy.decide(
                state.maneuver,
                currentMainTxDistance(),
                activeMicroManeuver,
                microDistance,
                true,
                NavigationFeature.sameManeuverFamily(activeMicroManeuver, state.maneuver),
            )
            .microWins()
    }

    private fun activeMicroTxEligibleWithoutSpatial(): Boolean {
        if (!AppSettings.navMicroManeuvers(app)) return false
        if (NavigationModeSettings.isTbt(app) || NavigationModeSettings.isFinishDirection(app))
            return false
        if (!state.active || state.finishReached) return false
        if (activeMicroManeuver.isNullOrEmpty()) return false
        if (microHintUntil <= System.currentTimeMillis()) return false
        if (!activeMicroTxAllowed) return false
        val microDistance = activeMicroDistanceToPublish()
        if (
            microDistance.isNullOrEmpty() ||
                !microDistanceAllowedForTx(
                    activeMicroManeuver,
                    microDistance,
                    NavigationFeature.clean(activeMicroSource).contains("inferred_forward"),
                    activeMicroSource,
                )
        ) {
            return false
        }
        return true
    }

    private fun resendActiveMicroToCluster(forceResend: Boolean = false): Boolean {
        if (!shouldTransmitActiveMicroToCluster()) return false
        return transmitActiveMicroToCluster(forceResend, "", "", -1)
    }

    private fun transmitActiveMicroToCluster(
        forceResend: Boolean,
        preferredMainManeuver: String,
        preferredMainDistance: String,
        preferredMainProgress: Int,
    ): Boolean {
        val laneDistance = activeMicroDistanceToPublish()
        val now: Long = System.currentTimeMillis()
        val providerMicro: Boolean = NavigationFeature.providerVisualLaneSource(activeMicroSource)
        var grayRoad: String? = activeGrayRoadForLaneTx(activeMicroManeuver, now)
        grayRoad = stableGrayRoadForLaneTx(grayRoad, activeMicroManeuver, now)
        val force: Boolean =
            forceResend ||
                NavigationFeature.clusterYellowDiffersFrom(
                    activeMicroManeuver,
                    currentClusterYellowManeuver(),
                )
        val sent: Boolean =
            sendResolvedMicroVisual(
                activeMicroManeuver!!,
                grayRoad,
                preferredMainManeuver,
                preferredMainDistance,
                preferredMainProgress,
                force,
                NavigationFeature.first(activeMicroSource, "active_micro"),
            )
        AppLog.line(
            app,
            ((if (providerMicro) "Navigation active provider micro TX: "
            else "Navigation active micro TX: ") +
                NavigationFeature.clean(activeMicroManeuver) +
                " mainDistance=" +
                NavigationFeature.clean(
                    NavigationFeature.first(preferredMainDistance, state.maneuverDistance)
                ) +
                " laneDistance=" +
                NavigationFeature.clean(laneDistance) +
                " txDistance=main" +
                " sent=" +
                sent +
                " gray=" +
                NavigationFeature.first(grayRoad, "-")),
        )
        return sent
    }

    private fun sendResolvedMicroVisual(
        microManeuver: String,
        grayRoad: String?,
        preferredMainManeuver: String,
        preferredMainDistance: String?,
        preferredMainProgress: Int,
        force: Boolean,
        source: String?,
    ): Boolean {
        val selectedMain: MicroTxDistancePolicy.MainSnapshot =
            MicroTxDistancePolicy.selectMainSnapshot(
                preferredMainManeuver,
                preferredMainDistance,
                state.maneuver,
                state.maneuverDistance,
            )
        var selectedMainDistance: String? = selectedMain.distance
        var selectedMainManeuver: String? = selectedMain.maneuver
        val cachedMain: NavigationTxKey? = clusterTx.lastMainManeuverKey()
        val expectedMainManeuver = NavigationFeature.first(preferredMainManeuver, state.maneuver)
        val selectedCachedMain: Boolean =
            !selectedMain.available() &&
                cachedMain != null &&
                cachedMain.distance > 0f &&
                NavigationFeature.isUsableManeuver(expectedMainManeuver!!) &&
                NavigationFeature.sameManeuverFamily(expectedMainManeuver, cachedMain.maneuver)
        if (selectedCachedMain) {
            selectedMainManeuver = cachedMain.maneuver
            selectedMainDistance =
                NavigationFeature.clusterDistanceText(cachedMain.distance, cachedMain.km)
        }
        if (
            !NavigationFeature.isUsableManeuver(microManeuver) ||
                !NavigationFeature.isUsableManeuver(selectedMainManeuver!!) ||
                selectedMainDistance.isNullOrEmpty()
        ) {
            val holdKey: String =
                (NavigationFeature.clean(activeRouteId) +
                    "|" +
                    NavigationFeature.clean(selectedMainManeuver) +
                    "|" +
                    NavigationFeature.clean(microManeuver))
            if (!holdKey.equals(lastMicroTxHoldKey)) {
                lastMicroTxHoldKey = holdKey
                AppLog.navigation(
                    app,
                    ("NAV_MICRO_TX_HOLD" +
                        " reason=main_distance_unknown" +
                        " route=" +
                        NavigationFeature.first(activeRouteId, "-") +
                        " main=" +
                        NavigationFeature.first(
                            NavigationFeature.clean(selectedMainManeuver),
                            "-",
                        ) +
                        " micro=" +
                        NavigationFeature.first(NavigationFeature.clean(microManeuver), "-") +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
            }
            return false
        }
        lastMicroTxHoldKey = ""
        val mainProgress: Int =
            if (selectedCachedMain) cachedMain.progress
            else if (selectedMain.preferred && preferredMainProgress >= 0)
                NavigationFeature.normalizeProgressBucket(preferredMainProgress)
            else currentMainTxProgress(selectedMainManeuver, selectedMainDistance)
        val selectedMainDistanceValue: Float =
            if (selectedCachedMain) cachedMain.distance
            else NavigationFeature.distanceValue(selectedMainDistance)
        val selectedMainKm: Boolean =
            if (selectedCachedMain) cachedMain.km else NavigationFeature.isKm(selectedMainDistance)
        val rememberedMain: NavigationTxKey? = clusterTx.lastMainManeuverKey()
        val rememberedMainGray: String? =
            if (
                rememberedMain != null &&
                    NavigationFeature.sameManeuverFamily(
                        selectedMainManeuver,
                        rememberedMain.maneuver,
                    )
            )
                rememberedMain.grayRoad
            else ""
        clusterTx.rememberMainManeuver(
            selectedMainManeuver,
            rememberedMainGray,
            selectedMainDistanceValue,
            selectedMainKm,
            mainProgress,
        )
        val compatibleGrayRoad: String? =
            if (NavigationFeature.canMergeGrayRoad(microManeuver)) NavigationFeature.clean(grayRoad)
            else ""
        if (!compatibleGrayRoad.isNullOrEmpty()) {
            sendManeuverWithGrayRoadIfChanged(
                microManeuver,
                compatibleGrayRoad,
                selectedMainDistanceValue,
                selectedMainKm,
                mainProgress,
                force,
                true,
            )
        } else {
            sendManeuverIfChanged(
                microManeuver,
                selectedMainDistanceValue,
                selectedMainKm,
                mainProgress,
                force,
                true,
            )
        }
        val txLogKey: String =
            (NavigationFeature.clean(activeRouteId) +
                "|" +
                NavigationFeature.clean(selectedMainManeuver) +
                "@" +
                NavigationFeature.clean(selectedMainDistance) +
                "|" +
                NavigationFeature.clean(microManeuver) +
                "|" +
                compatibleGrayRoad +
                "|" +
                mainProgress)
        if (!txLogKey.equals(lastMicroTxLogKey)) {
            lastMicroTxLogKey = txLogKey
            AppLog.navigation(
                app,
                ("NAV_MICRO_TX" +
                    " route=" +
                    NavigationFeature.first(activeRouteId, "-") +
                    " main=" +
                    NavigationFeature.clean(selectedMainManeuver) +
                    " mainDistance=" +
                    NavigationFeature.clean(selectedMainDistance) +
                    " micro=" +
                    NavigationFeature.clean(microManeuver) +
                    " microDistance=" +
                    NavigationFeature.first(
                        NavigationFeature.clean(activeMicroDistanceToPublish()),
                        "-",
                    ) +
                    " progress=" +
                    mainProgress +
                    " gray=" +
                    NavigationFeature.first(compatibleGrayRoad, "-") +
                    " cachedMain=" +
                    selectedCachedMain +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
        return true
    }

    private fun maneuverTxBlockedByEarlierActiveMicro(
        candidateManeuver: String?,
        candidateDistance: Float,
        candidateKm: Boolean,
        candidateProgress: Int,
    ): Boolean {
        val candidate = NavigationFeature.clean(candidateManeuver)
        if (NavigationFeature.isFinishManeuver(candidate)) return false
        if (!activeMicroTxEligibleWithoutSpatial()) return false
        val mainDistance: String =
            NavigationFeature.clusterDistanceText(candidateDistance, candidateKm)
        val microDistance = activeMicroDistanceToPublish()
        val decision: ManeuverArbiter.Decision? =
            NavigationManeuverPolicy.decide(
                candidate,
                mainDistance,
                activeMicroManeuver,
                microDistance,
                true,
                NavigationFeature.sameManeuverFamily(candidate, activeMicroManeuver),
            )
        if (!decision!!.microWins()) return false
        val guardKey: String =
            (NavigationFeature.clean(activeRouteId) +
                "|" +
                candidate +
                "@" +
                mainDistance +
                "|" +
                NavigationFeature.clean(activeMicroManeuver) +
                "@" +
                NavigationFeature.clean(microDistance))
        if (!guardKey.equals(lastTxMicroGuardKey)) {
            lastTxMicroGuardKey = guardKey
            AppLog.navigation(
                app,
                ("NAV_TX_GUARD selected=MICRO" +
                    " route=" +
                    NavigationFeature.first(activeRouteId, "-") +
                    " main=" +
                    NavigationFeature.first(candidate, "-") +
                    " mainDistance=" +
                    NavigationFeature.first(mainDistance, "-") +
                    " micro=" +
                    NavigationFeature.clean(activeMicroManeuver) +
                    " microDistance=" +
                    NavigationFeature.clean(microDistance) +
                    " reason=" +
                    decision.reason),
            )
        }
        transmitActiveMicroToCluster(false, candidate, mainDistance, candidateProgress)
        return true
    }

    private fun handleLaneDistancePassed(
        distanceText: String?,
        now: Long,
        source: String?,
    ): Boolean {
        if (!NavigationFeature.laneDistancePassed(distanceText)) return false
        clearLaneDistanceHold()
        if (!activeMicroManeuver.isNullOrEmpty()) {
            AppLog.line(
                app,
                ("Navigation lane micro passed; selecting next route event: micro=" +
                    NavigationFeature.clean(activeMicroManeuver) +
                    " main=" +
                    NavigationFeature.clean(state.maneuver) +
                    " mainDistance=" +
                    NavigationFeature.clean(currentMainTxDistance()) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
        clearMicroHintHold()
        return true
    }

    private fun restoreMainVisualAfterMicroEnd(reason: String?, force: Boolean): Boolean {
        val mainManeuver = NavigationFeature.clean(state.maneuver)
        val mainDistance = NavigationFeature.nonZeroDistance(state.maneuverDistance)
        val cachedMain: NavigationTxKey? = clusterTx.lastMainManeuverKey()
        val cachedMainUsable: Boolean =
            cachedMain != null &&
                cachedMain.distance > 0f &&
                NavigationFeature.isUsableManeuver(mainManeuver) &&
                NavigationFeature.sameManeuverFamily(mainManeuver, cachedMain.maneuver)
        if (
            !state.active ||
                state.finishReached ||
                routeWaiting() ||
                state.loading() ||
                routeReroutingActive(System.currentTimeMillis()) ||
                !NavigationFeature.isUsableManeuver(mainManeuver) ||
                (mainDistance.isNullOrEmpty() && !cachedMainUsable)
        ) {
            val holdReason: String =
                if (!state.active) "inactive"
                else if (state.finishReached) "finish"
                else if (routeReroutingActive(System.currentTimeMillis())) "rerouting"
                else if (routeWaiting() || state.loading()) "route_not_ready"
                else if (!NavigationFeature.isUsableManeuver(mainManeuver)) "main_unknown"
                else "main_distance_unknown"
            AppLog.navigation(
                app,
                ("NAV_MICRO_RESTORE_HOLD" +
                    " reason=" +
                    holdReason +
                    " route=" +
                    NavigationFeature.first(activeRouteId, "-") +
                    " main=" +
                    NavigationFeature.first(mainManeuver, "-") +
                    " source=" +
                    NavigationFeature.clean(reason)),
            )
            return false
        }
        if (mainDistance.isNullOrEmpty()) {
            if (cachedMain!!.grayRoad.isNullOrEmpty()) {
                sendManeuverIfChanged(
                    cachedMain.maneuver,
                    cachedMain.distance,
                    cachedMain.km,
                    cachedMain.progress,
                    force,
                )
            } else {
                sendManeuverWithGrayRoadIfChanged(
                    cachedMain.maneuver,
                    cachedMain.grayRoad,
                    cachedMain.distance,
                    cachedMain.km,
                    cachedMain.progress,
                    force,
                )
            }
            AppLog.navigation(
                app,
                ("NAV_MICRO_RESTORE" +
                    " route=" +
                    NavigationFeature.first(activeRouteId, "-") +
                    " main=" +
                    cachedMain.maneuver +
                    " mainDistance=" +
                    NavigationFeature.clusterDistanceText(cachedMain.distance, cachedMain.km) +
                    " progress=" +
                    cachedMain.progress +
                    " source=" +
                    NavigationFeature.clean(reason) +
                    " fallback=cached_positive_main"),
            )
            return true
        }
        val progress: Int = currentMainTxProgress(mainManeuver, mainDistance)
        sendManeuverWithFallbackGray(mainManeuver, mainDistance, progress, force)
        AppLog.navigation(
            app,
            ("NAV_MICRO_RESTORE" +
                " route=" +
                NavigationFeature.first(activeRouteId, "-") +
                " main=" +
                mainManeuver +
                " mainDistance=" +
                mainDistance +
                " progress=" +
                progress +
                " source=" +
                NavigationFeature.clean(reason)),
        )
        return true
    }

    private fun sendRoundaboutMainRefinementBeforeLaneReturn(
        refined: Boolean,
        mainDistance: String?,
        reason: String?,
        source: String?,
    ) {
        if (!refined) return
        val maneuver = NavigationFeature.clean(state.maneuver)
        val distance =
            NavigationFeature.first(
                NavigationFeature.nonZeroDistance(mainDistance),
                NavigationFeature.nonZeroDistance(state.maneuverDistance),
            )
        if (!RoundaboutMainPolicy.isNumberedExit(maneuver) || distance.isNullOrEmpty()) {
            AppLog.navigation(
                app,
                ("NAV_ROUNDABOUT_MAIN_REFINED_HOLD" +
                    " reason=" +
                    NavigationFeature.clean(reason) +
                    " main=" +
                    NavigationFeature.first(maneuver, "-") +
                    " distance=" +
                    NavigationFeature.first(distance, "-") +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            return
        }
        val progress: Int = currentMainTxProgress(maneuver, distance)
        val force: Boolean =
            !maneuver.equals(NavigationFeature.clean(currentClusterYellowManeuver()))
        sendManeuverWithFallbackGray(maneuver, distance, progress, force)
        AppLog.navigation(
            app,
            ("NAV_ROUNDABOUT_MAIN_REFINED_TX" +
                " reason=" +
                NavigationFeature.clean(reason) +
                " main=" +
                maneuver +
                " distance=" +
                distance +
                " progress=" +
                progress +
                " force=" +
                force +
                " source=" +
                NavigationFeature.clean(source)),
        )
    }

    private fun sendConfirmedMainRolloverBeforeLaneReturn(
        primed: Boolean,
        incomingMainDistance: String?,
        reason: String?,
        source: String?,
    ) {
        if (!primed) return
        val maneuver = NavigationFeature.clean(state.maneuver)
        val distance = NavigationFeature.nonZeroDistance(incomingMainDistance)
        if (!NavigationFeature.isUsableManeuver(maneuver) || distance.isNullOrEmpty()) {
            AppLog.navigation(
                app,
                ("NAV_PROGRESS_ROLLOVER_TX_HOLD" +
                    " reason=" +
                    NavigationFeature.clean(reason) +
                    " main=" +
                    NavigationFeature.first(maneuver, "-") +
                    " distance=" +
                    NavigationFeature.first(distance, "-") +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            return
        }
        val progress: Int = currentMainTxProgress(maneuver, distance)
        if (progress != ManeuverProgressTracker.FULL_BUCKET) {
            AppLog.navigation(
                app,
                ("NAV_PROGRESS_ROLLOVER_TX_HOLD" +
                    " reason=progress_not_full" +
                    " branch=" +
                    NavigationFeature.clean(reason) +
                    " main=" +
                    maneuver +
                    " distance=" +
                    distance +
                    " progress=" +
                    progress +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            return
        }
        sendManeuverWithFallbackGray(maneuver, distance, progress, true)
        AppLog.navigation(
            app,
            ("NAV_PROGRESS_ROLLOVER_TX" +
                " branch=" +
                NavigationFeature.clean(reason) +
                " main=" +
                maneuver +
                " distance=" +
                distance +
                " progress=" +
                progress +
                " source=" +
                NavigationFeature.clean(source)),
        )
    }

    private fun nextMainManeuverCloseForMicroPostPass(): Boolean {
        val meters: Float =
            NavigationFeature.distanceMeters(
                NavigationFeature.first(
                    NavigationFeature.nonZeroDistance(state.maneuverDistance),
                    NavigationFeature.nonZeroDistance(currentMainTxDistance()),
                )
            )
        return meters > 0f && meters <= NavigationFeature.MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS
    }

    private fun activeGrayRoadForLaneTx(maneuver: String?, now: Long): String {
        if (!NavigationFeature.canMergeGrayRoad(maneuver)) return ""
        return if (grayRoadUntil > now) NavigationFeature.clean(activeGrayRoadId) else ""
    }

    private fun stableGrayRoadForLaneTx(
        incomingGrayRoad: String?,
        maneuver: String?,
        now: Long,
    ): String {
        val incoming = NavigationFeature.clean(incomingGrayRoad)
        if (!NavigationFeature.canMergeGrayRoad(maneuver)) return incoming
        val maneuverMask: Int = NavigationFeature.maneuverRoadMask(maneuver)
        if (maneuverMask == 0) return incoming
        val held: String? =
            if (grayRoadUntil > now) NavigationFeature.clean(activeGrayRoadId) else ""
        val heldMask: Int = NavigationFeature.grayRoadMask(held)
        val incomingMask: Int = NavigationFeature.grayRoadMask(incoming)
        if (heldMask != 0 && (heldMask and maneuverMask) == maneuverMask) {
            if (
                incomingMask == 0 ||
                    (incomingMask and maneuverMask) == 0 ||
                    (heldMask or incomingMask) == heldMask
            ) {
                return held!!
            }
        }
        if (incomingMask == 0) return NavigationFeature.grayRoadForManeuver(maneuver)
        if ((incomingMask and maneuverMask) == 0)
            return NavigationFeature.grayRoadFromMask(incomingMask or maneuverMask)
        return incoming
    }

    private fun validMicroDistance(
        distanceText: String?,
        mainDistance: String?,
        microManeuver: String?,
        microSource: Boolean,
        source: String?,
    ): String {
        val normalizedMainDistance = NavigationFeature.nonZeroDistance(mainDistance)
        val distance = NavigationFeature.nonZeroDistance(distanceText)
        if (!microSource || microManeuver.isNullOrEmpty()) return distance
        if (distance.isNullOrEmpty()) {
            return ""
        }
        val microMeters: Float = NavigationFeature.distanceMeters(distance)
        if (microMeters <= 0f) {
            return ""
        }
        val mainMeters: Float = NavigationFeature.distanceMeters(normalizedMainDistance)
        if (NavigationFeature.sameMicroAndMainDistance(microMeters, mainMeters)) {
            if (NavigationFeature.clean(source).contains(":lana_fallback")) {
                return distance
            }
            AppLog.line(
                app,
                ("Navigation ignored micro equal main distance: " +
                    NavigationFeature.clean(microManeuver) +
                    " micro=" +
                    NavigationFeature.clean(distance) +
                    " main=" +
                    NavigationFeature.clean(normalizedMainDistance) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            return ""
        }
        return distance
    }

    private fun laneMainDistanceLooksLikeMicro(
        incomingMainDistance: String?,
        microDistance: String?,
    ): Boolean {
        val incoming = NavigationFeature.nonZeroDistance(incomingMainDistance)
        val micro = NavigationFeature.nonZeroDistance(microDistance)
        if (incoming.isNullOrEmpty() || micro.isNullOrEmpty()) return false
        val incomingMeters: Float = NavigationFeature.distanceMeters(incoming)
        val microMeters: Float = NavigationFeature.distanceMeters(micro)
        if (incomingMeters <= 0f || microMeters <= 0f) return false
        val mainMeters: Float = NavigationFeature.distanceMeters(currentMainTxDistance())
        if (mainMeters <= incomingMeters + NavigationFeature.MAIN_MANEUVER_MICRO_SEPARATION_METERS)
            return false
        val tolerance: Float = Math.max(40f, Math.min(120f, microMeters * 0.4f))
        return Math.abs(incomingMeters - microMeters) <= tolerance
    }

    private fun shouldInferForwardMicro(
        intent: Intent?,
        sourceLower: String?,
        microManeuver: String?,
        fallbackMain: Boolean,
        mainDistance: String?,
        microDistance: String?,
        routeRoadOnly: Boolean,
    ): Boolean {
        val micro = NavigationFeature.nonZeroDistance(microDistance)
        if (micro.isNullOrEmpty()) return false
        val microMeters: Float = NavigationFeature.distanceMeters(micro)
        if (microMeters <= 0f || microMeters > NavigationFeature.INFERRED_FORWARD_MICRO_MAX_METERS)
            return false
        val fallbackOrMain: Boolean =
            fallbackMain ||
                microManeuver.isNullOrEmpty() ||
                NavigationFeature.sameManeuverFamily(microManeuver, state.maneuver)
        if (!fallbackOrMain) return false
        if (!NavigationFeature.hasForwardLaneTruth(intent, sourceLower)) return false
        if (
            NavigationFeature.laneHighlightHas(intent, "right") &&
                !NavigationFeature.laneHighlightHas(intent, "straight")
        )
            return false
        val mainMeters: Float =
            NavigationFeature.distanceMeters(
                NavigationFeature.first(
                    NavigationFeature.nonZeroDistance(mainDistance),
                    NavigationFeature.nonZeroDistance(state.maneuverDistance),
                )
            )
        if (
            mainMeters > 0f &&
                mainMeters <= microMeters + NavigationFeature.MAIN_MANEUVER_MICRO_SEPARATION_METERS
        ) {
            return false
        }
        return routeRoadOnly ||
            NavigationFeature.laneMicroSource(sourceLower) ||
            NavigationFeature.hasLaneData(intent)
    }

    private fun mainManeuverBlocksLaneMicro(
        microManeuver: String?,
        microDistance: String?,
        mainDistance: String?,
    ): Boolean {
        val normalizedMainDistance =
            NavigationFeature.first(
                NavigationFeature.nonZeroDistance(mainDistance),
                NavigationFeature.nonZeroDistance(state.maneuverDistance),
            )
        return NavigationManeuverPolicy.mainBlocksMicro(
            state.maneuver,
            normalizedMainDistance,
            microManeuver,
            microDistance,
            !NavigationFeature.nonZeroDistance(microDistance).isNullOrEmpty(),
            NavigationFeature.sameManeuverFamily(microManeuver, state.maneuver),
        )
    }

    private fun routeRoadActionBlockedByCloseMain(
        routeActionManeuver: String,
        incomingDistance: String?,
    ): Boolean {
        if (!NavigationFeature.isUsableManeuver(routeActionManeuver)) return false
        if (NavigationFeature.isPriorityEventManeuver(routeActionManeuver)) return false
        val mainManeuver = NavigationFeature.clean(state.maneuver)
        if (!NavigationFeature.isUsableManeuver(mainManeuver)) return false
        if (NavigationFeature.sameManeuverFamily(mainManeuver, routeActionManeuver)) return false
        val mainDistance =
            NavigationFeature.first(
                NavigationFeature.nonZeroDistance(state.maneuverDistance),
                currentMainTxDistance(),
            )
        return NavigationManeuverPolicy.mainBlocksMicro(
            mainManeuver,
            mainDistance,
            routeActionManeuver,
            incomingDistance,
            !NavigationFeature.nonZeroDistance(incomingDistance).isNullOrEmpty(),
            false,
        )
    }

    private fun logNavigationDecision(
        intent: Intent?,
        source: String?,
        mainManeuver: String?,
        mainDistance: String?,
        microManeuver: String?,
        microDistance: String?,
        selectedManeuver: String?,
        reason: String?,
    ) {
        val routeId =
            NavigationFeature.first(NavigationFeature.routeIdFromIntent(intent), activeRouteId)
        val provenance =
            NavigationFeature.first(
                NavigationFeature.text(intent, "maneuver_provenance"),
                "unknown",
            )
        val key: String =
            (NavigationFeature.clean(routeId) +
                "|" +
                NavigationFeature.clean(provenance) +
                "|" +
                NavigationFeature.clean(mainManeuver) +
                "@" +
                NavigationFeature.clean(mainDistance) +
                "|" +
                NavigationFeature.clean(microManeuver) +
                "@" +
                NavigationFeature.clean(microDistance) +
                "|" +
                NavigationFeature.clean(selectedManeuver) +
                "|" +
                NavigationFeature.clean(reason))
        if (key.equals(lastNavigationDecisionKey)) return
        lastNavigationDecisionKey = key
        AppLog.navigation(
            app,
            ("NAV_DECISION" +
                " seq=" +
                NavigationFeature.first(NavigationFeature.text(intent, "seq"), "-") +
                " routeId=" +
                NavigationFeature.first(routeId, "-") +
                " provenance=" +
                provenance +
                " main=" +
                NavigationFeature.first(NavigationFeature.clean(mainManeuver), "-") +
                " mainDistance=" +
                NavigationFeature.first(NavigationFeature.clean(mainDistance), "-") +
                " micro=" +
                NavigationFeature.first(NavigationFeature.clean(microManeuver), "-") +
                " microDistance=" +
                NavigationFeature.first(NavigationFeature.clean(microDistance), "-") +
                " selected=" +
                NavigationFeature.first(NavigationFeature.clean(selectedManeuver), "-") +
                " reason=" +
                NavigationFeature.first(NavigationFeature.clean(reason), "-") +
                " source=" +
                NavigationFeature.clean(source)),
        )
    }

    private fun rememberLaneHint(hint: String?, source: String?, now: Long) {
        activeLaneHint = NavigationFeature.clean(hint)
        activeLaneSource = NavigationFeature.clean(source)
        laneGuidanceUntil = now + (AppSettings.navMicroHoldSeconds(app) * 1000L)
        laneHintUntil =
            now +
                Math.max(
                    NavigationFeature.LANE_HINT_OVERLAY_HOLD_MS,
                    AppSettings.navMicroHoldSeconds(app) * 1000L,
                )
    }

    private fun rememberEventHint(hint: String?, source: String?, now: Long) {
        activeEventHint = NavigationFeature.clean(hint)
        activeEventSource = NavigationFeature.clean(source)
        eventHintUntil = now + NavigationFeature.EVENT_HINT_HOLD_MS
    }

    private fun rememberNavigationRawDebug(intent: Intent?, now: Long) {
        if (intent == null) return
        val laneRaw =
            NavigationFeature.first(
                NavigationFeature.text(intent, "lane_topology"),
                NavigationFeature.text(intent, "lane_topology_json"),
                NavigationFeature.text(intent, "raw_lane_topology"),
                NavigationFeature.text(intent, "raw_lane_items"),
                NavigationFeature.text(intent, "lane_items"),
                NavigationFeature.text(intent, "ignored_raw_lane_items"),
                NavigationFeature.text(intent, "ignored_lane_items"),
            )
        val lanePosition = NavigationFeature.navigationRawPosition(intent)
        val roadRaw =
            NavigationFeature.first(
                NavigationFeature.text(intent, "road_scheme_raw"),
                NavigationFeature.text(intent, "direction_sign_items"),
                NavigationFeature.text(intent, "raw_direction_sign_items"),
                NavigationFeature.text(intent, "route_road_options"),
                NavigationFeature.text(intent, "gray_road_options"),
                NavigationFeature.text(intent, "road_options"),
            )
        val upcoming =
            NavigationFeature.first(
                NavigationFeature.text(intent, "upcoming_lane_signs"),
                NavigationFeature.text(intent, "upcoming_direction_signs"),
                NavigationFeature.text(intent, "upcoming_road_events"),
                NavigationFeature.text(intent, "events_json"),
            )
        if (
            laneRaw.isNullOrEmpty() &&
                lanePosition.isNullOrEmpty() &&
                roadRaw.isNullOrEmpty() &&
                upcoming.isNullOrEmpty()
        ) {
            return
        }
        activeLaneRaw = NavigationFeature.trimDebug(laneRaw)
        activeLanePosition = NavigationFeature.trimDebug(lanePosition)
        activeRoadSchemeRaw = NavigationFeature.trimDebug(roadRaw)
        activeUpcomingRaw = NavigationFeature.trimDebug(upcoming)
        navigationRawUntil = now + NavigationFeature.LANE_HINT_OVERLAY_HOLD_MS
    }

    private fun hasActiveNavigationRaw(): Boolean {
        return !activeLaneRaw.isNullOrEmpty() ||
            !activeLanePosition.isNullOrEmpty() ||
            !activeRoadSchemeRaw.isNullOrEmpty() ||
            !activeUpcomingRaw.isNullOrEmpty()
    }

    private fun clearEventHintHold() {
        activeEventHint = ""
        activeEventSource = ""
        eventHintUntil = 0L
    }

    private fun rememberGrayRoad(
        grayRoadId: String?,
        grayRoadKey: String?,
        hint: String?,
        source: String?,
        now: Long,
    ) {
        rememberLaneHint(hint, source, now)
        activeGrayRoadId = NavigationFeature.clean(grayRoadId)
        activeGrayRoadKey = NavigationFeature.clean(grayRoadKey)
        grayRoadUntil = laneHintUntil
    }

    private fun rememberRoadHint(hint: String?, source: String?, now: Long) {
        activeLaneHint = NavigationFeature.clean(hint)
        activeLaneSource = NavigationFeature.clean(source)
        laneHintUntil = now + NavigationFeature.LANE_HINT_OVERLAY_HOLD_MS
    }

    private fun applyLaneDistanceOnly(
        intent: Intent?,
        distance: String?,
        source: String?,
        sourceLower: String?,
        now: Long,
    ) {
        val hadActiveMicro: Boolean = !activeMicroManeuver.isNullOrEmpty()
        val lanePassed: Boolean = handleLaneDistancePassed(distance, now, source)
        val cleanDistance: String? =
            if (lanePassed) "" else NavigationFeature.nonZeroDistance(distance)
        if (!cleanDistance.isNullOrEmpty()) {
            rememberLaneDistance(cleanDistance, now)
        }
        val shownDistance = activeLaneDistanceToPublish(now)
        var heldGrayRoad: String? =
            NavigationFeature.first(
                if (grayRoadUntil > now) activeGrayRoadId else "",
                state.grayRoadId,
            )
        val visualGrayRoad: String = NavigationFeature.visualLaneGrayRoadManeuver(intent)
        var visualMicro: String? = NavigationFeature.visualLaneHighlightedManeuver(intent)
        var visualMicroInferred: Boolean = false
        if (visualMicro.isNullOrEmpty()) {
            visualMicro = NavigationFeature.visualLaneSingleManeuver(intent, sourceLower)
            visualMicroInferred = !visualMicro.isNullOrEmpty()
        }
        var visualMicroFound: Boolean = !visualMicro.isNullOrEmpty()
        val distanceOnlyDecision: ManeuverArbiter.Decision? =
            NavigationManeuverPolicy.decide(
                state.maneuver,
                state.maneuverDistance,
                visualMicro,
                shownDistance,
                visualMicroFound && !shownDistance.isNullOrEmpty(),
                NavigationFeature.sameManeuverFamily(visualMicro, state.maneuver),
            )
        val mainManeuverIsNext: Boolean = visualMicroFound && !distanceOnlyDecision!!.microWins()
        if (mainManeuverIsNext) {
            AppLog.line(
                app,
                ("Navigation ignored lane distance micro by spatial order: main=" +
                    NavigationFeature.clean(state.maneuver) +
                    " visual=" +
                    NavigationFeature.clean(visualMicro) +
                    " mainDistance=" +
                    NavigationFeature.clean(state.maneuverDistance) +
                    " microDistance=" +
                    NavigationFeature.clean(shownDistance) +
                    " reason=" +
                    distanceOnlyDecision.reason +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            visualMicro = ""
            visualMicroFound = false
            clearMicroHintHold()
        }
        if (
            visualMicroFound &&
                visualMicroInferred &&
                NavigationFeature.weakVisualLaneSingleSource(sourceLower) &&
                activeMicroFromTrustedLaneDirection(now) &&
                !activeMicroManeuver.isNullOrEmpty() &&
                !NavigationFeature.sameManeuverFamily(visualMicro, activeMicroManeuver)
        ) {
            AppLog.line(
                app,
                ("Navigation ignored weak single lane over active highlight: active=" +
                    NavigationFeature.clean(activeMicroManeuver) +
                    " incoming=" +
                    NavigationFeature.clean(visualMicro) +
                    " activeSource=" +
                    NavigationFeature.clean(activeMicroSource) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            visualMicro = ""
            visualMicroFound = false
        }
        if (
            visualMicroFound &&
                activeMicroFromTrustedLaneDirection(now) &&
                !NavigationFeature.sameManeuverFamily(visualMicro, activeMicroManeuver)
        ) {
            AppLog.line(
                app,
                ("Navigation replaced stale active lane micro by visual lane highlight: active=" +
                    NavigationFeature.clean(activeMicroManeuver) +
                    " visual=" +
                    NavigationFeature.clean(visualMicro) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            clearMicroHintHold()
        }
        if (
            !visualMicroFound &&
                activeMicroFromTrustedLaneDirection(now) &&
                NavigationFeature.visualGrayRoadCanClearActiveMicro(sourceLower) &&
                !activeMicroMatchesVisualGrayRoad(visualGrayRoad)
        ) {
            AppLog.line(
                app,
                ("Navigation cleared stale active lane micro by visual gray road: active=" +
                    NavigationFeature.clean(activeMicroManeuver) +
                    " visualGray=" +
                    NavigationFeature.first(visualGrayRoad, "-") +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
            clearMicroHintHold()
        }
        val postPassMicroHolding: Boolean =
            activeMicroPostPassActive(now) && !nextMainManeuverCloseForMicroPostPass()
        val visualRepeatsPostPass: Boolean =
            visualMicroFound &&
                postPassMicroHolding &&
                NavigationFeature.sameManeuverFamily(visualMicro, activeMicroManeuver)
        val trustedActiveMicro: Boolean =
            !visualMicroFound && (activeMicroFromTrustedLaneDirection(now) || postPassMicroHolding)
        if (!visualGrayRoad.isNullOrEmpty()) {
            val completeLaneGray = activeCompleteLaneGrayRoad(now)
            if (NavigationFeature.completeLaneGraySource(sourceLower) && visualMicroFound) {
                heldGrayRoad = visualGrayRoad
                rememberCompleteLaneGrayRoad(heldGrayRoad, now)
            } else if (trustedActiveMicro) {
                heldGrayRoad =
                    NavigationFeature.mergeGrayRoads(
                        visualGrayRoad,
                        NavigationFeature.grayRoadFromMask(
                            NavigationFeature.maneuverRoadMask(activeMicroManeuver)
                        ),
                    )
            } else if (
                NavigationFeature.narrowLaneGraySource(sourceLower) &&
                    NavigationFeature.grayRoadContains(completeLaneGray, visualGrayRoad)
            ) {
                heldGrayRoad = completeLaneGray
            } else if (visualMicroFound) {
                heldGrayRoad = visualGrayRoad
            } else {
                heldGrayRoad = visualGrayRoad
            }
            rememberVisualGrayRoad(heldGrayRoad, now)
        }
        val microManeuver: String? =
            if (postPassMicroHolding && (visualRepeatsPostPass || !visualMicroFound))
                activeMicroManeuver
            else if (trustedActiveMicro) activeMicroManeuver
            else if (visualMicroFound) visualMicro else ""
        var microDistance: String? = shownDistance
        var microStatus: String? =
            if (shownDistance.isNullOrEmpty()) "нет lane дистанции" else "только дистанция lane"
        val holdingPostPassManeuver: Boolean =
            postPassMicroHolding &&
                !microManeuver.isNullOrEmpty() &&
                NavigationFeature.sameManeuverFamily(microManeuver, activeMicroManeuver)
        if (holdingPostPassManeuver) {
            microDistance = ""
            microStatus = activeMicroStatusToPublish(now)
        } else if (visualMicroFound) {
            val rememberedStatus = microDecisionStatus(microManeuver, shownDistance, true, source)
            rememberMicroHint(
                microManeuver,
                shownDistance,
                shownDistance,
                rememberedStatus,
                source.toString() +
                    (if (visualMicroInferred) ":visual_lane_single" else ":visual_lane_highlight"),
                now,
                false,
                true,
            )
            microDistance = activeMicroDebugDistanceToPublish()
            microStatus = activeMicroStatus
        } else if (!trustedActiveMicro && !visualMicroFound && microManeuver.isNullOrEmpty()) {
            clearMicroHintHold()
            microStatus = "нет lane подсветки"
            microDistance = ""
        } else if (!trustedActiveMicro && !microManeuver.isNullOrEmpty()) {
            clearMicroHintHold()
            microStatus = "по умолчанию прямо"
        } else if (!microManeuver.isNullOrEmpty() && !shownDistance.isNullOrEmpty()) {
            activeMicroDistance = shownDistance
            activeMicroDebugDistance = shownDistance
            activeMicroStatus =
                microDecisionStatus(microManeuver, shownDistance, true, activeMicroSource)
            microHintUntil = now + laneHoldMs()
            scheduleMicroRestore(activeMicroSource)
            microDistance = activeMicroDebugDistanceToPublish()
            microStatus = activeMicroStatus
        }
        rememberLaneHint(laneDistanceOnlyHint(shownDistance), source, now)
        state =
            state
                .withLaneHint(activeLaneHint, activeLaneSource, now)
                .withNavigationDebug(
                    state.mainManeuverId,
                    state.routeActionId,
                    microManeuver,
                    microDistance,
                    microStatus,
                    heldGrayRoad,
                    NavigationFeature.grayRoadSchemeText(heldGrayRoad, intent, sourceLower),
                    now,
                )
        publishNavigationState()
        var microSent: Boolean = false
        if (!microManeuver.isNullOrEmpty() && shouldTransmitActiveMicroToCluster()) {
            microSent = resendActiveMicroToCluster()
        }
        if (!microSent && (lanePassed || hadActiveMicro)) {
            restoreMainVisualAfterMicroEnd(
                (if (lanePassed) "lane_passed:" else "lane_clear:") +
                    NavigationFeature.clean(source),
                false,
            )
        }
        AppLog.line(
            app,
            ("Navigation lane distance only: distance=" +
                NavigationFeature.first(shownDistance, "-") +
                " micro=" +
                NavigationFeature.first(microManeuver, "-") +
                " gray=" +
                NavigationFeature.first(heldGrayRoad, "-") +
                " visual=" +
                NavigationFeature.first(visualMicro, "-") +
                " visualGray=" +
                NavigationFeature.first(visualGrayRoad, "-") +
                " source=" +
                source),
        )
    }

    private fun laneDistanceOnlyHint(distance: String?): String {
        val cleanDistance = NavigationFeature.nonZeroDistance(distance)
        return if (cleanDistance.isNullOrEmpty()) "lane distance"
        else "lane distance, " + cleanDistance
    }

    private fun rememberLaneDistance(distance: String?, now: Long) {
        val cleanDistance = NavigationFeature.nonZeroDistance(distance)
        if (cleanDistance.isNullOrEmpty()) return
        activeLaneDistance = cleanDistance
        laneDistanceUntil = now + laneHoldMs()
    }

    private fun rememberVisualGrayRoad(grayRoadId: String?, now: Long) {
        val cleanGray = NavigationFeature.clean(grayRoadId)
        if (cleanGray.isNullOrEmpty()) {
            return
        }
        activeGrayRoadId = cleanGray
        activeGrayRoadKey =
            NavigationFeature.grayRoadKey(
                NavigationFeature.first(state.maneuver, state.routeActionId),
                NavigationFeature.first(state.nextStreet, state.currentStreet),
            )
        grayRoadUntil = now + laneHoldMs()
    }

    private fun rememberCompleteLaneGrayRoad(grayRoadId: String?, now: Long) {
        val cleanGray = NavigationFeature.clean(grayRoadId)
        if (cleanGray.isNullOrEmpty()) return
        activeCompleteLaneGrayRoad = cleanGray
        completeLaneGrayUntil = now + laneHoldMs()
    }

    private fun activeCompleteLaneGrayRoad(now: Long): String {
        if (completeLaneGrayUntil <= now) return ""
        return NavigationFeature.clean(activeCompleteLaneGrayRoad)
    }

    private fun clearCompleteLaneGrayRoad() {
        activeCompleteLaneGrayRoad = ""
        completeLaneGrayUntil = 0L
    }

    private fun clearGrayRoadHold() {
        activeGrayRoadId = ""
        activeGrayRoadKey = ""
        grayRoadUntil = 0L
        clearCompleteLaneGrayRoad()
    }

    private fun clearLearnedRoadOptions() {
        learnedRoadKey = ""
        learnedRoadMask = 0
        learnedRoadUntil = 0L
    }

    private fun activeLaneDistanceToPublish(now: Long): String {
        if (laneDistanceUntil <= now) return ""
        return NavigationFeature.nonZeroDistance(activeLaneDistance)
    }

    private fun laneHoldMs(): Long {
        return Math.max(
            NavigationFeature.LANE_HINT_OVERLAY_HOLD_MS,
            AppSettings.navMicroHoldSeconds(app) * 1000L,
        )
    }

    private fun rememberMicroHint(
        maneuver: String?,
        distance: String?,
        status: String?,
        now: Long,
    ) {
        rememberMicroHint(maneuver, distance, distance, status, now, true)
    }

    private fun rememberMicroHint(
        maneuver: String?,
        distance: String?,
        debugDistance: String?,
        status: String?,
        now: Long,
        clearOnEmpty: Boolean,
    ) {
        rememberMicroHint(maneuver, distance, debugDistance, status, now, clearOnEmpty, true)
    }

    private fun rememberMicroHint(
        maneuver: String?,
        distance: String?,
        debugDistance: String?,
        status: String?,
        now: Long,
        clearOnEmpty: Boolean,
        txAllowed: Boolean,
    ) {
        rememberMicroHint(
            maneuver,
            distance,
            debugDistance,
            status,
            "",
            now,
            clearOnEmpty,
            txAllowed,
        )
    }

    private fun rememberMicroHint(
        maneuver: String?,
        distance: String?,
        debugDistance: String?,
        status: String?,
        source: String?,
        now: Long,
        clearOnEmpty: Boolean,
        txAllowed: Boolean,
    ) {
        val cleanManeuver = NavigationFeature.clean(maneuver)
        val cleanDistance = NavigationFeature.clean(distance)
        val cleanDebugDistance = NavigationFeature.clean(debugDistance)
        if (
            !clearOnEmpty &&
                cleanDistance.isNullOrEmpty() &&
                activeMicroPostPassActive(now) &&
                (cleanManeuver.isNullOrEmpty() ||
                    NavigationFeature.sameManeuverFamily(cleanManeuver, activeMicroManeuver))
        ) {
            AppLog.line(
                app,
                ("Navigation kept lane micro post-pass over refresh: " +
                    NavigationFeature.clean(activeMicroManeuver) +
                    " incoming=" +
                    NavigationFeature.first(cleanManeuver, "-") +
                    " distance=" +
                    NavigationFeature.first(cleanDistance, "-") +
                    " status=" +
                    activeMicroStatusToPublish(now)),
            )
            return
        }
        if (cleanManeuver.isNullOrEmpty()) {
            if (!clearOnEmpty) {
                return
            }
            clearMicroHintHold()
            return
        }
        if (
            !clearOnEmpty &&
                cleanDistance.isNullOrEmpty() &&
                !activeMicroManeuver.isNullOrEmpty() &&
                microHintUntil > now &&
                !activeMicroDistanceToPublish().isNullOrEmpty()
        ) {
            AppLog.line(
                app,
                ("Navigation kept active lane micro over empty distance: " +
                    NavigationFeature.clean(activeMicroManeuver) +
                    " distance=" +
                    NavigationFeature.clean(activeMicroDistance) +
                    " incoming=" +
                    cleanManeuver),
            )
            return
        }
        activeMicroManeuver = cleanManeuver
        activeMicroDistance = cleanDistance
        activeMicroDebugDistance =
            if (cleanDebugDistance.isNullOrEmpty()) cleanDistance else cleanDebugDistance
        activeMicroStatus = NavigationFeature.clean(status)
        activeMicroSource = NavigationFeature.clean(source)
        activeMicroTxAllowed = txAllowed && !cleanDistance.isNullOrEmpty()
        activeMicroPostPassUntil = 0L
        microHintUntil = now + laneHoldMs()
        scheduleMicroRestore(source)
    }

    private fun clearMicroHintHold() {
        handler.removeCallbacks(microRestoreTask)
        pendingMicroRestoreSource = ""
        lastMicroTxHoldKey = ""
        lastMicroTxLogKey = ""
        activeMicroManeuver = ""
        activeMicroDebugDistance = ""
        activeMicroDistance = ""
        activeMicroStatus = ""
        activeMicroSource = ""
        activeMicroTxAllowed = true
        activeMicroPostPassUntil = 0L
        microHintUntil = 0L
    }

    private fun clearLaneDistanceHold() {
        activeLaneDistance = ""
        laneDistanceUntil = 0L
        clearCompleteLaneGrayRoad()
    }

    private fun clearLaneHintHold(keepMicroHint: Boolean = false) {
        activeLaneHint = ""
        activeLaneSource = ""
        clearGrayRoadHold()
        clearLearnedRoadOptions()
        clearLaneDistanceHold()
        if (!keepMicroHint) {
            clearMicroHintHold()
        }
        laneGuidanceUntil = 0L
        laneHintUntil = 0L
        grayRoadUntil = 0L
    }

    private fun clearClusterVisualHold() {
        activeClusterVisual = ""
    }

    private fun clearRouteChangeVisualHold(reason: String?) {
        resetMainManeuverPreview("route_change_visual_" + NavigationFeature.clean(reason))
        clearLaneHintHold()
        clearEventHintHold()
        clearRoundaboutExitHold()
        clearFinalSegmentFinishDirection("route change visual " + NavigationFeature.clean(reason))
        resetManeuverProgress()
        resetNavigationSendCache()
        clusterTx.clear()
        lastNavigationDecisionKey = ""
        lastTxMicroGuardKey = ""
        state =
            NavigationState(
                state.active,
                false,
                state.speedExceeded,
                "",
                "",
                "",
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                state.finishStreet,
                state.speedLimit,
                state.currentSpeed,
                NavigationFeature.clean(reason),
                System.currentTimeMillis(),
            )
        publishNavigationState()
        AppLog.line(
            app,
            "Navigation route change visual cleared: " + NavigationFeature.clean(reason),
        )
    }

    private fun currentClusterYellowManeuver(): String {
        val visual = NavigationFeature.first(activeClusterVisual, state.clusterVisual)
        val maneuver = NavigationFeature.clusterYellowManeuverFromFrame(visual)
        if (!maneuver.isNullOrEmpty()) return maneuver
        return NavigationFeature.clusterYellowManeuverFromFrame(
            NavigationFeature.latestClusterManeuverLine(state.clusterTx)
        )
    }

    private fun currentMainTxDistance(): String {
        return NavigationFeature.nonZeroDistance(state.maneuverDistance)
    }

    private fun currentMainTxProgress(maneuver: String, distanceText: String?): Int {
        if (!NavigationFeature.nonZeroDistance(distanceText).isNullOrEmpty()) {
            return maneuverProgressBucket(maneuver, distanceText)
        }
        val held: kia.app.navigation.domain.ManeuverProgressTracker.Result =
            maneuverProgressTracker.current(
                activeRouteId,
                NavigationFeature.maneuverFamily(maneuver),
            )
        if (held.hasValue) {
            return held.bucket
        }
        val frame = currentClusterManeuverFrame()
        val frameManeuver = NavigationFeature.clusterYellowManeuverFromFrame(frame)
        val current: Int =
            if (
                NavigationFeature.sameManeuverFamily(
                    NavigationFeature.first(maneuver, state.maneuver),
                    frameManeuver,
                )
            )
                NavigationFeature.clusterFrameProgressBucket(frame)
            else -1
        if (current >= 0) return current
        return 0
    }

    private fun currentClusterManeuverFrame(): String? {
        return NavigationFeature.first(
            activeClusterVisual,
            NavigationFeature.latestClusterManeuverLine(state.clusterTx),
        )
    }

    private fun clusterVisualIsLoading(): Boolean {
        val visual = NavigationFeature.first(activeClusterVisual, state.clusterVisual)
        return visual.isNullOrEmpty() || visual.lowercase(Locale.US).contains("unknown")
    }

    private fun clearStaleRouteVisual(reason: String?) {
        resetMainManeuverPreview("stale_route_visual_" + NavigationFeature.clean(reason))
        clearLaneHintHold()
        lastDirectManeuverAt = 0L
        clearRoundaboutExitHold()
        clearFinalSegmentFinishDirection("stale route visual " + NavigationFeature.clean(reason))
        clearClusterVisualHold()
        resetManeuverProgress()
        resetNavigationSendCache()
        state =
            NavigationState(
                state.active,
                false,
                state.speedExceeded,
                "",
                "",
                "",
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                "",
                state.speedLimit,
                state.currentSpeed,
                NavigationFeature.clean(reason),
                System.currentTimeMillis(),
            )
        publishNavigationState()
        AppLog.line(app, "Navigation stale visual cleared: " + NavigationFeature.clean(reason))
    }

    private fun clearStaleManeuverVisual(reason: String?) {
        resetMainManeuverPreview("stale_maneuver_visual_" + NavigationFeature.clean(reason))
        clearRoundaboutExitHold()
        clearFinalSegmentFinishDirection("stale maneuver visual " + NavigationFeature.clean(reason))
        clearClusterVisualHold()
        resetManeuverProgress()
        resetNavigationSendCache()
        state =
            NavigationState(
                state.active,
                false,
                state.speedExceeded,
                "",
                "",
                "",
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                state.finishStreet,
                state.speedLimit,
                state.currentSpeed,
                NavigationFeature.clean(reason),
                System.currentTimeMillis(),
            )
        publishNavigationState()
        if (state.active) sendConfiguredText()
        AppLog.line(app, "Navigation stale maneuver cleared: " + NavigationFeature.clean(reason))
    }

    private fun finishPointKnown(): Boolean {
        return NavigationFeature.validCoordinate(finishLatitude, finishLongitude)
    }

    private fun gpsPointKnown(): Boolean {
        return NavigationFeature.validCoordinate(lastGpsLatitude, lastGpsLongitude)
    }

    private fun rememberYandexRoutePose(intent: Intent?, source: String?) {
        if (intent == null || !NavigationFeature.isYandexSource(source)) return
        val now: Long = System.currentTimeMillis()
        val point: DoubleArray? = currentPointFromIntent(intent)
        if (point != null) {
            rememberCurrentPoint(
                point.get(0),
                point.get(1),
                now,
                Float.NaN,
                NavigationFeature.clean(source) + "_position",
            )
        }
        val heading: Float = headingFromIntent(intent)
        if (!java.lang.Float.isNaN(heading)) {
            rememberYandexHeading(heading, source, now)
        }
    }

    private fun currentPointFromIntent(intent: Intent): DoubleArray? {
        val lat: Double =
            NavigationFeature.coordinate(
                intent,
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
            )
        val lon: Double =
            NavigationFeature.coordinate(
                intent,
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
            )
        if (NavigationFeature.validCoordinate(lat, lon)) return doubleArrayOf(lat, lon)
        return NavigationFeature.coordinatePair(
            NavigationFeature.first(
                NavigationFeature.text(intent, "current_point"),
                NavigationFeature.text(intent, "currentPoint"),
                NavigationFeature.text(intent, "vehicle_point"),
                NavigationFeature.text(intent, "vehiclePoint"),
                NavigationFeature.text(intent, "car_point"),
                NavigationFeature.text(intent, "carPoint"),
                NavigationFeature.text(intent, "position_point"),
                NavigationFeature.text(intent, "positionPoint"),
                NavigationFeature.text(intent, "route_position_point"),
                NavigationFeature.text(intent, "routePositionPoint"),
                NavigationFeature.text(intent, "location_point"),
                NavigationFeature.text(intent, "locationPoint"),
                NavigationFeature.text(intent, "gps_point"),
                NavigationFeature.text(intent, "gpsPoint"),
            )
        )
    }

    private fun headingFromIntent(intent: Intent?): Float {
        return NavigationFeature.parseHeadingDegrees(
            NavigationFeature.first(
                NavigationFeature.text(intent, "yandex_heading"),
                NavigationFeature.text(intent, "yandexHeading"),
                NavigationFeature.text(intent, "vehicle_heading"),
                NavigationFeature.text(intent, "vehicleHeading"),
                NavigationFeature.text(intent, "car_heading"),
                NavigationFeature.text(intent, "carHeading"),
                NavigationFeature.text(intent, "map_heading"),
                NavigationFeature.text(intent, "mapHeading"),
                NavigationFeature.text(intent, "navigation_heading"),
                NavigationFeature.text(intent, "navigationHeading"),
                NavigationFeature.text(intent, "route_position_heading"),
                NavigationFeature.text(intent, "routePositionHeading"),
                NavigationFeature.text(intent, "location_heading"),
                NavigationFeature.text(intent, "locationHeading"),
                NavigationFeature.text(intent, "location_bearing"),
                NavigationFeature.text(intent, "locationBearing"),
                NavigationFeature.text(intent, "gps_bearing"),
                NavigationFeature.text(intent, "gpsBearing"),
                NavigationFeature.text(intent, "bearing"),
                NavigationFeature.text(intent, "heading"),
                NavigationFeature.text(intent, "azimuth"),
                NavigationFeature.text(intent, "course"),
            )
        )
    }

    private fun rememberCurrentPoint(
        lat: Double,
        lon: Double,
        now: Long,
        accuracyMeters: Float,
        source: String?,
    ) {
        if (!NavigationFeature.validCoordinate(lat, lon)) return
        if (gpsPointKnown()) {
            val from: Location = Location("kia_nav_previous")
            from.setLatitude(lastGpsLatitude)
            from.setLongitude(lastGpsLongitude)
            val to: Location = Location("kia_nav_current")
            to.setLatitude(lat)
            to.setLongitude(lon)
            val moved: Float = from.distanceTo(to)
            val accurateEnough: Boolean =
                java.lang.Float.isNaN(accuracyMeters) ||
                    accuracyMeters <= NavigationFeature.GPS_COURSE_MAX_ACCURACY_METERS
            val recentEnough: Boolean =
                lastGpsPointAt <= 0L ||
                    now - lastGpsPointAt <= NavigationFeature.GPS_COURSE_MAX_POINT_GAP_MS
            if (
                moved >= NavigationFeature.GPS_COURSE_MIN_DISTANCE_METERS &&
                    accurateEnough &&
                    recentEnough
            ) {
                var course: Float = NavigationFeature.normalizeDegrees(from.bearingTo(to))
                if (
                    !java.lang.Float.isNaN(lastGpsCourseBearing) &&
                        now - lastGpsCourseBearingAt <= NavigationFeature.GPS_COURSE_FRESH_MS
                ) {
                    course = NavigationFeature.blendDegrees(lastGpsCourseBearing, course, 0.45f)
                }
                lastGpsCourseBearing = course
                lastGpsCourseBearingAt = now
                AppLog.line(
                    app,
                    ("Navigation GPS course: " +
                        Math.round(course) +
                        " moved=" +
                        Math.round(moved) +
                        " source=" +
                        NavigationFeature.clean(source)),
                )
            }
        }
        lastGpsLatitude = lat
        lastGpsLongitude = lon
        lastGpsPointAt = now
    }

    private fun rememberYandexHeading(headingDegrees: Float, source: String?, now: Long) {
        lastYandexHeading = NavigationFeature.normalizeDegrees(headingDegrees)
        lastYandexHeadingAt = now
        if (now - lastYandexHeadingLogAt >= 5000L) {
            lastYandexHeadingLogAt = now
            AppLog.line(
                app,
                ("Navigation Yandex heading: " +
                    Math.round(lastYandexHeading) +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
    }

    private fun bearingToFinish(): Float {
        val from: Location = Location("kia_nav_current")
        from.setLatitude(lastGpsLatitude)
        from.setLongitude(lastGpsLongitude)
        val to: Location = Location("kia_nav_finish")
        to.setLatitude(finishLatitude)
        to.setLongitude(finishLongitude)
        return NavigationFeature.normalizeDegrees(from.bearingTo(to))
    }

    private fun routeLikelyChanged(distanceText: String?, timeText: String): Boolean {
        val oldMeters: Float = NavigationFeature.distanceMeters(state.routeDistance)
        val newMeters: Float = NavigationFeature.distanceMeters(distanceText)
        if (oldMeters > 0f && newMeters > 0f) {
            val delta: Float = Math.abs(newMeters - oldMeters)
            val threshold: Float = Math.max(800f, oldMeters * 0.35f)
            if (delta > threshold) return true
        }
        val oldMinutes: Int = NavigationFeature.minutesValue(state.routeTime)
        val newMinutes: Int = NavigationFeature.minutesValue(timeText)
        return oldMinutes > 0 && newMinutes > 0 && Math.abs(newMinutes - oldMinutes) > 3
    }

    private fun shouldPreserveMicroAfterRoundaboutTransition(
        oldManeuver: String?,
        newManeuver: String?,
        now: Long,
    ): Boolean {
        if (
            !NavigationFeature.isRoundaboutManeuver(oldManeuver) ||
                NavigationFeature.isRoundaboutManeuver(newManeuver)
        ) {
            return false
        }
        if (activeMicroManeuver.isNullOrEmpty() || microHintUntil <= now) {
            return false
        }
        if (NavigationModeSettings.isTbt(app) || NavigationModeSettings.isFinishDirection(app)) {
            return false
        }
        return AppSettings.navMicroManeuvers(app)
    }

    @kotlin.jvm.Synchronized
    fun setTbtMode(enabled: Boolean) {
        setOutputMode(if (enabled) NavigationOutputMode.TBT else NavigationOutputMode.NORMAL)
    }

    @kotlin.jvm.Synchronized
    fun setFinishDirectionMode(enabled: Boolean) {
        setOutputMode(
            if (enabled) NavigationOutputMode.FINISH_DIRECTION else NavigationOutputMode.NORMAL
        )
    }

    @kotlin.jvm.Synchronized
    fun setFinishCompassAuto(enabled: Boolean) {
        AppSettings.setNavFinishCompassAuto(app, enabled)
        if (!enabled) {
            clearFinalSegmentFinishDirection("finish compass auto disabled")
            resendCurrentVisual()
        } else if (state.active && !state.finishReached) {
            lastSentFinishDirectionKey = ""
            sendDirectionToFinishIfNeeded(true)
        }
        AppLog.line(app, "Navigation finish compass auto: " + enabled)
    }

    @kotlin.jvm.Synchronized
    fun setOutputMode(mode: Int) {
        syncRouteStateFromStore()
        NavigationModeSettings.setMode(app, mode)
        resetMainManeuverPreview("output_mode")
        AppLog.line(app, "Navigation route output mode: " + NavigationModeSettings.label(app))
        resetNavigationSendCache()
        if (state.active) sender.sendActive(true)
        resendKnownRouteData()
        if (NavigationModeSettings.isFinishDirection(app)) {
            if (finishDirectionShouldOverride()) {
                sendFinishDirectionPlaceholder(true)
                sendDirectionToFinishIfNeeded(true)
            } else {
                resendCurrentVisual()
            }
            return
        }
        resendCurrentVisual()
    }

    @kotlin.jvm.Synchronized
    fun onManeuverDisplaySettingsChanged(reason: String?) {
        syncRouteStateFromStore()
        resetMainManeuverPreview("settings_" + NavigationFeature.clean(reason))
        if (!AppSettings.navMicroManeuvers(app)) {
            clearMicroHintHold()
            clearLaneDistanceHold()
        }
        if (!state.active || state.finishReached) return
        resendCurrentVisual()
        AppLog.navigation(
            app,
            ("NAV_DISPLAY_SETTINGS_APPLIED" +
                " reason=" +
                NavigationFeature.clean(reason) +
                " straightUntilMain=" +
                AppSettings.navStraightUntilMain(app) +
                " mainReveal=" +
                AppSettings.navMainRevealDistanceMeters(app) +
                " microMax=" +
                AppSettings.navMicroMaxDistanceMeters(app)),
        )
    }

    private fun syncRouteStateFromStore() {
        val stored = StateStore.navigation()
        if (stored == null || !stored.active || stored.finishReached) return
        val currentMissingRoute: Boolean =
            !state.active || (state.maneuver.isNullOrEmpty() && !stored.maneuver.isNullOrEmpty())
        val storedNewer: Boolean = stored.updatedAt > state.updatedAt
        if (currentMissingRoute || storedNewer) {
            state = stored
            var now: Long = System.currentTimeMillis()
            activeClusterVisual = stored.clusterVisual
            if (!stored.laneHint.isNullOrEmpty()) {
                activeLaneHint = stored.laneHint
                activeLaneSource = stored.laneSource
                now = System.currentTimeMillis()
                laneHintUntil = now + NavigationFeature.LANE_HINT_OVERLAY_HOLD_MS
                grayRoadUntil = now + NavigationFeature.LANE_HINT_OVERLAY_HOLD_MS
            }
            if (!stored.eventHint.isNullOrEmpty()) {
                activeEventHint = stored.eventHint
                activeEventSource = stored.eventSource
                eventHintUntil = now + NavigationFeature.EVENT_HINT_HOLD_MS
            }
            val microHoldMs: Long =
                Math.max(
                    NavigationFeature.LANE_HINT_OVERLAY_HOLD_MS,
                    AppSettings.navMicroHoldSeconds(app) * 1000L,
                )
            val storedMicroUntil: Long =
                if (stored.updatedAt > 0L) stored.updatedAt + microHoldMs else 0L
            if (!stored.microManeuverId.isNullOrEmpty() && storedMicroUntil > now) {
                activeMicroManeuver = stored.microManeuverId
                activeMicroDistance =
                    validMicroDistance(
                        stored.microDistance,
                        stored.maneuverDistance,
                        stored.microManeuverId,
                        !stored.microDistance.isNullOrEmpty(),
                        stored.source,
                    )
                activeMicroDebugDistance = stored.microDistance
                activeMicroStatus = stored.microStatus
                activeMicroSource = stored.source
                activeMicroTxAllowed = microDistanceAllowed(activeMicroDistance)
                microHintUntil = storedMicroUntil
                scheduleMicroRestore(stored.source)
            } else if (!stored.microDistance.isNullOrEmpty() && storedMicroUntil > now) {
                activeLaneDistance = stored.microDistance
                laneDistanceUntil = storedMicroUntil
            } else if (
                !stored.microManeuverId.isNullOrEmpty() || !stored.microDistance.isNullOrEmpty()
            ) {
                clearMicroHintHold()
                clearLaneDistanceHold()
                state =
                    state.withNavigationDebug(
                        stored.mainManeuverId,
                        stored.routeActionId,
                        "",
                        "",
                        "expired",
                        stored.grayRoadId,
                        stored.grayRoadScheme,
                        now,
                    )
                StateStore.setNavigation(app, state)
            }
            AppLog.line(app, "Navigation route state synced for mode switch: " + state.summary())
        }
    }

    @kotlin.jvm.Synchronized
    fun setTextMode(mode: Int) {
        AppSettings.setNavTextMode(app, mode)
        AppLog.line(app, "Navigation text mode: " + textModeName(AppSettings.navTextMode(app)))
        resetNavigationTextCache()
        sendConfiguredText()
    }

    @kotlin.jvm.Synchronized
    fun setSourceMode(mode: Int) {
        val previous: Int = AppSettings.navSourceMode(app)
        AppSettings.setNavSourceMode(app, mode)
        val selected: Int = AppSettings.navSourceMode(app)
        if (previous != selected) dashboardRoadLimit = DashboardRoadSpeedLimit.NONE
        val source = NavigationFeature.clean(state.source).lowercase(Locale.US)
        if (
            previous != selected &&
                (selected == AppSettings.NAV_SOURCE_MVITE ||
                    previous == AppSettings.NAV_SOURCE_MVITE)
        ) {
            resetMviteRouteContext()
            cancelRoadSpeedLimitDelivery()
            if (state.active || !state.speedLimit.isNullOrEmpty()) {
                forceInactive("source_mode_" + AppSettings.navSourceLabel(app))
            }
        } else if (selected == AppSettings.NAV_SOURCE_YANDEX && source.contains("2gis")) {
            forceInactive("source_mode_yandex")
        } else if (
            selected == AppSettings.NAV_SOURCE_2GIS &&
                NavigationFeature.isYandexSource(state.source)
        ) {
            forceInactive("source_mode_2gis")
        }
        sourceArbitrator.reset()
        seedSourceOwnerFromState()
        AppLog.line(app, "Navigation source mode: " + AppSettings.navSourceLabel(app))
    }

    private fun sendConfiguredText() {
        if (!state.active) {
            return
        }
        if (state.finishReached) {
            sendNavigationText("Финиш")
            return
        }
        val now: Long = System.currentTimeMillis()
        if (routeReroutingActive(now)) {
            sendRouteReroutingVisual()
            return
        }
        val mode: Int = AppSettings.navTextMode(app)
        if (finishDirectionShouldOverride()) {
            if (!sendDirectionToFinishIfNeeded(false)) sendFinishDirectionPlaceholder(false)
            return
        }
        if (
            AppSettings.navOverspeedTextEnabled(app) &&
                NavigationFeature.MVITE_SOURCE.equals(state.source) &&
                mviteOverspeed &&
                !mviteCameraDistance!!.isEmpty()
        ) {
            sendNavigationText("Камера " + mviteCameraDistance)
            return
        }
        if (
            AppSettings.navOverspeedTextEnabled(app) &&
                state.speedExceeded &&
                System.currentTimeMillis() <= overspeedTextUntil &&
                !state.speedLimit.isNullOrEmpty()
        ) {
            sendNavigationText("Превышение " + state.speedLimit + " km/h")
            return
        }
        if (routeWaiting()) {
            sendRouteLoadingVisual()
            return
        }
        if (state.loading()) {
            val selectedText = textForMode(mode)
            if (!selectedText.isNullOrEmpty()) {
                sendNavigationText(selectedText)
                return
            }
            if (state.maneuver.isNullOrEmpty()) {
                if (routeLoadingFallbackReady()) {
                    if (!sendDirectionToFinishForLoading(false))
                        sendFinishDirectionPlaceholder(false)
                } else {
                    sendRouteLoadingVisual()
                }
            }
            return
        }
        val text = textForMode(mode)
        sendNavigationText(text)
    }

    private fun sendNavigationText(text: String?) {
        val cleanText = NavigationFeature.clean(text)
        if (cleanText.isNullOrEmpty()) return
        if (cleanText.equals(lastSentNavigationText)) return
        lastSentNavigationText = cleanText
        sender.sendText(cleanText)
    }

    private fun applyClusterVisual(visual: String?) {
        activeClusterVisual = NavigationFeature.clean(visual)
        state = state.withClusterVisualText(activeClusterVisual, System.currentTimeMillis())
        publishNavigationState()
    }

    private fun resetNavigationTextCache() {
        lastSentNavigationText = ""
        clearManeuverTextHint()
    }

    private fun startManeuverTextHint(maneuver: String?, roundaboutExit: String?) {
        clearManeuverTextHint()
    }

    private fun maneuverHintActive(): Boolean {
        return !activeManeuverHintText.isNullOrEmpty() &&
            System.currentTimeMillis() <= maneuverTextUntil
    }

    private fun clearManeuverTextHint() {
        maneuverTextGeneration++
        activeManeuverHintText = ""
        maneuverTextUntil = 0L
    }

    private fun sendManeuverIfChanged(
        imageId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
    ) {
        sendManeuverIfChanged(imageId, distance, km, progressBucket, false)
    }

    private fun sendManeuverIfChanged(
        imageId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
        force: Boolean,
    ) {
        sendManeuverIfChanged(imageId, distance, km, progressBucket, force, false)
    }

    private fun sendManeuverIfChanged(
        imageId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
        force: Boolean,
        resolvedMicro: Boolean,
    ) {
        var progressBucket: Int = progressBucket
        val cleanImageId = NavigationFeature.clean(imageId)
        progressBucket = NavigationFeature.normalizeProgressBucket(progressBucket)
        if (!navigationTxAllowed(cleanImageId)) return
        if (maneuverTxBlockedByRerouting(cleanImageId)) return
        if (
            !resolvedMicro &&
                maneuverTxBlockedByEarlierActiveMicro(cleanImageId, distance, km, progressBucket)
        )
            return
        if (finishDirectionShouldOverride() && !NavigationFeature.isFinishManeuver(cleanImageId)) {
            if (!sendDirectionToFinishIfNeeded(force)) {
                sendFinishDirectionPlaceholder(force)
            }
            return
        }
        if (
            NavigationFeature.canMergeGrayRoad(cleanImageId) &&
                !activeGrayRoadId.isNullOrEmpty() &&
                grayRoadUntil > System.currentTimeMillis()
        ) {
            sendManeuverWithGrayRoadIfChanged(
                cleanImageId,
                activeGrayRoadId,
                distance,
                km,
                progressBucket,
                force,
                resolvedMicro,
            )
            return
        }
        if (
            interceptMainManeuverPreview(
                cleanImageId,
                "",
                distance,
                km,
                progressBucket,
                force,
                resolvedMicro,
            )
        ) {
            return
        }
        clusterTx.sendManeuver(imageId, distance, km, progressBucket, force, !resolvedMicro)
    }

    private fun sendManeuverWithGrayRoadIfChanged(
        imageId: String?,
        grayRoadId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
    ) {
        sendManeuverWithGrayRoadIfChanged(imageId, grayRoadId, distance, km, progressBucket, false)
    }

    private fun sendManeuverWithGrayRoadIfChanged(
        imageId: String?,
        grayRoadId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
        force: Boolean,
    ) {
        sendManeuverWithGrayRoadIfChanged(
            imageId,
            grayRoadId,
            distance,
            km,
            progressBucket,
            force,
            false,
        )
    }

    private fun sendManeuverWithGrayRoadIfChanged(
        imageId: String?,
        grayRoadId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
        force: Boolean,
        resolvedMicro: Boolean,
    ) {
        var progressBucket: Int = progressBucket
        val cleanImageId = NavigationFeature.clean(imageId)
        progressBucket = NavigationFeature.normalizeProgressBucket(progressBucket)
        if (!navigationTxAllowed(cleanImageId)) return
        if (maneuverTxBlockedByRerouting(cleanImageId)) return
        if (
            !resolvedMicro &&
                maneuverTxBlockedByEarlierActiveMicro(cleanImageId, distance, km, progressBucket)
        )
            return
        if (finishDirectionShouldOverride() && !NavigationFeature.isFinishManeuver(cleanImageId)) {
            val sentFinishDirection: Boolean = sendDirectionToFinishIfNeeded(force)
            AppLog.line(
                app,
                ("Navigation gray fallback blocked by finish direction: maneuver=" +
                    cleanImageId +
                    " gray=" +
                    NavigationFeature.clean(grayRoadId) +
                    " sent=" +
                    sentFinishDirection),
            )
            if (!sentFinishDirection) sendFinishDirectionPlaceholder(force)
            return
        }
        val cleanGrayRoad = NavigationFeature.clean(grayRoadId)
        if (
            NavigationFeature.isPriorityEventManeuver(cleanImageId) &&
                !cleanGrayRoad.isNullOrEmpty()
        ) {
            AppLog.line(
                app,
                ("Navigation priority maneuver TX without gray road: " +
                    cleanImageId +
                    " gray=" +
                    cleanGrayRoad),
            )
            sendManeuverIfChanged(cleanImageId, distance, km, progressBucket, force, resolvedMicro)
            return
        }
        if (NavigationFeature.grayRoadIncompatibleWithManeuver(cleanImageId, cleanGrayRoad)) {
            AppLog.line(
                app,
                ("Navigation dropped incompatible gray road TX: maneuver=" +
                    cleanImageId +
                    " gray=" +
                    cleanGrayRoad),
            )
            clearGrayRoadHold()
            clearLearnedRoadOptions()
            sendManeuverIfChanged(cleanImageId, distance, km, progressBucket, force, resolvedMicro)
            return
        }
        if (
            interceptMainManeuverPreview(
                cleanImageId,
                cleanGrayRoad,
                distance,
                km,
                progressBucket,
                force,
                resolvedMicro,
            )
        ) {
            return
        }
        clusterTx.sendManeuverWithGrayRoad(
            imageId,
            grayRoadId,
            distance,
            km,
            progressBucket,
            force,
            !resolvedMicro,
        )
    }

    private fun interceptMainManeuverPreview(
        actualManeuver: String,
        grayRoad: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
        force: Boolean,
        resolvedMicro: Boolean,
    ): Boolean {
        val decision: MainManeuverPreviewPolicy.Decision? =
            mainManeuverPreviewDecision(actualManeuver, distance, km, resolvedMicro)
        if (decision == kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.ACTUAL)
            return false

        val identity = NavigationFeature.first(mainPreviewIdentity, "-")
        val decisionKey: String = decision.toString() + "|" + identity
        if (!decisionKey.equals(lastMainPreviewDecisionKey)) {
            lastMainPreviewDecisionKey = decisionKey
            AppLog.navigation(
                app,
                ("NAV_MAIN_PREVIEW" +
                    " decision=" +
                    decision +
                    " route=" +
                    NavigationFeature.first(activeRouteId, "-") +
                    " main=" +
                    NavigationFeature.clean(actualManeuver) +
                    " distance=" +
                    NavigationFeature.clusterDistanceText(distance, km) +
                    " reveal=" +
                    AppSettings.navMainRevealDistanceMeters(app) +
                    " latched=" +
                    mainPreviewRevealLatched),
            )
        }
        if (decision == kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.HOLD)
            return true

        val cleanGrayRoad = NavigationFeature.clean(grayRoad)
        val previewGrayRoad: String? =
            if (
                MainManeuverPreviewPolicy.grayRoadSupportsStraight(
                    NavigationFeature.grayRoadMask(cleanGrayRoad)
                )
            )
                cleanGrayRoad
            else ""
        clusterTx.rememberMainManeuver(actualManeuver, cleanGrayRoad, distance, km, progressBucket)
        if (previewGrayRoad.isNullOrEmpty()) {
            clusterTx.sendManeuver("context_ra_forward", distance, km, progressBucket, force, false)
        } else {
            clusterTx.sendManeuverWithGrayRoad(
                "context_ra_forward",
                previewGrayRoad,
                distance,
                km,
                progressBucket,
                force,
                false,
            )
        }
        return true
    }

    private fun mainManeuverPreviewDecision(
        actualManeuver: String,
        distance: Float,
        km: Boolean,
        resolvedMicro: Boolean,
    ): MainManeuverPreviewPolicy.Decision? {
        val enabled: Boolean =
            AppSettings.navStraightUntilMain(app) && NavigationFeature.isYandexSource(state.source)
        val normalMode: Boolean = NavigationModeSettings.isNormal(app)
        val usableMain: Boolean =
            NavigationFeature.isUsableManeuver(actualManeuver) &&
                !NavigationFeature.isFinishManeuver(actualManeuver)
        val actualStraight: Boolean =
            "context_ra_forward".equals(NavigationFeature.clean(actualManeuver))
        val meters: Float = if (distance > 0f && km) distance * 1000f else distance
        val revealMeters: Int = AppSettings.navMainRevealDistanceMeters(app)

        if (enabled && normalMode && !resolvedMicro && usableMain && !actualStraight) {
            val identity: String =
                NavigationFeature.clean(activeRouteId) +
                    "|" +
                    NavigationFeature.clean(actualManeuver)
            val changed: Boolean = !identity.equals(mainPreviewIdentity)
            val previousMeters: Float = mainPreviewLastPositiveMeters
            val jumpedToNextEvent: Boolean =
                !changed &&
                    meters > revealMeters &&
                    previousMeters > 0f &&
                    (meters - previousMeters >= Math.max(100f, revealMeters * 0.5f))
            if (changed || jumpedToNextEvent) {
                mainPreviewIdentity = identity
                mainPreviewRevealLatched = false
                mainPreviewLastPositiveMeters = -1f
                lastMainPreviewDecisionKey = ""
                if (jumpedToNextEvent) {
                    AppLog.navigation(
                        app,
                        ("NAV_MAIN_PREVIEW_RESET" +
                            " reason=distance_jump" +
                            " main=" +
                            NavigationFeature.clean(actualManeuver) +
                            " previous=" +
                            previousMeters +
                            " current=" +
                            meters),
                    )
                }
            }
            if (meters > 0f && java.lang.Float.isFinite(meters)) {
                mainPreviewLastPositiveMeters = meters
            }
        }

        val decision: MainManeuverPreviewPolicy.Decision? =
            MainManeuverPreviewPolicy.decide(
                enabled,
                normalMode,
                resolvedMicro,
                usableMain,
                actualStraight,
                meters,
                revealMeters,
                mainPreviewRevealLatched,
            )
        if (
            decision == kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.ACTUAL &&
                enabled &&
                normalMode &&
                !resolvedMicro &&
                usableMain &&
                !actualStraight &&
                meters > 0f &&
                java.lang.Float.isFinite(meters) &&
                meters <= revealMeters
        ) {
            mainPreviewRevealLatched = true
        }
        if (decision == kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.ACTUAL) {
            val decisionKey: String =
                decision.toString() + "|" + NavigationFeature.first(mainPreviewIdentity, "-")
            if (
                !decisionKey.equals(lastMainPreviewDecisionKey) &&
                    enabled &&
                    normalMode &&
                    !resolvedMicro &&
                    usableMain
            ) {
                lastMainPreviewDecisionKey = decisionKey
                AppLog.navigation(
                    app,
                    ("NAV_MAIN_PREVIEW" +
                        " decision=ACTUAL" +
                        " route=" +
                        NavigationFeature.first(activeRouteId, "-") +
                        " main=" +
                        NavigationFeature.clean(actualManeuver) +
                        " distance=" +
                        NavigationFeature.clusterDistanceText(distance, km) +
                        " reveal=" +
                        revealMeters +
                        " latched=" +
                        mainPreviewRevealLatched),
                )
            }
        }
        return decision
    }

    private fun navigationTxAllowed(imageId: String?): Boolean {
        val cleanImageId = NavigationFeature.clean(imageId)
        if (!state.active) {
            AppLog.line(
                app,
                ("Navigation TX blocked while inactive: " +
                    cleanImageId +
                    " visual=" +
                    NavigationFeature.clean(activeClusterVisual)),
            )
            return false
        }
        if (state.finishReached && !NavigationFeature.isFinishManeuver(cleanImageId)) {
            AppLog.line(app, "Navigation TX blocked after finish: " + cleanImageId)
            return false
        }
        return true
    }

    private fun maneuverTxBlockedByRerouting(imageId: String?): Boolean {
        if (NavigationFeature.isFinishManeuver(imageId)) return false
        if (!routeReroutingActive(System.currentTimeMillis())) return false
        sendRouteReroutingVisual()
        AppLog.line(
            app,
            "Navigation maneuver TX blocked by rerouting: " + NavigationFeature.clean(imageId),
        )
        return true
    }

    private fun sendEtaIfChanged(distance: Float, km: Boolean) {
        val sendDistance: Float = NavigationFeature.clusterDistanceValue(distance, km)
        val key: String = sendDistance.toString() + "|" + km
        if (key.equals(lastSentEtaKey)) return
        lastSentEtaKey = key
        sender.sendEta(sendDistance, km)
    }

    private fun sendEtaTimeIfChanged() {
        val remainingMode: Boolean =
            AppSettings.navEtaTimeMode(app) == AppSettings.NAV_ETA_TIME_REMAINING
        var hourMinute: IntArray? =
            if (remainingMode) NavigationFeature.durationHourMinute(state.routeTime) else null
        if (hourMinute == null) hourMinute = NavigationFeature.arrivalHourMinute(state.arrivalTime)
        if (hourMinute == null) return
        val key: String =
            (if (remainingMode) "remaining|" else "arrival|") +
                hourMinute.get(0) +
                ":" +
                hourMinute.get(1)
        if (key.equals(lastSentEtaTimeKey)) return
        lastSentEtaTimeKey = key
        sender.sendEtaTime(hourMinute.get(0), hourMinute.get(1))
    }

    @kotlin.jvm.Synchronized
    fun resendKnownRouteData() {
        expireStaleYandexRoadSpeedLimit(System.currentTimeMillis(), "resend")
        // A cached sign is never replayed by route/background/transport refreshes.
        if (!state.active) return
        if (!state.routeDistance.isNullOrEmpty()) {
            sendEtaIfChanged(
                NavigationFeature.distanceValue(state.routeDistance),
                NavigationFeature.isKm(state.routeDistance),
            )
        }
        if (!state.arrivalTime.isNullOrEmpty() || !state.routeTime.isNullOrEmpty()) {
            sendEtaTimeIfChanged()
        }
    }

    /** A changed positive limit sends immediately; a known fresh limit repeats every 15 seconds. */
    private fun sendSpeedLimitFrame(kmh: Int): AdapterTxOutcome? {
        val value: Int = Math.max(0, Math.min(255, kmh))
        val allowed: Boolean = roadSpeedLimitOutputAllowed()
        val now: Long = SystemClock.elapsedRealtime()
        if (allowed && value > 0) {
            roadSpeedLimitRepeat.observeFresh(value, now)
            knownRoadSpeedLimitSource = NavigationFeature.navigationSourceFamily(state.source)
            knownRoadSpeedLimitSourceMode = AppSettings.navSourceMode(app)
            scheduleRoadSpeedLimitRepeat()
        } else {
            cancelRoadSpeedLimitDelivery()
        }
        handler.removeCallbacks(roadSpeedLimitRetry)
        val previousObserved: Int = roadSpeedLimitTx.lastObservedValue()
        val accepted: Boolean = roadSpeedLimitTx.acceptFreshSample(value, allowed, now)
        logRoadSpeedLimitDecision(value, allowed, accepted, previousObserved)
        if (!accepted) return AdapterTxOutcome.BLOCKED
        pendingRoadSpeedLimitSource = NavigationFeature.navigationSourceFamily(state.source)
        pendingRoadSpeedLimitSourceMode = AppSettings.navSourceMode(app)
        pendingRoadSpeedLimitTrigger = if (previousObserved == value) "fresh_retry" else "change"
        return attemptPendingRoadSpeedLimit(false)
    }

    private fun repeatRoadSpeedLimit() {
        if (!AppSettings.navSpeedLimitRepeatEnabled(app)) return
        val value: Int = roadSpeedLimitRepeat.dueValue(SystemClock.elapsedRealtime())
        if (value <= 0) {
            scheduleRoadSpeedLimitRepeat()
            return
        }
        if (
            !roadSpeedLimitContextMatches(
                value,
                knownRoadSpeedLimitSource,
                knownRoadSpeedLimitSourceMode,
            )
        ) {
            cancelRoadSpeedLimitDelivery()
            return
        }
        // Timer ticks never renew source freshness. A failed attempt gets the existing bounded
        // retry, while the next periodic attempt still requires a fresh positive source sample.
        val now: Long = SystemClock.elapsedRealtime()
        roadSpeedLimitRepeat.scheduleNext(value, now)
        if (roadSpeedLimitTx.beginRepeat(value, true, now)) {
            pendingRoadSpeedLimitSource = knownRoadSpeedLimitSource
            pendingRoadSpeedLimitSourceMode = knownRoadSpeedLimitSourceMode
            pendingRoadSpeedLimitTrigger = "periodic_15s"
            attemptPendingRoadSpeedLimit(false)
        }
        scheduleRoadSpeedLimitRepeat()
    }

    private fun roadSpeedLimitContextMatches(value: Int, source: Int, sourceMode: Int): Boolean {
        return roadSpeedLimitOutputAllowed() &&
            NavigationFeature.speedNumber(state.speedLimit) == value &&
            source == NavigationFeature.navigationSourceFamily(state.source) &&
            sourceMode == AppSettings.navSourceMode(app) &&
            (source == NavigationSourcePolicy.SOURCE_NONE ||
                NavigationSourcePolicy.ingressAllowed(
                    AppSettings.navigationEnabled(app),
                    sourceMode,
                    source,
                ))
    }

    private fun scheduleRoadSpeedLimitRepeat() {
        handler.removeCallbacks(roadSpeedLimitRepeatTask)
        if (!AppSettings.navSpeedLimitRepeatEnabled(app)) return
        val delay: Long = roadSpeedLimitRepeat.delayUntilNext(SystemClock.elapsedRealtime())
        if (delay > 0L) handler.postDelayed(roadSpeedLimitRepeatTask, delay)
    }

    private fun attemptPendingRoadSpeedLimit(retry: Boolean): AdapterTxOutcome? {
        handler.removeCallbacks(roadSpeedLimitRetry)
        if (
            "periodic_15s".equals(pendingRoadSpeedLimitTrigger) &&
                !AppSettings.navSpeedLimitRepeatEnabled(app)
        ) {
            roadSpeedLimitTx.cancelPending()
            return AdapterTxOutcome.BLOCKED
        }
        val value: Int =
            roadSpeedLimitTx.pendingValue(
                SystemClock.elapsedRealtime(),
                roadSpeedLimitOutputAllowed(),
            )
        if (value <= 0) return AdapterTxOutcome.BLOCKED
        if (
            !roadSpeedLimitContextMatches(
                value,
                pendingRoadSpeedLimitSource,
                pendingRoadSpeedLimitSourceMode,
            ) || roadSpeedLimitRepeat.delayUntilNext(SystemClock.elapsedRealtime()) <= 0L
        ) {
            cancelRoadSpeedLimitDelivery()
            return AdapterTxOutcome.BLOCKED
        }
        val gateway: AdapterGateway? = AdapterGateway.get(app)
        val epochBefore: Long = gateway!!.connectionEpoch()
        val outcome: AdapterTxOutcome? = sender.sendSpeedLimit(value)
        AppLog.navigation(
            app,
            ("NAV_SPEED_LIMIT_DELIVERY value=" +
                value +
                " trigger=" +
                pendingRoadSpeedLimitTrigger +
                " retry=" +
                retry +
                " outcome=" +
                outcome +
                " epoch=" +
                epochBefore +
                "->" +
                gateway.connectionEpoch()),
        )
        if (outcome == AdapterTxOutcome.WRITTEN) {
            roadSpeedLimitTx.written(value)
            roadSpeedLimitRepeat.scheduleNext(value, SystemClock.elapsedRealtime())
            scheduleRoadSpeedLimitRepeat()
        } else if (
            roadSpeedLimitTx.pendingValue(
                SystemClock.elapsedRealtime(),
                roadSpeedLimitOutputAllowed(),
            ) > 0
        ) {
            handler.postDelayed(roadSpeedLimitRetry, 250L)
        }
        return outcome
    }

    private fun logRoadSpeedLimitDecision(
        value: Int,
        allowed: Boolean,
        accepted: Boolean,
        previousObserved: Int,
    ) {
        val decision: String =
            ("value=" +
                value +
                " previousObserved=" +
                previousObserved +
                " lastWritten=" +
                roadSpeedLimitTx.lastWrittenValue() +
                " action=" +
                (if (accepted) "send" else if (!allowed) "disabled" else "duplicate"))
        val now: Long = SystemClock.elapsedRealtime()
        if (
            decision.equals(lastRoadSpeedLimitDecision) &&
                now - lastRoadSpeedLimitDecisionAt < 10000L
        )
            return
        lastRoadSpeedLimitDecision = decision
        lastRoadSpeedLimitDecisionAt = now
        AppLog.navigation(app, "NAV_SPEED_LIMIT_DECISION " + decision)
    }

    private fun cancelRoadSpeedLimitDelivery() {
        handler.removeCallbacks(roadSpeedLimitRetry)
        handler.removeCallbacks(roadSpeedLimitRepeatTask)
        roadSpeedLimitTx.cancelPending()
        roadSpeedLimitRepeat.cancel()
    }

    private fun roadSpeedLimitOutputAllowed(): Boolean {
        return AppSettings.navigationEnabled(app) &&
            (!AppSettings.navSpeedLimitRouteOnly(app) ||
                (state.active && !state.finishReached && !restoredRouteAwaitingConfirmation))
    }

    /** Applying a mode never replays a stored sign; the next fresh sample uses it. */
    @kotlin.jvm.Synchronized
    fun onSpeedLimitSettingsChanged() {
        cancelRoadSpeedLimitDelivery()
        roadSpeedLimitTx.setAllowed(roadSpeedLimitOutputAllowed())
    }

    private fun sendRouteLoadingVisual() {
        if (routeReroutingActive(System.currentTimeMillis())) {
            sendRouteReroutingVisual()
            AppLog.line(app, "Navigation loading visual suppressed by rerouting")
            return
        }
        clusterTx.markCustomManeuverVisual("route_loading", "route_loading / text", true)
        sendNavigationText("Загрузка маршрута")
    }

    private fun startRouteReroutingHold(source: String?) {
        startRouteReroutingHold(source, false)
    }

    private fun startRouteReroutingHold(source: String?, allowInactive: Boolean) {
        if (state.finishReached) return
        if (!state.active && !allowInactive) return
        resetMainManeuverPreview("rerouting_" + NavigationFeature.clean(source))
        resetManeuverProgress()
        val now: Long = System.currentTimeMillis()
        routeReroutingMinUntil =
            Math.max(routeReroutingMinUntil, now + NavigationFeature.ROUTE_REROUTING_MIN_HOLD_MS)
        routeReroutingUntil =
            Math.max(routeReroutingUntil, now + NavigationFeature.ROUTE_REROUTING_HOLD_MS)
        val generation: Int = ++routeReroutingGeneration
        resetNavigationTextCache()
        AppLog.line(app, "Navigation route rerouting hold: " + NavigationFeature.clean(source))
        if (state.active) sendRouteReroutingVisual()
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (generation != routeReroutingGeneration) return@postDelayed
                    finishRouteReroutingIfReady(
                        System.currentTimeMillis(),
                        NavigationFeature.clean(source) + "_min_ready",
                    )
                }
            },
            NavigationFeature.ROUTE_REROUTING_MIN_HOLD_MS,
        )
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (generation != routeReroutingGeneration) return@postDelayed
                    if (System.currentTimeMillis() < routeReroutingUntil) return@postDelayed
                    routeReroutingUntil = 0L
                    routeReroutingMinUntil = 0L
                    if (!routeReroutingVisualActive()) return@postDelayed
                    clearClusterVisualHold()
                    resetNavigationTextCache()
                    resendCurrentVisual()
                    sendConfiguredText()
                    AppLog.line(app, "Navigation route rerouting hold finished")
                }
            },
            NavigationFeature.ROUTE_REROUTING_HOLD_MS,
        )
    }

    private fun routeReroutingActive(now: Long): Boolean {
        if (!state.active || state.finishReached || routeReroutingUntil <= 0L) {
            routeReroutingUntil = 0L
            routeReroutingMinUntil = 0L
            return false
        }
        if (finishRouteReroutingIfReady(now, "route_data_ready")) return false
        if (now > routeReroutingUntil) {
            routeReroutingUntil = 0L
            routeReroutingMinUntil = 0L
            return false
        }
        return true
    }

    private fun finishRouteReroutingIfReady(now: Long, source: String?): Boolean {
        if (routeReroutingUntil <= 0L || now < routeReroutingMinUntil) return false
        if (!state.active || state.finishReached || state.loading() || !routeDataReady())
            return false
        routeReroutingGeneration++
        routeReroutingUntil = 0L
        routeReroutingMinUntil = 0L
        if (routeReroutingVisualActive()) {
            clearClusterVisualHold()
            resetNavigationTextCache()
            resendCurrentVisual()
            sendConfiguredText()
        }
        AppLog.line(app, "Navigation route rerouting ready: " + NavigationFeature.clean(source))
        return true
    }

    private fun sendRouteReroutingVisual() {
        clusterTx.markCustomManeuverVisual(
            "route_rerouting",
            "route_loading / rerouting_text",
            true,
        )
        sendNavigationText("Перестроение маршрута")
    }

    private fun routeReroutingVisualActive(): Boolean {
        val visual =
            NavigationFeature.clean(
                    NavigationFeature.first(activeClusterVisual, state.clusterVisual)
                )
                .lowercase(Locale.US)
        return visual.contains("rerouting_text")
    }

    private fun scheduleRouteLoadingFallback() {
        val generation: Int = ++routeLoadingFallbackGeneration
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (generation != routeLoadingFallbackGeneration) return@postDelayed
                    if (!routeLoadingFallbackReady() || !state.maneuver.isNullOrEmpty())
                        return@postDelayed
                    if (!hasConfirmedRouteMetrics()) {
                        AppLog.line(app, "Navigation loading stopped without route metrics")
                        forceInactive("route_loading_no_metrics")
                        return@postDelayed
                    }
                    if (sendDirectionToFinishForLoading(true)) {
                        AppLog.line(app, "Navigation loading fallback: direction to finish")
                    } else {
                        sendFinishDirectionPlaceholder(true)
                        AppLog.line(app, "Navigation loading fallback: placeholder direction")
                    }
                }
            },
            NavigationFeature.ROUTE_WAIT_MAX_MS,
        )
    }

    private fun sendFinishDirectionPlaceholder(force: Boolean) {
        targetFinishDirectionStep = NavigationFeature.normalizeCompassStep(0)
        activeFinishDirectionDistance = 0f
        activeFinishDirectionKm = false
        lastFinishDirectionReason = "finish_direction_placeholder_straight"
        sendFinishDirectionStep(
            targetFinishDirectionStep,
            targetFinishDirectionStep,
            activeFinishDirectionDistance,
            activeFinishDirectionKm,
            lastFinishDirectionReason,
            force,
        )
    }

    private fun sendDirectionToFinishIfNeeded(force: Boolean): Boolean {
        if (!finishDirectionShouldOverride()) return false
        return sendDirectionToFinishFrame(force, finishDirectionReason())
    }

    private fun sendDirectionToFinishForLoading(force: Boolean): Boolean {
        if (!state.active || state.finishReached || !state.loading()) return false
        if (!state.maneuver.isNullOrEmpty()) return false
        if (!hasConfirmedRouteMetrics()) return false
        return sendDirectionToFinishFrame(force, "loading_finish_direction")
    }

    private fun sendDirectionToFinishFrame(force: Boolean, reason: String?): Boolean {
        if (!finishPointKnown() || !gpsPointKnown()) return false
        val now: Long = System.currentTimeMillis()
        if (!force && now - lastFinishDirectionAt < 700L) return true
        val absoluteBearing: Float = bearingToFinish()
        var heading: Float = finishReferenceHeading()
        val straightFallback: Boolean = java.lang.Float.isNaN(heading)
        if (straightFallback) {
            heading = absoluteBearing
        }
        val step: Int = NavigationFeature.compassDirectionStep(absoluteBearing - heading)
        val distanceText = NavigationFeature.first(state.routeDistance, state.maneuverDistance)
        var km: Boolean = NavigationFeature.isKm(distanceText)
        var distance: Float = NavigationFeature.distanceValue(distanceText)
        if (distance <= 0f) {
            distance = distanceToFinishMeters()
            km = false
        }
        val sendDistance: Float = NavigationFeature.clusterDistanceValue(distance, km)
        sendDirectionToFinishAnimated(
            step,
            sendDistance,
            km,
            force,
            if (straightFallback) NavigationFeature.clean(reason) + "_geo_straight" else reason,
        )
        return true
    }

    private fun sendDirectionToFinishAnimated(
        targetStep: Int,
        sendDistance: Float,
        km: Boolean,
        force: Boolean,
        reason: String?,
    ) {
        targetFinishDirectionStep = NavigationFeature.normalizeCompassStep(targetStep)
        activeFinishDirectionDistance = sendDistance
        activeFinishDirectionKm = km
        lastFinishDirectionReason = NavigationFeature.clean(reason)
        if (displayedFinishDirectionStep < 0) {
            if (lastKnownFinishDirectionStep < 0) {
                sendFinishDirectionStep(
                    targetFinishDirectionStep,
                    targetFinishDirectionStep,
                    sendDistance,
                    km,
                    reason,
                    force,
                )
                return
            }
            displayedFinishDirectionStep =
                NavigationFeature.normalizeCompassStep(lastKnownFinishDirectionStep)
        }
        val delta: Int =
            NavigationFeature.compassStepDelta(
                displayedFinishDirectionStep,
                targetFinishDirectionStep,
            )
        if (delta <= 3) {
            sendFinishDirectionStep(
                targetFinishDirectionStep,
                targetFinishDirectionStep,
                sendDistance,
                km,
                reason,
                force,
            )
            return
        }
        val next: Int =
            if (displayedFinishDirectionStep == targetFinishDirectionStep) targetFinishDirectionStep
            else
                NavigationFeature.nextCompassStep(
                    displayedFinishDirectionStep,
                    targetFinishDirectionStep,
                )
        sendFinishDirectionStep(next, targetFinishDirectionStep, sendDistance, km, reason, force)
        scheduleFinishDirectionAnimation()
    }

    private fun sendFinishDirectionStep(
        uiStep: Int,
        targetStep: Int,
        sendDistance: Float,
        km: Boolean,
        reason: String?,
        force: Boolean,
    ) {
        val step: Int = NavigationFeature.normalizeCompassStep(uiStep)
        val target: Int = NavigationFeature.normalizeCompassStep(targetStep)
        val now: Long = System.currentTimeMillis()
        val key: String =
            (step.toString() + "|" + target + "|" + Math.round(sendDistance * 10f) + "|" + km)
        val age: Long = now - lastFinishDirectionAt
        if (
            !force &&
                key.equals(lastSentFinishDirectionKey) &&
                age < NavigationFeature.FINISH_DIRECTION_DUPLICATE_TX_MS
        )
            return
        lastSentFinishDirectionKey = key
        lastFinishDirectionAt = now
        displayedFinishDirectionStep = step
        lastKnownFinishDirectionStep = step
        val visual: String =
            ("context_ra_direction_to_finish / step=" +
                step +
                " target=" +
                target +
                " / " +
                NavigationFeature.clusterDistanceText(sendDistance, km))
        clusterTx.markCustomManeuverVisual("finish_direction|" + key, visual, true)
        sender.sendDirectionToFinish(step, sendDistance, km)
    }

    private fun scheduleFinishDirectionAnimation() {
        if (!finishDirectionAnimationAllowed()) {
            return
        }
        if (displayedFinishDirectionStep == targetFinishDirectionStep) {
            return
        }
        if (finishDirectionAnimationRunning) {
            return
        }
        finishDirectionAnimationRunning = true
        handler.postDelayed(
            finishDirectionAnimator,
            NavigationFeature.FINISH_DIRECTION_ANIMATION_STEP_MS,
        )
    }

    private fun finishDirectionAnimationAllowed(): Boolean {
        return state.active &&
            !state.finishReached &&
            targetFinishDirectionStep >= 0 &&
            finishDirectionShouldOverride()
    }

    private fun resetFinishDirectionAnimation() {
        handler.removeCallbacks(finishDirectionAnimator)
        finishDirectionAnimationRunning = false
        displayedFinishDirectionStep = -1
        targetFinishDirectionStep = -1
        activeFinishDirectionDistance = 0f
        activeFinishDirectionKm = false
        lastFinishDirectionReason = ""
        lastSentFinishDirectionKey = ""
        lastFinishDirectionAt = 0L
    }

    private fun finishDirectionShouldOverride(): Boolean {
        return state.active &&
            !state.finishReached &&
            (NavigationModeSettings.isFinishDirection(app) ||
                (AppSettings.navFinishCompassAuto(app) && finalSegmentFinishDirectionActive))
    }

    private fun finishDirectionReason(): String {
        if (finalSegmentFinishDirectionActive && !NavigationModeSettings.isFinishDirection(app)) {
            return "final_segment_finish_direction"
        }
        return "finish_direction_mode"
    }

    private fun finishDirectionWithinLead(): Boolean {
        val leadMeters: Int = AppSettings.navFinishDirectionLeadMeters(app)
        if (leadMeters <= 0) return true
        val routeMeters: Float = NavigationFeature.distanceMeters(state.routeDistance)
        if (routeMeters > 0f) return routeMeters <= leadMeters
        val directMeters: Float = distanceToFinishMeters()
        if (directMeters > 0f) return directMeters <= leadMeters
        val maneuverMeters: Float = NavigationFeature.distanceMeters(state.maneuverDistance)
        return maneuverMeters > 0f && maneuverMeters <= leadMeters
    }

    private fun maybeStartFinalSegmentFinishDirection(
        routeDistance: String?,
        previousManeuver: String,
        previousManeuverDistance: String?,
        incomingManeuver: String,
        incomingManeuverDistance: String?,
        source: String?,
        now: Long,
    ): Boolean {
        if (!state.active || state.finishReached || NavigationModeSettings.isFinishDirection(app)) {
            return false
        }
        if (!AppSettings.navFinishCompassAuto(app)) {
            clearFinalSegmentFinishDirection(
                "finish compass auto off " + NavigationFeature.clean(source)
            )
            return false
        }
        val routeText =
            NavigationFeature.first(
                NavigationFeature.normalizeDistanceText(routeDistance),
                state.routeDistance,
            )
        val routeMeters: Float = NavigationFeature.distanceMeters(routeText)
        if (routeMeters > NavigationFeature.NO_MORE_MANEUVER_FINISH_DIRECTION_METERS) {
            clearFinalSegmentFinishDirection(
                "route beyond final segment " + NavigationFeature.clean(source)
            )
            return false
        }
        if (routeMeters <= NavigationFeature.AUTO_FINISH_ROUTE_METERS || routeMeters <= 0f) {
            return finalSegmentFinishDirectionActive
        }
        val maneuver =
            NavigationFeature.first(
                NavigationFeature.clean(incomingManeuver),
                NavigationFeature.clean(previousManeuver),
            )
        val incomingDistance = NavigationFeature.normalizeDistanceText(incomingManeuverDistance)
        val previousDistance = NavigationFeature.normalizeDistanceText(previousManeuverDistance)
        val decisionDistance =
            NavigationFeature.first(
                NavigationFeature.nonZeroDistance(incomingDistance),
                NavigationFeature.nonZeroDistance(previousDistance),
            )
        val incomingManeuverUsable: Boolean =
            NavigationFeature.isUsableManeuver(incomingManeuver) &&
                !NavigationFeature.isFinishManeuver(incomingManeuver)
        val previousManeuverUsable: Boolean =
            NavigationFeature.isUsableManeuver(previousManeuver) &&
                !NavigationFeature.isFinishManeuver(previousManeuver)
        val noIncomingManeuver: Boolean =
            !incomingManeuverUsable &&
                NavigationFeature.nonZeroDistance(incomingDistance).isNullOrEmpty()
        if (finalSegmentFinishDirectionActive) {
            if (routeMeters <= NavigationFeature.FINAL_SEGMENT_FINISH_DIRECTION_METERS) return true
            if (finalSegmentNewManeuverArrived(maneuver!!, decisionDistance, routeMeters)) {
                clearFinalSegmentFinishDirection(
                    "new maneuver before finish " + NavigationFeature.clean(source)
                )
                return false
            }
            return true
        }
        if (routeMeters <= NavigationFeature.FINAL_SEGMENT_FINISH_DIRECTION_METERS) {
            startFinalSegmentFinishDirection(
                NavigationFeature.first(maneuver, "final_segment"),
                routeText!!,
                source,
                "route_within_300m",
                now,
            )
            return true
        }
        if (
            !NavigationFeature.isUsableManeuver(maneuver!!) ||
                NavigationFeature.isFinishManeuver(maneuver)
        )
            return false
        var completionReason: String = ""
        if (
            previousManeuverUsable &&
                noIncomingManeuver &&
                NavigationFeature.distanceMeters(previousDistance) <=
                    NavigationFeature.NO_MORE_MANEUVER_FINISH_DIRECTION_METERS
        ) {
            completionReason = "last_maneuver_no_more"
        }
        if (completionReason.isNullOrEmpty()) {
            completionReason = finalSegmentManeuverCompletionReason(decisionDistance, routeMeters)
        }
        if (completionReason.isNullOrEmpty()) return false
        if (
            !"maneuver_beyond_route".equals(completionReason) &&
                !"last_maneuver_no_more".equals(completionReason) &&
                !NavigationFeature.isUsableManeuver(previousManeuver) &&
                NavigationFeature.nonZeroDistance(previousManeuverDistance).isNullOrEmpty()
        ) {
            return false
        }
        startFinalSegmentFinishDirection(maneuver, routeText!!, source, completionReason, now)
        return true
    }

    private fun finalSegmentManeuverCompletionReason(
        incomingDistance: String?,
        routeMeters: Float,
    ): String {
        if (NavigationFeature.clean(incomingDistance).isNullOrEmpty()) return ""
        val maneuverMeters: Float = NavigationFeature.distanceMeters(incomingDistance)
        if (maneuverMeters <= NavigationFeature.FINAL_SEGMENT_MANEUVER_DONE_METERS)
            return "maneuver_zero"
        if (
            routeMeters > NavigationFeature.AUTO_FINISH_ROUTE_METERS &&
                routeMeters <= NavigationFeature.NO_MORE_MANEUVER_FINISH_DIRECTION_METERS &&
                NavigationFeature.maneuverBeyondRoute(maneuverMeters, routeMeters)
        ) {
            return "maneuver_beyond_route"
        }
        return ""
    }

    private fun finalSegmentNewManeuverArrived(
        maneuver: String,
        distanceText: String?,
        routeMeters: Float,
    ): Boolean {
        if (!finalSegmentFinishDirectionActive) return false
        if (
            routeMeters > 0f &&
                routeMeters <= NavigationFeature.FINAL_SEGMENT_FINISH_DIRECTION_METERS
        )
            return false
        if (
            !NavigationFeature.isUsableManeuver(maneuver) ||
                NavigationFeature.isFinishManeuver(maneuver)
        )
            return false
        val distance = NavigationFeature.nonZeroDistance(distanceText)
        if (distance.isNullOrEmpty()) return false
        val meters: Float = NavigationFeature.distanceMeters(distance)
        if (routeMeters > 0f && NavigationFeature.maneuverBeyondRoute(meters, routeMeters))
            return false
        if (!NavigationFeature.sameManeuverFamily(finalSegmentManeuver, maneuver)) return true
        return meters > NavigationFeature.FINAL_SEGMENT_NEW_MANEUVER_METERS
    }

    private fun startFinalSegmentFinishDirection(
        maneuver: String?,
        routeDistance: String,
        source: String?,
        reason: String?,
        now: Long,
    ) {
        finalSegmentFinishDirectionActive = true
        finalSegmentManeuver = NavigationFeature.clean(maneuver)
        clearLaneHintHold()
        clearRoundaboutExitHold()
        clearClusterVisualHold()
        resetManeuverProgress()
        resetFinishDirectionAnimation()
        state =
            NavigationState(
                    true,
                    false,
                    state.speedExceeded,
                    "",
                    "",
                    "",
                    NavigationFeature.first(routeDistance, state.routeDistance),
                    state.routeTime,
                    state.arrivalTime,
                    state.currentStreet,
                    state.nextStreet,
                    state.finishStreet,
                    state.speedLimit,
                    state.currentSpeed,
                    NavigationFeature.clean(source),
                    now,
                )
                .withEventHint(activeEventHint, activeEventSource, now)
        publishNavigationState()
        AppLog.line(
            app,
            ("Navigation final segment finish direction: route=" +
                NavigationFeature.first(routeDistance, "-") +
                " after=" +
                NavigationFeature.first(finalSegmentManeuver, "-") +
                " reason=" +
                NavigationFeature.clean(reason) +
                " source=" +
                NavigationFeature.clean(source)),
        )
    }

    private fun clearFinalSegmentFinishDirection(reason: String?) {
        if (!finalSegmentFinishDirectionActive && finalSegmentManeuver.isNullOrEmpty()) return
        finalSegmentFinishDirectionActive = false
        finalSegmentManeuver = ""
        resetFinishDirectionAnimation()
        AppLog.line(
            app,
            "Navigation final segment finish direction cleared: " + NavigationFeature.clean(reason),
        )
    }

    private fun distanceToFinishMeters(): Float {
        if (!finishPointKnown() || !gpsPointKnown()) return -1f
        val from: Location = Location("kia_nav_current")
        from.setLatitude(lastGpsLatitude)
        from.setLongitude(lastGpsLongitude)
        val to: Location = Location("kia_nav_finish")
        to.setLatitude(finishLatitude)
        to.setLongitude(finishLongitude)
        return from.distanceTo(to)
    }

    private fun finishReferenceHeading(): Float {
        val now: Long = System.currentTimeMillis()
        val yandexFresh: Boolean =
            !java.lang.Float.isNaN(lastYandexHeading) &&
                now - lastYandexHeadingAt <= NavigationFeature.YANDEX_HEADING_FRESH_MS
        if (yandexFresh) return lastYandexHeading
        val courseFresh: Boolean =
            !java.lang.Float.isNaN(lastGpsCourseBearing) &&
                now - lastGpsCourseBearingAt <= NavigationFeature.GPS_COURSE_FRESH_MS
        if (courseFresh) return lastGpsCourseBearing
        val deviceFresh: Boolean =
            !java.lang.Float.isNaN(lastDeviceHeading) && now - lastDeviceHeadingAt <= 2500L
        val gpsFresh: Boolean =
            !java.lang.Float.isNaN(lastGpsBearing) && now - lastGpsBearingAt <= 5000L
        if (deviceFresh && gpsFresh && lastGpsSpeedMetersPerSecond >= 1.4f) {
            val gpsWeight: Float =
                Math.max(0.18f, Math.min(0.65f, (lastGpsSpeedMetersPerSecond - 1.4f) / 7.0f))
            return NavigationFeature.blendDegrees(lastDeviceHeading, lastGpsBearing, gpsWeight)
        }
        if (deviceFresh) return lastDeviceHeading
        return if (gpsFresh) lastGpsBearing else Float.NaN
    }

    private fun maybeAutoFinishStaleNearDestination(now: Long): Boolean {
        if (!state.active || state.finishReached) return false
        val routeMeters: Float = NavigationFeature.distanceMeters(state.routeDistance)
        val directMeters: Float = distanceToFinishMeters()
        val nearByRoute: Boolean =
            routeMeters > 0f && routeMeters <= NavigationFeature.AUTO_FINISH_ROUTE_METERS
        val nearByGps: Boolean =
            directMeters > 0f && directMeters <= NavigationFeature.AUTO_FINISH_GPS_METERS
        val routeStale: Boolean =
            lastRouteGuidanceAt <= 0L ||
                now - lastRouteGuidanceAt >= NavigationFeature.FINISH_STALE_AUTO_MS
        if (!routeStale || !nearByRoute || !nearByGps) return false
        markFinishReached("auto_finish_stale_near_destination")
        AppLog.line(
            app,
            ("Navigation auto finish: route=" +
                routeMeters +
                " gps=" +
                directMeters +
                " stale=" +
                routeStale),
        )
        return true
    }

    private fun maybeRecoverStaleFinishFromMovement() {
        if (!state.active || !state.finishReached) return
        val routeMeters: Float = NavigationFeature.distanceMeters(state.routeDistance)
        val directMeters: Float = distanceToFinishMeters()
        val routeKnown: Boolean = routeMeters > 0f
        val gpsKnown: Boolean = directMeters >= 0f
        if (!routeKnown && !gpsKnown) return
        val now: Long = System.currentTimeMillis()
        if (now <= finishRecoverySuppressUntil) return
        val routeAway: Boolean =
            routeKnown &&
                routeMeters >
                    NavigationFeature.AUTO_FINISH_ROUTE_METERS +
                        NavigationFeature.FINISH_STALE_RECOVERY_MARGIN_METERS
        val gpsAway: Boolean =
            gpsKnown &&
                directMeters >
                    NavigationFeature.AUTO_FINISH_GPS_METERS +
                        NavigationFeature.FINISH_STALE_RECOVERY_MARGIN_METERS
        val shouldRecover: Boolean =
            if (routeKnown && gpsKnown) routeAway && gpsAway else routeAway || gpsAway
        if (!shouldRecover) return
        cancelFinishHold()
        clearManeuverTextHint()
        clearStaleManeuverVisual("recover stale finish state")
        AppLog.line(
            app,
            ("Navigation stale finish recovered: route=" + routeMeters + " gps=" + directMeters),
        )
    }

    private fun finishPositionAllowsHold(source: String?, distanceText: String?): Boolean {
        if (!finishPointKnown() || !gpsPointKnown()) return true
        val directMeters: Float = distanceToFinishMeters()
        if (directMeters <= 0f) return true
        val allowed: Boolean =
            directMeters <=
                NavigationFeature.AUTO_FINISH_GPS_METERS +
                    NavigationFeature.FINISH_STALE_RECOVERY_MARGIN_METERS
        if (!allowed) {
            AppLog.line(
                app,
                ("Navigation ignored finish by GPS distance: route=" +
                    NavigationFeature.clean(distanceText) +
                    " gps=" +
                    directMeters +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
        return allowed
    }

    private fun markFinishReached(source: String?) {
        val now: Long = System.currentTimeMillis()
        val preserveRoadContext: Boolean = shouldPreserveRoadContext(source, now)
        val retainedSpeedLimit: String? = if (preserveRoadContext) state.speedLimit else ""
        val clearRoadSpeedLimit: Boolean =
            !preserveRoadContext && (!state.speedLimit.isNullOrEmpty() || lastSentSpeedLimit > 0)
        yandexFinishSuppressed = true
        yandexWatchdogGeneration++
        resetMainManeuverPreview("finish")
        clearFinalSegmentFinishDirection("finish reached " + NavigationFeature.clean(source))
        finishRecoverySuppressUntil =
            if (NavigationFeature.confirmedFinishHoldSource(source))
                now + NavigationFeature.FINISH_HOLD_MS
            else 0L
        if (!preserveRoadContext) {
            speedLimitExpiryGeneration++
            lastYandexRoadSpeedLimitAt = 0L
            yandexSpeedLimitClearCandidateAt = 0L
            clearOverspeedTextWindow()
        }
        state =
            NavigationState(
                true,
                true,
                preserveRoadContext && state.speedExceeded,
                "context_ra_finish",
                NavigationFeature.maneuverLabel("context_ra_finish"),
                "",
                state.routeDistance,
                state.routeTime,
                state.arrivalTime,
                state.currentStreet,
                state.nextStreet,
                state.finishStreet,
                retainedSpeedLimit,
                state.currentSpeed,
                if (preserveRoadContext) state.source else NavigationFeature.clean(source),
                now,
            )
        publishNavigationState()
        if (preserveRoadContext) {
            AppLog.navigation(
                app,
                ("NAV_SPEED_LIMIT_PRESERVED reason=finish_" +
                    NavigationFeature.clean(source) +
                    " value=" +
                    NavigationFeature.clean(retainedSpeedLimit)),
            )
        } else if (clearRoadSpeedLimit) {
            lastSentSpeedLimit = 0
            AppLog.navigation(
                app,
                "NAV_SPEED_LIMIT_UNAVAILABLE reason=finish_" + NavigationFeature.clean(source),
            )
        }
        startFinishHold()
    }

    private fun resendCurrentVisual() {
        if (!state.active || state.finishReached) return
        val now: Long = System.currentTimeMillis()
        if (routeReroutingActive(now)) {
            sendRouteReroutingVisual()
            return
        }
        if (finishDirectionShouldOverride()) {
            if (!sendDirectionToFinishIfNeeded(true)) sendFinishDirectionPlaceholder(true)
            return
        }
        if (routeWaiting() || state.loading()) {
            sendConfiguredText()
            return
        }
        if (shouldTransmitActiveMicroToCluster()) {
            if (resendActiveMicroToCluster(true)) return
        }
        if (!state.maneuver.isNullOrEmpty()) {
            val distance = NavigationFeature.nonZeroDistance(state.maneuverDistance)
            if (!distance.isNullOrEmpty()) {
                sendManeuverWithFallbackGray(
                    state.maneuver,
                    distance,
                    maneuverProgressBucket(state.maneuver, distance),
                    true,
                )
                return
            }
            val lastMain: NavigationTxKey? = clusterTx.lastMainManeuverKey()
            if (
                lastMain != null &&
                    lastMain.distance > 0f &&
                    NavigationFeature.sameManeuverFamily(state.maneuver, lastMain.maneuver)
            ) {
                if (
                    AppSettings.navStraightUntilMain(app) &&
                        NavigationModeSettings.isNormal(app) &&
                        NavigationFeature.isYandexSource(state.source)
                ) {
                    sendManeuverWithFallbackGray(
                        lastMain.maneuver,
                        NavigationFeature.clusterDistanceText(lastMain.distance, lastMain.km),
                        lastMain.progress,
                        true,
                    )
                    AppLog.navigation(
                        app,
                        ("NAV_RESEND_LAST_MAIN" +
                            " route=" +
                            NavigationFeature.first(activeRouteId, "-") +
                            " main=" +
                            NavigationFeature.clean(lastMain.maneuver) +
                            " distance=" +
                            lastMain.distance +
                            (if (lastMain.km) "km" else "m") +
                            " reason=preview_aware_cached_distance"),
                    )
                    return
                }
                if (!clusterTx.resendLastMainManeuver()) return
                AppLog.navigation(
                    app,
                    ("NAV_RESEND_LAST_MAIN" +
                        " route=" +
                        NavigationFeature.first(activeRouteId, "-") +
                        " main=" +
                        NavigationFeature.clean(lastMain.maneuver) +
                        " distance=" +
                        lastMain.distance +
                        (if (lastMain.km) "km" else "m") +
                        " reason=transient_main_distance_unknown"),
                )
                return
            }
            AppLog.navigation(
                app,
                ("NAV_RESEND_HOLD" +
                    " reason=main_distance_unknown" +
                    " route=" +
                    NavigationFeature.first(activeRouteId, "-") +
                    " main=" +
                    NavigationFeature.clean(state.maneuver)),
            )
            return
        }
        sendConfiguredText()
    }

    private fun sendManeuverWithFallbackGray(
        maneuver: String?,
        distanceText: String?,
        progressBucket: Int,
        force: Boolean,
    ) {
        if (NavigationFeature.isStandaloneManeuverFrame(maneuver)) {
            sendManeuverIfChanged(
                maneuver,
                NavigationFeature.distanceValue(distanceText),
                NavigationFeature.isKm(distanceText),
                progressBucket,
                force,
            )
            return
        }
        val now: Long = System.currentTimeMillis()
        val grayRoad = activeFallbackGrayRoadForManeuver(maneuver, now)
        if (!grayRoad.isNullOrEmpty()) {
            sendManeuverWithGrayRoadIfChanged(
                maneuver,
                grayRoad,
                NavigationFeature.distanceValue(distanceText),
                NavigationFeature.isKm(distanceText),
                progressBucket,
                force,
            )
        } else {
            sendManeuverIfChanged(
                maneuver,
                NavigationFeature.distanceValue(distanceText),
                NavigationFeature.isKm(distanceText),
                progressBucket,
                force,
            )
        }
    }

    private fun refreshFallbackGrayRoadVisualIfNeeded(now: Long) {
        if (!state.active || state.finishReached || state.loading()) return
        val maneuver = NavigationFeature.clean(state.maneuver)
        if (maneuver.isNullOrEmpty() || !NavigationFeature.canMergeGrayRoad(maneuver)) return
        val fallback = activeFallbackGrayRoadForManeuver(maneuver, now)
        if (fallback.isNullOrEmpty()) return
        val currentGray = NavigationFeature.clean(state.grayRoadId)
        if (!fallback.equals(currentGray)) {
            state =
                state.withNavigationDebug(
                    state.mainManeuverId,
                    state.routeActionId,
                    state.microManeuverId,
                    state.microDistance,
                    state.microStatus,
                    fallback,
                    NavigationFeature.grayRoadLabel(fallback),
                    now,
                )
            publishNavigationState()
        }
        val visual =
            NavigationFeature.clean(
                NavigationFeature.first(activeClusterVisual, state.clusterVisual)
            )
        if (visual.contains(fallback)) return
        val distance = NavigationFeature.nonZeroDistance(state.maneuverDistance)
        if (!distance.isNullOrEmpty()) {
            sendManeuverWithGrayRoadIfChanged(
                maneuver,
                fallback,
                NavigationFeature.distanceValue(distance),
                NavigationFeature.isKm(distance),
                maneuverProgressBucket(maneuver, distance),
                true,
            )
            AppLog.line(
                app,
                ("Navigation held gray road refreshed: maneuver=" +
                    maneuver +
                    " gray=" +
                    fallback +
                    " distance=" +
                    distance),
            )
            return
        }
        val cachedMain: NavigationTxKey? = clusterTx.lastMainManeuverKey()
        if (
            cachedMain == null ||
                cachedMain.distance <= 0f ||
                !NavigationFeature.sameManeuverFamily(maneuver, cachedMain.maneuver)
        ) {
            AppLog.navigation(
                app,
                ("NAV_GRAY_REFRESH_HOLD" +
                    " reason=main_distance_unknown" +
                    " route=" +
                    NavigationFeature.first(activeRouteId, "-") +
                    " main=" +
                    NavigationFeature.first(maneuver, "-") +
                    " gray=" +
                    fallback),
            )
            return
        }
        sendManeuverWithGrayRoadIfChanged(
            maneuver,
            fallback,
            cachedMain.distance,
            cachedMain.km,
            cachedMain.progress,
            true,
        )
        AppLog.line(
            app,
            ("Navigation held gray road refreshed from cached main: maneuver=" +
                maneuver +
                " gray=" +
                fallback +
                " distance=" +
                cachedMain.distance +
                (if (cachedMain.km) " km" else " m")),
        )
    }

    private fun activeFallbackGrayRoadForManeuver(maneuver: String?, now: Long): String {
        if (grayRoadUntil <= now || !NavigationFeature.canMergeGrayRoad(maneuver)) return ""
        val grayRoad = NavigationFeature.clean(activeGrayRoadId)
        if (grayRoad.isNullOrEmpty()) return ""
        val maneuverMask: Int = NavigationFeature.maneuverRoadMask(maneuver)
        val grayMask: Int = NavigationFeature.grayRoadMask(grayRoad)
        if (maneuverMask != 0 && grayMask != 0 && (grayMask and maneuverMask) == 0) {
            AppLog.line(
                app,
                ("Navigation dropped stale fallback gray road: maneuver=" +
                    NavigationFeature.clean(maneuver) +
                    " gray=" +
                    grayRoad),
            )
            clearGrayRoadHold()
            clearLearnedRoadOptions()
            return ""
        }
        return grayRoad
    }

    private fun resetNavigationSendCache() {
        lastSentEtaKey = ""
        lastSentEtaTimeKey = ""
        lastSentFinishDirectionKey = ""
        lastFinishDirectionAt = 0L
        lastRouteFinishEtaAt = 0L
        lastSentSpeedLimit = -1
        speedLimitTextUntil = 0L
        clearClusterVisualHold()
    }

    private fun resetMainManeuverPreview(reason: String?) {
        val hadState: Boolean =
            !mainPreviewIdentity.isNullOrEmpty() ||
                mainPreviewRevealLatched ||
                mainPreviewLastPositiveMeters > 0f
        mainPreviewIdentity = ""
        lastMainPreviewDecisionKey = ""
        mainPreviewLastPositiveMeters = -1f
        mainPreviewRevealLatched = false
        if (hadState) {
            AppLog.navigation(
                app,
                "NAV_MAIN_PREVIEW_RESET reason=" + NavigationFeature.clean(reason),
            )
        }
    }

    private fun textForMode(mode: Int): String? {
        val finishStreet = NavigationFeature.validFinishStreet(state.finishStreet)
        val routeText = routeMetricsText()
        when (mode) {
            1 ->
                return NavigationFeature.first(
                    state.nextStreet,
                    state.currentStreet,
                    finishStreet,
                    routeText,
                )
            2 -> return NavigationFeature.first(finishStreet, routeText)
            3 ->
                return NavigationFeature.first(
                    speedLimitText(),
                    state.currentStreet,
                    state.nextStreet,
                    routeText,
                )
            0 ->
                return NavigationFeature.first(
                    state.currentStreet,
                    state.nextStreet,
                    finishStreet,
                    routeText,
                )
            else ->
                return NavigationFeature.first(
                    state.currentStreet,
                    state.nextStreet,
                    finishStreet,
                    routeText,
                )
        }
    }

    private fun routeMetricsText(): String? {
        val distance = NavigationFeature.clean(state.routeDistance)
        val time = NavigationFeature.clean(state.routeTime)
        val arrival = NavigationFeature.clean(state.arrivalTime)
        if (!distance.isNullOrEmpty() && !arrival.isNullOrEmpty()) {
            return distance + " / " + arrival
        }
        if (!distance.isNullOrEmpty() && !time.isNullOrEmpty()) {
            return distance + " / " + time
        }
        return NavigationFeature.first(distance, time, arrival)
    }

    private fun speedLimitText(): String {
        return if (state.speedLimit.isNullOrEmpty()) "" else state.speedLimit.toString() + " km/h"
    }

    private fun textModeName(mode: Int): String {
        when (mode) {
            1 -> return "street_after_maneuver"
            2 -> return "finish_street"
            3 -> return "speed_limit"
            0 -> return "current_street"
            else -> return "current_street"
        }
    }

    private fun routeWaiting(): Boolean {
        if (!waitingForRoute || !state.active || state.finishReached) return false
        val now: Long = System.currentTimeMillis()
        if (routeLoadingMinUntil > now) return true
        if (routeDataReady()) {
            waitingForRoute = false
            routeLoadingMinUntil = 0L
            return false
        }
        if (routeStartedAt <= 0L || now - routeStartedAt >= NavigationFeature.ROUTE_WAIT_MAX_MS) {
            val loading: Boolean = state.loading()
            if (!loading) {
                waitingForRoute = false
                routeLoadingMinUntil = 0L
            }
            return loading
        }
        return true
    }

    private fun routeLoadingFallbackReady(): Boolean {
        val now: Long = System.currentTimeMillis()
        return waitingForRoute &&
            state.active &&
            !state.finishReached &&
            routeStartedAt > 0L &&
            routeLoadingMinUntil <= now &&
            now - routeStartedAt >= NavigationFeature.ROUTE_WAIT_MAX_MS &&
            state.loading()
    }

    private fun routeDataReady(): Boolean {
        val hasRoute: Boolean = !state.routeDistance.isNullOrEmpty()
        val hasGuidance: Boolean =
            !state.maneuverText.isNullOrEmpty() ||
                !state.maneuverDistance.isNullOrEmpty() ||
                !state.maneuver.isNullOrEmpty()
        return hasRoute && hasGuidance
    }

    private fun startFinishHold() {
        yandexFinishSuppressed = true
        waitingForRoute = false
        routeStartedAt = 0L
        routeReroutingGeneration++
        routeReroutingUntil = 0L
        routeReroutingMinUntil = 0L
        clearManeuverTextHint()
        resetFinishDirectionAnimation()
        val generation: Int = ++finishGeneration
        val now: Long = System.currentTimeMillis()
        finishHoldUntil = now + NavigationFeature.FINISH_HOLD_MS
        finishCompassSuppressUntil =
            now + NavigationFeature.FINISH_HOLD_MS + NavigationFeature.FINISH_COMPASS_SUPPRESS_MS
        resetNavigationTextCache()
        resetNavigationSendCache()
        sendManeuverIfChanged("context_ra_finish", 0f, false, 9, true)
        sendNavigationText("Финиш")
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (generation != finishGeneration || !state.finishReached) return@postDelayed
                    setActive(false, "finish_hold")
                }
            },
            NavigationFeature.FINISH_HOLD_MS,
        )
    }

    private fun cancelFinishHold() {
        finishGeneration++
        finishHoldUntil = 0L
        finishRecoverySuppressUntil = 0L
    }

    private fun startOverspeedTextWindow() {
        val generation: Int = ++overspeedGeneration
        overspeedTextUntil = System.currentTimeMillis() + NavigationFeature.OVERSPEED_TEXT_MS
        handler.postDelayed(
            {
                synchronized(this@NavigationFeature) {
                    if (generation != overspeedGeneration || !state.active || state.finishReached)
                        return@postDelayed
                    sendConfiguredText()
                }
            },
            NavigationFeature.OVERSPEED_TEXT_MS + 50L,
        )
    }

    private fun clearOverspeedTextWindow() {
        overspeedGeneration++
        overspeedTextUntil = 0L
    }

    private fun maneuverProgressBucket(maneuver: String?, distanceText: String?): Int {
        val meters: Float = NavigationFeature.distanceMeters(distanceText)
        val family: String = NavigationFeature.maneuverFamily(maneuver)
        val explicitRollover: Boolean =
            pendingManeuverProgressRolloverMatches(activeRouteId, family, meters)
        val inferredRolloverAllowed: Boolean =
            !YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(state.source))
        val result: kia.app.navigation.domain.ManeuverProgressTracker.Result =
            maneuverProgressTracker.observeMain(
                activeRouteId,
                family,
                meters,
                explicitRollover,
                inferredRolloverAllowed,
            )
        if (explicitRollover && result.newEvent) {
            clearPendingManeuverProgressRollover()
        }
        if (result.newEvent) {
            AppLog.navigation(
                app,
                ("NAV_PROGRESS_EVENT" +
                    " reason=" +
                    result.reason +
                    " routeId=" +
                    NavigationFeature.first(activeRouteId, "-") +
                    " maneuver=" +
                    NavigationFeature.first(NavigationFeature.clean(maneuver), "-") +
                    " distance=" +
                    NavigationFeature.first(NavigationFeature.clean(distanceText), "-") +
                    " progress=" +
                    result.bucket),
            )
        }
        return if (result.hasValue) result.bucket else 0
    }

    private fun resetManeuverProgress() {
        maneuverProgressTracker.reset()
        clearPendingManeuverProgressRollover()
    }

    private fun armManeuverProgressRollover(routeId: String?, family: String?) {
        maneuverProgressRolloverMarker.arm(routeId, family, System.currentTimeMillis())
    }

    private fun pendingManeuverProgressRolloverMatches(
        routeId: String?,
        family: String?,
        meters: Float,
    ): Boolean {
        return maneuverProgressRolloverMarker.matches(
            routeId,
            family,
            meters,
            System.currentTimeMillis(),
        )
    }

    private fun clearPendingManeuverProgressRollover() {
        maneuverProgressRolloverMarker.clear()
    }

    private fun yandexRouteHeartbeatIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = NavigationFeature.clean(intent.getAction())
        val incomingRouteMetrics: Boolean =
            NavigationFeature.hasConfirmedRouteMetrics(
                NavigationFeature.routeDistanceFromIntent(intent),
                NavigationFeature.routeTimeFromIntent(intent),
                NavigationFeature.arrivalFromIntent(intent),
            )
        if (
            NavigationFeature.ACTION_SPEED.equals(action) ||
                NavigationFeature.KIA_ACTION_SPEED.equals(action) ||
                NavigationFeature.ACTION_NAVI_ON.equals(action) ||
                NavigationFeature.KIA_ACTION_NAVI_ON.equals(action) ||
                NavigationFeature.ACTION_ETA.equals(action) ||
                NavigationFeature.KIA_ACTION_ETA.equals(action)
        ) {
            return incomingRouteMetrics
        }
        return (NavigationFeature.ACTION_MANEUVER.equals(action) ||
            NavigationFeature.KIA_ACTION_MANEUVER.equals(action)) &&
            routeMetricsFresh(System.currentTimeMillis())
    }

    private fun stableSourceForSpeed(incoming: String?): String? {
        val current = NavigationFeature.clean(state.source)
        val next = NavigationFeature.clean(incoming)
        if (current.isNullOrEmpty()) return NavigationFeature.first(next, "gps_speed")
        if (
            !state.active &&
                (NavigationFeature.isYandexSource(next) || NavigationFeature.isDgisSource(next))
        )
            return next
        if (
            NavigationFeature.isGpsSpeedSource(current) &&
                (NavigationFeature.isYandexSource(next) || NavigationFeature.isDgisSource(next))
        )
            return next
        return current
    }

    private fun finishSuppressesYandex(
        source: String?,
        distanceText: String?,
        timeText: String,
        finishText: String?,
    ): Boolean {
        if (!yandexFinishSuppressed || !NavigationFeature.isYandexSource(source)) return false
        if (newYandexRouteAfterFinish(source, distanceText, timeText, finishText)) {
            yandexFinishSuppressed = false
            cancelFinishHold()
            AppLog.line(
                app,
                ("Navigation finish suppression cleared by new route: " +
                    NavigationFeature.clean(source) +
                    " distance=" +
                    NavigationFeature.clean(distanceText)),
            )
            return false
        }
        AppLog.line(
            app,
            ("Navigation ignored stale Yandex after finish: " +
                NavigationFeature.clean(source) +
                " distance=" +
                NavigationFeature.clean(distanceText)),
        )
        return true
    }

    private fun newYandexRouteAfterFinish(
        source: String?,
        distanceText: String?,
        timeText: String,
        finishText: String?,
    ): Boolean {
        val cleanSource = NavigationFeature.clean(source).lowercase(Locale.US)
        if (cleanSource.contains("route_start") || cleanSource.contains("new_route")) return true
        val meters: Float = NavigationFeature.distanceMeters(distanceText)
        if (meters > NavigationFeature.NEW_ROUTE_AFTER_FINISH_METERS) return true
        if (NavigationFeature.minutesValue(timeText) >= NavigationFeature.FINISH_CONFLICT_MINUTES)
            return true
        return containsFinishTextChange(finishText) && routeLikelyChanged(distanceText, timeText)
    }

    private fun containsFinishTextChange(finishText: String?): Boolean {
        val finish = NavigationFeature.validFinishStreet(finishText)
        return !finish.isNullOrEmpty() && !NavigationFeature.sameStreet(finish, state.finishStreet)
    }

    private fun streetCandidate(
        value: String?,
        currentValue: String?,
        blockedA: String?,
        blockedB: String?,
    ): String {
        val text = NavigationFeature.normalizeStreetLabel(value)
        if (!NavigationFeature.looksLikeStreetText(text)) return ""
        if (
            NavigationFeature.sameStreet(text, blockedA) ||
                NavigationFeature.sameStreet(text, blockedB)
        )
            return ""
        if (!currentValue.isNullOrEmpty() && NavigationFeature.sameStreet(text, currentValue))
            return ""
        return text
    }

    private fun nextStreetCandidate(value: String?, currentValue: String?): String {
        val text = NavigationFeature.normalizeStreetLabel(value)
        if (!NavigationFeature.looksLikeStreetText(text)) return ""
        if (!currentValue.isNullOrEmpty() && NavigationFeature.sameStreet(text, currentValue))
            return ""
        return text
    }

    private fun etaStreetCandidate(value: String?, currentValue: String?): String {
        val text = NavigationFeature.normalizeStreetLabel(value)
        if (text.isNullOrEmpty() || !NavigationFeature.looksLikeStreetText(text)) return ""
        if (!currentValue.isNullOrEmpty() && NavigationFeature.sameStreet(text, currentValue))
            return ""
        return text
    }

    private fun finishCandidate(value: String?): String {
        return NavigationFeature.validFinishStreet(value)
    }

    private fun finishForState(value: String?, finishPointChanged: Boolean): String {
        val text = finishCandidate(value)
        if (finishPointChanged) {
            if (text.isNullOrEmpty()) return NavigationFeature.validFinishStreet(state.finishStreet)
            staleFinishStreetAfterPointChange = ""
            return text
        }
        if (!text.isNullOrEmpty()) {
            staleFinishStreetAfterPointChange = ""
            return text
        }
        return NavigationFeature.validFinishStreet(state.finishStreet)
    }

    private fun rememberRoundaboutExit(
        maneuver: String?,
        exitText: String?,
        distanceText: String?,
        now: Long,
    ) {
        val key: String =
            NavigationFeature.clean(activeRouteId) +
                "|" +
                NavigationFeature.clean(maneuver) +
                "|" +
                NavigationFeature.clean(exitText)
        if (
            !RoundaboutHoldPolicy.shouldStart(
                lastRoundaboutExitKey,
                lastRoundaboutExitUntil,
                key,
                distanceText,
                now,
                NavigationFeature.ROUNDABOUT_HOLD_ACTIVATION_METERS,
            )
        )
            return
        lastRoundaboutExitManeuver = NavigationFeature.clean(maneuver)
        lastRoundaboutExitText = NavigationFeature.clean(exitText)
        lastRoundaboutExitKey = key
        lastRoundaboutExitUntil = now + NavigationFeature.ROUNDABOUT_EXIT_HOLD_MS
    }

    private fun heldRoundaboutExitManeuver(now: Long): String? {
        if (now > lastRoundaboutExitUntil) {
            lastRoundaboutExitManeuver = ""
            lastRoundaboutExitText = ""
            lastRoundaboutExitUntil = 0L
            return ""
        }
        return lastRoundaboutExitManeuver
    }

    private fun clearRoundaboutExitHold() {
        lastRoundaboutExitManeuver = ""
        lastRoundaboutExitText = ""
        lastRoundaboutExitKey = ""
        lastRoundaboutExitUntil = 0L
    }

    private fun rememberLearnedRoadOptions(key: String?, mask: Int, now: Long, source: String?) {
        val cleanKey = NavigationFeature.clean(key)
        if (cleanKey.isNullOrEmpty() || mask == 0) return
        val previous: Int = learnedRoadMask(cleanKey, now)
        val next: Int = mask
        learnedRoadKey = cleanKey
        learnedRoadMask = next
        learnedRoadUntil = now + NavigationFeature.ROAD_OPTION_LEARN_MS
        if (next != previous) {
            AppLog.line(
                app,
                ("Navigation gray learned road: " +
                    NavigationFeature.grayRoadLabel(NavigationFeature.grayRoadFromMask(next)) +
                    " key=" +
                    cleanKey +
                    " source=" +
                    NavigationFeature.clean(source)),
            )
        }
    }

    private fun learnedRoadMask(key: String?, now: Long): Int {
        val cleanKey = NavigationFeature.clean(key)
        if (
            cleanKey.isNullOrEmpty() ||
                learnedRoadKey.isNullOrEmpty() ||
                now > learnedRoadUntil ||
                !cleanKey.equals(learnedRoadKey)
        ) {
            return 0
        }
        return learnedRoadMask
    }

    private fun hasConfirmedRouteMetrics(): Boolean =
        hasConfirmedRouteMetrics(state.routeDistance, state.routeTime, state.arrivalTime)

    companion object {
        private val TAG: String = "KiaNav"
        private val MVITE_SOURCE: String = "yandex_mvite"
        private const val MVITE_CAMERA_FRESH_MS: Long = 12000L

        const val ACTION_MANEUVER: String = "kia.app.ACTION_MANEUVER_DATA"
        const val ACTION_ETA: String = "kia.app.ACTION_ETA_DATA"
        const val ACTION_NAVI_ON: String = "kia.app.ACTION_NAVI_ON_DATA"
        const val ACTION_SPEED: String = "kia.app.ACTION_SPEED_DATA"
        const val ACTION_EXCEEDED: String = "kia.app.ACTION_EXCEEDED_DATA"
        const val KIA_ACTION_MANEUVER: String = "com.kia.navi.ACTION_MANEUVER_DATA"
        const val KIA_ACTION_ETA: String = "com.kia.navi.ACTION_ETA_DATA"
        const val KIA_ACTION_NAVI_ON: String = "com.kia.navi.ACTION_NAVI_ON_DATA"
        const val KIA_ACTION_SPEED: String = "com.kia.navi.ACTION_SPEED_DATA"
        const val KIA_ACTION_EXCEEDED: String = "com.kia.navi.ACTION_EXCEEDED_DATA"

        private val NUMBER: Pattern? = Pattern.compile("[-+]?\\d+(?:[\\.,]\\d+)?")
        private const val ROUTE_WAIT_MAX_MS: Long = 5000L
        private const val ROUTE_WAIT_MIN_MS: Long = 3000L
        private const val ROUTE_REROUTING_MIN_HOLD_MS: Long = 1000L
        private const val ROUTE_REROUTING_HOLD_MS: Long = 4000L
        private const val ROUTE_STOP_CONFIRM_MS: Long = 12000L
        private const val FINISH_HOLD_MS: Long = 5000L
        private const val FINISH_STALE_AUTO_MS: Long = 12000L
        private const val OVERSPEED_TEXT_MS: Long = 5000L
        private const val SPEED_LIMIT_TEXT_MS: Long = 5000L
        private const val MANEUVER_TEXT_RESEND_DELAY_MS: Long = 50L
        private const val ROUNDABOUT_EXIT_HOLD_MS: Long = 45000L
        private const val ROUNDABOUT_HOLD_ACTIVATION_METERS: Float = 250f
        private const val ROAD_OPTION_LEARN_MS: Long = 180000L
        private const val GPS_SPEED_MIN_INTERVAL_MS: Long = 900L
        private const val GPS_SPEED_LOG_MS: Long = 5000L
        private const val YANDEX_CURRENT_SPEED_HOLD_MS: Long = 3500L
        private const val GPS_CURRENT_SPEED_FALLBACK_MAX_AGE_MS: Long = 5000L
        private const val YANDEX_ROAD_SPEED_LIMIT_HOLD_MS: Long = 12000L
        private const val YANDEX_ZERO_SPEED_LIMIT_CONFIRM_MS: Long = 1500L
        private const val LANE_HINT_OVERLAY_HOLD_MS: Long = 120000L
        private const val EVENT_HINT_HOLD_MS: Long = 45000L
        private const val FINISH_DIRECTION_ANIMATION_STEP_MS: Long = 240L
        private const val FINISH_DIRECTION_DUPLICATE_TX_MS: Long = 1500L
        private const val LANE_TX_POST_PASS_PRE_TX_MAX_AGE_MS: Long = 3000L
        private const val BACKGROUND_RESEND_MIN_MS: Long = 2000L
        private const val YANDEX_HEADING_FRESH_MS: Long = 4000L
        private const val GPS_COURSE_FRESH_MS: Long = 8000L
        private const val GPS_COURSE_MAX_POINT_GAP_MS: Long = 12000L
        private const val FINISH_STALE_RECOVERY_MARGIN_METERS: Float = 2f
        private const val AUTO_FINISH_ROUTE_METERS: Float = 35f
        private const val AUTO_FINISH_GPS_METERS: Float = 10f
        private const val DASHBOARD_FINISH_METERS: Float = 10f
        private const val FINAL_SEGMENT_FINISH_DIRECTION_METERS: Float = 300f
        private const val NO_MORE_MANEUVER_FINISH_DIRECTION_METERS: Float = 1000f
        private const val NO_MORE_MANEUVER_ROUTE_MARGIN_METERS: Float = 25f
        private const val FINAL_SEGMENT_MANEUVER_DONE_METERS: Float = 1f
        private const val FINAL_SEGMENT_NEW_MANEUVER_METERS: Float = 20f
        private const val NEW_ROUTE_AFTER_FINISH_METERS: Float = 50f
        private const val FINISH_CONFLICT_MANEUVER_METERS: Float = 30f
        private const val SUSPICIOUS_FINISH_POINT_ROUTE_METERS: Float = 80f
        private const val SUSPICIOUS_FINISH_POINT_GPS_METERS: Float = 30f
        private const val FINISH_CONFLICT_MINUTES: Int = 2
        private const val DISTANCE_DISPLAY_STEP_METERS: Float = 10f
        private const val MANEUVER_PROGRESS_MARKER_TTL_MS: Long = 5000L
        private const val MAIN_MANEUVER_MICRO_SEPARATION_METERS: Float = 250f
        private const val MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS: Float = 150f
        private const val INFERRED_FORWARD_MICRO_MAX_METERS: Float = 450f
        private const val GPS_BEARING_MIN_SPEED_MPS: Float = 1.4f
        private const val GPS_COURSE_MIN_DISTANCE_METERS: Float = 2.5f
        private const val GPS_COURSE_MAX_ACCURACY_METERS: Float = 35f
        private const val FINISH_COMPASS_SUPPRESS_MS: Long = 0L
        private const val DGIS_MICRO_DISTANCE_METERS: Float = 160f
        private const val YANDEX_WATCHDOG_STALE_MS: Long = 25000L
        private const val YANDEX_WATCHDOG_GPS_FRESH_MS: Long = 15000L
        private const val YANDEX_WATCHDOG_NO_GPS_GRACE_MS: Long = 300000L
        private const val YANDEX_WATCHDOG_RETRY_MS: Long = 15000L
        private const val RESTORED_ROUTE_CONFIRM_MS: Long = 2500L
        private const val AUTO_SOURCE_OWNER_FRESH_MS: Long = 15000L

        private var instance: NavigationFeature? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): NavigationFeature {
            if (NavigationFeature.instance == null)
                NavigationFeature.instance = NavigationFeature(context)
            return NavigationFeature.instance!!
        }

        private fun navigationSourceFamily(value: String?): Int {
            val source = NavigationFeature.clean(value).lowercase(Locale.US)
            if (NavigationFeature.MVITE_SOURCE.equals(source))
                return NavigationSourcePolicy.SOURCE_MVITE
            if (source.contains("2gis") || source.contains("dublgis")) {
                return NavigationSourcePolicy.SOURCE_DGIS
            }
            if (
                source.contains("yandex") ||
                    source.contains("teyes") ||
                    source.contains(YandexCoreBridgeContract.SOURCE)
            ) {
                return NavigationSourcePolicy.SOURCE_YANDEX
            }
            return NavigationSourcePolicy.SOURCE_NONE
        }

        private fun yandexIngressEvent(intent: Intent?): Int {
            if (intent == null) return NavigationSourcePolicy.EVENT_PASSIVE
            val action = NavigationFeature.clean(intent.getAction())
            if (
                NavigationFeature.ACTION_MANEUVER.equals(action) ||
                    NavigationFeature.KIA_ACTION_MANEUVER.equals(action) ||
                    NavigationFeature.ACTION_ETA.equals(action) ||
                    NavigationFeature.KIA_ACTION_ETA.equals(action)
            ) {
                return NavigationSourcePolicy.EVENT_ROUTE_ACTIVE
            }
            if (
                NavigationFeature.ACTION_NAVI_ON.equals(action) ||
                    NavigationFeature.KIA_ACTION_NAVI_ON.equals(action)
            ) {
                val active: Boolean =
                    NavigationFeature.bool(intent, "navi_on", false) ||
                        NavigationFeature.bool(intent, "active", false) ||
                        NavigationFeature.bool(intent, "is_active", false)
                return if (active) NavigationSourcePolicy.EVENT_ROUTE_ACTIVE
                else NavigationSourcePolicy.EVENT_PASSIVE
            }
            return NavigationSourcePolicy.EVENT_PASSIVE
        }

        private fun coreBridgeFinishRequested(intent: Intent?, source: String?): Boolean {
            if (
                NavigationFeature.bool(intent, "finish_reached", false) ||
                    NavigationFeature.bool(intent, "arrived", false) ||
                    NavigationFeature.bool(intent, "destination_reached", false) ||
                    NavigationFeature.bool(intent, "route_finished", false)
            ) {
                return true
            }
            val sourceLower = NavigationFeature.clean(source).lowercase(Locale.US)
            return sourceLower.contains("finish") ||
                sourceLower.contains("arrived") ||
                sourceLower.contains("destination")
        }

        private fun confirmedCoreBridgeFinishedState(intent: Intent?): Boolean {
            val state =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "bridge_state"),
                    NavigationFeature.text(intent, "state"),
                    NavigationFeature.text(intent, "route_state"),
                    NavigationFeature.text(intent, "status"),
                )
            return YandexCoreBridgeContract.STATE_FINISHED.equals(
                NavigationFeature.clean(state).lowercase(Locale.US)
            ) &&
                NavigationFeature.bool(intent, "finish_reached", false) &&
                NavigationFeature.bool(intent, "route_finished", false)
        }

        private fun confirmedRouteStop(intent: Intent?, source: String?): Boolean {
            if (
                NavigationFeature.bool(intent, "route_stop_confirmed", false) ||
                    NavigationFeature.bool(intent, "confirmed_route_stop", false) ||
                    NavigationFeature.bool(intent, "route_confirmed_off", false) ||
                    NavigationFeature.bool(intent, "route_removed", false) ||
                    NavigationFeature.bool(intent, "route_cancel_confirmed", false) ||
                    NavigationFeature.bool(intent, "route_reset_confirmed", false)
            ) {
                return true
            }
            val cleanSource = NavigationFeature.clean(source).lowercase(Locale.US)
            return cleanSource.contains("route_stop_confirmed") ||
                cleanSource.contains("confirmed_route_stop") ||
                cleanSource.contains("route_confirmed_off") ||
                cleanSource.contains("route_removed") ||
                cleanSource.contains("route_cancel_confirmed") ||
                cleanSource.contains("route_reset_confirmed")
        }

        private fun preConfirmRouteStopClick(source: String?): Boolean {
            val cleanSource = NavigationFeature.clean(source).lowercase(Locale.US)
            return cleanSource.contains("reset_route_click") ||
                cleanSource.contains("route_stop_click") ||
                cleanSource.contains("route_cancel_click") ||
                cleanSource.contains("close_route_click")
        }

        private fun yandexContextHardReset(reason: String?): Boolean {
            val cleanReason = NavigationFeature.clean(reason).lowercase(Locale.US)
            return cleanReason.equals("navigation_disabled") ||
                cleanReason.startsWith("auto_source_handoff_") ||
                cleanReason.startsWith("source_mode_") ||
                cleanReason.contains("navigator_process_stopped")
        }

        private fun routeIdFromIntent(intent: Intent?): String {
            val routeId =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "route_id"),
                    NavigationFeature.text(intent, "routeId"),
                    NavigationFeature.text(intent, "route_uuid"),
                    NavigationFeature.text(intent, "routeUuid"),
                )
            return NavigationFeature.clean(routeId)
        }

        private fun normalizeRouteTotalDistance(
            routeTotalDistance: String?,
            remainingDistance: String?,
        ): String {
            val total = NavigationFeature.normalizeDistanceText(routeTotalDistance)
            val remaining = NavigationFeature.normalizeDistanceText(remainingDistance)
            val totalMeters: Float = NavigationFeature.distanceMetersForCompare(total)
            val remainingMeters: Float = NavigationFeature.distanceMetersForCompare(remaining)
            if (totalMeters > 0f && remainingMeters > 0f && totalMeters + 1f < remainingMeters) {
                return remaining
            }
            return total
        }

        private fun distanceMetersForCompare(value: String?): Float {
            return Math.max(0f, ManeuverArbiter.distanceMeters(value))
        }

        private fun coreBridgeMainDistancePacket(intent: Intent?, distance: String?): Boolean {
            if (NavigationFeature.nonZeroDistance(distance).isNullOrEmpty()) return false
            val laneDistance = NavigationFeature.explicitLaneDistanceFromIntent(intent)
            if (
                !NavigationFeature.nonZeroDistance(laneDistance).isNullOrEmpty() &&
                    NavigationFeature.sameMicroAndMainDistance(
                        NavigationFeature.distanceMeters(distance),
                        NavigationFeature.distanceMeters(laneDistance),
                    )
            ) {
                return false
            }
            if (NavigationFeature.hasMainManeuverIdentity(intent)) return true
            val action: String? =
                if (intent == null) "" else NavigationFeature.clean(intent.getAction())
            val routeSnapshot: Boolean =
                NavigationFeature.KIA_ACTION_NAVI_ON.equals(action) ||
                    NavigationFeature.KIA_ACTION_ETA.equals(action)
            if (!routeSnapshot) return false
            return NavigationFeature.hasRouteMetricExtras(intent) &&
                NavigationFeature.hasMainManeuverDistanceMeters(intent)
        }

        private fun hasMainManeuverIdentity(intent: Intent?): Boolean {
            val raw =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "imageId"),
                    NavigationFeature.text(intent, "image_id"),
                    NavigationFeature.text(intent, "direction"),
                    NavigationFeature.text(intent, "maneuver"),
                    NavigationFeature.text(intent, "maneuver_action"),
                    NavigationFeature.text(intent, "current_maneuver"),
                    NavigationFeature.text(intent, "maneuver_type"),
                )
            if (raw.isNullOrEmpty()) return false
            val maneuver =
                NavigationFeature.normalizeManeuver(
                    NavigationFeature.clean(raw),
                    if (intent == null) 0 else intent.getIntExtra("direction_lr", 0),
                )
            return NavigationFeature.isUsableManeuver(maneuver!!)
        }

        private fun hasMainManeuverDistanceMeters(intent: Intent?): Boolean {
            return !NavigationFeature.first(
                    NavigationFeature.text(intent, "maneuver_distance_meters"),
                    NavigationFeature.text(intent, "current_maneuver_distance_meters"),
                    NavigationFeature.text(intent, "distance_to_maneuver_meters"),
                )
                .isNullOrEmpty()
        }

        private fun hasRouteMetricExtras(intent: Intent?): Boolean {
            return !NavigationFeature.first(
                    NavigationFeature.text(intent, "remaining_meters"),
                    NavigationFeature.text(intent, "remaining_distance_meters"),
                    NavigationFeature.text(intent, "distance_left_meters"),
                    NavigationFeature.text(intent, "route_distance"),
                    NavigationFeature.text(intent, "remaining_distance"),
                    NavigationFeature.text(intent, "edistance"),
                )
                .isNullOrEmpty() &&
                !NavigationFeature.first(
                        NavigationFeature.text(intent, "route_time"),
                        NavigationFeature.text(intent, "time_left"),
                        NavigationFeature.text(intent, "remaining_time"),
                        NavigationFeature.text(intent, "etime"),
                        NavigationFeature.text(intent, "arrival_time"),
                        NavigationFeature.text(intent, "atime"),
                        NavigationFeature.text(intent, "arrival"),
                    )
                    .isNullOrEmpty()
        }

        private fun finishTextFromIntent(intent: Intent?): String? {
            val validAddress =
                NavigationFeature.firstValidFinishAddress(
                    NavigationFeature.text(intent, "finish_address"),
                    NavigationFeature.text(intent, "finishAddress"),
                    NavigationFeature.text(intent, "destination_address"),
                    NavigationFeature.text(intent, "destinationAddress"),
                    NavigationFeature.text(intent, "target_address"),
                    NavigationFeature.text(intent, "targetAddress"),
                    NavigationFeature.text(intent, "finish_full_address"),
                    NavigationFeature.text(intent, "finishFullAddress"),
                    NavigationFeature.text(intent, "destination_full_address"),
                    NavigationFeature.text(intent, "destinationFullAddress"),
                    NavigationFeature.text(intent, "target_full_address"),
                    NavigationFeature.text(intent, "targetFullAddress"),
                    NavigationFeature.text(intent, "to_address"),
                    NavigationFeature.text(intent, "toAddress"),
                    NavigationFeature.text(intent, "end_address"),
                    NavigationFeature.text(intent, "endAddress"),
                    NavigationFeature.text(intent, "address"),
                    NavigationFeature.text(intent, "formatted_address"),
                    NavigationFeature.text(intent, "formattedAddress"),
                )
            val validStreet =
                NavigationFeature.firstValidFinishAddress(
                    NavigationFeature.text(intent, "destination_street"),
                    NavigationFeature.text(intent, "destinationStreet"),
                    NavigationFeature.text(intent, "finish_street"),
                    NavigationFeature.text(intent, "finishStreet"),
                    NavigationFeature.text(intent, "target_street"),
                    NavigationFeature.text(intent, "targetStreet"),
                    NavigationFeature.text(intent, "to_street"),
                    NavigationFeature.text(intent, "toStreet"),
                    NavigationFeature.text(intent, "end_street"),
                    NavigationFeature.text(intent, "endStreet"),
                )
            val validName =
                NavigationFeature.firstValidFinishName(
                    NavigationFeature.text(intent, "destination_name"),
                    NavigationFeature.text(intent, "destinationName"),
                    NavigationFeature.text(intent, "destination"),
                    NavigationFeature.text(intent, "finish_name"),
                    NavigationFeature.text(intent, "finishName"),
                    NavigationFeature.text(intent, "target_name"),
                    NavigationFeature.text(intent, "targetName"),
                    NavigationFeature.text(intent, "target"),
                    NavigationFeature.text(intent, "to_name"),
                    NavigationFeature.text(intent, "toName"),
                    NavigationFeature.text(intent, "end_name"),
                    NavigationFeature.text(intent, "endName"),
                    NavigationFeature.text(intent, "place_name"),
                    NavigationFeature.text(intent, "placeName"),
                    NavigationFeature.text(intent, "point_name"),
                    NavigationFeature.text(intent, "pointName"),
                    NavigationFeature.text(intent, "title"),
                    NavigationFeature.text(intent, "name"),
                )
            return NavigationFeature.first(validAddress, validStreet, validName)
        }

        private fun finishTextFromAddress(address: Address?): String {
            if (address == null) return ""
            val street = NavigationFeature.clean(address.getThoroughfare())
            val house = NavigationFeature.clean(address.getSubThoroughfare())
            val streetAddress = NavigationFeature.streetWithHouse(street, house)
            val line: String? =
                if (address.getMaxAddressLineIndex() >= 0)
                    NavigationFeature.clean(address.getAddressLine(0))
                else ""
            val addressText = NavigationFeature.firstValidFinishAddress(streetAddress, line)
            if (!addressText.isNullOrEmpty()) return addressText
            val feature: String = NavigationFeature.meaningfulAddressFeature(address, street, house)
            return NavigationFeature.firstValidFinishName(
                NavigationFeature.clean(address.getPremises()),
                feature,
            )
        }

        private fun streetWithHouse(street: String?, house: String?): String {
            val cleanStreet = NavigationFeature.clean(street)
            val cleanHouse = NavigationFeature.clean(house)
            if (cleanStreet.isNullOrEmpty()) return ""
            if (cleanHouse.isNullOrEmpty()) return cleanStreet
            val lowerStreet = cleanStreet.lowercase(Locale.US)
            val lowerHouse = cleanHouse.lowercase(Locale.US)
            if (lowerStreet.contains(lowerHouse)) return cleanStreet
            return cleanStreet + " " + cleanHouse
        }

        private fun meaningfulAddressFeature(
            address: Address,
            street: String?,
            house: String?,
        ): String {
            val feature = NavigationFeature.clean(address.getFeatureName())
            if (feature.isNullOrEmpty()) return ""
            if (feature.equals(NavigationFeature.clean(house), ignoreCase = true)) return ""
            if (feature.matches(Regex("(?iu)^(д\\.?\\s*)?\\d+[a-zа-яё0-9/\\-]*$"))) return ""
            if (!street.isNullOrEmpty() && NavigationFeature.sameStreet(feature, street)) return ""
            return feature
        }

        private fun hasNavigationDebug(value: NavigationState?): Boolean {
            if (value == null) return false
            return !value.mainManeuverId.isNullOrEmpty() ||
                !value.routeActionId.isNullOrEmpty() ||
                !value.microManeuverId.isNullOrEmpty() ||
                !value.microDistance.isNullOrEmpty() ||
                !value.microStatus.isNullOrEmpty() ||
                !value.grayRoadId.isNullOrEmpty() ||
                !value.grayRoadScheme.isNullOrEmpty() ||
                !value.laneRaw.isNullOrEmpty() ||
                !value.lanePosition.isNullOrEmpty() ||
                !value.roadSchemeRaw.isNullOrEmpty() ||
                !value.upcomingRaw.isNullOrEmpty()
        }

        private fun isFinishDirectionVisual(visual: String?): Boolean {
            val text = NavigationFeature.clean(visual).lowercase(Locale.US)
            return text.contains("direction_to_finish") || text.contains("finish direction")
        }

        private fun sameFamilyDistanceResetForNewManeuver(
            currentManeuver: String?,
            incomingManeuver: String?,
            previousDistance: String?,
            incomingDistance: String?,
        ): Boolean {
            if (!NavigationFeature.sameManeuverFamily(currentManeuver, incomingManeuver))
                return false
            val previousMeters: Float = NavigationFeature.distanceMeters(previousDistance)
            val incomingMeters: Float = NavigationFeature.distanceMeters(incomingDistance)
            if (incomingMeters <= 0f) return false
            if (previousMeters <= 0f)
                return incomingMeters <= NavigationFeature.MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS
            if (previousMeters > NavigationFeature.MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS)
                return false
            val jump: Float = Math.max(25f, previousMeters * 0.6f)
            return incomingMeters >= previousMeters + jump &&
                incomingMeters <= NavigationFeature.MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS
        }

        private fun hasForwardLaneTruth(intent: Intent?, sourceLower: String?): Boolean {
            return NavigationFeature.grayRoadHasStraight(intent, sourceLower) ||
                NavigationFeature.laneHighlightHas(intent, "straight") ||
                NavigationFeature.containsLaneToken(
                    NavigationFeature.laneRawItemsText(intent),
                    "straight",
                ) ||
                NavigationFeature.containsLaneToken(
                    NavigationFeature.visualLaneItemsText(intent),
                    "straight",
                )
        }

        private fun sameMicroAndMainDistance(microMeters: Float, mainMeters: Float): Boolean {
            if (microMeters <= 0f || mainMeters <= 0f) return false
            val tolerance: Float = Math.max(12f, Math.min(50f, mainMeters * 0.06f))
            return Math.abs(microMeters - mainMeters) <= tolerance
        }

        private fun microMainHoldStatus(roundabout: Boolean, mainManeuver: String?): String {
            if (roundabout || NavigationFeature.isRoundaboutManeuver(mainManeuver))
                return "не перебивает круг"
            if (NavigationFeature.isStandaloneManeuverFrame(mainManeuver))
                return "не перебивает отдельное событие"
            return "не перебивает основной манёвр"
        }

        private fun navigationRawPosition(intent: Intent?): String {
            val out: StringBuilder = StringBuilder()
            NavigationFeature.appendRawPart(
                out,
                "dist",
                NavigationFeature.first(
                    NavigationFeature.metersDistanceText(
                        NavigationFeature.first(
                            NavigationFeature.text(intent, "lane_distance_meters"),
                            NavigationFeature.text(intent, "micro_distance_meters"),
                            NavigationFeature.text(intent, "lane_sign_distance_meters"),
                            NavigationFeature.text(intent, "upcoming_lane_distance_meters"),
                        )
                    ),
                    NavigationFeature.text(intent, "lane_distance"),
                    NavigationFeature.text(intent, "micro_distance"),
                ),
            )
            NavigationFeature.appendRawPart(
                out,
                "pos",
                NavigationFeature.first(
                    NavigationFeature.text(intent, "lane_sign_position"),
                    NavigationFeature.text(intent, "lane_position"),
                    NavigationFeature.text(intent, "upcoming_lane_position"),
                ),
            )
            NavigationFeature.appendRawPart(
                out,
                "highlight",
                NavigationFeature.first(
                    NavigationFeature.text(intent, "lane_highlight"),
                    NavigationFeature.text(intent, "lane_highlighted_direction"),
                    NavigationFeature.text(intent, "highlighted_direction"),
                    NavigationFeature.text(intent, "highlighted_directions"),
                    NavigationFeature.text(intent, "recommended_lanes"),
                ),
            )
            NavigationFeature.appendRawPart(
                out,
                "count",
                NavigationFeature.text(intent, "lane_count"),
            )
            return out.toString()
        }

        private fun appendRawPart(out: StringBuilder?, label: String?, value: String?) {
            val cleanValue = NavigationFeature.clean(value)
            if (out == null || label.isNullOrEmpty() || cleanValue.isNullOrEmpty()) return
            if (out.length > 0) out.append(" | ")
            out.append(label).append('=').append(cleanValue)
        }

        private fun trimDebug(value: String?): String {
            val text = NavigationFeature.clean(value)
            if (text.length <= 500) return text
            return text.substring(0, 497) + "..."
        }

        private fun grayRoadContains(fullGrayRoad: String?, partGrayRoad: String?): Boolean {
            val fullMask: Int = NavigationFeature.grayRoadMask(fullGrayRoad)
            val partMask: Int = NavigationFeature.grayRoadMask(partGrayRoad)
            return fullMask != 0 && partMask != 0 && (fullMask and partMask) == partMask
        }

        private fun clusterFrameDistanceText(frame: String?): String {
            val text = NavigationFeature.clean(frame)
            if (text.isNullOrEmpty()) return ""
            if (text.contains(" dist=")) {
                val tail = NavigationFeature.tokenAfterMarker(text, " dist=")
                return NavigationFeature.normalizeDistanceText(
                    NavigationFeature.beforeAny(tail, " progress=", " bytes=")
                )
            }
            val parts = Pattern.compile("/").split(text)
            return if (parts.size >= 2) NavigationFeature.normalizeDistanceText(parts.get(1))
            else ""
        }

        private fun clusterFrameProgressBucket(frame: String?): Int {
            val text = NavigationFeature.clean(frame)
            if (text.isNullOrEmpty() || !text.contains("progress=")) return -1
            val tail = NavigationFeature.tokenAfterMarker(text, "progress=")
            val matcher = NavigationFeature.NUMBER!!.matcher(tail)
            if (!matcher.find()) return -1
            val parsed: Int = Math.round(NavigationFeature.parseFloat(matcher.group()))
            return if (parsed < 0 || parsed > 9) -1 else parsed
        }

        private fun tokenAfterMarker(value: String?, marker: String): String {
            val text = NavigationFeature.clean(value)
            val start: Int = text.indexOf(marker)
            if (start < 0) return ""
            return NavigationFeature.clean(text.substring(start + marker.length))
        }

        private fun beforeAny(value: String?, vararg markers: String?): String {
            val text = NavigationFeature.clean(value)
            var end: Int = -1
            for (marker: String? in markers) {
                val index: Int = text.indexOf(marker!!)
                if (index >= 0 && (end < 0 || index < end)) end = index
            }
            return NavigationFeature.clean(if (end < 0) text else text.substring(0, end))
        }

        private fun clusterYellowDiffersFrom(expected: String?, actual: String?): Boolean {
            val cleanExpected = NavigationFeature.clean(expected)
            val cleanActual = NavigationFeature.clean(actual)
            return !cleanExpected.isNullOrEmpty() &&
                !cleanActual.isNullOrEmpty() &&
                !NavigationFeature.sameManeuverFamily(cleanExpected, cleanActual)
        }

        private fun latestClusterManeuverLine(clusterTx: String?): String {
            val text = NavigationFeature.clean(clusterTx)
            if (text.isNullOrEmpty()) return ""
            val lines = Pattern.compile("\\n").split(text)
            for (i in lines.indices.reversed()) {
                val line = NavigationFeature.clean(lines.get(i))
                if (line.startsWith("maneuver+gray ") || line.startsWith("maneuver ")) {
                    return line
                }
            }
            return ""
        }

        private fun clusterYellowManeuverFromFrame(frame: String?): String {
            val text = NavigationFeature.clean(frame)
            if (text.isNullOrEmpty()) return ""
            if (text.startsWith("maneuver+gray ")) {
                val tail = text.substring("maneuver+gray ".length).trim { it <= ' ' }
                val gray: Int = tail.indexOf(" gray=")
                return NavigationFeature.clean(if (gray >= 0) tail.substring(0, gray) else tail)
            }
            if (text.startsWith("maneuver ")) {
                val tail = text.substring("maneuver ".length).trim { it <= ' ' }
                val distance: Int = tail.indexOf(" dist=")
                return NavigationFeature.clean(
                    if (distance >= 0) tail.substring(0, distance) else tail
                )
            }
            val slash: Int = text.indexOf('/')
            val visual: String? = if (slash >= 0) text.substring(0, slash) else text
            val plus: Int = visual!!.indexOf(" + ")
            return NavigationFeature.clean(if (plus >= 0) visual.substring(0, plus) else visual)
        }

        private fun parseHeadingDegrees(value: String?): Float {
            val text = NavigationFeature.clean(value)
            if (text.isNullOrEmpty()) return Float.NaN
            val matcher = NavigationFeature.NUMBER!!.matcher(text.replace(',', '.'))
            if (!matcher.find()) return Float.NaN
            try {
                return NavigationFeature.normalizeDegrees(
                    java.lang.Float.parseFloat(matcher.group())
                )
            } catch (ignored: Exception) {
                return Float.NaN
            }
        }

        private fun coordinate(intent: Intent, vararg keys: String?): Double {
            for (key: String? in keys) {
                val value: Double = intent.getDoubleExtra(key, Double.NaN)
                if (!java.lang.Double.isNaN(value)) return value
                val floatValue: Float = intent.getFloatExtra(key, Float.NaN)
                if (!java.lang.Float.isNaN(floatValue)) return floatValue.toDouble()
                val text = NavigationFeature.text(intent, key)
                if (!text.isNullOrEmpty()) {
                    val parsed: Double = NavigationFeature.parseDouble(text)
                    if (!java.lang.Double.isNaN(parsed)) return parsed
                }
            }
            return Double.NaN
        }

        private fun coordinatePair(value: String?): DoubleArray? {
            val source = NavigationFeature.clean(value)
            val lower = source.lowercase(Locale.US)
            if (
                !(source.contains(",") ||
                    source.contains(";") ||
                    lower.contains("lat") ||
                    lower.contains("lon") ||
                    lower.contains("lng") ||
                    source.matches(Regex(".*\\d+[\\.,]\\d+.*\\d+[\\.,]\\d+.*")))
            ) {
                return null
            }
            val text = source.replace(',', '.')
            if (text.isNullOrEmpty()) return null
            val matcher = NavigationFeature.NUMBER!!.matcher(text)
            if (!matcher.find()) return null
            val first: Double = NavigationFeature.parseDouble(matcher.group())
            if (!matcher.find()) return null
            val second: Double = NavigationFeature.parseDouble(matcher.group())
            if (NavigationFeature.validCoordinate(first, second))
                return doubleArrayOf(first, second)
            if (NavigationFeature.validCoordinate(second, first))
                return doubleArrayOf(second, first)
            return null
        }

        private fun parseDouble(value: String?): Double {
            try {
                return java.lang.Double.parseDouble(
                    NavigationFeature.clean(value).replace(',', '.')
                )
            } catch (ignored: Exception) {
                return Double.NaN
            }
        }

        private fun validCoordinate(lat: Double, lon: Double): Boolean {
            return !java.lang.Double.isNaN(lat) &&
                !java.lang.Double.isNaN(lon) &&
                lat >= -90.0 &&
                lat <= 90.0 &&
                lon >= -180.0 &&
                lon <= 180.0 &&
                !(lat == 0.0 && lon == 0.0)
        }

        private fun staleManeuverBeyondRoute(
            routeDistance: String?,
            maneuverDistance: String?,
        ): Boolean {
            val routeMeters: Float = NavigationFeature.distanceMeters(routeDistance)
            val maneuverMeters: Float = NavigationFeature.distanceMeters(maneuverDistance)
            if (routeMeters <= 0f || maneuverMeters <= 0f) return false
            val margin: Float = Math.max(25f, routeMeters * 0.15f)
            return maneuverMeters > routeMeters + margin
        }

        private fun maneuverFamilyChanged(oldManeuver: String?, newManeuver: String?): Boolean {
            if (oldManeuver.isNullOrEmpty() || newManeuver.isNullOrEmpty()) return false
            return !NavigationFeature.sameManeuverFamily(oldManeuver, newManeuver)
        }

        private fun sameManeuverFamily(left: String?, right: String?): Boolean {
            val a: String = NavigationFeature.maneuverFamily(left)
            val b: String = NavigationFeature.maneuverFamily(right)
            return !a.isNullOrEmpty() && a.equals(b)
        }

        private fun maneuverFamily(maneuver: String?): String {
            val p = NavigationFeature.clean(maneuver).lowercase(Locale.US)
            if (p.isNullOrEmpty() || "unknown".equals(p)) return ""
            if (NavigationFeature.isFinishManeuver(p)) return "finish"
            if (NavigationFeature.isRoundaboutManeuver(p)) return "roundabout"
            if (p.contains("_gray_")) return "gray"
            if (p.contains("turn_back") || p.contains("uturn") || p.contains("развор"))
                return "uturn"
            if (p.contains("hard_turn"))
                return if (p.contains("left")) "hard_left" else "hard_right"
            if (p.contains("exit_") || p.contains("take_")) {
                return if (p.contains("left")) "exit_left" else "exit_right"
            }
            if (p.contains("left") || p.contains("лев")) return "left"
            if (p.contains("right") || p.contains("прав")) return "right"
            if (p.contains("forward") || p.contains("straight") || p.contains("прям"))
                return "forward"
            return p
        }

        private fun minutesValue(value: String?): Int {
            if (value.isNullOrEmpty()) return 0
            val text = value.lowercase(Locale.US).replace(',', '.')
            val matcher = NavigationFeature.NUMBER!!.matcher(text)
            if (!matcher.find()) return 0
            val first: Float = NavigationFeature.parseFloat(matcher.group())
            if (first <= 0f) return 0
            if (text.contains("ч") || text.contains("hour") || text.contains("hr")) {
                val minutes: Float =
                    if (matcher.find()) NavigationFeature.parseFloat(matcher.group()) else 0f
                return Math.round(first * 60f + minutes)
            }
            return Math.round(first)
        }

        private fun clusterVisualText(
            imageId: String?,
            progressBucket: Int,
            distance: Float,
            km: Boolean,
        ): String {
            return (NavigationFeature.clean(imageId) +
                " / " +
                NavigationFeature.clusterDistanceText(distance, km) +
                " / progress=" +
                progressBucket)
        }

        private fun normalizeProgressBucket(progressBucket: Int): Int {
            return if (progressBucket < 0) 0 else NavigationFeature.clampInt(progressBucket, 0, 9)
        }

        private fun clusterDistanceText(distance: Float, km: Boolean): String {
            if (distance <= 0f) return "0 м"
            if (km) return NavigationFeature.trimDistance(distance) + " км"
            return NavigationFeature.trimDistance(NavigationFeature.roundMetersForDisplay(distance)) + " м"
        }

        private fun trimDistance(distance: Float): String {
            if (distance == Math.round(distance).toFloat())
                return java.lang.String.valueOf(Math.round(distance))
            return String.format(Locale.US, "%.1f", distance)
        }

        private fun maneuverBeyondRoute(maneuverMeters: Float, routeMeters: Float): Boolean {
            return maneuverMeters > 0f &&
                routeMeters > 0f &&
                maneuverMeters + NavigationFeature.NO_MORE_MANEUVER_ROUTE_MARGIN_METERS >=
                    routeMeters
        }

        private fun gpsBearingReliable(speedMetersPerSecond: Float): Boolean {
            return java.lang.Float.isNaN(speedMetersPerSecond) ||
                speedMetersPerSecond >= NavigationFeature.GPS_BEARING_MIN_SPEED_MPS
        }

        private fun isLegacyFallbackGrayRoad(maneuver: String?, grayRoad: String?): Boolean {
            val cleanManeuver = NavigationFeature.clean(maneuver)
            val cleanGray = NavigationFeature.clean(grayRoad)
            return ("context_ra_turn_right".equals(cleanManeuver) &&
                "context_ra_gray_right".equals(cleanGray)) ||
                ("context_ra_turn_right".equals(cleanManeuver) &&
                    "context_ra_gray_straight_right".equals(cleanGray)) ||
                ("context_ra_turn_right".equals(cleanManeuver) &&
                    "context_ra_gray_straight_left_right".equals(cleanGray)) ||
                ("context_ra_turn_left".equals(cleanManeuver) &&
                    "context_ra_gray_left".equals(cleanGray)) ||
                ("context_ra_turn_left".equals(cleanManeuver) &&
                    "context_ra_gray_straight_left".equals(cleanGray)) ||
                ("context_ra_turn_left".equals(cleanManeuver) &&
                    "context_ra_gray_straight_left_right".equals(cleanGray))
        }

        private fun confirmedFinishHoldSource(source: String?): Boolean {
            val value = NavigationFeature.clean(source).lowercase(Locale.US)
            return value.contains("core_bridge_finish") || value.contains("route_stop_finish")
        }

        private fun isYandexNavigationIntent(intent: Intent?): Boolean {
            val source = NavigationFeature.text(intent, "source")
            if (source.isNullOrEmpty()) return false
            val clean = source.lowercase(Locale.US)
            return clean.contains("yandex") || clean.contains("яндекс")
        }

        private fun routeReroutingIntent(intent: Intent?): Boolean {
            val state =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "bridge_state"),
                    NavigationFeature.text(intent, "state"),
                    NavigationFeature.text(intent, "route_state"),
                    NavigationFeature.text(intent, "status"),
                )
            if (
                YandexCoreBridgeContract.STATE_REROUTING.equals(
                    NavigationFeature.clean(state).lowercase(Locale.US)
                )
            ) {
                return true
            }
            val marker =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "last_callback"),
                    NavigationFeature.text(intent, "callback"),
                    NavigationFeature.text(intent, "event_type"),
                    NavigationFeature.text(intent, "bridge_reason"),
                    NavigationFeature.text(intent, "reason"),
                )
            val lower = NavigationFeature.clean(marker).lowercase(Locale.US)
            return lower.contains("rerout") || lower.contains("перестро")
        }

        private fun hasConfirmedRouteMetrics(
            routeDistance: String?,
            routeTime: String?,
            arrivalTime: String?,
        ): Boolean {
            return !NavigationFeature.clean(routeDistance).isNullOrEmpty() ||
                !NavigationFeature.clean(routeTime).isNullOrEmpty() ||
                !NavigationFeature.clean(arrivalTime).isNullOrEmpty()
        }

        private fun routeDistanceFromIntent(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.metersDistanceText(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "remaining_meters"),
                        NavigationFeature.text(intent, "remaining_distance_meters"),
                        NavigationFeature.text(intent, "distance_left_meters"),
                    )
                ),
                NavigationFeature.text(intent, "edistance"),
                NavigationFeature.text(intent, "route_distance"),
                NavigationFeature.text(intent, "remaining_distance"),
                NavigationFeature.text(intent, "distance_left"),
                NavigationFeature.text(intent, "total_distance"),
                NavigationFeature.text(intent, "distance"),
                NavigationFeature.textByKeyPart(intent, "distance"),
            )
        }

        private fun routeDistanceForFinalSegment(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.metersDistanceText(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "remaining_meters"),
                        NavigationFeature.text(intent, "remaining_distance_meters"),
                        NavigationFeature.text(intent, "distance_left_meters"),
                    )
                ),
                NavigationFeature.text(intent, "edistance"),
                NavigationFeature.text(intent, "route_distance"),
                NavigationFeature.text(intent, "remaining_distance"),
                NavigationFeature.text(intent, "distance_left"),
                NavigationFeature.text(intent, "total_distance"),
            )
        }

        private fun routeTimeFromIntent(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.text(intent, "route_time"),
                NavigationFeature.text(intent, "time_left"),
                NavigationFeature.text(intent, "etime"),
                NavigationFeature.text(intent, "duration"),
                NavigationFeature.text(intent, "remaining_time"),
                NavigationFeature.textByKeyPart(intent, "time_left"),
            )
        }

        private fun arrivalFromIntent(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.text(intent, "arrival_time"),
                NavigationFeature.text(intent, "arrival"),
                NavigationFeature.text(intent, "atime"),
                NavigationFeature.text(intent, "eta"),
            )
        }

        private fun isYandexSource(source: String?): Boolean {
            val clean = NavigationFeature.clean(source).lowercase(Locale.US)
            return clean.contains("yandex") || clean.contains("яндекс")
        }

        private fun isDgisSource(source: String?): Boolean {
            val clean = NavigationFeature.clean(source).lowercase(Locale.US)
            return clean.contains("2gis") || clean.contains("2гис") || clean.contains("dgis")
        }

        private fun isGpsSpeedSource(source: String?): Boolean {
            val clean = NavigationFeature.clean(source).lowercase(Locale.US)
            return clean.contains("gps") || clean.equals("speed") || clean.endsWith("_speed")
        }

        private fun isRouteSuggestionSource(source: String?): Boolean {
            return NavigationFeature.clean(source).lowercase(Locale.US).contains("suggestion_route")
        }

        private fun isCachedFinishSource(source: String?): Boolean {
            val clean = NavigationFeature.clean(source).lowercase(Locale.US)
            return clean.contains("cached_finish") ||
                clean.contains("selected_finish") ||
                clean.contains("geo_finish")
        }

        private fun isRouteFinishSource(source: String?): Boolean {
            return NavigationFeature.clean(source).lowercase(Locale.US).contains("route_finish")
        }

        private fun isGuidanceEtaSource(source: String?): Boolean {
            val clean = NavigationFeature.clean(source).lowercase(Locale.US)
            return clean.contains("fullscreen_eta") || clean.contains("guidance_eta")
        }

        private fun finishContradictedByGuidance(
            routeDistance: String?,
            maneuverDistance: String?,
            routeTime: String,
        ): Boolean {
            val routeMeters: Float = NavigationFeature.distanceMeters(routeDistance)
            if (routeMeters <= 0f || routeMeters > NavigationFeature.AUTO_FINISH_ROUTE_METERS)
                return false
            val maneuverMeters: Float = NavigationFeature.distanceMeters(maneuverDistance)
            if (maneuverMeters > routeMeters + NavigationFeature.FINISH_CONFLICT_MANEUVER_METERS)
                return true
            return NavigationFeature.minutesValue(routeTime) >=
                NavigationFeature.FINISH_CONFLICT_MINUTES
        }

        private fun packetClaimsActiveRoute(intent: Intent?): Boolean {
            return NavigationFeature.bool(intent, "navi_on", false) ||
                NavigationFeature.bool(intent, "active", false) ||
                NavigationFeature.bool(intent, "is_active", false)
        }

        private fun canResumeFromEtaPacket(
            source: String?,
            distanceText: String?,
            timeText: String?,
            arrivalText: String?,
            finishText: String?,
        ): Boolean {
            val clean = NavigationFeature.clean(source)
            if (
                NavigationFeature.isRouteSuggestionSource(clean) ||
                    NavigationFeature.isCachedFinishSource(clean) ||
                    NavigationFeature.isWeakOverviewRouteSource(clean)
            ) {
                return false
            }
            return NavigationFeature.isRouteFinishSource(clean) ||
                NavigationFeature.isGuidanceEtaSource(clean) ||
                !distanceText.isNullOrEmpty() ||
                !timeText.isNullOrEmpty() ||
                !arrivalText.isNullOrEmpty() ||
                !finishText.isNullOrEmpty()
        }

        private fun canResumeFromManeuverPacket(
            routeRoadOnly: Boolean,
            maneuver: String,
            routeActionManeuver: String,
            grayRoad: String?,
        ): Boolean {
            return routeRoadOnly ||
                NavigationFeature.isUsableManeuver(maneuver) ||
                NavigationFeature.isUsableManeuver(routeActionManeuver) ||
                !grayRoad.isNullOrEmpty()
        }

        private fun isWeakOverviewRouteSource(source: String?): Boolean {
            val clean = NavigationFeature.clean(source).lowercase(Locale.US)
            return clean.contains("overview_compact") || clean.contains("overview_route")
        }

        private fun routeRoadOnlySource(intent: Intent?, sourceLower: String?): Boolean {
            val source = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            val priority =
                NavigationFeature.clean(NavigationFeature.text(intent, "priority"))
                    .lowercase(Locale.US)
            return source.contains("route_options") ||
                source.contains("route_road") ||
                priority.contains("route_road")
        }

        private fun etaCurrentStreetFromIntent(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.text(intent, "eta_current_street"),
                NavigationFeature.text(intent, "etaCurrentStreet"),
                NavigationFeature.text(intent, "bottom_current_street"),
                NavigationFeature.text(intent, "bottomCurrentStreet"),
                NavigationFeature.text(intent, "route_current_street"),
                NavigationFeature.text(intent, "routeCurrentStreet"),
                NavigationFeature.text(intent, "current_street"),
                NavigationFeature.text(intent, "currentStreet"),
                NavigationFeature.text(intent, "current_road"),
                NavigationFeature.text(intent, "currentRoad"),
                NavigationFeature.text(intent, "current_road_name"),
                NavigationFeature.text(intent, "currentRoadName"),
                NavigationFeature.text(intent, "current_street_name"),
                NavigationFeature.text(intent, "currentStreetName"),
                NavigationFeature.text(intent, "street"),
                NavigationFeature.text(intent, "road"),
            )
        }

        private fun validFinishStreet(value: String?): String {
            val text = NavigationFeature.normalizeFinishLabel(value)
            if (text.isNullOrEmpty()) return ""
            if (NavigationFeature.looksLikeCoordinate(text)) return ""
            if (!NavigationFeature.looksLikeFinishText(text)) return ""
            return text
        }

        private fun firstValidFinishAddress(vararg values: String?): String {
            for (value: String? in values) {
                val text =
                    NavigationFeature.addDefaultStreetPrefix(
                        NavigationFeature.validFinishStreet(value)
                    )
                if (!text.isNullOrEmpty()) return text
            }
            return ""
        }

        private fun firstValidFinishName(vararg values: String?): String {
            for (value: String? in values) {
                val text = NavigationFeature.validFinishStreet(value)
                if (!text.isNullOrEmpty()) return text
            }
            return ""
        }

        private fun looksLikeFinishText(value: String?): Boolean {
            if (value.isNullOrEmpty()) return false
            val v = value.lowercase(Locale.US).trim { it <= ' ' }
            if (NavigationFeature.isCityOrCountryOnly(v)) return false
            if (v.startsWith("context_") || v.startsWith("nav_")) return false
            if (v.startsWith("geo:") || v.startsWith("ymaps") || v.startsWith("yandex"))
                return false
            if (NavigationFeature.looksLikeEncodedToken(value)) return false
            if (
                v.contains("source=") ||
                    v.contains("finish_point") ||
                    v.contains("pointcontext") ||
                    v.contains("uri=") ||
                    v.contains("http://") ||
                    v.contains("https://")
            )
                return false
            if (
                v.contains("camera") ||
                    v.contains("камера") ||
                    v.contains("скорост") ||
                    v.contains("контрол")
            )
                return false
            if (
                v.contains("маневр") ||
                    v.contains("налево") ||
                    v.contains("направо") ||
                    v.equals("прямо") ||
                    v.contains("развор") ||
                    v.contains("круг")
            )
                return false
            if (v.equals("финиш") || v.equals("finish") || v.equals("destination")) return false
            if (NavigationFeature.isTimeText(v)) return false
            if (
                (v.contains(" м") || v.endsWith("м") || v.contains(" km") || v.contains("км")) &&
                    NavigationFeature.NUMBER!!.matcher(v).find() &&
                    v.length < 14
            )
                return false
            return v.matches(Regex(".*[a-zа-яё].*"))
        }

        private fun looksLikeEncodedToken(value: String?): Boolean {
            val text = NavigationFeature.clean(value)
            if (text.isNullOrEmpty()) return false
            val lower = text.lowercase(Locale.US)
            if (
                lower.startsWith("djj8") ||
                    lower.startsWith("djf8") ||
                    lower.startsWith("v1|") ||
                    lower.startsWith("v2|")
            )
                return true
            val hasCyrillic: Boolean = lower.matches(Regex(".*[а-яё].*"))
            val hasHumanSeparator: Boolean = lower.matches(Regex(".*[\\s.,#№/\\\\-].*"))
            val compact = lower.replace(Regex("[\\s.,:;#№/\\\\_+=-]"), "")
            return !hasCyrillic &&
                !hasHumanSeparator &&
                compact.length >= 32 &&
                lower.matches(Regex("[a-z0-9_+=/\\\\-]+"))
        }

        private fun looksLikeStreetText(value: String): Boolean {
            if (!NavigationFeature.looksLikeFinishText(value)) return false
            val v = value.lowercase(Locale.US).trim { it <= ' ' }
            if (
                v.contains("маневр") ||
                    v.contains("налево") ||
                    v.contains("направо") ||
                    v.equals("прямо") ||
                    v.contains("развор") ||
                    v.contains("круг") ||
                    v.contains("финиш")
            )
                return false
            return true
        }

        private fun addDefaultStreetPrefix(value: String?): String {
            val text = NavigationFeature.clean(value)
            if (
                text.isNullOrEmpty() ||
                    NavigationFeature.hasRoadTypePrefix(text) ||
                    NavigationFeature.looksLikePoiName(text)
            )
                return text
            if (
                NavigationFeature.NUMBER!!.matcher(text).find() &&
                    text.matches(Regex("(?iu).*[a-zа-яё].*")) &&
                    !text.matches(Regex("^\\d+.*"))
            ) {
                return "ул. " + text
            }
            return text
        }

        private fun hasRoadTypePrefix(value: String?): Boolean {
            val v = NavigationFeature.clean(value).lowercase(Locale.US)
            return v.matches(
                Regex(
                    ("(?iu)^(ул\\.|улица|пр-т|проспект|просп\\.|пр-кт|б-р|бул\\.|бульвар|" +
                        "пер\\.|переулок|пл\\.|площадь|ш\\.|шоссе|наб\\.|набережная|пр-д|проезд|" +
                        "туп\\.|тупик|ал\\.|аллея|тр\\.|тракт|мкр\\.|микрорайон|кв-л|квартал|" +
                        "лин\\.|линия|дор\\.|дорога)\\s+.*")
                )
            )
        }

        private fun looksLikePoiName(value: String?): Boolean {
            val v = NavigationFeature.clean(value).lowercase(Locale.US)
            return v.contains("аптека") ||
                v.contains("магазин") ||
                v.contains("маркет") ||
                v.contains("супермаркет") ||
                v.contains("тц ") ||
                v.contains("трц ") ||
                v.contains("кафе") ||
                v.contains("ресторан") ||
                v.contains("банк") ||
                v.contains("азс") ||
                v.contains("школ") ||
                v.contains("садик") ||
                v.contains("клиник") ||
                v.contains("больниц") ||
                v.contains("отель") ||
                v.contains("гостиниц") ||
                v.startsWith("жк ") ||
                v.startsWith("бц ")
        }

        private fun looksLikeCoordinate(value: String?): Boolean {
            if (value.isNullOrEmpty()) return false
            if (!value.contains(",")) return false
            val v = value.replace(',', '.')
            val matcher = NavigationFeature.NUMBER!!.matcher(v)
            return matcher.find() && matcher.find() && v.length <= 48
        }

        private fun sameStreet(a: String?, b: String?): Boolean {
            if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
            val left = NavigationFeature.simplifyStreet(a)
            val right = NavigationFeature.simplifyStreet(b)
            return !left.isNullOrEmpty() && left.equals(right)
        }

        private fun simplifyStreet(value: String?): String {
            var v: String? = NavigationFeature.clean(value).lowercase(Locale.US)
            v =
                v!!.replace(
                    Regex(
                        ("(?iu)\\b(улица|ул|проспект|просп|пр|пр-т|пр-кт|бульвар|бул|б-р|переулок|пер|" +
                            "площадь|пл|шоссе|ш|набережная|наб|проезд|пр-д|тупик|туп|аллея|ал|тракт|тр|" +
                            "микрорайон|мкр|квартал|кв-л|линия|лин|дорога|дор)\\b\\.?")
                    ),
                    "",
                )
            return v.replace(Regex("[^a-zа-яё0-9]"), "")
        }

        private fun normalizeStreetLabel(value: String?): String {
            var text: String? = NavigationFeature.clean(value)
            if (text.isNullOrEmpty()) return ""
            text = text.replace(Regex("\\s*,\\s*"), ", ")
            text = text.replace(Regex("(?iu)\\bулица\\b\\s*"), "ул. ")
            text = text.replace(Regex("(?iu)\\bул\\.?\\s+"), "ул. ")
            text = text.replace(Regex("(?iu)\\bпроспект\\b\\s*"), "пр-т ")
            text = text.replace(Regex("(?iu)\\bпросп\\.?\\s*"), "пр-т ")
            text = text.replace(Regex("(?iu)\\bпр-кт\\b\\s*"), "пр-т ")
            text = text.replace(Regex("(?iu)\\bбульвар\\b\\s*"), "бул. ")
            text = text.replace(Regex("(?iu)\\bбул\\.?\\s*"), "бул. ")
            text = text.replace(Regex("(?iu)\\bпереулок\\b\\s*"), "пер. ")
            text = text.replace(Regex("(?iu)\\bпер\\.?\\s*"), "пер. ")
            text = text.replace(Regex("(?iu)\\bплощадь\\b\\s*"), "пл. ")
            text = text.replace(Regex("(?iu)\\bпл\\.?\\s*"), "пл. ")
            text = text.replace(Regex("(?iu)\\bшоссе\\b\\s*"), "ш. ")
            text = text.replace(Regex("(?iu)\\bш\\.?\\s+"), "ш. ")
            text = text.replace(Regex("(?iu)\\bнабережная\\b\\s*"), "наб. ")
            text = text.replace(Regex("(?iu)\\bнаб\\.?\\s*"), "наб. ")
            text = text.replace(Regex("(?iu)\\bпроезд\\b\\s*"), "пр-д ")
            text = text.replace(Regex("(?iu)\\bтупик\\b\\s*"), "туп. ")
            text = text.replace(Regex("(?iu)\\bаллея\\b\\s*"), "ал. ")
            text = text.replace(Regex("(?iu)\\bтракт\\b\\s*"), "тр. ")
            text = text.replace(Regex("(?iu)\\bмикрорайон\\b\\s*"), "мкр. ")
            text = text.replace(Regex("(?iu)\\bквартал\\b\\s*"), "кв-л ")
            text = text.replace(Regex("(?iu)\\bлиния\\b\\s*"), "лин. ")
            text = text.replace(Regex("(?iu)\\bдорога\\b\\s*"), "дор. ")
            return NavigationFeature.clean(text)
        }

        private fun normalizeFinishLabel(value: String?): String {
            var text: String? = NavigationFeature.normalizeStreetLabel(value)
            if (text.isNullOrEmpty()) return ""
            text = text.replace(Regex("(?iu)\\b(город|г)\\.?\\s+"), "")
            text = NavigationFeature.firstAddressPart(text)
            text =
                text.replace(
                    Regex("(?iu)^\\s*(казахстан|атырау|алматы|астана|нур-султан)\\s*,\\s*"),
                    "",
                )
            text =
                text.replace(
                    Regex("(?iu)\\s*,\\s*(казахстан|атырау|алматы|астана|нур-султан)\\s*$"),
                    "",
                )
            return NavigationFeature.clean(text)
        }

        private fun firstAddressPart(value: String?): String {
            val parts = Pattern.compile("\\s*,\\s*").split(NavigationFeature.clean(value))
            if (parts.size == 0) return ""
            var firstAddress: String = ""
            for (part: String? in parts) {
                val text = NavigationFeature.clean(part)
                if (text.isNullOrEmpty()) continue
                val lower = text.lowercase(Locale.US)
                if (
                    lower.equals("казахстан") ||
                        lower.equals("атырау") ||
                        lower.equals("алматы") ||
                        lower.equals("астана") ||
                        lower.equals("нур-султан")
                ) {
                    continue
                }
                if (firstAddress.isNullOrEmpty()) {
                    firstAddress = text
                    continue
                }
                if (
                    NavigationFeature.looksLikeHousePart(text) &&
                        NavigationFeature.hasRoadTypePrefix(firstAddress)
                ) {
                    return firstAddress + " " + text
                }
                return firstAddress
            }
            return firstAddress
        }

        private fun looksLikeHousePart(value: String?): Boolean {
            val text = NavigationFeature.clean(value).lowercase(Locale.US)
            if (text.isNullOrEmpty() || text.length > 16) return false
            return text.matches(Regex("(?iu)^(д\\.?\\s*)?\\d+[a-zа-яё0-9/\\-]*$"))
        }

        private fun isCityOrCountryOnly(value: String?): Boolean {
            var v: String? = NavigationFeature.clean(value).lowercase(Locale.US)
            if (v.isNullOrEmpty()) {
                return true
            }
            v = v.replace(Regex("(?iu)^\\s*(город|г)\\.?\\s+"), "")
            v = v.replace(Regex("\\s*,\\s*"), ",")
            return v.equals("казахстан") ||
                v.equals("атырау") ||
                v.equals("алматы") ||
                v.equals("астана") ||
                v.equals("нур-султан") ||
                v.equals("атырау,казахстан") ||
                v.equals("алматы,казахстан") ||
                v.equals("астана,казахстан") ||
                v.equals("нур-султан,казахстан")
        }

        private fun isUsableManeuver(maneuver: String?): Boolean {
            if (maneuver.isNullOrEmpty()) return false
            return !NavigationFeature.isCameraDirection(maneuver)
        }

        private fun isOffState(state: String?): Boolean {
            if (state.isNullOrEmpty()) return false
            val s = state.lowercase(Locale.US).trim { it <= ' ' }
            return s.equals("0") ||
                s.equals("false") ||
                s.equals("off") ||
                s.equals("close") ||
                s.equals("closed") ||
                s.equals("idle")
        }

        private fun distance(intent: Intent): kotlin.Array<String?>? {
            val meters: Float = intent.getFloatExtra("distance_val", -1f)
            if (meters > 0f) {
                if (meters >= 1000f)
                    return arrayOf<String?>(String.format(Locale.US, "%.1f", meters / 1000f), "км")
                return arrayOf<String?>(java.lang.String.valueOf(Math.round(meters)), "м")
            }
            val value =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "distance_val_str"),
                    NavigationFeature.text(intent, "distance"),
                )
            if (value.isNullOrEmpty()) return null
            val unit = NavigationFeature.first(NavigationFeature.text(intent, "distance_unit"), "м")
            return arrayOf<String?>(value, unit)
        }

        private fun imageId(direction: String?, lr: Int): String {
            val p: String? = if (direction == null) "" else direction.lowercase(Locale.US)
            val left: Boolean = lr == 1 || p!!.contains("left") || p.contains("лев")
            val right: Boolean = lr == 2 || p!!.contains("right") || p.contains("прав")
            if (
                p!!.contains("finish") ||
                    p.contains("arriv") ||
                    p.contains("destination") ||
                    p.contains("финиш")
            ) {
                return "context_ra_finish"
            }
            if (p.contains("round") || p.contains("круг")) return "context_ra_in_circular_movement"
            if (p.contains("uturn") || p.contains("развор")) {
                if (left) return "context_ra_turn_back_left"
                if (right) return "context_ra_turn_back_right"
                return "context_ra_turn_back"
            }
            if (left)
                return if (
                    p.contains("take") ||
                        p.contains("keep") ||
                        p.contains("slight") ||
                        p.contains("fork") ||
                        p.contains("exit") ||
                        p.contains("ramp")
                )
                    "context_ra_exit_left"
                else "context_ra_turn_left"
            if (right)
                return if (
                    p.contains("take") ||
                        p.contains("keep") ||
                        p.contains("slight") ||
                        p.contains("fork") ||
                        p.contains("exit") ||
                        p.contains("ramp")
                )
                    "context_ra_exit_right"
                else "context_ra_turn_right"
            return "context_ra_forward"
        }

        private fun routeActionManeuver(intent: Intent?): String? {
            val actionValue =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "route_action"),
                    NavigationFeature.text(intent, "routeAction"),
                    NavigationFeature.text(intent, "route_road_action"),
                    NavigationFeature.text(intent, "routeRoadAction"),
                    NavigationFeature.text(intent, "maneuver_action"),
                    NavigationFeature.textByKeyPart(intent, "route_action"),
                )
            val sectionValue =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "route_section"),
                    NavigationFeature.text(intent, "routeSection"),
                )
            val value = NavigationFeature.first(actionValue, sectionValue)
            val p = NavigationFeature.clean(value).lowercase(Locale.US).replace('-', '_')
            if (p.isNullOrEmpty()) return ""
            if (
                p.contains("turn_back_right") ||
                    p.contains("uturn_right") ||
                    p.contains("u_turn_right")
            ) {
                return "context_ra_turn_back_right"
            }
            if (
                p.contains("turn_back_left") ||
                    p.contains("turn_back") ||
                    p.contains("uturn_left") ||
                    p.contains("u_turn_left") ||
                    p.contains("развор")
            ) {
                return "context_ra_turn_back_left"
            }
            val roundaboutText =
                NavigationFeature.clean(value.toString() + " " + sectionValue)
                    .lowercase(Locale.US)
                    .replace('-', '_')
            if (
                p.contains("roundabout") ||
                    p.contains("circular") ||
                    p.contains("круг") ||
                    roundaboutText.contains("leave_roundabout")
            ) {
                val exit =
                    NavigationFeature.first(
                        NavigationFeature.roundaboutExitFromRouteSection(sectionValue),
                        NavigationFeature.roundaboutExitFromText(p),
                    )
                return if (exit.isNullOrEmpty()) "context_ra_in_circular_movement" else exit
            }
            val precise = ManeuverCanonicalizer.canonicalize(value)
            if (NavigationFeature.isUsableManeuver(precise)) return precise
            if (
                p.contains("sharp_right") ||
                    p.contains("hard_right") ||
                    p.contains("hard_turn_right")
            )
                return "context_ra_hard_turn_right"
            if (p.contains("sharp_left") || p.contains("hard_left") || p.contains("hard_turn_left"))
                return "context_ra_hard_turn_left"
            if (
                p.contains("exit_right") ||
                    p.contains("ramp_right") ||
                    p.contains("slight_right") ||
                    p.contains("keep_right") ||
                    p.contains("take_right") ||
                    p.contains("fork_right")
            ) {
                return if (p.contains("take_right")) "context_ra_take_right"
                else "context_ra_exit_right"
            }
            if (
                p.contains("exit_left") ||
                    p.contains("ramp_left") ||
                    p.contains("slight_left") ||
                    p.contains("keep_left") ||
                    p.contains("take_left") ||
                    p.contains("fork_left")
            ) {
                return if (p.contains("take_left")) "context_ra_take_left"
                else "context_ra_exit_left"
            }
            if (p.contains("right") || p.contains("направо")) {
                return if (NavigationFeature.routeActionRightBranch(intent)) "context_ra_exit_right"
                else "context_ra_turn_right"
            }
            if (p.contains("left") || p.contains("налево")) {
                return if (NavigationFeature.routeActionLeftBranch(intent)) "context_ra_exit_left"
                else "context_ra_turn_left"
            }
            if (p.contains("straight") || p.contains("forward") || p.contains("прям")) {
                return "context_ra_forward"
            }
            return ""
        }

        private fun routeRoadActionContradictedByLaneTruth(
            intent: Intent?,
            sourceLower: String?,
            routeActionManeuver: String,
            laneHighlightManeuver: String,
        ): Boolean {
            if (!NavigationFeature.isUsableManeuver(routeActionManeuver)) return false
            if (NavigationFeature.routeRoadActionKeepsPriority(routeActionManeuver)) return false
            if (
                NavigationFeature.isUsableManeuver(laneHighlightManeuver) &&
                    !NavigationFeature.sameManeuverFamily(
                        routeActionManeuver,
                        laneHighlightManeuver,
                    ) &&
                    !NavigationFeature.routeActionHighlightMatches(intent, routeActionManeuver)
            ) {
                return true
            }
            if (
                NavigationFeature.maneuverNeedsLaneToken(routeActionManeuver, "right") &&
                    NavigationFeature.routeRoadForbidsLaneToken(intent, sourceLower, "right")
            ) {
                return true
            }
            if (
                NavigationFeature.maneuverNeedsLaneToken(routeActionManeuver, "left") &&
                    NavigationFeature.routeRoadForbidsLaneToken(intent, sourceLower, "left")
            ) {
                return true
            }
            if (
                NavigationFeature.maneuverNeedsLaneToken(routeActionManeuver, "straight") &&
                    NavigationFeature.routeRoadForbidsLaneToken(intent, sourceLower, "straight")
            ) {
                return true
            }
            return false
        }

        private fun routeRoadActionKeepsPriority(maneuver: String?): Boolean {
            val value = NavigationFeature.clean(maneuver)
            return NavigationFeature.isFinishManeuver(value) ||
                NavigationFeature.isRoundaboutManeuver(value) ||
                value.contains("turn_back") ||
                value.contains("uturn") ||
                value.contains("hard_turn")
        }

        private fun maneuverNeedsLaneToken(maneuver: String?, token: String?): Boolean {
            val value = NavigationFeature.clean(maneuver)
            if (value.isNullOrEmpty() || token.isNullOrEmpty()) return false
            if ("right".equals(token)) return value.contains("right")
            if ("left".equals(token)) return value.contains("left")
            if ("straight".equals(token)) {
                return value.contains("forward") || value.contains("straight")
            }
            return false
        }

        private fun routeRoadForbidsLaneToken(
            intent: Intent?,
            sourceLower: String?,
            token: String,
        ): Boolean {
            if (
                "right".equals(token) &&
                    NavigationFeature.hasAnyExtra(
                        intent,
                        "lane_has_right",
                        "road_has_right",
                        "has_right",
                        "can_go_right",
                        "allow_right",
                        "available_right",
                    )
            ) {
                return !NavigationFeature.boolAny(
                    intent,
                    "lane_has_right",
                    "road_has_right",
                    "has_right",
                    "can_go_right",
                    "allow_right",
                    "available_right",
                )
            }
            if (
                "left".equals(token) &&
                    NavigationFeature.hasAnyExtra(
                        intent,
                        "lane_has_left",
                        "road_has_left",
                        "has_left",
                        "can_go_left",
                        "allow_left",
                        "available_left",
                    )
            ) {
                return !NavigationFeature.boolAny(
                    intent,
                    "lane_has_left",
                    "road_has_left",
                    "has_left",
                    "can_go_left",
                    "allow_left",
                    "available_left",
                )
            }
            if (
                "straight".equals(token) &&
                    NavigationFeature.hasAnyExtra(
                        intent,
                        "lane_has_straight",
                        "road_has_straight",
                        "has_straight",
                        "can_go_straight",
                        "allow_straight",
                        "available_straight",
                    )
            ) {
                return !NavigationFeature.boolAny(
                    intent,
                    "lane_has_straight",
                    "road_has_straight",
                    "has_straight",
                    "can_go_straight",
                    "allow_straight",
                    "available_straight",
                )
            }
            val topology = NavigationFeature.grayRoadTopologyText(intent, sourceLower)
            return !topology.isNullOrEmpty() &&
                !NavigationFeature.containsLaneToken(topology, token)
        }

        private fun shouldPreferRouteActionManeuver(
            intent: Intent?,
            imageManeuver: String,
            routeActionManeuver: String,
        ): Boolean {
            if (!NavigationFeature.isUsableManeuver(routeActionManeuver)) return false
            if (!NavigationFeature.isUsableManeuver(imageManeuver)) return true
            if (routeActionManeuver.equals(imageManeuver)) return false
            if (
                NavigationFeature.isFinishManeuver(imageManeuver) &&
                    !NavigationFeature.isFinishManeuver(routeActionManeuver)
            )
                return false
            val provenance =
                NavigationFeature.clean(NavigationFeature.text(intent, "maneuver_provenance"))
                    .lowercase(Locale.US)
            if (
                "annotation".equals(provenance) &&
                    !NavigationFeature.sameManeuverFamily(imageManeuver, routeActionManeuver) &&
                    !"current"
                        .equals(
                            NavigationFeature.text(intent, "route_action_scope"),
                            ignoreCase = true,
                        )
            ) {
                return false
            }

            val action =
                NavigationFeature.clean(
                        NavigationFeature.first(
                            NavigationFeature.text(intent, "route_action"),
                            NavigationFeature.text(intent, "routeAction"),
                            NavigationFeature.textByKeyPart(intent, "route_action"),
                        )
                    )
                    .lowercase(Locale.US)
                    .replace('-', '_')
            if (action.isNullOrEmpty()) return false

            if (routeActionManeuver.contains("turn_back")) return true
            if (NavigationFeature.isRoundaboutManeuver(routeActionManeuver)) {
                return NavigationFeature.isRoundaboutManeuver(imageManeuver) ||
                    !NavigationFeature.isUsableManeuver(imageManeuver) ||
                    NavigationFeature.routeActionHighlightMatches(intent, routeActionManeuver)
            }
            if (NavigationFeature.routeActionHighlightMatches(intent, routeActionManeuver))
                return true

            val imageFamily: String = NavigationFeature.maneuverFamily(imageManeuver)
            val actionFamily: String = NavigationFeature.maneuverFamily(routeActionManeuver)
            return !imageFamily.isNullOrEmpty() &&
                !actionFamily.isNullOrEmpty() &&
                !imageFamily.equals(actionFamily) &&
                ("left".equals(actionFamily) ||
                    "right".equals(actionFamily) ||
                    "uturn".equals(actionFamily))
        }

        private fun shouldPromoteRouteRoadActionAsMain(
            routeRoadOnly: Boolean,
            routeActionManeuver: String,
            displayManeuver: String,
            distanceText: String?,
            rejectedAsMain: Boolean,
            intent: Intent?,
        ): Boolean {
            if (
                !routeRoadOnly ||
                    rejectedAsMain ||
                    !NavigationFeature.isUsableManeuver(routeActionManeuver)
            )
                return false
            if (
                !NavigationFeature.routeRoadPriorityActionConfirmed(
                    intent,
                    routeActionManeuver,
                    distanceText,
                )
            )
                return false
            if (
                NavigationFeature.isUsableManeuver(displayManeuver) &&
                    !routeActionManeuver.equals(displayManeuver) &&
                    !NavigationFeature.sameManeuverFamily(routeActionManeuver, displayManeuver)
            ) {
                return false
            }
            return true
        }

        private fun routeRoadPriorityActionConfirmed(
            intent: Intent?,
            routeActionManeuver: String?,
            distanceText: String?,
        ): Boolean {
            if (!NavigationFeature.isPriorityEventManeuver(routeActionManeuver)) return false
            return NavigationFeature.routeActionHighlightMatches(intent, routeActionManeuver) ||
                !NavigationFeature.nonZeroDistance(distanceText).isNullOrEmpty() ||
                NavigationFeature.routeActionTextConfirmsPriority(intent, routeActionManeuver)
        }

        private fun routeActionTextConfirmsPriority(intent: Intent?, maneuver: String?): Boolean {
            val action =
                NavigationFeature.clean(
                        NavigationFeature.first(
                            NavigationFeature.text(intent, "route_action"),
                            NavigationFeature.text(intent, "routeAction"),
                            NavigationFeature.text(intent, "route_road_action"),
                            NavigationFeature.text(intent, "routeRoadAction"),
                            NavigationFeature.textByKeyPart(intent, "route_action"),
                            NavigationFeature.text(intent, "route_section"),
                            NavigationFeature.text(intent, "routeSection"),
                        )
                    )
                    .lowercase(Locale.US)
                    .replace('-', '_')
            val value = NavigationFeature.clean(maneuver).lowercase(Locale.US)
            if (action.isNullOrEmpty() || value.isNullOrEmpty()) return false
            if (value.contains("turn_back") || value.contains("uturn")) {
                return NavigationFeature.containsAny(
                    action,
                    "turn_back",
                    "u_turn",
                    "uturn",
                    "u-turn",
                    "left180",
                    "right180",
                    "развор",
                )
            }
            if (NavigationFeature.isRoundaboutManeuver(value)) {
                return NavigationFeature.containsAny(action, "round", "circular", "кольц", "круг")
            }
            if (value.contains("hard_turn")) {
                return NavigationFeature.containsAny(action, "hard", "sharp", "резк", "круто")
            }
            if (value.contains("exit_") || value.contains("take_")) {
                return NavigationFeature.containsAny(
                    action,
                    "exit",
                    "ramp",
                    "take",
                    "fork",
                    "slight",
                    "keep",
                    "slip",
                    "съезд",
                    "держ",
                )
            }
            return false
        }

        private fun visibleRouteActionManeuver(
            routeActionManeuver: String,
            displayManeuver: String,
        ): String? {
            if (!NavigationFeature.isUsableManeuver(routeActionManeuver)) return ""
            if (!NavigationFeature.isUsableManeuver(displayManeuver)) return routeActionManeuver
            if (routeActionManeuver.equals(displayManeuver)) return routeActionManeuver
            if (NavigationFeature.sameManeuverFamily(routeActionManeuver, displayManeuver))
                return routeActionManeuver
            return ""
        }

        private fun routeActionHighlightMatches(intent: Intent?, maneuver: String?): Boolean {
            val value = NavigationFeature.clean(maneuver)
            if (value.contains("turn_back_left"))
                return NavigationFeature.laneHighlightHas(intent, "left180")
            if (value.contains("turn_back_right"))
                return NavigationFeature.laneHighlightHas(intent, "right180")
            if (value.contains("left")) return NavigationFeature.laneHighlightHas(intent, "left")
            if (value.contains("right")) return NavigationFeature.laneHighlightHas(intent, "right")
            if (value.contains("forward") || value.contains("straight")) {
                return NavigationFeature.laneHighlightHas(intent, "straight")
            }
            return false
        }

        private fun routeActionRightBranch(intent: Intent?): Boolean {
            return NavigationFeature.routeActionBranch(intent, true)
        }

        private fun routeActionLeftBranch(intent: Intent?): Boolean {
            return NavigationFeature.routeActionBranch(intent, false)
        }

        private fun routeActionBranch(intent: Intent?, right: Boolean): Boolean {
            val action =
                NavigationFeature.clean(
                        NavigationFeature.first(
                            NavigationFeature.text(intent, "route_action"),
                            NavigationFeature.text(intent, "routeAction"),
                            NavigationFeature.textByKeyPart(intent, "route_action"),
                        )
                    )
                    .lowercase(Locale.US)
                    .replace('-', '_')
            val section =
                NavigationFeature.clean(
                        NavigationFeature.first(
                            NavigationFeature.text(intent, "route_section"),
                            NavigationFeature.text(intent, "routeSection"),
                        )
                    )
                    .lowercase(Locale.US)
                    .replace('-', '_')
            val lanes =
                NavigationFeature.clean(
                        NavigationFeature.first(
                            NavigationFeature.laneRawItemsText(intent),
                            NavigationFeature.grayRoadTopologyText(intent),
                        )
                    )
                    .lowercase(Locale.US)
                    .replace('-', '_')
            val all: String = action + " " + section + " " + lanes
            val side: String = if (right) "right" else "left"
            val ruSide: String = if (right) "прав" else "лев"
            if (
                all.contains("exit_" + side) ||
                    all.contains("ramp_" + side) ||
                    all.contains("slight_" + side) ||
                    all.contains("fork_" + side) ||
                    all.contains("take_" + side) ||
                    all.contains("slip_" + side) ||
                    (all.contains("съезд") && all.contains(ruSide))
            ) {
                return true
            }
            return false
        }

        private fun laneMicroSource(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            return value.contains("micro") ||
                value.contains("direction_sign") ||
                value.contains("auto_widget_signs")
        }

        private fun laneDistanceOnlySource(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            return value.contains("lane_distance") ||
                value.contains("lane_guidance") ||
                value.contains("guidance_tick") ||
                value.contains("rendered_lane") ||
                value.contains("projected_lanes") ||
                value.contains("panel_lanes")
        }

        private fun mainGuidanceDistanceTick(
            sourceLower: String?,
            intent: Intent?,
            explicitLaneDistance: String?,
            maneuverDistance: String?,
        ): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            if (!value.contains("guidance_tick")) return false
            if (!NavigationFeature.nonZeroDistance(explicitLaneDistance).isNullOrEmpty())
                return false
            if (
                NavigationFeature.bool(intent, "lane_guidance", false) ||
                    NavigationFeature.hasLaneData(intent)
            )
                return false
            return !NavigationFeature.nonZeroDistance(maneuverDistance).isNullOrEmpty()
        }

        private fun ignoredLaneDebugSource(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            return value.contains("lane_guidance_debug") ||
                value.contains("rendered_lane_debug") ||
                value.contains("projected_lanes_debug")
        }

        private fun trustedLaneDirectionSource(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            if (value.contains("visual_lane_highlight")) return true
            if (value.contains("visual_lane_single")) return true
            if (value.contains(YandexCoreBridgeContract.SOURCE)) return true
            if (NavigationFeature.laneDistanceOnlySource(value)) return false
            return value.contains("route_road") ||
                value.contains("route_options") ||
                value.contains("direction_sign") ||
                value.contains("auto_widget_signs") ||
                value.contains("micro")
        }

        private fun fallbackLaneMicroManeuver(
            intent: Intent?,
            sourceLower: String?,
            grayRoad: String?,
        ): String? {
            val highlighted = NavigationFeature.laneHighlightedManeuver(intent, sourceLower)
            if (NavigationFeature.isUsableManeuver(highlighted!!)) return highlighted
            if (
                !NavigationFeature.containsAny(
                    sourceLower,
                    "direction_sign",
                    "auto_widget_signs",
                    "visual_lane_highlight",
                    "micro",
                )
            ) {
                return ""
            }
            return NavigationFeature.directionManeuverFromText(
                NavigationFeature.first(
                    NavigationFeature.text(intent, "lane_maneuver"),
                    NavigationFeature.text(intent, "lane_highlight"),
                    NavigationFeature.text(intent, "highlighted_directions"),
                    NavigationFeature.text(intent, "recommended_lanes"),
                )
            )
        }

        private fun visualLaneHighlightedManeuver(intent: Intent?): String? {
            val highlighted =
                NavigationFeature.first(
                    NavigationFeature.providerLaneHighlightText(intent),
                    NavigationFeature.highlightedDirectionSignText(
                        NavigationFeature.visualLaneItemsText(intent)
                    ),
                )
            if (highlighted.isNullOrEmpty()) return ""
            return NavigationFeature.directionManeuverFromText(highlighted)
        }

        private fun visualLaneSingleManeuver(intent: Intent?, sourceLower: String?): String {
            if (!NavigationFeature.signOnlyLaneSource(sourceLower)) return ""
            val text = NavigationFeature.visualLaneItemsText(intent)
            if (text.isNullOrEmpty()) return ""
            val uturnLeft: Boolean =
                NavigationFeature.containsLaneToken(text, "left180") ||
                    NavigationFeature.containsLaneToken(text, "uturn_left") ||
                    NavigationFeature.containsLaneToken(text, "turn_back_left") ||
                    NavigationFeature.containsLaneToken(text, "u_turn_left")
            val uturnRight: Boolean =
                NavigationFeature.containsLaneToken(text, "right180") ||
                    NavigationFeature.containsLaneToken(text, "uturn_right") ||
                    NavigationFeature.containsLaneToken(text, "turn_back_right") ||
                    NavigationFeature.containsLaneToken(text, "u_turn_right")
            val straight: Boolean = NavigationFeature.containsLaneToken(text, "straight")
            val left: Boolean = NavigationFeature.visualLaneHasLeft(text) && !uturnLeft
            val right: Boolean = NavigationFeature.visualLaneHasRight(text) && !uturnRight
            val families: Int =
                ((if (uturnLeft || uturnRight) 1 else 0) +
                    (if (left) 1 else 0) +
                    (if (right) 1 else 0) +
                    (if (straight) 1 else 0))
            if (families != 1) return ""
            if (uturnLeft) return "context_ra_turn_back_left"
            if (uturnRight) return "context_ra_turn_back_right"
            if (left) return "context_ra_turn_left"
            if (right) return "context_ra_turn_right"
            if (straight) return "context_ra_forward"
            return ""
        }

        private fun visualLaneGrayRoadManeuver(intent: Intent?): String {
            val text =
                NavigationFeature.first(
                    NavigationFeature.providerLaneHighlightText(intent),
                    NavigationFeature.visualLaneItemsText(intent),
                )
            if (text.isNullOrEmpty()) return ""
            val straight: Boolean = NavigationFeature.containsLaneToken(text, "straight")
            val left: Boolean = NavigationFeature.visualLaneHasLeft(text)
            val right: Boolean = NavigationFeature.visualLaneHasRight(text)
            return NavigationFeature.grayRoadFromBasicDirections(straight, left, right)
        }

        private fun visualLaneItemsText(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.text(intent, "raw_lane_items"),
                NavigationFeature.text(intent, "ignored_raw_lane_items"),
                NavigationFeature.text(intent, "lane_topology_json"),
                NavigationFeature.text(intent, "direction_sign_items"),
                NavigationFeature.text(intent, "raw_direction_sign_items"),
                NavigationFeature.text(intent, "lane_items"),
                NavigationFeature.text(intent, "ignored_lane_items"),
                NavigationFeature.text(intent, "ignored_recommended_lanes"),
                NavigationFeature.text(intent, "ignored_lane_type"),
                NavigationFeature.text(intent, "ignored_allowed_directions"),
            )
        }

        private fun providerLaneHighlightText(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.text(intent, "lane_highlight"),
                NavigationFeature.text(intent, "lane_highlighted_direction"),
                NavigationFeature.text(intent, "highlighted_direction"),
                NavigationFeature.text(intent, "highlighted_directions"),
                NavigationFeature.text(intent, "recommended_lanes"),
                NavigationFeature.text(intent, "ignored_recommended_lanes"),
                NavigationFeature.text(intent, "ignored_lane_maneuver"),
            )
        }

        private fun providerVisualLaneSource(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            return value.contains(YandexCoreBridgeContract.SOURCE)
        }

        private fun staleProviderLaneTopologyConflict(
            intent: Intent?,
            sourceLower: String?,
            mainManeuver: String,
            highlightManeuver: String,
            laneDistance: String?,
        ): Boolean {
            if (!NavigationFeature.providerVisualLaneSource(sourceLower)) return false
            if (!NavigationFeature.nonZeroDistance(laneDistance).isNullOrEmpty()) return false
            if (
                !NavigationFeature.isUsableManeuver(mainManeuver) ||
                    !NavigationFeature.isUsableManeuver(highlightManeuver)
            )
                return false
            if (NavigationFeature.sameManeuverFamily(mainManeuver, highlightManeuver)) return false
            val topology =
                NavigationFeature.first(
                    NavigationFeature.providerRawLaneTopologyText(intent),
                    NavigationFeature.explicitGrayRoadTopologyText(intent),
                    NavigationFeature.text(intent, "allowed_directions"),
                    NavigationFeature.text(intent, "allowedDirections"),
                )
            if (topology.isNullOrEmpty()) return false
            val mainFamily: String = NavigationFeature.maneuverFamily(mainManeuver)
            val highlightFamily: String = NavigationFeature.maneuverFamily(highlightManeuver)
            return ("left".equals(mainFamily) && "right".equals(highlightFamily)) ||
                ("right".equals(mainFamily) && "left".equals(highlightFamily))
        }

        private fun providerLaneSchemeBeyondMain(
            intent: Intent?,
            sourceLower: String?,
            mainManeuver: String,
            highlightManeuver: String,
            grayRoad: String?,
            mainDistance: String?,
            laneDistance: String?,
        ): Boolean {
            if (!NavigationFeature.providerVisualLaneSource(sourceLower)) return false
            if (!NavigationFeature.hasLaneData(intent)) return false
            if (!NavigationFeature.isUsableManeuver(mainManeuver)) return false
            val mainMeters: Float =
                NavigationFeature.distanceMeters(NavigationFeature.nonZeroDistance(mainDistance))
            val laneMeters: Float =
                NavigationFeature.distanceMeters(NavigationFeature.nonZeroDistance(laneDistance))
            if (mainMeters <= 0f || laneMeters <= 0f) return false
            val tolerance: Float = Math.max(20f, mainMeters * 0.12f)
            if (laneMeters <= mainMeters + tolerance) return false
            if (NavigationFeature.grayRoadIncompatibleWithManeuver(mainManeuver, grayRoad))
                return true
            if (!NavigationFeature.isUsableManeuver(highlightManeuver)) return false
            if (NavigationFeature.sameManeuverFamily(mainManeuver, highlightManeuver)) return false
            val mainFamily: String = NavigationFeature.maneuverFamily(mainManeuver)
            val highlightFamily: String = NavigationFeature.maneuverFamily(highlightManeuver)
            return ("left".equals(mainFamily) && "right".equals(highlightFamily)) ||
                ("right".equals(mainFamily) && "left".equals(highlightFamily))
        }

        private fun hasVisualLaneItems(intent: Intent?): Boolean {
            return !NavigationFeature.visualLaneItemsText(intent).isNullOrEmpty()
        }

        private fun visualLaneDirectionsText(intent: Intent?): String {
            val text =
                NavigationFeature.first(
                    NavigationFeature.providerLaneHighlightText(intent),
                    NavigationFeature.visualLaneItemsText(intent),
                )
            if (text.isNullOrEmpty()) return ""
            val straight: Boolean = NavigationFeature.containsLaneToken(text, "straight")
            val left: Boolean = NavigationFeature.visualLaneHasLeft(text)
            val right: Boolean = NavigationFeature.visualLaneHasRight(text)
            val out: StringBuilder = StringBuilder()
            NavigationFeature.appendPart(out, "прямо", straight)
            NavigationFeature.appendPart(out, "налево", left)
            NavigationFeature.appendPart(out, "направо", right)
            return out.toString()
        }

        private fun visualLaneHasLeft(text: String?): Boolean {
            val value = NavigationFeature.clean(text).lowercase(Locale.US)
            return NavigationFeature.containsLaneToken(value, "left")
        }

        private fun visualLaneHasRight(text: String?): Boolean {
            val value = NavigationFeature.clean(text).lowercase(Locale.US)
            return NavigationFeature.containsLaneToken(value, "right")
        }

        private fun grayRoadFromBasicDirections(
            straight: Boolean,
            left: Boolean,
            right: Boolean,
        ): String {
            if (straight && left && right) return "context_ra_gray_straight_left_right"
            if (straight && left) return "context_ra_gray_straight_left"
            if (straight && right) return "context_ra_gray_straight_right"
            if (left && right) return "context_ra_gray_left_right"
            if (straight) return "context_ra_gray_straight"
            if (left) return "context_ra_gray_left"
            if (right) return "context_ra_gray_right"
            return ""
        }

        private fun mergeGrayRoads(firstGray: String, secondGray: String?): String? {
            val mask: Int =
                NavigationFeature.grayRoadMask(firstGray) or
                    NavigationFeature.grayRoadMask(secondGray)
            return if (mask <= 0) NavigationFeature.first(firstGray, secondGray)
            else NavigationFeature.grayRoadFromMask(mask)
        }

        private fun yandexLaneDistanceSource(source: String?): Boolean {
            val value = NavigationFeature.clean(source).lowercase(Locale.US)
            if (!value.contains("yandex")) return false
            return value.contains("lane_distance") ||
                value.contains("guidance_tick") ||
                value.contains("direction_sign") ||
                value.contains("auto_widget_signs")
        }

        private fun laneHighlightedManeuver(intent: Intent?, sourceLower: String?): String? {
            if (!NavigationFeature.trustedLaneDirectionSource(sourceLower)) return ""
            val precise =
                ManeuverCanonicalizer.canonicalize(
                    NavigationFeature.providerLaneHighlightText(intent)
                )
            if (NavigationFeature.isUsableManeuver(precise)) return precise
            val uturnLeft: Boolean = NavigationFeature.laneHighlightHas(intent, "left180")
            val uturnRight: Boolean = NavigationFeature.laneHighlightHas(intent, "right180")
            val left: Boolean = NavigationFeature.laneHighlightHas(intent, "left")
            val right: Boolean = NavigationFeature.laneHighlightHas(intent, "right")
            val straight: Boolean = NavigationFeature.laneHighlightHas(intent, "straight")
            val families: Int =
                ((if (uturnLeft || uturnRight) 1 else 0) +
                    (if (left) 1 else 0) +
                    (if (right) 1 else 0) +
                    (if (straight) 1 else 0))
            if (families != 1) return ""
            if (uturnLeft) return "context_ra_turn_back_left"
            if (uturnRight) return "context_ra_turn_back_right"
            if (left) return "context_ra_turn_left"
            if (right) return "context_ra_turn_right"
            if (straight) return "context_ra_forward"
            return ""
        }

        private fun directionSignManeuver(intent: Intent?): String? {
            val raw = NavigationFeature.directionSignItemsText(intent)
            if (raw.isNullOrEmpty()) return ""
            val highlighted = NavigationFeature.highlightedDirectionSignText(raw)
            return NavigationFeature.directionManeuverFromText(
                NavigationFeature.first(highlighted, raw)
            )
        }

        private fun directionManeuverFromText(raw: String?): String? {
            val value = NavigationFeature.clean(raw).lowercase(Locale.US).replace('-', '_')
            if (value.isNullOrEmpty()) {
                return ""
            }
            val precise = ManeuverCanonicalizer.canonicalize(value)
            if (NavigationFeature.isUsableManeuver(precise)) return precise
            if (NavigationFeature.containsDirectionToken(value, "right180")) {
                return "context_ra_turn_back_right"
            }
            if (NavigationFeature.containsDirectionToken(value, "left180"))
                return "context_ra_turn_back_left"
            if (NavigationFeature.containsDirectionToken(value, "uturn"))
                return "context_ra_turn_back"
            val exitRight: Boolean =
                NavigationFeature.containsAny(
                    value,
                    "exit_right",
                    "ramp_right",
                    "take_right",
                    "slip_right",
                    "fork_right",
                )
            val exitLeft: Boolean =
                NavigationFeature.containsAny(
                    value,
                    "exit_left",
                    "ramp_left",
                    "take_left",
                    "slip_left",
                    "fork_left",
                )
            val right: Boolean =
                exitRight || NavigationFeature.containsDirectionToken(value, "right")
            val left: Boolean = exitLeft || NavigationFeature.containsDirectionToken(value, "left")
            val straight: Boolean = NavigationFeature.containsDirectionToken(value, "straight")
            val families: Int =
                (if (left) 1 else 0) + (if (right) 1 else 0) + (if (straight) 1 else 0)
            if (families != 1) return ""
            if (exitRight) return "context_ra_exit_right"
            if (exitLeft) return "context_ra_exit_left"
            if (right) return "context_ra_turn_right"
            if (left) return "context_ra_turn_left"
            if (straight) return "context_ra_forward"
            return ""
        }

        private fun highlightedDirectionSignText(raw: String?): String {
            val text = NavigationFeature.clean(raw)
            if (text.isNullOrEmpty()) return ""
            val out: StringBuilder = StringBuilder()
            val matcher = Pattern.compile("highlight=([^\\s|,;]+)").matcher(text)
            while (matcher.find()) {
                if (out.length > 0) out.append(' ')
                out.append(matcher.group(1))
            }
            return out.toString()
        }

        private fun directionSignItemsText(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.text(intent, "direction_sign_items"),
                NavigationFeature.text(intent, "raw_direction_sign_items"),
                NavigationFeature.text(intent, "direction_sign"),
                NavigationFeature.text(intent, "road_sign"),
            )
        }

        private fun containsDirectionToken(value: String?, token: String): Boolean {
            val text = NavigationFeature.clean(value).lowercase(Locale.US)
            if (text.isNullOrEmpty()) return false
            if ("straight".equals(token)) {
                return NavigationFeature.containsAny(
                    text,
                    "straightahead",
                    "straight_ahead",
                    "straight",
                    "forward",
                    "ahead",
                    "прям",
                )
            }
            if ("right".equals(token)) {
                return NavigationFeature.containsAny(
                    text,
                    "right90",
                    "right45",
                    "right135",
                    "rightshift",
                    "rightfromleft",
                    "turn_right",
                    "right",
                    "направ",
                    "прав",
                )
            }
            if ("left".equals(token)) {
                return NavigationFeature.containsAny(
                    text,
                    "left90",
                    "left45",
                    "left135",
                    "leftshift",
                    "leftfromright",
                    "turn_left",
                    "left",
                    "налев",
                    "лев",
                )
            }
            if ("right180".equals(token)) {
                return NavigationFeature.containsAny(
                    text,
                    "right180",
                    "turn_back_right",
                    "uturn_right",
                    "u_turn_right",
                )
            }
            if ("left180".equals(token)) {
                return NavigationFeature.containsAny(
                    text,
                    "left180",
                    "turn_back_left",
                    "uturn_left",
                    "u_turn_left",
                )
            }
            if ("uturn".equals(token)) {
                return NavigationFeature.containsAny(text, "turn_back", "u_turn", "uturn", "развор")
            }
            return NavigationFeature.containsAny(text, token)
        }

        private fun containsAny(value: String?, vararg tokens: String?): Boolean {
            val text = NavigationFeature.clean(value).lowercase(Locale.US)
            if (text.isNullOrEmpty()) return false
            for (token: String? in tokens) {
                if (!token.isNullOrEmpty() && text.contains(token.lowercase(Locale.US))) {
                    return true
                }
            }
            return false
        }

        private fun dashboardManeuver(icon: String?, description: String?): String? {
            val value = NavigationFeature.clean(NavigationFeature.first(icon, description))
            val p =
                NavigationFeature.clean(
                        (if (icon == null) "" else icon) +
                            " " +
                            (if (description == null) "" else description)
                    )
                    .lowercase(Locale.US)
                    .replace('-', '_')
            if (p.isNullOrEmpty()) return ""
            if (
                p.contains("roundabout") ||
                    p.contains("circular") ||
                    p.contains("кольц") ||
                    p.contains("круг")
            ) {
                val exit: String = NavigationFeature.roundaboutExitFromText(p)
                if (!exit.isNullOrEmpty()) return exit
                return "context_ra_in_circular_movement"
            }
            val left: Boolean = p.contains("left") || p.contains("лев")
            val right: Boolean = p.contains("right") || p.contains("прав")
            if (
                p.contains("u_turn") ||
                    p.contains("uturn") ||
                    p.contains("turn_back") ||
                    p.contains("развор")
            ) {
                if (right) return "context_ra_turn_back_right"
                if (left) return "context_ra_turn_back_left"
                return "context_ra_turn_back"
            }
            if (right && (p.contains("sharp") || p.contains("hard") || p.contains("резко"))) {
                return "context_ra_hard_turn_right"
            }
            if (left && (p.contains("sharp") || p.contains("hard") || p.contains("резко"))) {
                return "context_ra_hard_turn_left"
            }
            if (right && (p.contains("exit") || p.contains("ramp") || p.contains("съезд"))) {
                return "context_ra_exit_right"
            }
            if (left && (p.contains("exit") || p.contains("ramp") || p.contains("съезд"))) {
                return "context_ra_exit_left"
            }
            if (
                right &&
                    (p.contains("keep") ||
                        p.contains("slight") ||
                        p.contains("take") ||
                        p.contains("держитесь") ||
                        p.contains("правее"))
            ) {
                return "context_ra_exit_right"
            }
            if (
                left &&
                    (p.contains("keep") ||
                        p.contains("slight") ||
                        p.contains("take") ||
                        p.contains("держитесь") ||
                        p.contains("левее"))
            ) {
                return "context_ra_exit_left"
            }
            if (right || p.contains("направо")) return "context_ra_turn_right"
            if (left || p.contains("налево")) return "context_ra_turn_left"
            if (p.contains("straight") || p.contains("forward") || p.contains("прямо")) {
                return "context_ra_forward"
            }
            if (NavigationFeature.containsFinishToken(p)) return ""
            val normalized = NavigationFeature.normalizeManeuver(value, 0)
            return if (NavigationFeature.containsFinishToken(normalized!!.lowercase(Locale.US))) ""
            else normalized
        }

        private fun dashboardFinishReached(
            icon: String?,
            routeDistance: String,
            maneuverDistance: String,
        ): Boolean {
            if (
                NavigationFeature.routeFinished(routeDistance) ||
                    NavigationFeature.routeFinished(maneuverDistance)
            )
                return true
            val iconText = NavigationFeature.clean(icon).lowercase(Locale.US).replace('-', '_')
            if (!NavigationFeature.containsFinishToken(iconText)) return false
            val routeMeters: Float = NavigationFeature.distanceMeters(routeDistance)
            val maneuverMeters: Float = NavigationFeature.distanceMeters(maneuverDistance)
            val meters: Float = if (routeMeters > 0f) routeMeters else maneuverMeters
            if (meters > 0f) return meters <= NavigationFeature.DASHBOARD_FINISH_METERS
            return false
        }

        private fun containsFinishToken(value: String?): Boolean {
            if (value.isNullOrEmpty()) return false
            val p = value.lowercase(Locale.US)
            return p.contains("finish") ||
                p.contains("arriv") ||
                p.contains("destination") ||
                p.contains("финиш") ||
                p.contains("место назначения")
        }

        private fun dgisMicroManeuver(
            maneuver: String?,
            rawText: String?,
            distanceText: String?,
        ): Boolean {
            if (
                NavigationFeature.isFinishManeuver(maneuver) ||
                    NavigationFeature.isRoundaboutManeuver(maneuver)
            )
                return false
            val p = NavigationFeature.clean(rawText).lowercase(Locale.US).replace('-', '_')
            if (
                p.contains("lane") ||
                    p.contains("полос") ||
                    p.contains("ряд") ||
                    p.contains("держитесь") ||
                    p.contains("левее") ||
                    p.contains("правее")
            ) {
                return true
            }
            if (p.contains("съезд") || p.contains("exit") || p.contains("ramp")) return true
            val meters: Float = NavigationFeature.distanceMeters(distanceText)
            if (meters <= 0f || meters > NavigationFeature.DGIS_MICRO_DISTANCE_METERS) return false
            val id = NavigationFeature.clean(maneuver)
            return id.contains("take_") ||
                id.contains("exit_") ||
                id.contains("hard_turn") ||
                id.contains("turn_back")
        }

        private fun roundaboutExitFromText(value: String?): String {
            val p = NavigationFeature.clean(value).lowercase(Locale.US)
            val numbered = NavigationFeature.roundaboutExitNumberFromText(p)
            if (!numbered.isNullOrEmpty()) return "context_ra_roundabout_exit_" + numbered
            if (p.contains("first") || p.contains("перв")) {
                return "context_ra_roundabout_exit_1"
            }
            if (p.contains("second") || p.contains("втор")) {
                return "context_ra_roundabout_exit_2"
            }
            if (p.contains("third") || p.contains("трет")) {
                return "context_ra_roundabout_exit_3"
            }
            if (p.contains("fourth") || p.contains("четв")) {
                return "context_ra_roundabout_exit_4"
            }
            return ""
        }

        private fun roundaboutExitFromRouteSection(value: String?): String {
            val text = NavigationFeature.clean(value)
            if (text.isNullOrEmpty()) return ""
            val description =
                Pattern.compile(
                        "description=([^|;]*?)(?:\\s+raw=|\\s+toponym=|,|;|\\||$)",
                        Pattern.CASE_INSENSITIVE,
                    )
                    .matcher(text)
            if (description.find()) {
                val parsed: String = NavigationFeature.roundaboutExitFromText(description.group(1))
                if (!parsed.isNullOrEmpty()) return parsed
            }
            val exit =
                Pattern.compile(
                        "(\\d+)\\s*[-\\u2010-\\u2015]?\\s*(?:й|st|nd|rd|th)?\\s*(?:съезд|exit)",
                        Pattern.CASE_INSENSITIVE,
                    )
                    .matcher(text)
            if (exit.find()) return NavigationFeature.roundaboutExitFromText(exit.group(1))
            return ""
        }

        private fun normalizeManeuver(maneuver: String?, lr: Int): String? {
            val value = NavigationFeature.clean(maneuver)
            if (value.isNullOrEmpty()) return value
            val p = value.lowercase(Locale.US)
            if (p.startsWith("context_ra_")) return NavigationFeature.canonicalManeuver(value)
            if (p.contains("round") || p.contains("круг")) {
                val exit: String = NavigationFeature.roundaboutExitFromText(p)
                return if (exit.isNullOrEmpty()) "context_ra_in_circular_movement" else exit
            }
            val precise = ManeuverCanonicalizer.canonicalize(value, lr)
            if (NavigationFeature.isUsableManeuver(precise)) return precise
            return value
        }

        private fun canonicalManeuver(maneuver: String?): String? {
            val value = NavigationFeature.clean(maneuver)
            val precise = ManeuverCanonicalizer.canonicalize(value)
            return if (NavigationFeature.isUsableManeuver(precise)) precise else value
        }

        private fun maneuverLabel(maneuver: String?): String? {
            return NavigationFeature.maneuverLabel(maneuver, "")
        }

        private fun maneuverLabel(maneuver: String?, roundaboutExit: String?): String? {
            val p: String? = if (maneuver == null) "" else maneuver.lowercase(Locale.US)
            if (p!!.contains("finish") || p.contains("arriv") || p.contains("destination"))
                return "финиш"
            if (p.contains("out_circular") || p.contains("leave_roundabout"))
                return "съезд с кругового"
            if (p.contains("roundabout_exit_")) {
                val exit =
                    NavigationFeature.first(
                        roundaboutExit,
                        NavigationFeature.roundaboutExitLabelFromId(p),
                    )
                return if (exit.isNullOrEmpty()) "съезд с кругового" else "круговое, " + exit
            }
            if (p.contains("round") || p.contains("circular") || p.contains("круг")) {
                return if (roundaboutExit.isNullOrEmpty()) "круговое движение"
                else "круговое, " + roundaboutExit
            }
            if (p.contains("turn_back") || p.contains("uturn") || p.contains("развор")) {
                if (p.contains("right")) return "разворот направо"
                if (p.contains("left")) return "разворот налево"
                return "разворот"
            }
            if (p.contains("keep_left")) return "держаться левее"
            if (p.contains("keep_right")) return "держаться правее"
            if (p.contains("exit_left")) return "съезд налево"
            if (p.contains("exit_right")) return "съезд направо"
            if (p.contains("take_left")) return "держитесь левее"
            if (p.contains("take_right")) return "держитесь правее"
            if (p.contains("hard_turn_left")) return "резко налево"
            if (p.contains("hard_turn_right")) return "резко направо"
            if (p.contains("turn_left") || p.contains("left")) return "налево"
            if (p.contains("turn_right") || p.contains("right")) return "направо"
            if (p.contains("straight") || p.contains("forward")) return "прямо"
            if ("unknown".equals(p)) return "расчёт маршрута"
            if (maneuver.isNullOrEmpty()) return ""
            return maneuver
        }

        private fun roundaboutExit(intent: Intent?, maneuver: String?): String {
            if (
                !NavigationFeature.isRoundaboutManeuver(maneuver) &&
                    !NavigationFeature.hasRoundaboutContext(intent)
            )
                return ""
            val explicit =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "exit_number"),
                    NavigationFeature.text(intent, "exitNumber"),
                    NavigationFeature.text(intent, "roundabout_exit"),
                    NavigationFeature.text(intent, "roundaboutExit"),
                    NavigationFeature.text(intent, "circular_exit"),
                    NavigationFeature.text(intent, "circularExit"),
                    NavigationFeature.text(intent, "exit_name"),
                    NavigationFeature.text(intent, "exitName"),
                    NavigationFeature.text(intent, "exit"),
                    NavigationFeature.text(intent, "direction_sign"),
                    NavigationFeature.text(intent, "directionSign"),
                    NavigationFeature.text(intent, "road_sign"),
                    NavigationFeature.text(intent, "roadSign"),
                    NavigationFeature.textByKeyPart(intent, "exit"),
                    NavigationFeature.textByKeyPart(intent, "roundabout"),
                    NavigationFeature.textByKeyPart(intent, "circular"),
                )
            var parsed: String? = NavigationFeature.roundaboutExitText(explicit)
            if (!parsed.isNullOrEmpty()) return parsed
            parsed = NavigationFeature.roundaboutExitFromExtras(intent)
            if (!parsed.isNullOrEmpty()) return parsed
            return NavigationFeature.roundaboutExitText(maneuver)
        }

        private fun roundaboutExitLabelFromId(maneuver: String?): String {
            val matcher =
                Pattern.compile("roundabout_exit_([1-9])")
                    .matcher(NavigationFeature.clean(maneuver))
            return if (matcher.find()) NavigationFeature.roundaboutExitLabel(matcher.group(1))
            else ""
        }

        private fun roundaboutManeuver(
            maneuver: String?,
            roundaboutExit: String?,
            intent: Intent?,
            sourceLower: String?,
        ): String? {
            val exit: Int = NavigationFeature.roundaboutExitIndex(roundaboutExit)
            if (exit >= 1 && exit <= 4) return "context_ra_roundabout_exit_" + exit
            if (NavigationFeature.roundaboutExitContext(intent, maneuver, sourceLower)) {
                return "context_ra_out_circular_movement"
            }
            if (NavigationFeature.isRoundaboutManeuver(maneuver)) return maneuver
            return "context_ra_in_circular_movement"
        }

        private fun roundaboutExitContext(
            intent: Intent?,
            maneuver: String?,
            sourceLower: String?,
        ): Boolean {
            val seed =
                NavigationFeature.clean(maneuver.toString() + " " + sourceLower)
                    .lowercase(Locale.US)
                    .replace('-', '_')
            if (
                NavigationFeature.containsAny(
                    seed,
                    "out_circular",
                    "leave_roundabout",
                    "exit_roundabout",
                    "roundabout_exit",
                    "circular_exit",
                    "съезд",
                    "выезд",
                    "покин",
                )
            ) {
                return true
            }
            val extras: Bundle? = if (intent == null) null else intent.getExtras()
            if (extras == null) return false
            for (key: String? in extras.keySet()) {
                val lowerKey = NavigationFeature.clean(key).lowercase(Locale.US).replace('-', '_')
                val value: Any? = extras.get(key)
                val lowerText: String? =
                    if (value == null) ""
                    else
                        NavigationFeature.clean(java.lang.String.valueOf(value))
                            .lowercase(Locale.US)
                            .replace('-', '_')
                val joined: String = lowerKey + " " + lowerText
                if (
                    NavigationFeature.containsAny(
                        joined,
                        "out_circular",
                        "leave_roundabout",
                        "exit_roundabout",
                        "roundabout_exit",
                        "circular_exit",
                        "съезд",
                        "выезд",
                        "покин",
                    )
                ) {
                    return true
                }
            }
            return false
        }

        private fun isRoundaboutExitManeuver(maneuver: String?): Boolean {
            val value = NavigationFeature.clean(maneuver)
            return value.startsWith("context_ra_roundabout_exit_") ||
                "context_ra_out_circular_movement".equals(value)
        }

        private fun grayRoadTopologyText(intent: Intent?): String? {
            return NavigationFeature.grayRoadTopologyText(intent, "")
        }

        private fun grayRoadTopologyText(intent: Intent?, sourceLower: String?): String? {
            if (NavigationFeature.providerVisualLaneSource(sourceLower)) {
                return NavigationFeature.providerTrustedGrayRoadTopologyText(intent)
            }
            val roadTopology = NavigationFeature.explicitGrayRoadTopologyText(intent)
            if (!roadTopology.isNullOrEmpty()) return roadTopology
            val weakerRoadTopology =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "routeRoadOptions"),
                    NavigationFeature.text(intent, "roadOptions"),
                    NavigationFeature.text(intent, "roadDirections"),
                    NavigationFeature.text(intent, "availableDirections"),
                    NavigationFeature.text(intent, "turnOptions"),
                    NavigationFeature.text(intent, "routeOptions"),
                )
            if (!weakerRoadTopology.isNullOrEmpty()) return weakerRoadTopology
            if (NavigationFeature.signOnlyLaneSource(sourceLower)) return ""
            val topology =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "lane_topology"),
                    NavigationFeature.text(intent, "raw_lane_topology"),
                )
            if (!topology.isNullOrEmpty()) return topology
            return if (NavigationFeature.canUseAllowedDirectionsForGrayRoad(sourceLower))
                NavigationFeature.first(
                    NavigationFeature.text(intent, "allowed_directions"),
                    NavigationFeature.text(intent, "allowedDirections"),
                )
            else ""
        }

        private fun providerTrustedGrayRoadTopologyText(intent: Intent?): String? {
            val rawLaneTopology = NavigationFeature.providerRawLaneTopologyText(intent)
            if (!rawLaneTopology.isNullOrEmpty()) return rawLaneTopology
            val rawRoadScheme =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "road_scheme_raw"),
                    NavigationFeature.text(intent, "raw_road_scheme"),
                    NavigationFeature.text(intent, "road_scheme_items"),
                    NavigationFeature.text(intent, "direction_sign_items"),
                    NavigationFeature.text(intent, "raw_direction_sign_items"),
                    NavigationFeature.text(intent, "route_road_raw"),
                    NavigationFeature.text(intent, "gray_road_raw"),
                )
            if (!rawRoadScheme.isNullOrEmpty()) return rawRoadScheme
            val source =
                NavigationFeature.clean(
                        NavigationFeature.first(
                            NavigationFeature.text(intent, "route_road_source"),
                            NavigationFeature.text(intent, "gray_road_source"),
                            NavigationFeature.text(intent, "road_scheme_source"),
                        )
                    )
                    .lowercase(Locale.US)
            if (
                NavigationFeature.containsAny(source, "road_scheme", "direction_sign", "route_road")
            ) {
                return NavigationFeature.explicitGrayRoadTopologyText(intent)
            }
            return NavigationFeature.explicitGrayRoadTopologyText(intent)
        }

        private fun providerRawLaneTopologyText(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.text(intent, "raw_lane_items"),
                NavigationFeature.text(intent, "ignored_raw_lane_items"),
                NavigationFeature.text(intent, "lane_topology_json"),
                NavigationFeature.text(intent, "direction_sign_items"),
                NavigationFeature.text(intent, "raw_direction_sign_items"),
                NavigationFeature.text(intent, "road_scheme_raw"),
                NavigationFeature.text(intent, "raw_road_scheme"),
                NavigationFeature.text(intent, "road_scheme_items"),
                NavigationFeature.text(intent, "raw_lane_direction"),
                NavigationFeature.text(intent, "direction_sign"),
                NavigationFeature.text(intent, "road_sign"),
            )
        }

        private fun explicitGrayRoadTopologyText(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.text(intent, "route_road_options"),
                NavigationFeature.text(intent, "routeRoadOptions"),
                NavigationFeature.text(intent, "gray_road_options"),
                NavigationFeature.text(intent, "grayRoadOptions"),
                NavigationFeature.text(intent, "road_options"),
                NavigationFeature.text(intent, "roadOptions"),
                NavigationFeature.text(intent, "road_directions"),
                NavigationFeature.text(intent, "roadDirections"),
                NavigationFeature.text(intent, "turn_options"),
                NavigationFeature.text(intent, "turnOptions"),
                NavigationFeature.text(intent, "route_options"),
                NavigationFeature.text(intent, "routeOptions"),
            )
        }

        private fun laneRawItemsText(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.text(intent, "raw_lane_items"),
                NavigationFeature.text(intent, "lane_items"),
                NavigationFeature.text(intent, "direction_sign_items"),
                NavigationFeature.text(intent, "raw_direction_sign_items"),
                NavigationFeature.text(intent, "raw_lane_direction"),
                NavigationFeature.text(intent, "direction_sign"),
                NavigationFeature.text(intent, "road_sign"),
            )
        }

        private fun boolAny(intent: Intent?, vararg keys: String?): Boolean {
            for (key: String? in keys) {
                if (NavigationFeature.bool(intent, key, false)) return true
            }
            return false
        }

        private fun hasAnyExtra(intent: Intent?, vararg keys: String?): Boolean {
            if (intent == null) return false
            val extras: Bundle? = intent.getExtras()
            if (extras == null) return false
            for (key: String? in keys) {
                if (extras.containsKey(key)) return true
            }
            return false
        }

        private fun hasExplicitGrayDirectionFlags(intent: Intent?): Boolean {
            return NavigationFeature.hasAnyExtra(
                intent,
                "lane_has_straight",
                "road_has_straight",
                "has_straight",
                "can_go_straight",
                "allow_straight",
                "available_straight",
                "lane_has_right",
                "road_has_right",
                "has_right",
                "can_go_right",
                "allow_right",
                "available_right",
                "lane_has_left",
                "road_has_left",
                "has_left",
                "can_go_left",
                "allow_left",
                "available_left",
            )
        }

        private fun signOnlyLaneSource(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            return value.contains("rendered_lane") ||
                value.contains("lane_distance") ||
                value.contains("lane_guidance") ||
                value.contains("guidance_tick") ||
                value.contains("projected_lanes") ||
                value.contains("panel_lanes")
        }

        private fun weakVisualLaneSingleSource(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            return value.contains("rendered_lane") ||
                value.contains("projected_lanes") ||
                value.contains("panel_lanes")
        }

        private fun visualGrayRoadCanClearActiveMicro(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            if (NavigationFeature.weakVisualLaneSingleSource(value)) return false
            return value.contains("lane_distance") ||
                value.contains("guidance_tick") ||
                value.contains("lane_guidance") ||
                value.contains("direction_sign") ||
                value.contains("auto_widget_signs")
        }

        private fun completeLaneGraySource(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            return value.contains("lane_guidance") &&
                !value.contains("guidance_tick") &&
                !value.contains("lane_distance") &&
                !value.contains("rendered_lane")
        }

        private fun narrowLaneGraySource(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            return value.contains("guidance_tick") ||
                value.contains("lane_distance") ||
                value.contains("rendered_lane")
        }

        private fun canUseAllowedDirectionsForGrayRoad(sourceLower: String?): Boolean {
            val value = NavigationFeature.clean(sourceLower).lowercase(Locale.US)
            if (NavigationFeature.signOnlyLaneSource(value)) return false
            return value.contains("lane_guidance") ||
                value.contains("route_road") ||
                value.contains("route_options") ||
                value.contains("road_options") ||
                value.contains("road_directions") ||
                value.contains("available_directions")
        }

        private fun canUseLaneRawItemsForGrayRoad(sourceLower: String?): Boolean {
            if (NavigationFeature.providerVisualLaneSource(sourceLower)) return false
            return !NavigationFeature.signOnlyLaneSource(sourceLower)
        }

        private fun laneTextHas(intent: Intent?, token: String?): Boolean {
            return NavigationFeature.laneTextHas(intent, token, "")
        }

        private fun laneTextHas(intent: Intent?, token: String?, sourceLower: String?): Boolean {
            val topology = NavigationFeature.grayRoadTopologyText(intent, sourceLower)
            if (!topology.isNullOrEmpty()) {
                if (NavigationFeature.containsLaneToken(topology, token)) return true
                return NavigationFeature.canUseLaneRawItemsForGrayRoad(sourceLower) &&
                    NavigationFeature.containsLaneToken(
                        NavigationFeature.laneRawItemsText(intent),
                        token,
                    )
            }
            if (NavigationFeature.hasExplicitGrayDirectionFlags(intent)) return false
            if (!NavigationFeature.canUseLaneRawItemsForGrayRoad(sourceLower)) return false
            return NavigationFeature.containsLaneToken(
                NavigationFeature.laneRawItemsText(intent),
                token,
            )
        }

        private fun laneHasAny(intent: Intent?, vararg tokens: String?): Boolean {
            return NavigationFeature.laneHasAnyForGrayRoad(intent, "", *tokens)
        }

        private fun laneHasAnyForGrayRoad(
            intent: Intent?,
            sourceLower: String?,
            vararg tokens: String?,
        ): Boolean {
            for (token: String? in tokens) {
                if (NavigationFeature.laneTextHas(intent, token, sourceLower)) return true
            }
            return false
        }

        private fun hasLaneData(intent: Intent?): Boolean {
            return !NavigationFeature.grayRoadTopologyText(intent).isNullOrEmpty() ||
                !NavigationFeature.laneRawItemsText(intent).isNullOrEmpty() ||
                !NavigationFeature.providerLaneHighlightText(intent).isNullOrEmpty() ||
                NavigationFeature.hasLaneTopologyData(intent) ||
                NavigationFeature.hasVisualLaneItems(intent) ||
                NavigationFeature.hasExplicitGrayDirectionFlags(intent)
        }

        private fun hasLaneTopologyData(intent: Intent?): Boolean {
            return !NavigationFeature.first(
                    NavigationFeature.text(intent, "lane_topology"),
                    NavigationFeature.text(intent, "lane_topology_json"),
                    NavigationFeature.text(intent, "raw_lane_topology"),
                    NavigationFeature.text(intent, "lane_sign_topology"),
                    NavigationFeature.text(intent, "lane_sign_topology_json"),
                )
                .isNullOrEmpty()
        }

        private fun grayRoadHasStraight(intent: Intent?): Boolean {
            return NavigationFeature.grayRoadHasStraight(intent, "")
        }

        private fun grayRoadHasStraight(intent: Intent?, sourceLower: String?): Boolean {
            return NavigationFeature.boolAny(
                intent,
                "lane_has_straight",
                "road_has_straight",
                "has_straight",
                "can_go_straight",
                "allow_straight",
                "available_straight",
            ) || NavigationFeature.laneTextHas(intent, "straight", sourceLower)
        }

        private fun grayRoadHasRight(intent: Intent?): Boolean {
            return NavigationFeature.grayRoadHasRight(intent, "")
        }

        private fun grayRoadHasRight(intent: Intent?, sourceLower: String?): Boolean {
            return NavigationFeature.boolAny(
                intent,
                "lane_has_right",
                "road_has_right",
                "has_right",
                "can_go_right",
                "allow_right",
                "available_right",
            ) || NavigationFeature.laneTextHas(intent, "right", sourceLower)
        }

        private fun grayRoadHasLeft(intent: Intent?): Boolean {
            return NavigationFeature.grayRoadHasLeft(intent, "")
        }

        private fun grayRoadHasLeft(intent: Intent?, sourceLower: String?): Boolean {
            return NavigationFeature.boolAny(
                intent,
                "lane_has_left",
                "road_has_left",
                "has_left",
                "can_go_left",
                "allow_left",
                "available_left",
            ) || NavigationFeature.laneTextHas(intent, "left", sourceLower)
        }

        private fun grayRoadHasUturnLeft(intent: Intent?, sourceLower: String?): Boolean {
            if (
                NavigationFeature.hasAnyExtra(
                    intent,
                    "lane_has_uturn_left",
                    "road_has_uturn_left",
                    "has_uturn_left",
                    "can_uturn_left",
                    "allow_uturn_left",
                    "available_uturn_left",
                )
            ) {
                return NavigationFeature.boolAny(
                    intent,
                    "lane_has_uturn_left",
                    "road_has_uturn_left",
                    "has_uturn_left",
                    "can_uturn_left",
                    "allow_uturn_left",
                    "available_uturn_left",
                )
            }
            return NavigationFeature.boolAny(
                intent,
                "lane_has_uturn_left",
                "road_has_uturn_left",
                "has_uturn_left",
                "can_uturn_left",
                "allow_uturn_left",
                "available_uturn_left",
            ) ||
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "left180",
                    "uturn_left",
                    "turn_back_left",
                    "u_turn_left",
                )
        }

        private fun grayRoadHasUturnRight(intent: Intent?, sourceLower: String?): Boolean {
            if (
                NavigationFeature.hasAnyExtra(
                    intent,
                    "lane_has_uturn_right",
                    "road_has_uturn_right",
                    "has_uturn_right",
                    "can_uturn_right",
                    "allow_uturn_right",
                    "available_uturn_right",
                )
            ) {
                return NavigationFeature.boolAny(
                    intent,
                    "lane_has_uturn_right",
                    "road_has_uturn_right",
                    "has_uturn_right",
                    "can_uturn_right",
                    "allow_uturn_right",
                    "available_uturn_right",
                )
            }
            return NavigationFeature.boolAny(
                intent,
                "lane_has_uturn_right",
                "road_has_uturn_right",
                "has_uturn_right",
                "can_uturn_right",
                "allow_uturn_right",
                "available_uturn_right",
            ) ||
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "right180",
                    "uturn_right",
                    "turn_back_right",
                    "u_turn_right",
                )
        }

        private fun grayRoadAssumeLeftAtJunction(
            intent: Intent?,
            straight: Boolean,
            right: Boolean,
            exitRight: Boolean,
            exitLeft: Boolean,
        ): Boolean {
            return NavigationFeature.grayRoadAssumeLeftAtJunction(
                intent,
                straight,
                right,
                exitRight,
                exitLeft,
                "",
            )
        }

        private fun grayRoadAssumeLeftAtJunction(
            intent: Intent?,
            straight: Boolean,
            right: Boolean,
            exitRight: Boolean,
            exitLeft: Boolean,
            sourceLower: String?,
        ): Boolean {
            return false
        }

        private fun laneHighlightHas(intent: Intent?, token: String?): Boolean {
            val text =
                NavigationFeature.clean(
                        NavigationFeature.first(
                            NavigationFeature.providerLaneHighlightText(intent),
                            NavigationFeature.laneRawItemsText(intent),
                            NavigationFeature.grayRoadTopologyText(intent),
                        )
                    )
                    .lowercase(Locale.US)
                    .replace('-', '_')
            val needle = NavigationFeature.clean(token).lowercase(Locale.US)
            if (text.isNullOrEmpty() || needle.isNullOrEmpty()) return false
            if (!NavigationFeature.providerLaneHighlightText(intent).isNullOrEmpty()) {
                return NavigationFeature.containsLaneToken(text, needle)
            }
            val matcher = Pattern.compile("highlight=([^\\s|,;]+)").matcher(text)
            while (matcher.find()) {
                if (NavigationFeature.containsLaneToken(matcher.group(1), needle)) return true
            }
            return false
        }

        private fun containsLaneToken(value: String?, token: String?): Boolean {
            val text = NavigationFeature.clean(value).lowercase(Locale.US)
            if (text.isNullOrEmpty() || token.isNullOrEmpty()) return false
            val parts = Pattern.compile("[,;\\s=:@#|()\\[\\]{}]+").split(text)
            for (part: String? in parts) {
                if (token.equals(part)) return true
            }
            if ("straight".equals(token)) {
                return text.contains("straight") ||
                    text.contains("forward") ||
                    text.contains("ahead") ||
                    text.contains("прям")
            }
            if ("left".equals(token)) {
                return NavigationFeature.containsAny(
                    text,
                    "left45",
                    "left90",
                    "left135",
                    "left_shift",
                    "leftshift",
                    "left_from_right",
                    "leftfromright",
                    "turn_left",
                ) || text.contains("налево") || text.contains("лево") || text.contains("левее")
            }
            if ("right".equals(token)) {
                return NavigationFeature.containsAny(
                    text,
                    "right45",
                    "right90",
                    "right135",
                    "right_shift",
                    "rightshift",
                    "right_from_left",
                    "rightfromleft",
                    "turn_right",
                ) || text.contains("направо") || text.contains("право") || text.contains("правее")
            }
            if (
                "left180".equals(token) ||
                    "uturn_left".equals(token) ||
                    "turn_back_left".equals(token) ||
                    "u_turn_left".equals(token)
            ) {
                return NavigationFeature.containsAny(
                    text,
                    "left180",
                    "turn_back_left",
                    "uturn_left",
                    "u_turn_left",
                )
            }
            if (
                "right180".equals(token) ||
                    "uturn_right".equals(token) ||
                    "turn_back_right".equals(token) ||
                    "u_turn_right".equals(token)
            ) {
                return NavigationFeature.containsAny(
                    text,
                    "right180",
                    "turn_back_right",
                    "uturn_right",
                    "u_turn_right",
                )
            }
            if ("uturn".equals(token) || "turn_back".equals(token) || "u_turn".equals(token)) {
                return NavigationFeature.containsAny(text, "turn_back", "u_turn", "uturn", "развор")
            }
            if ("hard_left".equals(token) || "sharp_left".equals(token)) {
                return (text.contains("hard") ||
                    text.contains("sharp") ||
                    text.contains("резко") ||
                    text.contains("круто")) && (text.contains("left") || text.contains("лев"))
            }
            if ("hard_right".equals(token) || "sharp_right".equals(token)) {
                return (text.contains("hard") ||
                    text.contains("sharp") ||
                    text.contains("резко") ||
                    text.contains("круто")) && (text.contains("right") || text.contains("прав"))
            }
            if (
                "exit_left".equals(token) ||
                    "ramp_left".equals(token) ||
                    "take_left".equals(token) ||
                    "slip_left".equals(token) ||
                    "fork_left".equals(token)
            ) {
                return (text.contains("exit") ||
                    text.contains("ramp") ||
                    text.contains("take") ||
                    text.contains("slip") ||
                    text.contains("fork") ||
                    text.contains("съезд") ||
                    text.contains("ответв")) && (text.contains("left") || text.contains("лев"))
            }
            if (
                "exit_right".equals(token) ||
                    "ramp_right".equals(token) ||
                    "take_right".equals(token) ||
                    "slip_right".equals(token) ||
                    "fork_right".equals(token)
            ) {
                return (text.contains("exit") ||
                    text.contains("ramp") ||
                    text.contains("take") ||
                    text.contains("slip") ||
                    text.contains("fork") ||
                    text.contains("съезд") ||
                    text.contains("ответв")) && (text.contains("right") || text.contains("прав"))
            }
            return false
        }

        private fun roundaboutExitIndex(value: String?): Int {
            val text = NavigationFeature.clean(value)
            if (text.isNullOrEmpty()) return 0
            val matcher = NavigationFeature.NUMBER!!.matcher(text)
            if (!matcher.find()) return 0
            return Math.round(NavigationFeature.parseFloat(matcher.group()))
        }

        private fun roundaboutExitText(value: String?): String {
            val text = NavigationFeature.clean(value)
            if (text.isNullOrEmpty()) return ""
            val number = NavigationFeature.roundaboutExitNumberFromText(text)
            if (!number.isNullOrEmpty()) return NavigationFeature.roundaboutExitLabel(number)
            val lower = text.lowercase(Locale.US)
            if (lower.contains("перв") || lower.contains("first") || lower.contains("1st"))
                return "1-й съезд"
            if (lower.contains("втор") || lower.contains("second") || lower.contains("2nd"))
                return "2-й съезд"
            if (lower.contains("трет") || lower.contains("third") || lower.contains("3rd"))
                return "3-й съезд"
            if (lower.contains("четв") || lower.contains("fourth") || lower.contains("4th"))
                return "4-й съезд"
            return ""
        }

        private fun roundaboutExitNumberFromText(value: String?): String? {
            val text = NavigationFeature.clean(value).lowercase(Locale.US)
            if (text.isNullOrEmpty()) return ""
            val plain =
                Pattern.compile(
                        "^\\s*([1-9])\\s*[-\\u2010-\\u2015]?\\s*(?:й|st|nd|rd|th)?\\s*$",
                        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
                    )
                    .matcher(text)
            if (plain.find()) return plain.group(1)
            val before =
                Pattern.compile(
                        "(?iu)(^|[^0-9])([1-9])\\s*[-\\u2010-\\u2015]?\\s*" +
                            "(?:й|st|nd|rd|th)?\\s*(?:съезд|exit)"
                    )
                    .matcher(text)
            if (before.find()) return before.group(2)
            val after = Pattern.compile("(?iu)(?:съезд|exit)[^0-9]{0,24}([1-9])").matcher(text)
            if (after.find()) return after.group(1)
            return ""
        }

        private fun roundaboutExitLabel(number: String?): String {
            val clean = NavigationFeature.clean(number)
            if ("1".equals(clean)) return "1-й съезд"
            if ("2".equals(clean)) return "2-й съезд"
            if ("3".equals(clean)) return "3-й съезд"
            if ("4".equals(clean)) return "4-й съезд"
            return clean + "-й съезд"
        }

        private fun roundaboutExitLabelFromRouteSection(value: String?): String {
            val exitId: String = NavigationFeature.roundaboutExitFromRouteSection(value)
            val exit: Int = NavigationFeature.roundaboutExitIndex(exitId)
            return if (exit <= 0) ""
            else NavigationFeature.roundaboutExitLabel(java.lang.String.valueOf(exit))
        }

        private fun roundaboutExitFromExtras(intent: Intent?): String {
            val extras: Bundle? = if (intent == null) null else intent.getExtras()
            if (extras == null) return ""
            for (key: String? in extras.keySet()) {
                val lowerKey = NavigationFeature.clean(key).lowercase(Locale.US)
                val likelyKey: Boolean =
                    lowerKey.contains("exit") ||
                        lowerKey.contains("roundabout") ||
                        lowerKey.contains("circular") ||
                        lowerKey.contains("direction_sign") ||
                        lowerKey.contains("roadsign") ||
                        lowerKey.contains("road_sign") ||
                        lowerKey.contains("route_section")
                val value: Any? = extras.get(key)
                val text: String? =
                    if (value == null) ""
                    else NavigationFeature.clean(java.lang.String.valueOf(value))
                val lowerText = text!!.lowercase(Locale.US)
                val likelyText: Boolean = lowerText.contains("съезд") || lowerText.contains("exit")
                if (!likelyKey && !likelyText) continue
                var parsed: String = ""
                if (lowerKey.contains("route_section") || lowerText.contains("description=")) {
                    parsed = NavigationFeature.roundaboutExitLabelFromRouteSection(text)
                    if (!parsed.isNullOrEmpty()) return parsed
                }
                parsed = NavigationFeature.roundaboutExitText(text)
                if (!parsed.isNullOrEmpty()) return parsed
            }
            return ""
        }

        private fun hasRoundaboutContext(intent: Intent?): Boolean {
            val extras: Bundle? = if (intent == null) null else intent.getExtras()
            if (extras == null) return false
            for (key: String? in extras.keySet()) {
                val lowerKey = NavigationFeature.clean(key).lowercase(Locale.US)
                val value: Any? = extras.get(key)
                val lowerText: String? =
                    if (value == null) ""
                    else
                        NavigationFeature.clean(java.lang.String.valueOf(value))
                            .lowercase(Locale.US)
                if (
                    lowerKey.contains("roundabout") ||
                        lowerKey.contains("circular") ||
                        lowerText!!.contains("roundabout") ||
                        lowerText.contains("circular") ||
                        lowerText.contains("круг") ||
                        lowerText.contains("кольц") ||
                        (!NavigationFeature.roundaboutExitNumberFromText(lowerText)
                            .isNullOrEmpty() &&
                            (lowerKey.contains("route") ||
                                lowerKey.contains("sign") ||
                                lowerKey.contains("exit") ||
                                lowerKey.contains("description") ||
                                lowerKey.contains("maneuver")))
                ) {
                    return true
                }
            }
            return false
        }

        private fun laneDistance(laneDistance: String?, routeDistance: String?): String? {
            val route = NavigationFeature.nonZeroDistance(routeDistance)
            val lane = NavigationFeature.nonZeroDistance(laneDistance)
            return NavigationFeature.first(lane, route)
        }

        private fun withRouteTotalLine(clusterTx: String?, routeTotalDistance: String?): String {
            val line: String = "route total=" + NavigationFeature.clean(routeTotalDistance)
            if ("route total=".equals(line)) return NavigationFeature.clean(clusterTx)
            val current = NavigationFeature.clean(clusterTx)
            val kept: StringBuilder = StringBuilder()
            if (!current.isNullOrEmpty()) {
                val lines = Pattern.compile("\\n").split(current)
                for (part: String? in lines) {
                    val cleanPart = NavigationFeature.clean(part)
                    if (cleanPart.isNullOrEmpty() || cleanPart.startsWith("route total=")) {
                        continue
                    }
                    if (kept.length > 0) kept.append('\n')
                    kept.append(cleanPart)
                }
            }
            return if (kept.length == 0) line else kept.toString() + "\n" + line
        }

        private fun latestRouteTotalDistance(clusterTx: String?): String {
            val current = NavigationFeature.clean(clusterTx)
            if (current.isNullOrEmpty()) return ""
            var latest: String? = ""
            val lines = Pattern.compile("\\n").split(current)
            for (part: String? in lines) {
                val cleanPart = NavigationFeature.clean(part)
                if (!cleanPart.startsWith("route total=")) continue
                latest =
                    NavigationFeature.normalizeDistanceText(
                        NavigationFeature.cleanRouteTotalDistance(
                            cleanPart.substring("route total=".length)
                        )
                    )
            }
            return latest!!
        }

        private fun cleanRouteTotalDistance(value: String?): String {
            val text = NavigationFeature.clean(value)
            if (text.isNullOrEmpty()) return ""
            val matcher =
                Pattern.compile(
                        "[-+]?\\d+(?:[\\.,]\\d+)?\\s*(?:км|km|м|m)",
                        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
                    )
                    .matcher(text)
            return if (matcher.find()) NavigationFeature.clean(matcher.group()) else ""
        }

        private fun laneHintText(intent: Intent?, maneuver: String?, distance: String?): String? {
            return NavigationFeature.laneHintText(intent, maneuver, distance, "")
        }

        private fun laneHintText(
            intent: Intent?,
            maneuver: String?,
            distance: String?,
            sourceLower: String?,
        ): String? {
            val directions = NavigationFeature.grayRoadDirectionsText(intent, sourceLower)
            val label = NavigationFeature.maneuverLabel(maneuver)
            val shown = NavigationFeature.first(directions, label)
            var out: String? = if (shown.isNullOrEmpty()) "серая дорога" else shown
            val dist = NavigationFeature.nonZeroDistance(distance)
            if (!dist.isNullOrEmpty()) out += ", " + dist
            return out
        }

        private fun grayRoadDirectionsText(intent: Intent?): String? {
            return NavigationFeature.grayRoadDirectionsText(intent, "")
        }

        private fun grayRoadDirectionsText(intent: Intent?, sourceLower: String?): String? {
            if (NavigationFeature.providerVisualLaneSource(sourceLower)) {
                val visual = NavigationFeature.visualLaneDirectionsText(intent)
                if (!visual.isNullOrEmpty()) return visual
            }
            val straight: Boolean = NavigationFeature.grayRoadHasStraight(intent, sourceLower)
            val uturnLeft: Boolean = NavigationFeature.grayRoadHasUturnLeft(intent, sourceLower)
            val uturnRight: Boolean = NavigationFeature.grayRoadHasUturnRight(intent, sourceLower)
            val routeAction = NavigationFeature.routeActionManeuver(intent)
            val hardRight: Boolean =
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "hard_right",
                    "sharp_right",
                ) || "context_ra_hard_turn_right".equals(routeAction)
            val hardLeft: Boolean =
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "hard_left",
                    "sharp_left",
                ) || "context_ra_hard_turn_left".equals(routeAction)
            val exitRight: Boolean =
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "exit_right",
                    "ramp_right",
                    "take_right",
                    "slip_right",
                    "fork_right",
                ) || NavigationFeature.routeActionRightBranch(intent)
            val exitLeft: Boolean =
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "exit_left",
                    "ramp_left",
                    "take_left",
                    "slip_left",
                    "fork_left",
                ) || NavigationFeature.routeActionLeftBranch(intent)
            var right: Boolean =
                NavigationFeature.grayRoadHasRight(intent, sourceLower) || exitRight || hardRight
            var left: Boolean =
                NavigationFeature.grayRoadHasLeft(intent, sourceLower) || exitLeft || hardLeft
            if (
                !left &&
                    NavigationFeature.grayRoadAssumeLeftAtJunction(
                        intent,
                        straight,
                        right,
                        exitRight,
                        exitLeft,
                        sourceLower,
                    )
            ) {
                left = true
            }
            if (
                exitRight &&
                    NavigationFeature.laneHighlightHas(intent, "straight") &&
                    !NavigationFeature.laneHighlightHas(intent, "left")
            ) {
                left = false
            }
            if (
                exitLeft &&
                    NavigationFeature.laneHighlightHas(intent, "straight") &&
                    !NavigationFeature.laneHighlightHas(intent, "right")
            ) {
                right = false
            }
            val out: StringBuilder = StringBuilder()
            NavigationFeature.appendPart(out, "разворот налево", uturnLeft)
            NavigationFeature.appendPart(out, "разворот направо", uturnRight)
            NavigationFeature.appendPart(out, "прямо", straight)
            NavigationFeature.appendPart(
                out,
                if (hardLeft) "резко налево" else (if (exitLeft) "съезд налево" else "налево"),
                left,
            )
            NavigationFeature.appendPart(
                out,
                if (hardRight) "резко направо" else (if (exitRight) "съезд направо" else "направо"),
                right,
            )
            return out.toString()
        }

        private fun laneDebugText(grayRoad: String?, laneHint: String?): String? {
            val road: String = NavigationFeature.grayRoadLabel(grayRoad)
            if (road.isNullOrEmpty()) return laneHint
            return road
        }

        private fun grayRoadLabel(grayRoad: String?): String {
            val value = NavigationFeature.clean(grayRoad)
            if ("context_ra_gray_straight_left_right".equals(value))
                return "прямо + налево + направо"
            if ("context_ra_gray_left_right".equals(value)) return "налево + направо"
            if ("context_ra_gray_straight_left_exit_right".equals(value))
                return "прямо + налево + съезд направо"
            if ("context_ra_gray_straight_exit_left_right".equals(value))
                return "прямо + съезд налево + направо"
            if ("context_ra_gray_left_exit_right".equals(value)) return "налево + съезд направо"
            if ("context_ra_gray_exit_left_right".equals(value)) return "съезд налево + направо"
            if ("context_ra_gray_exit_right".equals(value)) return "прямо + съезд направо"
            if ("context_ra_gray_only_exit_right".equals(value)) return "съезд направо"
            if ("context_ra_gray_straight_exit_left".equals(value)) return "прямо + съезд налево"
            if ("context_ra_gray_exit_left".equals(value)) return "съезд налево"
            if ("context_ra_gray_hard_right".equals(value)) return "резко направо"
            if ("context_ra_gray_hard_left".equals(value)) return "резко налево"
            if ("context_ra_gray_straight_right".equals(value)) return "прямо + направо"
            if ("context_ra_gray_straight_left".equals(value)) return "прямо + налево"
            if ("context_ra_gray_right".equals(value)) return "направо"
            if ("context_ra_gray_left".equals(value)) return "налево"
            if ("context_ra_gray_straight".equals(value)) return "прямо"
            return ""
        }

        private fun grayRoadSchemeText(
            grayRoad: String?,
            intent: Intent?,
            sourceLower: String?,
        ): String {
            val base: String = NavigationFeature.grayRoadLabel(grayRoad)
            if (intent == null) return base
            if (
                NavigationFeature.providerVisualLaneSource(sourceLower) &&
                    NavigationFeature.hasVisualLaneItems(intent)
            )
                return base
            val uturnLeft: Boolean = NavigationFeature.grayRoadHasUturnLeft(intent, sourceLower)
            val uturnRight: Boolean = NavigationFeature.grayRoadHasUturnRight(intent, sourceLower)
            if (!uturnLeft && !uturnRight) return base
            val out: StringBuilder = StringBuilder()
            NavigationFeature.appendPart(out, "разворот налево", uturnLeft)
            NavigationFeature.appendPart(out, "разворот направо", uturnRight)
            if (!base.isNullOrEmpty()) {
                if (out.length > 0) out.append(" + ")
                out.append(base)
            }
            return out.toString()
        }

        private fun grayRoadManeuver(intent: Intent?): String? {
            return NavigationFeature.grayRoadManeuver(intent, "")
        }

        private fun grayRoadManeuver(intent: Intent?, sourceLower: String?): String? {
            if (NavigationFeature.providerVisualLaneSource(sourceLower)) {
                return NavigationFeature.explicitProviderGrayRoadManeuver(intent)
            }
            val straight: Boolean = NavigationFeature.grayRoadHasStraight(intent, sourceLower)
            val routeAction = NavigationFeature.routeActionManeuver(intent)
            val hardRight: Boolean =
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "hard_right",
                    "sharp_right",
                ) || "context_ra_hard_turn_right".equals(routeAction)
            val hardLeft: Boolean =
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "hard_left",
                    "sharp_left",
                ) || "context_ra_hard_turn_left".equals(routeAction)
            val exitRight: Boolean =
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "exit_right",
                    "ramp_right",
                    "take_right",
                    "slip_right",
                    "fork_right",
                ) || NavigationFeature.routeActionRightBranch(intent)
            val exitLeft: Boolean =
                NavigationFeature.laneHasAnyForGrayRoad(
                    intent,
                    sourceLower,
                    "exit_left",
                    "ramp_left",
                    "take_left",
                    "slip_left",
                    "fork_left",
                ) || NavigationFeature.routeActionLeftBranch(intent)
            var right: Boolean =
                NavigationFeature.grayRoadHasRight(intent, sourceLower) || exitRight || hardRight
            var left: Boolean =
                NavigationFeature.grayRoadHasLeft(intent, sourceLower) || exitLeft || hardLeft
            if (
                !left &&
                    NavigationFeature.grayRoadAssumeLeftAtJunction(
                        intent,
                        straight,
                        right,
                        exitRight,
                        exitLeft,
                        sourceLower,
                    )
            ) {
                left = true
            }
            if (
                exitRight &&
                    NavigationFeature.laneHighlightHas(intent, "straight") &&
                    !NavigationFeature.laneHighlightHas(intent, "left")
            ) {
                left = false
            }
            if (
                exitLeft &&
                    NavigationFeature.laneHighlightHas(intent, "straight") &&
                    !NavigationFeature.laneHighlightHas(intent, "right")
            ) {
                right = false
            }
            // The cluster has one gray road geometry, not lane-level arrows. U-turns and
            // roundabouts
            // are separate yellow maneuver families and must not inflate the gray road.
            if (hardRight && !straight && !left) return "context_ra_gray_hard_right"
            if (hardLeft && !straight && !right) return "context_ra_gray_hard_left"
            if (exitRight && straight && left) return "context_ra_gray_straight_left_exit_right"
            if (exitLeft && straight && right) return "context_ra_gray_straight_exit_left_right"
            if (exitRight && left) return "context_ra_gray_left_exit_right"
            if (exitLeft && right) return "context_ra_gray_exit_left_right"
            if (exitRight && straight) return "context_ra_gray_exit_right"
            if (exitLeft && straight) return "context_ra_gray_straight_exit_left"
            if (exitRight) return "context_ra_gray_only_exit_right"
            if (exitLeft) return "context_ra_gray_exit_left"
            if (straight && left && right) return "context_ra_gray_straight_left_right"
            if (left && right) return "context_ra_gray_left_right"
            if (straight && right) return "context_ra_gray_straight_right"
            if (straight && left) return "context_ra_gray_straight_left"
            if (right) return "context_ra_gray_right"
            if (left) return "context_ra_gray_left"
            if (straight) return "context_ra_gray_straight"
            return ""
        }

        private fun explicitProviderGrayRoadManeuver(intent: Intent?): String {
            val topology = NavigationFeature.providerTrustedGrayRoadTopologyText(intent)
            if (topology.isNullOrEmpty()) return ""
            val straight: Boolean = NavigationFeature.containsLaneToken(topology, "straight")
            val hardRight: Boolean =
                NavigationFeature.containsLaneToken(topology, "hard_right") ||
                    NavigationFeature.containsLaneToken(topology, "sharp_right")
            val hardLeft: Boolean =
                NavigationFeature.containsLaneToken(topology, "hard_left") ||
                    NavigationFeature.containsLaneToken(topology, "sharp_left")
            val exitRight: Boolean =
                NavigationFeature.containsLaneToken(topology, "exit_right") ||
                    NavigationFeature.containsLaneToken(topology, "ramp_right") ||
                    NavigationFeature.containsLaneToken(topology, "take_right") ||
                    NavigationFeature.containsLaneToken(topology, "slip_right") ||
                    NavigationFeature.containsLaneToken(topology, "fork_right")
            val exitLeft: Boolean =
                NavigationFeature.containsLaneToken(topology, "exit_left") ||
                    NavigationFeature.containsLaneToken(topology, "ramp_left") ||
                    NavigationFeature.containsLaneToken(topology, "take_left") ||
                    NavigationFeature.containsLaneToken(topology, "slip_left") ||
                    NavigationFeature.containsLaneToken(topology, "fork_left")
            val right: Boolean =
                NavigationFeature.containsLaneToken(topology, "right") || exitRight || hardRight
            val left: Boolean =
                NavigationFeature.containsLaneToken(topology, "left") || exitLeft || hardLeft
            if (hardRight && !straight && !left) return "context_ra_gray_hard_right"
            if (hardLeft && !straight && !right) return "context_ra_gray_hard_left"
            if (exitRight && straight && left) return "context_ra_gray_straight_left_exit_right"
            if (exitLeft && straight && right) return "context_ra_gray_straight_exit_left_right"
            if (exitRight && left) return "context_ra_gray_left_exit_right"
            if (exitLeft && right) return "context_ra_gray_exit_left_right"
            if (exitRight && straight) return "context_ra_gray_exit_right"
            if (exitLeft && straight) return "context_ra_gray_straight_exit_left"
            if (exitRight) return "context_ra_gray_only_exit_right"
            if (exitLeft) return "context_ra_gray_exit_left"
            return NavigationFeature.grayRoadFromBasicDirections(straight, left, right)
        }

        private fun grayRoadForManeuver(maneuver: String?): String {
            val family: String = NavigationFeature.maneuverFamily(maneuver)
            if ("hard_right".equals(family)) return "context_ra_gray_hard_right"
            if ("hard_left".equals(family)) return "context_ra_gray_hard_left"
            if ("exit_right".equals(family)) return "context_ra_gray_only_exit_right"
            if ("exit_left".equals(family)) return "context_ra_gray_exit_left"
            if ("right".equals(family)) return "context_ra_gray_right"
            if ("left".equals(family)) return "context_ra_gray_left"
            if ("forward".equals(family)) return "context_ra_gray_straight"
            return ""
        }

        private fun adjustRouteRoadGrayForCurrentManeuver(
            intent: Intent?,
            grayRoad: String?,
            currentManeuver: String?,
        ): String? {
            val currentFamily: String = NavigationFeature.maneuverFamily(currentManeuver)
            val routeFamily: String =
                NavigationFeature.maneuverFamily(NavigationFeature.routeActionManeuver(intent))
            if (
                grayRoad.isNullOrEmpty() ||
                    currentFamily.isNullOrEmpty() ||
                    routeFamily.isNullOrEmpty() ||
                    currentFamily.equals(routeFamily)
            ) {
                return grayRoad
            }
            val straight: Boolean = NavigationFeature.grayRoadHasStraight(intent)
            val right: Boolean =
                NavigationFeature.grayRoadHasRight(intent) ||
                    NavigationFeature.routeActionRightBranch(intent)
            val left: Boolean =
                NavigationFeature.grayRoadHasLeft(intent) ||
                    NavigationFeature.routeActionLeftBranch(intent)
            val exitRight: Boolean = NavigationFeature.routeActionRightBranch(intent)
            val exitLeft: Boolean = NavigationFeature.routeActionLeftBranch(intent)
            if (
                "right".equals(currentFamily) &&
                    "left".equals(routeFamily) &&
                    left &&
                    !right &&
                    !straight
            ) {
                return "context_ra_gray_right"
            }
            if (
                "left".equals(currentFamily) &&
                    "right".equals(routeFamily) &&
                    right &&
                    !left &&
                    !straight
            ) {
                return "context_ra_gray_left"
            }
            if (
                "left".equals(currentFamily) &&
                    "right".equals(routeFamily) &&
                    straight &&
                    right &&
                    !left
            ) {
                return if (exitRight) "context_ra_gray_left_exit_right"
                else "context_ra_gray_left_right"
            }
            if (
                "right".equals(currentFamily) &&
                    "left".equals(routeFamily) &&
                    straight &&
                    left &&
                    !right
            ) {
                return if (exitLeft) "context_ra_gray_exit_left_right"
                else "context_ra_gray_left_right"
            }
            return grayRoad
        }

        private fun grayRoadHintText(
            grayRoad: String?,
            intent: Intent?,
            sourceLower: String?,
            distance: String?,
        ): String {
            val label = NavigationFeature.grayRoadSchemeText(grayRoad, intent, sourceLower)
            if (label.isNullOrEmpty()) return ""
            val dist = NavigationFeature.nonZeroDistance(distance)
            return if (dist.isNullOrEmpty()) label else label + ", " + dist
        }

        private fun isTrustedCurrentStreetSpeedSource(source: String?): Boolean {
            val value = NavigationFeature.clean(source).lowercase(Locale.US)
            return value.contains("status_panel") ||
                value.contains("eta_view") ||
                value.contains("bottom_panel") ||
                value.contains("route_status")
        }

        private fun roadOptionKey(currentStreet: String?, nextStreet: String?): String {
            val current = NavigationFeature.simplifyStreet(currentStreet)
            val next = NavigationFeature.simplifyStreet(nextStreet)
            if (current.isNullOrEmpty() && next.isNullOrEmpty()) return ""
            return current + "|" + next
        }

        private fun maneuverRoadMask(maneuver: String?): Int {
            val value = NavigationFeature.clean(maneuver).lowercase(Locale.US)
            if (value.isNullOrEmpty()) return 0
            var mask: Int = 0
            if (value.contains("forward") || value.contains("straight")) {
                mask = mask or 1
            }
            if (
                value.contains("turn_left") ||
                    value.contains("take_left") ||
                    value.contains("exit_left") ||
                    value.contains("left")
            ) {
                mask = mask or 2
            }
            if (
                value.contains("turn_right") ||
                    value.contains("take_right") ||
                    value.contains("exit_right") ||
                    value.contains("right")
            ) {
                mask = mask or 4
            }
            return mask
        }

        private fun grayRoadMask(grayRoad: String?): Int {
            val value = NavigationFeature.clean(grayRoad)
            if (value.isNullOrEmpty()) return 0
            if ("context_ra_gray_straight_left_right".equals(value)) return 7
            if ("context_ra_gray_left_right".equals(value)) return 6
            if (
                "context_ra_gray_straight_right".equals(value) ||
                    "context_ra_gray_exit_right".equals(value)
            )
                return 5
            if ("context_ra_gray_straight_exit_left".equals(value)) return 3
            if (
                "context_ra_gray_straight_left_exit_right".equals(value) ||
                    "context_ra_gray_straight_exit_left_right".equals(value)
            )
                return 7
            if (
                "context_ra_gray_left_exit_right".equals(value) ||
                    "context_ra_gray_exit_left_right".equals(value)
            )
                return 6
            if ("context_ra_gray_straight_left".equals(value)) return 3
            if (
                "context_ra_gray_right".equals(value) ||
                    "context_ra_gray_only_exit_right".equals(value) ||
                    "context_ra_gray_hard_right".equals(value)
            )
                return 4
            if (
                "context_ra_gray_left".equals(value) ||
                    "context_ra_gray_exit_left".equals(value) ||
                    "context_ra_gray_hard_left".equals(value)
            )
                return 2
            if ("context_ra_gray_straight".equals(value)) return 1
            return 0
        }

        private fun grayRoadIncompatibleWithManeuver(
            maneuver: String?,
            grayRoad: String?,
        ): Boolean {
            val maneuverMask: Int = NavigationFeature.maneuverRoadMask(maneuver)
            val grayMask: Int = NavigationFeature.grayRoadMask(grayRoad)
            return maneuverMask != 0 && grayMask != 0 && (grayMask and maneuverMask) == 0
        }

        private fun grayRoadFromMask(mask: Int): String {
            if ((mask and 7) == 7) return "context_ra_gray_straight_left_right"
            if ((mask and 6) == 6) return "context_ra_gray_left_right"
            if ((mask and 5) == 5) return "context_ra_gray_straight_right"
            if ((mask and 3) == 3) return "context_ra_gray_straight_left"
            if ((mask and 4) == 4) return "context_ra_gray_right"
            if ((mask and 2) == 2) return "context_ra_gray_left"
            if ((mask and 1) == 1) return "context_ra_gray_straight"
            return ""
        }

        private fun grayRoadKey(maneuver: String?, street: String?): String {
            return NavigationFeature.clean(maneuver) +
                "|" +
                NavigationFeature.simplifyStreet(street)
        }

        private fun appendPart(out: StringBuilder, text: String?, enabled: Boolean) {
            if (!enabled) return
            if (out.length > 0) out.append(" + ")
            out.append(text)
        }

        private fun nonZeroDistance(value: String?): String {
            val clean = NavigationFeature.clean(value)
            if (clean.isNullOrEmpty()) return ""
            return if (NavigationFeature.distanceMeters(clean) > 1f) clean else ""
        }

        private fun laneDistancePassed(value: String?): Boolean {
            val clean = NavigationFeature.clean(value)
            if (clean.isNullOrEmpty()) return false
            val matcher = NavigationFeature.NUMBER!!.matcher(clean.replace(',', '.'))
            return matcher.find() && NavigationFeature.distanceMeters(clean) <= 1f
        }

        private fun isFinishManeuver(maneuver: String?): Boolean {
            if (maneuver == null) return false
            val p = maneuver.lowercase(Locale.US)
            return p.contains("finish") ||
                p.contains("arriv") ||
                p.contains("destination") ||
                p.contains("финиш")
        }

        private fun isRoundaboutManeuver(maneuver: String?): Boolean {
            if (maneuver == null) return false
            val p = maneuver.lowercase(Locale.US)
            return p.contains("round") || p.contains("circular") || p.contains("круг")
        }

        private fun isStandaloneManeuverFrame(maneuver: String?): Boolean {
            val value = NavigationFeature.clean(maneuver)
            if (value.isNullOrEmpty()) return false
            return NavigationFeature.isFinishManeuver(value) ||
                NavigationFeature.isRoundaboutManeuver(value) ||
                "context_ra_exit_right".equals(value) ||
                "context_ra_exit_left".equals(value) ||
                value.contains("take_") ||
                value.contains("hard_turn") ||
                value.contains("turn_back")
        }

        private fun isPriorityEventManeuver(maneuver: String?): Boolean {
            val value = NavigationFeature.clean(maneuver)
            if (value.isNullOrEmpty()) return false
            return NavigationFeature.isFinishManeuver(value) ||
                NavigationFeature.isRoundaboutManeuver(value) ||
                value.contains("turn_back") ||
                value.contains("uturn") ||
                value.contains("hard_turn") ||
                value.contains("exit_") ||
                value.contains("take_")
        }

        private fun canMergeGrayRoad(maneuver: String?): Boolean {
            val value = NavigationFeature.clean(maneuver)
            return !value.isNullOrEmpty() &&
                !value.contains("_gray_") &&
                !NavigationFeature.isPriorityEventManeuver(value)
        }

        private fun firstMergeableManeuver(maneuvers: Array<out String?>?): String {
            if (maneuvers == null) return ""
            for (maneuver: String? in maneuvers) {
                val value = NavigationFeature.clean(maneuver)
                if (NavigationFeature.canMergeGrayRoad(value)) return value
            }
            return ""
        }

        private fun directionStep(relativeDegrees: Float): Int {
            val centered: Float = NavigationFeature.normalizeSignedDegrees(relativeDegrees)
            if (Math.abs(centered) < 7.5f) return 0
            val step: Int = Math.round(NavigationFeature.normalizeDegrees(centered) / 7.5f)
            return if (step >= 48) 0 else step
        }

        private fun compassDirectionStep(relativeDegrees: Float): Int {
            val centered: Float = NavigationFeature.normalizeSignedDegrees(relativeDegrees)
            if (Math.abs(centered) < 11.25f) return 0
            val step: Int = Math.round(NavigationFeature.normalizeDegrees(centered) / 22.5f) * 3
            return if (step >= 48) 0 else step
        }

        private fun nextCompassStep(current: Int, target: Int): Int {
            val c: Int = NavigationFeature.normalizeCompassStep(current)
            val t: Int = NavigationFeature.normalizeCompassStep(target)
            val forward: Int = (t - c + 48) % 48
            val backward: Int = (c - t + 48) % 48
            if (forward == 0) return t
            if (forward == backward)
                return NavigationFeature.normalizeCompassStep(c + (if (t >= c) 3 else -3))
            return NavigationFeature.normalizeCompassStep(c + (if (forward < backward) 3 else -3))
        }

        private fun compassStepDelta(current: Int, target: Int): Int {
            val c: Int = NavigationFeature.normalizeCompassStep(current)
            val t: Int = NavigationFeature.normalizeCompassStep(target)
            val forward: Int = (t - c + 48) % 48
            val backward: Int = (c - t + 48) % 48
            return Math.min(forward, backward)
        }

        private fun normalizeCompassStep(step: Int): Int {
            var out: Int = ((step % 48) + 48) % 48
            out = Math.round(out / 3f) * 3
            return if (out == 48) 0 else out
        }

        private fun normalizeDegrees(value: Float): Float {
            val out: Float = value % 360f
            return if (out < 0f) out + 360f else out
        }

        private fun normalizeSignedDegrees(value: Float): Float {
            val out: Float = NavigationFeature.normalizeDegrees(value)
            return if (out > 180f) out - 360f else out
        }

        private fun blendDegrees(from: Float, to: Float, toWeight: Float): Float {
            val weight: Float = Math.max(0f, Math.min(1f, toWeight))
            return NavigationFeature.normalizeDegrees(
                from + NavigationFeature.normalizeSignedDegrees(to - from) * weight
            )
        }

        private fun routeFinished(routeDistance: String?): Boolean {
            if (routeDistance.isNullOrEmpty()) return false
            val value = routeDistance.lowercase(Locale.US).replace(',', '.').trim { it <= ' ' }
            if (value.contains("км") || value.contains("km")) return false
            if (!(value.contains("м") || value.contains("m"))) return false
            val parsed: Float = NavigationFeature.distanceValue(value)
            return parsed >= 0f && parsed <= NavigationFeature.AUTO_FINISH_ROUTE_METERS
        }

        private fun isTimeText(value: String?): Boolean {
            if (value.isNullOrEmpty()) return false
            val v = value.lowercase(Locale.US)
            if (!NavigationFeature.NUMBER!!.matcher(v).find()) return false
            return Pattern.compile(
                    "(?iu)(^|[^\\p{L}])(мин\\.?|минут[а-я]*|min|mins|minute|minutes|" +
                        "час[а-я]*|hour|hours|hr|hrs)([^\\p{L}]|$)"
                )
                .matcher(v)
                .find()
        }

        private fun arrivalFromRouteTime(value: String?): String? {
            if (value.isNullOrEmpty()) return ""
            val text = value.lowercase(Locale.US).replace(',', '.')
            val matcher = NavigationFeature.NUMBER!!.matcher(text)
            var hours: Float = 0f
            var minutes: Float = 0f
            if (text.contains("ч") || text.contains("hour") || text.contains("hr")) {
                if (matcher.find()) hours = NavigationFeature.parseFloat(matcher.group())
                if (matcher.find()) minutes = NavigationFeature.parseFloat(matcher.group())
            } else if (matcher.find()) {
                minutes = NavigationFeature.parseFloat(matcher.group())
            }
            val addMinutes: Long = Math.round(hours * 60f + minutes).toLong()
            if (addMinutes <= 0L) return ""
            return SimpleDateFormat("HH:mm", Locale.US)
                .format(Date(System.currentTimeMillis() + addMinutes * 60000L))
        }

        private fun arrivalHourMinute(value: String?): IntArray? {
            if (value.isNullOrEmpty()) return null
            val matcher = Pattern.compile("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").matcher(value.trim { it <= ' ' })
            if (!matcher.find()) return null
            try {
                val hour: Int = Integer.parseInt(matcher.group(1))
                val minute: Int = Integer.parseInt(matcher.group(2))
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null
                return intArrayOf(hour, minute)
            } catch (ignored: NumberFormatException) {
                return null
            }
        }

        private fun durationHourMinute(value: String?): IntArray? {
            if (value.isNullOrEmpty()) return null
            val text = value.lowercase(Locale.US).replace(',', '.').trim { it <= ' ' }
            val clock = Pattern.compile("(\\d{1,3})\\s*[:.]\\s*(\\d{1,2})").matcher(text)
            if (clock.find()) {
                return NavigationFeature.normalizedHourMinute(
                    NavigationFeature.parseInt(clock.group(1), 0),
                    NavigationFeature.parseInt(clock.group(2), 0),
                )
            }
            val hours: Int =
                NavigationFeature.numberBeforeUnit(text, "(?:ч|час|часа|часов|h|hr|hrs|hour|hours)")
            var minutes: Int =
                NavigationFeature.numberBeforeUnit(
                    text,
                    "(?:мин|минута|минуты|минут|min|mins|minute|minutes|m)",
                )
            if (hours <= 0 && minutes <= 0) {
                val number = NavigationFeature.NUMBER!!.matcher(text)
                if (number.find())
                    minutes = Math.round(NavigationFeature.parseFloat(number.group()))
            }
            if (hours <= 0 && minutes <= 0) return null
            return NavigationFeature.normalizedHourMinute(hours, minutes)
        }

        private fun normalizedHourMinute(hours: Int, minutes: Int): IntArray {
            var hours: Int = hours
            var minutes: Int = minutes
            if (minutes >= 60) {
                hours += minutes / 60
                minutes = minutes % 60
            }
            if (hours < 0) hours = 0
            if (minutes < 0) minutes = 0
            return intArrayOf(Math.min(hours, 23), Math.min(minutes, 59))
        }

        private fun numberBeforeUnit(text: String?, unitPattern: String?): Int {
            val matcher = Pattern.compile("(\\d+)\\s*" + unitPattern).matcher(text)
            if (!matcher.find()) return 0
            return NavigationFeature.parseInt(matcher.group(1), 0)
        }

        private fun parseInt(value: String?, fallback: Int): Int {
            try {
                return Integer.parseInt(value)
            } catch (ignored: NumberFormatException) {
                return fallback
            }
        }

        private fun isCameraDirection(direction: String?): Boolean {
            if (direction.isNullOrEmpty()) return false
            val d = direction.lowercase(Locale.US)
            return d.contains("camera") || d.contains("камера")
        }

        private fun passiveRoadEventText(intent: Intent?, direction: String): String? {
            val text =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "position"),
                    NavigationFeature.text(intent, "event"),
                    NavigationFeature.text(intent, "title"),
                    NavigationFeature.text(intent, "description"),
                    NavigationFeature.text(intent, "street"),
                    NavigationFeature.text(intent, "road"),
                )
            var combined: String? =
                NavigationFeature.first(direction, "").toString() +
                    " " +
                    NavigationFeature.first(text, "")
            combined = combined!!.lowercase(Locale.US)
            val passive: Boolean =
                NavigationFeature.isCameraDirection(direction) ||
                    combined.contains("camera") ||
                    combined.contains("камера") ||
                    combined.contains("obstacle") ||
                    combined.contains("препят") ||
                    combined.contains("road_event") ||
                    combined.contains("roadevent")
            return if (passive)
                NavigationFeature.first(text, NavigationFeature.maneuverLabel(direction))
            else ""
        }

        private fun yandexSignEventText(intent: Intent?, sourceLower: String?): String {
            var source: String? = NavigationFeature.clean(sourceLower)
            source = if (source.isNullOrEmpty()) "" else source.lowercase(Locale.US)
            val raw =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "first_event_text"),
                    NavigationFeature.text(intent, "event"),
                    NavigationFeature.text(intent, "main_event"),
                    NavigationFeature.text(intent, "route_event"),
                    NavigationFeature.text(intent, "first_event_type"),
                    NavigationFeature.text(intent, "camera_speed_limit"),
                    NavigationFeature.text(intent, "direction_sign_items"),
                    NavigationFeature.text(intent, "raw_direction_sign_items"),
                    NavigationFeature.text(intent, "direction_sign"),
                    NavigationFeature.text(intent, "road_sign"),
                    NavigationFeature.text(intent, "warning"),
                )
            if (raw.isNullOrEmpty()) return ""
            val lower = raw.lowercase(Locale.US)
            val eventSource: Boolean =
                source.contains("direction_sign") ||
                    source.contains("road_sign") ||
                    source.contains("roadsign") ||
                    source.contains("auto_widget_signs") ||
                    source.contains("event") ||
                    source.contains("warning") ||
                    source.contains("camera") ||
                    source.contains("next_camera")
            val eventText: Boolean =
                lower.contains("camera") ||
                    lower.contains("камера") ||
                    lower.contains("speed") ||
                    lower.contains("скорост") ||
                    lower.contains("lane_control") ||
                    lower.contains("road_marking_control") ||
                    lower.contains("cross_road_control") ||
                    lower.contains("mobile_control") ||
                    lower.contains("warning") ||
                    lower.contains("предуп") ||
                    lower.contains("road_sign") ||
                    lower.contains("roadsign") ||
                    lower.contains("sign=") ||
                    lower.contains("roadSign=") ||
                    lower.contains("exit=")
            if (!eventSource && !eventText) return ""
            val exit =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "exit_number"),
                    NavigationFeature.text(intent, "roundabout_exit"),
                    NavigationFeature.text(intent, "exit_name"),
                    NavigationFeature.text(intent, "exit"),
                )
            if (!exit.isNullOrEmpty()) return "знак: " + exit
            return "знак/предупреждение: " + NavigationFeature.trimText(raw, 160)
        }

        private fun trimText(value: String?, max: Int): String {
            val text = NavigationFeature.clean(value)
            if (text.isNullOrEmpty() || text.length <= max) return text
            return text.substring(0, Math.max(0, max - 3)) + "..."
        }

        private fun distanceText(distance: String?, unit: String?): String {
            if (distance.isNullOrEmpty()) return ""
            val cleanDistance = NavigationFeature.clean(distance)
            val cleanUnit = NavigationFeature.clean(unit)
            if (cleanUnit.isNullOrEmpty()) {
                return NavigationFeature.normalizeDistanceText(cleanDistance)
            }
            val lowerDistance = cleanDistance.lowercase(Locale.US)
            val lowerUnit = cleanUnit.lowercase(Locale.US)
            if (
                lowerDistance.endsWith(" " + lowerUnit) ||
                    lowerDistance.endsWith(lowerUnit) ||
                    lowerDistance.contains(" " + lowerUnit + " ")
            ) {
                return NavigationFeature.normalizeDistanceText(cleanDistance)
            }
            return NavigationFeature.normalizeDistanceText(cleanDistance + " " + cleanUnit)
        }

        private fun dashboardDistance(value: String?): String {
            val text = NavigationFeature.clean(value)
            if (text.isNullOrEmpty()) return ""
            val lower = text.lowercase(Locale.US)
            if (
                lower.contains("км") ||
                    lower.contains("km") ||
                    lower.contains("м") ||
                    lower.contains("meter")
            ) {
                return NavigationFeature.normalizeDistanceText(text)
            }
            return if (NavigationFeature.NUMBER!!.matcher(text).find())
                NavigationFeature.normalizeDistanceText(text + " м")
            else text
        }

        private fun metersDistanceText(value: String?): String {
            val cleanValue = NavigationFeature.clean(value)
            if (cleanValue.isNullOrEmpty()) return ""
            val meters: Float = ManeuverArbiter.distanceValue(cleanValue)
            if (meters < 0f) return ""
            if (meters < 1000f) return Math.round(meters).toString() + " м"
            return String.format(Locale.US, "%.1f км", meters / 1000f)
        }

        private fun normalizeDistanceText(value: String?): String {
            val text = NavigationFeature.clean(value)
            if (text.isNullOrEmpty()) return ""
            val meters: Float = ManeuverArbiter.distanceMeters(text)
            if (meters < 0f) return text
            if (meters == 0f) return "0 м"
            if (meters < 1000f)
                return Math.round(NavigationFeature.roundMetersForDisplay(meters)).toString() + " м"
            return String.format(Locale.US, "%.1f км", meters / 1000f)
        }

        private fun clusterDistanceValue(distance: Float, km: Boolean): Float {
            if (distance <= 0f) return 0f
            return if (km) distance else NavigationFeature.roundMetersForDisplay(distance)
        }

        private fun roundMetersForDisplay(meters: Float): Float {
            if (meters <= 0f) return 0f
            return Math.max(
                NavigationFeature.DISTANCE_DISPLAY_STEP_METERS,
                Math.round(meters / NavigationFeature.DISTANCE_DISPLAY_STEP_METERS) *
                    NavigationFeature.DISTANCE_DISPLAY_STEP_METERS,
            )
        }

        private fun finishAllowedByDistance(vararg values: String?): Boolean {
            for (value: String? in values) {
                if (value.isNullOrEmpty()) continue
                if (NavigationFeature.routeFinished(value)) return true
                val meters: Float = NavigationFeature.distanceMeters(value)
                if (meters > 0f) return meters <= NavigationFeature.AUTO_FINISH_ROUTE_METERS
            }
            return false
        }

        private fun distanceValue(value: String?): Float {
            return Math.max(0f, ManeuverArbiter.distanceValue(value))
        }

        private fun distanceMeters(value: String?): Float {
            return Math.max(0f, ManeuverArbiter.distanceMeters(value))
        }

        private fun parseFloat(value: String): Float {
            try {
                return java.lang.Float.parseFloat(value.replace(',', '.'))
            } catch (ignored: Exception) {
                return 0f
            }
        }

        private fun isKm(unit: String?): Boolean {
            if (unit == null) return false
            val u = unit.lowercase(Locale.US)
            return u.contains("км") || u.contains("km")
        }

        private fun explicitManeuverDistanceFromIntent(intent: Intent?): String? {
            return NavigationFeature.first(
                NavigationFeature.metersDistanceText(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "maneuver_distance_meters"),
                        NavigationFeature.text(intent, "current_maneuver_distance_meters"),
                        NavigationFeature.text(intent, "distance_to_maneuver_meters"),
                    )
                ),
                NavigationFeature.text(intent, "maneuver_distance"),
                NavigationFeature.text(intent, "current_maneuver_distance"),
                NavigationFeature.text(intent, "distance_to_maneuver"),
                NavigationFeature.text(intent, "next_maneuver_distance"),
                NavigationFeature.text(intent, "last_maneuver_distance"),
            )
        }

        private fun explicitLaneDistanceFromIntent(intent: Intent?): String? {
            val unit =
                NavigationFeature.first(
                    NavigationFeature.text(intent, "lane_distance_unit"),
                    NavigationFeature.text(intent, "micro_distance_unit"),
                    NavigationFeature.text(intent, "distance_unit"),
                    NavigationFeature.text(intent, "unit"),
                )
            return NavigationFeature.first(
                NavigationFeature.metersDistanceText(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "lane_distance_meters"),
                        NavigationFeature.text(intent, "micro_distance_meters"),
                    )
                ),
                NavigationFeature.distanceText(
                    NavigationFeature.first(
                        NavigationFeature.text(intent, "lane_distance"),
                        NavigationFeature.text(intent, "laneDistance"),
                        NavigationFeature.text(intent, "micro_distance"),
                        NavigationFeature.text(intent, "microDistance"),
                    ),
                    unit,
                ),
            )
        }

        private fun laneGuidanceDistancePacket(intent: Intent?, source: String?): Boolean {
            val sourceLower = NavigationFeature.clean(source).lowercase(Locale.US)
            if (NavigationFeature.bool(intent, "lane_guidance", false)) return true
            if (NavigationFeature.laneDistanceOnlySource(sourceLower)) return true
            if (
                !NavigationFeature.nonZeroDistance(
                        NavigationFeature.explicitLaneDistanceFromIntent(intent)
                    )
                    .isNullOrEmpty()
            )
                return true
            return YandexCoreBridgeContract.SOURCE.equals(NavigationFeature.clean(source)) &&
                NavigationFeature.hasLaneData(intent)
        }

        private fun speedNumber(value: String?): Int {
            val parsed: Float = NavigationFeature.distanceValue(value)
            return if (parsed <= 0) 0 else Math.round(parsed)
        }

        private fun clampInt(value: Int, min: Int, max: Int): Int {
            return Math.max(min, Math.min(max, value))
        }

        private fun text(intent: Intent?, key: String?): String? {
            if (intent == null || key == null) return null
            val extras: Bundle? = intent.getExtras()
            if (extras == null || !extras.containsKey(key)) return null
            val value: Any? = extras.get(key)
            return if (value == null) null
            else NavigationFeature.clean(java.lang.String.valueOf(value))
        }

        private fun textByKeyPart(intent: Intent?, part: String?): String? {
            if (intent == null || part == null) return null
            val extras: Bundle? = intent.getExtras()
            if (extras == null) return null
            val needle = part.lowercase(Locale.US)
            for (key: String? in extras.keySet()) {
                if (key != null && key.lowercase(Locale.US).contains(needle)) {
                    val value: Any? = extras.get(key)
                    val text: String? =
                        if (value == null) ""
                        else NavigationFeature.clean(java.lang.String.valueOf(value))
                    if (!text.isNullOrEmpty()) return text
                }
            }
            return null
        }

        private fun bool(intent: Intent?, key: String?, fallback: Boolean): Boolean {
            if (intent == null || key == null) return fallback
            val extras: Bundle? = intent.getExtras()
            if (extras == null || !extras.containsKey(key)) return fallback
            val value: Any? = extras.get(key)
            if (value is Boolean) return value as Boolean
            if (value is Number) return (value as Number).toInt() != 0
            if (value == null) return fallback
            val s = java.lang.String.valueOf(value).lowercase(Locale.US).trim { it <= ' ' }
            if (
                "1".equals(s) ||
                    "true".equals(s) ||
                    "yes".equals(s) ||
                    "on".equals(s) ||
                    "да".equals(s)
            )
                return true
            if (
                "0".equals(s) ||
                    "false".equals(s) ||
                    "no".equals(s) ||
                    "off".equals(s) ||
                    "нет".equals(s)
            )
                return false
            return fallback
        }

        private fun rawExtras(intent: Intent?): String {
            val extras: Bundle? = if (intent == null) null else intent.getExtras()
            if (extras == null || extras.isEmpty()) return "{}"
            val out: StringBuilder = StringBuilder("{")
            var first: Boolean = true
            for (key: String? in extras.keySet()) {
                if (!first) out.append(", ")
                first = false
                val value: Any? = extras.get(key)
                out.append(key)
                    .append('=')
                    .append(
                        if (value == null) "null"
                        else NavigationFeature.clean(java.lang.String.valueOf(value))
                    )
            }
            out.append('}')
            return out.toString()
        }

        private fun shortRaw(action: String?, raw: String?): String {
            val value: String = action.toString() + " " + raw
            return if (value.length <= 220) value else value.substring(0, 217) + "..."
        }

        private fun first(vararg values: String?): String? {
            for (value: String? in values) {
                if (!value.isNullOrEmpty()) return value
            }
            return null
        }

        private fun clean(value: String?): String {
            if (value == null) return ""
            return value.replace('\n', ' ').replace('\r', ' ').trim { it <= ' ' }.replace(Regex("\\s+"), " ")
        }
    }
}
