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

internal class SpdBluetoothServiceClient private constructor(context: Context) {
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
                AppLog.line(app, "BT service: binder died")
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
                AppLog.line(app, "BT service: connected " + name!!.flattenToShortString())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                bound = false
                binding = false
                AppLog.line(app, "BT service: disconnected " + name!!.flattenToShortString())
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

    fun readSnapshot(playPositionMs: Long): SpdBluetoothServiceClient.Snapshot? {
        ensureBound()
        val remote: IBinder? = binder
        if (remote == null || !remote.isBinderAlive()) return null

        val playbackStatus: Int? = readPlaybackStatus(remote)
        val info: MusicInfo? = readMusicInfo(remote)
        if (playbackStatus == null && info == null) return null
        val status: Int = if (playbackStatus == null) -1 else playbackStatus
        return SpdBluetoothServiceClient.Snapshot(status, playPositionMs, info)
    }

    fun readCallSnapshot(): CallSnapshot? {
        ensureBound()
        val remote: IBinder? = binder
        if (remote == null || !remote.isBinderAlive()) return null

        val status: Int? = readDialStatus(remote)
        val calls: kotlin.Array<CallLogInfo?>? = readDialInfo(remote)
        if (status == null && (calls == null || calls.size == 0)) return null
        val call: CallLogInfo? = SpdBluetoothServiceClient.firstCall(calls)
        return CallSnapshot(if (status == null) -1 else status, call)
    }

