package kia.app.tpms;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TeyesHomeVisibilityPolicyTest {
    @Test public void visibleHomeWithInactiveCameraCanShowCard() {
        assertTrue(TeyesHomeVisibilityPolicy.shouldShow("true", "0", true, false));
    }
    @Test public void cameraOverridesHomeEvenIfHomeActivityRemainsStarted() {
        assertFalse(TeyesHomeVisibilityPolicy.shouldShow("true", "1", true, false));
    }
    @Test public void missingOrUnrecognizedVendorStateHidesCard() {
        for (String unknown : new String[]{null, "", "unknown", "2"}) {
            assertFalse(TeyesHomeVisibilityPolicy.shouldShow("true", unknown, true, false));
            assertFalse(TeyesHomeVisibilityPolicy.shouldShow(unknown, "0", true, false));
        }
        assertFalse(TeyesHomeVisibilityPolicy.shouldShow("false", "0", true, false));
    }
    @Test public void sleepAndLockHideCardWithoutWaitingForVendorPropertyChange() {
        assertFalse(TeyesHomeVisibilityPolicy.shouldShow("true", "0", false, false));
        assertFalse(TeyesHomeVisibilityPolicy.shouldShow("true", "0", true, true));
    }
}
