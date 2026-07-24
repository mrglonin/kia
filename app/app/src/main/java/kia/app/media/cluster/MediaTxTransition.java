package kia.app.media.cluster;

import kia.app.core.settings.AppSettings;

/**
 * Pure transition decision. Metadata changes must never masquerade as source changes.
 */
public final class MediaTxTransition {
    public final boolean modeChanged;
    public final boolean sourceChanged;
    public final boolean metadataChanged;
    public final boolean sendMediaOff;
    public final boolean sendSourcePayload;
    public final boolean sendText;

    private MediaTxTransition(boolean modeChanged, boolean sourceChanged,
                              boolean metadataChanged, boolean sendMediaOff,
                              boolean sendSourcePayload, boolean sendText) {
        this.modeChanged = modeChanged;
        this.sourceChanged = sourceChanged;
        this.metadataChanged = metadataChanged;
        this.sendMediaOff = sendMediaOff;
        this.sendSourcePayload = sendSourcePayload;
        this.sendText = sendText;
    }

    public static MediaTxTransition decide(int profile,
                                           String previousModeKey, String previousSourceKey,
                                           String previousMetadataKey,
                                           String nextModeKey, String nextSourceKey,
                                           String nextMetadataKey) {
        boolean modeChanged = MediaTxPolicy.modeChanged(previousModeKey, nextModeKey);
        boolean sourceChanged = MediaTxPolicy.sourceChanged(previousSourceKey, nextSourceKey);
        boolean metadataChanged = !clean(previousMetadataKey).equals(clean(nextMetadataKey));
        boolean uartReal = profile == AppSettings.MEDIA_PROFILE_UART_REAL;
        return new MediaTxTransition(
                modeChanged,
                sourceChanged,
                metadataChanged,
                !uartReal && modeChanged,
                !uartReal && sourceChanged,
                sourceChanged || metadataChanged
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
