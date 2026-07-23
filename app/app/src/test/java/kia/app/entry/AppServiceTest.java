package kia.app.entry;

import static org.junit.Assert.assertEquals;

import android.content.pm.ServiceInfo;

import org.junit.Test;

public class AppServiceTest {
    @Test
    public void connectedDeviceTypeIsAlwaysPresentWithoutRuntimePermissions() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                AppService.foregroundTypeMask(false)
        );
    }

    @Test
    public void locationTypeIsAddedOnlyWhenBackgroundLocationIsReady() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                AppService.foregroundTypeMask(true)
        );
    }
}
