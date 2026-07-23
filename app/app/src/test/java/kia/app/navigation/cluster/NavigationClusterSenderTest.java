package kia.app.navigation.cluster;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

import kia.app.protocol.adapter.AdapterProtocol;

public final class NavigationClusterSenderTest {
    @Test
    public void finishDirectionVisualKeyIncludesEncodedDistance() {
        byte[] near = AdapterProtocol.directionToFinish(6, 80f, false);
        byte[] far = AdapterProtocol.directionToFinish(6, 120f, false);

        assertFalse(NavigationClusterSender.finishDirectionVisualKey(near)
                .equals(NavigationClusterSender.finishDirectionVisualKey(far)));
    }
}
