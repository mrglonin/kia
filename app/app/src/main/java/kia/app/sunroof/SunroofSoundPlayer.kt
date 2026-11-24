package kia.app.sunroof

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kia.app.R
import kia.app.core.AppLog

/** Finite roof speech and ADAS cues. Main thread only; playback never restarts itself. */
class SunroofSoundPlayer internal constructor(scheduler: Scheduler?, factory: Factory?) {
    internal interface Scheduler {
        fun now(): Long

        fun postDelayed(runnable: Runnable?, delayMs: Long)

        fun remove(runnable: Runnable?)
    }

    internal interface Clip {
        fun start()

        fun close()
    }

    internal interface Factory {
        fun create(resource: Int, complete: Runnable, error: Runnable): Clip?
    }

    private val scheduler: Scheduler
    private val factory: Factory
    private var expiration: Runnable? = null
    private var enabled: Boolean = true
    private var generation: Long = 0
    private var clipToken: Long = 0
    private var deadline: Long = 0
    private var player: Clip? = null

    constructor(context: Context) : this(MainScheduler(), AndroidFactory(context))

    init {
        require(!(scheduler == null || factory == null)) { "scheduler/factory" }
        this.scheduler = scheduler
        this.factory = factory
    }

    /** Compatibility with callers that previously distinguished only transition/warning. */
    fun play(warning: Boolean) {
        play(
            if (warning) kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING
            else kia.app.sunroof.SunroofAlertPolicy.Kind.OPENING
        )
    }

    /** One phrase for a transition, or one combined ADAS signal and roof-warning phrase. */
    fun play(kind: kia.app.sunroof.SunroofAlertPolicy.Kind?) {
        stop()
        if (!enabled || kind == null || kind == kia.app.sunroof.SunroofAlertPolicy.Kind.NONE) return
        val warning: Boolean = kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING
        val resource: Int =
            if (warning) R.raw.sunroof_warning_adas
            else if (kind == kia.app.sunroof.SunroofAlertPolicy.Kind.OPENING)
                R.raw.sunroof_open_voice
            else R.raw.sunroof_close_voice
        val current: Long = generation
        val now: Long = scheduler.now()
        deadline =
            now +
                (if (warning) SunroofSoundPlayer.WARNING_WINDOW_MS
                else SunroofSoundPlayer.TRANSITION_WINDOW_MS)
        expiration = Runnable { if (current == generation && enabled) stop() }
        scheduler.postDelayed(
            expiration,
            if (warning) SunroofSoundPlayer.WARNING_WINDOW_MS
            else SunroofSoundPlayer.TRANSITION_WINDOW_MS,
        )
        startClip(current, resource)
    }

