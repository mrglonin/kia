package kia.app.media.cluster

import kia.app.core.settings.AppSettings

/** Pure transition decision. Metadata changes must never masquerade as source changes. */
class MediaTxTransition
private constructor(
    modeChanged: Boolean,
    sourceChanged: Boolean,
    metadataChanged: Boolean,
    sendMediaOff: Boolean,
    sendSourcePayload: Boolean,
    sendText: Boolean,
) {
    @JvmField val modeChanged: Boolean
    @JvmField val sourceChanged: Boolean
    @JvmField val metadataChanged: Boolean
    @JvmField val sendMediaOff: Boolean
    @JvmField val sendSourcePayload: Boolean
    @JvmField val sendText: Boolean

    init {
        this.modeChanged = modeChanged
        this.sourceChanged = sourceChanged
        this.metadataChanged = metadataChanged
        this.sendMediaOff = sendMediaOff
        this.sendSourcePayload = sendSourcePayload
        this.sendText = sendText
    }

    companion object {
        @JvmStatic
        fun decide(
            profile: Int,
            previousModeKey: String?,
            previousSourceKey: String?,
            previousMetadataKey: String?,
            nextModeKey: String?,
            nextSourceKey: String?,
            nextMetadataKey: String?,
        ): MediaTxTransition {
            val modeChanged: Boolean = MediaTxPolicy.modeChanged(previousModeKey, nextModeKey)
            val sourceChanged: Boolean =
                MediaTxPolicy.sourceChanged(previousSourceKey, nextSourceKey)
            val metadataChanged: Boolean =
                !MediaTxTransition.clean(previousMetadataKey)
                    .equals(MediaTxTransition.clean(nextMetadataKey))
            val uartReal: Boolean = profile == AppSettings.MEDIA_PROFILE_UART_REAL
            return MediaTxTransition(
                modeChanged,
                sourceChanged,
                metadataChanged,
                !uartReal && modeChanged,
                !uartReal && sourceChanged,
                sourceChanged || metadataChanged,
            )
        }

        private fun clean(value: String?): String? {
            return if (value == null) "" else value
        }
    }
}
