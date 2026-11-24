package kia.app.media.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.text.TextUtils
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import kia.app.core.AppLog

internal class TeyesRadioBrowserClient private constructor(context: Context) {
    private val app: Context
    private var binder: IBinder? = null
    private var binding: Boolean = false
    private var bound: Boolean = false
    private var lastBindAt: Long = 0
    private var lastReadAt: Long = 0
    private var lastError: String? = ""
    private var lastListKey: String? = ""
    private var lastMatchKey: String = ""
    private var stations: List<Station?> = ArrayList()

    private val deathRecipient: IBinder.DeathRecipient =
        object : IBinder.DeathRecipient {
            override fun binderDied() {
                binder = null
                bound = false
                binding = false
                AppLog.line(app, "TEYES local radio: binder died")
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
                AppLog.line(app, "TEYES local radio: connected " + name!!.flattenToShortString())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                bound = false
                binding = false
                AppLog.line(app, "TEYES local radio: disconnected " + name!!.flattenToShortString())
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

    fun stationName(source: String?, frequencyText: String?): String? {
        if (!"FM".equals(source)) return ""
        val target: Int = TeyesRadioBrowserClient.parseFrequency(frequencyText)
        if (target <= 0) return ""
        val snapshot: List<Station?>? = readStations()
        for (station in snapshot!!) {
            if (station!!.frequencyKhz() == target && !TextUtils.isEmpty(station.name)) {
                reportMatch(frequencyText, station.name)
                return station.name
            }
        }
        reportMatch(frequencyText, "")
        return ""
    }

    private fun readStations(): List<Station?>? {
        ensureBound()
        val now: Long = System.currentTimeMillis()
        val remote: IBinder? = binder
        if (remote == null || !remote.isBinderAlive()) return stations
        if (!stations.isEmpty() && now - lastReadAt < TeyesRadioBrowserClient.READ_DELAY_MS)
            return stations
        lastReadAt = now

        val fresh: List<Station?>? = readLocalList(remote)
        if (!fresh!!.isEmpty()) {
            stations = fresh
            reportList(fresh)
        }
        return stations
    }

    private fun readLocalList(remote: IBinder): List<Station?>? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(TeyesRadioBrowserClient.DESCRIPTOR)
            data.writeInt(
                0
            ) // FmLocalGetList(false): local TEYES cache, same path used by the widget.
            if (
                !remote.transact(
                    TeyesRadioBrowserClient.TRANSACTION_FM_LOCAL_GET_LIST,
                    data,
                    reply,
                    0,
                )
            )
                return stations
            reply!!.readException()
            val size: Int = reply.readInt()
            if (size <= 0 || size > 256) return ArrayList()
            val out: MutableList<Station?> = ArrayList(size)
            for (i in 0..<size) {
                if (reply.readInt() == 0) continue
                val station: Station = Station.read(reply)
                if (station.frequencyKhz() > 0) out.add(station)
            }
            return out
        } catch (e: Exception) {
            reportError("read list failed " + e.javaClass.getSimpleName())
            return stations
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
        if (now - lastBindAt < TeyesRadioBrowserClient.REBIND_DELAY_MS) return
        lastBindAt = now

        val intent: Intent = Intent(TeyesRadioBrowserClient.ACTION)
        intent.setComponent(
            ComponentName(TeyesRadioBrowserClient.PACKAGE, TeyesRadioBrowserClient.SERVICE)
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
        AppLog.line(app, "TEYES local radio: " + message)
    }

    private fun reportList(list: List<Station?>) {
        val key: String? = java.lang.String.valueOf(list.size)
        if (TextUtils.equals(key, lastListKey)) return
        lastListKey = key
        AppLog.line(app, "TEYES local radio: local station list " + list.size)
    }

    private fun reportMatch(frequencyText: String?, stationName: String?) {
        val key: String = frequencyText.toString() + "|" + stationName
        if (TextUtils.equals(key, lastMatchKey)) return
        lastMatchKey = key
        AppLog.line(
            app,
            ("TEYES local radio: " +
                TeyesRadioBrowserClient.clean(frequencyText) +
                " -> " +
                (if (TextUtils.isEmpty(stationName)) "-" else stationName)),
        )
    }

    private class Station(
        logo: String?,
        id: Int,
        name: String?,
        rate: String?,
        city: String?,
        cityword: String?,
    ) {
        @JvmField val logo: String?
        @JvmField val id: Int
        @JvmField val name: String?
        @JvmField val rate: String?
        @JvmField val city: String?
        @JvmField val cityword: String?

        init {
            this.logo = TeyesRadioBrowserClient.clean(logo)
            this.id = id
            this.name = TeyesRadioBrowserClient.clean(name)
            this.rate = TeyesRadioBrowserClient.clean(rate)
            this.city = TeyesRadioBrowserClient.clean(city)
            this.cityword = TeyesRadioBrowserClient.clean(cityword)
        }

        fun frequencyKhz(): Int {
            return TeyesRadioBrowserClient.parseFrequency(rate)
        }

        override fun toString(): String {
            return String.format(Locale.US, "%s %s", rate, name)
        }

        companion object {
            @JvmStatic
            fun read(parcel: Parcel): Station {
                TeyesRadioBrowserClient.skipBaseInfo(parcel)
                val logo: String? = parcel.readString()
                val id: Int = parcel.readInt()
                val name: String? = parcel.readString()
                val rate: String? = parcel.readString()
                val city: String? = parcel.readString()
                val cityword: String? = parcel.readString()
                return Station(logo, id, name, rate, city, cityword)
            }
        }
    }

    companion object {
        private val PACKAGE: String = "com.spd.home"
        private val ACTION: String = "com.spd.teyes.radio.browser"
        private val SERVICE: String = "com.spd.teyes.TeyesNetFmService"
        private val DESCRIPTOR: String = "com.spd.teyes.ITEyesNetFmBroswerService"
        private const val TRANSACTION_FM_LOCAL_GET_LIST: Int = 5
        private const val REBIND_DELAY_MS: Long = 5000L
        private const val READ_DELAY_MS: Long = 5000L

        private var instance: TeyesRadioBrowserClient? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): TeyesRadioBrowserClient {
            if (TeyesRadioBrowserClient.instance == null)
                TeyesRadioBrowserClient.instance = TeyesRadioBrowserClient(context)
            return TeyesRadioBrowserClient.instance!!
        }

        private fun skipBaseInfo(parcel: Parcel) {
            parcel.readHashMap(HashMap::class.java.getClassLoader())
            parcel.readHashMap(HashMap::class.java.getClassLoader())
        }

        private fun parseFrequency(value: String?): Int {
            val clean: String? = TeyesRadioBrowserClient.clean(value)!!.replace(',', '.')
            if (TextUtils.isEmpty(clean)) return -1
            try {
                if (clean!!.contains(".")) {
                    return Math.round(java.lang.Double.parseDouble(clean) * 1000.0).toInt()
                }
                val digits = clean.replace(Regex("[^0-9]"), "")
                if (TextUtils.isEmpty(digits)) return -1
                val raw: Int = Integer.parseInt(digits)
                if (raw >= 65000 && raw <= 108000) return raw
                if (raw >= 6500 && raw <= 10800) return raw * 10
                if (raw >= 650 && raw <= 1080) return raw * 100
                if (raw >= 65 && raw <= 108) return raw * 1000
                return raw
            } catch (ignored: Exception) {
                return -1
            }
        }

        private fun clean(value: String?): String? {
            if (value == null) return ""
            val out = value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
            if ("null".equals(out, ignoreCase = true) || "[]".equals(out)) return ""
            return out
        }
    }
}
