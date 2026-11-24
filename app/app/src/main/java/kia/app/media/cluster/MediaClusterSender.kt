package kia.app.media.cluster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import java.util.Locale
import kia.app.core.StateStore
import kia.app.core.model.CallState
import kia.app.core.model.MediaState
import kia.app.core.settings.AppSettings
import kia.app.media.domain.RealMediaSourceStatus
import kia.app.protocol.adapter.AdapterCommand
import kia.app.protocol.adapter.AdapterGateway
import kia.app.protocol.adapter.AdapterProtocol
import kia.app.protocol.adapter.MediaSourceKind

class MediaClusterSender(context: Context) {
    private val app: Context
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val universalCoordinator: UniversalMediaTxCoordinator
    private var lastMediaKey: String? = ""
    private var lastSourceKey: String? = ""
    private var pendingTrackText: Runnable? = null
    private var routedProfile: Int = -1

    init {
        this.app = context.applicationContext ?: context
        this.universalCoordinator = UniversalMediaTxCoordinator(app)
    }

    @kotlin.jvm.Synchronized
    fun send(state: MediaState?) {
        if (state == null) return
        val profile: Int = AppSettings.mediaProfile(app)
        observeRoutedProfile(profile)
        if (MediaTxPolicy.usesSynchronizedTx(profile)) {
            universalCoordinator.submit(state)
            return
        }
        universalCoordinator.onProfileChanged(profile)
        val kind: MediaSourceKind? = effectiveKind(state)
        val gateway: AdapterGateway? = AdapterGateway.get(app)
        val radioLike: Boolean = MediaClusterSender.isRadioKind(kind)
        val source: String? =
            if (TextUtils.isEmpty(state.source)) kind!!.defaultLabel() else state.source
        val artist: String? = MediaClusterSender.cleanDisplay(state.artist)
        val title: String? = MediaClusterSender.cleanDisplay(state.title)
        val textMode: Int = AppSettings.mediaTextMode(app)
        val firstText: String?
        val trackText: String?
        if (radioLike) {
            firstText = MediaClusterSender.radioHeaderText(source, artist!!, title!!)
            trackText = firstText
        } else if (textMode == AppSettings.MEDIA_TEXT_TRACK_ONLY) {
            firstText = MediaClusterSender.firstNonEmpty(title, artist, source)
            trackText = firstText
        } else {
            firstText = MediaClusterSender.firstNonEmpty(artist, title, source)
            trackText = MediaClusterSender.firstNonEmpty(title, artist, source)
        }
        val sourceKey: String = MediaClusterSender.sourceKey(kind!!, source)
        val mediaKey: String =
            (sourceKey +
                "|" +
                state.packageName +
                "|" +
                artist +
                "|" +
                title +
                "|" +
                state.playing +
                "|text=" +
                textMode)
        val sameMedia: Boolean = TextUtils.equals(mediaKey, lastMediaKey)
        val sendSource: Boolean = !sameMedia || !TextUtils.equals(sourceKey, lastSourceKey)

        if (AppSettings.uartRealMediaProfile(app)) {
            if (!sameMedia) {
                sendTextSequence(
                    gateway!!,
                    MediaClusterSender.textCommand(kind, state),
                    mediaKey,
                    firstText,
                    trackText,
                )
            }
            rememberSource(sourceKey)
            return
        }

        if (radioLike) {
            if (sendSource) {
                if (!TextUtils.equals(sourceKey, lastSourceKey)) {
                    sendMediaOff(gateway!!)
                }
                gateway!!.send(
                    quiet(
                        "Источник 0x7A радио " +
                            MediaClusterSender.firstNonEmpty(artist, title, source),
                        MediaClusterSender.radioSourceStatus(kind, artist!!, title!!),
                    )
                )
                rememberSource(sourceKey)
            }
            if (!sameMedia && kind != MediaSourceKind.AM_RADIO) {
                sendTextSequence(
                    gateway!!,
                    AdapterProtocol.CMD_RADIO_TEXT,
                    mediaKey,
                    firstText,
                    trackText,
                )
            }
            return
        }

        if (sendSource) {
            sendMediaOff(gateway!!)
            if (!MediaClusterSender.textOnlySource(kind)) {
                gateway.send(
                    quiet(
                        "Источник 0x7A " + kind.defaultLabel(),
                        AdapterProtocol.mediaSourceStatus(kind, title),
                    )
                )
            }
            rememberSource(sourceKey)
        }
        if (!sameMedia)
            sendTextSequence(
                gateway!!,
                MediaClusterSender.textCommand(kind, state),
                mediaKey,
                firstText,
                trackText,
            )
    }

