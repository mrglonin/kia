package kia.app.entry;

import static org.junit.Assert.assertEquals;

import android.app.Service;
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

    @Test
    public void foregroundFailureIsNonStickyAndHasStableHealth() {
        assertEquals(Service.START_NOT_STICKY, AppService.foregroundStartMode(false));
        assertEquals("service foreground failed: create",
                AppService.foregroundFailureHealth("create"));
        assertEquals("service foreground failed: launch TestException",
                AppService.foregroundFailureHealth("launch TestException"));
    }

    @Test
    public void foregroundSuccessRemainsSticky() {
        assertEquals(Service.START_STICKY, AppService.foregroundStartMode(true));
    }
}
