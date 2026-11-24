package kia.app.navigation.domain

import java.util.Objects

/** Immutable semantic key for a cluster maneuver frame. */
internal class NavigationTxKey(
    maneuver: String?,
    grayRoad: String?,
    distance: Float,
    km: Boolean,
    progress: Int,
) {
    @JvmField val maneuver: String?
    @JvmField val grayRoad: String?
    @JvmField val distance: Float
    @JvmField val km: Boolean
    @JvmField val progress: Int

    init {
        this.maneuver = NavigationTxKey.clean(maneuver)
        this.grayRoad = NavigationTxKey.clean(grayRoad)
        this.distance = distance
        this.km = km
        this.progress = progress
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NavigationTxKey) return false
        val that: NavigationTxKey = other as NavigationTxKey
        return java.lang.Float.compare(distance, that.distance) == 0 &&
            km == that.km &&
            progress == that.progress &&
            maneuver.equals(that.maneuver) &&
            grayRoad.equals(that.grayRoad)
    }

    override fun hashCode(): Int {
        return Objects.hash(maneuver, grayRoad, distance, km, progress)
    }

    companion object {
        private fun clean(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
