package kia.app.navigation.capture;

/**
 * Deterministic route-owner state machine. Time is supplied by the caller, which keeps the class
 * free of Android dependencies and makes complete ingress sequences unit-testable.
 */
public final class NavigationSourceArbitrator {
    private static final long NO_OBSERVATION = -1L;

    private final long ownerFreshMs;
    private int ownerSource = NavigationSourcePolicy.SOURCE_NONE;
    private long ownerObservedAt = NO_OBSERVATION;

    public NavigationSourceArbitrator(long ownerFreshMs) {
        if (ownerFreshMs <= 0L) {
            throw new IllegalArgumentException("ownerFreshMs must be positive");
        }
        this.ownerFreshMs = ownerFreshMs;
    }

    public synchronized Result accept(boolean navigationEnabled, int selectedMode,
                                      int incomingSource, int event, long nowMs) {
        long safeNow = Math.max(0L, nowMs);
        boolean ownerFresh = ownerSource != NavigationSourcePolicy.SOURCE_NONE
                && ownerObservedAt != NO_OBSERVATION
                && (safeNow < ownerObservedAt || safeNow - ownerObservedAt <= ownerFreshMs);
        int previousOwner = ownerSource;
        int decision = NavigationSourcePolicy.decide(
                navigationEnabled, selectedMode, ownerSource, ownerFresh, incomingSource, event);
        if (decision == NavigationSourcePolicy.DECISION_ALLOW_CLAIM) {
            ownerSource = incomingSource;
            ownerObservedAt = safeNow;
        } else if (decision == NavigationSourcePolicy.DECISION_ALLOW_RELEASE) {
            ownerSource = NavigationSourcePolicy.SOURCE_NONE;
            ownerObservedAt = NO_OBSERVATION;
        }
        boolean accepted = decision == NavigationSourcePolicy.DECISION_ALLOW
                || decision == NavigationSourcePolicy.DECISION_ALLOW_CLAIM
                || decision == NavigationSourcePolicy.DECISION_ALLOW_RELEASE;
        return new Result(accepted, decision, previousOwner, ownerSource,
                accepted && previousOwner != NavigationSourcePolicy.SOURCE_NONE
                        && ownerSource != NavigationSourcePolicy.SOURCE_NONE
                        && previousOwner != ownerSource);
    }

    public synchronized void seed(int source, long nowMs) {
        if (source != NavigationSourcePolicy.SOURCE_YANDEX
                && source != NavigationSourcePolicy.SOURCE_DGIS) {
            reset();
            return;
        }
        ownerSource = source;
        ownerObservedAt = Math.max(0L, nowMs);
    }

    public synchronized void release(int source) {
        if (source == NavigationSourcePolicy.SOURCE_NONE || ownerSource != source) return;
        reset();
    }

    public synchronized void reset() {
        ownerSource = NavigationSourcePolicy.SOURCE_NONE;
        ownerObservedAt = NO_OBSERVATION;
    }

    public synchronized int ownerSource() {
        return ownerSource;
    }

    public static final class Result {
        public final boolean accepted;
        public final int decision;
        public final int previousOwner;
        public final int owner;
        public final boolean switchedOwner;

        private Result(boolean accepted, int decision, int previousOwner,
                       int owner, boolean switchedOwner) {
            this.accepted = accepted;
            this.decision = decision;
            this.previousOwner = previousOwner;
            this.owner = owner;
            this.switchedOwner = switchedOwner;
        }

        public boolean claimedOwner() {
            return decision == NavigationSourcePolicy.DECISION_ALLOW_CLAIM;
        }
    }
}
