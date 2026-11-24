package kia.app.media.cluster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import java.util.Locale
import kia.app.core.StateStore
import kia.app.core.model.MediaState
import kia.app.core.settings.AppSettings
import kia.app.media.domain.RealMediaSourceStatus
import kia.app.protocol.adapter.AdapterCommand
import kia.app.protocol.adapter.AdapterGateway
import kia.app.protocol.adapter.AdapterProtocol
import kia.app.protocol.adapter.AdapterTxOutcome
import kia.app.protocol.adapter.MediaSourceKind

/**
 * Ordered media transport used only by Universal Android and UART Real.
 *
 * The legacy TEYES path intentionally remains in [MediaClusterSender]. This coordinator separates
 * source transitions from metadata changes, so a new song cannot reset the source.
 */
internal class UniversalMediaTxCoordinator(context: Context) {
    private val app: Context
    private val handler: Handler = Handler(Looper.getMainLooper())

    private var activeProfile: Int = -1
    private var generation: Int = 0
    private var lastModeKey: String = ""
    private var lastSourceKey: String = ""
    private var lastMediaKey: String? = ""
    private var lastObservedContentKey: String = ""
    private var desiredMediaKey: String = ""
    private var pendingMediaKey: String? = ""
    private var offCompletedSourceKey: String? = ""
    private var recoveredRealSourceMediaKey: String? = ""
    private var settleUntilElapsed: Long = 0
    private var lastUsbEpoch: Long = -1L
    private var lastObservedPlaying: Boolean = false
    private var reassertNextText: Boolean = false
    private var usbStateKnown: Boolean = false
    private var lastUsbReady: Boolean = false
    private var latestIdle: Boolean = true
    private var latestState: MediaState = MediaState.empty()
    private var realSource: RealMediaSourceStatus? = null
    private var realSourceKey: String = ""
    private var pendingFirstText: Runnable? = null
    private var pendingTrackText: Runnable? = null
    private var pendingReassertText: Runnable? = null

    init {
        this.app = context.applicationContext ?: context
    }

