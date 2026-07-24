package kia.app.media.cluster;

import java.util.Locale;

import kia.app.core.settings.AppSettings;
import kia.app.protocol.adapter.MediaSourceKind;

/**
 * Pure decisions used by the synchronized Android/UART media path.
 */
public final class MediaTxPolicy {
    private MediaTxPolicy() {
    }

    public static boolean usesSynchronizedTx(int profile) {
        return profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID
                || profile == AppSettings.MEDIA_PROFILE_UART_REAL;
    }

    public static MediaSourceKind resolveOtherSource(MediaSourceKind detected, int otherMode) {
        if (detected != MediaSourceKind.CLOUD_MUSIC
                && detected != MediaSourceKind.GENERIC_MUSIC) {
            return detected;
        }
        switch (otherMode) {
            case AppSettings.OTHER_SOURCE_USB:
                return MediaSourceKind.USB_MUSIC;
            case AppSettings.OTHER_SOURCE_BLUETOOTH:
                return MediaSourceKind.BLUETOOTH_AUDIO;
            case AppSettings.OTHER_SOURCE_MY_MUSIC:
                return MediaSourceKind.MY_MUSIC;
            case AppSettings.OTHER_SOURCE_CARPLAY:
                return MediaSourceKind.CARPLAY;
            case AppSettings.OTHER_SOURCE_ANDROID:
            default:
                return MediaSourceKind.ANDROID_AUTO;
        }
    }

    public static MediaSourceKind detectUniversalSource(String source, String packageName) {
        String text = ((source == null ? "" : source) + " "
                + (packageName == null ? "" : packageName)).toLowerCase(Locale.US);
        if (text.contains("интернет") || text.contains("network radio")
                || text.contains("net radio") || text.contains("s-radio")
                || text.contains("s radio")) {
            return MediaSourceKind.CLOUD_MUSIC;
        }
        return MediaSourceKind.from(source, packageName);
    }

    public static boolean isPhysicalRadioSource(String source, String packageName) {
        MediaSourceKind kind = detectUniversalSource(source, packageName);
        return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO;
    }

    public static String modeKey(int profile, MediaSourceKind kind) {
        return profile + "|" + kind.name();
    }

    public static String sourceKey(int profile, MediaSourceKind kind, String frequency) {
        String detail = isRadio(kind) ? clean(frequency) : "";
        return modeKey(profile, kind) + "|" + detail;
    }

    public static boolean sourceChanged(String previousSourceKey, String nextSourceKey) {
        return !clean(previousSourceKey).equals(clean(nextSourceKey));
    }

    public static boolean modeChanged(String previousModeKey, String nextModeKey) {
        return !clean(previousModeKey).equals(clean(nextModeKey));
    }

    public static boolean resumedSameContent(String previousContentKey, boolean previousPlaying,
                                             String nextContentKey, boolean nextPlaying) {
        return nextPlaying && !previousPlaying
                && clean(previousContentKey).equals(clean(nextContentKey));
    }

    public static boolean shouldSendMediaOff(boolean modeChanged, String sourceKey,
                                             String offCompletedSourceKey) {
        return modeChanged && !clean(sourceKey).equals(clean(offCompletedSourceKey));
    }

    public static boolean transportChanged(boolean known, boolean previousReady,
                                           long previousEpoch, boolean ready, long epoch) {
        return known && (previousReady != ready || (ready && previousEpoch != epoch));
    }

    public static boolean transportCurrent(boolean ready, long scheduledEpoch,
                                           long currentEpoch) {
        return ready && scheduledEpoch == currentEpoch;
    }

    public static boolean callbackCurrent(int scheduledGeneration, int currentGeneration,
                                          int scheduledProfile, int activeProfile,
                                          int configuredProfile, boolean callActive,
                                          String scheduledMediaKey, String desiredMediaKey) {
        return scheduledGeneration == currentGeneration
                && scheduledProfile == activeProfile
                && scheduledProfile == configuredProfile
                && usesSynchronizedTx(scheduledProfile)
                && !callActive
                && clean(scheduledMediaKey).equals(clean(desiredMediaKey));
    }

    public static boolean shouldReassertAfterRealSource(boolean sourceOff, boolean sourceRadio,
                                                        boolean latestStateRadio,
                                                        boolean latestStateHasText,
                                                        boolean latestStateIdle) {
        return !sourceOff && !sourceRadio && !latestStateRadio
                && latestStateHasText && !latestStateIdle;
    }

    public static boolean shouldRecoverSameRealSource(boolean sourceOff, boolean sourceRadio,
                                                      boolean latestStateRadio,
                                                      boolean latestStateHasText,
                                                      boolean latestStateIdle,
                                                      String currentMediaKey,
                                                      String recoveredMediaKey) {
        return !sourceOff
                && sourceRadio == latestStateRadio
                && latestStateHasText
                && !latestStateIdle
                && !clean(currentMediaKey).isEmpty()
                && !clean(currentMediaKey).equals(clean(recoveredMediaKey));
    }

    private static boolean isRadio(MediaSourceKind kind) {
        return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO;
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