    @kotlin.jvm.Synchronized
    fun sendSourceOnly(state: MediaState?) {
        if (state == null) return
        val profile: Int = AppSettings.mediaProfile(app)
        observeRoutedProfile(profile)
        if (MediaTxPolicy.usesSynchronizedTx(profile)) {
            universalCoordinator.submitSourceOnly(state)
            return
        }
        universalCoordinator.onProfileChanged(profile)
        val kind: MediaSourceKind? = effectiveKind(state)
        val source: String? =
            if (TextUtils.isEmpty(state.source)) kind!!.defaultLabel() else state.source
        val sourceKey: String = MediaClusterSender.sourceKey(kind!!, source)
        val mediaKey: String = sourceKey + "|" + state.packageName + "|source-only|" + state.playing
        val sameMedia: Boolean = TextUtils.equals(mediaKey, lastMediaKey)
        val sendSource: Boolean = !sameMedia || !TextUtils.equals(sourceKey, lastSourceKey)
        if (!sendSource) return
        lastMediaKey = mediaKey
        cancelPendingTrackText()
        val gateway: AdapterGateway? = AdapterGateway.get(app)
        if (AppSettings.uartRealMediaProfile(app)) {
            val line: String? =
                MediaClusterSender.firstNonEmpty(
                    MediaClusterSender.cleanDisplay(state.title),
                    MediaClusterSender.cleanDisplay(state.artist),
                    source,
                )
            if (!TextUtils.isEmpty(line)) {
                gateway!!.send(
                    quiet(
                        MediaClusterSender.textTxLabel(
                            MediaClusterSender.textCommand(kind, state),
                            "source",
                            line!!,
                        ),
                        AdapterProtocol.textPacket(
                            MediaClusterSender.textCommand(kind, state),
                            line,
                        ),
                    )
                )
            }
            rememberSource(sourceKey)
            return
        }
        sendMediaOff(gateway!!)
        if (MediaClusterSender.textOnlySource(kind)) {
            gateway.send(
                quiet(
                    MediaClusterSender.textTxLabel(
                        MediaClusterSender.textCommand(kind, state),
                        "source",
                        source!!,
                    ),
                    AdapterProtocol.textPacket(MediaClusterSender.textCommand(kind, state), source),
                )
            )
        } else {
            gateway.send(
                quiet(
                    "Источник 0x7A " + source,
                    if (MediaClusterSender.radioLikeSource(kind))
                        MediaClusterSender.radioSourceStatus(kind, "", "")
                    else AdapterProtocol.mediaSourceStatus(kind, source),
                )
            )
        }
        rememberSource(sourceKey)
    }

