package kia.app.navigation.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TeyesIngressPolicyTest {
    @Test
    public void usableFallbackForActiveYandexRouteClaimsOwner() {
        assertEquals(NavigationSourcePolicy.EVENT_ROUTE_ACTIVE,
                TeyesIngressPolicy.event(
                        false, false, true, true, true, true));
    }

    @Test
    public void passiveAndIgnoredClosePacketsNeverClaimOwner() {
        assertEquals(NavigationSourcePolicy.EVENT_PASSIVE,
                TeyesIngressPolicy.event(
                        false, true, true, true, true, true));
        assertEquals(NavigationSourcePolicy.EVENT_PASSIVE,
                TeyesIngressPolicy.event(
                        true, false, true, true, true, true));
    }

    @Test
    public void fallbackCannotTakeOverDgisOrInventRoute() {
        assertEquals(NavigationSourcePolicy.EVENT_PASSIVE,
                TeyesIngressPolicy.event(
                        false, false, true, false, true, true));
        assertEquals(NavigationSourcePolicy.EVENT_PASSIVE,
                TeyesIngressPolicy.event(
                        false, false, false, true, true, true));
        assertEquals(NavigationSourcePolicy.EVENT_PASSIVE,
                TeyesIngressPolicy.event(
                        false, false, true, true, false, true));
    }
}
