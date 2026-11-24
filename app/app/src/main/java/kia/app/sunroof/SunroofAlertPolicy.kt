package kia.app.sunroof

/**
 * Presentation episodes derived only from the roof's own accepted source and a known ACC state.
 * Callers supply a monotonic clock and connection generation. No roof TTL is imposed: some adapters
 * publish the roof only when it changes.
 */
class SunroofAlertPolicy {
    enum class Kind {
        NONE,
        OPENING,
        CLOSING,
        PARKING_WARNING,
    }

    class Input
    @kotlin.jvm.JvmOverloads
    constructor(
        enabled: Boolean,
        parkingWarningEnabled: Boolean,
        connected: Boolean,
        connectionEpoch: Long,
        roofKnown: Boolean,
        roofOpen: Boolean,
        roofConnectionEpoch: Long,
        roofReceivedElapsed: Long,
        accKnown: Boolean,
        accOn: Boolean,
        nowElapsed: Long,
        cachedRoofKnown: Boolean = false,
        cachedRoofOpen: Boolean = false,
        warningAcknowledged: Boolean = false,
    ) {
        @JvmField val enabled: Boolean
        @JvmField val parkingWarningEnabled: Boolean
        @JvmField val connected: Boolean
        @JvmField val connectionEpoch: Long
        @JvmField val roofKnown: Boolean
        @JvmField val roofOpen: Boolean
        @JvmField val roofConnectionEpoch: Long
        @JvmField val roofReceivedElapsed: Long
        @JvmField val accKnown: Boolean
        @JvmField val accOn: Boolean
        @JvmField val nowElapsed: Long
        @JvmField val cachedRoofKnown: Boolean
        @JvmField val cachedRoofOpen: Boolean
        @JvmField val warningAcknowledged: Boolean

        init {
            this.enabled = enabled
            this.parkingWarningEnabled = parkingWarningEnabled
            this.connected = connected
            this.connectionEpoch = connectionEpoch
            this.roofKnown = roofKnown
            this.roofOpen = roofOpen
            this.roofConnectionEpoch = roofConnectionEpoch
            this.roofReceivedElapsed = roofReceivedElapsed
            this.accKnown = accKnown
            this.accOn = accOn
            this.nowElapsed = nowElapsed
            this.cachedRoofKnown = cachedRoofKnown
            this.cachedRoofOpen = cachedRoofOpen
            this.warningAcknowledged = warningAcknowledged
        }
    }

    class Decision
    internal constructor(
        sequence: Long,
        kind: kia.app.sunroof.SunroofAlertPolicy.Kind?,
        roofOpen: Boolean,
        warningEpisode: Long,
    ) {
        @JvmField val sequence: Long
        @JvmField val kind: kia.app.sunroof.SunroofAlertPolicy.Kind?
        @JvmField val roofOpen: Boolean
        @JvmField val warningEpisode: Long

        init {
            this.sequence = sequence
            this.kind = kind
            this.roofOpen = roofOpen
            this.warningEpisode = warningEpisode
        }
    }

    private var decision: kia.app.sunroof.SunroofAlertPolicy.Decision =
        kia.app.sunroof.SunroofAlertPolicy.Decision(
            0L,
            kia.app.sunroof.SunroofAlertPolicy.Kind.NONE,
            false,
            0L,
        )
    private var sequence: Long = 0
    private var warningEpisode: Long = 0
    private var lastNow: Long = -1L
    private var epoch: Long = -1L
    private var connected: Boolean = false
    private var enabled: Boolean = false
    private var roofBaseline: Boolean = false
    private var roofUsable: Boolean = false
    private var roofOpen: Boolean = false
    private var lastRoofElapsed: Long = -1L
    private var lastRoofEpoch: Long = -1L
    private var accKnown: Boolean = false
    private var accOn: Boolean = false
    private var everAccKnown: Boolean = false
    private var lastKnownAccOn: Boolean = false
    private var acknowledged: Boolean = false
    private var warningLatched: Boolean = false
    private var disconnectedAt: Long = -1L
    private var disconnectedEpoch: Long = -1L
    private var openAtDisconnect: Boolean = false
    private var accOnAtDisconnect: Boolean = false

