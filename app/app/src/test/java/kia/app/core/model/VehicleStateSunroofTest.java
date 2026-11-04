package kia.app.core.model;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VehicleStateSunroofTest {
    @Test public void temperatureUpdatesPreserveRoofSessionAndReceiveTime() {
        VehicleState roof = VehicleState.empty().withSunroof(true, "0x76", 1234L, 7L);
        VehicleState next = roof.withEngineTemp(90, "test")
                .withOutsideTemp(21, "test").withCabinTemp(true, 22, true, 23, "test");
        assertTrue(next.sunroofKnown);
        assertTrue(next.sunroofOpen);
        assertEquals(1234L, next.sunroofReceivedElapsed);
        assertEquals(7L, next.sunroofConnectionEpoch);
    }

    @Test public void disconnectClearsRoofWithoutErasingTemperatures() {
        VehicleState roof = VehicleState.empty().withEngineTemp(90, "test")
                .withSunroof(true, "0x76", 1234L, 7L);
        VehicleState lost = roof.withoutSunroof();
        assertFalse(lost.sunroofKnown);
        assertEquals(0L, lost.sunroofReceivedElapsed);
        assertEquals(0L, lost.sunroofConnectionEpoch);
        assertTrue(lost.engineTempKnown);
        assertEquals(90, lost.engineTempC);
        assertSame(lost, lost.withoutSunroof());
    }
}
