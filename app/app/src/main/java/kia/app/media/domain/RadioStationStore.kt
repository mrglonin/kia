package kia.app.media.domain

import android.content.Context
import android.content.SharedPreferences
import java.util.ArrayList
import java.util.Collections
import java.util.Locale
import kia.app.core.model.MediaState

object RadioStationStore {
    private val PREFS: String = "kia_radio_stations"
    private val PREFIX: String = "station_"
    private val MANUAL_PREFIX: String = "manual_"
    private val ORIGIN_PREFIX: String = "origin_"
    private val ORIGIN_MANUAL: String = "manual"
    private val ORIGIN_LEARNED: String = "learned"
    private val ORIGIN_FALLBACK: String = "fallback"

    @JvmStatic
    fun resolve(
        context: Context?,
        source: String?,
        frequency: String?,
        candidateName: String?,
    ): String? {
        val band: String = RadioStationStore.band(source, frequency)!!
        val normalized: String? = RadioStationStore.normalizeFrequency(source, frequency)
        val learned: String? = RadioStationStore.cleanStationName(candidateName)
        if (RadioStationStore.empty(normalized)) return learned
        if (!RadioStationStore.empty(learned)) {
            RadioStationStore.setLegacyStationName(context, band, normalized, learned)
            return learned
        }
        val stored: String? = RadioStationStore.stationName(context, band, normalized)
        if (!RadioStationStore.empty(stored)) return stored
        val fallback: String = RadioStationStore.defaultLabel(band, normalized)!!
        RadioStationStore.ensureLegacyStation(context, band, normalized, fallback)
        return fallback
    }

    @JvmStatic
    fun resolveUniversal(
        context: Context?,
        source: String?,
        frequency: String?,
        candidateName: String?,
    ): String? {
        val band: String = RadioStationStore.band(source, frequency)!!
        val normalized: String? = RadioStationStore.normalizeFrequency(source, frequency)
        val learned: String? = RadioStationStore.cleanStationName(candidateName)
        if (RadioStationStore.empty(normalized)) return learned
        if (context == null) {
            return if (RadioStationStore.empty(learned))
                RadioStationStore.defaultLabel(band, normalized)
            else learned
        }

        val stored: StoredStation? = RadioStationStore.storedStation(context, band, normalized)
        if (stored != null && RadioStationStore.ORIGIN_MANUAL.equals(stored.origin)) {
            return stored.name
        }
        if (!RadioStationStore.empty(learned)) {
            if (
                stored == null ||
                    !RadioStationStore.same(stored.name, learned) ||
                    !RadioStationStore.ORIGIN_LEARNED.equals(stored.origin)
            ) {
                RadioStationStore.writeStation(
                    context,
                    band,
                    normalized,
                    learned,
                    RadioStationStore.ORIGIN_LEARNED,
                )
            }
            return learned
        }
        if (stored != null && !RadioStationStore.empty(stored.name)) return stored.name

        val fallback: String = RadioStationStore.defaultLabel(band, normalized)!!
        RadioStationStore.ensureStation(
            context,
            band,
            normalized,
            fallback,
            RadioStationStore.ORIGIN_FALLBACK,
        )
        return fallback
    }

    @JvmStatic
    fun currentFrequency(media: MediaState?): String {
        if (media == null) return ""
        if (!RadioStationStore.isRadio(media.source, media.packageName)) return ""
        return RadioStationStore.normalizeFrequency(media.source, media.artist)
    }

    @JvmStatic
    fun currentBand(media: MediaState?): String {
        if (media == null) return "FM"
        return RadioStationStore.band(media.source, media.artist)
    }

    @JvmStatic
    fun setStationName(context: Context?, source: String?, frequency: String?, name: String?) {
        val normalized: String? = RadioStationStore.normalizeFrequency(source, frequency)
        if (RadioStationStore.empty(normalized)) return
        RadioStationStore.setStationNameForBand(
            context,
            RadioStationStore.band(source, frequency),
            normalized,
            name,
        )
    }

