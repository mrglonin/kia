package kia.app.navigation.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigationSourceArbitratorTest {
    private static final long FRESH_MS = 15_000L;

    @Test
    public void autoRouteOwnerDoesNotFlapAndFreshHeartbeatsExtendOwnership() {
        NavigationSourceArbitrator arbiter = new NavigationSourceArbitrator(FRESH_MS);

        NavigationSourceArbitrator.Result yandex = arbiter.accept(
                true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 1_000L);
        assertTrue(yandex.accepted);
        assertTrue(yandex.claimedOwner());
        assertEquals(NavigationSourcePolicy.SOURCE_YANDEX, arbiter.ownerSource());

        assertFalse(arbiter.accept(
                true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_DGIS,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 10_000L).accepted);

        assertTrue(arbiter.accept(
                true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 12_000L).accepted);
        assertFalse(arbiter.accept(
                true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_DGIS,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 26_000L).accepted);
    }

    @Test
    public void staleOwnerHandsOffAtomicallyToOtherActiveRoute() {
        NavigationSourceArbitrator arbiter = new NavigationSourceArbitrator(FRESH_MS);
        arbiter.accept(true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 1_000L);

        NavigationSourceArbitrator.Result handoff = arbiter.accept(
                true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_DGIS,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 16_001L);

        assertTrue(handoff.accepted);
        assertTrue(handoff.switchedOwner);
        assertEquals(NavigationSourcePolicy.SOURCE_YANDEX, handoff.previousOwner);
        assertEquals(NavigationSourcePolicy.SOURCE_DGIS, handoff.owner);
    }

    @Test
    public void staleNonOwnerPassiveOrEndPacketCannotClearRoute() {
        NavigationSourceArbitrator arbiter = new NavigationSourceArbitrator(FRESH_MS);
        arbiter.accept(true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_DGIS,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 1_000L);

        assertFalse(arbiter.accept(
                true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_PASSIVE, 20_000L).accepted);
        assertFalse(arbiter.accept(
                true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_ROUTE_END, 20_000L).accepted);
        assertEquals(NavigationSourcePolicy.SOURCE_DGIS, arbiter.ownerSource());
    }

    @Test
    public void ownerReleaseAllowsImmediateFallback() {
        NavigationSourceArbitrator arbiter = new NavigationSourceArbitrator(FRESH_MS);
        arbiter.seed(NavigationSourcePolicy.SOURCE_DGIS, 5_000L);
        arbiter.release(NavigationSourcePolicy.SOURCE_DGIS);

        NavigationSourceArbitrator.Result fallback = arbiter.accept(
                true, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 5_001L);

        assertTrue(fallback.accepted);
        assertEquals(NavigationSourcePolicy.SOURCE_YANDEX, fallback.owner);
    }

    @Test
    public void sourceModeAndGlobalDisableAreAppliedToExistingOwner() {
        NavigationSourceArbitrator arbiter = new NavigationSourceArbitrator(FRESH_MS);
        arbiter.seed(NavigationSourcePolicy.SOURCE_YANDEX, 1_000L);

        assertFalse(arbiter.accept(
                true, NavigationSourcePolicy.MODE_DGIS,
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 2_000L).accepted);
        assertFalse(arbiter.accept(
                false, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_YANDEX,
                NavigationSourcePolicy.EVENT_ROUTE_ACTIVE, 2_000L).accepted);
    }
}
