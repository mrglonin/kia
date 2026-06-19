package kia.app.tpms;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import kia.app.core.StateStore;
import kia.app.core.model.TpmsState;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;

public final class TpmsController {
    private static final long BACKGROUND_POLL_MS = 120_000L;
    private static final long WIDGET_POLL_MS = 30_000L;
    private static final long FOREGROUND_POLL_MS = 5_000L;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling;
    private boolean foregroundActive;
    private boolean widgetForeground;

    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            requestNow();
            scheduleNext();
        }
    };

    private TpmsController(Context context) {
        this.app = context.getApplicationContext();
    }

    public static synchronized TpmsController get(Context context) {
        if (instance == null) instance = new TpmsController(context);
        return instance;
    }

    public void startPolling() {
        if (polling) return;
        polling = true;
        scheduleImmediate();
    }

    public void stopPolling() {
        polling = false;
        handler.removeCallbacks(pollTick);
    }

    public void setForegroundActive(boolean active) {
        setForegroundActive(active, false);
    }

    public void setForegroundActive(boolean active, boolean widgetMode) {
        boolean cleanWidget = active && widgetMode;
        if (foregroundActive == active && widgetForeground == cleanWidget) return;
        foregroundActive = active;
        widgetForeground = cleanWidget;
        if (polling) scheduleImmediate();
    }

    public void requestNow() {
        AdapterGateway.get(app).pollTpms();
    }

    public void handleAdapterFrame(byte[] frame) {
        TpmsState next = parseAdapterFrame(frame, StateStore.tpms());
        if (next == null) return;
        StateStore.setTpms(app, next);
        TpmsAlertController.get(app).apply(next);
        if (polling) scheduleNext();
    }

    private TpmsState parseAdapterFrame(byte[] frame, TpmsState current) {
        int len = frame == null || frame.length < 6 ? 0 : Math.min(frame.length, frame[3] & 0xff);
        int id = u8(frame, 4);
        if (id == AdapterProtocol.CMD_TPMS && len >= 14) {
            return parseNativeTpms(frame, len, current);
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
            next = next.withWheel(wheel, pressureKpa, temperatureC, flags, "adapter");
            changed = true;
        }
        return changed ? next : null;
    }

    private TpmsState parseNativeTpms(byte[] frame, int len, TpmsState current) {
        if (len < 14) return null;
        TpmsState next = current == null ? TpmsState.empty() : current;
        boolean changed = false;
        for (int index = 0; index < TpmsState.WHEEL_COUNT; index++) {
            int wheel = NATIVE_TPMS_WHEEL_ORDER[index];
            int pressureRaw = u8(frame, 5 + index);
            if (pressureRaw <= 0) continue;
            int tempRaw = u8(frame, 9 + index);
            float psi = pressureRaw * 0.25f;
            int pressureKpa = Math.round(psi * 6.894757f);
            int temperatureC = tempRaw - 55;
            next = next.withWheel(wheel, pressureKpa, temperatureC, 0, "adapter 0x51");
            changed = true;
        }
        return changed ? next : null;
    }

    private void scheduleImmediate() {
        handler.removeCallbacks(pollTick);
        handler.post(pollTick);
    }

    private void scheduleNext() {
        handler.removeCallbacks(pollTick);
        handler.postDelayed(pollTick, pollIntervalMs());
    }

    private long pollIntervalMs() {
        int severity = maxSeverity(StateStore.tpms());
        if (severity == TpmsAlertController.SEVERITY_CRITICAL) return CRITICAL_POLL_MS;
        if (severity == TpmsAlertController.SEVERITY_WARNING) return WARNING_POLL_MS;
        if (foregroundActive && widgetForeground) return WIDGET_POLL_MS;
        return foregroundActive ? FOREGROUND_POLL_MS : BACKGROUND_POLL_MS;
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
