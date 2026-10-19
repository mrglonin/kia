package kia.app.protocol.adapter;

/** Tracks identity replies from the current USB session, independently of cached UI state. */
final class AdapterInfoDiscoveryPolicy {
    static final int REQUEST_UID = 1;
    static final int REQUEST_VERSION = 2;

    private long connectionEpoch = -1L;
    private int received;

    synchronized int missingRequests(long epoch) {
        if (!useEpoch(epoch)) return 0;
        return (REQUEST_UID | REQUEST_VERSION) & ~received;
    }

    synchronized void invalidate() {
        received = 0;
    }

    /** Frames arrive after the USB parser has checked their envelope and checksum. */
    synchronized void observeReply(byte[] frame, long epoch) {
        if (frame == null || frame.length < 6
                || (frame[4] & 0xff) != AdapterProtocol.CMD_VERSION) return;
        int length = frame[3] & 0xff;
        int reply = length == 18 && frame.length >= 18 ? REQUEST_UID
                : length == 10 && frame.length >= 10 ? REQUEST_VERSION : 0;
        if (reply == 0 || !useEpoch(epoch)) return;
        received |= reply;
    }

    private boolean useEpoch(long epoch) {
        // Epochs only advance in UsbTransport. A late callback from the old reader must not
        // satisfy discovery for the newly opened port, or roll the discovery state backwards.
        if (epoch <= 0L || epoch < connectionEpoch) return false;
        if (epoch != connectionEpoch) {
            connectionEpoch = epoch;
            received = 0;
        }
        return true;
    }
}
