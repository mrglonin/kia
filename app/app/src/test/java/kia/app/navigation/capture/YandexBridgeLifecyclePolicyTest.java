package kia.app.navigation.capture;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class YandexBridgeLifecyclePolicyTest {
    @Test
    public void backgroundSuspensionDoesNotRemoveKnownRoute() {
        assertTrue(YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                "off", "background_suspended_SYSTEM", true, true, true, true));
        assertTrue(YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                "off", "task_removed", true, false, false, false));
        assertTrue(YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                "off", "onBackgroundGuidanceWillBeSuspended", true,
                false, false, false));
    }

    @Test
    public void ordinaryOffAndUnknownRouteRemainOff() {
        assertFalse(YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                "off", "route_removed", true, true, true, true));
        assertFalse(YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                "off", "background_suspended_SYSTEM", false, false, false, false));
        assertFalse(YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                "active", "background_suspended_SYSTEM", true, true, true, true));
        assertFalse(YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                "off", "task_removed", true, false, true, false));
        assertFalse(YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                "off", "background_guidance_task_removed route_reset",
                true, true, true, true));
    }
}