    @kotlin.jvm.Synchronized
    fun sendRadioSearch(state: MediaState?) {
        if (state == null) return
        val profile: Int = AppSettings.mediaProfile(app)
        observeRoutedProfile(profile)
        if (MediaTxPolicy.usesSynchronizedTx(profile)) {
            universalCoordinator.submitRadioSearch(state)
            return
        }
        universalCoordinator.onProfileChanged(profile)
        val kind: MediaSourceKind? = effectiveKind(state)
        if (!MediaClusterSender.radioLikeSource(kind)) return
        val source: String? =
            if (TextUtils.isEmpty(state.source)) kind!!.defaultLabel() else state.source
        val frequency: String? = MediaClusterSender.cleanDisplay(state.artist)
        if (TextUtils.isEmpty(frequency) && kind != MediaSourceKind.AM_RADIO) return
        val sourceKey: String = MediaClusterSender.sourceKey(kind!!, source)
        val mediaKey: String = sourceKey + "|" + state.packageName + "|radio-search|" + frequency
        val sameMedia: Boolean = TextUtils.equals(mediaKey, lastMediaKey)
        val sameSource: Boolean = TextUtils.equals(sourceKey, lastSourceKey)
        val wasSearch: Boolean = lastMediaKey!!.contains("|radio-search|")
        if (sameMedia) return
        lastMediaKey = mediaKey
        cancelPendingTrackText()
        val gateway: AdapterGateway? = AdapterGateway.get(app)
        if (kind == MediaSourceKind.AM_RADIO) {
            if (!sameSource || !wasSearch) {
                gateway!!.send(
                    quiet(
                        MediaClusterSender.textTxLabel(
                            AdapterProtocol.CMD_RADIO_TEXT,
                            "search",
                            MediaClusterSender.radioSearchText(source, frequency)!!,
                        ),
                        AdapterProtocol.textPacket(
                            AdapterProtocol.CMD_RADIO_TEXT,
                            MediaClusterSender.radioSearchText(source, frequency),
                        ),
                    )
                )
            }
            rememberSource(sourceKey)
            return
        }
        if (!sameSource) sendMediaOff(gateway!!)
        gateway!!.send(
            quiet(
                "Источник 0x7A поиск радио " + frequency,
                MediaClusterSender.radioSourceStatus(kind, frequency!!, ""),
            )
        )
        if (!sameSource || !wasSearch) {
            gateway.send(
                quiet(
                    MediaClusterSender.textTxLabel(
                        AdapterProtocol.CMD_RADIO_TEXT,
                        "search",
                        MediaClusterSender.radioSearchText(source, frequency)!!,
                    ),
                    AdapterProtocol.textPacket(
                        AdapterProtocol.CMD_RADIO_TEXT,
                        MediaClusterSender.radioSearchText(source, frequency),
                    ),
                )
            )
        }
        rememberSource(sourceKey)
    }

    @kotlin.jvm.Synchronized
    fun sendCall(call: CallState?) {
        if (call == null || !call.active) return
        observeRoutedProfile(AppSettings.mediaProfile(app))
        universalCoordinator.suspendForCall()
        if (MediaTxPolicy.usesSynchronizedTx(AppSettings.mediaProfile(app))) return
        cancelPendingTrackText()
        val gateway: AdapterGateway? = AdapterGateway.get(app)
        val callSource: Int = AppSettings.callSourceMode(app)
        val kind: MediaSourceKind = MediaClusterSender.callSourceKind(callSource)
        val sourceKey: String =
            (MediaClusterSender.sourceKey(kind, AppSettings.callSourceLabel(app)))
        val line: String? =
            MediaClusterSender.cleanDisplay(call.clusterLine(System.currentTimeMillis()))
        val mediaKey: String = sourceKey + "|call|" + line
        val sameMedia: Boolean = TextUtils.equals(mediaKey, lastMediaKey)
        val sameSource: Boolean = TextUtils.equals(sourceKey, lastSourceKey)
        if (!sameSource) {
            gateway!!.send(
                loud(
                    "Звонок: сброс media перед " + AppSettings.callSourceLabel(app),
                    AdapterProtocol.mediaOffStatus(),
                )
            )
            val sourceStatus: ByteArray? = MediaClusterSender.callSourceStatus(callSource)
            if (sourceStatus != null) {
                gateway.send(
                    loud("Звонок: источник " + AppSettings.callSourceLabel(app), sourceStatus)
                )
            }
            rememberSource(sourceKey)
        }
        if (!sameMedia) {
            lastMediaKey = mediaKey
            gateway!!.send(
                loud(
                    MediaClusterSender.textTxLabel(
                        MediaClusterSender.callTextCommand(callSource),
                        "call",
                        line!!,
                    ),
                    AdapterProtocol.textPacket(MediaClusterSender.callTextCommand(callSource), line),
                )
            )
        }
    }

