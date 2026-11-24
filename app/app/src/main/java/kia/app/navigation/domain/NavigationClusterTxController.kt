package kia.app.navigation.domain

import java.util.Locale
import kia.app.navigation.cluster.NavigationClusterSender

internal class NavigationClusterTxController(
    sender: NavigationClusterSender?,
    visualSink: VisualSink?,
) {
    internal interface VisualSink {
        fun onClusterVisual(visual: String?)
    }

    private val sender: NavigationClusterSender?
    private val visualSink: VisualSink?
    private var lastManeuverKey: NavigationTxKey? = null
    private var lastMainManeuverKey: NavigationTxKey? = null
    private var lastCustomKey: String? = ""
    private var lastCustomVisual: String? = ""

    init {
        this.sender = sender
        this.visualSink = visualSink
    }

    fun markCustomManeuverVisual(key: String?, visual: String?, force: Boolean): Boolean {
        val cleanKey = NavigationClusterTxController.clean(key)
        val cleanVisual = NavigationClusterTxController.clean(visual)
        if (!force && cleanKey.equals(lastCustomKey) && cleanVisual.equals(lastCustomVisual)) {
            return false
        }
        lastCustomKey = cleanKey
        lastCustomVisual = cleanVisual
        lastManeuverKey = null
        visualSink!!.onClusterVisual(cleanVisual)
        return true
    }

    fun sendManeuver(
        imageId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
        force: Boolean,
    ) {
        sendManeuver(imageId, distance, km, progressBucket, force, true)
    }

    fun sendManeuver(
        imageId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
        force: Boolean,
        rememberAsMain: Boolean,
    ) {
        val key: NavigationTxKey =
            NavigationClusterTxController.maneuverKey(imageId, "", distance, km, progressBucket)
        if (!force && key.equals(lastManeuverKey)) {
            if (rememberAsMain) lastMainManeuverKey = key
            return
        }
        send(key, rememberAsMain)
    }

    fun sendManeuverWithGrayRoad(
        imageId: String?,
        grayRoadId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
        force: Boolean,
    ) {
        sendManeuverWithGrayRoad(imageId, grayRoadId, distance, km, progressBucket, force, true)
    }

    fun sendManeuverWithGrayRoad(
        imageId: String?,
        grayRoadId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
        force: Boolean,
        rememberAsMain: Boolean,
    ) {
        val key: NavigationTxKey =
            NavigationClusterTxController.maneuverKey(
                imageId,
                grayRoadId,
                distance,
                km,
                progressBucket,
            )
        if (!force && key.equals(lastManeuverKey)) {
            if (rememberAsMain) lastMainManeuverKey = key
            return
        }
        send(key, rememberAsMain)
    }

    fun resendLastManeuver(): Boolean {
        if (lastManeuverKey == null) return false
        send(lastManeuverKey!!, lastManeuverKey!!.equals(lastMainManeuverKey))
        return true
    }

    fun rememberMainManeuver(imageId: String?, distance: Float, km: Boolean, progressBucket: Int) {
        rememberMainManeuver(imageId, "", distance, km, progressBucket)
    }

    fun rememberMainManeuver(
        imageId: String?,
        grayRoadId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
    ) {
        val key: NavigationTxKey =
            NavigationClusterTxController.maneuverKey(
                imageId,
                grayRoadId,
                distance,
                km,
                progressBucket,
            )
        lastMainManeuverKey =
            NavigationClusterTxController.updatedMainManeuverKey(lastMainManeuverKey, key, true)
    }

    fun lastMainManeuverKey(): NavigationTxKey? {
        return lastMainManeuverKey
    }

    fun resendLastMainManeuver(): Boolean {
        if (lastMainManeuverKey == null) return false
        send(lastMainManeuverKey!!, true)
        return true
    }

    fun clear() {
        lastManeuverKey = null
        lastMainManeuverKey = null
        lastCustomKey = ""
        lastCustomVisual = ""
    }

    private fun send(key: NavigationTxKey, rememberAsMain: Boolean) {
        if (key.grayRoad!!.isEmpty()) {
            visualSink!!.onClusterVisual(
                NavigationClusterTxController.clusterVisualText(
                    key.maneuver,
                    key.progress,
                    key.distance,
                    key.km,
                )
            )
            sender!!.sendManeuver(key.maneuver, key.distance, key.km, key.progress)
        } else {
            visualSink!!.onClusterVisual(
                (key.maneuver.toString() +
                    " + " +
                    key.grayRoad +
                    " / " +
                    NavigationClusterTxController.clusterDistanceText(key.distance, key.km) +
                    " / progress=" +
                    key.progress)
            )
            sender!!.sendManeuverWithGrayRoad(
                key.maneuver,
                key.grayRoad,
                key.distance,
                key.km,
                key.progress,
            )
        }
        lastManeuverKey = key
        lastMainManeuverKey =
            NavigationClusterTxController.updatedMainManeuverKey(
                lastMainManeuverKey,
                key,
                rememberAsMain,
            )
        lastCustomKey = ""
        lastCustomVisual = ""
    }

    companion object {
        @JvmStatic
        fun updatedMainManeuverKey(
            currentMain: NavigationTxKey?,
            candidate: NavigationTxKey?,
            rememberAsMain: Boolean,
        ): NavigationTxKey? {
            return if (rememberAsMain) candidate else currentMain
        }

        @JvmStatic
        fun clusterVisualText(
            imageId: String?,
            progressBucket: Int,
            distance: Float,
            km: Boolean,
        ): String {
            return (NavigationClusterTxController.clean(imageId) +
                " / " +
                NavigationClusterTxController.clusterDistanceText(distance, km) +
                " / progress=" +
                NavigationClusterTxController.normalizeProgressBucket(progressBucket))
        }

        @JvmStatic
        fun normalizeProgressBucket(progressBucket: Int): Int {
            return if (progressBucket < 0) 0 else Math.max(0, Math.min(9, progressBucket))
        }

        @JvmStatic
        fun clusterDistanceText(distance: Float, km: Boolean): String {
            if (distance <= 0f) return "0 м"
            if (km) return NavigationClusterTxController.trimDistance(distance) + " км"
            return NavigationClusterTxController.trimDistance(
                NavigationClusterTxController.roundMetersForDisplay(distance)
            ) + " м"
        }

        @JvmStatic
        fun clusterDistanceValue(distance: Float, km: Boolean): Float {
            if (distance <= 0f) return 0f
            return if (km) distance
            else NavigationClusterTxController.roundMetersForDisplay(distance)
        }

        @JvmStatic
        fun maneuverKey(
            imageId: String?,
            grayRoadId: String?,
            distance: Float,
            km: Boolean,
            progressBucket: Int,
        ): NavigationTxKey {
            return NavigationTxKey(
                NavigationClusterTxController.clean(imageId),
                NavigationClusterTxController.clean(grayRoadId),
                NavigationClusterTxController.clusterDistanceValue(distance, km),
                km,
                NavigationClusterTxController.normalizeProgressBucket(progressBucket),
            )
        }

        private fun trimDistance(distance: Float): String {
            if (distance == Math.round(distance).toFloat())
                return java.lang.String.valueOf(Math.round(distance))
            return String.format(Locale.US, "%.1f", distance)
        }

        private fun roundMetersForDisplay(meters: Float): Float {
            if (meters <= 0f) return 0f
            return Math.max(10f, Math.round(meters / 10f) * 10f)
        }

        private fun clean(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
