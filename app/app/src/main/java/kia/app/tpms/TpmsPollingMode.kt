package kia.app.tpms

/**
 * Explicit TPMS polling intent. Screen geometry must not decide the polling cadence: a full
 * dashboard remains FULL_DASHBOARD even in a wide window, while an embedded card explicitly selects
 * EMBEDDED_WIDGET.
 */
enum class TpmsPollingMode(intervalMs: Long) {
    FULL_DASHBOARD(5000L),
    EMBEDDED_WIDGET(30000L),
    BACKGROUND(120000L);

    private val intervalMs: Long

    init {
        this.intervalMs = intervalMs
    }

    fun intervalMs(): Long {
        return intervalMs
    }
}
