package kia.app.media.domain;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.CallState;
import kia.app.core.model.MediaState;
import kia.app.core.settings.AppSettings;
import kia.app.media.cluster.MediaClusterSender;

public final class CallFeature {
    private static final long TICK_MS = 1000L;
    private static final long END_HOLD_MS = 1800L;
    private static CallFeature instance;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaClusterSender clusterSender;
    private final MediaClusterSender synchronizedMediaSender;
    private String pendingEndReason = "";
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };
    private final Runnable endHold = new Runnable() {
        @Override
        public void run() {
            finishPendingEnd();
        }
    };

    private CallFeature(Context context) {
        this.app = context.getApplicationContext();
        // Keep the proven TEYES call sender isolated exactly as before. Only Android/UART share
        // the synchronized media coordinator so delayed metadata can be cancelled during calls.
        this.clusterSender = new MediaClusterSender(app);
        this.synchronizedMediaSender = MediaClusterSender.get(app);
    }

    public static synchronized CallFeature get(Context context) {
        if (instance == null) instance = new CallFeature(context);
        return instance;
    }

    public synchronized void reportActive(String name, String phone, String source) {
        if (!AppSettings.callEnabled(app)) return;
        handler.removeCallbacks(endHold);
        pendingEndReason = "";
        CallState current = StateStore.call();
        long startedAt = current.active ? current.startedAt : System.currentTimeMillis();
        CallState next = CallState.active(name, phone, firstNonEmpty(source, "Bluetooth audio"), startedAt);
        StateStore.setCall(app, next);
        synchronizedMediaSender.suspendForCall();
        clusterSender.sendCall(next);
        scheduleTick();
        AppLog.line(app, "Call RX active: " + next.displayName() + " | " + next.subtitle());
    }

    public synchronized void reportEnded(String reason) {
        if (!AppSettings.callEnabled(app)) {
            stop();
            return;
        }
        if (!StateStore.call().active) return;
        pendingEndReason = clean(reason);
        handler.removeCallbacks(endHold);
        handler.postDelayed(endHold, END_HOLD_MS);
        AppLog.line(app, "Call RX ending hold: " + pendingEndReason);
    }

    private synchronized void finishPendingEnd() {
        CallState previous = StateStore.call();
        if (previous == null || !previous.active) return;
        String reason = pendingEndReason;
        pendingEndReason = "";
        handler.removeCallbacks(ticker);
        StateStore.setCall(app, CallState.empty());
        clusterSender.clearCall(previous);
        MediaState media = StateStore.media();
        if (media != null && (!TextUtils.isEmpty(media.source)
                || !TextUtils.isEmpty(media.artist) || !TextUtils.isEmpty(media.title))) {
            MediaFeature.get(app).resendCurrent("call ended");
        }
        AppLog.line(app, "Call RX ended: " + clean(reason));
    }

    public synchronized void tick() {
        if (!AppSettings.callEnabled(app)) {
            stop();
            return;
        }
        CallState current = StateStore.call();
        if (current == null || !current.active) {
            handler.removeCallbacks(ticker);
            return;
        }
        StateStore.setCall(app, CallState.active(current.name, current.phone,
                current.source, current.startedAt));
        clusterSender.sendCall(StateStore.call());
        scheduleTick();
    }

    private void scheduleTick() {
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_MS);
    }

    public synchronized void stop() {
        boolean wasActive = StateStore.call().active;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(endHold);
        pendingEndReason = "";
        StateStore.setCall(app, CallState.empty());
        if (wasActive && AppSettings.universalMediaProfile(app)) {
            MediaFeature.get(app).resendCurrent("call feature disabled");
        }
        AppLog.line(app, "Call RX disabled");
    }

    private static String firstNonEmpty(String first, String second) {
        return TextUtils.isEmpty(first) ? second : first;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
