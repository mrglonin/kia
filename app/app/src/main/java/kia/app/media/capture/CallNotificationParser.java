package kia.app.media.capture;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.Locale;

final class CallNotificationParser {
    private CallNotificationParser() {
    }

    static Parsed parse(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return null;
        String pkg = clean(sbn.getPackageName());
        Notification notification = sbn.getNotification();
        if (!looksLikeCallPackage(pkg) && !Notification.CATEGORY_CALL.equals(notification.category)) {
            return null;
        }
        Bundle extras = notification.extras;
        String title = extras == null ? "" : clean(String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE, "")));
        String text = extras == null ? "" : clean(String.valueOf(extras.getCharSequence(Notification.EXTRA_TEXT, "")));
        String bigText = extras == null ? "" : clean(String.valueOf(extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "")));
        if (looksLikeMissedCall(title) || looksLikeMissedCall(text) || looksLikeMissedCall(bigText)
                || looksLikeMissedCall(sbn.getTag())) {
            return null;
        }
        String name = firstUseful(title, text, bigText);
        String phone = firstPhone(text, bigText, title);
        if (TextUtils.isEmpty(name) && TextUtils.isEmpty(phone)) return null;
        if (TextUtils.equals(name, phone)) name = "";
        return new Parsed(name, phone, pkg);
    }

    static boolean isCallLikePackage(String packageName) {
        return looksLikeCallPackage(clean(packageName));
    }

    private static boolean looksLikeCallPackage(String packageName) {
        String p = packageName.toLowerCase(Locale.ROOT);
        return p.contains("phone")
                || p.contains("dialer")
                || p.contains("telecom")
                || p.contains("incall")
                || p.contains("bluetooth")
                || p.contains("btphone");
    }

    private static String firstUseful(String first, String second, String third) {
        if (usefulName(first)) return first;
        if (usefulName(second)) return second;
        if (usefulName(third)) return third;
        return "";
    }

    private static boolean usefulName(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return !v.contains("звон")
                && !v.contains("call")
                && !v.contains("bluetooth")
                && !v.contains("phone");
    }

    private static String firstPhone(String first, String second, String third) {
        if (looksLikePhone(first)) return first;
        if (looksLikePhone(second)) return second;
        if (looksLikePhone(third)) return third;
        return "";
    }

    private static boolean looksLikePhone(String value) {
        if (TextUtils.isEmpty(value)) return false;
        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) digits++;
        }
        return digits >= 5;
    }

    private static boolean looksLikeMissedCall(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return v.contains("пропущ")
                || v.contains("missed call")
                || v.contains("missedcall");
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    static final class Parsed {
        final String name;
        final String phone;
        final String source;

        Parsed(String name, String phone, String source) {
            this.name = name;
            this.phone = phone;
            this.source = source;
        }
    }
}
