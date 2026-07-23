package kia.app.navigation.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YandexSnapshotCoalescingPolicyTest {
    @Test
    public void absenceBetweenTwoSameManeuversCannotBeCoalescedAway() {
        String right = "micro|turn_right";
        String absent = YandexSnapshotSemantics.MICRO_ABSENT_IDENTITY;

        assertTrue(YandexSnapshotCoalescingPolicy.isTransition(right, absent));
        assertTrue(YandexSnapshotCoalescingPolicy.isTransition(absent, right));
        assertFalse(YandexSnapshotCoalescingPolicy.canReplaceTail(
                false, absent, right));
    }

    @Test
    public void distanceOnlyUpdateOfSameSemanticStateCanReplaceTail() {
        String right = "micro|turn_right|right";

        assertFalse(YandexSnapshotCoalescingPolicy.isTransition(right, right));
        assertTrue(YandexSnapshotCoalescingPolicy.canReplaceTail(
                false, right, right));
        assertFalse(YandexSnapshotCoalescingPolicy.canReplaceTail(
                true, right, right));
    }

    @Test
    public void lifecyclePacketDisplacesOrdinaryPacketInFullQueue() {
        assertTrue(YandexSnapshotCoalescingPolicy.evictionIndex(
                true, true, false, false, true) >= 0);
        assertTrue(YandexSnapshotCoalescingPolicy.evictionIndex(
                true, true, true, true, true) >= 0);
    }

    @Test
    public void transitionNeverDisplacesQueuedLifecycleHistory() {
        assertTrue(YandexSnapshotCoalescingPolicy.evictionIndex(
                false, true, true, false, true) >= 0);
        assertEquals(-1, YandexSnapshotCoalescingPolicy.evictionIndex(
                false, true, true, true, true));
        assertEquals(-1, YandexSnapshotCoalescingPolicy.evictionIndex(
                false, false, false, false, false));
    }
}
