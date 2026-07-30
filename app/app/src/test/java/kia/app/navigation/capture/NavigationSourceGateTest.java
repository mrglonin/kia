package kia.app.navigation.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigationSourceGateTest {
    @Test
    public void yandexRunsOnlyWhenNavigationAndSourceAreEnabled() {
        assertTrue(NavigationSourceGate.yandexEnabled(true, true));
        assertFalse(NavigationSourceGate.yandexEnabled(false, true));
        assertFalse(NavigationSourceGate.yandexEnabled(true, false));
    }

    @Test
    public void dgisRunsOnlyWhenNavigationAndSourceAreEnabled() {
        assertTrue(NavigationSourceGate.dgisEnabled(true, true));
        assertFalse(NavigationSourceGate.dgisEnabled(false, true));
        assertFalse(NavigationSourceGate.dgisEnabled(true, false));
    }
}
