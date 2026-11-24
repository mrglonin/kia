package kia.app.media.capture

import android.content.ComponentName
import android.content.Context
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.text.TextUtils
import java.util.ArrayList
import kia.app.core.AppLog
import kia.app.core.settings.AppSettings

internal class AndroidMediaSessionClient private constructor(context: Context) {
    private val app: Context
    private val listenerComponent: ComponentName
    private var lastError: String? = ""
    private var lastInfoKey: String = ""

    init {
        this.app = context.applicationContext ?: context
        this.listenerComponent = ComponentName(app, MediaNotificationListener::class.java)
    }

    fun readSnapshot(): AndroidMediaSessionClient.Snapshot? {
        return readSnapshot(null, true, false, false)
    }

    fun readUniversalSnapshot(): AndroidMediaSessionClient.Snapshot? {
        return readSnapshot(null, false, false, true)
    }

    fun readPackageSnapshot(packageName: String?): AndroidMediaSessionClient.Snapshot? {
        return readSnapshot(packageName, false, true, false)
    }

    private fun readSnapshot(
        packageName: String?,
        applyIgnore: Boolean,
        allowStopped: Boolean,
        applyUniversalSelection: Boolean,
    ): AndroidMediaSessionClient.Snapshot? {
        val manager: MediaSessionManager? =
            app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager?
        if (manager == null) return null
        val controllers: List<MediaController?>?
        try {
            controllers = manager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            reportError("notification listener permission missing")
            return null
        } catch (e: Exception) {
            reportError("read sessions failed " + e.javaClass.getSimpleName())
            return null
        }
        if (controllers == null || controllers.isEmpty()) return null

        var fallback: AndroidMediaSessionClient.Snapshot? = null
        val universalCandidates: MutableList<AndroidMediaSessionClient.Snapshot?>? =
            if (applyUniversalSelection) ArrayList() else null
        for (controller: MediaController in controllers) {
            if (
                !TextUtils.isEmpty(packageName) &&
                    !TextUtils.equals(packageName, controller.getPackageName())
            )
                continue
            val snapshot: AndroidMediaSessionClient.Snapshot? =
                snapshot(controller, applyIgnore, allowStopped)
            if (snapshot == null) continue
            if (applyUniversalSelection) {
                universalCandidates!!.add(snapshot)
                continue
            }
            if (snapshot.isPlaying()) {
                reportInfo(snapshot)
                return snapshot
            }
            if (fallback == null) fallback = snapshot
        }
        if (applyUniversalSelection) {
            val selectedIndex: Int =
                MediaSessionSelectionPolicy.selectIndex(
                    AndroidMediaSessionClient.selectionCandidates(universalCandidates),
                    AppSettings.mediaPreferredSessionPackage(app),
                    AppSettings.mediaBlockedSessionPackages(app),
                )
            val selected: AndroidMediaSessionClient.Snapshot? =
                if (selectedIndex >= 0 && selectedIndex < universalCandidates!!.size)
                    universalCandidates.get(selectedIndex)
                else null
            reportInfo(selected)
            return selected
        }
        reportInfo(fallback)
        return fallback
    }

    private fun snapshot(controller: MediaController?): AndroidMediaSessionClient.Snapshot? {
        return snapshot(controller, true, false)
    }

    private fun snapshot(
        controller: MediaController?,
        applyIgnore: Boolean,
        allowStopped: Boolean,
    ): AndroidMediaSessionClient.Snapshot? {
        if (
            controller == null ||
                TextUtils.equals(app.getPackageName(), controller.getPackageName()) ||
                (applyIgnore && ignore(controller.getPackageName()))
        ) {
            return null
        }
        val state: PlaybackState? = controller.getPlaybackState()
        val metadata: MediaMetadata? = controller.getMetadata()
        if (state == null || metadata == null) return null
        val stateCode: Int = state.getState()
        if (
            stateCode == PlaybackState.STATE_NONE ||
                (stateCode == PlaybackState.STATE_STOPPED && !allowStopped)
        )
            return null

        val description: MediaDescription? = metadata.getDescription()
        val title: String? =
            AndroidMediaSessionClient.first(
                AndroidMediaSessionClient.text(metadata, MediaMetadata.METADATA_KEY_TITLE),
                if (description == null || description.getTitle() == null) ""
                else description.getTitle().toString(),
            )
        val artist: String? =
            AndroidMediaSessionClient.first(
                AndroidMediaSessionClient.text(metadata, MediaMetadata.METADATA_KEY_ARTIST),
                AndroidMediaSessionClient.text(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                AndroidMediaSessionClient.text(metadata, MediaMetadata.METADATA_KEY_AUTHOR),
                if (description == null || description.getSubtitle() == null) ""
                else description.getSubtitle().toString(),
            )
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(artist)) return null

        val durationMs: Long = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val remainingMs: Long = AndroidMediaSessionClient.remainingMs(durationMs, state)
        return AndroidMediaSessionClient.Snapshot(
            controller.getPackageName(),
            artist,
            title,
            remainingMs,
            stateCode == PlaybackState.STATE_PLAYING,
        )
    }

