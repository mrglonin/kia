package kia.app.sunroof;

/**
 * Presentation episodes derived only from the roof's own accepted source and a known ACC state.
 * Callers supply a monotonic clock and connection generation. No roof TTL is imposed: some
 * adapters publish the roof only when it changes.
 */
public final class SunroofAlertPolicy {
    public enum Kind { NONE, OPENING, CLOSING, PARKING_WARNING }

    public static final long DISCONNECT_ACC_GRACE_MS = 3_000L;

    public static final class Input {
        public final boolean enabled;
        public final boolean parkingWarningEnabled;
        public final boolean connected;
        public final long connectionEpoch;
        public final boolean roofKnown;
        public final boolean roofOpen;
        public final long roofConnectionEpoch;
        public final long roofReceivedElapsed;
        public final boolean accKnown;
        public final boolean accOn;
        public final long nowElapsed;
        public final boolean cachedRoofKnown;
        public final boolean cachedRoofOpen;
        public final boolean warningAcknowledged;

        public Input(boolean enabled, boolean parkingWarningEnabled, boolean connected,
                     long connectionEpoch, boolean roofKnown, boolean roofOpen,
                     long roofConnectionEpoch, long roofReceivedElapsed,
                     boolean accKnown, boolean accOn, long nowElapsed) {
            this(enabled, parkingWarningEnabled, connected, connectionEpoch, roofKnown, roofOpen,
                    roofConnectionEpoch, roofReceivedElapsed, accKnown, accOn, nowElapsed,
                    false, false, false);
        }

        public Input(boolean enabled, boolean parkingWarningEnabled, boolean connected,
                     long connectionEpoch, boolean roofKnown, boolean roofOpen,
                     long roofConnectionEpoch, long roofReceivedElapsed,
                     boolean accKnown, boolean accOn, long nowElapsed,
                     boolean cachedRoofKnown, boolean cachedRoofOpen, boolean warningAcknowledged) {
            this.enabled = enabled;
            this.parkingWarningEnabled = parkingWarningEnabled;
            this.connected = connected;
            this.connectionEpoch = connectionEpoch;
            this.roofKnown = roofKnown;
            this.roofOpen = roofOpen;
            this.roofConnectionEpoch = roofConnectionEpoch;
            this.roofReceivedElapsed = roofReceivedElapsed;
            this.accKnown = accKnown;
            this.accOn = accOn;
            this.nowElapsed = nowElapsed;
            this.cachedRoofKnown = cachedRoofKnown;
            this.cachedRoofOpen = cachedRoofOpen;
            this.warningAcknowledged = warningAcknowledged;
        }
    }

    public static final class Decision {
        public final long sequence;
        public final Kind kind;
        public final boolean roofOpen;
        public final long warningEpisode;

        private Decision(long sequence, Kind kind, boolean roofOpen, long warningEpisode) {
            this.sequence = sequence;
            this.kind = kind;
            this.roofOpen = roofOpen;
            this.warningEpisode = warningEpisode;
        }
    }

    private Decision decision = new Decision(0L, Kind.NONE, false, 0L);
    private long sequence;
    private long warningEpisode;
    private long lastNow = -1L;
    private long epoch = -1L;
    private boolean connected;
    private boolean enabled;
    private boolean roofBaseline;
    private boolean roofUsable;
    private boolean roofOpen;
    private long lastRoofElapsed = -1L;
    private long lastRoofEpoch = -1L;
    private boolean accKnown;
    private boolean accOn;
    private boolean everAccKnown;
    private boolean lastKnownAccOn;
    private boolean acknowledged;
    private boolean warningLatched;
    private long disconnectedAt = -1L;
    private long disconnectedEpoch = -1L;
    private boolean openAtDisconnect;
    private boolean accOnAtDisconnect;