    @kotlin.jvm.Synchronized
    fun submit(state: MediaState?) {
        if (state == null) return
        val profile: Int = AppSettings.mediaProfile(app)
        if (!MediaTxPolicy.usesSynchronizedTx(profile)) {
            onProfileChanged(profile)
            return
        }
        ensureProfile(profile)
        if (StateStore.call().active) {
            latestState = state
            latestIdle = false
            cancelPending()
            return
        }

        val gateway: AdapterGateway? = AdapterGateway.get(app)
        val usbReady: Boolean = gateway!!.usbReady()
        observeUsbState(usbReady, gateway.connectionEpoch())
        latestState = state
        latestIdle = false
        if (!usbReady) return
        if (
            profile == AppSettings.MEDIA_PROFILE_UART_REAL && realSource != null && realSource!!.off
        ) {
            cancelPending()
            return
        }
        if (
            profile == AppSettings.MEDIA_PROFILE_UART_REAL &&
                realSource != null &&
                realSource!!.radio() &&
                !UniversalMediaTxCoordinator.isRadioState(state)
        ) {
            return
        }

        val kind: MediaSourceKind? = effectiveKind(profile, state)
        val radioLike: Boolean = UniversalMediaTxCoordinator.isRadioKind(kind)
        val source: String? = sourceLabel(profile, kind!!, state)
        val artist: String? = UniversalMediaTxCoordinator.cleanDisplay(state.artist)
        val title: String? = UniversalMediaTxCoordinator.cleanDisplay(state.title)
        var frequency: String? =
            if (radioLike) UniversalMediaTxCoordinator.firstFrequency(artist!!, title!!) else ""
        if (radioLike && TextUtils.isEmpty(frequency) && realSource != null) {
            frequency = UniversalMediaTxCoordinator.cleanDisplay(realSource!!.frequency)
        }
        val textMode: Int = AppSettings.mediaTextMode(app)
        val firstText: String?
        val trackText: String?
        if (radioLike) {
            firstText =
                UniversalMediaTxCoordinator.radioHeaderText(source, artist!!, title!!, frequency)
            trackText = firstText
        } else if (textMode == AppSettings.MEDIA_TEXT_TRACK_ONLY) {
            firstText = UniversalMediaTxCoordinator.firstNonEmpty(title, artist, source)
            trackText = firstText
        } else {
            firstText = UniversalMediaTxCoordinator.firstNonEmpty(artist, title, source)
            trackText = UniversalMediaTxCoordinator.firstNonEmpty(title, artist, source)
        }

        val modeKey: String = MediaTxPolicy.modeKey(profile, kind)
        val sourceKey: String =
            if (profile == AppSettings.MEDIA_PROFILE_UART_REAL && realSource != null)
                profile.toString() + "|real|" + realSource!!.key()
            else MediaTxPolicy.sourceKey(profile, kind, frequency)
        val contentKey: String =
            (sourceKey +
                "|" +
                UniversalMediaTxCoordinator.cleanDisplay(state.packageName) +
                "|" +
                artist +
                "|" +
                title +
                "|text=" +
                textMode)
        val resumed: Boolean =
            MediaTxPolicy.resumedSameContent(
                lastObservedContentKey,
                lastObservedPlaying,
                contentKey,
                state.playing,
            )
        lastObservedContentKey = contentKey
        lastObservedPlaying = state.playing
        val mediaKey: String = contentKey
        val transition: MediaTxTransition =
            MediaTxTransition.decide(
                profile,
                lastModeKey,
                lastSourceKey,
                lastMediaKey,
                modeKey,
                sourceKey,
                mediaKey,
            )
        val modeChanged: Boolean = transition.modeChanged
        val sourceChanged: Boolean = transition.sourceChanged

        if (!transition.sendText && !resumed) return
        if (resumed || !TextUtils.equals(desiredMediaKey, mediaKey)) {
            beginGeneration(mediaKey)
        }

        if (sourceChanged) {
            if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID) {
                if (
                    !sendAndroidSourceTransition(
                        gateway,
                        kind,
                        modeChanged,
                        sourceKey,
                        artist!!,
                        title!!,
                    )
                ) {
                    return
                }
            }
            lastModeKey = modeKey
            lastSourceKey = sourceKey
            settleUntilElapsed =
                (SystemClock.elapsedRealtime() + AppSettings.mediaSourceDelayMs(app, profile))
            reassertNextText = AppSettings.mediaSourceReassertEnabled(app)
            appendJournal(
                ("SCHEDULE source=" +
                    kind.name +
                    " delay=" +
                    AppSettings.mediaSourceDelayMs(app, profile) +
                    "ms")
            )
        }

