package kia.app.media.cluster;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import kia.app.core.settings.AppSettings;

public final class MediaTxTransitionTest {
    @Test
    public void androidTrackChangeSendsOnlyText() {
        MediaTxTransition transition = MediaTxTransition.decide(
                AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID,
                "android|music", "android|music|", "track-one",
                "android|music", "android|music|", "track-two");

        assertFalse(transition.modeChanged);
        assertFalse(transition.sourceChanged);
        assertTrue(transition.metadataChanged);
        assertFalse(transition.sendMediaOff);
        assertFalse(transition.sendSourcePayload);
        assertTrue(transition.sendText);
    }

    @Test
    public void androidModeChangeSendsOffSourceAndText() {
        MediaTxTransition transition = MediaTxTransition.decide(
                AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID,
                "android|bt", "android|bt|", "track",
                "android|usb", "android|usb|", "track");

        assertTrue(transition.modeChanged);
        assertTrue(transition.sourceChanged);
        assertTrue(transition.sendMediaOff);
        assertTrue(transition.sendSourcePayload);
        assertTrue(transition.sendText);
    }

    @Test
    public void radioFrequencyChangeDoesNotSendMediaOff() {
        MediaTxTransition transition = MediaTxTransition.decide(
                AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID,
                "android|fm", "android|fm|101.0", "station",
                "android|fm", "android|fm|102.0", "station");

        assertFalse(transition.modeChanged);
        assertTrue(transition.sourceChanged);
        assertFalse(transition.sendMediaOff);
        assertTrue(transition.sendSourcePayload);
        assertTrue(transition.sendText);
    }

    @Test
    public void uartNeverSendsSyntheticSourceFrames() {
        MediaTxTransition transition = MediaTxTransition.decide(
                AppSettings.MEDIA_PROFILE_UART_REAL,
                "uart|bt", "uart|bt|", "track-one",
                "uart|usb", "uart|usb|", "track-two");

        assertTrue(transition.modeChanged);
        assertTrue(transition.sourceChanged);
        assertFalse(transition.sendMediaOff);
        assertFalse(transition.sendSourcePayload);
        assertTrue(transition.sendText);
    }

    @Test
    public void identicalStateSendsNothing() {
        MediaTxTransition transition = MediaTxTransition.decide(
                AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID,
                "mode", "source", "media",
                "mode", "source", "media");

        assertFalse(transition.modeChanged);
        assertFalse(transition.sourceChanged);
        assertFalse(transition.metadataChanged);
        assertFalse(transition.sendMediaOff);
        assertFalse(transition.sendSourcePayload);
        assertFalse(transition.sendText);
    }
}
