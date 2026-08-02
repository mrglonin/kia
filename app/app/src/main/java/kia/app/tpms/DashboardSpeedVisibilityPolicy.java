package kia.app.tpms;

/**
 * Pure visibility rule for the speed badges drawn by the TPMS dashboard.
 */
public final class DashboardSpeedVisibilityPolicy {
    private DashboardSpeedVisibilityPolicy() {}

    public static Decision resolve(boolean moving, int currentSpeedKmh, int speedLimitKmh) {
        boolean showLimit = speedLimitKmh > 0;
        boolean showCurrent = moving && currentSpeedKmh > 0;
        return new Decision(showCurrent, showLimit);
    }

    public static final class Decision {
        public final boolean showCurrentSpeed;
        public final boolean showSpeedLimit;

        private Decision(boolean showCurrentSpeed, boolean showSpeedLimit) {
            this.showCurrentSpeed = showCurrentSpeed;
            this.showSpeedLimit = showSpeedLimit;
        }
    }
}
