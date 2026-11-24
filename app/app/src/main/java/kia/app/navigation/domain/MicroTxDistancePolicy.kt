package kia.app.navigation.domain

/**
 * Selects the distance sent with a micro-maneuver visual.
 *
 * The micro maneuver owns only the icon/topology. Its own distance is intentionally not accepted by
 * this policy: the cluster counter must keep showing the distance to the main maneuver. A fresh
 * main distance wins over a previously held main distance, and only strictly positive values are
 * usable.
 */
internal object MicroTxDistancePolicy {
    @JvmStatic
    fun selectMainDistance(preferredMainDistance: String?, heldMainDistance: String?): String {
        val preferred = MicroTxDistancePolicy.clean(preferredMainDistance)
        if (MicroTxDistancePolicy.isPositive(preferred)) {
            return preferred
        }

        val held = MicroTxDistancePolicy.clean(heldMainDistance)
        return if (MicroTxDistancePolicy.isPositive(held)) held else ""
    }

    @JvmStatic
    /**
     * Chooses maneuver identity and distance atomically so a fresh identity can never be paired
     * with the previous maneuver's held counter.
     */
    fun selectMainSnapshot(
        preferredMainManeuver: String?,
        preferredMainDistance: String?,
        heldMainManeuver: String?,
        heldMainDistance: String?,
    ): MainSnapshot {
        val preferredManeuver = MicroTxDistancePolicy.clean(preferredMainManeuver)
        val preferredDistance = MicroTxDistancePolicy.clean(preferredMainDistance)
        if (!preferredManeuver.isEmpty() && MicroTxDistancePolicy.isPositive(preferredDistance)) {
            return MainSnapshot(preferredManeuver, preferredDistance, true)
        }

        val heldManeuver = MicroTxDistancePolicy.clean(heldMainManeuver)
        val heldDistance = MicroTxDistancePolicy.clean(heldMainDistance)
        if (!heldManeuver.isEmpty() && MicroTxDistancePolicy.isPositive(heldDistance)) {
            return MainSnapshot(heldManeuver, heldDistance, false)
        }
        return MainSnapshot("", "", false)
    }

    private fun isPositive(distance: String?): Boolean {
        return ManeuverArbiter.distanceMeters(distance) > 0f
    }

    private fun clean(value: String?): String {
        return if (value == null) "" else value.trim()
    }

    internal class MainSnapshot(maneuver: String?, distance: String?, preferred: Boolean) {
        @JvmField val maneuver: String?
        @JvmField val distance: String?
        @JvmField val preferred: Boolean

        init {
            this.maneuver = MicroTxDistancePolicy.clean(maneuver)
            this.distance = MicroTxDistancePolicy.clean(distance)
            this.preferred = preferred
        }

        fun available(): Boolean {
            return !maneuver!!.isEmpty() && !distance!!.isEmpty()
        }
    }
}
