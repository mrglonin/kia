package kia.app.navigation.domain;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class YandexSpeedSnapshotPolicyTest {
    @Test
    public void exceededFlagAloneDoesNotCreateAFreeDriveSpeedUpdate() {
        assertFalse(YandexSpeedSnapshotPolicy.meaningfulPayload(-1, false, ""));
    }

    @Test
    public void currentRoadAndCameraFieldsAreMeaningfulIndependently() {
        assertTrue(YandexSpeedSnapshotPolicy.meaningfulPayload(0, false, ""));
        assertTrue(YandexSpeedSnapshotPolicy.meaningfulPayload(-1, true, ""));
        assertTrue(YandexSpeedSnapshotPolicy.meaningfulPayload(-1, false, "80"));
    }

    @Test
    public void onlyAnExactlyMatchingYandexSnapshotCanUseCheapFreshnessTouch() {
        assertTrue(YandexSpeedSnapshotPolicy.matchesStoredState(
                true, 0, 0, 60, true, 60, false, true, false));
        assertFalse(YandexSpeedSnapshotPolicy.matchesStoredState(
                true, 0, 1, 60, true, 60, false, true, false));
        assertFalse(YandexSpeedSnapshotPolicy.matchesStoredState(
                true, 0, 0, 60, true, 0, false, true, false));
        assertFalse(YandexSpeedSnapshotPolicy.matchesStoredState(
                true, 0, 0, 60, true, 60, false, true, true));
        assertFalse(YandexSpeedSnapshotPolicy.matchesStoredState(
                false, 0, 0, 60, true, 60, false, true, false));
    }
}
