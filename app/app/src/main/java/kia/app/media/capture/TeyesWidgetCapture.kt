package kia.app.media.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import java.util.Locale
import kia.app.media.domain.MediaFeature
import kia.app.media.domain.RadioStationStore

internal class TeyesWidgetCapture(context: Context) {
    private val app: Context
    private val spdMedia: SpdMediaServiceClient?
    private val btMedia: SpdBluetoothServiceClient?
    private val radioMedia: SpdRadioServiceClient?
    private val teyesRadio: TeyesRadioBrowserClient?
    private val onlineRadio: TeyesOnlineRadioClient?
    private val androidMedia: AndroidMediaSessionClient?
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var lastPokeAt: Long = 0
    private var radioSearchFastUntil: Long = 0
    private var pendingAmFrequency: String? = ""
    private var pendingAmFrequencyAt: Long = 0
    private var reportedAmFrequency: String? = ""
    private var running: Boolean = false

    private val poll: Runnable =
        object : Runnable {
            override fun run() {
                if (!running) return
                scanNow()
                handler.postDelayed(this, nextPollDelay())
            }
        }

    init {
        this.app = context.applicationContext ?: context
        this.spdMedia = SpdMediaServiceClient.get(app)
        this.btMedia = SpdBluetoothServiceClient.get(app)
        this.radioMedia = SpdRadioServiceClient.get(app)
        this.teyesRadio = TeyesRadioBrowserClient.get(app)
        this.onlineRadio = TeyesOnlineRadioClient.get(app)
        this.androidMedia = AndroidMediaSessionClient.get(app)
    }

    fun start() {
        if (running) return
        running = true
        spdMedia!!.start()
        btMedia!!.start()
        radioMedia!!.start()
        teyesRadio!!.start()
        onlineRadio!!.start()
        handler.post(poll)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        spdMedia!!.stop()
        btMedia!!.stop()
        radioMedia!!.stop()
        teyesRadio!!.stop()
        onlineRadio!!.stop()
    }

