package kia.app.navigation.domain;

public final class NavigationOutputMode {
    public static final int NORMAL = 0;
    public static final int TBT = 1;
    public static final int FINISH_DIRECTION = 2;

    private NavigationOutputMode() {
    }

    public static int normalize(int mode) {
        if (mode == TBT || mode == FINISH_DIRECTION) return mode;
        return NORMAL;
    }

    public static boolean isNormal(int mode) {
        return normalize(mode) == NORMAL;
    }

    public static boolean isTbt(int mode) {
        return normalize(mode) == TBT;
    }

    public static boolean isFinishDirection(int mode) {
        return normalize(mode) == FINISH_DIRECTION;
    }

    public static String label(int mode) {
        switch (normalize(mode)) {
            case TBT:
                return "TBT";
            case FINISH_DIRECTION:
                return "стрелка к флагу";
            case NORMAL:
            default:
                return "обычный";
        }
    }
}
