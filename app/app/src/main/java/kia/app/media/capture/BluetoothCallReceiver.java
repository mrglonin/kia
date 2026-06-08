package kia.app.media.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.Locale;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.media.domain.CallFeature;

public final class BluetoothCallReceiver extends BroadcastReceiver {
    public static final String ACTION_CALL_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED";

    private static final String EXTRA_CALL = "android.bluetooth.headsetclient.extra.CALL";
    private static final String STATE_TERMINATED = "TERMINATED";
    private static String lastActiveKey = "";
    private static String lastEndedKey = "";

    public static void addActions(android.content.IntentFilter filter) {
        if (filter != null) filter.addAction(ACTION_CALL_CHANGED);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!ACTION_CALL_CHANGED.equals(intent.getAction())) return;
        ParsedCall call = parseCall(context, intent);
        if (call == null) {
            AppLog.line(context, "HFP call: empty call extra " + extraKeys(intent));
            return;
        }
        if (call.ended()) {
            String key = call.key();
            if (!TextUtils.equals(key, lastEndedKey)) {
                lastEndedKey = key;
                lastActiveKey = "";
                CallFeature.get(context).reportEnded("hfp " + call.stateLabel);
            }
            return;
        }
        SpdBluetoothServiceClient.CallSnapshot snapshot =
                SpdBluetoothServiceClient.get(context).readCallSnapshot();
        String name = snapshot != null && snapshot.active() ? snapshot.name : "";
        String number = snapshot != null && snapshot.active() ? snapshot.number : "";
        String key = call.key() + "|" + clean(name) + "|" + clean(number);
        if (TextUtils.equals(key, lastActiveKey) && StateStore.call().active) return;
        lastActiveKey = key;
        lastEndedKey = "";
        AppLog.line(context, "HFP call: " + call.stateLabel + " "
                + firstNonEmpty(clean(name), clean(number)));
        CallFeature.get(context).reportActive(name, number, "HFP " + call.stateLabel);
    }

    private static ParsedCall parseCall(Context context, Intent intent) {
        Object raw = callExtra(intent);
        if (raw == null) return null;
        String rawText = raw.toString();
        String state = parseBetween(rawText, "mState: ", ",");
        String uuid = parseBetween(rawText, "mUUID: ", ",");
        if (TextUtils.isEmpty(state)) {
            AppLog.line(context, "HFP call: state parse failed " + raw);
            state = "UNKNOWN";
        }
        return new ParsedCall(state, uuid);
    }

    private static Object callExtra(Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            return extras == null ? null : extras.get(EXTRA_CALL);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String parseBetween(String value, String start, String end) {
        if (value == null) return "";
        int from = value.indexOf(start);
        if (from < 0) return "";
        from += start.length();
        int to = value.indexOf(end, from);
        if (to < 0) to = value.length();
        return clean(value.substring(from, to));
    }

    private static String firstNonEmpty(String first, String second) {
        return TextUtils.isEmpty(first) ? second : first;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String extraKeys(Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            return extras == null ? "[]" : extras.keySet().toString();
        } catch (Exception ignored) {
            return "unreadable";
        }
    }

    private static final class ParsedCall {
        final String stateLabel;
        final String uuid;

        ParsedCall(String state, String uuid) {
            this.stateLabel = clean(state).toUpperCase(Locale.US);
            this.uuid = clean(uuid);
        }

        boolean ended() {
            return stateLabel.contains(STATE_TERMINATED);
        }

        String key() {
            return stateLabel + "|" + uuid;
        }
    }
}
