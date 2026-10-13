package kia.app.rcta;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import kia.app.R;
import kia.app.core.AppLog;
import kia.app.core.settings.AppSettings;

/** Plays the same warning in the live overlay and the in-app demonstration. Main thread only. */
public final class RctaSoundPlayer {
    private static final long VOICE_PAUSE_MS = 350L;
    private static final long VOICE_RETRY_MS = 1500L;
    private static final int SIDE_NONE = 0;
    private static final int SIDE_LEFT = 1;
    private static final int SIDE_RIGHT = 2;
    // All callers run on the main thread. A real warning owns speech until it clears,
    // including pauses between phrases, so diagnostics cannot talk over it.
    private static RctaSoundPlayer voiceOwner;
    private static RctaSoundPlayer waitingDemo;

    private final Context app;
    private final boolean liveWarning;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable nextVoice = this::playNextVoice;
    private MediaPlayer legacyPlayer;
    private MediaPlayer leftPlayer;
    private MediaPlayer rightPlayer;
    private MediaPlayer voicePlayer;
    private boolean leftActive;
    private boolean rightActive;
    private boolean voiceQueued;
    private int activeStyle = -1;
    private int activeMode = -1;
    private int voiceSide = SIDE_NONE;
    private int lastVoiceSide = SIDE_NONE;

    public RctaSoundPlayer(Context context) {
        this(context, false);
    }

    public RctaSoundPlayer(Context context, boolean liveWarning) {
        app = context.getApplicationContext();
        this.liveWarning = liveWarning;
    }

    public void apply(int style, int mode, boolean left, boolean right, boolean enabled) {
        if (!enabled || (!left && !right)) {
            stop();
            return;
        }
        if (activeStyle != style || activeMode != mode) {
            stopVoice();
            stopTones();
            lastVoiceSide = SIDE_NONE;
            // Retain live speech priority for a style change within voice mode.
            if (mode != AppSettings.RCTA_SOUND_VOICE) releaseVoiceOwnership();
        }
        activeStyle = style;
        activeMode = mode;
        leftActive = left;
        rightActive = right;
        if (mode == AppSettings.RCTA_SOUND_VOICE) {
            stopTones();
            if (!acquireVoiceOwnership()) return;
            // Adding another hazard does not interrupt the current phrase. Clearing the
            // side being spoken stops it immediately; the remaining side can speak now.
            if (voiceSide != SIDE_NONE && !isSideActive(voiceSide)) stopVoice();
            if (voicePlayer == null && !voiceQueued) playNextVoice();
            return;
        }
        stopVoice();
        releaseVoiceOwnership();
        if (style == AppSettings.RCTA_STYLE_TEYES) {
            legacyPlayer = release(legacyPlayer);
            // Original stereo WAVs contain a signal only in their named channel.
            // Both hazards play both files; a cleared side is released immediately.
            leftPlayer = update(leftPlayer, R.raw.teyes_rear_left_warning, left);
            rightPlayer = update(rightPlayer, R.raw.teyes_rear_right_warning, right);
        } else {
            leftPlayer = release(leftPlayer);
            rightPlayer = release(rightPlayer);
            legacyPlayer = update(legacyPlayer, R.raw.rcta_warning, true);
        }
    }

    public void stop() {
        leftActive = false;
        rightActive = false;
        activeStyle = -1;
        activeMode = -1;
        lastVoiceSide = SIDE_NONE;
        stopVoice();
        releaseVoiceOwnership();
        stopTones();
    }

    private boolean voiceRequested() {
        return activeMode == AppSettings.RCTA_SOUND_VOICE && (leftActive || rightActive);
    }

    private boolean acquireVoiceOwnership() {
        if (voiceOwner == this) return true;
        if (voiceOwner != null) {
            if (!liveWarning) {
                stopVoice();
                waitingDemo = this;
                return false;
            }
            RctaSoundPlayer previous = voiceOwner;
            previous.stopVoice();
            if (!previous.liveWarning && previous.voiceRequested()) waitingDemo = previous;
        }
        voiceOwner = this;
        if (waitingDemo == this) waitingDemo = null;
        return true;
    }