    public Decision update(Input input) {
        if (input == null) return decision;
        // A settings change must silence a visible episode even if its accompanying snapshot
        // was queued before a newer sensor update. It must not overwrite accepted sensor state.
        if (!input.enabled) {
            enabled = false;
            roofBaseline = false;
            roofUsable = false;
            connected = false;
            accKnown = false;
            disconnectedAt = -1L;
            openAtDisconnect = false;
            accOnAtDisconnect = false;
            warningLatched = false;
            return present(Kind.NONE);
        }
        if (input.nowElapsed < 0L || input.nowElapsed < lastNow
                || input.connectionEpoch < 0L || input.connectionEpoch < epoch) {
            return decision;
        }
        lastNow = input.nowElapsed;
        boolean wasEnabled = enabled;
        enabled = true;
        boolean wasConnected = connected;
        boolean epochChanged = input.connectionEpoch != epoch;
        boolean confirmedAccOff = accKnown && accOn && input.accKnown && !input.accOn;

        // Capture the last *usable* roof before clearing its connection baseline. readerGeneration
        // may advance at disconnect itself as well as at reconnect.
        if (wasConnected && !input.connected) {
            disconnectedAt = input.nowElapsed;
            disconnectedEpoch = input.connectionEpoch;
            openAtDisconnect = roofUsable && roofOpen;
            accOnAtDisconnect = accKnown && accOn;
        } else if (input.connected) {
            disconnectedAt = -1L;
            disconnectedEpoch = -1L;
            openAtDisconnect = false;
            accOnAtDisconnect = false;
        }
        if (epochChanged || !input.connected || !wasConnected || !wasEnabled) {
            roofBaseline = false;
        }
        epoch = input.connectionEpoch;
        connected = input.connected;

        // The persisted real roof position is authoritative for parking warnings. Its age and
        // USB connection are deliberately irrelevant: the adapter publishes only changes.
        if (input.cachedRoofKnown) {
            acknowledged = input.warningAcknowledged;
            if (!input.cachedRoofOpen || acknowledged) warningLatched = false;
        }

        // A fresh known ON ends the old ignition cycle, including through a temporary unknown
        // observation. Unknown and Android screen state never stand in for ACC OFF.
        if (input.accKnown) {
            if (input.accOn && everAccKnown && !lastKnownAccOn) acknowledged = false;
            if (input.accOn) warningLatched = false;
            lastKnownAccOn = input.accOn;
            everAccKnown = true;
        }
        accKnown = input.accKnown;
        accOn = input.accOn;

        Kind transition = Kind.NONE;
        boolean validRoof = input.connected && input.roofKnown
                && input.roofConnectionEpoch == input.connectionEpoch
                && input.roofReceivedElapsed >= 0L
                && input.roofReceivedElapsed <= input.nowElapsed
                && input.roofReceivedElapsed >= lastRoofElapsed;
        // Consecutive accepted CAN packets may share an elapsed millisecond. Caller order is
        // authoritative for ties, matching FrameRouter; only strictly older samples are stale.
        if (validRoof) {
            boolean changed = roofBaseline && input.roofOpen != roofOpen;
            if (changed) {
                transition = input.roofOpen ? Kind.OPENING : Kind.CLOSING;
                if (input.roofOpen) acknowledged = false;
            }
            roofOpen = input.roofOpen;
            roofBaseline = true;
            lastRoofElapsed = input.roofReceivedElapsed;
            lastRoofEpoch = input.roofConnectionEpoch;
            if (!roofOpen && (!input.cachedRoofKnown || !input.cachedRoofOpen)) {
                warningLatched = false;
            }
        } else if (!input.connected || !input.roofKnown) {
            roofBaseline = false;
        }
        // Ignore an older/future source observation without erasing an already
        // accepted observation in this same connection. A new connection has no such baseline.
        roofUsable = validRoof || (input.connected && roofBaseline && roofUsable
                && lastRoofEpoch == input.connectionEpoch);

        if (!input.parkingWarningEnabled) warningLatched = false;
        boolean warningRoofOpen = input.cachedRoofKnown ? input.cachedRoofOpen : roofUsable && roofOpen;
        boolean connectedHazard = warningRoofOpen && input.accKnown && !input.accOn;
        boolean graceHazard = !input.cachedRoofKnown && !input.connected && confirmedAccOff && accOnAtDisconnect
                && openAtDisconnect && disconnectedAt >= 0L
                && input.connectionEpoch == disconnectedEpoch
                && input.nowElapsed - disconnectedAt <= DISCONNECT_ACC_GRACE_MS;
        if (input.parkingWarningEnabled && !warningLatched && !acknowledged
                && (connectedHazard || graceHazard)) {
            warningLatched = true;
            warningEpisode++;
        }
        if (warningLatched) return present(Kind.PARKING_WARNING);
        if (transition != Kind.NONE) return present(transition);
        if (!roofUsable || decision.kind == Kind.PARKING_WARNING) return present(Kind.NONE);
        // Valid duplicate observations never restart a running transition. The controller owns
        // its finite display time and calls finish with this exact presentation sequence.
        return present(decision.kind);
    }

    /** A hardware action acknowledges a parking episode until a real roof/ignition cycle. */
    public Decision dismiss() {
        if (warningLatched || decision.kind == Kind.PARKING_WARNING) acknowledged = true;
        warningLatched = false;
        return present(Kind.NONE);
    }

    public Decision current() {
        return decision;
    }

    /** A delayed animation completion cannot dismiss a new transition or a persistent warning. */
    public Decision finish(long expectedSequence) {
        if (decision.sequence == expectedSequence
                && (decision.kind == Kind.OPENING || decision.kind == Kind.CLOSING)) {
            return present(Kind.NONE);
        }
        return decision;
    }

    /** Full service teardown; sequences stay monotonic to fence callbacks from its prior run. */
    public Decision reset() {
        enabled = false;
        lastNow = -1L;
        epoch = -1L;
        connected = false;
        roofBaseline = false;
        roofUsable = false;
        roofOpen = false;
        lastRoofElapsed = -1L;
        lastRoofEpoch = -1L;
        accKnown = false;
        accOn = false;
        everAccKnown = false;
        lastKnownAccOn = false;
        acknowledged = false;
        warningLatched = false;
        disconnectedAt = -1L;
        disconnectedEpoch = -1L;
        openAtDisconnect = false;
        accOnAtDisconnect = false;
        // Advancing even an already hidden presentation invalidates old service callbacks.
        sequence++;
        warningEpisode = 0L;
        decision = new Decision(sequence, Kind.NONE, false, warningEpisode);
        return decision;
    }

    private Decision present(Kind kind) {
        boolean displayOpen = kind == Kind.PARKING_WARNING || roofOpen;
        if (decision.kind != kind || (kind != Kind.NONE && decision.roofOpen != displayOpen)
                || (kind == Kind.PARKING_WARNING && decision.warningEpisode != warningEpisode)) {
            sequence++;
        }
        if (decision.sequence != sequence || decision.kind != kind
                || decision.roofOpen != displayOpen || decision.warningEpisode != warningEpisode) {
            decision = new Decision(sequence, kind, displayOpen, warningEpisode);
        }
        return decision;
    }
}
