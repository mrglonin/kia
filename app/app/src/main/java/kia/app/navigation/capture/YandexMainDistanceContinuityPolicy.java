package kia.app.navigation.capture;

import java.util.Locale;

/** Pure continuity rule for transient zero main-distance samples during micro guidance. */
final class YandexMainDistanceContinuityPolicy {
    static final class ZeroContinuity {
        final boolean handled;
        final long outputMeters;
        final long stateMeters;
        final long stateRouteRemaining;
        final String mode;

        ZeroContinuity(boolean handled, long outputMeters, long stateMeters,
                       long stateRouteRemaining, String mode) {
            this.handled = handled;
            this.outputMeters = outputMeters;
            this.stateMeters = stateMeters;
            this.stateRouteRemaining = stateRouteRemaining;
            this.mode = clean(mode);
        }
    }

    private YandexMainDistanceContinuityPolicy() {
    }

    static String identity(String routeId, String provenance, String maneuver) {
        String route = lower(routeId);
        String main = lower(maneuver).replace('-', '_');
        if (route.isEmpty() || main.isEmpty()) return "";
        if (main.contains("roundabout") || main.contains("circular")
                || main.contains("круг") || main.contains("кольц")) {
            main = "roundabout";
        }
        return route + "|" + lower(provenance) + "|" + main;
    }

    static String selectProvenanceDistance(boolean sameMainIdentity,
                                           String provenanceDistance,
                                           String genericDistance) {
        String specific = clean(provenanceDistance);
        String generic = clean(genericDistance);
        long specificMeters = YandexSnapshotSemantics.distanceMeters(specific);
        long genericMeters = YandexSnapshotSemantics.distanceMeters(generic);
        if (specificMeters > 1L) return specific;
        if (sameMainIdentity && genericMeters > 1L) return generic;
        if (!specific.isEmpty()) return specific;
        if (sameMainIdentity && !generic.isEmpty()) return generic;
        return "0 м";
    }

    static long select(String previousIdentity,
                       String currentIdentity,
                       long previousMeters,
                       long incomingMeters,
                       long previousRouteRemaining,
                       long currentRouteRemaining,
                       boolean semanticMicro,
                       long maxDeltaMeters,
                       long zeroMeters) {
        ZeroContinuity result = resolveTransientZero(
                previousIdentity, currentIdentity,
                previousMeters, incomingMeters,
                previousRouteRemaining, currentRouteRemaining,
                semanticMicro, maxDeltaMeters, 0L, zeroMeters);
        return result.handled ? result.outputMeters : incomingMeters;
    }

    static boolean preservePreviousAfterRejectedZero(String previousIdentity,
                                                     String currentIdentity,
                                                     long previousMeters,
                                                     long incomingMeters,
                                                     long previousRouteRemaining,
                                                     long currentRouteRemaining,
                                                     boolean semanticMicro,
                                                     long maxDeltaMeters,
                                                     long maxRouteGrowthMeters,
                                                     long zeroMeters) {
        ZeroContinuity result = resolveTransientZero(
                previousIdentity, currentIdentity,
                previousMeters, incomingMeters,
                previousRouteRemaining, currentRouteRemaining,
                semanticMicro, maxDeltaMeters, maxRouteGrowthMeters, zeroMeters);
        return result.handled && result.outputMeters == incomingMeters;
    }

    /**
     * Resolves only transient 0/1-metre samples. A held result intentionally keeps the
     * last non-zero cluster frame on screen; a projected result supplies a fresh main distance.
     */
    static ZeroContinuity resolveTransientZero(String previousIdentity,
                                               String currentIdentity,
                                               long previousMeters,
                                               long incomingMeters,
                                               long previousRouteRemaining,
                                               long currentRouteRemaining,
                                               boolean semanticMicro,
                                               long maxDeltaMeters,
                                               long maxRouteGrowthMeters,
                                               long zeroMeters) {
        ZeroContinuity unhandled = new ZeroContinuity(
                false, incomingMeters, previousMeters, previousRouteRemaining, "");
        if (incomingMeters < 0L || incomingMeters > zeroMeters
                || !semanticMicro || previousMeters <= zeroMeters) {
            return unhandled;
        }
        String previousKey = clean(previousIdentity);
        String currentKey = clean(currentIdentity);
        if (previousKey.isEmpty() || !previousKey.equals(currentKey)) return unhandled;

        if (previousRouteRemaining < 0L || currentRouteRemaining < 0L) {
            long anchoredRoute = previousRouteRemaining < 0L && currentRouteRemaining >= 0L
                    ? currentRouteRemaining : previousRouteRemaining;
            String mode = previousRouteRemaining < 0L && currentRouteRemaining >= 0L
                    ? "route_anchored" : "route_unknown_preserved";
            return new ZeroContinuity(
                    true, incomingMeters, previousMeters, anchoredRoute,
                    mode);
        }
        if (maxDeltaMeters < 0L) return unhandled;

        long routeDelta = previousRouteRemaining - currentRouteRemaining;
        if (routeDelta >= 0L && routeDelta <= maxDeltaMeters) {
            long projected = previousMeters - routeDelta;
            if (projected > zeroMeters) {
                return new ZeroContinuity(
                        true, projected, projected, currentRouteRemaining, "projected");
            }
            return unhandled;
        }
        if (routeDelta < 0L
                && -routeDelta <= Math.max(0L, maxRouteGrowthMeters)) {
            return new ZeroContinuity(
                    true, incomingMeters, previousMeters, previousRouteRemaining,
                    "route_jitter_preserved");
        }
        return unhandled;
    }

    static String selectDistanceForProvenance(String provenance,
                                              boolean annotationMatchesRaw,
                                              String annotationDistance,
                                              String notificationDistance,
                                              String genericDistance) {
        String source = lower(provenance);
        if ("annotation".equals(source)) {
            return selectProvenanceDistance(
                    annotationMatchesRaw, annotationDistance, genericDistance);
        }
        if ("notification".equals(source)) {
            if (clean(notificationDistance).isEmpty() && clean(genericDistance).isEmpty()) {
                return "";
            }
            return selectProvenanceDistance(
                    true, notificationDistance, genericDistance);
        }
        return clean(genericDistance);
    }

    static String[] synchronizedDistanceMeterKeys(String provenance) {
        String source = lower(provenance);
        if ("annotation".equals(source)) {
            return new String[]{
                    "maneuver_distance_meters",
                    "current_maneuver_distance_meters",
                    "distance_to_maneuver_meters",
                    "annotation_maneuver_distance_meters",
                    "annotation_distance_to_maneuver_meters",
                    "guide_maneuver_distance_meters",
                    "guide_distance_to_maneuver_meters",
                    "displayed_annotation_maneuver_distance_meters",
                    "displayed_annotation_distance_to_maneuver_meters",
                    "current_annotation_maneuver_distance_meters",
                    "current_annotation_distance_to_maneuver_meters"
            };
        }
        if ("notification".equals(source)) {
            return new String[]{
                    "maneuver_distance_meters",
                    "current_maneuver_distance_meters",
                    "distance_to_maneuver_meters",
                    "notification_maneuver_distance_meters",
                    "notification_distance_to_maneuver_meters"
            };
        }
        return new String[]{
                "maneuver_distance_meters",
                "current_maneuver_distance_meters",
                "distance_to_maneuver_meters"
        };
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String lower(String value) {
        return clean(value).toLowerCase(Locale.US);
    }
}