    private void releaseVoiceOwnership() {
        if (waitingDemo == this) waitingDemo = null;
        if (voiceOwner != this) return;
        voiceOwner = null;
        RctaSoundPlayer pending = waitingDemo;
        waitingDemo = null;
        if (pending != null && pending.voiceRequested()
                && AppSettings.rctaSoundEnabled(pending.app)
                && AppSettings.rctaSoundMode(pending.app) == AppSettings.RCTA_SOUND_VOICE) {
            pending.apply(AppSettings.rctaStyle(pending.app), AppSettings.RCTA_SOUND_VOICE,
                    pending.leftActive, pending.rightActive, true);
        }
    }

    private void stopTones() {
        legacyPlayer = release(legacyPlayer);
        leftPlayer = release(leftPlayer);
        rightPlayer = release(rightPlayer);
    }

    private void stopVoice() {
        handler.removeCallbacks(nextVoice);
        voiceQueued = false;
        MediaPlayer previous = voicePlayer;
        voicePlayer = null;
        voiceSide = SIDE_NONE;
        release(previous);
    }

    private boolean isSideActive(int side) {
        return side == SIDE_LEFT ? leftActive : side == SIDE_RIGHT && rightActive;
    }

    private void playNextVoice() {
        voiceQueued = false;
        if (activeMode != AppSettings.RCTA_SOUND_VOICE || (!leftActive && !rightActive)
                || voicePlayer != null || voiceOwner != this) return;
        int side = leftActive && rightActive
                ? (lastVoiceSide == SIDE_LEFT ? SIDE_RIGHT : SIDE_LEFT)
                : (leftActive ? SIDE_LEFT : SIDE_RIGHT);
        MediaPlayer current = null;
        try {
            current = MediaPlayer.create(app, side == SIDE_LEFT
                    ? R.raw.teyes_rear_left_warning_voice : R.raw.teyes_rear_right_warning_voice);
            if (current == null) {
                queueVoice(VOICE_RETRY_MS);
                return;
            }
            voicePlayer = current;
            voiceSide = side;
            current.setOnCompletionListener(completed -> finishVoice(completed, false));
            current.setOnErrorListener((failed, what, extra) -> {
                finishVoice(failed, true);
                return true;
            });
            current.start();
        } catch (Exception e) {
            AppLog.line(app, "RCTA voice failed " + e.getClass().getSimpleName());
            if (current == voicePlayer) {
                voicePlayer = null;
                voiceSide = SIDE_NONE;
            }
            release(current);
            queueVoice(VOICE_RETRY_MS);
        }
    }

    private void finishVoice(MediaPlayer completed, boolean failed) {
        // Ignore callbacks from a player cancelled by mute, side, style or mode changes.
        if (voicePlayer != completed) return;
        lastVoiceSide = voiceSide;
        voicePlayer = null;
        voiceSide = SIDE_NONE;
        release(completed);
        queueVoice(failed ? VOICE_RETRY_MS : VOICE_PAUSE_MS);
    }

    private void queueVoice(long delayMs) {
        handler.removeCallbacks(nextVoice);
        voiceQueued = activeMode == AppSettings.RCTA_SOUND_VOICE && (leftActive || rightActive);
        if (voiceQueued) handler.postDelayed(nextVoice, delayMs);
    }

    private MediaPlayer update(MediaPlayer current, int resource, boolean active) {
        if (!active) return release(current);
        try {
            if (current == null) {
                current = MediaPlayer.create(app, resource);
                if (current == null) return null;
                current.setLooping(true);
            }
            // State broadcasts and identical RCTA heartbeats must not restart a playing clip.
            if (!current.isPlaying()) current.start();
            return current;
        } catch (Exception e) {
            AppLog.line(app, "RCTA sound failed " + e.getClass().getSimpleName());
            return release(current);
        }
    }

    private static MediaPlayer release(MediaPlayer player) {
        if (player == null) return null;
        try {
            player.setOnCompletionListener(null);
            player.setOnErrorListener(null);
        } catch (Exception ignored) {
        }
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
        return null;
    }
}
