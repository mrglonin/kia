package kia.app.media.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.text.TextUtils
import java.util.Locale
import kia.app.core.AppLog

internal class SpdRadioServiceClient private constructor(context: Context) {
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
                AppLog.line(app, "Radio service: binder died")
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
                AppLog.line(app, "Radio service: connected " + name!!.flattenToShortString())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                bound = false
                binding = false
                AppLog.line(app, "Radio service: disconnected " + name!!.flattenToShortString())
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

    fun readSnapshot(): SpdRadioServiceClient.Snapshot? {
        ensureBound()
        val remote: IBinder? = binder
        if (remote == null || !remote.isBinderAlive()) return null

        val freqInfo: RadioFreqInfo? = readFreqInfo(remote)
        val status: RadioStatus? = readRadioStatus(remote)
        val listName: String? = readListStationName(remote, freqInfo)
        val rdsPs: String? = readRdsText(remote, SpdRadioServiceClient.TRANSACTION_GET_RADIO_RDS_PS)
        val rdsRt: String? = readRdsText(remote, SpdRadioServiceClient.TRANSACTION_GET_RADIO_RDS_RT)
        if (freqInfo == null && status == null && TextUtils.isEmpty(listName)) {
            return null
        }
        return SpdRadioServiceClient.Snapshot(freqInfo, status, listName, rdsPs, rdsRt)
    }

