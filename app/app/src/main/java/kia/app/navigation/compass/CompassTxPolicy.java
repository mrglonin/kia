package kia.app.navigation.compass;

/** Pure transition policy for reasserting the compass after cluster/USB ownership changes. */
final class CompassTxPolicy {
    private CompassTxPolicy() {
    }

    static boolean shouldReassert(boolean sendAllowed, boolean previouslySendAllowed,
                                  boolean usbReady, long connectionEpoch,
                                  long previousConnectionEpoch) {
        return sendAllowed && usbReady
                && (!previouslySendAllowed || connectionEpoch != previousConnectionEpoch);
    }

    static boolean shouldKeepAlive(boolean sendAllowed, boolean usbReady,
                                   long nowElapsedMs, long lastSuccessfulTxElapsedMs,
                                   long keepAliveMs) {
        return sendAllowed && usbReady
                && nowElapsedMs > 0L
                && lastSuccessfulTxElapsedMs > 0L
                && lastSuccessfulTxElapsedMs <= nowElapsedMs
                && keepAliveMs >= 0L
                && nowElapsedMs - lastSuccessfulTxElapsedMs >= keepAliveMs;
    }

    static boolean retryAllowed(int step, int lastAttemptedStep,
                                long nowElapsedMs, long lastAttemptElapsedMs,
                                long retryIntervalMs) {
        if (step != lastAttemptedStep || lastAttemptElapsedMs <= 0L) return true;
        return retryIntervalMs >= 0L
                && lastAttemptElapsedMs <= nowElapsedMs
                && nowElapsedMs - lastAttemptElapsedMs >= retryIntervalMs;
    }

    static int headingStep(float heading, boolean kiaNaviFormat) {
        float normalized = heading % 360f;
        if (normalized < 0f) normalized += 360f;
        float degreesPerSector = kiaNaviFormat ? 22.5f : 30f;
        return normalizeStep(Math.round(normalized / degreesPerSector) * 3, kiaNaviFormat);
    }

    static int nextStep(int current, int target, boolean kiaNaviFormat) {
        int fullCircle = kiaNaviFormat ? 48 : 36;
        int forward = (target - current + fullCircle) % fullCircle;
        int backward = (current - target + fullCircle) % fullCircle;
        if (forward == 0) return target;
        return normalizeStep(current + (forward <= backward ? 3 : -3), kiaNaviFormat);
    }

    static int storedStep(String clusterTx, boolean kiaNaviFormat) {
        String text = clusterTx == null ? "" : clusterTx.trim();
        if (text.isEmpty()) return -1;
        String[] lines = text.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.startsWith("compass step=") || !line.endsWith(" outcome=WRITTEN")) continue;
            // Only the last delivered frame can describe the direction actually on the cluster.
            if (line.contains(" format=kia-navi ") != kiaNaviFormat) return -1;
            String tail = line.substring("compass step=".length()).trim();
            int space = tail.indexOf(' ');
            String token = space >= 0 ? tail.substring(0, space).trim() : tail;
            try {
                return normalizeStep(Integer.parseInt(token), kiaNaviFormat);
            } catch (RuntimeException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static int normalizeStep(int step, boolean kiaNaviFormat) {
        int fullCircle = kiaNaviFormat ? 48 : 36;
        int out = ((step % fullCircle) + fullCircle) % fullCircle;
        out = Math.round(out / 3f) * 3;
        return out == fullCircle ? 0 : out;
    }
}