    @JvmStatic
    fun setStationNameForBand(context: Context?, band: String?, frequency: String?, name: String?) {
        if (context == null) return
        val cleanBand: String = RadioStationStore.cleanBand(band)
        val normalized: String? = RadioStationStore.normalizeFrequency(cleanBand, frequency)
        val cleanName: String? = RadioStationStore.cleanStationName(name)
        if (RadioStationStore.empty(normalized)) return
        if (RadioStationStore.empty(cleanName)) {
            RadioStationStore.writeStation(
                context,
                cleanBand,
                normalized,
                RadioStationStore.defaultLabel(cleanBand, normalized),
                RadioStationStore.ORIGIN_FALLBACK,
            )
            return
        }
        RadioStationStore.writeStation(
            context,
            cleanBand,
            normalized,
            cleanName,
            RadioStationStore.ORIGIN_MANUAL,
        )
    }

    @JvmStatic
    fun saveManualStation(
        context: Context?,
        band: String?,
        frequency: String?,
        name: String?,
    ): Boolean {
        if (context == null) return false
        val cleanBand: String = RadioStationStore.cleanBand(band)
        val normalized: String? = RadioStationStore.normalizeFrequency(cleanBand, frequency)
        val cleanName: String? = RadioStationStore.cleanStationName(name)
        if (RadioStationStore.empty(normalized) || RadioStationStore.empty(cleanName)) return false
        RadioStationStore.writeStation(
            context,
            cleanBand,
            normalized,
            cleanName,
            RadioStationStore.ORIGIN_MANUAL,
        )
        return true
    }

    @JvmStatic
    fun normalizeFrequencyInput(band: String?, frequency: String?): String {
        return RadioStationStore.normalizeFrequency(RadioStationStore.cleanBand(band), frequency)
    }

    @JvmStatic
    fun displayName(band: String?, frequency: String?, stationName: String?): String {
        val cleanName = RadioStationStore.clean(stationName)
        if (RadioStationStore.empty(cleanName)) return ""
        val cleanBand: String = RadioStationStore.cleanBand(band)
        val normalized: String? = RadioStationStore.normalizeFrequency(cleanBand, frequency)
        val fallbackFrequency: String? =
            if (RadioStationStore.empty(normalized)) RadioStationStore.clean(frequency)
            else normalized
        val fallback: String = RadioStationStore.defaultLabel(cleanBand, fallbackFrequency)!!
        return if (cleanName.equals(fallback, ignoreCase = true)) "" else cleanName
    }

    @JvmStatic
    fun clearStationName(context: Context?, source: String?, frequency: String?) {
        if (context == null) return
        val band: String = RadioStationStore.band(source, frequency)!!
        val normalized: String? = RadioStationStore.normalizeFrequency(source, frequency)
        if (RadioStationStore.empty(normalized)) return
        RadioStationStore.prefs(context)!!.edit()
            .remove(RadioStationStore.key(band, normalized))
            .remove(RadioStationStore.manualKey(band, normalized))
            .remove(RadioStationStore.originKey(band, normalized))
            .apply()
    }

    @JvmStatic
    fun entries(context: Context?): List<Entry> {
        if (context == null) return Collections.emptyList()
        val out: ArrayList<Entry> = ArrayList()
        for (item in RadioStationStore.prefs(context)!!.getAll().entries) {
            val key: String? = item.key
            if (key == null || !key.startsWith(RadioStationStore.PREFIX)) continue
            val value: Any? = item.value
            if (value !is String) continue
            val parsed: Entry? = RadioStationStore.parseEntry(context, key, value as String)
            if (parsed != null) out.add(parsed)
        }
        Collections.sort(
            out,
            { a, b ->
                val band: Int = Integer.compare(a!!.bandRank(), b!!.bandRank())
                if (band != 0) return@sort band
                val frequency: Int =
                    java.lang.Double.compare(a.numericFrequency(), b.numericFrequency())
                if (frequency != 0) return@sort frequency
                a.name!!.compareTo(b.name!!, ignoreCase = true)
            },
        )
        return out
    }

    @JvmStatic
    fun summary(context: Context?, maxItems: Int): String {
        val entries: List<Entry?>? = RadioStationStore.entries(context)
        if (entries!!.isEmpty()) return "станции появятся автоматически после включения радио"
        val out: StringBuilder = StringBuilder()
        val limit: Int = Math.max(1, Math.min(maxItems, entries.size))
        for (i in 0..<limit) {
            if (out.length > 0) out.append('\n')
            val e: Entry? = entries.get(i)
            out.append(e!!.band).append(' ').append(e.frequency).append(" -> ").append(e.name)
        }
        if (entries.size > limit) out.append("\n+").append(entries.size - limit)
        return out.toString()
    }