    @kotlin.jvm.Synchronized
    fun clearCall(previous: CallState?) {
        observeRoutedProfile(AppSettings.mediaProfile(app))
        universalCoordinator.suspendForCall()
        if (MediaTxPolicy.usesSynchronizedTx(AppSettings.mediaProfile(app))) return
        cancelPendingTrackText()
        lastMediaKey = ""
        lastSourceKey = ""
        AdapterGateway.get(app)
            .send(loud("Звонок: завершение, media off", AdapterProtocol.mediaOffStatus()))
    }

    @kotlin.jvm.Synchronized
    fun onRealSource(status: RealMediaSourceStatus?): Boolean {
        return universalCoordinator.onRealSource(status)
    }

    @kotlin.jvm.Synchronized
    fun hasRealRadioSource(): Boolean {
        return universalCoordinator.hasRealRadioSource()
    }

    @kotlin.jvm.Synchronized
    fun hasRealSource(): Boolean {
        return universalCoordinator.hasRealSource()
    }

    @kotlin.jvm.Synchronized
    fun realRadioFrequency(): String? {
        return universalCoordinator.realRadioFrequency()
    }

    @kotlin.jvm.Synchronized
    fun onIdle(state: MediaState?) {
        universalCoordinator.onIdle(state)
    }

    @kotlin.jvm.Synchronized
    fun onProfileChanged() {
        val profile: Int = AppSettings.mediaProfile(app)
        observeRoutedProfile(profile)
        universalCoordinator.onProfileChanged(profile)
    }

    @kotlin.jvm.Synchronized
    fun suspendForCall() {
        universalCoordinator.suspendForCall()
    }

    @kotlin.jvm.Synchronized
    fun resumeAfterCall(current: MediaState?) {
        universalCoordinator.resumeAfterCall(current)
    }

    @kotlin.jvm.Synchronized
    fun forceResend(current: MediaState?) {
        if (MediaTxPolicy.usesSynchronizedTx(AppSettings.mediaProfile(app))) {
            universalCoordinator.forceResend(current)
        } else {
            send(current)
        }
    }

    @kotlin.jvm.Synchronized
    fun stopSynchronizedPath() {
        universalCoordinator.stop()
    }

    private fun sendTextSequence(
        gateway: AdapterGateway,
        command: Int,
        mediaKey: String?,
        firstText: String?,
        trackText: String?,
    ) {
        lastMediaKey = mediaKey
        cancelPendingTrackText()
        if (firstText.isNullOrEmpty()) return
        gateway.send(
            quiet(
                MediaClusterSender.textTxLabel(command, "first", firstText),
                AdapterProtocol.textPacket(command, firstText),
            )
        )
        if (trackText.isNullOrEmpty() || TextUtils.equals(firstText, trackText)) return
        pendingTrackText = Runnable {
            synchronized(this@MediaClusterSender) {
                if (!TextUtils.equals(mediaKey, lastMediaKey)) return@Runnable
                AdapterGateway.get(app)
                    .send(
                        quiet(
                            MediaClusterSender.textTxLabel(command, "track", trackText),
                            AdapterProtocol.textPacket(command, trackText),
                        )
                    )
            }
        }
        handler.postDelayed(pendingTrackText!!, MediaClusterSender.TRACK_SWAP_DELAY_MS)
    }

