package kia.app.media.capture;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure selection policy for Universal Android/UART Real MediaSession candidates.
 *
 * <p>The Android framework already orders active controllers by importance. This policy preserves
 * that order as the tie-breaker while allowing one preferred package and a package denylist.
 */
final class MediaSessionSelectionPolicy {
    static final int NONE = -1;

    private MediaSessionSelectionPolicy() {
    }

    static int selectIndex(List<Candidate> candidates, String preferredPackage,
                           Set<String> blockedPackages) {
        if (candidates == null || candidates.isEmpty()) return NONE;
        String preferred = normalizePackage(preferredPackage);
        Set<String> blocked = normalizedPackages(blockedPackages);
        int firstAllowed = NONE;
        int firstPlaying = NONE;
        int preferredFallback = NONE;

        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            if (candidate == null || candidate.packageName.isEmpty()
                    || blocked.contains(candidate.packageName)) {
                continue;
            }
            if (firstAllowed == NONE) firstAllowed = index;
            boolean preferredCandidate = !preferred.isEmpty()
                    && preferred.equals(candidate.packageName);
            if (candidate.playing) {
                if (preferredCandidate) return index;
                if (firstPlaying == NONE) firstPlaying = index;
            } else if (preferredCandidate && preferredFallback == NONE) {
                preferredFallback = index;
            }
        }

        if (firstPlaying != NONE) return firstPlaying;
        if (preferredFallback != NONE) return preferredFallback;
        return firstAllowed;
    }

    private static Set<String> normalizedPackages(Set<String> packages) {
        Set<String> out = new HashSet<>();
        if (packages == null) return out;
        for (String packageName : packages) {
            String clean = normalizePackage(packageName);
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    static String normalizePackage(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    static final class Candidate {
        final String packageName;
        final boolean playing;

        Candidate(String packageName, boolean playing) {
            this.packageName = normalizePackage(packageName);
            this.playing = playing;
        }
    }
}