    private fun ignore(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return true
        return pkg.startsWith("com.android.") ||
            pkg.startsWith("android") ||
            pkg.startsWith("com.spd.") ||
            pkg.startsWith("com.teyes.") ||
            pkg.startsWith("com.alink.") ||
            pkg.startsWith("com.yf.")
    }

    private fun reportError(message: String?) {
        if (TextUtils.equals(message, lastError)) return
        lastError = message
        AppLog.line(app, "Android media session: " + message)
    }

    private fun reportInfo(snapshot: AndroidMediaSessionClient.Snapshot?) {
        if (snapshot == null) return
        val key: String =
            (snapshot.packageName.toString() +
                "|" +
                snapshot.artist +
                "|" +
                snapshot.title +
                "|" +
                snapshot.remainingMs +
                "|" +
                snapshot.playing)
        if (TextUtils.equals(key, lastInfoKey)) return
        lastInfoKey = key
        AppLog.line(
            app,
            ("Android media session: " +
                snapshot.packageName +
                " | " +
                (if (TextUtils.isEmpty(snapshot.artist)) "-" else snapshot.artist) +
                " | " +
                snapshot.title +
                " | " +
                snapshot.playing),
        )
    }

    internal class Snapshot
    internal constructor(
        packageName: String?,
        artist: String?,
        title: String?,
        remainingMs: Long,
        playing: Boolean,
    ) {
        @JvmField val packageName: String?
        @JvmField val artist: String?
        @JvmField val title: String?
        @JvmField val remainingMs: Long
        @JvmField val playing: Boolean

        init {
            this.packageName = AndroidMediaSessionClient.clean(packageName)
            this.artist = AndroidMediaSessionClient.clean(artist)
            this.title = AndroidMediaSessionClient.clean(title)
            this.remainingMs = remainingMs
            this.playing = playing
        }

        fun isPlaying(): Boolean {
            return playing
        }
    }

    companion object {
        private var instance: AndroidMediaSessionClient? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): AndroidMediaSessionClient {
            if (AndroidMediaSessionClient.instance == null)
                AndroidMediaSessionClient.instance = AndroidMediaSessionClient(context)
            return AndroidMediaSessionClient.instance!!
        }

        private fun selectionCandidates(
            snapshots: List<AndroidMediaSessionClient.Snapshot?>?
        ): List<MediaSessionSelectionPolicy.Candidate?>? {
            val candidates: MutableList<MediaSessionSelectionPolicy.Candidate?> =
                ArrayList(if (snapshots == null) 0 else snapshots.size)
            if (snapshots == null) return candidates
            for (snapshot: AndroidMediaSessionClient.Snapshot? in snapshots) {
                candidates.add(
                    MediaSessionSelectionPolicy.Candidate(
                        if (snapshot == null) "" else snapshot.packageName,
                        snapshot != null && snapshot.playing,
                    )
                )
            }
            return candidates
        }

        private fun remainingMs(durationMs: Long, state: PlaybackState?): Long {
            if (durationMs <= 0L || state == null) return -1L
            var positionMs: Long = Math.max(0L, state.getPosition())
            if (state.getState() == PlaybackState.STATE_PLAYING) {
                val deltaMs: Long =
                    SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()
                if (deltaMs > 0L) {
                    positionMs += (deltaMs * Math.max(0f, state.getPlaybackSpeed())).toLong()
                }
            }
            return Math.max(0L, durationMs - positionMs)
        }

        private fun text(metadata: MediaMetadata, key: String?): String? {
            val value: CharSequence? = metadata.getText(key)
            return if (value == null) "" else AndroidMediaSessionClient.clean(value.toString())
        }

        private fun first(vararg values: String?): String? {
            for (value: String? in values) {
                val clean: String? = AndroidMediaSessionClient.clean(value)
                if (!TextUtils.isEmpty(clean)) return clean
            }
            return ""
        }

        private fun clean(value: String?): String? {
            if (value == null) return ""
            val out = value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
            if ("null".equals(out, ignoreCase = true) || "[]".equals(out)) return ""
            return out
        }
    }
}