    fun scanNow(): Boolean {
        pokeWidget()
        var cursor: Cursor? = null
        try {
            cursor =
                app!!
                    .getContentResolver()
                    .query(TeyesWidgetCapture.PROGRESS_URI!!, null, null, null, null)
            if (cursor == null || !cursor.moveToFirst()) {
                return reportFallback()
            }
            var best: WidgetState? = null
            do {
                val state: WidgetState? = read(cursor)
                if (state != null && !TextUtils.isEmpty(state.source)) best = state
            } while (cursor.moveToNext())
            if (best == null) {
                return reportFallback()
            }
            if (TeyesWidgetCapture.isRadioSource(best.source) && best.isSearching()) {
                if (TeyesWidgetCapture.isAmSource(best.source)) {
                    markRadioSearchFast()
                    resetPendingAmFrequency()
                    MediaFeature.get(app)
                        .reportRadioSearch(best.source, TeyesWidgetCapture.PACKAGE, best.frequency)
                    return true
                }
                val currentRadio: SpdRadioServiceClient.Snapshot? = radioMedia!!.readSnapshot()
                if (currentRadio != null && currentRadio.hasFrequency()) {
                    if (reportRadio(currentRadio)) return true
                } else if (!TextUtils.isEmpty(best.frequency)) {
                    markRadioSearchFast()
                    MediaFeature.get(app)
                        .reportRadioSearch(best.source, TeyesWidgetCapture.PACKAGE, best.frequency)
                }
                return true
            }
            if (TeyesWidgetCapture.isRadioSource(best.source)) {
                if (TeyesWidgetCapture.isAmSource(best.source)) {
                    if (
                        !TextUtils.isEmpty(best.frequency) &&
                            stableAmFrequency(best.source, best.frequency)
                    ) {
                        val displayTimeMs: Long =
                            TeyesWidgetCapture.remainingMs(best.positionMs, best.totalDurationMs)
                        MediaFeature.get(app)
                            .report(
                                best.source,
                                TeyesWidgetCapture.PACKAGE,
                                best.frequency,
                                "",
                                displayTimeMs,
                            )
                        return true
                    }
                    val currentRadio: SpdRadioServiceClient.Snapshot? = radioMedia!!.readSnapshot()
                    if (reportAmRadioFallback(currentRadio)) return true
                    return true
                }
                val currentRadio: SpdRadioServiceClient.Snapshot? = radioMedia!!.readSnapshot()
                if (currentRadio != null && currentRadio.hasFrequency()) {
                    if (reportRadio(currentRadio)) return true
                }
                if (!TextUtils.isEmpty(best.frequency)) {
                    if (!stableAmFrequency(best.source, best.frequency)) return true
                    val displayTimeMs: Long =
                        TeyesWidgetCapture.remainingMs(best.positionMs, best.totalDurationMs)
                    MediaFeature.get(app)
                        .report(
                            best.source,
                            TeyesWidgetCapture.PACKAGE,
                            best.frequency,
                            "",
                            displayTimeMs,
                        )
                    return true
                }
                return true
            }
            if ("TEYES".equals(best.source)) {
                if (reportTeyesOnline()) return true
            }
            if ("USB".equals(best.source)) {
                if (reportUsb()) return true
            }
            if (TeyesWidgetCapture.isBluetoothSource(best.source)) {
                if (reportBluetooth(best.positionMs)) return true
            }
            if (TeyesWidgetCapture.isGenericAppSource(best)) {
                val appMedia: AndroidMediaSessionClient.Snapshot? = androidMedia!!.readSnapshot()
                if (appMedia != null && appMedia.isPlaying()) {
                    if (reportAndroidMediaSession(appMedia)) return true
                }
                if (reportConcreteSourceIfCurrent()) return true
                return false
            }
            val displayTimeMs: Long =
                TeyesWidgetCapture.remainingMs(best.positionMs, best.totalDurationMs)
            if (best.hasText()) {
                MediaFeature.get(app)
                    .report(
                        best.source,
                        TeyesWidgetCapture.PACKAGE,
                        best.displayArtist(),
                        best.displayTitle(),
                        displayTimeMs,
                    )
            } else {
                MediaFeature.get(app)
                    .reportSourceOnly(best.source, TeyesWidgetCapture.PACKAGE, displayTimeMs)
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            if (cursor != null) cursor.close()
        }
    }

    private fun reportFallback(): Boolean {
        val radio: SpdRadioServiceClient.Snapshot? = radioMedia!!.readSnapshot()
        if (radio != null && radio.hasFrequency() && radio.isCurrent()) {
            if (reportRadio(radio)) return true
        }
        val online: TeyesOnlineRadioClient.Snapshot? = onlineRadio!!.readSnapshot()
        if (online != null && online.isCurrent()) {
            reportTeyesOnline(online)
            return true
        }
        if (reportAndroidMediaSession(androidMedia!!.readSnapshot())) return true
        MediaFeature.get(app).reportIdle(TeyesWidgetCapture.PACKAGE, "teyes provider empty")
        return false
    }

    private fun reportConcreteSourceIfCurrent(): Boolean {
        val radio: SpdRadioServiceClient.Snapshot? = radioMedia!!.readSnapshot()
        if (radio != null && radio.hasFrequency() && radio.isCurrent()) {
            if (reportRadio(radio)) return true
        }
        val online: TeyesOnlineRadioClient.Snapshot? = onlineRadio!!.readSnapshot()
        if (online != null && online.isCurrent()) {
            reportTeyesOnline(online)
            return true
        }
        val usb: SpdMediaServiceClient.NowPlaying? = spdMedia!!.readNowPlaying()
        if (usb != null && usb.hasText() && usb.isPlaying()) {
            MediaFeature.get(app)
                .report(
                    "USB",
                    SpdMediaServiceClient.PACKAGE,
                    usb.artist,
                    usb.title,
                    usb.remainingMs(),
                    true,
                )
            return true
        }
        val bt: SpdBluetoothServiceClient.Snapshot? = btMedia!!.readSnapshot(-1L)
        if (bt != null && bt.hasText() && bt.isPlaying()) {
            MediaFeature.get(app)
                .report(
                    "Bluetooth",
                    SpdBluetoothServiceClient.PACKAGE,
                    bt.artist,
                    bt.title,
                    bt.remainingMs(),
                    true,
                )
            return true
        }
        return false
    }

    private fun reportRadio(radio: SpdRadioServiceClient.Snapshot): Boolean {
        val frequency: String? = radio.frequencyText()
        if ("AM".equals(radio.source())) return false
        if (radio.isSearching()) {
            markRadioSearchFast()
            MediaFeature.get(app)
                .reportRadioSearch(radio.source(), SpdRadioServiceClient.PACKAGE, frequency)
            return true
        }
        val teyesStation: String? = teyesRadio!!.stationName(radio.source(), frequency)
        val station: String? =
            RadioStationStore.resolve(
                app,
                radio.source(),
                frequency,
                radio.stationName(teyesStation!!),
            )
        MediaFeature.get(app)
            .report(
                radio.source(),
                SpdRadioServiceClient.PACKAGE,
                frequency,
                station,
                -1L,
                radio.isPlaying(),
            )
        return true
    }

    private fun reportAmRadioFallback(radio: SpdRadioServiceClient.Snapshot?): Boolean {
        if (radio == null || !radio.hasFrequency() || !"AM".equals(radio.source())) return false
        if (radio.isSearching() || System.currentTimeMillis() < radioSearchFastUntil) {
            markRadioSearchFast()
            resetPendingAmFrequency()
            MediaFeature.get(app).reportRadioSearch("AM", SpdRadioServiceClient.PACKAGE, "")
            return true
        }
        val frequency: String? = radio.frequencyText()
        if (!stableAmFrequency("AM", frequency)) return true
        val station: String? = RadioStationStore.resolve(app, "AM", frequency, "")
        MediaFeature.get(app)
            .report("AM", SpdRadioServiceClient.PACKAGE, frequency, station, -1L, radio.isPlaying())
        return true
    }

    private fun stableAmFrequency(source: String?, frequency: String?): Boolean {
        if (!"AM".equals(TeyesWidgetCapture.clean(source), ignoreCase = true)) return true
        val cleanFrequency: String? = TeyesWidgetCapture.clean(frequency)
        if (TextUtils.isEmpty(cleanFrequency)) return false
        val now: Long = System.currentTimeMillis()
        if (!TextUtils.equals(cleanFrequency, pendingAmFrequency)) {
            pendingAmFrequency = cleanFrequency
            pendingAmFrequencyAt = now
            return TextUtils.equals(cleanFrequency, reportedAmFrequency)
        }
        if (
            TextUtils.equals(cleanFrequency, reportedAmFrequency) ||
                now - pendingAmFrequencyAt >= TeyesWidgetCapture.AM_STABLE_MS
        ) {
            reportedAmFrequency = cleanFrequency
            return true
        }
        return false
    }

    private fun resetPendingAmFrequency() {
        pendingAmFrequency = ""
        pendingAmFrequencyAt = 0L
    }

    private fun reportUsb(): Boolean {
        val nowPlaying: SpdMediaServiceClient.NowPlaying? = spdMedia!!.readNowPlaying()
        if (nowPlaying == null || !nowPlaying.hasText()) return false
        MediaFeature.get(app)
            .report(
                "USB",
                SpdMediaServiceClient.PACKAGE,
                nowPlaying.artist,
                nowPlaying.title,
                nowPlaying.remainingMs(),
                nowPlaying.isPlaying(),
            )
        return true
    }

    private fun reportBluetooth(fallbackPositionMs: Long): Boolean {
        val bt: SpdBluetoothServiceClient.Snapshot? = btMedia!!.readSnapshot(fallbackPositionMs)
        if (bt == null || !bt.hasText()) {
            val session: AndroidMediaSessionClient.Snapshot? =
                androidMedia!!.readPackageSnapshot("com.android.bluetooth")
            if (session == null) return false
            MediaFeature.get(app)
                .report(
                    "Bluetooth",
                    session.packageName!!,
                    session.artist,
                    session.title,
                    session.remainingMs,
                    session.playing,
                )
            return true
        }
        MediaFeature.get(app)
            .report(
                "Bluetooth",
                SpdBluetoothServiceClient.PACKAGE,
                bt.artist,
                bt.title,
                bt.remainingMs(),
                bt.isPlaying(),
            )
        return true
    }

    private fun reportTeyesOnline(): Boolean {
        val online: TeyesOnlineRadioClient.Snapshot? = onlineRadio!!.readSnapshot()
        if (online == null || !online.isCurrent()) return false
        reportTeyesOnline(online)
        return true
    }

    private fun reportTeyesOnline(online: TeyesOnlineRadioClient.Snapshot) {
        MediaFeature.get(app)
            .report(
                online.source(),
                TeyesOnlineRadioClient.PACKAGE,
                online.artist(),
                online.title(),
                -1L,
                online.isPlaying(),
            )
    }

    private fun nextPollDelay(): Long {
        return if (System.currentTimeMillis() < radioSearchFastUntil)
            TeyesWidgetCapture.RADIO_SEARCH_POLL_MS
        else TeyesWidgetCapture.POLL_MS
    }

    private fun markRadioSearchFast() {
        radioSearchFastUntil =
            System.currentTimeMillis() + TeyesWidgetCapture.RADIO_SEARCH_FAST_HOLD_MS
    }

    private fun reportAndroidMediaSession(snapshot: AndroidMediaSessionClient.Snapshot?): Boolean {
        if (snapshot == null) return false
        if (!snapshot.playing) return false
        MediaFeature.get(app)
            .report(
                null,
                snapshot.packageName!!,
                snapshot.artist,
                snapshot.title,
                snapshot.remainingMs,
                snapshot.playing,
            )
        return true
    }

    private fun read(cursor: Cursor): WidgetState? {
        val songType: String? = TeyesWidgetCapture.value(cursor, "songType")
        val appName: String? =
            TeyesWidgetCapture.firstValue(cursor, "app_name", "appName", "player", "sourceName")
        val source: String? = TeyesWidgetCapture.sourceFromSongType(songType, appName)
        if (TextUtils.isEmpty(source)) return null
        val state: WidgetState = WidgetState()
        state.source = source
        state.songType = TeyesWidgetCapture.clean(songType)
        state.title =
            TeyesWidgetCapture.firstValue(
                cursor,
                "song_title",
                "songTitle",
                "title",
                "name",
                "track",
                "track_title",
                "music_title",
            )
        state.artist =
            TeyesWidgetCapture.firstValue(
                cursor,
                "song_artist",
                "songArtist",
                "artist",
                "singer",
                "author",
                "station",
                "station_name",
            )
        state.frequency =
            TeyesWidgetCapture.firstValue(
                cursor,
                "freq",
                "frequency",
                "radio_freq",
                "fm",
                "radio_frequency",
                "freq_text",
                "frequency_text",
                "radio_text",
                "radioText",
                "station_freq",
                "stationFrequency",
            )
        state.totalDurationMs =
            TeyesWidgetCapture.longValue(
                cursor,
                -1L,
                "duration",
                "durationMs",
                "duration_ms",
                "total",
                "totalTime",
                "total_time",
            )
        state.positionMs =
            TeyesWidgetCapture.longValue(
                cursor,
                -1L,
                "progress",
                "position",
                "current",
                "currentTime",
                "current_time",
            )
        state.searchState =
            TeyesWidgetCapture.firstValue(
                cursor,
                "search",
                "scan",
                "seek",
                "isSearching",
                "isSeeking",
                "isScanning",
                "searching",
                "seeking",
                "scanning",
                "search_state",
                "seek_state",
                "scan_state",
            )
        state.appName = TeyesWidgetCapture.clean(appName)
        return state
    }

    private fun pokeWidget() {
        val now: Long = System.currentTimeMillis()
        if (now - lastPokeAt < 30000L) return
        lastPokeAt = now
        try {
            val broadcast: Intent = Intent(TeyesWidgetCapture.START_ACTION)
            broadcast.setPackage(TeyesWidgetCapture.PACKAGE)
            app.sendBroadcast(broadcast)
        } catch (ignored: Exception) {}
        try {
            val service: Intent = Intent()
            service.setComponent(
                ComponentName(TeyesWidgetCapture.PACKAGE, TeyesWidgetCapture.SERVICE)
            )
            app.startService(service)
        } catch (ignored: Exception) {}
    }

    private class WidgetState {
        @JvmField var source: String? = null
        @JvmField var songType: String? = null
        @JvmField var title: String? = null
        @JvmField var artist: String? = null
        @JvmField var frequency: String? = null
        @JvmField var positionMs: Long = -1L
        @JvmField var totalDurationMs: Long = -1L
        @JvmField var searchState: String? = null
        @JvmField var appName: String? = null

        fun hasText(): Boolean {
            return !TextUtils.isEmpty(displayTitle()) || !TextUtils.isEmpty(displayArtist())
        }

        fun displayTitle(): String? {
            if (!TextUtils.isEmpty(frequency)) return frequency
            return title
        }

        fun displayArtist(): String? {
            return artist
        }

        fun isSearching(): Boolean {
            val value: String? =
                if (searchState == null) "" else searchState!!.trim().lowercase(Locale.US)
            return !TextUtils.isEmpty(value) &&
                !"0".equals(value) &&
                !"false".equals(value) &&
                !"stopped".equals(value) &&
                !"idle".equals(value) &&
                !"none".equals(value)
        }
    }

    companion object {
        private val PACKAGE: String = "com.teyes.music.widget"
        private val SERVICE: String = "com.teyes.music.service.MediaService"
        private val START_ACTION: String = "com.teyes.widget.action.START_SERVICE"
        private val PROGRESS_URI: Uri? = Uri.parse("content://com.teyes.music.provider/progress")
        private const val POLL_MS: Long = 1000L
        private const val RADIO_SEARCH_POLL_MS: Long = 100L
        private const val RADIO_SEARCH_FAST_HOLD_MS: Long = 1500L
        private const val AM_STABLE_MS: Long = 1500L

        private fun sourceFromSongType(songType: String?, appName: String?): String? {
            val type: String? = TeyesWidgetCapture.clean(songType)
            val appSource: String? = TeyesWidgetCapture.sourceFromAppName(appName)
            if (TextUtils.isEmpty(type)) return appSource
            val t = type!!.lowercase(Locale.US)
            if (TeyesWidgetCapture.isBluetoothText(t)) return "Bluetooth"
            if (t.contains("local_radio")) return "FM радио"
            if (t.contains("dab") || t.contains("dts")) return "DTS радио"
            if (t.contains("network_radio")) return "TEYES"
            if (t.contains("local_music")) return "USB"
            if (t.contains("cloud_music") || t.contains("network_music") || t.contains("net_music"))
                return "OTHER"
            if (t.contains("carplay")) return "CarPlay"
            if (t.contains("android_auto") || t.contains("androidauto")) return "Android Auto"
            if (t.contains("radio")) return if (t.contains("am")) "AM" else "FM радио"
            if (t.contains("music")) return "TEYES Media"
            return appSource
        }

        private fun sourceFromAppName(appName: String?): String? {
            val app = TeyesWidgetCapture.clean(appName)
            if (TextUtils.isEmpty(app)) return null
            val p = app.lowercase(Locale.US)
            if (TeyesWidgetCapture.isBluetoothText(p)) return "Bluetooth"
            if (p.contains("янд") || p.contains("yandex")) return "Яндекс Музыка"
            if (p.contains("radio") || p.contains("радио")) return "FM радио"
            if (p.contains("usb") || p.contains("local")) return "USB"
            return app
        }

        private fun isBluetoothText(value: String?): Boolean {
            val p: String? = if (value == null) "" else value.lowercase(Locale.US)
            return p!!.contains("bluetooth") ||
                p.contains("bt") ||
                p.contains("a2dp") ||
                p.contains("avrcp")
        }

        private fun isBluetoothSource(value: String?): Boolean {
            return TeyesWidgetCapture.isBluetoothText(value)
        }

        private fun isRadioSource(value: String?): Boolean {
            val p: String? = if (value == null) "" else value.lowercase(Locale.US)
            return p!!.contains("radio") ||
                p.contains("радио") ||
                p.contains("fm") ||
                p.contains("am")
        }

        private fun isAmSource(value: String?): Boolean {
            val p: String? = if (value == null) "" else value.lowercase(Locale.US)
            return p.equals("am") || p!!.contains("am ")
        }

        private fun isGenericAppSource(state: WidgetState?): Boolean {
            if (state == null) return false
            val source: String? =
                if (state.source == null) "" else state.source!!.lowercase(Locale.US)
            val type: String? =
                if (state.songType == null) "" else state.songType!!.lowercase(Locale.US)
            return source.equals("other") ||
                source!!.contains("янд") ||
                source.contains("yandex") ||
                source.contains("spotify") ||
                type!!.contains("cloud_music") ||
                type.contains("network_music") ||
                type.contains("net_music")
        }

        private fun value(cursor: Cursor, column: String?): String? {
            val idx: Int = cursor.getColumnIndex(column)
            if (idx < 0) return null
            try {
                return cursor.getString(idx)
            } catch (ignored: Exception) {
                return null
            }
        }

        private fun firstValue(cursor: Cursor, vararg columns: String?): String? {
            for (column: String? in columns) {
                val value: String? =
                    TeyesWidgetCapture.clean(TeyesWidgetCapture.value(cursor, column))
                if (!TextUtils.isEmpty(value)) return value
            }
            return null
        }

        private fun longValue(cursor: Cursor, fallback: Long, vararg columns: String?): Long {
            for (column: String? in columns) {
                val idx: Int = cursor.getColumnIndex(column)
                if (idx < 0) continue
                try {
                    val value: Long = cursor.getLong(idx)
                    if (value >= 0L) return value
                } catch (ignored: Exception) {}
            }
            return fallback
        }

        private fun remainingMs(positionMs: Long, totalDurationMs: Long): Long {
            if (positionMs < 0L || totalDurationMs <= 0L) return -1L
            return Math.max(0L, totalDurationMs - positionMs)
        }

        private fun clean(value: String?): String {
            if (value == null) return ""
            return value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
        }
    }
}
