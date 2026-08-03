package kia.app.transport.usb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class UsbReconnectBackoffPolicyTest {
    @Test
    public void immediateTrafficCannotBypassFailureBackoff() {
        long notBefore = UsbReconnectBackoffPolicy.extendNotBefore(0L, 1000L, 1000L);

        assertEquals(2000L, notBefore);
        assertEquals(1000L,
                UsbReconnectBackoffPolicy.effectiveDelay(0L, 1000L, notBefore));
    }

    @Test
    public void repeatedFailureExtendsRatherThanRecurses() {
        long first = UsbReconnectBackoffPolicy.extendNotBefore(0L, 1000L, 1000L);
        long second = UsbReconnectBackoffPolicy.extendNotBefore(first, 1500L, 1000L);

        assertEquals(2500L, second);
        assertEquals(1000L,
                UsbReconnectBackoffPolicy.effectiveDelay(0L, 1500L, second));
    }

    @Test
    public void expiredBackoffKeepsRequestedDelay() {
        assertEquals(750L,
                UsbReconnectBackoffPolicy.effectiveDelay(750L, 3000L, 2000L));
    }
}
