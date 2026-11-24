package kia.app.navigation.cluster

import kia.app.protocol.adapter.AdapterProtocol

internal class TbtNavigationOutput {
    fun maneuver(imageId: String?, distance: Float, km: Boolean, progressBucket: Int): ByteArray? {
        return AdapterProtocol.maneuver(imageId, distance, km, true, progressBucket)
    }

    fun maneuverWithGrayRoad(
        imageId: String?,
        grayRoadId: String?,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
    ): ByteArray? {
        return AdapterProtocol.maneuverWithGrayRoad(
            imageId,
            grayRoadId,
            distance,
            km,
            true,
            progressBucket,
        )
    }
}
