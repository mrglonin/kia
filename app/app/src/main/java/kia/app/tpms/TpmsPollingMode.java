package kia.app.tpms;

/**
 * Explicit TPMS polling intent. Screen geometry must not decide the polling
 * cadence: a full dashboard remains FULL_DASHBOARD even in a wide window,
 * while an embedded card explicitly selects EMBEDDED_WIDGET.
 */
public enum TpmsPollingMode {
    FULL_DASHBOARD(5_000L),
    EMBEDDED_WIDGET(30_000L),
    BACKGROUND(120_000L);

    private final long intervalMs;

    TpmsPollingMode(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long intervalMs() {
        return intervalMs;
    }
}
