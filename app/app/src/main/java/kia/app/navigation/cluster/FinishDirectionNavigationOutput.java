package kia.app.navigation.cluster;

import kia.app.protocol.adapter.AdapterProtocol;

final class FinishDirectionNavigationOutput {
    byte[] directionToFinish(int uiStep, float distance, boolean km) {
        return AdapterProtocol.directionToFinish(uiStep, distance, km);
    }
}
