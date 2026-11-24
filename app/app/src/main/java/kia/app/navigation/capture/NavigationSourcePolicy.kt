package kia.app.navigation.capture

/**
 * Pure source-selection and ownership decisions for navigation ingress.
 *
 * Mode values intentionally match the persisted navigation source values: `0=Auto, 1=Yandex,
 * 2=2GIS, 3=MVite`. Incoming source values match the explicit modes, with [.SOURCE_NONE] reserved
 * for an unowned route.
 */
object NavigationSourcePolicy {
    const val MODE_AUTO: Int = 0
    const val MODE_YANDEX: Int = 1
    const val MODE_DGIS: Int = 2
    const val MODE_MVITE: Int = 3

    const val SOURCE_NONE: Int = 0
    const val SOURCE_YANDEX: Int = 1
    const val SOURCE_DGIS: Int = 2
    const val SOURCE_MVITE: Int = 3

    const val EVENT_PASSIVE: Int = 0
    const val EVENT_ROUTE_ACTIVE: Int = 1
    const val EVENT_ROUTE_END: Int = 2

    const val DECISION_DENY_DISABLED: Int = 0
    const val DECISION_DENY_SELECTED_SOURCE: Int = 1
    const val DECISION_DENY_FRESH_OWNER: Int = 2
    const val DECISION_ALLOW: Int = 3
    const val DECISION_ALLOW_CLAIM: Int = 4
    const val DECISION_ALLOW_RELEASE: Int = 5

    @JvmStatic
    fun ingressAllowed(
        navigationEnabled: Boolean,
        selectedMode: Int,
        incomingSource: Int,
    ): Boolean {
        if (!navigationEnabled || !NavigationSourcePolicy.validSource(incomingSource)) return false
        if (selectedMode == NavigationSourcePolicy.MODE_YANDEX)
            return incomingSource == NavigationSourcePolicy.SOURCE_YANDEX
        if (selectedMode == NavigationSourcePolicy.MODE_DGIS)
            return incomingSource == NavigationSourcePolicy.SOURCE_DGIS
        if (selectedMode == NavigationSourcePolicy.MODE_MVITE)
            return incomingSource == NavigationSourcePolicy.SOURCE_MVITE
        if (incomingSource == NavigationSourcePolicy.SOURCE_MVITE) return false
        return selectedMode == NavigationSourcePolicy.MODE_AUTO
    }

    @JvmStatic
    fun decide(
        navigationEnabled: Boolean,
        selectedMode: Int,
        ownerSource: Int,
        ownerFresh: Boolean,
        incomingSource: Int,
        event: Int,
    ): Int {
        if (!navigationEnabled) return NavigationSourcePolicy.DECISION_DENY_DISABLED
        if (!NavigationSourcePolicy.ingressAllowed(true, selectedMode, incomingSource)) {
            return NavigationSourcePolicy.DECISION_DENY_SELECTED_SOURCE
        }

        // A strict mode has exactly one possible writer, so no Auto ownership conflict exists.
        if (selectedMode != NavigationSourcePolicy.MODE_AUTO) {
            if (event == NavigationSourcePolicy.EVENT_ROUTE_ACTIVE)
                return NavigationSourcePolicy.DECISION_ALLOW_CLAIM
            if (event == NavigationSourcePolicy.EVENT_ROUTE_END)
                return NavigationSourcePolicy.DECISION_ALLOW_RELEASE
            return NavigationSourcePolicy.DECISION_ALLOW
        }

        if (event == NavigationSourcePolicy.EVENT_ROUTE_ACTIVE) {
            if (
                ownerSource == NavigationSourcePolicy.SOURCE_NONE ||
                    ownerSource == incomingSource ||
                    !ownerFresh
            ) {
                return NavigationSourcePolicy.DECISION_ALLOW_CLAIM
            }
            return NavigationSourcePolicy.DECISION_DENY_FRESH_OWNER
        }
        if (event == NavigationSourcePolicy.EVENT_ROUTE_END) {
            if (ownerSource == NavigationSourcePolicy.SOURCE_NONE)
                return NavigationSourcePolicy.DECISION_ALLOW
            return if (ownerSource == incomingSource) NavigationSourcePolicy.DECISION_ALLOW_RELEASE
            else NavigationSourcePolicy.DECISION_DENY_FRESH_OWNER
        }

        // Passive metadata, speed and lifecycle packets may follow their owner, but they may not
        // steal or clear a route merely because the other owner's freshness deadline elapsed.
        return if (
            ownerSource == NavigationSourcePolicy.SOURCE_NONE || ownerSource == incomingSource
        )
            NavigationSourcePolicy.DECISION_ALLOW
        else NavigationSourcePolicy.DECISION_DENY_FRESH_OWNER
    }

    @JvmStatic
    fun sourceName(source: Int): String {
        if (source == NavigationSourcePolicy.SOURCE_YANDEX) return "yandex"
        if (source == NavigationSourcePolicy.SOURCE_DGIS) return "2gis"
        if (source == NavigationSourcePolicy.SOURCE_MVITE) return "mvite"
        return "none"
    }

    @JvmStatic
    fun decisionName(decision: Int): String {
        when (decision) {
            NavigationSourcePolicy.DECISION_DENY_DISABLED -> return "navigation_disabled"
            NavigationSourcePolicy.DECISION_DENY_SELECTED_SOURCE -> return "source_not_selected"
            NavigationSourcePolicy.DECISION_DENY_FRESH_OWNER -> return "other_route_owner"
            NavigationSourcePolicy.DECISION_ALLOW_CLAIM -> return "claim"
            NavigationSourcePolicy.DECISION_ALLOW_RELEASE -> return "release"
            NavigationSourcePolicy.DECISION_ALLOW -> return "allow"
            else -> return "allow"
        }
    }

    @JvmStatic
    fun validSource(source: Int): Boolean {
        return source == NavigationSourcePolicy.SOURCE_YANDEX ||
            source == NavigationSourcePolicy.SOURCE_DGIS ||
            source == NavigationSourcePolicy.SOURCE_MVITE
    }
}
