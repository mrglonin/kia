package kia.app.media.cluster

import java.util.Locale
import kia.app.core.settings.AppSettings
import kia.app.protocol.adapter.MediaSourceKind

/** Pure decisions used by the synchronized Android/UART media path. */
object MediaTxPolicy {
    @JvmStatic
    fun usesSynchronizedTx(profile: Int): Boolean {
        return profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID ||
            profile == AppSettings.MEDIA_PROFILE_UART_REAL
    }

    @JvmStatic
    fun resolveOtherSource(detected: MediaSourceKind?, otherMode: Int): MediaSourceKind? {
        if (detected != MediaSourceKind.CLOUD_MUSIC && detected != MediaSourceKind.GENERIC_MUSIC) {
            return detected
        }
        when (otherMode) {
            AppSettings.OTHER_SOURCE_USB -> return MediaSourceKind.USB_MUSIC
            AppSettings.OTHER_SOURCE_BLUETOOTH -> return MediaSourceKind.BLUETOOTH_AUDIO
            AppSettings.OTHER_SOURCE_MY_MUSIC -> return MediaSourceKind.MY_MUSIC
            AppSettings.OTHER_SOURCE_CARPLAY -> return MediaSourceKind.CARPLAY
            AppSettings.OTHER_SOURCE_ANDROID -> return MediaSourceKind.ANDROID_AUTO
            else -> return MediaSourceKind.ANDROID_AUTO
        }
    }

    @JvmStatic
    fun detectUniversalSource(source: String?, packageName: String?): MediaSourceKind? {
        val text: String? =
            (((if (source == null) "" else source) +
                    " " +
                    (if (packageName == null) "" else packageName)))
                .lowercase(Locale.US)
        if (
            text!!.contains("интернет") ||
                text.contains("network radio") ||
                text.contains("net radio") ||
                text.contains("s-radio") ||
                text.contains("s radio")
        ) {
            return MediaSourceKind.CLOUD_MUSIC
        }
        return MediaSourceKind.from(source, packageName)
    }

    @JvmStatic
    fun isPhysicalRadioSource(source: String?, packageName: String?): Boolean {
        val kind: MediaSourceKind? = MediaTxPolicy.detectUniversalSource(source, packageName)
        return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO
    }

    @JvmStatic
    fun modeKey(profile: Int, kind: MediaSourceKind): String {
        return profile.toString() + "|" + kind.name
    }

    @JvmStatic
    fun sourceKey(profile: Int, kind: MediaSourceKind, frequency: String?): String {
        val detail: String = if (MediaTxPolicy.isRadio(kind)) MediaTxPolicy.clean(frequency) else ""
        return MediaTxPolicy.modeKey(profile, kind) + "|" + detail
    }

    @JvmStatic
    fun sourceChanged(previousSourceKey: String?, nextSourceKey: String?): Boolean {
        return !MediaTxPolicy.clean(previousSourceKey).equals(MediaTxPolicy.clean(nextSourceKey))
    }

    @JvmStatic
    fun modeChanged(previousModeKey: String?, nextModeKey: String?): Boolean {
        return !MediaTxPolicy.clean(previousModeKey).equals(MediaTxPolicy.clean(nextModeKey))
    }

    @JvmStatic
    fun resumedSameContent(
        previousContentKey: String?,
        previousPlaying: Boolean,
        nextContentKey: String?,
        nextPlaying: Boolean,
    ): Boolean {
        return nextPlaying &&
            !previousPlaying &&
            MediaTxPolicy.clean(previousContentKey).equals(MediaTxPolicy.clean(nextContentKey))
    }

    @JvmStatic
    fun shouldSendMediaOff(
        modeChanged: Boolean,
        sourceKey: String?,
        offCompletedSourceKey: String?,
    ): Boolean {
        return modeChanged &&
            !MediaTxPolicy.clean(sourceKey).equals(MediaTxPolicy.clean(offCompletedSourceKey))
    }

    @JvmStatic
    fun transportChanged(
        known: Boolean,
        previousReady: Boolean,
        previousEpoch: Long,
        ready: Boolean,
        epoch: Long,
    ): Boolean {
        return known && (previousReady != ready || (ready && previousEpoch != epoch))
    }

    @JvmStatic
    fun transportCurrent(ready: Boolean, scheduledEpoch: Long, currentEpoch: Long): Boolean {
        return ready && scheduledEpoch == currentEpoch
    }

    @JvmStatic
    fun callbackCurrent(
        scheduledGeneration: Int,
        currentGeneration: Int,
        scheduledProfile: Int,
        activeProfile: Int,
        configuredProfile: Int,
        callActive: Boolean,
        scheduledMediaKey: String?,
        desiredMediaKey: String?,
    ): Boolean {
        return scheduledGeneration == currentGeneration &&
            scheduledProfile == activeProfile &&
            scheduledProfile == configuredProfile &&
            MediaTxPolicy.usesSynchronizedTx(scheduledProfile) &&
            !callActive &&
            MediaTxPolicy.clean(scheduledMediaKey).equals(MediaTxPolicy.clean(desiredMediaKey))
    }

    @JvmStatic
    fun shouldReassertAfterRealSource(
        sourceOff: Boolean,
        sourceRadio: Boolean,
        latestStateRadio: Boolean,
        latestStateHasText: Boolean,
        latestStateIdle: Boolean,
    ): Boolean {
        return !sourceOff &&
            !sourceRadio &&
            !latestStateRadio &&
            latestStateHasText &&
            !latestStateIdle
    }

    @JvmStatic
    fun shouldRecoverSameRealSource(
        sourceOff: Boolean,
        sourceRadio: Boolean,
        latestStateRadio: Boolean,
        latestStateHasText: Boolean,
        latestStateIdle: Boolean,
        currentMediaKey: String?,
        recoveredMediaKey: String?,
    ): Boolean {
        return !sourceOff &&
            sourceRadio == latestStateRadio &&
            latestStateHasText &&
            !latestStateIdle &&
            !MediaTxPolicy.clean(currentMediaKey).isEmpty() &&
            !MediaTxPolicy.clean(currentMediaKey).equals(MediaTxPolicy.clean(recoveredMediaKey))
    }

    private fun isRadio(kind: MediaSourceKind?): Boolean {
        return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO
    }

    private fun clean(value: String?): String {
        return if (value == null) "" else value
    }
}
