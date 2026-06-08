package kia.app.rcta;

import android.content.Context;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.RctaState;

public final class RctaController {
    private static RctaController instance;

    private final Context app;

    private RctaController(Context context) {
        this.app = context.getApplicationContext();
    }

    public static synchronized RctaController get(Context context) {
        if (instance == null) instance = new RctaController(context);
        return instance;
    }

    public void handleFrame(byte[] frame) {
        if (frame == null || frame.length < 7) return;
        int len = frame.length > 3 ? Math.min(frame.length, frame[3] & 0xff) : frame.length;
        if (len < 7) return;
        boolean left = (frame[5] & 0xff) != 0;
        boolean right = (frame[6] & 0xff) != 0;
        RctaState current = StateStore.rcta();
        if (current.left == left && current.right == right) return;

        RctaState next = new RctaState(left, right, System.currentTimeMillis());
        StateStore.setRcta(app, next);
        RctaOverlayController.get(app).apply();
        AppLog.line(app, "RCTA: " + next.summary());
    }
}
