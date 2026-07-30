package kia.app.navigation.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class YandexMainDistanceContinuityPolicyTest {
    private static final long MAX_ROUTE_DELTA = 350L;
    private static final long ZERO_METERS = 1L;

    @Test
    public void projectsTransientZeroFromStableMainDuringMicroGuidance() {
        assertEquals(980L, select("main|right", "main|right",
                1000L, 0L, 5000L, 4980L, true));
        assertEquals(999L, select("main|right", "main|right",
                1000L, 1L, 5000L, 4999L, true));
    }

    @Test
    public void acceptsStationaryAndMaximumRouteDeltaBoundaries() {
        assertEquals(1000L, select("main|right", "main|right",
                1000L, 0L, 5000L, 5000L, true));
        assertEquals(650L, select("main|right", "main|right",
                1000L, 0L, 5000L, 4650L, true));
    }

    @Test
    public void validIncomingDistanceAlwaysPassesThrough() {
        assertEquals(2L, select("main|right", "main|right",
                1000L, 2L, 5000L, 4980L, true));
        assertEquals(750L, select("main|right", "main|right",
                1000L, 750L, 5000L, 4980L, true));
    }

    @Test
    public void requiresSemanticMicroAndStableNonEmptyMainIdentity() {
        assertEquals(0L, select("main|right", "main|right",
                1000L, 0L, 5000L, 4980L, false));
        assertEquals(0L, select("main|right", "main|left",
                1000L, 0L, 5000L, 4980L, true));
        assertEquals(0L, select("", "",
                1000L, 0L, 5000L, 4980L, true));
        assertEquals(0L, select(" ", "main|right",
                1000L, 0L, 5000L, 4980L, true));
    }

    @Test
    public void requiresKnownNonGrowingRouteWithinSafetyWindow() {
        assertEquals(0L, select("main|right", "main|right",
                1000L, 0L, -1L, 4980L, true));
        assertEquals(0L, select("main|right", "main|right",
                1000L, 0L, 5000L, -1L, true));
        assertEquals(0L, select("main|right", "main|right",
                1000L, 0L, 5000L, 4649L, true));
        assertEquals(0L, select("main|right", "main|right",
                1000L, 0L, 5000L, 5001L, true));
    }

    @Test
    public void rejectsProjectionAtOrBelowZeroThreshold() {
        assertEquals(0L, select("main|right", "main|right",
                100L, 0L, 5000L, 4900L, true));
        assertEquals(1L, select("main|right", "main|right",
                100L, 1L, 5000L, 4900L, true));
        assertEquals(0L, select("main|right", "main|right",
                1L, 0L, 5000L, 4990L, true));
    }

    @Test
    public void unknownIncomingDistanceIsNotProjected() {
        assertEquals(-1L, select("main|right", "main|right",
                1000L, -1L, 5000L, 4980L, true));
    }

    @Test
    public void roundaboutExitEnrichmentKeepsStableMainIdentity() {
        assertEquals(
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "annotation", "ROUNDABOUT"),
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "annotation", "ROUNDABOUT_EXIT_3"));
    }

    @Test
    public void directionalGlyphRefinementKeepsStableDistanceIdentity() {
        assertEquals(
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "annotation", "RIGHT"),
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "annotation", "CONTEXT_RA_EXIT_RIGHT"));
        assertEquals(
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "annotation", "LEFT"),
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "annotation", "CONTEXT_RA_HARD_TURN_LEFT"));
        assertEquals(
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "annotation", "RIGHT_FROM_LEFT"),
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "bridge_main", "CONTEXT_RA_TAKE_RIGHT"));
        assertEquals(
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "annotation", "LEFT_FROM_RIGHT"),
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "bridge_main", "CONTEXT_RA_TAKE_LEFT"));
    }

    @Test
    public void provenanceRefinementKeepsStableDistanceIdentity() {
        String annotation = YandexMainDistanceContinuityPolicy.identity(
                "route-1", "annotation", "RIGHT");
        String notification = YandexMainDistanceContinuityPolicy.identity(
                "route-1", "notification", "RIGHT");

        assertEquals(annotation, notification);
        assertEquals(
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "notification", "RIGHT"),
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "bridge_main", "CONTEXT_RA_EXIT_RIGHT"));
        assertTrue(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                annotation, notification,
                300L, 40L, 300L,
                5000L, 4970L, MAX_ROUTE_DELTA));
    }

    @Test
    public void differentRouteOrNonRoundaboutManeuverChangesIdentity() {
        String right = YandexMainDistanceContinuityPolicy.identity(
                "route-1", "annotation", "RIGHT");

        org.junit.Assert.assertNotEquals(right,
                YandexMainDistanceContinuityPolicy.identity(
                        "route-2", "annotation", "RIGHT"));
        org.junit.Assert.assertNotEquals(right,
                YandexMainDistanceContinuityPolicy.identity(
                        "route-1", "annotation", "LEFT"));
        assertEquals("", YandexMainDistanceContinuityPolicy.identity(
                "", "annotation", "RIGHT"));
    }

    @Test
    public void acceptsStrictSameIdentityPostPassJump() {
        assertTrue(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                300L, 40L, 300L,
                5000L, 4970L, MAX_ROUTE_DELTA));
        assertTrue(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                300L, 60L, 300L,
                5000L, 4990L, MAX_ROUTE_DELTA));
    }

    @Test
    public void acceptsSparseHandoffOnlyAfterRealCountdown() {
        assertTrue(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                1000L, 120L, 300L,
                5000L, 4990L, MAX_ROUTE_DELTA));
        assertTrue(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                1000L, 180L, 800L,
                5000L, 4990L, MAX_ROUTE_DELTA));
        assertFalse(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                100L, 100L, 180L,
                5000L, 4990L, MAX_ROUTE_DELTA));
        assertFalse(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                1000L, 700L, 1100L,
                5000L, 4990L, MAX_ROUTE_DELTA));
    }

    @Test
    public void rejectsNoiseUnknownRouteGrowthAndDifferentIdentity() {
        assertFalse(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                300L, 300L, 310L,
                5000L, 4995L, MAX_ROUTE_DELTA));
        assertFalse(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                300L, 40L, 300L,
                -1L, 4970L, MAX_ROUTE_DELTA));
        assertFalse(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                300L, 40L, 300L,
                5000L, 5001L, MAX_ROUTE_DELTA));
        assertFalse(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|right",
                300L, 40L, 300L,
                5000L, 4600L, MAX_ROUTE_DELTA));
        assertFalse(YandexMainDistanceContinuityPolicy.isSameIdentityPostPassJump(
                "route|right", "route|left",
                300L, 40L, 300L,
                5000L, 4970L, MAX_ROUTE_DELTA));
    }

    @Test
    public void confirmsOnlyASecondConsistentPostPassSnapshot() {
        assertTrue(YandexMainDistanceContinuityPolicy.confirmsPendingSameIdentityPostPass(
                "route|right", "route|right",
                40L, 300L, 290L,
                4970L, 4960L, MAX_ROUTE_DELTA));
        assertTrue(YandexMainDistanceContinuityPolicy.confirmsPendingSameIdentityPostPass(
                "route|right", "route|right",
                50L, 100L, 95L,
                4970L, 4965L, MAX_ROUTE_DELTA));
        assertTrue(YandexMainDistanceContinuityPolicy.confirmsPendingSameIdentityPostPass(
                "route|right", "route|right",
                180L, 800L, 780L,
                4990L, 4970L, MAX_ROUTE_DELTA));

        assertFalse(YandexMainDistanceContinuityPolicy.confirmsPendingSameIdentityPostPass(
                "route|right", "route|right",
                100L, 180L, 105L,
                4970L, 4965L, MAX_ROUTE_DELTA));
        assertFalse(YandexMainDistanceContinuityPolicy.confirmsPendingSameIdentityPostPass(
                "route|right", "route|right",
                180L, 800L, 185L,
                4990L, 4970L, MAX_ROUTE_DELTA));
        assertFalse(YandexMainDistanceContinuityPolicy.confirmsPendingSameIdentityPostPass(
                "route|right", "route|left",
                40L, 300L, 290L,
                4970L, 4960L, MAX_ROUTE_DELTA));
        assertFalse(YandexMainDistanceContinuityPolicy.confirmsPendingSameIdentityPostPass(
                "route|right", "route|right",
                40L, 300L, 290L,
                4970L, 4980L, MAX_ROUTE_DELTA));
    }

    @Test
    public void positiveGenericBeatsTransientAnnotationZeroOnlyForSameMain() {
        assertEquals("990 м",
                YandexMainDistanceContinuityPolicy.selectProvenanceDistance(
                        true, "0 м", "990 м"));
        assertEquals("0 м",
                YandexMainDistanceContinuityPolicy.selectProvenanceDistance(
                        false, "0 м", "990 м"));
    }

    @Test
    public void positiveProvenanceDistanceRemainsAuthoritative() {
        assertEquals("1 км",
                YandexMainDistanceContinuityPolicy.selectProvenanceDistance(
                        true, "1 км", "990 м"));
    }

    @Test
    public void notificationWithoutPrefixedDistanceKeepsLegacyGenericFallback() {
        assertEquals("850 м",
                YandexMainDistanceContinuityPolicy.selectDistanceForProvenance(
                        "notification", false,
                        "100 м", "", "850 м"));
        assertEquals("",
                YandexMainDistanceContinuityPolicy.selectDistanceForProvenance(
                        "notification", false,
                        "100 м", "", ""));
    }

    @Test
    public void unsafeZeroDoesNotPoisonPreviousPositiveGuard() {
        assertTrue(YandexMainDistanceContinuityPolicy.preservePreviousAfterRejectedZero(
                "route|right", "route|right",
                1000L, 0L, 5000L, 5001L, true,
                MAX_ROUTE_DELTA, 25L, ZERO_METERS));

        assertEquals(990L, select("route|right", "route|right",
                1000L, 0L, 5000L, 4990L, true));
    }

    @Test
    public void realPassOrLargeRouteJumpMayClearPreviousGuard() {
        assertFalse(YandexMainDistanceContinuityPolicy.preservePreviousAfterRejectedZero(
                "route|right", "route|right",
                10L, 0L, 5000L, 4990L, true,
                MAX_ROUTE_DELTA, 25L, ZERO_METERS));
        assertFalse(YandexMainDistanceContinuityPolicy.preservePreviousAfterRejectedZero(
                "route|right", "route|right",
                1000L, 0L, 5000L, 4600L, true,
                MAX_ROUTE_DELTA, 25L, ZERO_METERS));
    }

    @Test
    public void firstKnownRouteAnchorsHeldPositiveThenNextZeroProjects() {
        YandexMainDistanceContinuityPolicy.ZeroContinuity anchor =
                YandexMainDistanceContinuityPolicy.resolveTransientZero(
                        "route|right", "route|right",
                        1000L, 0L, -1L, 5000L, true,
                        MAX_ROUTE_DELTA, 25L, ZERO_METERS);

        assertTrue(anchor.handled);
        assertEquals(0L, anchor.outputMeters);
        assertEquals(1000L, anchor.stateMeters);
        assertEquals(5000L, anchor.stateRouteRemaining);
        assertEquals("route_anchored", anchor.mode);

        YandexMainDistanceContinuityPolicy.ZeroContinuity next =
                YandexMainDistanceContinuityPolicy.resolveTransientZero(
                        "route|right", "route|right",
                        anchor.stateMeters, 0L,
                        anchor.stateRouteRemaining, 4990L, true,
                        MAX_ROUTE_DELTA, 25L, ZERO_METERS);

        assertTrue(next.handled);
        assertEquals(990L, next.outputMeters);
        assertEquals(990L, next.stateMeters);
        assertEquals(4990L, next.stateRouteRemaining);
        assertEquals("projected", next.mode);
    }

    @Test
    public void smallRouteJitterDoesNotPoisonNextProjection() {
        YandexMainDistanceContinuityPolicy.ZeroContinuity jitter =
                YandexMainDistanceContinuityPolicy.resolveTransientZero(
                        "route|right", "route|right",
                        1000L, 0L, 5000L, 5001L, true,
                        MAX_ROUTE_DELTA, 25L, ZERO_METERS);

        assertTrue(jitter.handled);
        assertEquals(1000L, jitter.stateMeters);
        assertEquals(5000L, jitter.stateRouteRemaining);
        assertEquals("route_jitter_preserved", jitter.mode);

        YandexMainDistanceContinuityPolicy.ZeroContinuity next =
                YandexMainDistanceContinuityPolicy.resolveTransientZero(
                        "route|right", "route|right",
                        jitter.stateMeters, 0L,
                        jitter.stateRouteRemaining, 4990L, true,
                        MAX_ROUTE_DELTA, 25L, ZERO_METERS);
        assertEquals(990L, next.outputMeters);
        assertEquals(990L, next.stateMeters);
    }

    @Test
    public void synchronizedAliasesCoverGenericAndSelectedProvenance() {
        assertArrayEquals(new String[]{
                        "maneuver_distance_meters",
                        "current_maneuver_distance_meters",
                        "distance_to_maneuver_meters",
                        "annotation_maneuver_distance_meters",
                        "annotation_distance_to_maneuver_meters",
                        "guide_maneuver_distance_meters",
                        "guide_distance_to_maneuver_meters",
                        "displayed_annotation_maneuver_distance_meters",
                        "displayed_annotation_distance_to_maneuver_meters",
                        "current_annotation_maneuver_distance_meters",
                        "current_annotation_distance_to_maneuver_meters"
                },
                YandexMainDistanceContinuityPolicy.synchronizedDistanceMeterKeys(
                        "annotation"));
        assertArrayEquals(new String[]{
                        "maneuver_distance_meters",
                        "current_maneuver_distance_meters",
                        "distance_to_maneuver_meters",
                        "notification_maneuver_distance_meters",
                        "notification_distance_to_maneuver_meters"
                },
                YandexMainDistanceContinuityPolicy.synchronizedDistanceMeterKeys(
                        "notification"));
        assertArrayEquals(new String[]{
                        "maneuver_distance_meters",
                        "current_maneuver_distance_meters",
                        "distance_to_maneuver_meters"
                },
                YandexMainDistanceContinuityPolicy.synchronizedDistanceMeterKeys(
                        "bridge_main"));
    }

    private static long select(String previousIdentity,
                               String currentIdentity,
                               long previousMeters,
                               long incomingMeters,
                               long previousRouteRemaining,
                               long currentRouteRemaining,
                               boolean semanticMicro) {
        return YandexMainDistanceContinuityPolicy.select(
                previousIdentity, currentIdentity,
                previousMeters, incomingMeters,
                previousRouteRemaining, currentRouteRemaining,
                semanticMicro, MAX_ROUTE_DELTA, ZERO_METERS);
    }
}