    fun update(input: Input?): kia.app.sunroof.SunroofAlertPolicy.Decision {
        if (input == null) return decision
        // A settings change must silence a visible episode even if its accompanying snapshot
        // was queued before a newer sensor update. It must not overwrite accepted sensor state.
        if (!input.enabled) {
            enabled = false
            roofBaseline = false
            roofUsable = false
            connected = false
            accKnown = false
            disconnectedAt = -1L
            openAtDisconnect = false
            accOnAtDisconnect = false
            warningLatched = false
            return present(kia.app.sunroof.SunroofAlertPolicy.Kind.NONE)
        }
        if (
            input.nowElapsed < 0L ||
                input.nowElapsed < lastNow ||
                input.connectionEpoch < 0L ||
                input.connectionEpoch < epoch
        ) {
            return decision
        }
        lastNow = input.nowElapsed
        val wasEnabled: Boolean = enabled
        enabled = true
        val wasConnected: Boolean = connected
        val epochChanged: Boolean = input.connectionEpoch != epoch
        val confirmedAccOff: Boolean = accKnown && accOn && input.accKnown && !input.accOn

        // Capture the last *usable* roof before clearing its connection baseline. readerGeneration
        // may advance at disconnect itself as well as at reconnect.
        if (wasConnected && !input.connected) {
            disconnectedAt = input.nowElapsed
            disconnectedEpoch = input.connectionEpoch
            openAtDisconnect = roofUsable && roofOpen
            accOnAtDisconnect = accKnown && accOn
        } else if (input.connected) {
            disconnectedAt = -1L
            disconnectedEpoch = -1L
            openAtDisconnect = false
            accOnAtDisconnect = false
        }
        if (epochChanged || !input.connected || !wasConnected || !wasEnabled) {
            roofBaseline = false
        }
        epoch = input.connectionEpoch
        connected = input.connected

        // The persisted real roof position is authoritative for parking warnings. Its age and
        // USB connection are deliberately irrelevant: the adapter publishes only changes.
        if (input.cachedRoofKnown) {
            acknowledged = input.warningAcknowledged
            if (!input.cachedRoofOpen || acknowledged) warningLatched = false
        }

        // A fresh known ON ends the old ignition cycle, including through a temporary unknown
        // observation. Unknown and Android screen state never stand in for ACC OFF.
        if (input.accKnown) {
            if (input.accOn && everAccKnown && !lastKnownAccOn) acknowledged = false
            if (input.accOn) warningLatched = false
            lastKnownAccOn = input.accOn
            everAccKnown = true
        }
        accKnown = input.accKnown
        accOn = input.accOn

        var transition: kia.app.sunroof.SunroofAlertPolicy.Kind =
            kia.app.sunroof.SunroofAlertPolicy.Kind.NONE
        val validRoof: Boolean =
            input.connected &&
                input.roofKnown &&
                input.roofConnectionEpoch == input.connectionEpoch &&
                input.roofReceivedElapsed >= 0L &&
                input.roofReceivedElapsed <= input.nowElapsed &&
                input.roofReceivedElapsed >= lastRoofElapsed
        // Consecutive accepted CAN packets may share an elapsed millisecond. Caller order is
        // authoritative for ties, matching FrameRouter; only strictly older samples are stale.
        if (validRoof) {
            // Some adapters send position only when it changes. The first real OPEN must
            // therefore be visible even if no initial CLOSED packet arrived. Do this only
            // before any live roof has been accepted: reconnect/unknown snapshots and
            // duplicate OPEN packets must not repeatedly announce an already open roof.
            // A persisted acknowledgement still suppresses the same parked episode.
            val firstOpen: Boolean = lastRoofEpoch < 0L && input.roofOpen && !acknowledged
            // Wake/reconnect can also omit a baseline CLOSED. A new confirmed OPEN after
            // the last accepted CLOSED is still a real change; a repeated OPEN is not.
            val openedAfterGap: Boolean =
                !roofBaseline && lastRoofEpoch >= 0L && !roofOpen && input.roofOpen
            val changed: Boolean =
                firstOpen || openedAfterGap || (roofBaseline && input.roofOpen != roofOpen)
            if (changed) {
                transition =
                    if (input.roofOpen) kia.app.sunroof.SunroofAlertPolicy.Kind.OPENING
                    else kia.app.sunroof.SunroofAlertPolicy.Kind.CLOSING
                if (input.roofOpen) acknowledged = false
            }
            roofOpen = input.roofOpen
            roofBaseline = true
            lastRoofElapsed = input.roofReceivedElapsed
            lastRoofEpoch = input.roofConnectionEpoch
            if (!roofOpen && (!input.cachedRoofKnown || !input.cachedRoofOpen)) {
                warningLatched = false
            }
        } else if (!input.connected || !input.roofKnown) {
            roofBaseline = false
        }
        // Ignore an older/future source observation without erasing an already
        // accepted observation in this same connection. A new connection has no such baseline.
        roofUsable =
            validRoof ||
                (input.connected &&
                    roofBaseline &&
                    roofUsable &&
                    lastRoofEpoch == input.connectionEpoch)

        if (!input.parkingWarningEnabled) warningLatched = false
        val warningRoofOpen: Boolean =
            if (input.cachedRoofKnown) input.cachedRoofOpen else roofUsable && roofOpen
        val connectedHazard: Boolean = warningRoofOpen && input.accKnown && !input.accOn
        val graceHazard: Boolean =
            !input.cachedRoofKnown &&
                !input.connected &&
                confirmedAccOff &&
                accOnAtDisconnect &&
                openAtDisconnect &&
                disconnectedAt >= 0L &&
                input.connectionEpoch == disconnectedEpoch &&
                input.nowElapsed - disconnectedAt <= SunroofAlertPolicy.DISCONNECT_ACC_GRACE_MS
        if (
            input.parkingWarningEnabled &&
                !warningLatched &&
                !acknowledged &&
                (connectedHazard || graceHazard)
        ) {
            warningLatched = true
            warningEpisode++
        }
        if (warningLatched) return present(kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING)
        if (transition != kia.app.sunroof.SunroofAlertPolicy.Kind.NONE) return present(transition)
        if (!roofUsable || decision.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING)
            return present(kia.app.sunroof.SunroofAlertPolicy.Kind.NONE)
        // Valid duplicate observations never restart a running transition. The controller owns
        // its finite display time and calls finish with this exact presentation sequence.
        return present(decision.kind)
    }

