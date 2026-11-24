package kia.app.protocol.adapter

/** Tracks identity replies from the current USB session, independently of cached UI state. */
internal class AdapterInfoDiscoveryPolicy {
    private var connectionEpoch: Long = -1L
    private var received: Int = 0

    @kotlin.jvm.Synchronized
    fun missingRequests(epoch: Long): Int {
        if (!useEpoch(epoch)) return 0
        return (AdapterInfoDiscoveryPolicy.REQUEST_UID or
            AdapterInfoDiscoveryPolicy.REQUEST_VERSION) and received.inv()
    }

    @kotlin.jvm.Synchronized
    fun invalidate() {
        received = 0
    }

    /** Frames arrive after the USB parser has checked their envelope and checksum. */
    @kotlin.jvm.Synchronized
    fun observeReply(frame: ByteArray?, epoch: Long) {
        if (
            frame == null ||
                frame.size < 6 ||
                (frame.get(4).toInt() and 0xff) != AdapterProtocol.CMD_VERSION
        )
            return
        val length: Int = frame.get(3).toInt() and 0xff
        val reply: Int =
            if (length == 18 && frame.size >= 18) AdapterInfoDiscoveryPolicy.REQUEST_UID
            else if (length == 10 && frame.size >= 10) AdapterInfoDiscoveryPolicy.REQUEST_VERSION
            else 0
        if (reply == 0 || !useEpoch(epoch)) return
        received = received or reply
    }

    private fun useEpoch(epoch: Long): Boolean {
        // Epochs only advance in UsbTransport. A late callback from the old reader must not
        // satisfy discovery for the newly opened port, or roll the discovery state backwards.
        if (epoch <= 0L || epoch < connectionEpoch) return false
        if (epoch != connectionEpoch) {
            connectionEpoch = epoch
            received = 0
        }
        return true
    }

    companion object {
        const val REQUEST_UID: Int = 1
        const val REQUEST_VERSION: Int = 2
    }
}