        val delayMs: Long = Math.max(0L, settleUntilElapsed - SystemClock.elapsedRealtime())
        dispatchText(kind, state, mediaKey, firstText, trackText, delayMs)
    }

    @kotlin.jvm.Synchronized
    fun submitSourceOnly(state: MediaState?) {
        submit(state)
    }

    @kotlin.jvm.Synchronized
    fun submitRadioSearch(state: MediaState?) {
        submit(state)
    }

    @kotlin.jvm.Synchronized
    fun forceResend(state: MediaState?) {
        lastMediaKey = ""
        desiredMediaKey = ""
        settleUntilElapsed = 0L
        reassertNextText = false
        cancelPending()
        if (state != null) submit(state)
    }

    @kotlin.jvm.Synchronized
    fun onRealSource(status: RealMediaSourceStatus?): Boolean {
        if (status == null || !AppSettings.uartRealMediaProfile(app)) return false
        ensureProfile(AppSettings.MEDIA_PROFILE_UART_REAL)
        val nextKey: String = status.key()!!
        if (TextUtils.equals(realSourceKey, nextKey)) {
            return recoverAfterSameRealSource(status)
        }

        realSource = status
        realSourceKey = nextKey
        lastModeKey = ""
        lastSourceKey = ""
        lastMediaKey = ""
        settleUntilElapsed =
            (SystemClock.elapsedRealtime() +
                AppSettings.mediaSourceDelayMs(app, AppSettings.MEDIA_PROFILE_UART_REAL))
        reassertNextText = AppSettings.mediaSourceReassertEnabled(app)
        beginGeneration("")
        appendJournal(
            ("SOURCE_RX raw=0x" +
                String.format(Locale.US, "%02X", status.rawSource) +
                " kind=" +
                status.kind!!.name +
                (if (TextUtils.isEmpty(status.frequency)) "" else " frequency=" + status.frequency))
        )

        if (
            latestState != null &&
                MediaTxPolicy.shouldReassertAfterRealSource(
                    status.off,
                    status.radio(),
                    UniversalMediaTxCoordinator.isRadioState(latestState),
                    UniversalMediaTxCoordinator.hasMediaText(latestState),
                    latestIdle,
                )
        ) {
            submit(latestState)
            recoveredRealSourceMediaKey = desiredMediaKey
        }
        return true
    }

    @kotlin.jvm.Synchronized
    fun hasRealRadioSource(): Boolean {
        return AppSettings.uartRealMediaProfile(app) &&
            realSource != null &&
            !realSource!!.off &&
            realSource!!.radio()
    }

    @kotlin.jvm.Synchronized
    fun hasRealSource(): Boolean {
        return AppSettings.uartRealMediaProfile(app) && realSource != null
    }

    @kotlin.jvm.Synchronized
    fun realRadioFrequency(): String? {
        return if (hasRealRadioSource())
            UniversalMediaTxCoordinator.cleanDisplay(realSource!!.frequency)
        else ""
    }

    @kotlin.jvm.Synchronized
    fun onIdle(state: MediaState?) {
        if (!MediaTxPolicy.usesSynchronizedTx(AppSettings.mediaProfile(app))) return
        latestState = if (state == null) MediaState.empty() else state
        latestIdle = true
        lastMediaKey = ""
        lastObservedPlaying = false
        reassertNextText = false
        beginGeneration("")
    }

    @kotlin.jvm.Synchronized
    fun onProfileChanged(profile: Int) {
        if (profile == activeProfile && MediaTxPolicy.usesSynchronizedTx(profile)) return
        resetInternal()
        activeProfile = profile
        if (profile != AppSettings.MEDIA_PROFILE_UART_REAL) {
            realSource = null
            realSourceKey = ""
        }
    }

    @kotlin.jvm.Synchronized
    fun suspendForCall() {
        cancelPending()
        desiredMediaKey = ""
        pendingMediaKey = ""
    }

    @kotlin.jvm.Synchronized
    fun resumeAfterCall(current: MediaState?) {
        lastMediaKey = ""
        desiredMediaKey = ""
        settleUntilElapsed = 0L
        cancelPending()
        if (current != null) submit(current)
    }

    @kotlin.jvm.Synchronized
    fun stop() {
        resetInternal()
        activeProfile = -1
        realSource = null
        realSourceKey = ""
    }

    private fun ensureProfile(profile: Int) {
        if (activeProfile == profile) return
        resetInternal()
        activeProfile = profile
        if (profile != AppSettings.MEDIA_PROFILE_UART_REAL) {
            realSource = null
            realSourceKey = ""
        }
    }

    private fun observeUsbState(ready: Boolean, epoch: Long) {
        if (!usbStateKnown) {
            usbStateKnown = true
            lastUsbReady = ready
            lastUsbEpoch = epoch
            return
        }
        if (!MediaTxPolicy.transportChanged(true, lastUsbReady, lastUsbEpoch, ready, epoch)) return
        lastUsbReady = ready
        lastUsbEpoch = epoch
        lastModeKey = ""
        lastSourceKey = ""
        lastMediaKey = ""
        lastObservedContentKey = ""
        lastObservedPlaying = false
        desiredMediaKey = ""
        offCompletedSourceKey = ""
        recoveredRealSourceMediaKey = ""
        settleUntilElapsed = 0L
        cancelPending()
        generation++
        appendJournal(
            "USB_STATE " +
                (if (ready) "ready epoch=" + epoch + "; resync"
                else "offline; waiting for current state")
        )
    }

    private fun sendAndroidSourceTransition(
        gateway: AdapterGateway,
        kind: MediaSourceKind,
        modeChanged: Boolean,
        sourceKey: String?,
        artist: String,
        title: String,
    ): Boolean {
        if (MediaTxPolicy.shouldSendMediaOff(modeChanged, sourceKey, offCompletedSourceKey)) {
            if (!tx(gateway, "media source off", AdapterProtocol.mediaOffStatus())) {
                return false
            }
            offCompletedSourceKey = sourceKey
        }
        if (UniversalMediaTxCoordinator.textOnlySource(kind)) {
            offCompletedSourceKey = ""
            return true
        }
        val sourceFrame: ByteArray? =
            if (UniversalMediaTxCoordinator.isRadioKind(kind))
                UniversalMediaTxCoordinator.radioSourceStatus(kind, artist, title)
            else AdapterProtocol.mediaSourceStatus(kind, title)
        if (!tx(gateway, "media source " + kind.name, sourceFrame)) return false
        offCompletedSourceKey = ""
        return true
    }

    private fun recoverAfterSameRealSource(status: RealMediaSourceStatus): Boolean {
        val currentMediaKey: String? =
            if (TextUtils.isEmpty(desiredMediaKey)) lastMediaKey else desiredMediaKey
        if (
            !MediaTxPolicy.shouldRecoverSameRealSource(
                status.off,
                status.radio(),
                UniversalMediaTxCoordinator.isRadioState(latestState),
                UniversalMediaTxCoordinator.hasMediaText(latestState),
                latestIdle,
                currentMediaKey,
                recoveredRealSourceMediaKey,
            )
        ) {
            return false
        }
        recoveredRealSourceMediaKey = currentMediaKey
        settleUntilElapsed =
            (SystemClock.elapsedRealtime() +
                AppSettings.mediaSourceDelayMs(app, AppSettings.MEDIA_PROFILE_UART_REAL))
        reassertNextText = false
        lastMediaKey = ""
        beginGeneration(currentMediaKey)
        appendJournal(("SOURCE_RX same " + status.kind!!.name + "; recover current text once"))
        submit(latestState)
        return true
    }

    private fun dispatchText(
        kind: MediaSourceKind?,
        state: MediaState?,
        mediaKey: String?,
        firstText: String?,
        trackText: String?,
        delayMs: Long,
    ) {
        if (firstText.isNullOrEmpty()) return
        val profile: Int = activeProfile
        val token: Int = generation
        val command: Int = UniversalMediaTxCoordinator.textCommand(kind)
        if (delayMs <= 0L) {
            transmitTextSequence(profile, token, command, mediaKey, firstText, trackText)
            return
        }
        if (TextUtils.equals(pendingMediaKey, mediaKey) && pendingFirstText != null) return
        if (pendingFirstText != null) handler.removeCallbacks(pendingFirstText!!)
        pendingMediaKey = mediaKey
        pendingFirstText = Runnable {
            synchronized(this@UniversalMediaTxCoordinator) {
                pendingFirstText = null
                pendingMediaKey = ""
                if (!valid(profile, token, mediaKey)) return@Runnable
                transmitTextSequence(profile, token, command, mediaKey, firstText, trackText)
            }
        }
        handler.postDelayed(pendingFirstText!!, delayMs)
    }

    private fun transmitTextSequence(
        profile: Int,
        token: Int,
        command: Int,
        mediaKey: String?,
        firstText: String?,
        trackText: String?,
    ) {
        if (!valid(profile, token, mediaKey)) return
        cancelTextTail()
        val gateway: AdapterGateway? = AdapterGateway.get(app)
        if (
            !tx(
                gateway!!,
                UniversalMediaTxCoordinator.textLabel(command, "first", firstText),
                AdapterProtocol.textPacket(command, firstText),
            )
        ) {
            lastMediaKey = ""
            return
        }
        lastMediaKey = mediaKey

        val artistDelayMs: Long = AppSettings.mediaArtistDelayMs(app).toLong()
        val differentTrack: Boolean =
            !trackText.isNullOrEmpty() && !TextUtils.equals(firstText, trackText)
        if (differentTrack) {
            pendingTrackText = Runnable {
                synchronized(this@UniversalMediaTxCoordinator) {
                    pendingTrackText = null
                    if (!valid(profile, token, mediaKey)) return@Runnable
                    if (
                        !tx(
                            AdapterGateway.get(app),
                            UniversalMediaTxCoordinator.textLabel(command, "track", trackText),
                            AdapterProtocol.textPacket(command, trackText),
                        )
                    ) {
                        lastMediaKey = ""
                    }
                }
            }
            if (artistDelayMs <= 0L) pendingTrackText!!.run()
            else handler.postDelayed(pendingTrackText!!, artistDelayMs)
        }

        if (reassertNextText) {
            reassertNextText = false
            val finalText: String? = if (differentTrack) trackText else firstText
            val repeatDelay: Long =
                Math.max(
                    UniversalMediaTxCoordinator.SOURCE_REASSERT_DELAY_MS,
                    (if (differentTrack) artistDelayMs else 0L) +
                        UniversalMediaTxCoordinator.SOURCE_REASSERT_DELAY_MS,
                )
            pendingReassertText = Runnable {
                synchronized(this@UniversalMediaTxCoordinator) {
                    pendingReassertText = null
                    if (!valid(profile, token, mediaKey)) return@Runnable
                    if (
                        !tx(
                            AdapterGateway.get(app),
                            UniversalMediaTxCoordinator.textLabel(command, "reassert", finalText),
                            AdapterProtocol.textPacket(command, finalText),
                        )
                    ) {
                        lastMediaKey = ""
                    }
                }
            }
            handler.postDelayed(pendingReassertText!!, repeatDelay)
        }
    }

    private fun valid(profile: Int, token: Int, mediaKey: String?): Boolean {
        if (
            !MediaTxPolicy.callbackCurrent(
                token,
                generation,
                profile,
                activeProfile,
                AppSettings.mediaProfile(app),
                StateStore.call().active,
                mediaKey,
                desiredMediaKey,
            )
        ) {
            return false
        }
        val gateway: AdapterGateway? = AdapterGateway.get(app)
        return MediaTxPolicy.transportCurrent(
            gateway!!.usbReady(),
            lastUsbEpoch,
            gateway.connectionEpoch(),
        )
    }

    private fun beginGeneration(mediaKey: String?) {
        val next: String = if (mediaKey == null) "" else mediaKey
        if (!TextUtils.equals(desiredMediaKey, next)) {
            recoveredRealSourceMediaKey = ""
        }
        cancelPending()
        generation++
        desiredMediaKey = next
        pendingMediaKey = ""
    }

    private fun cancelPending() {
        if (pendingFirstText != null) handler.removeCallbacks(pendingFirstText!!)
        pendingFirstText = null
        cancelTextTail()
        pendingMediaKey = ""
    }

    private fun cancelTextTail() {
        if (pendingTrackText != null) handler.removeCallbacks(pendingTrackText!!)
        if (pendingReassertText != null) handler.removeCallbacks(pendingReassertText!!)
        pendingTrackText = null
        pendingReassertText = null
    }

    private fun resetInternal() {
        cancelPending()
        generation++
        lastModeKey = ""
        lastSourceKey = ""
        lastMediaKey = ""
        lastObservedContentKey = ""
        lastObservedPlaying = false
        desiredMediaKey = ""
        offCompletedSourceKey = ""
        recoveredRealSourceMediaKey = ""
        settleUntilElapsed = 0L
        reassertNextText = false
        usbStateKnown = false
        lastUsbEpoch = -1L
        latestIdle = true
        latestState = MediaState.empty()
    }

    private fun tx(gateway: AdapterGateway, label: String, frame: ByteArray?): Boolean {
        val outcome: AdapterTxOutcome? = gateway.send(AdapterCommand.quiet(label, frame))
        StateStore.appendMediaTx(
            app,
            (UniversalMediaTxCoordinator.cleanDisplay(label) +
                " outcome=" +
                outcome +
                " bytes=" +
                AdapterProtocol.hex(frame)),
        )
        return outcome == AdapterTxOutcome.WRITTEN || outcome == AdapterTxOutcome.QUEUED
    }

    private fun appendJournal(value: String) {
        StateStore.appendMediaTx(app, UniversalMediaTxCoordinator.cleanDisplay(value))
    }

    private fun effectiveKind(profile: Int, state: MediaState): MediaSourceKind? {
        if (
            profile == AppSettings.MEDIA_PROFILE_UART_REAL &&
                realSource != null &&
                !realSource!!.off &&
                realSource!!.kind != MediaSourceKind.GENERIC_MUSIC
        ) {
            return realSource!!.kind
        }
        val detected: MediaSourceKind? =
            MediaTxPolicy.detectUniversalSource(state.source, state.packageName)
        return MediaTxPolicy.resolveOtherSource(detected, AppSettings.otherMediaSourceMode(app))
    }

    private fun sourceLabel(profile: Int, kind: MediaSourceKind, state: MediaState): String? {
        if (
            profile == AppSettings.MEDIA_PROFILE_UART_REAL &&
                realSource != null &&
                !TextUtils.isEmpty(realSource!!.source)
        ) {
            return realSource!!.source
        }
        return if (TextUtils.isEmpty(state.source)) kind.defaultLabel() else state.source
    }

    companion object {
        private const val SOURCE_REASSERT_DELAY_MS: Long = 1800L

        private fun isRadioState(state: MediaState?): Boolean {
            return state != null &&
                MediaTxPolicy.isPhysicalRadioSource(state.source, state.packageName)
        }

        private fun hasMediaText(state: MediaState?): Boolean {
            return state != null &&
                (!TextUtils.isEmpty(state.source) ||
                    !TextUtils.isEmpty(state.artist) ||
                    !TextUtils.isEmpty(state.title))
        }

        private fun textCommand(kind: MediaSourceKind?): Int {
            if (UniversalMediaTxCoordinator.isRadioKind(kind)) return AdapterProtocol.CMD_RADIO_TEXT
            if (kind == MediaSourceKind.USB_MUSIC) return AdapterProtocol.CMD_USB_TEXT
            if (kind == MediaSourceKind.ANDROID_AUTO) return AdapterProtocol.CMD_ANDROID_AUTO_TEXT
            if (kind == MediaSourceKind.CARPLAY) return AdapterProtocol.CMD_CARPLAY_TEXT
            if (kind == MediaSourceKind.MY_MUSIC) return AdapterProtocol.CMD_MY_MUSIC_TEXT
            if (kind == MediaSourceKind.BLUETOOTH_AUDIO || kind == MediaSourceKind.BT_PHONE) {
                return AdapterProtocol.CMD_MEDIA_TEXT
            }
            return AdapterProtocol.CMD_ANDROID_AUTO_TEXT
        }

        private fun textOnlySource(kind: MediaSourceKind?): Boolean {
            return kind == MediaSourceKind.ANDROID_AUTO ||
                kind == MediaSourceKind.CARPLAY ||
                kind == MediaSourceKind.MY_MUSIC
        }

        private fun isRadioKind(kind: MediaSourceKind?): Boolean {
            return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO
        }

        private fun radioSourceStatus(
            kind: MediaSourceKind?,
            artist: String?,
            title: String?,
        ): ByteArray? {
            if (kind == MediaSourceKind.FM_RADIO) {
                val frequency: String? = UniversalMediaTxCoordinator.firstFrequency(artist, title)
                return if (TextUtils.isEmpty(frequency))
                    AdapterProtocol.mediaFmSourceStatusNoFrequency()
                else AdapterProtocol.mediaSourceStatus(kind, frequency)
            }
            return AdapterProtocol.mediaSourceStatus(
                kind,
                UniversalMediaTxCoordinator.firstNonEmpty(artist, title),
            )
        }

        private fun radioHeaderText(
            source: String?,
            artist: String?,
            title: String?,
            frequency: String?,
        ): String? {
            val station: String? =
                UniversalMediaTxCoordinator.firstNonEmpty(
                    UniversalMediaTxCoordinator.nonFrequency(title),
                    UniversalMediaTxCoordinator.nonFrequency(artist),
                )
            if (!TextUtils.isEmpty(station)) return station
            return UniversalMediaTxCoordinator.firstNonEmpty(frequency, source)
        }

        private fun firstFrequency(first: String?, second: String?): String? {
            if (UniversalMediaTxCoordinator.looksLikeOnlyFrequency(first)) return first
            if (UniversalMediaTxCoordinator.looksLikeOnlyFrequency(second)) return second
            return ""
        }

        private fun nonFrequency(value: String?): String? {
            return if (UniversalMediaTxCoordinator.looksLikeOnlyFrequency(value)) "" else value
        }

        private fun looksLikeOnlyFrequency(value: String?): Boolean {
            if (value.isNullOrEmpty()) return false
            return value
                .trim()
                .matches(Regex("(?i)^(FM|AM)?\\s*\\d{2,4}([\\.,]\\d{1,2})?\\s*(MHz|kHz)?$"))
        }

        private fun textLabel(command: Int, stage: String?, text: String?): String {
            return ("media text " +
                UniversalMediaTxCoordinator.commandLabel(command) +
                " " +
                stage +
                ": \"" +
                UniversalMediaTxCoordinator.shortText(text) +
                "\"")
        }

        private fun commandLabel(command: Int): String? {
            return String.format(Locale.US, "0x%02X", command)
        }

        private fun shortText(value: String?): String {
            val clean = UniversalMediaTxCoordinator.cleanDisplay(value)
            return if (clean.length <= 42) clean else clean.substring(0, 39) + "..."
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
