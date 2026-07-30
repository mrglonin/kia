package kia.app.core.model;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TpmsStateFreshnessTest {
    @Test
    public void eachWheelKeepsItsOwnObservationTime() {
        TpmsState state = TpmsState.empty()
                .withWheelAt(TpmsState.WHEEL_FL, 245, 24, 0, "test", 10_000L)
                .withWheelAt(TpmsState.WHEEL_FR, 250, 25, 0, "test", 20_000L);

        assertArrayEquals(new long[]{10_000L, 20_000L, 0L, 0L}, state.wheelUpdatedAt);
        assertTrue(state.isWheelFresh(TpmsState.WHEEL_FL, 25_000L, 20_000L));
        assertTrue(state.isWheelFresh(TpmsState.WHEEL_FR, 25_000L, 20_000L));
        assertFalse(state.isWheelFresh(TpmsState.WHEEL_RL, 25_000L, 20_000L));
        assertEquals(15_000L, state.wheelAgeMs(TpmsState.WHEEL_FL, 25_000L));
    }

    @Test
    public void staleBoundaryIsDeterministic() {
        TpmsState state = TpmsState.empty()
                .withWheelAt(TpmsState.WHEEL_FL, 245, 24, 0, "test", 1_000L);

        assertTrue(state.isWheelFresh(TpmsState.WHEEL_FL, 11_000L, 10_000L));
        assertFalse(state.isWheelFresh(TpmsState.WHEEL_FL, 11_001L, 10_000L));
    }

    @Test
    public void explicitMissingSlotKeepsLastValueButMakesItStale() {
        TpmsState state = TpmsState.empty()
                .withWheelAt(TpmsState.WHEEL_RR, 257, 31, 0x10, "test", 10_000L);

        TpmsState stale = state.withWheelStale(
                TpmsState.WHEEL_RR, "adapter 0x51", 20_000L);

        assertTrue(stale.known[TpmsState.WHEEL_RR]);
        assertEquals(257, stale.pressureKpa[TpmsState.WHEEL_RR]);
        assertEquals(31, stale.temperatureC[TpmsState.WHEEL_RR]);
        assertEquals(10_000L, stale.wheelUpdatedAt[TpmsState.WHEEL_RR]);
        assertTrue(stale.explicitlyStale[TpmsState.WHEEL_RR]);
        assertEquals(10_000L, stale.wheelAgeMs(TpmsState.WHEEL_RR, 20_000L));
        assertTrue(stale.isWheelStale(TpmsState.WHEEL_RR, 20_000L));
        assertFalse(stale.hasFreshData());
    }

    @Test
    public void legacyConstructorSeedsKnownWheelTimestampsForCompatibility() {
        TpmsState state = new TpmsState(
                new boolean[]{true, false, true, false},
                new int[]{240, 0, 250, 0},
                new int[]{20, 0, 22, 0},
                new int[4],
                "legacy",
                55_000L);

        assertArrayEquals(new long[]{55_000L, 0L, 55_000L, 0L}, state.wheelUpdatedAt);
        assertTrue(state.isWheelFresh(TpmsState.WHEEL_FL, 56_000L));
        assertTrue(state.isWheelFresh(TpmsState.WHEEL_RL, 56_000L));
    }
}