    private fun readPlaybackStatus(remote: IBinder): Int? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(SpdBluetoothServiceClient.DESCRIPTOR)
            if (
                !remote.transact(
                    SpdBluetoothServiceClient.TRANSACTION_GET_PLAYBACK_STATUS,
                    data,
                    reply,
                    0,
                )
            )
                return null
            reply!!.readException()
            return reply.readInt()
        } catch (e: Exception) {
            reportError("read status failed " + e.javaClass.getSimpleName())
            return null
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun readMusicInfo(remote: IBinder): MusicInfo? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(SpdBluetoothServiceClient.DESCRIPTOR)
            if (
                !remote.transact(
                    SpdBluetoothServiceClient.TRANSACTION_GET_MUSIC_INFO,
                    data,
                    reply,
                    0,
                )
            )
                return null
            reply!!.readException()
            if (reply.readInt() == 0) return null
            return MusicInfo.read(reply)
        } catch (e: Exception) {
            reportError("read info failed " + e.javaClass.getSimpleName())
            return null
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun readDialStatus(remote: IBinder): Int? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(SpdBluetoothServiceClient.DESCRIPTOR)
            if (
                !remote.transact(
                    SpdBluetoothServiceClient.TRANSACTION_GET_DIAL_STATUS,
                    data,
                    reply,
                    0,
                )
            )
                return null
            reply!!.readException()
            return reply.readInt()
        } catch (e: Exception) {
            reportError("read dial status failed " + e.javaClass.getSimpleName())
            return null
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun readDialInfo(remote: IBinder): kotlin.Array<CallLogInfo?>? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(SpdBluetoothServiceClient.DESCRIPTOR)
            if (
                !remote.transact(
                    SpdBluetoothServiceClient.TRANSACTION_GET_DIAL_INFO,
                    data,
                    reply,
                    0,
                )
            )
                return null
            reply!!.readException()
            val count: Int = reply.readInt()
            if (count < 0) return null
            val calls: kotlin.Array<CallLogInfo?> = arrayOfNulls<CallLogInfo>(count)
            for (i in 0..<count) {
                if (reply.readInt() == 0) continue
                calls[i] = CallLogInfo.read(reply)
            }
            return calls
        } catch (e: Exception) {
            reportError("read dial info failed " + e.javaClass.getSimpleName())
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
        if (now - lastBindAt < SpdBluetoothServiceClient.REBIND_DELAY_MS) return
        lastBindAt = now

        val intent: Intent = Intent(SpdBluetoothServiceClient.ACTION)
        intent.setComponent(
            ComponentName(SpdBluetoothServiceClient.PACKAGE, SpdBluetoothServiceClient.SERVICE)
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
        AppLog.line(app, "BT service: " + message)
    }

    internal class Snapshot
    internal constructor(playbackStatus: Int, playPositionMs: Long, info: MusicInfo?) {
        @JvmField val playbackStatus: Int
        @JvmField val playPositionMs: Long
        @JvmField val title: String?
        @JvmField val artist: String?
        @JvmField val album: String?
        @JvmField val duration: String?

        init {
            this.playbackStatus = playbackStatus
            this.playPositionMs = playPositionMs
            this.title = if (info == null) "" else info.title
            this.artist = if (info == null) "" else info.artist
            this.album = if (info == null) "" else info.album
            this.duration = if (info == null) "" else info.duration
        }

        fun isPlaying(): Boolean {
            return playbackStatus == SpdBluetoothServiceClient.PLAYBACK_STATE_PLAYING
        }

        fun hasText(): Boolean {
            return !TextUtils.isEmpty(title) || !TextUtils.isEmpty(artist)
        }

        fun remainingMs(): Long {
            val durationMs: Long = SpdBluetoothServiceClient.parseDurationMs(duration)
            if (durationMs <= 0L || playPositionMs < 0L) return -1L
            return Math.max(0L, durationMs - playPositionMs)
        }
    }

    internal class CallSnapshot internal constructor(dialStatus: Int, call: CallLogInfo?) {
        @JvmField val dialStatus: Int
        @JvmField val name: String?
        @JvmField val number: String?
        @JvmField val state: Int
        @JvmField val type: Int
        @JvmField val contactId: Long

        init {
            this.dialStatus = dialStatus
            this.name = if (call == null) "" else call.name
            this.number = if (call == null) "" else call.number
            this.state = if (call == null) -1 else call.state
            this.type = if (call == null) -1 else call.type
            this.contactId = if (call == null) -1L else call.contactId
        }

        fun active(): Boolean {
            return dialStatus > 0 ||
                state >= 0 ||
                !TextUtils.isEmpty(number) ||
                !TextUtils.isEmpty(name)
        }

        fun hasIdentity(): Boolean {
            return !TextUtils.isEmpty(name) || !TextUtils.isEmpty(number)
        }
    }

    internal class CallLogInfo(
        id: Int,
        contactId: Long,
        type: Int,
        name: String?,
        number: String?,
        date: String?,
        duration: String?,
        state: Int,
        local: String?,
    ) {
        @JvmField val id: Int
        @JvmField val contactId: Long
        @JvmField val type: Int
        @JvmField val name: String?
        @JvmField val number: String?
        @JvmField val date: String?
        @JvmField val duration: String?
        @JvmField val state: Int
        @JvmField val local: String?

        init {
            this.id = id
            this.contactId = contactId
            this.type = type
            this.name = SpdBluetoothServiceClient.clean(name)
            this.number = SpdBluetoothServiceClient.clean(number)
            this.date = SpdBluetoothServiceClient.clean(date)
            this.duration = SpdBluetoothServiceClient.clean(duration)
            this.state = state
            this.local = SpdBluetoothServiceClient.clean(local)
        }

        companion object {
            @JvmStatic
            fun read(parcel: Parcel): CallLogInfo {
                val id: Int = parcel.readInt()
                val contactId: Long = parcel.readLong()
                val type: Int = parcel.readInt()
                val name: String? = parcel.readString()
                val number: String? = parcel.readString()
                val date: String? = parcel.readString()
                val duration: String? = parcel.readString()
                val state: Int = parcel.readInt()
                val local: String? = parcel.readString()
                return CallLogInfo(id, contactId, type, name, number, date, duration, state, local)
            }
        }
    }

    internal class MusicInfo(
        title: String?,
        artist: String?,
        album: String?,
        duration: String?,
        cover: String?,
    ) {
        @JvmField val title: String?
        @JvmField val artist: String?
        @JvmField val album: String?
        @JvmField val duration: String?
        @JvmField val cover: String?

        init {
            this.title = SpdBluetoothServiceClient.clean(title)
            this.artist = SpdBluetoothServiceClient.clean(artist)
            this.album = SpdBluetoothServiceClient.clean(album)
            this.duration = SpdBluetoothServiceClient.clean(duration)
            this.cover = SpdBluetoothServiceClient.clean(cover)
        }

        companion object {
            @JvmStatic
            fun read(parcel: Parcel): MusicInfo {
                val title: String? = parcel.readString()
                val artist: String? = parcel.readString()
                val album: String? = parcel.readString()
                val duration: String? = parcel.readString()
                val cover: String? = parcel.readString()
                return MusicInfo(title, artist, album, duration, cover)
            }
        }
    }

    companion object {
        const val PACKAGE: String = "com.spd.bluetooth"

        private val ACTION: String = "com.spd.bluetooth.service.BluetoothService"
        private val SERVICE: String = "com.spd.bluetooth.service.BluetoothService"
        private val DESCRIPTOR: String = "com.spd.bluetooth.aidl.IBluetoothService"
        private const val TRANSACTION_GET_MUSIC_INFO: Int = 39
        private const val TRANSACTION_GET_PLAYBACK_STATUS: Int = 40
        private const val TRANSACTION_GET_DIAL_STATUS: Int = 42
        private const val TRANSACTION_GET_DIAL_INFO: Int = 43
        private const val PLAYBACK_STATE_PLAYING: Int = 3
        private const val REBIND_DELAY_MS: Long = 5000L

        private var instance: SpdBluetoothServiceClient? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): SpdBluetoothServiceClient {
            if (SpdBluetoothServiceClient.instance == null)
                SpdBluetoothServiceClient.instance = SpdBluetoothServiceClient(context)
            return SpdBluetoothServiceClient.instance!!
        }

        private fun firstCall(calls: kotlin.Array<CallLogInfo?>?): CallLogInfo? {
            if (calls == null) return null
            for (call: CallLogInfo? in calls) {
                if (
                    call != null &&
                        (!TextUtils.isEmpty(call.number) || !TextUtils.isEmpty(call.name))
                ) {
                    return call
                }
            }
            for (call: CallLogInfo? in calls) {
                if (call != null) return call
            }
            return null
        }

        private fun clean(value: String?): String? {
            if (value == null) return ""
            return value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
        }

        private fun parseDurationMs(value: String?): Long {
            val text: String? = if (value == null) "" else value.trim()
            if (TextUtils.isEmpty(text)) return -1L
            try {
                if (text!!.indexOf(':') >= 0) {
                    val parts: kotlin.Array<out String?>? =
                        java.util.regex.Pattern.compile(":").split(text)
                    var seconds: Long = 0L
                    for (part in parts!!) {
                        seconds = seconds * 60L + java.lang.Long.parseLong(part!!.trim())
                    }
                    return seconds * 1000L
                }
                val raw: Long = java.lang.Long.parseLong(text.replace(Regex("[^0-9]"), ""))
                if (raw <= 0L) return -1L
                return if (raw > 1000L) raw else raw * 1000L
            } catch (ignored: Exception) {
                return -1L
            }
        }
    }
}
