package kia.app.rcta;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.RctaState;

public final class RctaController {
    /**
     * RCTA is delivered as a repeating adapter status frame. Five seconds tolerates a long
     * scheduling/USB pause while still bounding a lost clear frame and its looping warning sound.
     */
    static final long ACTIVE_FRAME_TIMEOUT_MS = 5_000L;

    private static RctaController instance;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RctaFreshnessPolicy freshness =
            new RctaFreshnessPolicy(ACTIVE_FRAME_TIMEOUT_MS);
    private final Runnable watchdog = this::handleWatchdog;

    private RctaController(Context context) {
        this.app = context.getApplicationContext();
    }

    public static synchronized RctaController get(Context context) {
        if (instance == null) instance = new RctaController(context);
        return instance;
    }

    public synchronized void handleFrame(byte[] frame) {
        if (frame == null || frame.length < 7) return;
        int len = frame.length > 3 ? Math.min(frame.length, frame[3] & 0xff) : frame.length;
        if (len < 7) return;
        boolean left = (frame[5] & 0xff) != 0;
        boolean right = (frame[6] & 0xff) != 0;
        boolean active = left || right;

        // Identical RCTA frames are heartbeats, not duplicates. Observe and re-arm before the
        // state equality check so a live warning can never expire merely because it did not
        // change sides.
        freshness.observeFrame(active, SystemClock.elapsedRealtime());
        if (active) {
            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog, ACTIVE_FRAME_TIMEOUT_MS);
        } else {
            handler.removeCallbacks(watchdog);
        }

        RctaState next = new RctaState(left, right, System.currentTimeMillis());
        RctaState current = StateStore.rcta();
        boolean changed = current == null || current.left != left || current.right != right;
        // Keep updatedAt truthful for health/diagnostic views even when the payload is unchanged.
        StateStore.setRcta(app, next);
        if (!changed) return;

        RctaOverlayController.get(app).apply();
        AppLog.line(app, "RCTA: " + next.summary());
    }

    private synchronized void handleWatchdog() {
        long remainingMs = freshness.remainingMs(SystemClock.elapsedRealtime());
        if (remainingMs > 0L) {
            handler.postDelayed(watchdog, remainingMs);
            return;
        }
        if (!freshness.hasActiveObservation()) return;
        freshness.clear();

        RctaState current = StateStore.rcta();
        if (current == null || !current.active()) return;

        RctaState clear = new RctaState(false, false, System.currentTimeMillis());
        StateStore.setRcta(app, clear);
        RctaOverlayController.get(app).apply();
        AppLog.line(app, "RCTA: clear (нет свежих кадров "
                + ACTIVE_FRAME_TIMEOUT_MS + " мс)");
    }
}