    private fun cancelPendingTrackText() {
        if (pendingTrackText == null) return
        handler.removeCallbacks(pendingTrackText!!)
        pendingTrackText = null
    }

    private fun observeRoutedProfile(profile: Int) {
        if (routedProfile == profile) return
        cancelPendingTrackText()
        lastMediaKey = ""
        lastSourceKey = ""
        routedProfile = profile
    }

    private fun rememberSource(sourceKey: String?) {
        lastSourceKey = sourceKey
    }

    private fun sendMediaOff(gateway: AdapterGateway) {
        gateway.send(quiet("Сброс media source 0x7A", AdapterProtocol.mediaOffStatus()))
    }

    private fun quiet(label: String, frame: ByteArray?): AdapterCommand {
        recordTx(label, frame)
        return AdapterCommand.quiet(label, frame)
    }

    private fun loud(label: String, frame: ByteArray?): AdapterCommand {
        recordTx(label, frame)
        return AdapterCommand.loud(label, frame)
    }

    private fun recordTx(label: String, frame: ByteArray?) {
        StateStore.appendMediaTx(
            app,
            MediaClusterSender.cleanDisplay(label) + " bytes=" + AdapterProtocol.hex(frame),
        )
    }

    private fun effectiveKind(state: MediaState): MediaSourceKind? {
        if (AppSettings.universalMediaProfile(app) && MediaClusterSender.isYandexMusic(state)) {
            return MediaSourceKind.MY_MUSIC
        }
        val kind: MediaSourceKind? =
            if (MediaClusterSender.isTeyesOnline(state)) MediaSourceKind.MY_MUSIC
            else MediaSourceKind.from(state.source, state.packageName)
        if (kind != MediaSourceKind.CLOUD_MUSIC && kind != MediaSourceKind.GENERIC_MUSIC)
            return kind
        when (AppSettings.otherMediaSourceMode(app)) {
            AppSettings.OTHER_SOURCE_USB -> return MediaSourceKind.USB_MUSIC
            AppSettings.OTHER_SOURCE_BLUETOOTH -> return MediaSourceKind.BLUETOOTH_AUDIO
            AppSettings.OTHER_SOURCE_MY_MUSIC -> return MediaSourceKind.MY_MUSIC
            AppSettings.OTHER_SOURCE_CARPLAY -> return MediaSourceKind.CARPLAY
            AppSettings.OTHER_SOURCE_ANDROID -> return kind
            else -> return kind
        }
    }

    companion object {
        private const val TRACK_SWAP_DELAY_MS: Long = 2500L
        private var instance: MediaClusterSender? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): MediaClusterSender {
            if (MediaClusterSender.instance == null)
                MediaClusterSender.instance = MediaClusterSender(context)
            return MediaClusterSender.instance!!
        }

        private fun textTxLabel(command: Int, stage: String?, text: String?): String {
            return "Текст " +
                MediaClusterSender.commandLabel(command) +
                " " +
                stage +
                ": " +
                MediaClusterSender.quote(MediaClusterSender.shortText(text))
        }

        private fun commandLabel(command: Int): String? {
            when (command) {
                AdapterProtocol.CMD_RADIO_TEXT -> return "0x20 radio"
                AdapterProtocol.CMD_MEDIA_TEXT -> return "0x21 BT/media"
                AdapterProtocol.CMD_USB_TEXT -> return "0x22 USB"
                AdapterProtocol.CMD_ANDROID_AUTO_TEXT -> return "0x23 Android Auto"
                AdapterProtocol.CMD_MY_MUSIC_TEXT -> return "0x24 My Music"
                AdapterProtocol.CMD_CARPLAY_TEXT -> return "0x25 CarPlay"
                else -> return "0x" + Integer.toHexString(command).uppercase(Locale.ROOT)
            }
        }

        private fun quote(value: String): String? {
            return "\"" + MediaClusterSender.cleanDisplay(value) + "\""
        }

