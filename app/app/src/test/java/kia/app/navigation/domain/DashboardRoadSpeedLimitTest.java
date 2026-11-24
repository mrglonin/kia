package kia.app.navigation.domain;

import org.junit.Test;
import kia.app.navigation.capture.NavigationSourcePolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class DashboardRoadSpeedLimitTest {
    private static final int YANDEX = NavigationSourcePolicy.SOURCE_YANDEX;

    @Test public void unrelatedReadsNeverExtendTheDeadline() {
        DashboardRoadSpeedLimit limit = DashboardRoadSpeedLimit.NONE.observe(YANDEX, "60", 1000);
        assertEquals(60, limit.valueAt(8000));
        assertEquals(60, limit.valueAt(12999));
        assertEquals(0, limit.valueAt(13000));
        assertEquals(0, limit.valueAt(900));
    }

    @Test public void onlyNewValueSourceOrExpiredSampleStartsTheAnimationAgain() {
        DashboardRoadSpeedLimit first = DashboardRoadSpeedLimit.NONE.observe(YANDEX, 60, 1000);
        DashboardRoadSpeedLimit renewed = first.observe(YANDEX, 60, 5000);
        assertEquals(1000, renewed.changedAt);
        assertEquals(60, renewed.valueAt(16000));
        assertEquals(12000, renewed.observe(YANDEX, 40, 12000).changedAt);
        assertEquals(12000, renewed.observe(NavigationSourcePolicy.SOURCE_DGIS, 60, 12000).changedAt);
        assertEquals(18000, renewed.observe(YANDEX, 60, 18000).changedAt);
    }

    @Test public void aMissingOrInvalidValueClearsAPreviousSign() {
        DashboardRoadSpeedLimit first = DashboardRoadSpeedLimit.NONE.observe(YANDEX, "255", 1000);
        assertEquals(255, first.valueAt(1000));
        for (String invalid : new String[]{null, "", "0", "-15", "258", "camera_60"}) {
            assertSame(DashboardRoadSpeedLimit.NONE, first.observe(YANDEX, invalid, 1100));
        }
    }
}
