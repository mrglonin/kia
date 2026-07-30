package kia.app.tpms;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TpmsPollingModeTest {
    @Test
    public void explicitModesHaveStableCadence() {
        assertEquals(5_000L, TpmsPollingMode.FULL_DASHBOARD.intervalMs());
        assertEquals(30_000L, TpmsPollingMode.EMBEDDED_WIDGET.intervalMs());
        assertEquals(120_000L, TpmsPollingMode.BACKGROUND.intervalMs());
    }
}
