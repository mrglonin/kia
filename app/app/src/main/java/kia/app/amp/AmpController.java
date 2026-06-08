package kia.app.amp;

import android.content.Context;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.AmpState;
import kia.app.core.settings.AppSettings;
import kia.app.protocol.adapter.AdapterCommand;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;

public final class AmpController {
    private static AmpController instance;

    private final Context app;
    private AmpState state = AmpState.empty();

    private AmpController(Context context) {
        this.app = context.getApplicationContext();
    }

    public static synchronized AmpController get(Context context) {
        if (instance == null) instance = new AmpController(context);
        return instance;
    }

    public AmpState state() {
        return state;
    }

    public void requestSettings() {
        if (!AppSettings.ampEnabled(app)) {
            AppLog.line(app, "AMP: disabled, request skipped");
            return;
        }
        AdapterGateway.get(app).send(AdapterCommand.loud("AMP request", AdapterProtocol.requestAmpSettings()));
        AppLog.line(app, "AMP: request settings");
    }

    public void setSettings(AmpState value, boolean save) {
        if (!AppSettings.ampEnabled(app)) {
            AppLog.line(app, "AMP: disabled, send skipped");
            return;
        }
        if (value == null) value = state;
        state = value;
        StateStore.setAmp(app, state);
        AdapterGateway.get(app).send(AdapterCommand.loud("AMP set",
                AdapterProtocol.ampSettings(value.volume, value.balance, value.fader,
                        value.bass, value.mid, value.treble, value.mode, save)));
        AppLog.line(app, "AMP: send " + state.summary());
    }

    public void handleFrame(byte[] frame) {
        if (frame == null || frame.length < 13) return;
        int mode = frame.length > 5 ? frame[5] & 0xff : 0;
        if (mode != 0x00 && mode != 0x01 && mode != 0x02) return;
        int volume = u8(frame, 6, state.volume);
        int balance = u8(frame, 7, state.balance);
        int fader = u8(frame, 8, state.fader);
        int bass = u8(frame, 9, state.bass);
        int mid = u8(frame, 10, state.mid);
        int treble = u8(frame, 11, state.treble);
        int ampMode = u8(frame, 12, state.mode);
        state = new AmpState(volume, balance, fader, bass, mid, treble, ampMode,
                System.currentTimeMillis());
        StateStore.setAmp(app, state);
        AppLog.line(app, "AMP: received " + state.summary());
    }

    private int u8(byte[] frame, int offset, int fallback) {
        return frame.length > offset ? frame[offset] & 0xff : fallback;
    }
}
