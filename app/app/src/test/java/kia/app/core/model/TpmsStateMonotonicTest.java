package kia.app.core.model;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class TpmsStateMonotonicTest {
    private final MutableClock clock = new MutableClock();

    private static final class MutableClock implements TpmsState.Clock {
        long wall = 1_000_000L;
        long elapsed = 10_000L;
        public long wallMillis() { return wall; }
        public long elapsedMillis() { return elapsed; }
    }

    @Before public void setClock() { TpmsState.setClockForTests(clock); }
    @After public void restoreClock() { TpmsState.setClockForTests(null); }

    @Test public void backwardWallJumpCannotReviveExpiredWheel() {
        TpmsState state = TpmsState.empty().withWheel(0, 240, 20, 0, "test");
        clock.elapsed += 180_001L;
        clock.wall -= 600_000L;
        assertEquals(180_001L, state.wheelAgeMs(0));
        assertFalse(state.isWheelFresh(0));
        assertFalse(state.hasFreshData());
    }

    @Test public void forwardWallJumpDoesNotExpireFreshWheel() {
        TpmsState state = TpmsState.empty().withWheel(0, 240, 20, 0, "test");
        clock.elapsed += 1_000L;
        clock.wall += 86_400_000L;
        assertEquals(1_000L, state.wheelAgeMs(0));
        assertTrue(state.isWheelFresh(0));
    }

    @Test public void otherWheelUpdateAfterClockCorrectionPreservesOriginalAge() {
        TpmsState state = TpmsState.empty().withWheel(0, 240, 20, 0, "test");
        clock.elapsed += 180_001L;
        clock.wall -= 600_000L;
        state = state.withWheel(1, 250, 25, 0, "test");
        assertFalse(state.isWheelFresh(0));
        assertEquals(180_001L, state.wheelAgeMs(0));
        assertTrue(state.isWheelFresh(1));
        assertEquals(0L, state.wheelAgeMs(1));
        assertTrue(state.hasFreshData());
    }

    @Test public void sleepElapsedTimeExpiresAtExactBoundary() {
        TpmsState state = TpmsState.empty().withWheel(0, 240, 20, 0, "test");
        clock.elapsed += TpmsState.WHEEL_STALE_AFTER_MS;
        assertTrue(state.isWheelFresh(0));
        clock.elapsed++;
        assertFalse(state.isWheelFresh(0));
    }

    @Test public void importedHistoricalTimestampGetsAgeOnce() {
        TpmsState state = new TpmsState(new boolean[]{true}, new int[]{240},
                new int[]{20}, new int[]{0}, "history", clock.wall - 179_000L);
        clock.wall -= 600_000L;
        clock.elapsed += 1_001L;
        assertEquals(180_001L, state.wheelAgeMs(0));
        assertFalse(state.isWheelFresh(0));
    }

    @Test public void futureOrUnknownHistoricalTimestampIsNotLive() {
        TpmsState future = TpmsState.empty().withWheelAt(0, 240, 20, 0, "test", clock.wall + 1L);
        assertFalse(future.isWheelFresh(0));
        assertEquals(Long.MAX_VALUE, future.wheelAgeMs(0));
        assertEquals(Long.MAX_VALUE, future.wheelAgeMs(1));
    }

    @Test public void explicitEmptySlotPreservesMonotonicAge() {
        TpmsState state = TpmsState.empty().withWheel(0, 240, 20, 0, "test");
        clock.elapsed += 25_000L;
        clock.wall -= 500_000L;
        state = state.withWheelStale(0, "missing", clock.wall);
        assertEquals(25_000L, state.wheelAgeMs(0));
        assertFalse(state.isWheelFresh(0));
        state = state.withWheel(0, 245, 22, 0, "recovered");
        assertTrue(state.isWheelFresh(0));
        assertEquals(0L, state.wheelAgeMs(0));
    }

    @Test public void impossibleElapsedRegressionIsConservative() {
        TpmsState state = TpmsState.empty().withWheel(0, 240, 20, 0, "test");
        clock.elapsed--;
        assertFalse(state.isWheelFresh(0));
        assertEquals(Long.MAX_VALUE, state.wheelAgeMs(0));
    }
}
