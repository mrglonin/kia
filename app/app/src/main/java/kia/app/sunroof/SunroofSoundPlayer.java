package kia.app.sunroof;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import kia.app.R;
import kia.app.core.AppLog;

/** Finite roof speech and ADAS cues. Main thread only; callers sound only a shown overlay. */
public final class SunroofSoundPlayer {
    static final long WARNING_WINDOW_MS = 9_500L;
    static final long TRANSITION_WINDOW_MS = 2_500L;

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
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                // Each resource contains speech. The warning combines its ADAS signal and
                // phrase into one clip so cancellation cannot leave a delayed phrase behind.
                media = MediaPlayer.create(app, resource, attributes, 0);
                if (media == null) return null;
                MediaPlayer created = media;
                created.setLooping(false);
                created.setOnCompletionListener(ignored -> complete.run());
                created.setOnErrorListener((ignored, what, extra) -> {
                    error.run();
                    return true;
                });
                return new Clip() {
                    private boolean closed;

                    @Override public void start() { created.start(); }

                    @Override public void close() {
                        if (closed) return;
                        closed = true;
                        try {
                            created.setOnCompletionListener(null);
                            created.setOnErrorListener(null);
                        } catch (RuntimeException ignored) {
                        }
                        try {
                            created.stop();
                        } catch (RuntimeException ignored) {
                        }
                        created.release();
                    }
                };
            } catch (RuntimeException failure) {
                if (media != null) {
                    try {
                        media.release();
                    } catch (RuntimeException ignored) {
                    }
                }
                AppLog.line(app, "Sunroof sound failed " + failure.getClass().getSimpleName());
                return null;
            }
        }
    }
}
