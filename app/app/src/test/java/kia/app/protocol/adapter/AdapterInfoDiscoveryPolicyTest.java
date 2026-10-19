package kia.app.protocol.adapter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AdapterInfoDiscoveryPolicyTest {
    private static final int BOTH = AdapterInfoDiscoveryPolicy.REQUEST_UID
            | AdapterInfoDiscoveryPolicy.REQUEST_VERSION;

    @Test
    public void eachReplyStopsOnlyItsOwnRetry() {
        AdapterInfoDiscoveryPolicy policy = new AdapterInfoDiscoveryPolicy();
        assertEquals(BOTH, policy.missingRequests(1L));
        policy.observeReply(reply(18), 1L);
        assertEquals(AdapterInfoDiscoveryPolicy.REQUEST_VERSION, policy.missingRequests(1L));
        policy.observeReply(reply(10), 1L);
        assertEquals(0, policy.missingRequests(1L));
        assertEquals(0, policy.missingRequests(1L));
    }

    @Test
    public void versionMayArriveBeforeUidOrBeforeTheFirstHealthTick() {
        AdapterInfoDiscoveryPolicy policy = new AdapterInfoDiscoveryPolicy();
        policy.observeReply(reply(10), 4L);
        assertEquals(AdapterInfoDiscoveryPolicy.REQUEST_UID, policy.missingRequests(4L));
        policy.observeReply(reply(18), 4L);
        assertEquals(0, policy.missingRequests(4L));
    }

    @Test
    public void reconnectRequiresNewRepliesEvenWhenOldIdentityWasComplete() {
        AdapterInfoDiscoveryPolicy policy = new AdapterInfoDiscoveryPolicy();
        policy.observeReply(reply(18), 1L);
        policy.observeReply(reply(10), 1L);
        assertEquals(0, policy.missingRequests(1L));
        assertEquals(BOTH, policy.missingRequests(2L));
        policy.observeReply(reply(18), 2L);
        assertEquals(AdapterInfoDiscoveryPolicy.REQUEST_VERSION, policy.missingRequests(2L));
    }

    @Test
    public void oldReaderCannotCompleteOrResetNewSessionDiscovery() {
        AdapterInfoDiscoveryPolicy policy = new AdapterInfoDiscoveryPolicy();
        assertEquals(BOTH, policy.missingRequests(2L));
        policy.observeReply(reply(18), 1L);
        policy.observeReply(reply(10), 1L);
        assertEquals(BOTH, policy.missingRequests(2L));
        policy.observeReply(reply(18), 2L);
        assertEquals(0, policy.missingRequests(1L));
        assertEquals(AdapterInfoDiscoveryPolicy.REQUEST_VERSION, policy.missingRequests(2L));
    }

    @Test
    public void truncatedOrUnrelatedRepliesDoNotStopDiscovery() {
        AdapterInfoDiscoveryPolicy policy = new AdapterInfoDiscoveryPolicy();
        byte[] truncatedUid = new byte[17];
        truncatedUid[3] = 18;
        truncatedUid[4] = (byte) AdapterProtocol.CMD_VERSION;
        byte[] truncatedVersion = new byte[9];
        truncatedVersion[3] = 10;
        truncatedVersion[4] = (byte) AdapterProtocol.CMD_VERSION;
        byte[] health = reply(10);
        health[4] = (byte) AdapterProtocol.CMD_HEALTH;
        policy.observeReply(null, 1L);
        policy.observeReply(new byte[5], 1L);
        policy.observeReply(truncatedUid, 1L);
        policy.observeReply(truncatedVersion, 1L);
        policy.observeReply(reply(12), 1L);
        policy.observeReply(health, 1L);
        assertEquals(BOTH, policy.missingRequests(1L));
    }

    @Test
    public void noDiscoveryStateIsAcceptedBeforeARealConnection() {
        AdapterInfoDiscoveryPolicy policy = new AdapterInfoDiscoveryPolicy();
        policy.observeReply(reply(18), 0L);
        policy.observeReply(reply(10), -1L);
        assertEquals(0, policy.missingRequests(0L));
        assertEquals(0, policy.missingRequests(-1L));
        assertEquals(BOTH, policy.missingRequests(1L));
    }

    @Test
    public void firmwareRestartCanInvalidateIdentityWithoutUsbReconnection() {
        AdapterInfoDiscoveryPolicy policy = new AdapterInfoDiscoveryPolicy();
        policy.observeReply(reply(18), 5L);
        policy.observeReply(reply(10), 5L);
        assertEquals(0, policy.missingRequests(5L));
        policy.invalidate();
        policy.observeReply(reply(18), 4L);
        policy.observeReply(reply(10), 4L);
        assertEquals(BOTH, policy.missingRequests(5L));
        policy.observeReply(reply(10), 5L);
        assertEquals(AdapterInfoDiscoveryPolicy.REQUEST_UID, policy.missingRequests(5L));
    }

    private static byte[] reply(int size) {
        byte[] frame = new byte[size];
        frame[3] = (byte) size;
        frame[4] = (byte) AdapterProtocol.CMD_VERSION;
        return frame;
    }
}