    private fun ensureStation(
        context: Context?,
        band: String?,
        frequency: String?,
        name: String?,
        origin: String?,
    ) {
        if (context == null) return
        val key: String = RadioStationStore.key(band, frequency)!!
        val prefs: SharedPreferences? = RadioStationStore.prefs(context)
        if (prefs!!.contains(key)) return
        prefs
            .edit()
            .putString(key, name)
            .putString(RadioStationStore.originKey(band, frequency), origin)
            .apply()
    }

    private fun ensureLegacyStation(
        context: Context?,
        band: String?,
        frequency: String?,
        name: String?,
    ) {
        if (context == null) return
        val key: String = RadioStationStore.key(band, frequency)!!
        val prefs: SharedPreferences? = RadioStationStore.prefs(context)
        if (prefs!!.contains(key)) return
        prefs.edit().putString(key, name).apply()
    }

    private fun setLegacyStationName(
        context: Context?,
        band: String?,
        frequency: String?,
        name: String?,
    ) {
        if (context == null) return
        val cleanBand: String = RadioStationStore.cleanBand(band)
        val normalized: String? = RadioStationStore.normalizeFrequency(cleanBand, frequency)
        var cleanName: String? = RadioStationStore.cleanStationName(name)
        if (RadioStationStore.empty(normalized)) return
        if (RadioStationStore.empty(cleanName))
            cleanName = RadioStationStore.defaultLabel(cleanBand, normalized)
        RadioStationStore.prefs(context)!!.edit()
            .putString(RadioStationStore.key(cleanBand, normalized), cleanName)
            .apply()
    }

    private fun stationName(context: Context?, band: String?, frequency: String?): String? {
        if (context == null) return ""
        return RadioStationStore.clean(
            RadioStationStore.prefs(context)!!.getString(RadioStationStore.key(band, frequency), "")
        )
    }

    private fun storedStation(context: Context, band: String?, frequency: String?): StoredStation? {
        val prefs: SharedPreferences? = RadioStationStore.prefs(context)
        val manualName: String? =
            RadioStationStore.clean(
                prefs!!.getString(RadioStationStore.manualKey(band, frequency), "")
            )
        if (!RadioStationStore.empty(manualName)) {
            if (
                !RadioStationStore.same(
                    manualName,
                    RadioStationStore.stationName(context, band, frequency),
                ) ||
                    !RadioStationStore.ORIGIN_MANUAL.equals(
                        RadioStationStore.cleanOrigin(
                            prefs.getString(RadioStationStore.originKey(band, frequency), "")
                        )
                    )
            ) {
                prefs
                    .edit()
                    .putString(RadioStationStore.key(band, frequency), manualName)
                    .putString(
                        RadioStationStore.originKey(band, frequency),
                        RadioStationStore.ORIGIN_MANUAL,
                    )
                    .apply()
            }
            return StoredStation(manualName, RadioStationStore.ORIGIN_MANUAL)
        }

        val name: String? = RadioStationStore.stationName(context, band, frequency)
        if (RadioStationStore.empty(name)) return null
        var origin: String? =
            RadioStationStore.cleanOrigin(
                prefs.getString(RadioStationStore.originKey(band, frequency), "")
            )
        if (RadioStationStore.empty(origin)) {
            origin =
                if (RadioStationStore.same(name, RadioStationStore.defaultLabel(band, frequency)))
                    RadioStationStore.ORIGIN_FALLBACK
                else RadioStationStore.ORIGIN_MANUAL
            val edit: SharedPreferences.Editor? =
                prefs.edit().putString(RadioStationStore.originKey(band, frequency), origin)
            if (RadioStationStore.ORIGIN_MANUAL.equals(origin))
                edit!!.putString(RadioStationStore.manualKey(band, frequency), name)
            edit!!.apply()
        } else if (RadioStationStore.ORIGIN_MANUAL.equals(origin)) {
            prefs.edit().putString(RadioStationStore.manualKey(band, frequency), name).apply()
        }
        return StoredStation(name, origin)
    }

