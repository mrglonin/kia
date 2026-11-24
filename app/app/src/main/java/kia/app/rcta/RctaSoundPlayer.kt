package kia.app.rcta

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kia.app.R
import kia.app.core.AppLog
import kia.app.core.settings.AppSettings

/** Plays the same warning in the live overlay and the in-app demonstration. Main thread only. */
class RctaSoundPlayer(context: Context, liveWarning: Boolean) {
    private val app: Context
    private val liveWarning: Boolean
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val nextVoice: Runnable = Runnable { this.playNextVoice() }
    private var legacyPlayer: MediaPlayer? = null
    private var leftPlayer: MediaPlayer? = null
    private var rightPlayer: MediaPlayer? = null
    private var voicePlayer: MediaPlayer? = null
    private var leftActive: Boolean = false
    private var rightActive: Boolean = false
    private var voiceQueued: Boolean = false
    private var activeStyle: Int = -1
    private var activeMode: Int = -1
    private var voiceSide: Int = RctaSoundPlayer.SIDE_NONE
    private var lastVoiceSide: Int = RctaSoundPlayer.SIDE_NONE

    constructor(context: Context) : this(context, false)

    init {
        app = context.applicationContext ?: context
        this.liveWarning = liveWarning
    }

    fun apply(style: Int, mode: Int, left: Boolean, right: Boolean, enabled: Boolean) {
        if (!enabled || (!left && !right)) {
            stop()
            return
        }
        if (activeStyle != style || activeMode != mode) {
            stopVoice()
            stopTones()
            lastVoiceSide = RctaSoundPlayer.SIDE_NONE
            // Retain live speech priority for a style change within voice mode.
            if (mode != AppSettings.RCTA_SOUND_VOICE) releaseVoiceOwnership()
        }
        activeStyle = style
        activeMode = mode
        leftActive = left
        rightActive = right
        if (mode == AppSettings.RCTA_SOUND_VOICE) {
            stopTones()
            if (!acquireVoiceOwnership()) return
            // Adding another hazard does not interrupt the current phrase. Clearing the
            // side being spoken stops it immediately; the remaining side can speak now.
            if (voiceSide != RctaSoundPlayer.SIDE_NONE && !isSideActive(voiceSide)) stopVoice()
            if (voicePlayer == null && !voiceQueued) playNextVoice()
            return
        }
        stopVoice()
        releaseVoiceOwnership()
        if (style == AppSettings.RCTA_STYLE_TEYES) {
            legacyPlayer = RctaSoundPlayer.release(legacyPlayer)
            // Original stereo WAVs contain a signal only in their named channel.
            // Both hazards play both files; a cleared side is released immediately.
            leftPlayer = update(leftPlayer, R.raw.teyes_rear_left_warning, left)
            rightPlayer = update(rightPlayer, R.raw.teyes_rear_right_warning, right)
        } else {
            leftPlayer = RctaSoundPlayer.release(leftPlayer)
            rightPlayer = RctaSoundPlayer.release(rightPlayer)
            legacyPlayer = update(legacyPlayer, R.raw.rcta_warning, true)
        }
    }

    fun stop() {
        leftActive = false
        rightActive = false
        activeStyle = -1
        activeMode = -1
        lastVoiceSide = RctaSoundPlayer.SIDE_NONE
        stopVoice()
        releaseVoiceOwnership()
        stopTones()
    }

    private fun voiceRequested(): Boolean {
        return activeMode == AppSettings.RCTA_SOUND_VOICE && (leftActive || rightActive)
    }

    private fun acquireVoiceOwnership(): Boolean {
        if (RctaSoundPlayer.voiceOwner == this) return true
        if (RctaSoundPlayer.voiceOwner != null) {
            if (!liveWarning) {
                stopVoice()
                RctaSoundPlayer.waitingDemo = this
                return false
            }
            val previous: RctaSoundPlayer = RctaSoundPlayer.voiceOwner!!
            previous.stopVoice()
            if (!previous.liveWarning && previous.voiceRequested())
                RctaSoundPlayer.waitingDemo = previous
        }
        RctaSoundPlayer.voiceOwner = this
        if (RctaSoundPlayer.waitingDemo == this) RctaSoundPlayer.waitingDemo = null
        return true
    }

    private fun releaseVoiceOwnership() {
        if (RctaSoundPlayer.waitingDemo == this) RctaSoundPlayer.waitingDemo = null
        if (RctaSoundPlayer.voiceOwner != this) return
        RctaSoundPlayer.voiceOwner = null
        val pending: RctaSoundPlayer? = RctaSoundPlayer.waitingDemo
        RctaSoundPlayer.waitingDemo = null
        if (
            pending != null &&
                pending.voiceRequested() &&
                AppSettings.rctaSoundEnabled(pending.app) &&
                AppSettings.rctaSoundMode(pending.app) == AppSettings.RCTA_SOUND_VOICE
        ) {
            pending.apply(
                AppSettings.rctaStyle(pending.app),
                AppSettings.RCTA_SOUND_VOICE,
                pending.leftActive,
                pending.rightActive,
                true,
            )
        }
    }

