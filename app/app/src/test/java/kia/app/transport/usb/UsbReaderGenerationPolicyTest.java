package kia.app.transport.usb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UsbReaderGenerationPolicyTest {
    @Test
    public void currentReaderRequiresRunningSamePortAndGeneration() {
        assertTrue(UsbReaderGenerationPolicy.current(true, true, 7L, 7L));
        assertFalse(UsbReaderGenerationPolicy.current(false, true, 7L, 7L));
        assertFalse(UsbReaderGenerationPolicy.current(true, false, 7L, 7L));
    }

    @Test
    public void staleReaderCannotActOnNewConnection() {
        assertFalse(UsbReaderGenerationPolicy.current(true, true, 8L, 7L));
        assertFalse(UsbReaderGenerationPolicy.current(true, false, 8L, 7L));
    }
}
