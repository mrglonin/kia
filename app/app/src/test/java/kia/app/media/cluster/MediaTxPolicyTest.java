package kia.app.media.cluster;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import kia.app.core.settings.AppSettings;
import kia.app.protocol.adapter.MediaSourceKind;

public final class MediaTxPolicyTest {
    @Test
    public void teyesKeepsLegacyMediaTxPath() {
        assertFalse(MediaTxPolicy.usesSynchronizedTx(AppSettings.MEDIA_PROFILE_TEYES));
    }

    @Test
    public void onlyAndroidAndUartRealUseSynchronizedMediaTxPath() {
        assertTrue(MediaTxPolicy.usesSynchronizedTx(
                AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID));
        assertTrue(MediaTxPolicy.usesSynchronizedTx(
                AppSettings.MEDIA_PROFILE_UART_REAL));
        assertFalse(MediaTxPolicy.usesSynchronizedTx(AppSettings.MEDIA_PROFILE_OFF));
        assertFalse(MediaTxPolicy.usesSynchronizedTx(Integer.MIN_VALUE));
        assertFalse(MediaTxPolicy.usesSynchronizedTx(Integer.MAX_VALUE));
    }

    @Test
    public void androidFallbackIsARealAndroidAutoSource() {
        assertSame(MediaSourceKind.ANDROID_AUTO, MediaTxPolicy.resolveOtherSource(
                MediaSourceKind.GENERIC_MUSIC,
                AppSettings.OTHER_SOURCE_ANDROID));
        assertSame(MediaSourceKind.ANDROID_AUTO, MediaTxPolicy.resolveOtherSource(
                MediaSourceKind.CLOUD_MUSIC,
                AppSettings.OTHER_SOURCE_ANDROID));
    }

    @Test
    public void concreteTeyesSourceKindsAreNeverRemappedByUniversalPolicy() {
        for (MediaSourceKind kind : new MediaSourceKind[]{
                MediaSourceKind.USB_MUSIC,
                MediaSourceKind.BLUETOOTH_AUDIO,
                MediaSourceKind.FM_RADIO,
                MediaSourceKind.AM_RADIO,
                MediaSourceKind.MY_MUSIC,
                MediaSourceKind.CARPLAY
        }) {
            assertSame(kind, MediaTxPolicy.resolveOtherSource(
                    kind, AppSettings.OTHER_SOURCE_ANDROID));
        }
    }

    @Test
    public void radioFrequencyChangesPayloadButNotMode() {
        int profile = AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID;
        String mode = MediaTxPolicy.modeKey(profile, MediaSourceKind.FM_RADIO);
        String first = MediaTxPolicy.sourceKey(profile, MediaSourceKind.FM_RADIO, "101.0");
        String second = MediaTxPolicy.sourceKey(profile, MediaSourceKind.FM_RADIO, "102.0");

        assertFalse(MediaTxPolicy.modeChanged(mode, mode));
        assertTrue(MediaTxPolicy.sourceChanged(first, second));
    }

    @Test
    public void networkRadioIsCloudMusicInUniversalPathNotFm() {
        assertSame(MediaSourceKind.CLOUD_MUSIC,
                MediaTxPolicy.detectUniversalSource("Network Radio", "com.example.player"));
        assertFalse(MediaTxPolicy.isPhysicalRadioSource(
                "Network Radio", "com.example.player"));
        assertTrue(MediaTxPolicy.isPhysicalRadioSource("FM", "com.spd.radio"));
    }

    @Test
    public void realNonRadioSourceNeverReassertsStaleRadioMetadata() {
        assertFalse(MediaTxPolicy.shouldReassertAfterRealSource(
                false, false, true, true, false));
        assertTrue(MediaTxPolicy.shouldReassertAfterRealSource(
                false, false, false, true, false));
        assertFalse(MediaTxPolicy.shouldReassertAfterRealSource(
                true, false, false, true, false));
        assertFalse(MediaTxPolicy.shouldReassertAfterRealSource(
                false, true, false, true, false));
        assertFalse(MediaTxPolicy.shouldReassertAfterRealSource(
                false, false, false, true, true));
    }

