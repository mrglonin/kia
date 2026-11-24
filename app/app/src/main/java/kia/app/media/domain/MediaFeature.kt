package kia.app.media.domain

import android.content.Context
import android.text.TextUtils
import java.util.Locale
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.MediaState
import kia.app.core.settings.AppSettings
import kia.app.media.cluster.MediaClusterSender
import kia.app.media.cluster.MediaTxPolicy

class MediaFeature private constructor(context: Context) {
    private val app: Context
    private val clusterSender: MediaClusterSender?
    private var lastKey: String = ""

    init {
        this.app = context.applicationContext ?: context
        this.clusterSender = MediaClusterSender.get(app)
    }

    @kotlin.jvm.Synchronized
    fun report(
        source: String?,
        packageName: String?,
        artist: String?,
        title: String?,
        durationMs: Long,
    ) {
        report(source, packageName, artist, title, durationMs, true)
    }

    @kotlin.jvm.Synchronized
    fun report(
        source: String?,
        packageName: String?,
        artist: String?,
        title: String?,
        durationMs: Long,
        playing: Boolean,
    ) {
        if (!AppSettings.mediaEnabled(app)) return
        val cleanSource: String? = MediaFeature.firstNonEmpty(source, labelFromPackage(packageName))
        val cleanArtist: String? = MediaFeature.clean(artist)
        var cleanTitle: String? = MediaFeature.clean(title)
        val radioSource: Boolean =
            if (AppSettings.universalMediaProfile(app))
                MediaTxPolicy.isPhysicalRadioSource(cleanSource, packageName)
            else MediaFeature.isRadioSource(cleanSource, packageName)
        if (radioSource && !TextUtils.isEmpty(cleanArtist)) {
            cleanTitle =
                if (AppSettings.universalMediaProfile(app))
                    RadioStationStore.resolveUniversal(app, cleanSource, cleanArtist, cleanTitle)
                else RadioStationStore.resolve(app, cleanSource, cleanArtist, cleanTitle)
        }
        if (
            TextUtils.isEmpty(cleanSource) &&
                TextUtils.isEmpty(cleanArtist) &&
                TextUtils.isEmpty(cleanTitle)
        )
            return

        val stableDurationMs: Long =
            stableDuration(cleanSource, packageName, cleanArtist, cleanTitle, durationMs, playing)
        val state: MediaState =
            MediaState(
                cleanSource,
                packageName,
                cleanArtist,
                cleanTitle,
                stableDurationMs,
                playing,
                System.currentTimeMillis(),
            )
        StateStore.setMedia(app, state)

        val key: String =
            cleanSource.toString() +
                "|" +
                packageName +
                "|" +
                cleanArtist +
                "|" +
                cleanTitle +
                "|" +
                playing
        val changed: Boolean = !TextUtils.equals(key, lastKey)
        if (changed) {
            lastKey = key
        }
        if (StateStore.call().active) {
            if (changed) AppLog.line(app, "Media RX held by call: " + state.summary())
        } else if (changed || AppSettings.universalMediaProfile(app)) {
            clusterSender!!.send(state)
            if (changed) AppLog.line(app, "Media RX: " + state.summary())
        }
    }

    @kotlin.jvm.Synchronized
    fun reportSourceOnly(source: String?, packageName: String?) {
        reportSourceOnly(source, packageName, -1L)
    }

    @kotlin.jvm.Synchronized
    fun reportSourceOnly(source: String?, packageName: String?, durationMs: Long) {
        if (!AppSettings.mediaEnabled(app)) return
        if (TextUtils.isEmpty(source)) return
        val stableDurationMs: Long = stableDuration(source, packageName, "", "", durationMs, false)
        val state: MediaState =
            MediaState(
                source,
                packageName,
                "",
                "",
                stableDurationMs,
                false,
                System.currentTimeMillis(),
            )
        StateStore.setMedia(app, state)
        val key: String = source.toString() + "|" + packageName + "|source|false"
        val changed: Boolean = !TextUtils.equals(key, lastKey)
        if (changed) {
            lastKey = key
        }
        if (StateStore.call().active) {
            if (changed) AppLog.line(app, "Media RX source held by call: " + state.summary())
        } else if (changed || AppSettings.universalMediaProfile(app)) {
            clusterSender!!.sendSourceOnly(state)
            if (changed) AppLog.line(app, "Media RX source: " + state.summary())
        }
    }

    @kotlin.jvm.Synchronized
    fun reportRadioSearch(source: String?, packageName: String?, frequency: String?) {
        if (!AppSettings.mediaEnabled(app)) return
        val cleanSource: String? = MediaFeature.firstNonEmpty(source, labelFromPackage(packageName))
        val cleanFrequency: String? = MediaFeature.clean(frequency)
        if (TextUtils.isEmpty(cleanSource)) return

        val state: MediaState =
            MediaState(
                cleanSource,
                packageName,
                cleanFrequency,
                "",
                -1L,
                true,
                System.currentTimeMillis(),
            )
        StateStore.setMedia(app, state)
        val key: String =
            cleanSource.toString() + "|" + packageName + "|radio-search|" + cleanFrequency
        val changed: Boolean = !TextUtils.equals(key, lastKey)
        if (changed) {
            lastKey = key
        }
        if (StateStore.call().active) {
            if (changed) AppLog.line(app, "Media RX radio search held by call: " + state.summary())
        } else if (changed || AppSettings.universalMediaProfile(app)) {
            clusterSender!!.sendRadioSearch(state)
            if (changed) AppLog.line(app, "Media RX radio search: " + state.summary())
        }
    }

