package kia.app.tpms;

/** Same Home/camera signals used by the vendor ADAS overlay; unknown means hidden. */
public final class TeyesHomeVisibilityPolicy {
    private TeyesHomeVisibilityPolicy() {}

    public static boolean shouldShow(String homeVisible, String reverseCamera,
                                     boolean screenInteractive, boolean deviceLocked) {
        return screenInteractive && !deviceLocked
                && "true".equals(homeVisible) && "0".equals(reverseCamera);
    }
}
