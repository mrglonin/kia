package kia.app.navigation.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigationSourcePolicyTest {
    @Test
    public void globalDisableRejectsEveryIngress() {
        assertFalse(NavigationSourcePolicy.ingressAllowed(
                false, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_YANDEX));
        assertFalse(NavigationSourcePolicy.ingressAllowed(
                false, NavigationSourcePolicy.MODE_AUTO,
                NavigationSourcePolicy.SOURCE_DGIS));
        assertEquals(NavigationSourcePolicy.DECISION_DENY_DISABLED,
                NavigationSourcePolicy.decide(
                        false, NavigationSourcePolicy.MODE_YANDEX,
                        NavigationSourcePolicy.SOURCE_NONE, false,
                        NavigationSourcePolicy.SOURCE_YANDEX,
                        NavigationSourcePolicy.EVENT_ROUTE_ACTIVE));
    }

    @Test
    public void strictModesHaveExactlyOneWriter() {
        assertTrue(NavigationSourcePolicy.ingressAllowed(
                true, NavigationSourcePolicy.MODE_YANDEX,
                NavigationSourcePolicy.SOURCE_YANDEX));
        assertFalse(NavigationSourcePolicy.ingressAllowed(
                true, NavigationSourcePolicy.MODE_YANDEX,
                NavigationSourcePolicy.SOURCE_DGIS));
        assertTrue(NavigationSourcePolicy.ingressAllowed(
                true, NavigationSourcePolicy.MODE_DGIS,
                NavigationSourcePolicy.SOURCE_DGIS));
        assertFalse(NavigationSourcePolicy.ingressAllowed(
                true, NavigationSourcePolicy.MODE_DGIS,
                NavigationSourcePolicy.SOURCE_YANDEX));
    }

    @Test
    public void freshAutoOwnerRejectsOtherRoute() {
        assertEquals(NavigationSourcePolicy.DECISION_DENY_FRESH_OWNER,
                NavigationSourcePolicy.decide(
                        true, NavigationSourcePolicy.MODE_AUTO,
                        NavigationSourcePolicy.SOURCE_YANDEX, true,
                        NavigationSourcePolicy.SOURCE_DGIS,
                        NavigationSourcePolicy.EVENT_ROUTE_ACTIVE));
    }

    @Test
    public void staleAutoOwnerCanBeReplacedOnlyByActiveRouteEvidence() {
        assertEquals(NavigationSourcePolicy.DECISION_ALLOW_CLAIM,
                NavigationSourcePolicy.decide(
                        true, NavigationSourcePolicy.MODE_AUTO,
                        NavigationSourcePolicy.SOURCE_YANDEX, false,
                        NavigationSourcePolicy.SOURCE_DGIS,
                        NavigationSourcePolicy.EVENT_ROUTE_ACTIVE));
        assertEquals(NavigationSourcePolicy.DECISION_DENY_FRESH_OWNER,
                NavigationSourcePolicy.decide(
                        true, NavigationSourcePolicy.MODE_AUTO,
                        NavigationSourcePolicy.SOURCE_YANDEX, false,
                        NavigationSourcePolicy.SOURCE_DGIS,
                        NavigationSourcePolicy.EVENT_PASSIVE));
        assertEquals(NavigationSourcePolicy.DECISION_DENY_FRESH_OWNER,
                NavigationSourcePolicy.decide(
                        true, NavigationSourcePolicy.MODE_AUTO,
                        NavigationSourcePolicy.SOURCE_YANDEX, false,
                        NavigationSourcePolicy.SOURCE_DGIS,
                        NavigationSourcePolicy.EVENT_ROUTE_END));
    }
}
