package kia.app.media.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpdRadioServiceClientTest {
    @Test
    public void universalStationNameNeverUsesRdsRadioText() {
        assertEquals("", SpdRadioServiceClient.selectUniversalStationName(
                "", "", "", "Current song title"));
    }

    @Test
    public void universalStationNameUsesStableSourcesInPriorityOrder() {
        assertEquals("Saved station", SpdRadioServiceClient.selectUniversalStationName(
                "Saved station", "Frequency PS", "RDS PS", "RDS RT"));
        assertEquals("Frequency PS", SpdRadioServiceClient.selectUniversalStationName(
                "", "Frequency PS", "RDS PS", "RDS RT"));
        assertEquals("RDS PS", SpdRadioServiceClient.selectUniversalStationName(
                "", "", "RDS PS", "RDS RT"));
    }

    @Test
    public void staleSpdFrequencyCannotOverrideRealUartRadioFrequency() {
        assertTrue(UniversalMediaCapture.matchesRealRadioFrequency(
                "FM", "101.2", "101.2"));
        assertFalse(UniversalMediaCapture.matchesRealRadioFrequency(
                "FM", "101.2", "102.4"));
        assertTrue(UniversalMediaCapture.matchesRealRadioFrequency(
                "AM", "1584", "1584"));
        assertFalse(UniversalMediaCapture.matchesRealRadioFrequency(
                "AM", "1584", "1602"));
    }
}
