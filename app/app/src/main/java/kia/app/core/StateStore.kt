package kia.app.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.function.UnaryOperator
import kia.app.core.model.AdapterState
import kia.app.core.model.AmpState
import kia.app.core.model.CallState
import kia.app.core.model.MediaState
import kia.app.core.model.NavigationState
import kia.app.core.model.RctaState
import kia.app.core.model.TpmsState
import kia.app.core.model.UpdateState
import kia.app.core.model.VehicleState

object StateStore {
    private const val PREFS: String = "kia_state"
    private const val NAV_PREFIX: String = "nav_"
    private const val NAV_TX_PERSIST_INTERVAL_MS: Long = 2000L
    private val UI_BROADCAST_HANDLER: Handler = Handler(Looper.getMainLooper())

    private var adapter: AdapterState = AdapterState.empty()
    private var media: MediaState = MediaState.empty()
    private var navigation: NavigationState = NavigationState.empty()
    private var call: CallState = CallState.empty()
    private var amp: AmpState = AmpState.empty()
    private var updates: UpdateState = UpdateState.empty()
    private var vehicle: VehicleState = VehicleState.empty()
    private var tpms: TpmsState = TpmsState.empty()
    private var rcta: RctaState = RctaState.empty()
    private var lastLog: String = ""
    private var pendingBroadcast: Boolean = false
    private var lastNavigationTxPersistAt: Long = 0

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun adapter(): AdapterState {
        return StateStore.adapter
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun media(): MediaState {
        return StateStore.media
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun navigation(): NavigationState {
        return StateStore.navigation
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun call(): CallState {
        return StateStore.call
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun amp(): AmpState {
        return StateStore.amp
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun updates(): UpdateState {
        return StateStore.updates
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun vehicle(): VehicleState {
        return StateStore.vehicle
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun tpms(): TpmsState {
        return StateStore.tpms
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun rcta(): RctaState {
        return StateStore.rcta
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun lastLog(): String {
        return StateStore.lastLog
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setAdapter(context: Context?, value: AdapterState?) {
        StateStore.adapter = if (value == null) AdapterState.empty() else value
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setMedia(context: Context?, value: MediaState?) {
        var next: MediaState = if (value == null) MediaState.empty() else value
        if (
            StateStore.media != null &&
                StateStore.media.clusterTx != null &&
                !StateStore.media.clusterTx!!.isEmpty() &&
                (next.clusterTx == null || next.clusterTx.isEmpty())
        ) {
            next = next.withClusterTxText(StateStore.media.clusterTx, next.updatedAt)
        }
        StateStore.media = next
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setNavigation(context: Context?, value: NavigationState?) {
        var next: NavigationState = if (value == null) NavigationState.empty() else value
        if (
            StateStore.navigation != null &&
                StateStore.navigation.clusterTx != null &&
                !StateStore.navigation.clusterTx.isEmpty()
        ) {
            val mergedClusterTx =
                StateStore.mergeProtectedNavigationTx(
                    StateStore.navigation.clusterTx,
                    next.clusterTx,
                )
            if (!mergedClusterTx.equals(next.clusterTx)) {
                next = next.withClusterTxText(mergedClusterTx, next.updatedAt)
            }
        }
        if (
            next.active &&
                !StateStore.hasNavigationDebug(next) &&
                StateStore.navigation != null &&
                StateStore.hasNavigationDebug(StateStore.navigation)
        ) {
            next =
                next.withNavigationDebug(
                    StateStore.navigation.mainManeuverId,
                    StateStore.navigation.routeActionId,
                    StateStore.navigation.microManeuverId,
                    StateStore.navigation.microDistance,
                    StateStore.navigation.microStatus,
                    StateStore.navigation.grayRoadId,
                    StateStore.navigation.grayRoadScheme,
                    next.updatedAt,
                )
        }
        if (
            next.active &&
                !StateStore.hasNavigationRaw(next) &&
                StateStore.navigation != null &&
                StateStore.hasNavigationRaw(StateStore.navigation)
        ) {
            next =
                next.withNavigationRaw(
                    StateStore.navigation.laneRaw,
                    StateStore.navigation.lanePosition,
                    StateStore.navigation.roadSchemeRaw,
                    StateStore.navigation.upcomingRaw,
                    next.updatedAt,
                )
        }
        StateStore.navigation = next
        StateStore.persistNavigation(context, StateStore.navigation)
        StateStore.lastNavigationTxPersistAt = SystemClock.elapsedRealtime()
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setCall(context: Context?, value: CallState?) {
        StateStore.call = if (value == null) CallState.empty() else value
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun restoreNavigation(context: Context?) {
        if (context == null) {
            StateStore.navigation = NavigationState.empty()
            return
        }
        val p = context.getSharedPreferences(StateStore.PREFS, Context.MODE_PRIVATE)
        var restored: NavigationState =
            NavigationState(
                p!!.getBoolean(StateStore.NAV_PREFIX + "active", false),
                p.getBoolean(StateStore.NAV_PREFIX + "finishReached", false),
                p.getBoolean(StateStore.NAV_PREFIX + "speedExceeded", false),
                p.getString(StateStore.NAV_PREFIX + "maneuver", ""),
                p.getString(StateStore.NAV_PREFIX + "maneuverText", ""),
                p.getString(StateStore.NAV_PREFIX + "maneuverDistance", ""),
                p.getString(StateStore.NAV_PREFIX + "routeDistance", ""),
                p.getString(StateStore.NAV_PREFIX + "routeTime", ""),
                p.getString(StateStore.NAV_PREFIX + "arrivalTime", ""),
                p.getString(StateStore.NAV_PREFIX + "currentStreet", ""),
                p.getString(StateStore.NAV_PREFIX + "nextStreet", ""),
                p.getString(StateStore.NAV_PREFIX + "finishStreet", ""),
                p.getString(StateStore.NAV_PREFIX + "speedLimit", ""),
                p.getString(StateStore.NAV_PREFIX + "currentSpeed", ""),
                p.getString(StateStore.NAV_PREFIX + "source", ""),
                p.getLong(StateStore.NAV_PREFIX + "updatedAt", 0L),
            )
        val laneHint = p.getString(StateStore.NAV_PREFIX + "laneHint", "")
        val laneSource = p.getString(StateStore.NAV_PREFIX + "laneSource", "")
        if (laneHint != null && !laneHint.isEmpty()) {
            restored = restored.withLaneHint(laneHint, laneSource, restored.updatedAt)
        }
        val eventHint = p.getString(StateStore.NAV_PREFIX + "eventHint", "")
        val eventSource = p.getString(StateStore.NAV_PREFIX + "eventSource", "")
        if (eventHint != null && !eventHint.isEmpty()) {
            restored = restored.withEventHint(eventHint, eventSource, restored.updatedAt)
        }
        restored =
            restored.withNavigationDebug(
                p.getString(StateStore.NAV_PREFIX + "mainManeuverId", ""),
                p.getString(StateStore.NAV_PREFIX + "routeActionId", ""),
                p.getString(StateStore.NAV_PREFIX + "microManeuverId", ""),
                p.getString(StateStore.NAV_PREFIX + "microDistance", ""),
                p.getString(StateStore.NAV_PREFIX + "microStatus", ""),
                p.getString(StateStore.NAV_PREFIX + "grayRoadId", ""),
                p.getString(StateStore.NAV_PREFIX + "grayRoadScheme", ""),
                restored.updatedAt,
            )
        restored =
            restored.withNavigationRaw(
                p.getString(StateStore.NAV_PREFIX + "laneRaw", ""),
                p.getString(StateStore.NAV_PREFIX + "lanePosition", ""),
                p.getString(StateStore.NAV_PREFIX + "roadSchemeRaw", ""),
                p.getString(StateStore.NAV_PREFIX + "upcomingRaw", ""),
                restored.updatedAt,
            )
        val clusterVisual = p.getString(StateStore.NAV_PREFIX + "clusterVisual", "")
        if (clusterVisual != null && !clusterVisual.isEmpty()) {
            restored = restored.withClusterVisualText(clusterVisual, restored.updatedAt)
        }
        val clusterTx = p.getString(StateStore.NAV_PREFIX + "clusterTx", "")
        if (clusterTx != null && !clusterTx.isEmpty()) {
            restored = restored.withClusterTxText(clusterTx, restored.updatedAt)
        }
        StateStore.navigation = restored
    }

    private fun hasNavigationDebug(value: NavigationState?): Boolean {
        if (value == null) return false
        return StateStore.notEmpty(value.mainManeuverId) ||
            StateStore.notEmpty(value.routeActionId) ||
            StateStore.notEmpty(value.microManeuverId) ||
            StateStore.notEmpty(value.microDistance) ||
            StateStore.notEmpty(value.microStatus) ||
            StateStore.notEmpty(value.grayRoadId) ||
            StateStore.notEmpty(value.grayRoadScheme)
    }

    private fun hasNavigationRaw(value: NavigationState?): Boolean {
        if (value == null) return false
        return StateStore.notEmpty(value.laneRaw) ||
            StateStore.notEmpty(value.lanePosition) ||
            StateStore.notEmpty(value.roadSchemeRaw) ||
            StateStore.notEmpty(value.upcomingRaw)
    }

    private fun notEmpty(value: String?): Boolean {
        return value != null && !value.isEmpty()
    }

    private fun persistNavigation(context: Context?, value: NavigationState?) {
        if (context == null || value == null) return
        context
            .getSharedPreferences(StateStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(StateStore.NAV_PREFIX + "active", value.active)
            .putBoolean(StateStore.NAV_PREFIX + "finishReached", value.finishReached)
            .putBoolean(StateStore.NAV_PREFIX + "speedExceeded", value.speedExceeded)
            .putString(StateStore.NAV_PREFIX + "maneuver", value.maneuver)
            .putString(StateStore.NAV_PREFIX + "maneuverText", value.maneuverText)
            .putString(StateStore.NAV_PREFIX + "maneuverDistance", value.maneuverDistance)
            .putString(StateStore.NAV_PREFIX + "routeDistance", value.routeDistance)
            .putString(StateStore.NAV_PREFIX + "routeTime", value.routeTime)
            .putString(StateStore.NAV_PREFIX + "arrivalTime", value.arrivalTime)
            .putString(StateStore.NAV_PREFIX + "currentStreet", value.currentStreet)
            .putString(StateStore.NAV_PREFIX + "nextStreet", value.nextStreet)
            .putString(StateStore.NAV_PREFIX + "finishStreet", value.finishStreet)
            .putString(StateStore.NAV_PREFIX + "speedLimit", value.speedLimit)
            .putString(StateStore.NAV_PREFIX + "currentSpeed", value.currentSpeed)
            .putString(StateStore.NAV_PREFIX + "source", value.source)
            .putString(StateStore.NAV_PREFIX + "mainManeuverId", value.mainManeuverId)
            .putString(StateStore.NAV_PREFIX + "routeActionId", value.routeActionId)
            .putString(StateStore.NAV_PREFIX + "microManeuverId", value.microManeuverId)
            .putString(StateStore.NAV_PREFIX + "microDistance", value.microDistance)
            .putString(StateStore.NAV_PREFIX + "microStatus", value.microStatus)
            .putString(StateStore.NAV_PREFIX + "grayRoadId", value.grayRoadId)
            .putString(StateStore.NAV_PREFIX + "grayRoadScheme", value.grayRoadScheme)
            .putString(StateStore.NAV_PREFIX + "laneHint", value.laneHint)
            .putString(StateStore.NAV_PREFIX + "laneSource", value.laneSource)
            .putString(StateStore.NAV_PREFIX + "eventHint", value.eventHint)
            .putString(StateStore.NAV_PREFIX + "eventSource", value.eventSource)
            .putString(StateStore.NAV_PREFIX + "clusterVisual", value.clusterVisual)
            .putString(StateStore.NAV_PREFIX + "clusterTx", value.clusterTx)
            .putString(StateStore.NAV_PREFIX + "laneRaw", value.laneRaw)
            .putString(StateStore.NAV_PREFIX + "lanePosition", value.lanePosition)
            .putString(StateStore.NAV_PREFIX + "roadSchemeRaw", value.roadSchemeRaw)
            .putString(StateStore.NAV_PREFIX + "upcomingRaw", value.upcomingRaw)
            .putLong(StateStore.NAV_PREFIX + "updatedAt", value.updatedAt)
            .apply()
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun appendNavigationTx(context: Context?, value: String?) {
        val line = if (value == null) "" else value.trim()
        if (line.isEmpty()) return
        val current: NavigationState =
            if (StateStore.navigation == null) NavigationState.empty() else StateStore.navigation
        StateStore.navigation =
            current.withClusterTxText(
                StateStore.appendNavigationTxLine(current.clusterTx, line),
                System.currentTimeMillis(),
            )
        val now: Long = SystemClock.elapsedRealtime()
        if (now - StateStore.lastNavigationTxPersistAt >= StateStore.NAV_TX_PERSIST_INTERVAL_MS) {
            StateStore.persistNavigation(context, StateStore.navigation)
            StateStore.lastNavigationTxPersistAt = now
        }
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun appendMediaTx(context: Context?, value: String?) {
        val line = if (value == null) "" else value.trim()
        if (line.isEmpty()) return
        val current: MediaState =
            if (StateStore.media == null) MediaState.empty() else StateStore.media
        StateStore.media =
            current.withClusterTxText(
                StateStore.appendRollingLine(current.clusterTx, line, 14, 3400),
                System.currentTimeMillis(),
            )
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setAmp(context: Context?, value: AmpState?) {
        StateStore.amp = if (value == null) AmpState.empty() else value
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setUpdates(context: Context?, value: UpdateState?) {
        StateStore.updates = if (value == null) UpdateState.empty() else value
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun updateUpdates(context: Context?, update: UnaryOperator<UpdateState?>?) {
        if (update == null) return
        val value =
            update.apply(
                if (StateStore.updates == null) UpdateState.empty() else StateStore.updates
            )
        StateStore.updates = if (value == null) UpdateState.empty() else value
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setVehicle(context: Context?, value: VehicleState?) {
        StateStore.vehicle = if (value == null) VehicleState.empty() else value
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setTpms(context: Context?, value: TpmsState?) {
        StateStore.tpms = if (value == null) TpmsState.empty() else value
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setRcta(context: Context?, value: RctaState?) {
        StateStore.rcta = if (value == null) RctaState.empty() else value
        StateStore.changed(context)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun setLastLog(context: Context?, value: String?) {
        StateStore.lastLog = if (value == null) "" else value
        StateStore.changed(context)
    }

    private fun changed(context: Context?) {
        if (context == null) return
        val broadcastContext = context.getApplicationContext()
        if (broadcastContext == null) return
        if (StateStore.pendingBroadcast) return
        StateStore.pendingBroadcast = true
        StateStore.UI_BROADCAST_HANDLER.post({
            StateStore.pendingBroadcast = false
            AppLog.broadcast(broadcastContext)
        })
    }

    private fun appendRollingLine(
        current: String?,
        line: String?,
        maxLines: Int,
        maxChars: Int,
    ): String? {
        val joined =
            if (current == null || current.trim().isEmpty()) line else current.trim() + "\n" + line
        return StateStore.trimRollingLines(joined, maxLines, maxChars)
    }

    private fun appendNavigationTxLine(current: String?, line: String?): String? {
        val previousCompass: String = StateStore.lastLineStarting(current, "compass step=")!!
        val previousFinishDirection: String =
            StateStore.lastLineStarting(current, "finish direction ")!!
        var rolled: String? = StateStore.appendRollingLine(current, line, 16, 3600)
        if (!previousCompass.isEmpty() && !StateStore.hasLineStarting(rolled, "compass step=")) {
            rolled = previousCompass + "\n" + rolled
        }
        if (
            !previousFinishDirection.isEmpty() &&
                !StateStore.hasLineStarting(rolled, "finish direction ")
        ) {
            rolled = previousFinishDirection + "\n" + rolled
        }
        return StateStore.trimRollingLines(rolled, 18, 4200)
    }

    private fun mergeProtectedNavigationTx(previous: String?, next: String?): String? {
        var merged: String? = if (next == null) "" else next.trim()
        val previousCompass: String = StateStore.lastLineStarting(previous, "compass step=")!!
        val previousFinishDirection: String =
            StateStore.lastLineStarting(previous, "finish direction ")!!
        if (!previousCompass.isEmpty() && !StateStore.hasLineStarting(merged, "compass step=")) {
            merged = if (merged!!.isEmpty()) previousCompass else previousCompass + "\n" + merged
        }
        if (
            !previousFinishDirection.isEmpty() &&
                !StateStore.hasLineStarting(merged, "finish direction ")
        ) {
            merged =
                if (merged!!.isEmpty()) previousFinishDirection
                else previousFinishDirection + "\n" + merged
        }
        return StateStore.trimRollingLines(merged, 18, 4200)
    }

    private fun trimRollingLines(text: String?, maxLines: Int, maxChars: Int): String? {
        val joined = if (text == null) "" else text.trim()
        if (joined.isEmpty()) return ""
        val lines = java.util.regex.Pattern.compile("\\n").split(joined)
        val out: StringBuilder = StringBuilder()
        val start: Int = Math.max(0, lines.size - maxLines)
        for (i in start..<lines.size) {
            val item = if (lines.get(i) == null) "" else lines.get(i)!!.trim()
            if (item.isEmpty()) continue
            if (out.length > 0) out.append('\n')
            out.append(item)
        }
        if (out.length <= maxChars) return out.toString()
        return out.substring(out.length - maxChars)
    }

    private fun lastLineStarting(text: String?, prefix: String?): String? {
        val cleanText = if (text == null) "" else text.trim()
        if (cleanText.isEmpty()) return ""
        val lines = java.util.regex.Pattern.compile("\\n").split(cleanText)
        for (i in lines.indices.reversed()) {
            val line = if (lines.get(i) == null) "" else lines.get(i)!!.trim()
            if (line.startsWith(prefix!!)) return line
        }
        return ""
    }

    private fun hasLineStarting(text: String?, prefix: String?): Boolean {
        return !StateStore.lastLineStarting(text, prefix)!!.isEmpty()
    }
}
