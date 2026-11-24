package kia.app.media.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.text.TextUtils
import java.util.HashMap
import kia.app.core.AppLog

internal class TeyesOnlineRadioClient private constructor(context: Context) {
    private val app: Context
    private var binder: IBinder? = null
    private var binding: Boolean = false
    private var bound: Boolean = false
    private var lastBindAt: Long = 0
    private var lastError: String? = ""
    private var lastInfoKey: String = ""

    private val deathRecipient: IBinder.DeathRecipient =
        object : IBinder.DeathRecipient {
            override fun binderDied() {
                binder = null
                bound = false
                binding = false
                AppLog.line(app, "TEYES online radio: binder died")
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
                AppLog.line(app, "TEYES online radio: connected " + name!!.flattenToShortString())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                bound = false
                binding = false
                AppLog.line(
                    app,
                    "TEYES online radio: disconnected " + name!!.flattenToShortString(),
                )
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

    fun readSnapshot(): TeyesOnlineRadioClient.Snapshot? {
        ensureBound()
        val remote: IBinder? = binder
        if (remote == null || !remote.isBinderAlive()) return null

        val playState: Int = readPlayState(remote)
        val track: OnlineInfo? = readTrackInfo(remote)
        val rank: OnlineInfo? = readRankInfo(remote)
        val info: OnlineInfo? = if (track != null && track.hasText()) track else rank
        if (info == null || !info.hasText()) return null

        val snapshot: TeyesOnlineRadioClient.Snapshot =
            TeyesOnlineRadioClient.Snapshot(playState, info.artist, info.title)
        reportInfo(snapshot)
        return snapshot
    }

    private fun readPlayState(remote: IBinder): Int {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(TeyesOnlineRadioClient.DESCRIPTOR)
            if (!remote.transact(TeyesOnlineRadioClient.TRANSACTION_GET_PLAY_STATE, data, reply, 0))
                return Integer.MIN_VALUE
            reply!!.readException()
            return reply.readInt()
        } catch (e: Exception) {
            reportError("read state failed " + e.javaClass.getSimpleName())
            return Integer.MIN_VALUE
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun readTrackInfo(remote: IBinder): OnlineInfo? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(TeyesOnlineRadioClient.DESCRIPTOR)
            if (!remote.transact(TeyesOnlineRadioClient.TRANSACTION_GET_TRACK_INFO, data, reply, 0))
                return null
            reply!!.readException()
            if (reply.readInt() == 0) return null
            return OnlineInfo.readTrack(reply)
        } catch (e: Exception) {
            reportError("read track failed " + e.javaClass.getSimpleName())
            return null
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun readRankInfo(remote: IBinder): OnlineInfo? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(TeyesOnlineRadioClient.DESCRIPTOR)
            if (!remote.transact(TeyesOnlineRadioClient.TRANSACTION_GET_RANK_INFO, data, reply, 0))
                return null
            reply!!.readException()
            if (reply.readInt() == 0) return null
            return OnlineInfo.readRank(reply)
        } catch (e: Exception) {
            reportError("read rank failed " + e.javaClass.getSimpleName())
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
        if (now - lastBindAt < TeyesOnlineRadioClient.REBIND_DELAY_MS) return
        lastBindAt = now

        val intent: Intent = Intent(TeyesOnlineRadioClient.ACTION)
        intent.setComponent(
            ComponentName(TeyesOnlineRadioClient.PACKAGE, TeyesOnlineRadioClient.SERVICE)
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
        AppLog.line(app, "TEYES online radio: " + message)
    }

    private fun reportInfo(snapshot: TeyesOnlineRadioClient.Snapshot) {
        val key: String =
            snapshot.playState.toString() + "|" + snapshot.artist + "|" + snapshot.title
        if (TextUtils.equals(key, lastInfoKey)) return
        lastInfoKey = key
        AppLog.line(
            app,
            ("TEYES online radio: state=" +
                snapshot.playState +
                " | " +
                (if (TextUtils.isEmpty(snapshot.artist)) "-" else snapshot.artist) +
                " | " +
                snapshot.title),
        )
    }

    internal class Snapshot internal constructor(playState: Int, artist: String?, title: String?) {
        internal val playState: Int
        internal val artist: String?
        internal val title: String?

        init {
            this.playState = playState
            this.artist = TeyesOnlineRadioClient.clean(artist)
            this.title = TeyesOnlineRadioClient.clean(title)
        }

        fun isCurrent(): Boolean {
            return hasText() &&
                (isPlaying() ||
                    playState == TeyesOnlineRadioClient.PLAY_STATE_LOADING ||
                    playState == TeyesOnlineRadioClient.PLAY_STATE_BUFFERING ||
                    playState == TeyesOnlineRadioClient.PLAY_STATE_ERROR)
        }

        fun isPlaying(): Boolean {
            return playState == TeyesOnlineRadioClient.PLAY_STATE_PLAYING
        }

        fun source(): String? {
            return "TEYES"
        }

        fun artist(): String? {
            return artist
        }

        fun title(): String? {
            return title
        }

        private fun hasText(): Boolean {
            return !TextUtils.isEmpty(title) || !TextUtils.isEmpty(artist)
        }
    }

    private class OnlineInfo(artist: String?, title: String?) {
        @JvmField val artist: String?
        @JvmField val title: String?

        init {
            this.artist = TeyesOnlineRadioClient.clean(artist)
            this.title = TeyesOnlineRadioClient.clean(title)
        }

        fun hasText(): Boolean {
            return !TextUtils.isEmpty(title) || !TextUtils.isEmpty(artist)
        }

        companion object {
            @JvmStatic
            fun readTrack(parcel: Parcel): OnlineInfo {
                TeyesOnlineRadioClient.skipBaseInfo(parcel)
                if (parcel.readInt() == 1) TeyesOnlineRadioClient.skipTrackParent(parcel)
                val logo: String? = parcel.readString()
                parcel.readInt() // id
                val name: String? = parcel.readString()
                val bit: String? = parcel.readString()
                parcel.readInt() // favorite
                parcel.readByte() // isfavorite
                parcel.readInt() // playcount
                parcel.readInt() // ranking
                parcel.readString() // url
                val country: String? = parcel.readString()
                return OnlineInfo(TeyesOnlineRadioClient.firstNonEmpty(country, bit, logo), name)
            }

            @JvmStatic
            fun readRank(parcel: Parcel): OnlineInfo {
                TeyesOnlineRadioClient.skipBaseInfo(parcel)
                if (parcel.readInt() == 1) TeyesOnlineRadioClient.skipRankParent(parcel)
                parcel.readInt() // id
                val title: String? = parcel.readString()
                parcel.readString() // cover
                parcel.readLong() // createTime
                parcel.readLong() // updateTime
                parcel.readInt() // rid
                parcel.readLong() // rdate
                parcel.readString() // locurl
                val status: String? = parcel.readString()
                parcel.readInt() // weigh
                parcel.readInt() // fav
                parcel.readInt() // play
                parcel.readInt() // vstatus
                parcel.readString() // localurl
                parcel.readString() // url
                parcel.readInt() // vgg
                parcel.readInt() // cid
                val category: String? = parcel.readString()
                parcel.readInt() // yq
                parcel.readInt() // feedback
                parcel.readByte() // isfavorite
                parcel.readInt() // ranking
                return OnlineInfo(TeyesOnlineRadioClient.firstNonEmpty(category, status), title)
            }
        }
    }

    companion object {
        const val PACKAGE: String = "com.spd.home"

        private val ACTION: String = "com.spd.teyes.radio.player"
        private val SERVICE: String = "com.spd.teyes.TeyesNetFmService"
        private val DESCRIPTOR: String = "com.spd.teyes.ITeyesNetFmService"
        private const val TRANSACTION_GET_TRACK_INFO: Int = 3
        private const val TRANSACTION_GET_RANK_INFO: Int = 4
        private const val TRANSACTION_GET_PLAY_STATE: Int = 5
        private const val PLAY_STATE_PLAYING: Int = 1
        private val PLAY_STATE_LOADING: Int = -3
        private val PLAY_STATE_BUFFERING: Int = -2
        private val PLAY_STATE_ERROR: Int = -4
        private const val REBIND_DELAY_MS: Long = 5000L

        private var instance: TeyesOnlineRadioClient? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): TeyesOnlineRadioClient {
            if (TeyesOnlineRadioClient.instance == null)
                TeyesOnlineRadioClient.instance = TeyesOnlineRadioClient(context)
            return TeyesOnlineRadioClient.instance!!
        }

        private fun skipTrackParent(parcel: Parcel) {
            TeyesOnlineRadioClient.skipBaseInfo(parcel)
            parcel.readBoolean() // favourite flag
            if (parcel.readInt() == 1) TeyesOnlineRadioClient.skipCategoryInfo(parcel)
            if (parcel.readInt() == 1) TeyesOnlineRadioClient.skipCategoryInfo(parcel)
            if (parcel.readInt() == 1) TeyesOnlineRadioClient.skipCityInfo(parcel)
        }

        private fun skipRankParent(parcel: Parcel) {
            TeyesOnlineRadioClient.skipBaseInfo(parcel)
            parcel.readBoolean() // favourite flag
            if (parcel.readInt() == 1) TeyesOnlineRadioClient.skipRankCategoryInfo(parcel)
        }

        private fun skipCategoryInfo(parcel: Parcel) {
            TeyesOnlineRadioClient.skipBaseInfo(parcel)
            parcel.readInt() // id
            parcel.readString() // name
            parcel.readString() // img
            parcel.readString() // bg
            parcel.readString() // bg2k
            parcel.readString() // img_c4
            parcel.readString() // img_cc4white
            parcel.readInt() // TopID
            parcel.readString() // TopName
            parcel.readInt() // TopPlaycount
            parcel.readString() // TopUrl
            parcel.readString() // country_code
            parcel.readInt() // pid
            parcel.readInt() // iscity
            parcel.readBoolean() // has child
        }

        private fun skipCityInfo(parcel: Parcel) {
            TeyesOnlineRadioClient.skipBaseInfo(parcel)
            parcel.readInt() // id
            parcel.readString() // name
            parcel.readString() // img
            parcel.readInt() // pid
            parcel.readString() // status
            parcel.readInt() // weigh
            parcel.readString() // bg
            parcel.readString() // type
            parcel.readString() // bg2k
            parcel.readString() // img2k
            parcel.readString() // imgc4
            parcel.readString() // bgc4
            parcel.readString() // country_code
            parcel.readString() // active
        }

        private fun skipRankCategoryInfo(parcel: Parcel) {
            TeyesOnlineRadioClient.skipBaseInfo(parcel)
            parcel.readInt() // id
            parcel.readLong() // createTime
            parcel.readLong() // updateTime
            parcel.readString() // title
            parcel.readString() // cover
            parcel.readString() // status
            parcel.readInt() // weigh
            parcel.readString() // logo
            parcel.readString() // cover2k
            parcel.readString() // logo2k
            parcel.readString() // logoc4
            parcel.readString() // logoc4white
        }

        private fun skipBaseInfo(parcel: Parcel) {
            parcel.readHashMap(HashMap::class.java.getClassLoader())
            parcel.readHashMap(HashMap::class.java.getClassLoader())
        }

        private fun firstNonEmpty(vararg values: String?): String? {
            for (value: String? in values) {
                val clean: String? = TeyesOnlineRadioClient.clean(value)
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