    private fun writeStation(
        context: Context?,
        band: String?,
        frequency: String?,
        name: String?,
        origin: String?,
    ) {
        if (context == null) return
        val cleanBand: String = RadioStationStore.cleanBand(band)
        val normalized: String? = RadioStationStore.normalizeFrequency(cleanBand, frequency)
        val cleanName: String? = RadioStationStore.clean(name)
        if (RadioStationStore.empty(normalized) || RadioStationStore.empty(cleanName)) return
        val edit: SharedPreferences.Editor? =
            RadioStationStore.prefs(context)!!.edit()
                .putString(RadioStationStore.key(cleanBand, normalized), cleanName)
                .putString(
                    RadioStationStore.originKey(cleanBand, normalized),
                    RadioStationStore.cleanOrigin(origin),
                )
        if (RadioStationStore.ORIGIN_MANUAL.equals(origin)) {
            edit!!.putString(RadioStationStore.manualKey(cleanBand, normalized), cleanName)
        } else {
            edit!!.remove(RadioStationStore.manualKey(cleanBand, normalized))
        }
        edit.apply()
    }

    private fun isRadio(source: String?, packageName: String?): Boolean {
        val text: String? =
            RadioStationStore.clean(source.toString() + " " + packageName)!!.lowercase(Locale.US)
        return text!!.contains("fm") ||
            text.contains("am") ||
            text.contains("radio") ||
            text.contains("радио") ||
            text.contains("com.spd.radio")
    }

    private fun band(source: String?, frequency: String?): String {
        val text: String? =
            RadioStationStore.clean(source.toString() + " " + frequency)!!.lowercase(Locale.US)
        if (text!!.contains("am")) return "AM"
        return "FM"
    }

    private fun cleanBand(value: String?): String {
        return if ("AM".equals(RadioStationStore.clean(value), ignoreCase = true)) "AM" else "FM"
    }

    private fun normalizeFrequency(source: String?, value: String?): String {
        val band: String = RadioStationStore.cleanBand(source)
        val text: String? = RadioStationStore.clean(value)!!.replace(',', '.')
        if (RadioStationStore.empty(text)) return ""
        val numeric: String? = RadioStationStore.firstNumeric(text)
        if (RadioStationStore.empty(numeric)) return ""
        try {
            if ("AM".equals(band)) {
                val digits = numeric!!.replace(Regex("[^0-9]"), "")
                return if (RadioStationStore.empty(digits)) "" else digits
            }
            val mhz: Double
            if (numeric!!.contains(".")) {
                mhz = java.lang.Double.parseDouble(numeric)
            } else {
                val raw: Int = Integer.parseInt(numeric)
                if (raw >= 65000 && raw <= 108000) mhz = raw / 1000.0
                else if (raw >= 6500 && raw <= 10800) mhz = raw / 100.0
                else if (raw >= 650 && raw <= 1080) mhz = raw / 10.0
                else if (raw >= 65 && raw <= 108) mhz = raw.toDouble() else return ""
            }
            if (mhz < 60.0 || mhz > 120.0) return ""
            val formatted: String? = String.format(Locale.US, "%.1f", mhz)
            return if (formatted!!.endsWith(".0")) formatted else formatted
        } catch (ignored: Exception) {
            return ""
        }
    }

    private fun firstNumeric(text: String?): String {
        if (text == null) return ""
        for (start in 0..<text.length) {
            val first: Char = text.get(start)
            if (!Character.isDigit(first)) continue
            var end: Int = start
            var dot: Boolean = false
            while (end < text.length) {
                val c: Char = text.get(end)
                if (Character.isDigit(c)) {
                    end++
                } else if (c == '.' && !dot) {
                    dot = true
                    end++
                } else {
                    break
                }
            }
            return text.substring(start, end)
        }
        return ""
    }

    private fun cleanStationName(value: String?): String? {
        val out: String? = RadioStationStore.clean(value)
        if (RadioStationStore.empty(out)) return ""
        if (RadioStationStore.looksLikeFrequency(out)) return ""
        return out
    }

    private fun looksLikeFrequency(value: String?): Boolean {
        val out: String? = RadioStationStore.clean(value)
        return out!!.matches(Regex("(?i)^(FM|AM)?\\s*\\d{2,5}([\\.,]\\d{1,2})?\\s*(MHz|kHz)?$"))
    }

