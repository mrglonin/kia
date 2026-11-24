package kia.app.navigation.cluster

import android.content.Context
import android.os.SystemClock
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.navigation.domain.NavigationModeSettings
import kia.app.protocol.adapter.AdapterCommand
import kia.app.protocol.adapter.AdapterGateway
import kia.app.protocol.adapter.AdapterProtocol
import kia.app.protocol.adapter.AdapterTxOutcome

class NavigationClusterSender(context: Context) {
    private val app: Context?
    private val normalOutput: NormalNavigationOutput = NormalNavigationOutput()
    private val tbtOutput: TbtNavigationOutput = TbtNavigationOutput()
    private val finishDirectionOutput: FinishDirectionNavigationOutput =
        FinishDirectionNavigationOutput()
    private var lastFinishDirectionVisualKey: String? = ""
    private var lastFinishDirectionFrameAt: Long = 0
    private var lastRouteManeuverId: String? = ""
    private var lastNavigationActive: Boolean? = null

    init {
        this.app = context.getApplicationContext()
    }

    fun sendActive(active: Boolean): AdapterTxOutcome? {
        if (lastNavigationActive == null || lastNavigationActive != active) {
            resetNavigationRoute()
        }
        lastNavigationActive = active
        val frame = AdapterProtocol.navOn(active)
        return AdapterGateway.get(app!!)
            .send(AdapterCommand.loud(if (active) "nav active on" else "nav active off", frame))
    }

    fun sendManeuver(imageId: String?, distance: Float, km: Boolean) {
        sendManeuver(imageId, distance, km, -1)
    }

    fun sendManeuver(imageId: String?, distance: Float, km: Boolean, progressBucket: Int) {
        val logicalProgress: Int = NavigationClusterSender.normalizeProgress(progressBucket)
        val txProgress: Int = AdapterProtocol.txProgressBucket(logicalProgress)
        val tbt: Boolean = NavigationModeSettings.isTbt(app)
        val frame =
            if (tbt) tbtOutput.maneuver(imageId, distance, km, logicalProgress)
            else normalOutput.maneuver(imageId, distance, km, logicalProgress)
        sendActive(true)
        resetFinishDirectionOnManeuverChange(imageId)
        val outcome: AdapterTxOutcome? =
            AdapterGateway.get(app!!).send(AdapterCommand.loud("nav maneuver", frame))
        val summary: String =
            ("maneuver " +
                imageId +
                " dist=" +
                distance +
                (if (km) "km" else "m") +
                " progress=" +
                logicalProgress +
                " txProgress=" +
                txProgress +
                " mode=" +
                NavigationModeSettings.label(app) +
                " bytes=" +
                AdapterProtocol.hex(frame))
        recordTx(summary, outcome)
    }

    fun sendManeuverWithGrayRoad(
        imageId: String?,
        grayRoadId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
    ) {
        val logicalProgress: Int = NavigationClusterSender.normalizeProgress(progressBucket)
        val txProgress: Int = AdapterProtocol.txProgressBucket(logicalProgress)
        val tbt: Boolean = NavigationModeSettings.isTbt(app)
        val frame =
            if (tbt)
                tbtOutput.maneuverWithGrayRoad(imageId, grayRoadId, distance, km, logicalProgress)
            else
                normalOutput.maneuverWithGrayRoad(
                    imageId,
                    grayRoadId,
                    distance,
                    km,
                    logicalProgress,
                )
        sendActive(true)
        resetFinishDirectionOnManeuverChange(imageId)
        val outcome: AdapterTxOutcome? =
            AdapterGateway.get(app!!).send(AdapterCommand.loud("nav maneuver gray", frame))
        val summary: String =
            ("maneuver+gray " +
                imageId +
                " gray=" +
                grayRoadId +
                " dist=" +
                distance +
                (if (km) "km" else "m") +
                " progress=" +
                logicalProgress +
                " txProgress=" +
                txProgress +
                " mode=" +
                NavigationModeSettings.label(app) +
                " bytes=" +
                AdapterProtocol.hex(frame))
        recordTx(summary, outcome)
    }

    fun sendEta(distance: Float, km: Boolean) {
        val frame = AdapterProtocol.etaDistance(distance, km)
        val outcome: AdapterTxOutcome? =
            AdapterGateway.get(app!!).send(AdapterCommand.loud("nav eta", frame))
        recordTx(
            ("eta distance=" +
                distance +
                (if (km) "km" else "m") +
                " bytes=" +
                AdapterProtocol.hex(frame)),
            outcome,
        )
    }

