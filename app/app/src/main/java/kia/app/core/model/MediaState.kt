package kia.app.core.model

import java.util.Locale

class MediaState(
    source: String?,
    packageName: String?,
    artist: String?,
    title: String?,
    durationMs: Long,
    playing: Boolean,
    clusterTx: String?,
    updatedAt: Long,
) {
    @JvmField val source: String
    @JvmField val packageName: String
    @JvmField val artist: String
    @JvmField val title: String
    @JvmField val durationMs: Long
    @JvmField val playing: Boolean
    @JvmField val clusterTx: String?
    @JvmField val updatedAt: Long

    constructor(
        source: String?,
        packageName: String?,
        artist: String?,
        title: String?,
        durationMs: Long,
        updatedAt: Long,
    ) : this(source, packageName, artist, title, durationMs, true, updatedAt)

    constructor(
        source: String?,
        packageName: String?,
        artist: String?,
        title: String?,
        durationMs: Long,
        playing: Boolean,
        updatedAt: Long,
    ) : this(source, packageName, artist, title, durationMs, playing, "", updatedAt)

    init {
        this.source = MediaState.safe(source)
        this.packageName = MediaState.safe(packageName)
        this.artist = MediaState.safe(artist)
        this.title = MediaState.safe(title)
        this.durationMs = durationMs
        this.playing = playing
        this.clusterTx = MediaState.safeMultiline(clusterTx)
        this.updatedAt = updatedAt
    }

    fun summary(): String {
        if (source.isEmpty() && artist.isEmpty() && title.isEmpty()) return "нет данных"
        val cleanArtist: String =
            if (artist.isEmpty() || "<unknown>".equals(artist, ignoreCase = true)) "-" else artist
        val cleanTitle: String = if (title.isEmpty()) "-" else title
        return ("Мультимедиа: " +
            sourceCode() +
            " | " +
            cleanArtist +
            " | " +
            cleanTitle +
            " | " +
            MediaState.formatDuration(durationMs) +
            " | " +
            (if (playing) "играет" else "не играет"))
    }

    fun withClusterTxText(value: String?, updatedAt: Long): MediaState {
        return MediaState(source, packageName, artist, title, durationMs, playing, value, updatedAt)
    }

    private fun sourceCode(): String {
        val value: String? = (source + " " + packageName).lowercase(Locale.ROOT)
        val sourceValue = source.lowercase(Locale.ROOT)
        if (value!!.contains("usb") || value.contains("local_music") || value.contains("spd.media"))
            return "USB"
        if (
            value.contains("bluetooth") ||
                value.contains("bt") ||
                value.contains("a2dp") ||
                value.contains("avrcp")
        )
            return "BT"
        if (value.contains("am")) return "AM"
        if (sourceValue.contains("teyes online") || sourceValue.equals("teyes")) return "TEYES"
        if (value.contains("fm") || value.contains("radio") || value.contains("радио")) return "FM"
        return "OTHER"
    }

    companion object {
        @JvmStatic
        fun empty(): MediaState {
            return MediaState("", "", "", "", -1L, false, 0L)
        }

        private fun safe(value: String?): String {
            return if (value == null) "" else value.trim()
        }

        private fun safeMultiline(value: String?): String {
            return if (value == null) "" else value.trim()
        }

        private fun formatDuration(value: Long): String {
            if (value < 0L) return "-"
            val seconds: Long = value / 1000L
            val minutes: Long = seconds / 60L
            val rest: Long = seconds % 60L
            return minutes.toString() + ":" + (if (rest < 10) "0" else "") + rest
        }
    }
}
