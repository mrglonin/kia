package kia.app.core.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AppSettingsNavigationValuesTest {
    @Test
    public void microDistanceSupportsLegacyAndExpandedChoices() {
        int[] supported = {100, 150, 200, 250, 300, 400, 500};
        for (int value : supported) {
            assertEquals(value, AppSettings.normalizeNavMicroMaxDistanceMeters(value));
        }
    }

    @Test
    public void microDistanceClampsAndRoundsToSupportedChoice() {
        assertEquals(100, AppSettings.normalizeNavMicroMaxDistanceMeters(0));
        assertEquals(150, AppSettings.normalizeNavMicroMaxDistanceMeters(101));
        assertEquals(250, AppSettings.normalizeNavMicroMaxDistanceMeters(201));
        assertEquals(400, AppSettings.normalizeNavMicroMaxDistanceMeters(301));
        assertEquals(500, AppSettings.normalizeNavMicroMaxDistanceMeters(501));
    }

    @Test
    public void mainRevealDistanceUsesOneHundredMeterChoices() {
        int[] supported = {100, 200, 300, 400, 500};
        for (int value : supported) {
            assertEquals(value, AppSettings.normalizeNavMainRevealDistanceMeters(value));
        }
        assertEquals(100, AppSettings.normalizeNavMainRevealDistanceMeters(0));
        assertEquals(200, AppSettings.normalizeNavMainRevealDistanceMeters(101));
        assertEquals(500, AppSettings.normalizeNavMainRevealDistanceMeters(501));
    }
}