        private fun shortText(value: String?): String {
            val clean = MediaClusterSender.cleanDisplay(value)
            return if (clean.length <= 42) clean else clean.substring(0, 39) + "..."
        }

        private fun textCommand(kind: MediaSourceKind?, state: MediaState?): Int {
            if (MediaClusterSender.isRadioKind(kind)) {
                return AdapterProtocol.CMD_RADIO_TEXT
            }
            if (kind == MediaSourceKind.USB_MUSIC) {
                return AdapterProtocol.CMD_USB_TEXT
            }
            if (kind == MediaSourceKind.ANDROID_AUTO) {
                return AdapterProtocol.CMD_ANDROID_AUTO_TEXT
            }
            if (kind == MediaSourceKind.CARPLAY) {
                return AdapterProtocol.CMD_CARPLAY_TEXT
            }
            if (kind == MediaSourceKind.MY_MUSIC) {
                return AdapterProtocol.CMD_MY_MUSIC_TEXT
            }
            if (kind == MediaSourceKind.BLUETOOTH_AUDIO || kind == MediaSourceKind.BT_PHONE) {
                return AdapterProtocol.CMD_MEDIA_TEXT
            }
            return AdapterProtocol.CMD_ANDROID_AUTO_TEXT
        }

        private fun callSourceStatus(mode: Int): ByteArray? {
            when (mode) {
                AppSettings.CALL_SOURCE_ANDROID_AUTO -> return null
                AppSettings.CALL_SOURCE_BLUETOOTH ->
                    return AdapterProtocol.mediaSourceStatus(
                        MediaSourceKind.BLUETOOTH_AUDIO,
                        "BT Audio",
                    )
                AppSettings.CALL_SOURCE_MY_MUSIC -> return null
                AppSettings.CALL_SOURCE_USB ->
                    return AdapterProtocol.mediaSourceStatus(MediaSourceKind.USB_MUSIC, "USB Music")
                AppSettings.CALL_SOURCE_FM ->
                    return AdapterProtocol.mediaFmSourceStatusNoFrequency()
                AppSettings.CALL_SOURCE_CARPLAY -> return null
                else -> return null
            }
        }

        private fun callTextCommand(mode: Int): Int {
            when (mode) {
                AppSettings.CALL_SOURCE_ANDROID_AUTO -> return AdapterProtocol.CMD_ANDROID_AUTO_TEXT
                AppSettings.CALL_SOURCE_BLUETOOTH -> return AdapterProtocol.CMD_MEDIA_TEXT
                AppSettings.CALL_SOURCE_MY_MUSIC -> return AdapterProtocol.CMD_MY_MUSIC_TEXT
                AppSettings.CALL_SOURCE_USB -> return AdapterProtocol.CMD_USB_TEXT
                AppSettings.CALL_SOURCE_FM -> return AdapterProtocol.CMD_RADIO_TEXT
                AppSettings.CALL_SOURCE_CARPLAY -> return AdapterProtocol.CMD_CARPLAY_TEXT
                else -> return AdapterProtocol.CMD_CARPLAY_TEXT
            }
        }

        private fun callSourceKind(mode: Int): MediaSourceKind {
            when (mode) {
                AppSettings.CALL_SOURCE_ANDROID_AUTO -> return MediaSourceKind.ANDROID_AUTO
                AppSettings.CALL_SOURCE_BLUETOOTH -> return MediaSourceKind.BLUETOOTH_AUDIO
                AppSettings.CALL_SOURCE_MY_MUSIC -> return MediaSourceKind.MY_MUSIC
                AppSettings.CALL_SOURCE_USB -> return MediaSourceKind.USB_MUSIC
                AppSettings.CALL_SOURCE_FM -> return MediaSourceKind.FM_RADIO
                AppSettings.CALL_SOURCE_CARPLAY -> return MediaSourceKind.CARPLAY
                else -> return MediaSourceKind.CARPLAY
            }
        }