    private fun stopTones() {
        legacyPlayer = RctaSoundPlayer.release(legacyPlayer)
        leftPlayer = RctaSoundPlayer.release(leftPlayer)
        rightPlayer = RctaSoundPlayer.release(rightPlayer)
    }

    private fun stopVoice() {
        handler.removeCallbacks(nextVoice)
        voiceQueued = false
        val previous: MediaPlayer? = voicePlayer
        voicePlayer = null
        voiceSide = RctaSoundPlayer.SIDE_NONE
        RctaSoundPlayer.release(previous)
    }

    private fun isSideActive(side: Int): Boolean {
        return if (side == RctaSoundPlayer.SIDE_LEFT) leftActive
        else side == RctaSoundPlayer.SIDE_RIGHT && rightActive
    }

    private fun playNextVoice() {
        voiceQueued = false
        if (
            activeMode != AppSettings.RCTA_SOUND_VOICE ||
                (!leftActive && !rightActive) ||
                voicePlayer != null ||
                RctaSoundPlayer.voiceOwner != this
        )
            return
        val side: Int =
            if (leftActive && rightActive)
                (if (lastVoiceSide == RctaSoundPlayer.SIDE_LEFT) RctaSoundPlayer.SIDE_RIGHT
                else RctaSoundPlayer.SIDE_LEFT)
            else (if (leftActive) RctaSoundPlayer.SIDE_LEFT else RctaSoundPlayer.SIDE_RIGHT)
        var current: MediaPlayer? = null
        try {
            current =
                MediaPlayer.create(
                    app,
                    if (side == RctaSoundPlayer.SIDE_LEFT) R.raw.teyes_rear_left_warning_voice
                    else R.raw.teyes_rear_right_warning_voice,
                )
            if (current == null) {
                queueVoice(RctaSoundPlayer.VOICE_RETRY_MS)
                return
            }
            voicePlayer = current
            voiceSide = side
            current.setOnCompletionListener({ completed -> finishVoice(completed, false) })
            current.setOnErrorListener({ failed, what, extra ->
                finishVoice(failed, true)
                true
            })
            current.start()
        } catch (e: Exception) {
            AppLog.line(app, "RCTA voice failed " + e.javaClass.getSimpleName())
            if (current === voicePlayer) {
                voicePlayer = null
                voiceSide = RctaSoundPlayer.SIDE_NONE
            }
            RctaSoundPlayer.release(current)
            queueVoice(RctaSoundPlayer.VOICE_RETRY_MS)
        }
    }

    private fun finishVoice(completed: MediaPlayer?, failed: Boolean) {
        // Ignore callbacks from a player cancelled by mute, side, style or mode changes.
        if (voicePlayer !== completed) return
        lastVoiceSide = voiceSide
        voicePlayer = null
        voiceSide = RctaSoundPlayer.SIDE_NONE
        RctaSoundPlayer.release(completed)
        queueVoice(if (failed) RctaSoundPlayer.VOICE_RETRY_MS else RctaSoundPlayer.VOICE_PAUSE_MS)
    }

    private fun queueVoice(delayMs: Long) {
        handler.removeCallbacks(nextVoice)
        voiceQueued = activeMode == AppSettings.RCTA_SOUND_VOICE && (leftActive || rightActive)
        if (voiceQueued) handler.postDelayed(nextVoice, delayMs)
    }

    private fun update(current: MediaPlayer?, resource: Int, active: Boolean): MediaPlayer? {
        var current: MediaPlayer? = current
        if (!active) return RctaSoundPlayer.release(current)
        try {
            if (current == null) {
                current = MediaPlayer.create(app, resource)
                if (current == null) return null
                current.setLooping(true)
            }
            // State broadcasts and identical RCTA heartbeats must not restart a playing clip.
            if (!current.isPlaying()) current.start()
            return current
        } catch (e: Exception) {
            AppLog.line(app, "RCTA sound failed " + e.javaClass.getSimpleName())
            return RctaSoundPlayer.release(current)
        }
    }

    companion object {
        private const val VOICE_PAUSE_MS: Long = 350L
        private const val VOICE_RETRY_MS: Long = 1500L
        private const val SIDE_NONE: Int = 0
        private const val SIDE_LEFT: Int = 1
        private const val SIDE_RIGHT: Int = 2
        // All callers run on the main thread. A real warning owns speech until it clears,
        // including pauses between phrases, so diagnostics cannot talk over it.
        private var voiceOwner: RctaSoundPlayer? = null
        private var waitingDemo: RctaSoundPlayer? = null

        private fun release(player: MediaPlayer?): MediaPlayer? {
            if (player == null) return null
            try {
                player.setOnCompletionListener(null)
                player.setOnErrorListener(null)
            } catch (ignored: Exception) {}
            try {
                player.stop()
            } catch (ignored: Exception) {}
            try {
                player.release()
            } catch (ignored: Exception) {}
            return null
        }
    }
}
