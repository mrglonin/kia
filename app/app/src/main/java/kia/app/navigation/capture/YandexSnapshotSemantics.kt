package kia.app.navigation.capture

import java.util.Locale
import java.util.regex.Pattern

/** Pure key classification shared by snapshot signature/coalescing tests. */
internal object YandexSnapshotSemantics {
    const val MICRO_ABSENT_IDENTITY: String = "micro|absent"
    private val DISTANCE_NUMBER: Pattern? =
        Pattern.compile("[-+]?\\d(?:[\\d\\s\\u00a0\\u202f]*\\d)?(?:[\\.,]\\d+)?")

    @JvmStatic
    fun isEnvelopeKey(key: String?): Boolean {
        val clean = YandexSnapshotSemantics.normalize(key)
        return "seq".equals(clean) ||
            "sequence".equals(clean) ||
            "timestampms".equals(clean) ||
            "timestampelapsedms".equals(clean) ||
            "elapsedrealtimems".equals(clean) ||
            "updatedatelapsedms".equals(clean) ||
            "freshnessms".equals(clean) ||
            "bridgefreshnessms".equals(clean) ||
            "walltimems".equals(clean) ||
            "snapshotjson".equals(clean) ||
            "rawsnapshotjson".equals(clean) ||
            "broadcastreceivedat".equals(clean) ||
            "receivedatelapsedms".equals(clean) ||
            "locationrelativetimestampms".equals(clean) ||
            "locationrelativetimestamp".equals(clean) ||
            "locationelapsedrealtimems".equals(clean) ||
            "locationabsolutetimestampms".equals(clean) ||
            "locationabsolutetimestamp".equals(clean) ||
            "locationtimestampms".equals(clean)
    }

    @JvmStatic
    fun isMicroKey(key: String?): Boolean {
        val clean = YandexSnapshotSemantics.normalize(key)
        return clean.startsWith("lane") ||
            clean.startsWith("micro") ||
            clean.startsWith("recommendedlane") ||
            clean.startsWith("ignoredlane") ||
            clean.startsWith("highlighteddirection") ||
            clean.startsWith("roadscheme") ||
            clean.startsWith("directionsign")
    }

    @JvmStatic
    fun distanceMeters(value: String?): Long {
        val text = if (value == null) "" else value.trim()
        if (text.isEmpty()) return -1L
        val matcher = YandexSnapshotSemantics.DISTANCE_NUMBER!!.matcher(text)
        if (!matcher.find()) return -1L
        val numeric =
            matcher
                .group()
                .replace(" ", "")
                .replace("\u00a0", "")
                .replace("\u202f", "")
                .replace(',', '.')
        val parsed: Double
        try {
            parsed = java.lang.Double.parseDouble(numeric)
        } catch (ignored: RuntimeException) {
            return -1L
        }
        if (parsed < 0.0) return -1L
        val lower = text.lowercase(Locale.US)
        val km: Boolean = lower.contains("км") || lower.contains("km")
        return Math.round(if (km) parsed * 1000.0 else parsed)
    }

    @JvmStatic
    fun microIdentity(hasEnvelope: Boolean, vararg semanticParts: String?): String {
        if (!hasEnvelope) return ""
        val out: StringBuilder = StringBuilder("micro")
        if (semanticParts != null) {
            for (part: String? in semanticParts) {
                val clean = if (part == null) "" else part.trim().lowercase(Locale.US)
                if (!clean.isEmpty()) out.append('|').append(clean)
            }
        }
        return if (out.length == "micro".length) "micro|none" else out.toString()
    }

    @JvmStatic
    fun microStateIdentity(hasEnvelope: Boolean, vararg semanticParts: String?): String? {
        val identity = YandexSnapshotSemantics.microIdentity(hasEnvelope, *semanticParts)
        if (identity.isEmpty() || "micro|none".equals(identity)) {
            return YandexSnapshotSemantics.MICRO_ABSENT_IDENTITY
        }
        return identity
    }

    @JvmStatic
    /**
     * Identity used only for queue coalescing. Distances and volatile envelope fields are
     * intentionally not arguments: two distance-only updates may be replaced, while route,
     * main-event and micro transitions must stay ordered.
     */
    fun coalescingIdentity(vararg semanticParts: String?): String {
        val out: StringBuilder = StringBuilder("snapshot")
        if (semanticParts == null) return out.toString()
        for (part: String? in semanticParts) {
            val clean = if (part == null) "" else part.trim().lowercase(Locale.US)
            out.append('|').append(clean.length).append(':').append(clean)
        }
        return out.toString()
    }

    @JvmStatic
    fun shouldEmitManeuver(
        mainManeuver: String?,
        mainDistance: String?,
        semanticMicroPresent: Boolean,
    ): Boolean {
        return mainManeuver != null &&
            !mainManeuver.trim().isEmpty() &&
            ((mainDistance != null && !mainDistance.trim().isEmpty()) || semanticMicroPresent)
    }

    @JvmStatic
    /**
     * The Yandex bridge attaches annotation exit metadata to generic keys. Keep an explicitly
     * prefixed value authoritative, then accept that producer contract.
     */
    fun roundaboutExitForProvenance(
        provenance: String?,
        annotationExit: String?,
        notificationExit: String?,
        genericExit: String?,
    ): String {
        if ("annotation".equals(provenance)) {
            return YandexSnapshotSemantics.firstNonEmpty(annotationExit, genericExit)
        }
        if ("notification".equals(provenance)) {
            return YandexSnapshotSemantics.firstNonEmpty(notificationExit, genericExit)
        }
        return YandexSnapshotSemantics.firstNonEmpty(genericExit)
    }

    private fun firstNonEmpty(vararg values: String?): String {
        if (values == null) return ""
        for (value: String? in values) {
            if (value != null && !value.trim().isEmpty()) return value.trim()
        }
        return ""
    }

    private fun normalize(value: String?): String {
        if (value == null) return ""
        return value.trim().lowercase(Locale.US).replace("_", "").replace("-", "")
    }
}
