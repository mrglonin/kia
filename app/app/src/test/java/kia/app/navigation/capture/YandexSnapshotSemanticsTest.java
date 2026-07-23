package kia.app.navigation.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YandexSnapshotSemanticsTest {
    @Test
    public void excludesEnvelopeAndRawSnapshotFromSemanticSignature() {
        assertTrue(YandexSnapshotSemantics.isEnvelopeKey("seq"));
        assertTrue(YandexSnapshotSemantics.isEnvelopeKey("wall_time_ms"));
        assertTrue(YandexSnapshotSemantics.isEnvelopeKey("snapshot_json"));
        assertFalse(YandexSnapshotSemantics.isEnvelopeKey("maneuver"));
    }

    @Test
    public void recognizesMicroTransitionFields() {
        assertTrue(YandexSnapshotSemantics.isMicroKey("lane_maneuver"));
        assertTrue(YandexSnapshotSemantics.isMicroKey("micro_distance_meters"));
        assertTrue(YandexSnapshotSemantics.isMicroKey("highlighted_direction"));
        assertFalse(YandexSnapshotSemantics.isMicroKey("current_speed_kmh"));
    }

    @Test
    public void parsesGroupedDistanceWithoutTurningThreeThousandIntoThree() {
        assertEquals(3000L, YandexSnapshotSemantics.distanceMeters("3 000 м"));
        assertEquals(3500L, YandexSnapshotSemantics.distanceMeters("3,5 км"));
    }

    @Test
    public void microIdentityChangesOnlyForSemanticTransition() {
        String first = YandexSnapshotSemantics.microIdentity(
                true, "turn_right", "right", "straight,right", "segment-1");
        String distanceOnlyUpdate = YandexSnapshotSemantics.microIdentity(
                true, "turn_right", "right", "straight,right", "segment-1");
        String next = YandexSnapshotSemantics.microIdentity(
                true, "turn_left", "left", "straight,left", "segment-2");
        String cleared = YandexSnapshotSemantics.microIdentity(true, "", "", "", "");

        assertEquals(first, distanceOnlyUpdate);
        assertFalse(first.equals(next));
        assertFalse(next.equals(cleared));
        assertEquals("micro|none", cleared);
    }

    @Test
    public void signOnlyDirectionChangeHasDifferentIdentity() {
        String right = YandexSnapshotSemantics.microIdentity(
                true, "", "", "", "", "highlight=right90");
        String left = YandexSnapshotSemantics.microIdentity(
                true, "", "", "", "", "highlight=left90");

        assertFalse(right.equals(left));
    }

    @Test
    public void absentEnvelopeIsProtectedAsARealMicroStateTransition() {
        String right = YandexSnapshotSemantics.microStateIdentity(
                true, "turn_right", "right");
        String absentWithoutKeys = YandexSnapshotSemantics.microStateIdentity(false);
        String explicitClear = YandexSnapshotSemantics.microStateIdentity(
                true, "", "", "");

        assertEquals(YandexSnapshotSemantics.MICRO_ABSENT_IDENTITY, absentWithoutKeys);
        assertEquals(absentWithoutKeys, explicitClear);
        assertFalse(right.equals(absentWithoutKeys));
        assertFalse(absentWithoutKeys.equals(right));
    }

    @Test
    public void explicitMicroEmitsPacketWhenMainDistanceIsUnknown() {
        assertTrue(YandexSnapshotSemantics.shouldEmitManeuver(
                "turn_right", "", true));
        assertFalse(YandexSnapshotSemantics.shouldEmitManeuver(
                "turn_right", "", false));
    }

    @Test
    public void annotationAcceptsGenericExitMetadataFromYandexBridge() {
        assertEquals("3", YandexSnapshotSemantics.roundaboutExitForProvenance(
                "annotation", "", "", "3"));
    }

    @Test
    public void annotationPrefixedExitRemainsAuthoritative() {
        assertEquals("2", YandexSnapshotSemantics.roundaboutExitForProvenance(
                "annotation", "2", "", "4"));
    }

    @Test
    public void notificationDoesNotReuseAnnotationExit() {
        assertEquals("4", YandexSnapshotSemantics.roundaboutExitForProvenance(
                "notification", "2", "4", "3"));
    }

    @Test
    public void coalescingIdentityPreservesRouteAndManeuverTransitions() {
        String first = YandexSnapshotSemantics.coalescingIdentity(
                "active", "route-a", "turn_right", "annotation",
                "turn right", "", "", "straight,right", "micro|turn_right");
        String distanceOnlyUpdate = YandexSnapshotSemantics.coalescingIdentity(
                "active", "route-a", "turn_right", "annotation",
                "turn right", "", "", "straight,right", "micro|turn_right");
        String nextMain = YandexSnapshotSemantics.coalescingIdentity(
                "active", "route-a", "roundabout", "annotation",
                "roundabout", "2", "", "straight,right", "micro|turn_right");
        String nextRoute = YandexSnapshotSemantics.coalescingIdentity(
                "active", "route-b", "turn_right", "annotation",
                "turn right", "", "", "straight,right", "micro|turn_right");

        assertEquals(first, distanceOnlyUpdate);
        assertFalse(first.equals(nextMain));
        assertFalse(first.equals(nextRoute));
    }
}
