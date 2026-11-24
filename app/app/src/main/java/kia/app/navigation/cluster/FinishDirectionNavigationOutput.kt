package kia.app.navigation.cluster

import kia.app.protocol.adapter.AdapterProtocol

internal class FinishDirectionNavigationOutput {
    fun directionToFinish(uiStep: Int, distance: Float, km: Boolean): ByteArray? {
        return AdapterProtocol.directionToFinish(uiStep, distance, km)
    }
}
