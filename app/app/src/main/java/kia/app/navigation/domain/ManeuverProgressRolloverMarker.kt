package kia.app.navigation.domain

/**
 * One-shot downstream marker for a rollover already confirmed by the capture layer.
 *
 * The marker survives handler branches that do not render a main maneuver yet. It is consumed only
 * by a valid observation of the same route scope and canonical maneuver family.
 */
internal class ManeuverProgressRolloverMarker(ttlMs: Long) {
    private val ttlMs: Long
    private var pending: Boolean = false
    private var routeId: String? = ""
    private var family: String = ""
    private var armedAt: Long = 0

    init {
        this.ttlMs = Math.max(0L, ttlMs)
    }

    fun arm(incomingRouteId: String?, incomingFamily: String?, now: Long) {
        val normalizedFamily: String = ManeuverProgressTracker.normalizedFamily(incomingFamily)
        if (normalizedFamily.isEmpty()) return
        pending = true
        routeId = ManeuverProgressRolloverMarker.clean(incomingRouteId)
        family = normalizedFamily
        armedAt = now
    }

    fun matches(
        incomingRouteId: String?,
        incomingFamily: String?,
        meters: Float,
        now: Long,
    ): Boolean {
        if (!pending) return false
        val age: Long = now - armedAt
        if (age < 0L || age > ttlMs) {
            clear()
            return false
        }
        if (!java.lang.Float.isFinite(meters) || meters <= 1f) return false

        val scope = ManeuverProgressRolloverMarker.clean(incomingRouteId)
        val normalizedFamily: String = ManeuverProgressTracker.normalizedFamily(incomingFamily)
        val scopeMatches: Boolean = routeId!!.isEmpty() || scope.isEmpty() || routeId.equals(scope)
        val familyMatches: Boolean = family.isEmpty() || family.equals(normalizedFamily)
        if (!scopeMatches || !familyMatches) {
            clear()
            return false
        }
        return true
    }

    fun pending(): Boolean {
        return pending
    }

    fun clear() {
        pending = false
        routeId = ""
        family = ""
        armedAt = 0L
    }

    companion object {
        private fun clean(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