        private fun isRadioKind(kind: MediaSourceKind?): Boolean {
            return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO
        }

        private fun radioLikeSource(kind: MediaSourceKind?): Boolean {
            return MediaClusterSender.isRadioKind(kind)
        }

        private fun textOnlySource(kind: MediaSourceKind?): Boolean {
            return kind == MediaSourceKind.ANDROID_AUTO ||
                kind == MediaSourceKind.CARPLAY ||
                kind == MediaSourceKind.MY_MUSIC
        }

        private fun radioSourceStatus(
            kind: MediaSourceKind?,
            artist: String?,
            title: String?,
        ): ByteArray? {
            if (kind == MediaSourceKind.FM_RADIO) {
                val frequency: String? = MediaClusterSender.firstFrequency(artist, title)
                return if (TextUtils.isEmpty(frequency))
                    AdapterProtocol.mediaFmSourceStatusNoFrequency()
                else AdapterProtocol.mediaSourceStatus(kind, frequency)
            }
            return AdapterProtocol.mediaSourceStatus(
                kind,
                MediaClusterSender.firstNonEmpty(artist, title),
            )
        }

        private fun sourceKey(kind: MediaSourceKind, source: String?): String {
            return kind.name + "|" + source
        }

        private fun radioHeaderText(source: String?, artist: String?, title: String?): String? {
            val station: String? =
                MediaClusterSender.firstNonEmpty(
                    MediaClusterSender.nonFrequency(title),
                    MediaClusterSender.nonFrequency(artist),
                )
            if (!TextUtils.isEmpty(station)) return station
            return MediaClusterSender.firstNonEmpty(
                MediaClusterSender.firstFrequency(artist, title),
                source,
            )
        }

        private fun radioSearchText(source: String?, frequency: String?): String? {
            val label: String? = if (TextUtils.isEmpty(source)) "Radio" else source
            return if (TextUtils.isEmpty(frequency)) label.toString() + " поиск"
            else label.toString() + " поиск"
        }

        private fun firstFrequency(first: String?, second: String?): String? {
            if (MediaClusterSender.looksLikeOnlyFrequency(first)) return first
            if (MediaClusterSender.looksLikeOnlyFrequency(second)) return second
            return ""
        }

        private fun nonFrequency(value: String?): String? {
            return if (MediaClusterSender.looksLikeOnlyFrequency(value)) "" else value
        }

        private fun looksLikeOnlyFrequency(value: String?): Boolean {
            if (value.isNullOrEmpty()) return false
            val text = value.trim()
            return text.matches(Regex("(?i)^(FM|AM)?\\s*\\d{2,4}([\\.,]\\d{1,2})?\\s*(MHz|kHz)?$"))
        }

        private fun isTeyesOnline(state: MediaState?): Boolean {
            if (state == null || TextUtils.isEmpty(state.source)) return false
            return "TEYES".equals(state.source.trim(), ignoreCase = true)
        }

        private fun isYandexMusic(state: MediaState?): Boolean {
            if (state == null) return false
            val text: String? =
                (((if (state.source == null) "" else state.source) +
                        " " +
                        (if (state.packageName == null) "" else state.packageName)))
                    .lowercase(Locale.ROOT)
            return text!!.contains("yandex") || text.contains("яндекс")
        }

        private fun cleanDisplay(value: String?): String {
            if (value.isNullOrEmpty() || "<unknown>".equals(value.trim(), ignoreCase = true))
                return ""
            return value.trim()
        }

        private fun firstNonEmpty(first: String?, second: String?): String? {
            return if (TextUtils.isEmpty(first)) second else first
        }

        private fun firstNonEmpty(first: String?, second: String?, third: String?): String? {
            if (!TextUtils.isEmpty(first)) return first
            if (!TextUtils.isEmpty(second)) return second
            return third
        }
    }
}
