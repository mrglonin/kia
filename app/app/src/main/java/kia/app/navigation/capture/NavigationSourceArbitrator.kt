package kia.app.navigation.capture

/**
 * Deterministic route-owner state machine. Time is supplied by the caller, which keeps the class
 * free of Android dependencies and makes complete ingress sequences unit-testable.
 */
class NavigationSourceArbitrator(ownerFreshMs: Long) {
    private val ownerFreshMs: Long
    private var ownerSource: Int = NavigationSourcePolicy.SOURCE_NONE
    private var ownerObservedAt: Long = NavigationSourceArbitrator.NO_OBSERVATION

    init {
        require(ownerFreshMs > 0L) { "ownerFreshMs must be positive" }
        this.ownerFreshMs = ownerFreshMs
    }

    @kotlin.jvm.Synchronized
    fun accept(
        navigationEnabled: Boolean,
        selectedMode: Int,
        incomingSource: Int,
        event: Int,
        nowMs: Long,
    ): kia.app.navigation.capture.NavigationSourceArbitrator.Result {
        val safeNow: Long = Math.max(0L, nowMs)
        val ownerFresh: Boolean =
            ownerSource != NavigationSourcePolicy.SOURCE_NONE &&
                ownerObservedAt != NavigationSourceArbitrator.NO_OBSERVATION &&
                (safeNow < ownerObservedAt || safeNow - ownerObservedAt <= ownerFreshMs)
        val previousOwner: Int = ownerSource
        val decision: Int =
            NavigationSourcePolicy.decide(
                navigationEnabled,
                selectedMode,
                ownerSource,
                ownerFresh,
                incomingSource,
                event,
            )
        if (decision == NavigationSourcePolicy.DECISION_ALLOW_CLAIM) {
            ownerSource = incomingSource
            ownerObservedAt = safeNow
        } else if (decision == NavigationSourcePolicy.DECISION_ALLOW_RELEASE) {
            ownerSource = NavigationSourcePolicy.SOURCE_NONE
            ownerObservedAt = NavigationSourceArbitrator.NO_OBSERVATION
        }
        val accepted: Boolean =
            decision == NavigationSourcePolicy.DECISION_ALLOW ||
                decision == NavigationSourcePolicy.DECISION_ALLOW_CLAIM ||
                decision == NavigationSourcePolicy.DECISION_ALLOW_RELEASE
        return kia.app.navigation.capture.NavigationSourceArbitrator.Result(
            accepted,
            decision,
            previousOwner,
            ownerSource,
            accepted &&
                previousOwner != NavigationSourcePolicy.SOURCE_NONE &&
                ownerSource != NavigationSourcePolicy.SOURCE_NONE &&
                previousOwner != ownerSource,
        )
    }

    @kotlin.jvm.Synchronized
    fun seed(source: Int, nowMs: Long) {
        if (!NavigationSourcePolicy.validSource(source)) {
            reset()
            return
        }
        ownerSource = source
        ownerObservedAt = Math.max(0L, nowMs)
    }

    @kotlin.jvm.Synchronized
    fun release(source: Int) {
        if (source == NavigationSourcePolicy.SOURCE_NONE || ownerSource != source) return
        reset()
    }

    @kotlin.jvm.Synchronized
    fun reset() {
        ownerSource = NavigationSourcePolicy.SOURCE_NONE
        ownerObservedAt = NavigationSourceArbitrator.NO_OBSERVATION
    }

    @kotlin.jvm.Synchronized
    fun ownerSource(): Int {
        return ownerSource
    }

    class Result
    internal constructor(
        accepted: Boolean,
        decision: Int,
        previousOwner: Int,
        owner: Int,
        switchedOwner: Boolean,
    ) {
        @JvmField val accepted: Boolean
        @JvmField val decision: Int
        @JvmField val previousOwner: Int
        @JvmField val owner: Int
        @JvmField val switchedOwner: Boolean

        init {
            this.accepted = accepted
            this.decision = decision
            this.previousOwner = previousOwner
            this.owner = owner
            this.switchedOwner = switchedOwner
        }

        fun claimedOwner(): Boolean {
            return decision == NavigationSourcePolicy.DECISION_ALLOW_CLAIM
        }
    }

    companion object {
        private val NO_OBSERVATION: Long = -1L
    }
}