    private fun defaultLabel(band: String?, frequency: String?): String {
        return RadioStationStore.cleanBand(band) + " " + frequency
    }

    private fun parseEntry(context: Context, rawKey: String, name: String?): Entry? {
        val body = rawKey.substring(RadioStationStore.PREFIX.length)
        val sep: Int = body.indexOf('_')
        if (sep <= 0 || sep >= body.length - 1) return null
        val band: String = RadioStationStore.cleanBand(body.substring(0, sep))
        val frequency = body.substring(sep + 1)
        val prefs: SharedPreferences? = RadioStationStore.prefs(context)
        var cleanName: String? =
            RadioStationStore.clean(
                prefs!!.getString(RadioStationStore.manualKey(band, frequency), "")
            )
        if (RadioStationStore.empty(cleanName)) cleanName = RadioStationStore.clean(name)
        if (RadioStationStore.empty(cleanName)) return null
        var origin: String? =
            RadioStationStore.cleanOrigin(
                RadioStationStore.prefs(context)!!.getString(
                    RadioStationStore.originKey(band, frequency),
                    "",
                )
            )
        if (
            !RadioStationStore.empty(
                prefs.getString(RadioStationStore.manualKey(band, frequency), "")
            )
        ) {
            origin = RadioStationStore.ORIGIN_MANUAL
        }
        if (RadioStationStore.empty(origin)) {
            origin =
                if (
                    RadioStationStore.same(
                        cleanName,
                        RadioStationStore.defaultLabel(band, frequency),
                    )
                )
                    RadioStationStore.ORIGIN_FALLBACK
                else RadioStationStore.ORIGIN_MANUAL
        }
        return Entry(band, frequency, cleanName, origin)
    }

    private fun key(band: String?, frequency: String?): String? {
        return (RadioStationStore.PREFIX + RadioStationStore.cleanBand(band)) +
            "_" +
            RadioStationStore.clean(frequency)
    }

    private fun originKey(band: String?, frequency: String?): String? {
        return (RadioStationStore.ORIGIN_PREFIX + RadioStationStore.cleanBand(band)) +
            "_" +
            RadioStationStore.clean(frequency)
    }

    private fun manualKey(band: String?, frequency: String?): String? {
        return (RadioStationStore.MANUAL_PREFIX + RadioStationStore.cleanBand(band)) +
            "_" +
            RadioStationStore.clean(frequency)
    }

    private fun cleanOrigin(value: String?): String? {
        val origin: String? = RadioStationStore.clean(value)!!.lowercase(Locale.US)
        if (
            RadioStationStore.ORIGIN_MANUAL.equals(origin) ||
                RadioStationStore.ORIGIN_LEARNED.equals(origin) ||
                RadioStationStore.ORIGIN_FALLBACK.equals(origin)
        ) {
            return origin
        }
        return ""
    }

    private fun empty(value: String?): Boolean {
        return value == null || value.isEmpty()
    }

    private fun same(first: String?, second: String?): Boolean {
        return if (first == null) second == null else first.equals(second)
    }

    private fun prefs(context: Context): SharedPreferences? {
        return context.getSharedPreferences(RadioStationStore.PREFS, Context.MODE_PRIVATE)
    }

    private fun clean(value: String?): String {
        if (value == null) return ""
        val out = value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
        if ("null".equals(out, ignoreCase = true) || "[]".equals(out)) return ""
        return out
    }

    class Entry
    internal constructor(band: String?, frequency: String?, name: String?, origin: String?) {
        @JvmField val band: String?
        @JvmField val frequency: String?
        @JvmField val name: String?
        @JvmField val manual: Boolean

        init {
            this.band = band
            this.frequency = frequency
            this.name = name
            this.manual = RadioStationStore.ORIGIN_MANUAL.equals(origin)
        }

        internal fun bandRank(): Int {
            return if ("FM".equals(band, ignoreCase = true)) 0 else 1
        }

        internal fun numericFrequency(): Double {
            try {
                return java.lang.Double.parseDouble(frequency)
            } catch (ignored: Exception) {
                return Double.MAX_VALUE
            }
        }
    }

    private class StoredStation(name: String?, origin: String?) {
        @JvmField val name: String?
        @JvmField val origin: String?

        init {
            this.name = name
            this.origin = origin
        }
    }
}
