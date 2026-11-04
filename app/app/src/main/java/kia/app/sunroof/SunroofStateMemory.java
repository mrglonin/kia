package kia.app.sunroof;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

import kia.app.core.AppLog;

/** Last confirmed roof position, independent of USB availability and presentation state. */
public final class SunroofStateMemory {
    private static final String PREFS = "kia_sunroof_state";
    private static final String KEY_KNOWN = "roof_known";
    private static final String KEY_OPEN = "roof_open";
    private static final String KEY_SOURCE = "roof_source";
    private static final String KEY_RECORDED_AT = "roof_recorded_at";
    private static final String KEY_ACKNOWLEDGED = "warning_acknowledged";
    private static final String REAL_SOURCE = "0x76";

    public static final class Snapshot {
        public final boolean known;
        public final boolean open;
        public final String source;
        public final long recordedAt;
        public final boolean acknowledged;

        private Snapshot(boolean known, boolean open, String source, long recordedAt,
                         boolean acknowledged) {
            this.known = known;
            this.open = open;
            this.source = source;
            this.recordedAt = recordedAt;
            this.acknowledged = acknowledged;
        }
    }

    private SunroofStateMemory() {
    }

    public static synchronized Snapshot snapshot(Context context) {
        Map<String, ?> values = prefs(context).getAll();
        boolean known = Boolean.TRUE.equals(values.get(KEY_KNOWN))
                && values.get(KEY_OPEN) instanceof Boolean
                && REAL_SOURCE.equals(values.get(KEY_SOURCE));
        return new Snapshot(known, known && Boolean.TRUE.equals(values.get(KEY_OPEN)),
                known ? REAL_SOURCE : "",
                values.get(KEY_RECORDED_AT) instanceof Long
                        ? (Long) values.get(KEY_RECORDED_AT) : 0L,
                known && Boolean.TRUE.equals(values.get(KEY_ACKNOWLEDGED)));
    }

    /** Called only after a real 0x76 packet has passed the production router's ordering checks. */
    public static synchronized void record(Context context, boolean open, String source) {
        if (!REAL_SOURCE.equals(source)) return;
        Snapshot previous = snapshot(context);
        if (previous.known && previous.open == open) return;
        boolean committed = prefs(context).edit()
                .putBoolean(KEY_KNOWN, true)
                .putBoolean(KEY_OPEN, open)
                .putString(KEY_SOURCE, REAL_SOURCE)
                .putLong(KEY_RECORDED_AT, System.currentTimeMillis())
                .putBoolean(KEY_ACKNOWLEDGED, false)
                .commit();
        if (!committed) AppLog.line(context, "Sunroof memory: position could not be saved");
    }

    /** Acknowledging a warning never writes or clears the roof's actual position. */
    public static synchronized void acknowledge(Context context) {
        Snapshot previous = snapshot(context);
        if (!previous.known || !previous.open || previous.acknowledged) return;
        if (!prefs(context).edit().putBoolean(KEY_ACKNOWLEDGED, true).commit()) {
            AppLog.line(context, "Sunroof memory: acknowledgement could not be saved");
        }
    }

    /** Only a confirmed ACC ON begins the next ignition cycle. Unknown ACC is ignored. */
    public static synchronized void observeAcc(Context context, boolean known, boolean on) {
        if (!known || !on || !snapshot(context).acknowledged) return;
        if (!prefs(context).edit().putBoolean(KEY_ACKNOWLEDGED, false).commit()) {
            AppLog.line(context, "Sunroof memory: ignition cycle could not be saved");
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
