package kia.app.navigation.domain;

import static kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.ACTUAL;
import static kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.HOLD;
import static kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.STRAIGHT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MainManeuverPreviewPolicyTest {
    private static final int THRESHOLD_METERS = 300;

    @Test
    public void disabledPolicyAlwaysKeepsActualMain() {
        assertDecision(ACTUAL, false, true, false, true, false,
                2000f, THRESHOLD_METERS, false);
        assertDecision(ACTUAL, false, true, false, true, false,
                0f, THRESHOLD_METERS, false);
        assertDecision(ACTUAL, false, true, false, true, false,
                2000f, THRESHOLD_METERS, true);
    }

    @Test
    public void nonNormalModeAlwaysKeepsActualMain() {
        assertDecision(ACTUAL, true, false, false, true, false,
                2000f, THRESHOLD_METERS, false);
        assertDecision(ACTUAL, true, false, false, true, false,
                0f, THRESHOLD_METERS, false);
    }

    @Test
    public void activeMicroMakesMainPreviewStepAside() {
        assertDecision(ACTUAL, true, true, true, true, false,
                2000f, THRESHOLD_METERS, false);
        assertDecision(ACTUAL, true, true, true, true, false,
                0f, THRESHOLD_METERS, false);
        assertDecision(ACTUAL, true, true, true, true, false,
                2000f, THRESHOLD_METERS, true);
    }

    @Test
    public void unusableMainIsNeverReplacedWithSyntheticStraight() {
        assertDecision(ACTUAL, true, true, false, false, false,
                2000f, THRESHOLD_METERS, false);
        assertDecision(ACTUAL, true, true, false, false, false,
                0f, THRESHOLD_METERS, false);
    }

    @Test
    public void alreadyStraightMainAlwaysStaysActual() {
        assertDecision(ACTUAL, true, true, false, true, true,
                2000f, THRESHOLD_METERS, false);
        assertDecision(ACTUAL, true, true, false, true, true,
                0f, THRESHOLD_METERS, false);
    }

    @Test
    public void farMainUsesStraightUntilRevealDistance() {
        int[] thresholds = {100, 200, 300, 400, 500};
        for (int threshold : thresholds) {
            assertDecision(STRAIGHT, true, true, false, true, false,
                    threshold + 1f, threshold, false);
            assertDecision(STRAIGHT, true, true, false, true, false,
                    2000f, threshold, false);
        }
    }

    @Test
    public void exactThresholdAndNearerDistanceRevealActualMain() {
        int[] thresholds = {100, 200, 300, 400, 500};
        for (int threshold : thresholds) {
            assertDecision(ACTUAL, true, true, false, true, false,
                    threshold, threshold, false);
            assertDecision(ACTUAL, true, true, false, true, false,
                    threshold - 1f, threshold, false);
        }
    }

    @Test
    public void revealLatchPreventsFarDistanceJitterFromRestoringStraight() {
        assertDecision(ACTUAL, true, true, false, true, false,
                301f, THRESHOLD_METERS, true);
        assertDecision(ACTUAL, true, true, false, true, false,
                2000f, THRESHOLD_METERS, true);
    }

    @Test
    public void unknownOrNonPositiveDistanceHoldsCurrentWireVisual() {
        float[] unknownDistances = {
                0f,
                -1f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY
        };
        for (float distance : unknownDistances) {
            assertDecision(HOLD, true, true, false, true, false,
                    distance, THRESHOLD_METERS, false);
            assertDecision(HOLD, true, true, false, true, false,
                    distance, THRESHOLD_METERS, true);
        }
    }

    @Test
    public void straightPreviewKeepsOnlyGrayRoadsThatContainStraight() {
        assertTrue(MainManeuverPreviewPolicy.grayRoadSupportsStraight(1));
        assertTrue(MainManeuverPreviewPolicy.grayRoadSupportsStraight(3));
        assertTrue(MainManeuverPreviewPolicy.grayRoadSupportsStraight(5));
        assertTrue(MainManeuverPreviewPolicy.grayRoadSupportsStraight(7));
        assertFalse(MainManeuverPreviewPolicy.grayRoadSupportsStraight(0));
        assertFalse(MainManeuverPreviewPolicy.grayRoadSupportsStraight(2));
        assertFalse(MainManeuverPreviewPolicy.grayRoadSupportsStraight(4));
        assertFalse(MainManeuverPreviewPolicy.grayRoadSupportsStraight(6));
    }

    private static void assertDecision(MainManeuverPreviewPolicy.Decision expected,
                                       boolean enabled,
                                       boolean normalMode,
                                       boolean activeMicro,
                                       boolean usableMain,
                                       boolean actualMainStraight,
                                       float distanceMeters,
                                       int revealDistanceMeters,
                                       boolean revealLatched) {
        assertEquals(expected, MainManeuverPreviewPolicy.decide(
                enabled,
                normalMode,
                activeMicro,
                usableMain,
                actualMainStraight,
                distanceMeters,
                revealDistanceMeters,
                revealLatched));
    }
}