    @Test
    public void sameRealSourceRecoversCurrentTextOnlyOncePerMediaKey() {
        assertTrue(MediaTxPolicy.shouldRecoverSameRealSource(
                false, false, false, true, false, "track-b", ""));
        assertFalse(MediaTxPolicy.shouldRecoverSameRealSource(
                false, false, false, true, false, "track-b", "track-b"));
        assertTrue(MediaTxPolicy.shouldRecoverSameRealSource(
                false, false, false, true, false, "track-c", "track-b"));
    }

    @Test
    public void sameRealSourceRecoveryRequiresMatchingActiveSource() {
        assertFalse(MediaTxPolicy.shouldRecoverSameRealSource(
                false, false, true, true, false, "station", ""));
        assertFalse(MediaTxPolicy.shouldRecoverSameRealSource(
                false, true, false, true, false, "track", ""));
        assertFalse(MediaTxPolicy.shouldRecoverSameRealSource(
                true, false, false, true, false, "track", ""));
        assertFalse(MediaTxPolicy.shouldRecoverSameRealSource(
                false, false, false, true, true, "track", ""));
    }

    @Test
    public void onlyPausedToPlayingOfSameContentIsAResume() {
        assertTrue(MediaTxPolicy.resumedSameContent("same", false, "same", true));
        assertFalse(MediaTxPolicy.resumedSameContent("same", true, "same", false));
        assertFalse(MediaTxPolicy.resumedSameContent("old", false, "new", true));
    }

    @Test
    public void completedOffStageIsNotRepeatedWhileSourceRetries() {
        assertTrue(MediaTxPolicy.shouldSendMediaOff(true, "usb", ""));
        assertFalse(MediaTxPolicy.shouldSendMediaOff(true, "usb", "usb"));
        assertTrue(MediaTxPolicy.shouldSendMediaOff(true, "bt", "usb"));
        assertFalse(MediaTxPolicy.shouldSendMediaOff(false, "fm-102", ""));
    }

    @Test
    public void reconnectEpochDetectsFastReconnectEvenWhenReadyStayedTrue() {
        assertTrue(MediaTxPolicy.transportChanged(true, true, 4L, true, 5L));
        assertTrue(MediaTxPolicy.transportChanged(true, true, 4L, false, 4L));
        assertFalse(MediaTxPolicy.transportChanged(true, true, 4L, true, 4L));
        assertFalse(MediaTxPolicy.transportChanged(false, false, -1L, false, 0L));
        assertTrue(MediaTxPolicy.transportCurrent(true, 5L, 5L));
        assertFalse(MediaTxPolicy.transportCurrent(true, 4L, 5L));
        assertFalse(MediaTxPolicy.transportCurrent(false, 5L, 5L));
    }

    @Test
    public void delayedCallbackRequiresSameGenerationProfileAndMedia() {
        int profile = AppSettings.MEDIA_PROFILE_UART_REAL;
        assertTrue(MediaTxPolicy.callbackCurrent(
                7, 7, profile, profile, profile, false, "track-c", "track-c"));
        assertFalse(MediaTxPolicy.callbackCurrent(
                6, 7, profile, profile, profile, false, "track-b", "track-b"));
        assertFalse(MediaTxPolicy.callbackCurrent(
                7, 7, profile, profile, profile, true, "track-c", "track-c"));
        assertFalse(MediaTxPolicy.callbackCurrent(
                7, 7, profile, profile, profile, false, "track-b", "track-c"));
        assertFalse(MediaTxPolicy.callbackCurrent(
                7, 7, profile, AppSettings.MEDIA_PROFILE_TEYES,
                AppSettings.MEDIA_PROFILE_TEYES, false, "track-c", "track-c"));
    }
}