    fun sendEtaTime(hour: Int, minute: Int) {
        val frame = AdapterProtocol.etaTime(hour, minute)
        val outcome: AdapterTxOutcome? =
            AdapterGateway.get(app!!).send(AdapterCommand.loud("nav eta time", frame))
        recordTx(
            ("eta time=" +
                hour +
                ":" +
                (if (minute < 10) "0" else "") +
                minute +
                " bytes=" +
                AdapterProtocol.hex(frame)),
            outcome,
        )
    }

    fun sendText(text: String?) {
        val frame = AdapterProtocol.navText(text!!)
        val outcome: AdapterTxOutcome? =
            AdapterGateway.get(app!!).send(AdapterCommand.loud("nav text", frame))
        recordTx(
            "text=" + NavigationClusterSender.clean(text) + " bytes=" + AdapterProtocol.hex(frame),
            outcome,
        )
    }

    fun sendSpeedLimit(kmh: Int): AdapterTxOutcome? {
        val frame = AdapterProtocol.speedLimit(kmh)
        val outcome: AdapterTxOutcome? =
            AdapterGateway.get(app!!).send(AdapterCommand.loud("nav speed", frame))
        recordTx("speedLimit=" + kmh + " bytes=" + AdapterProtocol.hex(frame), outcome)
        return outcome
    }

    fun sendCompassStep(uiStep: Int, kiaNaviFormat: Boolean): AdapterTxOutcome? {
        val frame = AdapterProtocol.compassStep(uiStep, kiaNaviFormat)
        val outcome: AdapterTxOutcome? =
            AdapterGateway.get(app!!).send(AdapterCommand.quiet("nav compass", frame))
        val format: String = if (kiaNaviFormat) " format=kia-navi" else ""
        recordTx(
            "compass step=" + uiStep + format + " bytes=" + AdapterProtocol.hex(frame),
            outcome,
        )
        return outcome
    }

    fun sendDirectionToFinish(uiStep: Int, distance: Float, km: Boolean) {
        val frame = finishDirectionOutput.directionToFinish(uiStep, distance, km)
        val hex = AdapterProtocol.hex(frame)
        val visualKey = NavigationClusterSender.finishDirectionVisualKey(frame)
        val now: Long = SystemClock.elapsedRealtime()
        if (
            visualKey.equals(lastFinishDirectionVisualKey) &&
                now - lastFinishDirectionFrameAt <
                    NavigationClusterSender.FINISH_DIRECTION_DUPLICATE_TX_MS
        ) {
            return
        }
        sendActive(true)
        lastFinishDirectionVisualKey = visualKey
        lastFinishDirectionFrameAt = now
        val outcome: AdapterTxOutcome? =
            AdapterGateway.get(app!!).send(AdapterCommand.loud("nav finish direction", frame))
        recordTx(
            ("finish direction step=" +
                uiStep +
                " dist=" +
                distance +
                (if (km) "km" else "m") +
                " bytes=" +
                hex),
            outcome,
        )
    }

    fun resetNavigationRoute() {
        lastFinishDirectionVisualKey = ""
        lastFinishDirectionFrameAt = 0L
        lastRouteManeuverId = ""
    }

    private fun resetFinishDirectionOnManeuverChange(imageId: String?) {
        val cleanId = NavigationClusterSender.clean(imageId)
        if (!lastRouteManeuverId!!.isEmpty() && !lastRouteManeuverId.equals(cleanId)) {
            lastFinishDirectionVisualKey = ""
            lastFinishDirectionFrameAt = 0L
        }
        lastRouteManeuverId = cleanId
    }

    private fun recordTx(line: String?, outcome: AdapterTxOutcome?) {
        val recorded: String = NavigationClusterSender.clean(line) + " outcome=" + outcome
        AppLog.navigation(
            app,
            "Navigation TX " + outcome + ": " + NavigationClusterSender.clean(line),
        )
        StateStore.appendNavigationTx(app, recorded)
    }

    companion object {
        private const val FINISH_DIRECTION_DUPLICATE_TX_MS: Long = 5000L

        @JvmStatic
        fun finishDirectionVisualKey(frame: ByteArray?): String? {
            if (frame == null || frame.size < 13) return AdapterProtocol.hex(frame)
            return ((frame.get(5).toInt() and 0xff).toString() +
                "|" +
                (frame.get(6).toInt() and 0xff) +
                "|" +
                (frame.get(7).toInt() and 0xff) +
                "|" +
                (frame.get(8).toInt() and 0xff) +
                "|" +
                (frame.get(9).toInt() and 0xff) +
                "|" +
                (frame.get(10).toInt() and 0xff) +
                "|" +
                (frame.get(11).toInt() and 0xff) +
                "|" +
                (frame.get(12).toInt() and 0xff))
        }

        private fun normalizeProgress(progressBucket: Int): Int {
            if (progressBucket < 0) return 0
            return Math.max(0, Math.min(9, progressBucket))
        }

        private fun clean(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
