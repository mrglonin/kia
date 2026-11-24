package kia.app.navigation.domain

/** Pure rules for the short post-entry roundabout hold. */
internal object RoundaboutHoldPolicy {
    @JvmStatic
    fun canStart(distanceText: String?, activationMeters: Float): Boolean {
        val meters: Float = ManeuverArbiter.distanceMeters(distanceText)
        return meters > 0f && meters <= activationMeters
    }

    @JvmStatic
    fun shouldStart(
        currentKey: String?,
        currentDeadline: Long,
        incomingKey: String?,
        distanceText: String?,
        now: Long,
        activationMeters: Float,
    ): Boolean {
        if (!RoundaboutHoldPolicy.canStart(distanceText, activationMeters)) return false
        val current = RoundaboutHoldPolicy.clean(currentKey)
        val incoming = RoundaboutHoldPolicy.clean(incomingKey)
        if (incoming.isEmpty()) return false
        return !incoming.equals(current)
    }

    private fun clean(value: String?): String {
        return if (value == null) "" else value.trim()
    }
}