    private fun readFreqInfo(remote: IBinder): RadioFreqInfo? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(SpdRadioServiceClient.DESCRIPTOR)
            if (!remote.transact(SpdRadioServiceClient.TRANSACTION_GET_FREQ_INFO, data, reply, 0))
                return null
            reply!!.readException()
            if (reply.readInt() == 0) return null
            return RadioFreqInfo.read(reply)
        } catch (e: Exception) {
            reportError("read freq failed " + e.javaClass.getSimpleName())
            return null
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun readRadioStatus(remote: IBinder): RadioStatus? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(SpdRadioServiceClient.DESCRIPTOR)
            if (
                !remote.transact(SpdRadioServiceClient.TRANSACTION_GET_RADIO_STATUS, data, reply, 0)
            )
                return null
            reply!!.readException()
            if (reply.readInt() == 0) return null
            return RadioStatus.read(reply)
        } catch (e: Exception) {
            reportError("read status failed " + e.javaClass.getSimpleName())
            return null
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun readListStationName(remote: IBinder, current: RadioFreqInfo?): String? {
        if (current == null || current.freq <= 0) return ""
        val source: String = SpdRadioServiceClient.sourceFor(current)!!
        val name: String? = readListStationName(remote, source, current.freq)
        if (!TextUtils.isEmpty(name)) return name
        if (!"FM".equals(source)) return ""
        return readListStationName(remote, "FM_FAVORITES", current.freq)
    }

    private fun readListStationName(remote: IBinder, band: String?, freq: Int): String? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(SpdRadioServiceClient.DESCRIPTOR)
            data.writeString(band)
            if (!remote.transact(SpdRadioServiceClient.TRANSACTION_GET_RADIO_LIST, data, reply, 0))
                return ""
            reply!!.readException()
            val size: Int = reply.readInt()
            if (size <= 0 || size > 256) return ""
            for (i in 0..<size) {
                if (reply.readInt() == 0) continue
                val item: RadioFreqInfo = RadioFreqInfo.read(reply)
                if (item.freq == freq && !TextUtils.isEmpty(item.ps)) return item.ps
            }
        } catch (e: Exception) {
            reportError("read list failed " + e.javaClass.getSimpleName())
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
        return ""
    }

    private fun readRdsText(remote: IBinder, transaction: Int): String? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(SpdRadioServiceClient.DESCRIPTOR)
            if (!remote.transact(transaction, data, reply, 0)) return ""
            reply!!.readException()
            return SpdRadioServiceClient.clean(reply.readString())
        } catch (e: Exception) {
            reportError("read rds failed " + e.javaClass.getSimpleName())
            return ""
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
        if (now - lastBindAt < SpdRadioServiceClient.REBIND_DELAY_MS) return
        lastBindAt = now

        val intent: Intent = Intent(SpdRadioServiceClient.ACTION)
        intent.setComponent(
            ComponentName(SpdRadioServiceClient.PACKAGE, SpdRadioServiceClient.SERVICE)
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
        AppLog.line(app, "Radio service: " + message)
    }

    internal class Snapshot
    internal constructor(
        freqInfo: RadioFreqInfo?,
        status: RadioStatus?,
        listName: String?,
        rdsPs: String?,
        rdsRt: String?,
    ) {
        private val freqInfo: RadioFreqInfo?
        private val status: RadioStatus?
        private val listName: String?
        private val rdsPs: String?
        private val rdsRt: String?

        init {
            this.freqInfo = freqInfo
            this.status = status
            this.listName = SpdRadioServiceClient.clean(listName)
            this.rdsPs = SpdRadioServiceClient.clean(rdsPs)
            this.rdsRt = SpdRadioServiceClient.clean(rdsRt)
        }

        fun isPlaying(): Boolean {
            return status != null && status.playState == SpdRadioServiceClient.PLAY_STATE_PLAYING
        }

        fun isCurrent(): Boolean {
            return isPlaying() || (status != null && status.isSearching())
        }

        fun isSearching(): Boolean {
            return status != null && status.isSearching()
        }

        fun hasFrequency(): Boolean {
            return freqInfo != null && freqInfo.freq > 0
        }

        fun source(): String? {
            return SpdRadioServiceClient.sourceFor(freqInfo)
        }

        fun frequencyText(): String? {
            if (freqInfo == null) return ""
            return SpdRadioServiceClient.formatFrequency(source(), freqInfo.freq)
        }

        fun stationName(teyesLocalName: String?): String? {
            return SpdRadioServiceClient.firstNonEmpty(
                teyesLocalName,
                listName,
                if (freqInfo == null) "" else freqInfo.ps,
                rdsPs,
                rdsRt,
            )
        }

        fun stationNameForUniversal(): String? {
            // RDS RT is programme/song text and changes while listening. It must not be learned
            // as the persistent station name. TEYES keeps the legacy method above unchanged.
            return SpdRadioServiceClient.selectUniversalStationName(
                listName,
                if (freqInfo == null) "" else freqInfo.ps,
                rdsPs,
                rdsRt,
            )
        }
    }

    internal class RadioFreqInfo(band: String?, freq: Int, ps: String?) {
        @JvmField val band: String?
        @JvmField val freq: Int
        @JvmField val ps: String?

        init {
            this.band = SpdRadioServiceClient.clean(band)
            this.freq = freq
            this.ps = SpdRadioServiceClient.clean(ps)
        }

        companion object {
            @JvmStatic
            fun read(parcel: Parcel): RadioFreqInfo {
                val band: String? = parcel.readString()
                val freq: Int = parcel.readInt()
                parcel.readInt() // min
                parcel.readInt() // max
                parcel.readInt() // step
                parcel.readInt() // pi
                parcel.readInt() // signal
                val ps: String? = parcel.readString()
                return RadioFreqInfo(band, freq, ps)
            }
        }
    }

    internal class RadioStatus(
        seeking: Boolean,
        previewScanning: Boolean,
        autoSearching: Boolean,
        playState: Int,
    ) {
        @JvmField val seeking: Boolean
        @JvmField val previewScanning: Boolean
        @JvmField val autoSearching: Boolean
        @JvmField val playState: Int

        init {
            this.seeking = seeking
            this.previewScanning = previewScanning
            this.autoSearching = autoSearching
            this.playState = playState
        }

        fun isSearching(): Boolean {
            return seeking || previewScanning || autoSearching
        }

        companion object {
            @JvmStatic
            fun read(parcel: Parcel): RadioStatus {
                parcel.readInt() // signalLevel
                parcel.readInt() // stereo
                parcel.readInt() // rdsTPInfo
                parcel.readInt() // rdsTAInfo
                val seeking: Boolean = parcel.readInt() != 0
                val previewScanning: Boolean = parcel.readInt() != 0
                val autoSearching: Boolean = parcel.readInt() != 0
                parcel.readInt() // local
                parcel.readInt() // rdsPTY
                val playState: Int = parcel.readInt()
                return RadioStatus(seeking, previewScanning, autoSearching, playState)
            }
        }
    }

    companion object {
        const val PACKAGE: String = "com.spd.radio"

        private val ACTION: String = "com.spd.radio.service"
        private val SERVICE: String = "com.spd.radio.service.RadioService"
        private val DESCRIPTOR: String = "com.spd.radio.IRadioAidlInterface"
        private const val TRANSACTION_GET_FREQ_INFO: Int = 5
        private const val TRANSACTION_GET_RADIO_RDS_PS: Int = 11
        private const val TRANSACTION_GET_RADIO_RDS_RT: Int = 12
        private const val TRANSACTION_GET_RADIO_LIST: Int = 18
        private const val TRANSACTION_GET_RADIO_STATUS: Int = 13
        private const val PLAY_STATE_PLAYING: Int = 1
        private const val REBIND_DELAY_MS: Long = 5000L

        private var instance: SpdRadioServiceClient? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): SpdRadioServiceClient {
            if (SpdRadioServiceClient.instance == null)
                SpdRadioServiceClient.instance = SpdRadioServiceClient(context)
            return SpdRadioServiceClient.instance!!
        }

        @JvmStatic
        fun selectUniversalStationName(
            listName: String?,
            frequencyPs: String?,
            rdsPs: String?,
            ignoredRdsRt: String?,
        ): String? {
            val candidates: kotlin.Array<String?> = arrayOf<String?>(listName, frequencyPs, rdsPs)
            for (candidate: String? in candidates) {
                val value: String? = SpdRadioServiceClient.clean(candidate)
                if (!value!!.isEmpty()) return value
            }
            return ""
        }

        private fun formatFrequency(source: String?, freq: Int): String? {
            if (freq <= 0) return ""
            if ("AM".equals(source)) return java.lang.String.valueOf(freq)
            val mhz: Double = if (freq > 20000) freq / 1000.0 else freq / 100.0
            var text: String? = String.format(Locale.US, "%.2f", mhz)
            while (text!!.endsWith("0")) text = text.substring(0, text.length - 1)
            if (text.endsWith(".")) text = text.substring(0, text.length - 1)
            return text
        }

        private fun sourceFor(freqInfo: RadioFreqInfo?): String? {
            if (freqInfo == null) return "FM"
            val band: String? = SpdRadioServiceClient.clean(freqInfo.band)!!.uppercase(Locale.US)
            if (band!!.contains("AM")) return "AM"
            if (band.contains("FM") || band.contains("OIRT")) return "FM"
            return if (freqInfo.freq > 30000) "FM" else "AM"
        }

        private fun firstNonEmpty(vararg values: String?): String? {
            for (value: String? in values) {
                val clean: String? = SpdRadioServiceClient.clean(value)
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
