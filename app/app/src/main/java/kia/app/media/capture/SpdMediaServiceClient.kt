package kia.app.media.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.text.TextUtils
import kia.app.core.AppLog

internal class SpdMediaServiceClient private constructor(context: Context) {
    private val app: Context
    private var binder: IBinder? = null
    private var binding: Boolean = false
    private var bound: Boolean = false
    private var lastBindAt: Long = 0
    private var lastError: String? = ""

    private val deathRecipient: IBinder.DeathRecipient =
        object : IBinder.DeathRecipient {
            override fun binderDied() {
                binder = null
                bound = false
                binding = false
                AppLog.line(app, "Media service: com.spd.media binder died")
            }
        }

    private val connection: ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                bound = true
                binding = false
                lastError = ""
                try {
                    service!!.linkToDeath(deathRecipient, 0)
                } catch (ignored: RemoteException) {
                    binder = null
                    bound = false
                }
                AppLog.line(app, "Media service: connected " + name!!.flattenToShortString())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                bound = false
                binding = false
                AppLog.line(app, "Media service: disconnected " + name!!.flattenToShortString())
            }
        }

    init {
        this.app = context.applicationContext ?: context
    }

    fun start() {
        ensureBound()
    }

    fun stop() {
        if (!bound && !binding) return
        try {
            if (binder != null) binder!!.unlinkToDeath(deathRecipient, 0)
        } catch (ignored: Exception) {}
        try {
            app.unbindService(connection)
        } catch (ignored: Exception) {}
        binder = null
        bound = false
        binding = false
    }

    fun readNowPlaying(): NowPlaying? {
        ensureBound()
        val remote: IBinder? = binder
        if (remote == null || !remote.isBinderAlive()) return null

        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(SpdMediaServiceClient.DESCRIPTOR)
            if (!remote.transact(SpdMediaServiceClient.TRANSACTION_GET_NOW_PLAYING, data, reply, 0))
                return null
            reply!!.readException()
            if (reply.readInt() == 0) return null
            return NowPlaying.read(reply)
        } catch (e: Exception) {
            reportError("read failed " + e.javaClass.getSimpleName())
            return null
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun ensureBound() {
        val remote: IBinder? = binder
        if (remote != null && remote.isBinderAlive()) return
        if (binding) return
        val now: Long = System.currentTimeMillis()
        if (now - lastBindAt < SpdMediaServiceClient.REBIND_DELAY_MS) return
        lastBindAt = now

        val intent: Intent = Intent(SpdMediaServiceClient.ACTION)
        intent.setComponent(
            ComponentName(SpdMediaServiceClient.PACKAGE, SpdMediaServiceClient.SERVICE)
        )
        try {
            binding = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!binding) reportError("bind returned false")
        } catch (e: Exception) {
            binding = false
            reportError("bind failed " + e.javaClass.getSimpleName())
        }
    }

    private fun reportError(message: String?) {
        if (TextUtils.equals(message, lastError)) return
        lastError = message
        AppLog.line(app, "Media service: " + message)
    }

    internal class NowPlaying
    private constructor(
        playStatus: Int,
        playtimeMs: Int,
        durationMs: Int,
        title: String?,
        artist: String?,
        album: String?,
        path: String?,
    ) {
        @JvmField val playStatus: Int
        @JvmField val playtimeMs: Int
        @JvmField val durationMs: Int
        @JvmField val title: String?
        @JvmField val artist: String?
        @JvmField val album: String?
        @JvmField val path: String?

        init {
            this.playStatus = playStatus
            this.playtimeMs = playtimeMs
            this.durationMs = durationMs
            this.title = NowPlaying.clean(title)
            this.artist = NowPlaying.clean(artist)
            this.album = NowPlaying.clean(album)
            this.path = NowPlaying.clean(path)
        }

        fun hasText(): Boolean {
            return !TextUtils.isEmpty(title) || !TextUtils.isEmpty(artist)
        }

        fun isPlaying(): Boolean {
            return playStatus == SpdMediaServiceClient.MEDIA_PLAYER_STATUS_PLAY
        }

        fun remainingMs(): Long {
            if (playtimeMs < 0 || durationMs <= 0) return -1L
            return Math.max(0L, durationMs.toLong() - playtimeMs.toLong())
        }

        companion object {
            @JvmStatic
            fun read(parcel: Parcel): NowPlaying {
                parcel.readInt() // fileType
                parcel.readInt() // list_id
                parcel.readInt() // listType
                parcel.readInt() // playIndex
                parcel.readInt() // playCount
                val playStatus: Int = parcel.readInt()
                parcel.readInt() // playSpeed
                parcel.readInt() // repeatMode
                parcel.readInt() // shuffleMode
                val playtimeMs: Int = parcel.readInt()
                val durationMs: Int = parcel.readInt()
                parcel.readLong() // file_id
                parcel.readLong() // artist_id
                parcel.readLong() // album_id
                parcel.readInt() // parent_id
                parcel.readInt() // storage_id
                parcel.readInt() // ability
                parcel.readInt() // lyricsOffsetMs
                parcel.readInt() // select_parent
                parcel.readLong() // select_artist
                parcel.readLong() // select_album
                val title: String? = parcel.readString()
                val artist: String? = parcel.readString()
                val album: String? = parcel.readString()
                parcel.readString() // albumArt
                val path: String? = parcel.readString()
                parcel.readInt() // audioSessionID
                parcel.readInt() // mediaType
                parcel.readInt() // deviceMask
                return NowPlaying(playStatus, playtimeMs, durationMs, title, artist, album, path)
            }

            private fun clean(value: String?): String? {
                if (value == null) return ""
                return value
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
                    .replace(Regex("\\s+"), " ")
            }
        }
    }

    companion object {
        const val PACKAGE: String = "com.spd.media"

        private val ACTION: String = "android.spd.IMediaService"
        private val SERVICE: String = "com.spd.media.service.MediaService"
        private val DESCRIPTOR: String = "com.spd.media.aidl.IMediaService"
        private const val TRANSACTION_GET_NOW_PLAYING: Int = 6
        private const val MEDIA_PLAYER_STATUS_PLAY: Int = 3
        private const val REBIND_DELAY_MS: Long = 5000L

        private var instance: SpdMediaServiceClient? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): SpdMediaServiceClient {
            if (SpdMediaServiceClient.instance == null)
                SpdMediaServiceClient.instance = SpdMediaServiceClient(context)
            return SpdMediaServiceClient.instance!!
        }
    }
}
