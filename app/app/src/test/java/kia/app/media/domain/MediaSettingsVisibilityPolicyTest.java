package kia.app.media.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import kia.app.core.settings.AppSettings;

public final class MediaSettingsVisibilityPolicyTest {
    @Test
    public void androidAndUartTimingIsVisibleOutsideExpertMode() {
        assertTrue(MediaSettingsVisibilityPolicy.showsTimingControls(
                AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID));
        assertTrue(MediaSettingsVisibilityPolicy.showsTimingControls(
                AppSettings.MEDIA_PROFILE_UART_REAL));
    }

    @Test
    public void teyesAndOffNeverExposeUniversalTiming() {
        assertFalse(MediaSettingsVisibilityPolicy.showsTimingControls(
                AppSettings.MEDIA_PROFILE_TEYES));
        assertFalse(MediaSettingsVisibilityPolicy.showsTimingControls(
                AppSettings.MEDIA_PROFILE_OFF));
    }

    @Test
    public void sourceReassertRemainsAnExpertOnlyUniversalControl() {
        assertFalse(MediaSettingsVisibilityPolicy.showsSourceReassertControl(
                AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID, false));
        assertFalse(MediaSettingsVisibilityPolicy.showsSourceReassertControl(
                AppSettings.MEDIA_PROFILE_UART_REAL, false));
        assertTrue(MediaSettingsVisibilityPolicy.showsSourceReassertControl(
                AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID, true));
        assertTrue(MediaSettingsVisibilityPolicy.showsSourceReassertControl(
                AppSettings.MEDIA_PROFILE_UART_REAL, true));
        assertFalse(MediaSettingsVisibilityPolicy.showsSourceReassertControl(
                AppSettings.MEDIA_PROFILE_TEYES, true));
    }
}