    @kotlin.jvm.Synchronized
    fun reportIdle(packageName: String?, reason: String?) {
        if (!AppSettings.mediaEnabled(app)) return
        val current: MediaState? = StateStore.media()
        val state: MediaState =
            MediaState(
                current!!.source,
                packageName,
                current.artist,
                current.title,
                current.durationMs,
                false,
                System.currentTimeMillis(),
            )
        StateStore.setMedia(app, state)
        if (AppSettings.universalMediaProfile(app)) {
            clusterSender!!.onIdle(state)
        }
        val key: String = state.source.toString() + "|" + packageName + "|idle|" + reason
        if (!TextUtils.equals(key, lastKey)) {
            lastKey = key
            AppLog.line(
                app,
                "Media RX idle: " + state.summary() + " | " + MediaFeature.clean(reason),
            )
        }
    }

    @kotlin.jvm.Synchronized
    fun resendCurrent(reason: String?) {
        if (!AppSettings.mediaEnabled(app)) return
        val current: MediaState? = StateStore.media()
        if (
            current == null ||
                (TextUtils.isEmpty(current.source) &&
                    TextUtils.isEmpty(current.artist) &&
                    TextUtils.isEmpty(current.title))
        ) {
            return
        }
        lastKey = ""
        if (StateStore.call().active) {
            AppLog.line(
                app,
                ("Media resend held by call: " +
                    current.summary() +
                    " | " +
                    MediaFeature.clean(reason)),
            )
            return
        }
        clusterSender!!.forceResend(current)
        AppLog.line(app, "Media resend: " + current.summary() + " | " + MediaFeature.clean(reason))
    }

    @kotlin.jvm.Synchronized
    fun handleRealMediaStatus(frame: ByteArray?) {
        if (!AppSettings.uartRealMediaProfile(app)) return
        val status: RealMediaSourceStatus? = RealMediaSourceStatus.parse(frame)
        if (status == null) return
        val changed: Boolean = clusterSender!!.onRealSource(status)
        if (!changed) return
        AppLog.line(
            app,
            ("Media real source: " +
                status.source +
                (if (TextUtils.isEmpty(status.frequency)) "" else " " + status.frequency)),
        )
        if (status.radio() && !TextUtils.isEmpty(status.frequency)) {
            report(status.source, "uart.real", status.frequency, "", -1L, true)
        }
    }

    @kotlin.jvm.Synchronized
    fun hasRealRadioSource(): Boolean {
        return clusterSender!!.hasRealRadioSource()
    }

    @kotlin.jvm.Synchronized
    fun hasRealSource(): Boolean {
        return clusterSender!!.hasRealSource()
    }

    @kotlin.jvm.Synchronized
    fun realRadioFrequency(): String? {
        return clusterSender!!.realRadioFrequency()
    }

    @kotlin.jvm.Synchronized
    fun onProfileChanged() {
        lastKey = ""
        clusterSender!!.onProfileChanged()
        // StateStore still belongs to the previous capture for a short moment. The newly selected
        // capture starts immediately and supplies an authoritative fresh state instead.
    }

    private fun labelFromPackage(packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return "Music"
        val p = packageName.lowercase(Locale.ROOT)
        if (p.contains("yandex")) return "Яндекс Музыка"
        if (p.contains("bluetooth") || p.contains("btmusic")) return "Bluetooth"
        if (p.contains("radio")) return "FM радио"
        if (p.contains("spd.media")) return "USB"
        return packageName
    }

    private fun stableDuration(
        source: String?,
        packageName: String?,
        artist: String?,
        title: String?,
        durationMs: Long,
        playing: Boolean,
    ): Long {
        if (playing) return durationMs
        val current: MediaState? = StateStore.media()
        if (
            TextUtils.equals(MediaFeature.clean(source), current!!.source) &&
                TextUtils.equals(MediaFeature.clean(packageName), current.packageName) &&
                TextUtils.equals(MediaFeature.clean(artist), current.artist) &&
                TextUtils.equals(MediaFeature.clean(title), current.title)
        ) {
            return current.durationMs
        }
        return -1L
    }

    companion object {
        private var instance: MediaFeature? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): MediaFeature {
            if (MediaFeature.instance == null) MediaFeature.instance = MediaFeature(context)
            return MediaFeature.instance!!
        }

        private fun isRadioSource(source: String?, packageName: String?): Boolean {
            val text: String? =
                MediaFeature.clean(source.toString() + " " + packageName)!!.lowercase(Locale.ROOT)
            return text!!.contains("fm") ||
                text.contains("am") ||
                text.contains("radio") ||
                text.contains("радио") ||
                text.contains("com.spd.radio")
        }

        private fun firstNonEmpty(first: String?, fallback: String?): String? {
            return if (TextUtils.isEmpty(first)) fallback else first
        }

        private fun clean(value: String?): String? {
            if (value == null) return ""
            val out = value.replace('\n', ' ').replace('\r', ' ').trim()
            return out.replace(Regex("\\s+"), " ")
        }
    }
}
