package kia.app.diagnostics;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import kia.app.core.AppLog;
import kia.app.protocol.adapter.AdapterGateway;

/** Polls adapter health away from the main looper and recovers silent half-open USB sessions. */
public final class HealthMonitor {
    private static final long POLL_MS = 750L;
    private static final long INFO_POLL_MS = 5000L;
    private static final long DISCONNECTED_RETRY_MS = 2000L;
    private static final long RX_STARTUP_GRACE_MS = 20000L;
    private static final long RX_STALE_MS = 15000L;
    private static final int REQUIRED_SILENT_PROBE_BURSTS = 3;

    private final Context app;
    private volatile boolean running;
    private HandlerThread thread;
    private Handler handler;
    private long sessionGeneration;
    private Runnable tick;

    private static final class SessionState {
        long lastHealthAt;
        long observedConnectionEpoch = -1L;
        long observedLastRxAt;
        int silentProbeBursts;
    }

    public HealthMonitor(Context context) {
        this.app = context.getApplicationContext();
    }

    public synchronized void start() {
        if (running) return;
        HandlerThread nextThread = new HandlerThread("KiaAdapterHealth");
        nextThread.start();
        thread = nextThread;
        handler = new Handler(nextThread.getLooper());
        long generation = ++sessionGeneration;
        Handler target = handler;
        tick = createTick(generation, target, new SessionState());
        running = true;
        handler.post(tick);
    }

    public synchronized void stop() {
        running = false;
        sessionGeneration++;
        Handler oldHandler = handler;
        HandlerThread oldThread = thread;
        handler = null;
        thread = null;
        tick = null;
        if (oldHandler != null) oldHandler.removeCallbacksAndMessages(null);
        if (oldThread != null) oldThread.quitSafely();
    }

    private long runTick(SessionState session) {
        AdapterGateway gateway = AdapterGateway.get(app);
        if (gateway.exclusiveUpdateMode()) return POLL_MS;
        if (!gateway.usbReady()) {
            // connect() is asynchronous and coalesced by UsbTransport.
            gateway.start();
            return DISCONNECTED_RETRY_MS;
        }

        long now = SystemClock.elapsedRealtime();
        long connectionEpoch = gateway.connectionEpoch();
        long lastRxAt = gateway.lastRxAtElapsedRealtime();
        if (connectionEpoch != session.observedConnectionEpoch) {
            session.observedConnectionEpoch = connectionEpoch;
            session.observedLastRxAt = lastRxAt;
            session.silentProbeBursts = 0;
            session.lastHealthAt = 0L;
        } else if (lastRxAt > 0L && lastRxAt != session.observedLastRxAt) {
            session.observedLastRxAt = lastRxAt;
            session.silentProbeBursts = 0;
        }
        if (UsbConnectionHealthPolicy.shouldReconnect(
                true,
                gateway.connectedAtElapsedRealtime(),
                lastRxAt,
                now,
                RX_STARTUP_GRACE_MS,
                RX_STALE_MS,
                session.silentProbeBursts,
                REQUIRED_SILENT_PROBE_BURSTS)) {
            AppLog.line(app, "Health monitor: USB открыт, но RX устарел; переподключение");
            gateway.reconnect("нет RX от адаптера");
            return DISCONNECTED_RETRY_MS;
        }

        gateway.pollVehicleSnapshot();
        if (session.lastHealthAt <= 0L || now < session.lastHealthAt
                || now - session.lastHealthAt > INFO_POLL_MS) {
            session.lastHealthAt = now;
            gateway.requestAdapterInfoQuiet();
            session.silentProbeBursts++;
        }
        return POLL_MS;
    }

    private Runnable createTick(long generation, Handler target, SessionState session) {
        return new Runnable() {
            @Override
            public void run() {
                if (!sessionCurrent(generation, target, this)) return;
                long nextMs = POLL_MS;
                try {
                    nextMs = runTick(session);
                } catch (RuntimeException e) {
                    AppLog.line(app, "Health monitor error " + e.getClass().getSimpleName());
                    nextMs = DISCONNECTED_RETRY_MS;
                }
                scheduleNext(generation, target, this, nextMs);
            }
        };
    }

    private synchronized boolean sessionCurrent(long generation, Handler target, Runnable task) {
        return HealthMonitorSessionPolicy.current(
                running, sessionGeneration, generation,
                handler == target, tick == task);
    }

    private void scheduleNext(long generation, Handler target, Runnable task, long delayMs) {
        synchronized (this) {
            if (!HealthMonitorSessionPolicy.current(
                    running, sessionGeneration, generation,
                    handler == target, tick == task)) {
                return;
            }
        }
        target.postDelayed(task, Math.max(1L, delayMs));
    }
}