    /** A hardware action acknowledges a parking episode until a real roof/ignition cycle. */
    fun dismiss(): kia.app.sunroof.SunroofAlertPolicy.Decision {
        if (
            warningLatched ||
                decision.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING
        )
            acknowledged = true
        warningLatched = false
        return present(kia.app.sunroof.SunroofAlertPolicy.Kind.NONE)
    }

    fun current(): kia.app.sunroof.SunroofAlertPolicy.Decision {
        return decision
    }

    /** A delayed animation completion cannot dismiss a new transition or a persistent warning. */
    fun finish(expectedSequence: Long): kia.app.sunroof.SunroofAlertPolicy.Decision {
        if (
            decision.sequence == expectedSequence &&
                (decision.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.OPENING ||
                    decision.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.CLOSING)
        ) {
            return present(kia.app.sunroof.SunroofAlertPolicy.Kind.NONE)
        }
        return decision
    }

    /** Full service teardown; sequences stay monotonic to fence callbacks from its prior run. */
    fun reset(): kia.app.sunroof.SunroofAlertPolicy.Decision {
        enabled = false
        lastNow = -1L
        epoch = -1L
        connected = false
        roofBaseline = false
        roofUsable = false
        roofOpen = false
        lastRoofElapsed = -1L
        lastRoofEpoch = -1L
        accKnown = false
        accOn = false
        everAccKnown = false
        lastKnownAccOn = false
        acknowledged = false
        warningLatched = false
        disconnectedAt = -1L
        disconnectedEpoch = -1L
        openAtDisconnect = false
        accOnAtDisconnect = false
        // Advancing even an already hidden presentation invalidates old service callbacks.
        sequence++
        warningEpisode = 0L
        decision =
            kia.app.sunroof.SunroofAlertPolicy.Decision(
                sequence,
                kia.app.sunroof.SunroofAlertPolicy.Kind.NONE,
                false,
                warningEpisode,
            )
        return decision
    }

    private fun present(
        kind: kia.app.sunroof.SunroofAlertPolicy.Kind?
    ): kia.app.sunroof.SunroofAlertPolicy.Decision {
        val displayOpen: Boolean =
            kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING || roofOpen
        if (
            decision.kind != kind ||
                (kind != kia.app.sunroof.SunroofAlertPolicy.Kind.NONE &&
                    decision.roofOpen != displayOpen) ||
                (kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING &&
                    decision.warningEpisode != warningEpisode)
        ) {
            sequence++
        }
        if (
            decision.sequence != sequence ||
                decision.kind != kind ||
                decision.roofOpen != displayOpen ||
                decision.warningEpisode != warningEpisode
        ) {
            decision =
                kia.app.sunroof.SunroofAlertPolicy.Decision(
                    sequence,
                    kind,
                    displayOpen,
                    warningEpisode,
                )
        }
        return decision
    }

    companion object {
        const val DISCONNECT_ACC_GRACE_MS: Long = 3000L
    }
}
