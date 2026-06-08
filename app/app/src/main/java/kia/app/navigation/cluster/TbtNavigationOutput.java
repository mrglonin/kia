package kia.app.navigation.cluster;

import kia.app.protocol.adapter.AdapterProtocol;

final class TbtNavigationOutput {
    byte[] maneuver(String imageId, float distance, boolean km, int progressBucket) {
        return AdapterProtocol.maneuver(imageId, distance, km, true, progressBucket);
    }

    byte[] maneuverWithGrayRoad(String imageId, String grayRoadId, float distance,
                                boolean km, int progressBucket) {
        return AdapterProtocol.maneuverWithGrayRoad(imageId, grayRoadId, distance, km,
                true, progressBucket);
    }
}
