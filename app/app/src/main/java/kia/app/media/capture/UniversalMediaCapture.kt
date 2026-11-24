package kia.app.media.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import kia.app.core.settings.AppSettings
import kia.app.media.domain.MediaFeature
import kia.app.media.domain.RadioStationStore

internal class UniversalMediaCapture(context: Context) {
    private val app: Context
    private val androidMedia: AndroidMediaSessionClient?
    private val radioMedia: SpdRadioServiceClient?
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var running: Boolean = false

    private val poll: Runnable =
        object : Runnable {
            override fun run() {
                if (!running) return
                scanNow()
                handler.postDelayed(this, UniversalMediaCapture.POLL_MS)
            }
        }

    init {
        this.app = context.applicationContext ?: context
        this.androidMedia = AndroidMediaSessionClient.get(app)
        this.radioMedia = SpdRadioServiceClient.get(app)
    }

    fun start() {
        if (running) return
        running = true
        radioMedia!!.start()
        handler.post(poll)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        radioMedia!!.stop()
    }

    fun scanNow(): Boolean {
        if (!AppSettings.universalMediaProfile(app)) return false
        val mediaFeature: MediaFeature? = MediaFeature.get(app)
        val uartHasRealSource: Boolean =
            AppSettings.uartRealMediaProfile(app) && mediaFeature!!.hasRealSource()
        if (uartHasRealSource && mediaFeature.hasRealRadioSource()) {
            val radio: SpdRadioServiceClient.Snapshot? = radioMedia!!.readSnapshot()
            if (
                radio != null &&
                    radio.hasFrequency() &&
                    radio.isCurrent() &&
                    UniversalMediaCapture.matchesRealRadioFrequency(
                        radio.source(),
                        mediaFeature.realRadioFrequency(),
                        radio.frequencyText(),
                    )
            ) {
                reportRadio(radio)
            }
            // The head unit's real FM/AM source remains authoritative until another 0x7A source
            // arrives. A stale Android PLAYING session must not overwrite it on the next poll.
            return true
        }

        if (!uartHasRealSource) {
            val radio: SpdRadioServiceClient.Snapshot? = radioMedia!!.readSnapshot()
            if (radio != null && radio.hasFrequency() && radio.isCurrent()) {
                reportRadio(radio)
                return true
            }
        }
        val session: AndroidMediaSessionClient.Snapshot? = androidMedia!!.readUniversalSnapshot()
        if (session != null && session.isPlaying()) {
            reportAndroidMediaSession(session)
            return true
        }
        if (session != null) {
            reportAndroidMediaSession(session)
            return true
        }
        MediaFeature.get(app).reportIdle("", "universal media empty")
        return false
    }

    private fun reportAndroidMediaSession(snapshot: AndroidMediaSessionClient.Snapshot?) {
        if (snapshot == null) return
        MediaFeature.get(app)
            .report(
                null,
                snapshot.packageName!!,
                snapshot.artist,
                snapshot.title,
                snapshot.remainingMs,
                snapshot.playing,
            )
    }

    private fun reportRadio(radio: SpdRadioServiceClient.Snapshot) {
        val frequency: String? = radio.frequencyText()
        if (TextUtils.isEmpty(frequency)) return
        val source: String = radio.source()!!
        val station: String? =
            RadioStationStore.resolveUniversal(
                app,
                source,
                frequency,
                radio.stationNameForUniversal(),
            )
        MediaFeature.get(app)
            .report(
                source,
                SpdRadioServiceClient.PACKAGE,
                frequency,
                station,
                -1L,
                radio.isPlaying(),
            )
    }

    companion object {
        private const val POLL_MS: Long = 1000L

        @JvmStatic
        fun matchesRealRadioFrequency(
            source: String?,
            realFrequency: String?,
            snapshotFrequency: String?,
        ): Boolean {
            val expected: String? = RadioStationStore.normalizeFrequencyInput(source, realFrequency)
            val actual: String? =
                RadioStationStore.normalizeFrequencyInput(source, snapshotFrequency)
            return expected!!.isEmpty() || expected.equals(actual)
        }
    }
}
