package kia.app.navigation.capture;

import android.net.Uri;

public final class YandexCoreBridgeContract {
    public static final String AUTHORITY = "ru.yandex.yandexnavi.kia.bridge";
    public static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY + "/state");
    public static final String METHOD_GET_STATE = "get_state";
    public static final String SOURCE = "yandex_core_bridge";

    public static final String STATE_OFF = "off";
    public static final String STATE_LOADING = "loading";
    public static final String STATE_ACTIVE = "active";
    public static final String STATE_REROUTING = "rerouting";
    public static final String STATE_FINISHED = "finished";

    private YandexCoreBridgeContract() {
    }
}
