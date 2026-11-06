package kia.app.sunroof;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import kia.app.R;
import kia.app.core.AppLog;

/** Finite roof speech and ADAS cues. Main thread only; playback never restarts itself. */
public final class SunroofSoundPlayer {
    static final long WARNING_WINDOW_MS = 9_500L;
    static final long TRANSITION_WINDOW_MS = 4_500L;

    interface Scheduler {
        long now();
        void postDelayed(Runnable runnable, long delayMs);
        void remove(Runnable runnable);
    }

    interface Clip {
        void start();
        void close();
    }

    interface Factory {
        Clip create(int resource, Runnable complete, Runnable error);
    }

    private final Scheduler scheduler;
    private final Factory factory;
    private Runnable expiration;
    private boolean enabled = true;
    private long generation;
    private long clipToken;
    private long deadline;
    private Clip player;

    public SunroofSoundPlayer(Context context) {
        this(new MainScheduler(), new AndroidFactory(context));
    }

    SunroofSoundPlayer(Scheduler scheduler, Factory factory) {
        if (scheduler == null || factory == null) throw new IllegalArgumentException("scheduler/factory");
        this.scheduler = scheduler;
        this.factory = factory;
    }

    /** Compatibility with callers that previously distinguished only transition/warning. */
    public void play(boolean warning) {
        play(warning ? SunroofAlertPolicy.Kind.PARKING_WARNING : SunroofAlertPolicy.Kind.OPENING);
    }

    /** One phrase for a transition, or one combined ADAS signal and roof-warning phrase. */
    public void play(SunroofAlertPolicy.Kind kind) {
        stop();
        if (!enabled || kind == null || kind == SunroofAlertPolicy.Kind.NONE) return;
        boolean warning = kind == SunroofAlertPolicy.Kind.PARKING_WARNING;
        int resource = warning ? R.raw.sunroof_warning_adas
                : kind == SunroofAlertPolicy.Kind.OPENING
                ? R.raw.sunroof_open_voice : R.raw.sunroof_close_voice;
        long current = generation;
        long now = scheduler.now();
        deadline = now + (warning ? WARNING_WINDOW_MS : TRANSITION_WINDOW_MS);
        expiration = () -> {
            if (current == generation && enabled) stop();
        };
        scheduler.postDelayed(expiration, warning ? WARNING_WINDOW_MS : TRANSITION_WINDOW_MS);
        startClip(current, resource);
    }

