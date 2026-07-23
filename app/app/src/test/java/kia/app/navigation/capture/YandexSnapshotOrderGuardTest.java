package kia.app.navigation.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class YandexSnapshotOrderGuardTest {
    @Test
    public void rejectsOlderSequenceBeforeMutation() {
        YandexSnapshotOrderGuard guard = new YandexSnapshotOrderGuard();
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 10L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.REJECT_STALE_SEQUENCE,
                guard.evaluate("route-a", 9L, 1000L));
    }

    @Test
    public void routeChangeResetsOrdering() {
        YandexSnapshotOrderGuard guard = new YandexSnapshotOrderGuard();
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 100L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-b", 1L, 1100L));
    }

    @Test
    public void delayedSnapshotCannotRestoreRetiredRoute() {
        YandexSnapshotOrderGuard guard = new YandexSnapshotOrderGuard();
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 100L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-b", 1L, 1100L));
        assertEquals(YandexSnapshotOrderGuard.Result.REJECT_RETIRED_ROUTE,
                guard.evaluate("route-a", 101L, 1200L));
    }

    @Test
    public void olderEnvelopeCannotStartUnseenRoute() {
        YandexSnapshotOrderGuard guard = new YandexSnapshotOrderGuard();
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 100L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.REJECT_STALE_TIMESTAMP,
                guard.evaluate("route-b", 1L, 900L));
    }

    @Test
    public void rejectedTimestampOnlyRouteDoesNotRetireCurrentRoute() {
        YandexSnapshotOrderGuard guard = new YandexSnapshotOrderGuard();
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 100L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.REJECT_STALE_TIMESTAMP,
                guard.evaluate("route-b", -1L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 101L, 1100L));
    }

    @Test
    public void sequenceResetWithNewerTimestampStartsNewGeneration() {
        YandexSnapshotOrderGuard guard = new YandexSnapshotOrderGuard();
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 100L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 1L, 1100L));
        assertEquals(YandexSnapshotOrderGuard.Result.REJECT_STALE_TIMESTAMP,
                guard.evaluate("route-a", 0L, 1050L));
    }

    @Test
    public void smallSequenceResetWithNewerTimestampStartsNewGeneration() {
        YandexSnapshotOrderGuard guard = new YandexSnapshotOrderGuard();
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 5L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 2L, 1100L));
    }

    @Test
    public void higherSequenceCannotCarryOlderTimestamp() {
        YandexSnapshotOrderGuard guard = new YandexSnapshotOrderGuard();
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 10L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.REJECT_STALE_TIMESTAMP,
                guard.evaluate("route-a", 11L, 900L));
    }

    @Test
    public void timestampLessSnapshotCannotOverrideTrustedStream() {
        YandexSnapshotOrderGuard guard = new YandexSnapshotOrderGuard();
        assertEquals(YandexSnapshotOrderGuard.Result.ACCEPT,
                guard.evaluate("route-a", 1L, 1000L));
        assertEquals(YandexSnapshotOrderGuard.Result.REJECT_UNTRUSTED_AFTER_TRUSTED,
                guard.evaluate("route-a", -1L, -1L));
    }
}
