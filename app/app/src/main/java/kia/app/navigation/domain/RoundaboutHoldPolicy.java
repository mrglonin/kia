package kia.app.navigation.domain;

/** Pure rules for the short post-entry roundabout hold. */
final class RoundaboutHoldPolicy {
    private RoundaboutHoldPolicy() {
    }

    static boolean canStart(String distanceText, float activationMeters) {
        float meters = ManeuverArbiter.distanceMeters(distanceText);
        return meters > 0f && meters <= activationMeters;
    }

    static boolean shouldStart(String currentKey, long currentDeadline,
                               String incomingKey, String distanceText,
                               long now, float activationMeters) {
        if (!canStart(distanceText, activationMeters)) return false;
        String current = clean(currentKey);
        String incoming = clean(incomingKey);
        if (incoming.isEmpty()) return false;
        return !incoming.equals(current);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