    /** Cancels the clip, its deadline and callbacks from the previous presentation immediately. */
    public void stop() {
        generation++;
        if (expiration != null) scheduler.remove(expiration);
        expiration = null;
        releasePlayer();
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!value) stop();
    }

    private void startClip(long current, int resource) {
        if (current != generation || !enabled) return;
        long now = scheduler.now();
        if (now >= deadline) {
            stop();
            return;
        }
        long token = ++clipToken;
        try {
            Clip created = factory.create(resource, () -> finished(current, token),
                    () -> finished(current, token));
            if (current != generation || !enabled) {
                close(created);
                return;
            }
            if (scheduler.now() >= deadline) {
                close(created);
                stop();
                return;
            }
            player = created;
            if (created != null) created.start();
            else stop();
        } catch (RuntimeException error) {
            // A failed cue is consumed; it must never become an automatic retry.
            stop();
        }
    }

    private void finished(long current, long token) {
        if (current == generation && token == clipToken) stop();
    }

    private void releasePlayer() {
        Clip previous = player;
        player = null;
        close(previous);
    }

    private static void close(Clip clip) {
        if (clip == null) return;
        try {
            clip.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static final class MainScheduler implements Scheduler {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override public long now() { return SystemClock.elapsedRealtime(); }
        @Override public void postDelayed(Runnable runnable, long delayMs) {
            handler.postDelayed(runnable, delayMs);
        }
        @Override public void remove(Runnable runnable) { handler.removeCallbacks(runnable); }
    }

    private static final class AndroidFactory implements Factory {
        private final Context app;

        AndroidFactory(Context context) {
            if (context == null) throw new IllegalArgumentException("context");
            Context application = context.getApplicationContext();
            app = application == null ? context : application;
        }

        @Override public Clip create(int resource, Runnable complete, Runnable error) {
            MediaPlayer media = null;
            try {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        // TEYES also reads the explicit legacy stream field; usage alone
                        // leaves its vendor focus callback at stream=-1.
                        .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                // Each clip begins with 1200 ms of silent PCM so TEYES can wake its
                // I2S route/amplifier before the original recording starts. One stream
                // keeps that warm-up and speech under the same cancellation boundary.
                media = MediaPlayer.create(app, resource, attributes, 0);
                if (media == null) {
                    log("create_error resource=" + resourceName(resource) + " player=null");
                    return null;
                }
                MediaPlayer created = media;
                created.setLooping(false);
                AndroidClip clip = new AndroidClip(created, attributes, resource, error);
                created.setOnCompletionListener(ignored -> clip.finish("completion", complete));
                created.setOnErrorListener((ignored, what, extra) -> {
                    clip.finish("error what=" + what + " extra=" + extra, error);
                    return true;
                });
                return clip;
            } catch (RuntimeException failure) {
                if (media != null) {
                    try {
                        media.release();
                    } catch (RuntimeException ignored) {
                    }
                }
                log("create_error resource=" + resourceName(resource)
                        + " exception=" + failure.getClass().getSimpleName());
                return null;
            }
        }

        private final class AndroidClip implements Clip {
            private final MediaPlayer media;
            private final AudioManager audio;
            private final AudioFocusRequest focus;
            private final Runnable error;
            private final int resource;
            private final int durationMs;
            private PowerManager.WakeLock wakeLock;
            private boolean focusRequested;
            private boolean closed;
            private long startedAt = -1L;
            private String endReason = "cancel";

            AndroidClip(MediaPlayer media, AudioAttributes attributes, int resource, Runnable error) {
                this.media = media;
                this.resource = resource;
                this.error = error;
                durationMs = media.getDuration();
                audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attributes)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(change -> {
                            if (closed) return;
                            log("focus_change resource=" + resourceName(resource) + " change=" + change);
                            if (change < 0) finish("focus_loss change=" + change, this.error);
                            // A later gain never resumes or creates another player.
                        }, new Handler(Looper.getMainLooper()))
                        .build();
            }

            @Override public void start() {
                if (closed) return;
                try {
                    focusRequested = audio != null;
                    int result = audio == null ? AudioManager.AUDIOFOCUS_REQUEST_FAILED
                            : audio.requestAudioFocus(focus);
                    log("focus resource=" + resourceName(resource) + " result=" + result);
                    if (closed) return;
                    if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        finish("focus_denied result=" + result, error);
                        return;
                    }
                    PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                    if (power != null) {
                        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kia:SunroofAudio");
                        wakeLock.setReferenceCounted(false);
                        wakeLock.acquire(resource == R.raw.sunroof_warning_adas
                                ? WARNING_WINDOW_MS : TRANSITION_WINDOW_MS);
                    }
                    startedAt = SystemClock.elapsedRealtime();
                    media.start();
                    log("start resource=" + resourceName(resource) + " durationMs=" + durationMs
                            + " wake=" + (wakeLock != null && wakeLock.isHeld()));
                } catch (RuntimeException failure) {
                    finish("start_error exception=" + failure.getClass().getSimpleName(), error);
                }
            }

            void finish(String reason, Runnable callback) {
                if (closed) return;
                endReason = reason;
                try {
                    callback.run();
                } finally {
                    // Even a stale outer-generation callback must release its own resources.
                    close();
                }
            }

            @Override public void close() {
                if (closed) return;
                closed = true;
                int positionMs = -1;
                try { positionMs = media.getCurrentPosition(); } catch (RuntimeException ignored) { }
                log(endReason + " resource=" + resourceName(resource) + " durationMs=" + durationMs
                        + " playedMs=" + (startedAt < 0L ? 0L : SystemClock.elapsedRealtime() - startedAt)
                        + " positionMs=" + positionMs);
                try {
                    media.setOnCompletionListener(null);
                    media.setOnErrorListener(null);
                } catch (RuntimeException ignored) { }
                try { media.stop(); } catch (RuntimeException ignored) { }
                try { media.release(); } catch (RuntimeException ignored) { }
                if (focusRequested && audio != null) {
                    focusRequested = false;
                    try {
                        log("focus_release resource=" + resourceName(resource)
                                + " result=" + audio.abandonAudioFocusRequest(focus));
                    } catch (RuntimeException failure) {
                        log("focus_release_error resource=" + resourceName(resource)
                                + " exception=" + failure.getClass().getSimpleName());
                    }
                }
                if (wakeLock != null) {
                    PowerManager.WakeLock held = wakeLock;
                    wakeLock = null;
                    try {
                        if (held.isHeld()) held.release();
                        log("wake_release resource=" + resourceName(resource));
                    } catch (RuntimeException failure) {
                        log("wake_release_error resource=" + resourceName(resource)
                                + " exception=" + failure.getClass().getSimpleName());
                    }
                }
            }
        }

        private static String resourceName(int resource) {
            return resource == R.raw.sunroof_warning_adas ? "sunroof_warning_adas"
                    : resource == R.raw.sunroof_open_voice ? "sunroof_open_voice"
                    : resource == R.raw.sunroof_close_voice ? "sunroof_close_voice" : String.valueOf(resource);
        }

        private void log(String text) {
            try { AppLog.line(app, "Sunroof audio " + text); } catch (RuntimeException ignored) { }
        }
    }
}
