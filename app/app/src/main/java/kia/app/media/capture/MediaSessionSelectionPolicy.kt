package kia.app.media.capture

import java.util.HashSet
import java.util.Locale

/**
 * Pure selection policy for Universal Android/UART Real MediaSession candidates.
 *
 * The Android framework already orders active controllers by importance. This policy preserves that
 * order as the tie-breaker while allowing one preferred package and a package denylist.
 */
internal object MediaSessionSelectionPolicy {
    const val NONE: Int = -1

    @JvmStatic
    fun selectIndex(
        candidates: List<Candidate?>?,
        preferredPackage: String?,
        blockedPackages: Set<String?>?,
    ): Int {
        if (candidates == null || candidates.isEmpty()) return MediaSessionSelectionPolicy.NONE
        val preferred: String? = MediaSessionSelectionPolicy.normalizePackage(preferredPackage)
        val blocked: Set<String?> =
            MediaSessionSelectionPolicy.normalizedPackages(blockedPackages)!!
        var firstAllowed: Int = MediaSessionSelectionPolicy.NONE
        var firstPlaying: Int = MediaSessionSelectionPolicy.NONE
        var preferredFallback: Int = MediaSessionSelectionPolicy.NONE

        for (index in 0..<candidates.size) {
            val candidate: Candidate? = candidates.get(index)
            if (
                candidate == null ||
                    candidate.packageName!!.isEmpty() ||
                    blocked.contains(candidate.packageName)
            ) {
                continue
            }
            if (firstAllowed == MediaSessionSelectionPolicy.NONE) firstAllowed = index
            val preferredCandidate: Boolean =
                !preferred!!.isEmpty() && preferred.equals(candidate.packageName)
            if (candidate.playing) {
                if (preferredCandidate) return index
                if (firstPlaying == MediaSessionSelectionPolicy.NONE) firstPlaying = index
            } else if (
                preferredCandidate && preferredFallback == MediaSessionSelectionPolicy.NONE
            ) {
                preferredFallback = index
            }
        }

        if (firstPlaying != MediaSessionSelectionPolicy.NONE) return firstPlaying
        if (preferredFallback != MediaSessionSelectionPolicy.NONE) return preferredFallback
        return firstAllowed
    }

    private fun normalizedPackages(packages: Set<String?>?): Set<String?>? {
        val out: MutableSet<String?> = HashSet()
        if (packages == null) return out
        for (packageName: String? in packages) {
            val clean: String? = MediaSessionSelectionPolicy.normalizePackage(packageName)
            if (!clean!!.isEmpty()) out.add(clean)
        }
        return out
    }

    @JvmStatic
    fun normalizePackage(value: String?): String? {
        return if (value == null) "" else value.trim().lowercase(Locale.US)
    }

    internal class Candidate(packageName: String?, playing: Boolean) {
        @JvmField val packageName: String?
        @JvmField val playing: Boolean

        init {
            this.packageName = MediaSessionSelectionPolicy.normalizePackage(packageName)
            this.playing = playing
        }
    }
}
