package kia.app.tpms;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import kia.app.core.StateStore;
import kia.app.core.model.TpmsState;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;

public final class TpmsController {
    private static final long WARNING_POLL_MS = 5_000L;
    private static final long CRITICAL_POLL_MS = 1_000L;
    // Adapter 0x51 payload order is FR, FL, RR, RL.
    private static final int[] NATIVE_TPMS_WHEEL_ORDER = {
            TpmsState.WHEEL_FR,
            TpmsState.WHEEL_FL,
            TpmsState.WHEEL_RR,
            TpmsState.WHEEL_RL
    };

    private static TpmsController instance;

    private final Context app;
    private final Object lock = new Object();
    private Handler worker;
    private boolean polling;
    private TpmsPollingMode pollingMode = TpmsPollingMode.BACKGROUND;
    private long generation;
    private long connectedEpoch;
    private long lastPollStartedAt = -1L;
    private long nextPollAt = Long.MAX_VALUE;
    private long forcedPollAt = Long.MAX_VALUE;
    private Runnable pollTick;

    private TpmsController(Context context) {
        this.app = context.getApplicationContext();
    }

    public static synchronized TpmsController get(Context context) {
        if (instance == null) instance = new TpmsController(context);
        return instance;
    }

    public void startPolling() {
        synchronized (lock) {
            if (polling) return;
            if (worker == null) {
                // Keep one serialized worker for this process, including service restarts.
                // A slow write from an old session cannot overlap a new session's write.
                HandlerThread thread = new HandlerThread("KiaTpmsPolling");
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            polling = true;
            long session = ++generation;
            pollTick = () -> pollOnWorker(session);
            lastPollStartedAt = -1L;
            nextPollAt = Long.MAX_VALUE;
            forcedPollAt = SystemClock.elapsedRealtime();
            scheduleNextLocked();
        }
    }

    public void stopPolling() {
        synchronized (lock) {
            polling = false;
            generation++;
            if (worker != null && pollTick != null) worker.removeCallbacks(pollTick);
            pollTick = null;
            nextPollAt = Long.MAX_VALUE;
            forcedPollAt = Long.MAX_VALUE;
            // USB writes never hold this lock. An already-started write may finish,
            // but it cannot apply alerts or schedule another tick after this fence.
        }
    }

    public void setForegroundActive(boolean active) {
        setForegroundActive(active, false);
    }

    /**
     * Compatibility bridge for existing callers. New UI code should use
     * {@link #setPollingMode(TpmsPollingMode)} so screen size cannot
     * accidentally turn a full dashboard into a slow widget.
     */
    public void setForegroundActive(boolean active, boolean widgetMode) {
        setPollingMode(!active
                ? TpmsPollingMode.BACKGROUND
                : (widgetMode ? TpmsPollingMode.EMBEDDED_WIDGET
                : TpmsPollingMode.FULL_DASHBOARD));
    }

    public void setPollingMode(TpmsPollingMode mode) {
        synchronized (lock) {
            TpmsPollingMode clean = mode == null ? TpmsPollingMode.BACKGROUND : mode;
            if (pollingMode == clean) return;
            pollingMode = clean;
            if (polling) scheduleImmediateLocked();
        }
    }

    public TpmsPollingMode pollingMode() {
        synchronized (lock) { return pollingMode; }
    }

    /** Request a poll on the worker; callers never perform USB I/O on their thread. */
    public void requestNow() {
        synchronized (lock) {
            if (polling) scheduleImmediateLocked();
        }
    }

    /** Called once a new USB connection epoch becomes usable. */
    public void onAdapterConnected(long connectionEpoch) {
        synchronized (lock) {
            if (connectionEpoch <= 0L || connectionEpoch <= connectedEpoch) return;
            connectedEpoch = connectionEpoch;
            if (polling) scheduleImmediateLocked();
        }
    }

    /** Re-evaluate thresholds and alert enablement without waiting for another packet. */
    public void onSettingsChanged() {
        synchronized (lock) {
            if (!polling) return;
            TpmsAlertController.get(app).apply(StateStore.tpms());
            scheduleNextLocked();
        }
    }

    /** Returns whether the packet contains recognized wheel slots, even if all are empty. */
    public boolean handleAdapterFrame(byte[] frame) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            TpmsState next = parseAdapterFrame(frame, StateStore.tpms(), now);
            if (next != null) {
                // Keep this synchronous: FrameRouter forwards the newly stored wheel
                // values to the native TEYES widget immediately after this method.
                StateStore.setTpms(app, next);
                if (polling) {
                    TpmsAlertController.get(app).apply(next);
                    scheduleNextLocked();
                }
            }
        }
        return observedWheelMask(frame) != 0;
    }

    static int observedWheelMask(byte[] frame) {
        int len = frame == null || frame.length < 6 ? 0 : Math.min(frame.length, frame[3] & 0xff);
        int id = u8(frame, 4);
        if (id == AdapterProtocol.CMD_TPMS && len >= 14) return 0x0f;
        if (id == AdapterProtocol.CMD_TPMS_LEGACY && len >= 23) return u8(frame, 5) & 0x0f;
        return 0;
    }

    static TpmsState parseAdapterFrame(byte[] frame, TpmsState current, long observedAt) {
        int len = frame == null || frame.length < 6 ? 0 : Math.min(frame.length, frame[3] & 0xff);
        int id = u8(frame, 4);
        if (id == AdapterProtocol.CMD_TPMS && len >= 14) {
            return parseNativeTpms(frame, len, current, observedAt);
        }
        if (id != AdapterProtocol.CMD_TPMS_LEGACY) return null;
        if (len < 23) return null;
        int mask = u8(frame, 5);
        TpmsState next = current == null ? TpmsState.empty() : current;
        boolean changed = false;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            if ((mask & (1 << wheel)) == 0) continue;
            int offset = 6 + wheel * 4;
            if (offset + 3 >= len - 1) continue;
            int pressureKpa = u16(frame, offset);
            int temperatureC = s8(frame, offset + 2);
            int flags = u8(frame, offset + 3);
            next = next.withWheelAt(wheel, pressureKpa, temperatureC, flags,
                    "adapter", observedAt);
            changed = true;
        }
        return changed ? next : null;
    }

    private static TpmsState parseNativeTpms(byte[] frame, int len, TpmsState current,
                                             long observedAt) {
        if (len < 14) return null;
        TpmsState next = current == null ? TpmsState.empty() : current;
        boolean changed = false;
        for (int index = 0; index < TpmsState.WHEEL_COUNT; index++) {
            int wheel = NATIVE_TPMS_WHEEL_ORDER[index];
            int pressureRaw = u8(frame, 5 + index);
            if (pressureRaw <= 0) {
                TpmsState stale = next.withWheelStale(wheel, "adapter 0x51", observedAt);
                if (stale != next) {
                    next = stale;
                    changed = true;
                }
                continue;
            }
            int tempRaw = u8(frame, 9 + index);
            float psi = pressureRaw * 0.25f;
            int pressureKpa = Math.round(psi * 6.894757f);
            int temperatureC = tempRaw - 55;
            next = next.withWheelAt(wheel, pressureKpa, temperatureC, 0,
                    "adapter 0x51", observedAt);
            changed = true;
        }
        return changed ? next : null;
    }

    private void pollOnWorker(long session) {
        synchronized (lock) {
            if (!polling || session != generation) return;
            long now = SystemClock.elapsedRealtime();
            // A callback already dequeued before a newer reschedule must not
            // consume that newer deadline or create a duplicate timer.
            if (now < nextPollAt) return;
            worker.removeCallbacks(pollTick);
            nextPollAt = Long.MAX_VALUE;
            forcedPollAt = Long.MAX_VALUE;
            lastPollStartedAt = now;
            TpmsAlertController.get(app).apply(StateStore.tpms());
        }
        try {
            AdapterGateway.get(app).pollTpms();
        } finally {
            synchronized (lock) {
                if (polling && session == generation) scheduleNextLocked();
            }
        }
    }

    private void scheduleImmediateLocked() {
        forcedPollAt = Math.min(forcedPollAt, SystemClock.elapsedRealtime());
        scheduleNextLocked();
    }

    private void scheduleNextLocked() {
        if (!polling || pollTick == null) return;
        long now = SystemClock.elapsedRealtime();
        long regular = lastPollStartedAt < 0L ? now : lastPollStartedAt + pollIntervalMs();
        long deadline = Math.max(now, Math.min(regular, forcedPollAt));
        if (deadline == nextPollAt) return;
        worker.removeCallbacks(pollTick);
        nextPollAt = deadline;
        worker.postDelayed(pollTick, Math.max(0L, deadline - now));
        // Packet arrivals do not restart the interval. Only the last poll, the
        // current severity/mode, or an explicit immediate request sets its end.
    }

    private long pollIntervalMs() {
        int severity = maxSeverity(StateStore.tpms());
        if (severity == TpmsAlertController.SEVERITY_CRITICAL) return CRITICAL_POLL_MS;
        if (severity == TpmsAlertController.SEVERITY_WARNING) return WARNING_POLL_MS;
        return pollingMode.intervalMs();
    }

    private int maxSeverity(TpmsState state) {
        if (state == null || state.known == null) return TpmsAlertController.SEVERITY_NONE;
        int max = TpmsAlertController.SEVERITY_NONE;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            int severity = TpmsAlertController.warningSeverity(app, state, wheel);
            if (severity > max) max = severity;
        }
        return max;
    }

    private static int u8(byte[] data, int offset) {
        return data == null || offset < 0 || offset >= data.length ? 0 : data[offset] & 0xff;
    }

    private static int u16(byte[] data, int offset) {
        return u8(data, offset) | (u8(data, offset + 1) << 8);
    }

    private static int s8(byte[] data, int offset) {
        int value = u8(data, offset);
        return value >= 0x80 ? value - 0x100 : value;
    }
}
