package kia.app.navigation.capture;

/**
 * Pure source-selection and ownership decisions for navigation ingress.
 *
 * <p>Mode values intentionally match the persisted navigation source values:
 * {@code 0=Auto, 1=Yandex, 2=2GIS}. Incoming source values use the same Yandex/2GIS numbers, with
 * {@link #SOURCE_NONE} reserved for an unowned route.</p>
 */
public final class NavigationSourcePolicy {
    public static final int MODE_AUTO = 0;
    public static final int MODE_YANDEX = 1;
    public static final int MODE_DGIS = 2;

    public static final int SOURCE_NONE = 0;
    public static final int SOURCE_YANDEX = 1;
    public static final int SOURCE_DGIS = 2;

    public static final int EVENT_PASSIVE = 0;
    public static final int EVENT_ROUTE_ACTIVE = 1;
    public static final int EVENT_ROUTE_END = 2;

    public static final int DECISION_DENY_DISABLED = 0;
    public static final int DECISION_DENY_SELECTED_SOURCE = 1;
    public static final int DECISION_DENY_FRESH_OWNER = 2;
    public static final int DECISION_ALLOW = 3;
    public static final int DECISION_ALLOW_CLAIM = 4;
    public static final int DECISION_ALLOW_RELEASE = 5;

    private NavigationSourcePolicy() {
    }

    public static boolean ingressAllowed(boolean navigationEnabled, int selectedMode,
                                         int incomingSource) {
        if (!navigationEnabled || !validSource(incomingSource)) return false;
        if (selectedMode == MODE_YANDEX) return incomingSource == SOURCE_YANDEX;
        if (selectedMode == MODE_DGIS) return incomingSource == SOURCE_DGIS;
        return selectedMode == MODE_AUTO;
    }

    public static int decide(boolean navigationEnabled, int selectedMode,
                             int ownerSource, boolean ownerFresh,
                             int incomingSource, int event) {
        if (!navigationEnabled) return DECISION_DENY_DISABLED;
        if (!ingressAllowed(true, selectedMode, incomingSource)) {
            return DECISION_DENY_SELECTED_SOURCE;
        }

        // A strict mode has exactly one possible writer, so no Auto ownership conflict exists.
        if (selectedMode != MODE_AUTO) {
            if (event == EVENT_ROUTE_ACTIVE) return DECISION_ALLOW_CLAIM;
            if (event == EVENT_ROUTE_END) return DECISION_ALLOW_RELEASE;
            return DECISION_ALLOW;
        }

        if (event == EVENT_ROUTE_ACTIVE) {
            if (ownerSource == SOURCE_NONE || ownerSource == incomingSource || !ownerFresh) {
                return DECISION_ALLOW_CLAIM;
            }
            return DECISION_DENY_FRESH_OWNER;
        }
        if (event == EVENT_ROUTE_END) {
            if (ownerSource == SOURCE_NONE) return DECISION_ALLOW;
            return ownerSource == incomingSource
                    ? DECISION_ALLOW_RELEASE : DECISION_DENY_FRESH_OWNER;
        }

        // Passive metadata, speed and lifecycle packets may follow their owner, but they may not
        // steal or clear a route merely because the other owner's freshness deadline elapsed.
        return ownerSource == SOURCE_NONE || ownerSource == incomingSource
                ? DECISION_ALLOW : DECISION_DENY_FRESH_OWNER;
    }

    public static String sourceName(int source) {
        if (source == SOURCE_YANDEX) return "yandex";
        if (source == SOURCE_DGIS) return "2gis";
        return "none";
    }

    public static String decisionName(int decision) {
        switch (decision) {
            case DECISION_DENY_DISABLED:
                return "navigation_disabled";
            case DECISION_DENY_SELECTED_SOURCE:
                return "source_not_selected";
            case DECISION_DENY_FRESH_OWNER:
                return "other_route_owner";
            case DECISION_ALLOW_CLAIM:
                return "claim";
            case DECISION_ALLOW_RELEASE:
                return "release";
            case DECISION_ALLOW:
            default:
                return "allow";
        }
    }

    private static boolean validSource(int source) {
        return source == SOURCE_YANDEX || source == SOURCE_DGIS;
    }
}
