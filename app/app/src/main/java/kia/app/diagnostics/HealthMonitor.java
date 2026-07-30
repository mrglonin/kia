package kia.app.diagnostics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import kia.app.protocol.adapter.AdapterGateway;

public final class HealthMonitor {
    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private long lastHealthAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            AdapterGateway gateway = AdapterGateway.get(app);
            if (gateway.exclusiveUpdateMode()) {
                handler.postDelayed(this, 750L);
                return;
            }
            if (!gateway.usbReady()) {
                handler.postDelayed(this, 5000L);
                return;
            }
            gateway.pollVehicleSnapshot();
            long now = System.currentTimeMillis();
            if (now - lastHealthAt > 5000L) {
                lastHealthAt = now;
                gateway.requestAdapterInfoQuiet();
            }
            handler.postDelayed(this, 750L);
        }
    };

    public HealthMonitor(Context context) {
        this.app = context.getApplicationContext();
    }

    public void start() {
        if (running) return;
        running = true;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }
}
