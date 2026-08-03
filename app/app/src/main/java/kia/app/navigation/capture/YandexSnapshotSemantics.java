package kia.app.navigation.capture;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure key classification shared by snapshot signature/coalescing tests. */
final class YandexSnapshotSemantics {
    static final String MICRO_ABSENT_IDENTITY = "micro|absent";
    private static final Pattern DISTANCE_NUMBER = Pattern.compile(
            "[-+]?\\d(?:[\\d\\s\\u00a0\\u202f]*\\d)?(?:[\\.,]\\d+)?");

    private YandexSnapshotSemantics() {
    }

    static boolean isEnvelopeKey(String key) {
        String clean = normalize(key);
        return "seq".equals(clean)
                || "sequence".equals(clean)
                || "timestampms".equals(clean)
                || "timestampelapsedms".equals(clean)
                || "elapsedrealtimems".equals(clean)
                || "updatedatelapsedms".equals(clean)
                || "freshnessms".equals(clean)
                || "bridgefreshnessms".equals(clean)
                || "walltimems".equals(clean)
                || "snapshotjson".equals(clean)
                || "rawsnapshotjson".equals(clean)
                || "broadcastreceivedat".equals(clean)
                || "receivedatelapsedms".equals(clean)
                || "locationrelativetimestampms".equals(clean)
                || "locationrelativetimestamp".equals(clean)
                || "locationelapsedrealtimems".equals(clean)
                || "locationabsolutetimestampms".equals(clean)
                || "locationabsolutetimestamp".equals(clean)
                || "locationtimestampms".equals(clean);
    }

    static boolean isMicroKey(String key) {
        String clean = normalize(key);
        return clean.startsWith("lane")
                || clean.startsWith("micro")
                || clean.startsWith("recommendedlane")
                || clean.startsWith("ignoredlane")
                || clean.startsWith("highlighteddirection")
                || clean.startsWith("roadscheme")
                || clean.startsWith("directionsign");
    }

    static long distanceMeters(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return -1L;
        Matcher matcher = DISTANCE_NUMBER.matcher(text);
        if (!matcher.find()) return -1L;
        String numeric = matcher.group()
                .replace(" ", "")
                .replace("\u00a0", "")
                .replace("\u202f", "")
                .replace(',', '.');
        double parsed;
        try {
            parsed = Double.parseDouble(numeric);
        } catch (RuntimeException ignored) {
            return -1L;
        }
        if (parsed < 0d) return -1L;
        String lower = text.toLowerCase(Locale.US);
        boolean km = lower.contains("км") || lower.contains("km");
        return Math.round(km ? parsed * 1000d : parsed);
    }

    static String microIdentity(boolean hasEnvelope, String... semanticParts) {
        if (!hasEnvelope) return "";
        StringBuilder out = new StringBuilder("micro");
        if (semanticParts != null) {
            for (String part : semanticParts) {
                String clean = part == null ? "" : part.trim().toLowerCase(Locale.US);
                if (!clean.isEmpty()) out.append('|').append(clean);
            }
        }
        return out.length() == "micro".length() ? "micro|none" : out.toString();
    }

    static String microStateIdentity(boolean hasEnvelope, String... semanticParts) {
        String identity = microIdentity(hasEnvelope, semanticParts);
        if (identity.isEmpty() || "micro|none".equals(identity)) {
            return MICRO_ABSENT_IDENTITY;
        }
        return identity;
    }

    /**
     * Identity used only for queue coalescing. Distances and volatile envelope
     * fields are intentionally not arguments: two distance-only updates may be
     * replaced, while route, main-event and micro transitions must stay ordered.
     */
    static String coalescingIdentity(String... semanticParts) {
        StringBuilder out = new StringBuilder("snapshot");
        if (semanticParts == null) return out.toString();
        for (String part : semanticParts) {
            String clean = part == null ? "" : part.trim().toLowerCase(Locale.US);
            out.append('|').append(clean.length()).append(':').append(clean);
        }
        return out.toString();
    }

    static boolean shouldEmitManeuver(String mainManeuver, String mainDistance,
                                      boolean semanticMicroPresent) {
        return mainManeuver != null && !mainManeuver.trim().isEmpty()
                && ((mainDistance != null && !mainDistance.trim().isEmpty())
                || semanticMicroPresent);
    }

    /**
     * The Yandex bridge attaches annotation exit metadata to generic keys. Keep an
     * explicitly prefixed value authoritative, then accept that producer contract.
     */
    static String roundaboutExitForProvenance(String provenance,
                                              String annotationExit,
                                              String notificationExit,
                                              String genericExit) {
        if ("annotation".equals(provenance)) {
            return firstNonEmpty(annotationExit, genericExit);
        }
        if ("notification".equals(provenance)) {
            return firstNonEmpty(notificationExit, genericExit);
        }
        return firstNonEmpty(genericExit);
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.US)
                .replace("_", "")
                .replace("-", "");
    }
}