    /** Cancels the clip, its deadline and callbacks from the previous presentation immediately. */
    fun stop() {
        generation++
        if (expiration != null) scheduler.remove(expiration)
        expiration = null
        releasePlayer()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) stop()
    }

    private fun startClip(current: Long, resource: Int) {
        if (current != generation || !enabled) return
        val now: Long = scheduler.now()
        if (now >= deadline) {
            stop()
            return
        }
        val token: Long = ++clipToken
        try {
            val created =
                factory.create(
                    resource,
                    Runnable { finished(current, token) },
                    Runnable { finished(current, token) },
                )
            if (current != generation || !enabled) {
                SunroofSoundPlayer.close(created)
                return
            }
            if (scheduler.now() >= deadline) {
                SunroofSoundPlayer.close(created)
                stop()
                return
            }
            player = created
            if (created != null) created.start() else stop()
        } catch (error: RuntimeException) {
            // A failed cue is consumed; it must never become an automatic retry.
            stop()
        }
    }

    private fun finished(current: Long, token: Long) {
        if (current == generation && token == clipToken) stop()
    }

    private fun releasePlayer() {
        val previous = player
        player = null
        SunroofSoundPlayer.close(previous)
    }

    private class MainScheduler : Scheduler {
        private val handler: Handler = Handler(Looper.getMainLooper())

        override fun now(): Long {
            return SystemClock.elapsedRealtime()
        }

        override fun postDelayed(runnable: Runnable?, delayMs: Long) {
            handler.postDelayed(runnable!!, delayMs)
        }

        override fun remove(runnable: Runnable?) {
            handler.removeCallbacks(runnable!!)
        }
    }

    private class AndroidFactory(context: Context) : Factory {
        private val app: Context

        init {
            requireNotNull(context) { "context" }
            val application = context.getApplicationContext()
            app = if (application == null) context else application
        }

        override fun create(resource: Int, complete: Runnable, error: Runnable): Clip? {
            var media: MediaPlayer? = null
            try {
                val attributes =
                    AudioAttributes
                        .Builder() // TEYES also reads the explicit legacy stream field; usage alone
                        // leaves its vendor focus callback at stream=-1.
                        .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                // Each clip begins with 1200 ms of silent PCM so TEYES can wake its
                // I2S route/amplifier before the original recording starts. One stream
                // keeps that warm-up and speech under the same cancellation boundary.
                media = MediaPlayer.create(app, resource, attributes, 0)
                if (media == null) {
                    log(
                        "create_error resource=" +
                            AndroidFactory.resourceName(resource) +
                            " player=null"
                    )
                    return null
                }
                val created: MediaPlayer = media
                created.setLooping(false)
                val clip: AndroidClip = AndroidClip(created, attributes, resource, error)
                created.setOnCompletionListener({ ignored -> clip.finish("completion", complete) })
                created.setOnErrorListener({ ignored, what, extra ->
                    clip.finish("error what=" + what + " extra=" + extra, error)
                    true
                })
                return clip
            } catch (failure: RuntimeException) {
                if (media != null) {
                    try {
                        media.release()
                    } catch (ignored: RuntimeException) {}
                }
                log(
                    ("create_error resource=" +
                        AndroidFactory.resourceName(resource) +
                        " exception=" +
                        failure.javaClass.getSimpleName())
                )
                return null
            }
        }

        private inner class AndroidClip(
            media: MediaPlayer,
            attributes: AudioAttributes?,
            resource: Int,
            error: Runnable?,
        ) : Clip {
            private val media: MediaPlayer?
            private val audio: AudioManager?
            private val focus: AudioFocusRequest?
            private val error: Runnable?
            private val resource: Int
            private val durationMs: Int
            private var wakeLock: PowerManager.WakeLock? = null
            private var focusRequested: Boolean = false
            private var closed: Boolean = false
            private var startedAt: Long = -1L
            private var endReason: String? = "cancel"

            init {
                this.media = media
                this.resource = resource
                this.error = error
                durationMs = media.getDuration()
                audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
                focus =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attributes!!)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(
                            { change ->
                                if (closed) return@setOnAudioFocusChangeListener
                                log(
                                    "focus_change resource=" +
                                        AndroidFactory.resourceName(resource) +
                                        " change=" +
                                        change
                                )
                                if (change < 0) finish("focus_loss change=" + change, this.error!!)
                            },
                            Handler(Looper.getMainLooper()),
                        )
                        .build()
            }

            override fun start() {
                if (closed) return
                try {
                    focusRequested = audio != null
                    val result: Int =
                        if (audio == null) AudioManager.AUDIOFOCUS_REQUEST_FAILED
                        else audio.requestAudioFocus(focus!!)
                    log(
                        "focus resource=" +
                            AndroidFactory.resourceName(resource) +
                            " result=" +
                            result
                    )
                    if (closed) return
                    if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        finish("focus_denied result=" + result, error!!)
                        return
                    }
                    val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager?
                    if (power != null) {
                        wakeLock =
                            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kia:SunroofAudio")
                        wakeLock!!.setReferenceCounted(false)
                        wakeLock!!.acquire(
                            if (resource == R.raw.sunroof_warning_adas)
                                SunroofSoundPlayer.WARNING_WINDOW_MS
                            else SunroofSoundPlayer.TRANSITION_WINDOW_MS
                        )
                    }
                    startedAt = SystemClock.elapsedRealtime()
                    media!!.start()
                    log(
                        ("start resource=" +
                            AndroidFactory.resourceName(resource) +
                            " durationMs=" +
                            durationMs +
                            " wake=" +
                            (wakeLock != null && wakeLock!!.isHeld()))
                    )
                } catch (failure: RuntimeException) {
                    finish("start_error exception=" + failure.javaClass.getSimpleName(), error!!)
                }
            }

            fun finish(reason: String?, callback: Runnable) {
                if (closed) return
                endReason = reason
                try {
                    callback.run()
                } finally {
                    // Even a stale outer-generation callback must release its own resources.
                    close()
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                var positionMs: Int = -1
                try {
                    positionMs = media!!.getCurrentPosition()
                } catch (ignored: RuntimeException) {}
                log(
                    (endReason.toString() +
                        " resource=" +
                        AndroidFactory.resourceName(resource) +
                        " durationMs=" +
                        durationMs +
                        " playedMs=" +
                        (if (startedAt < 0L) 0L else SystemClock.elapsedRealtime() - startedAt) +
                        " positionMs=" +
                        positionMs)
                )
                try {
                    media!!.setOnCompletionListener(null)
                    media.setOnErrorListener(null)
                } catch (ignored: RuntimeException) {}
                try {
                    media!!.stop()
                } catch (ignored: RuntimeException) {}
                try {
                    media!!.release()
                } catch (ignored: RuntimeException) {}
                if (focusRequested && audio != null) {
                    focusRequested = false
                    try {
                        log(
                            ("focus_release resource=" +
                                AndroidFactory.resourceName(resource) +
                                " result=" +
                                audio.abandonAudioFocusRequest(focus!!))
                        )
                    } catch (failure: RuntimeException) {
                        log(
                            ("focus_release_error resource=" +
                                AndroidFactory.resourceName(resource) +
                                " exception=" +
                                failure.javaClass.getSimpleName())
                        )
                    }
                }
                if (wakeLock != null) {
                    val held: PowerManager.WakeLock = wakeLock!!
                    wakeLock = null
                    try {
                        if (held.isHeld()) held.release()
                        log("wake_release resource=" + AndroidFactory.resourceName(resource))
                    } catch (failure: RuntimeException) {
                        log(
                            ("wake_release_error resource=" +
                                AndroidFactory.resourceName(resource) +
                                " exception=" +
                                failure.javaClass.getSimpleName())
                        )
                    }
                }
            }
        }

        fun log(text: String?) {
            try {
                AppLog.line(app, "Sunroof audio " + text)
            } catch (ignored: RuntimeException) {}
        }

        companion object {
            private fun resourceName(resource: Int): String? {
                return if (resource == R.raw.sunroof_warning_adas) "sunroof_warning_adas"
                else if (resource == R.raw.sunroof_open_voice) "sunroof_open_voice"
                else if (resource == R.raw.sunroof_close_voice) "sunroof_close_voice"
                else java.lang.String.valueOf(resource)
            }
        }
    }

    companion object {
        const val WARNING_WINDOW_MS: Long = 9500L
        const val TRANSITION_WINDOW_MS: Long = 4500L

        private fun close(clip: Clip?) {
            if (clip == null) return
            try {
                clip.close()
            } catch (ignored: RuntimeException) {}
        }
    }
}
