package kia.app.navigation.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.text.TextUtils
import java.util.Locale
import kia.app.core.AppLog
import kia.app.core.settings.AppSettings
import kia.app.navigation.domain.NavigationFeature
import org.json.JSONArray
import org.json.JSONObject

class DgisTripClient(context: Context) : ServiceConnection {
    private val app: Context?
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val callback: TripCallback = TripCallback()
    private var remote: IBinder? = null
    private var running: Boolean = false
    private var bound: Boolean = false
    private var boundPackage: String? = ""
    private var lastTrip: String? = ""
    private var lastQuietLogAt: Long = 0

    init {
        this.app = context.getApplicationContext()
    }

    fun start() {
        if (running) return
        running = true
        bind()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (bound) {
            try {
                app!!.unbindService(this)
            } catch (ignored: Exception) {}
        }
        bound = false
        remote = null
        boundPackage = ""
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        remote = service
        boundPackage = if (name == null) "" else name.getPackageName()
        AppLog.line(app, "2GIS trip: connected " + boundPackage)
        registerCallback()
        val navigating: Boolean =
            transactBoolean(DgisTripClient.TRANSACTION_GET_IS_NAVIGATING, false)
        AppLog.line(app, "2GIS trip: navigating=" + navigating)
        if (!navigating)
            NavigationFeature.get(app!!).handleDgisStopped("2gis_car_trip_connected_idle")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        remote = null
        bound = false
        AppLog.line(app, "2GIS trip: disconnected")
        if (running) handler.postDelayed({ this.bind() }, DgisTripClient.RECONNECT_MS)
    }

    private fun bind() {
        if (!running || bound) return
        if (!sourceEnabled()) {
            logQuiet("2GIS trip: disabled by source mode " + AppSettings.navSourceLabel(app!!))
            handler.postDelayed({ this.bind() }, DgisTripClient.RECONNECT_MS)
            return
        }
        val pkg = installedPackage()
        if (TextUtils.isEmpty(pkg)) {
            logQuiet("2GIS trip: package not installed")
            handler.postDelayed({ this.bind() }, DgisTripClient.RECONNECT_MS)
            return
        }
        val intent: Intent = Intent()
        intent.setComponent(ComponentName(pkg!!, DgisTripClient.SERVICE_CLASS))
        try {
            bound = app!!.bindService(intent, this, Context.BIND_AUTO_CREATE)
            if (!bound) {
                logQuiet("2GIS trip: bind returned false for " + pkg)
                handler.postDelayed({ this.bind() }, DgisTripClient.RECONNECT_MS)
            }
        } catch (e: Exception) {
            bound = false
            logQuiet("2GIS trip: bind failed " + e.javaClass.getSimpleName())
            handler.postDelayed({ this.bind() }, DgisTripClient.RECONNECT_MS)
        }
    }

    private fun sourceEnabled(): Boolean {
        return NavigationSourceGate.dgisEnabled(
            AppSettings.navigationEnabled(app!!),
            AppSettings.dgisNavigationEnabled(app),
        )
    }

    private fun installedPackage(): String? {
        val pm: PackageManager? = app!!.getPackageManager()
        if (DgisTripClient.isInstalled(pm!!, DgisTripClient.PACKAGE)) return DgisTripClient.PACKAGE
        if (DgisTripClient.isInstalled(pm, DgisTripClient.PACKAGE_PREVIEW))
            return DgisTripClient.PACKAGE_PREVIEW
        return ""
    }

    private fun registerCallback() {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(DgisTripClient.SERVICE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            if (
                remote != null &&
                    remote!!.transact(DgisTripClient.TRANSACTION_REGISTER_CALLBACK, data, reply, 0)
            ) {
                reply!!.readException()
                AppLog.line(app, "2GIS trip: callback registered")
            }
        } catch (e: Exception) {
            AppLog.line(app, "2GIS trip register failed " + e.javaClass.getSimpleName())
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun transactBoolean(code: Int, fallback: Boolean): Boolean {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(DgisTripClient.SERVICE_DESCRIPTOR)
            if (remote == null || !remote!!.transact(code, data, reply, 0)) return fallback
            reply!!.readException()
            return reply.readInt() != 0
        } catch (e: Exception) {
            logQuiet("2GIS trip transact " + code + " failed " + e.javaClass.getSimpleName())
            return fallback
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun handleTrip(json: String?) {
        val text = DgisTripClient.clean(json)
        if (TextUtils.isEmpty(text) || text.equals(lastTrip)) return
        lastTrip = text
        try {
            val root: JSONObject = JSONObject(text)
            val state: JSONObject? = root.optJSONObject("State")
            val started: Boolean = root.optBoolean("NavigationStarted", false)
            val currentRoad = root.optString("CurrentRoad", "")
            val maneuverRoad = if (state == null) "" else state.optString("ManeuverRoad", "")
            val destinationName = root.optString("DestinationName", "")
            val destinationAddress = root.optString("DestinationAddress", "")
            val destinationPoint = DgisTripClient.destinationPoint(root)
            if (
                !started &&
                    TextUtils.isEmpty(DgisTripClient.clean(currentRoad)) &&
                    TextUtils.isEmpty(DgisTripClient.clean(maneuverRoad)) &&
                    TextUtils.isEmpty(DgisTripClient.clean(destinationName)) &&
                    TextUtils.isEmpty(DgisTripClient.clean(destinationAddress))
            ) {
                logQuiet("2GIS trip: ignored non-trip payload " + DgisTripClient.shortText(text))
                return
            }
            NavigationFeature.get(app!!)
                .handleDgisTrip(
                    currentRoad,
                    maneuverRoad,
                    destinationName,
                    destinationAddress,
                    started,
                    if (destinationPoint == null) Double.NaN else destinationPoint.get(0),
                    if (destinationPoint == null) Double.NaN else destinationPoint.get(1),
                    text,
                )
            AppLog.line(
                app,
                ("2GIS trip: started=" +
                    started +
                    " current=" +
                    DgisTripClient.clean(currentRoad) +
                    " next=" +
                    DgisTripClient.clean(maneuverRoad) +
                    " dest=" +
                    DgisTripClient.clean(DgisTripClient.first(destinationAddress, destinationName))),
            )
        } catch (e: Exception) {
            AppLog.line(
                app,
                ("2GIS trip JSON failed: " +
                    e.javaClass.getSimpleName() +
                    " " +
                    DgisTripClient.shortText(text)),
            )
        }
    }

    private fun logQuiet(value: String?) {
        val now: Long = System.currentTimeMillis()
        if (now - lastQuietLogAt < DgisTripClient.QUIET_LOG_MS) return
        lastQuietLogAt = now
        AppLog.line(app, value)
    }

    private class Point {
        @JvmField var lat: Double = Double.NaN
        @JvmField var lon: Double = Double.NaN

        fun valid(): Boolean {
            return !java.lang.Double.isNaN(lat) &&
                !java.lang.Double.isNaN(lon) &&
                lat >= -90.0 &&
                lat <= 90.0 &&
                lon >= -180.0 &&
                lon <= 180.0 &&
                !(lat == 0.0 && lon == 0.0)
        }
    }

    private inner class TripCallback : Binder(), IInterface {
        init {
            attachInterface(this, DgisTripClient.CALLBACK_DESCRIPTOR)
        }

        override fun asBinder(): IBinder {
            return this
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                if (code == DgisTripClient.INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(DgisTripClient.CALLBACK_DESCRIPTOR)
                    return true
                }
                if (data != null) data.enforceInterface(DgisTripClient.CALLBACK_DESCRIPTOR)
                when (code) {
                    DgisTripClient.CALLBACK_UPDATE_TRIP -> {
                        handleTrip(if (data == null) "" else data.readString())
                        writeNoException(reply)
                        return true
                    }
                    DgisTripClient.CALLBACK_GET_DENSITY -> {
                        writeNoException(reply)
                        if (reply != null)
                            reply.writeFloat(app!!.getResources().getDisplayMetrics().density)
                        return true
                    }
                    DgisTripClient.CALLBACK_START_NAVIGATION -> {
                        AppLog.line(app, "2GIS trip: startNavigation")
                        writeNoException(reply)
                        return true
                    }
                    DgisTripClient.CALLBACK_STOP_NAVIGATION -> {
                        AppLog.line(app, "2GIS trip: stopNavigation")
                        lastTrip = ""
                        NavigationFeature.get(app!!)
                            .handleDgisStopped("2gis_car_trip_stop_callback")
                        writeNoException(reply)
                        return true
                    }
                    DgisTripClient.CALLBACK_SCREEN_PUSH,
                    DgisTripClient.CALLBACK_SCREEN_INVALIDATE -> {
                        writeNoException(reply)
                        return true
                    }
                    else -> {
                        writeNoException(reply)
                        return true
                    }
                }
            } catch (e: Exception) {
                AppLog.line(
                    app,
                    ("2GIS trip callback failed " + code + " " + e.javaClass.getSimpleName()),
                )
                writeNoException(reply)
                return true
            }
        }

        fun writeNoException(reply: Parcel?) {
            if (reply != null) reply.writeNoException()
        }
    }

    companion object {
        private val PACKAGE: String = "ru.dublgis.dgismobile"
        private val PACKAGE_PREVIEW: String = "ru.dublgis.dgismobile4preview"
        private val SERVICE_CLASS: String = "ru.dublgis.car.NavigationService"
        private val SERVICE_DESCRIPTOR: String = "ru.dublgis.car.INavigationService"
        private val CALLBACK_DESCRIPTOR: String = "ru.dublgis.car.ICarServiceCallback"
        private const val TRANSACTION_REGISTER_CALLBACK: Int = 1
        private const val TRANSACTION_GET_IS_NAVIGATING: Int = 9
        private const val CALLBACK_SCREEN_PUSH: Int = 1
        private const val CALLBACK_SCREEN_INVALIDATE: Int = 2
        private const val CALLBACK_START_NAVIGATION: Int = 7
        private const val CALLBACK_STOP_NAVIGATION: Int = 8
        private const val CALLBACK_GET_DENSITY: Int = 17
        private const val CALLBACK_UPDATE_TRIP: Int = 16
        private const val INTERFACE_TRANSACTION: Int = 0x5f4e5446
        private const val RECONNECT_MS: Long = 5000L
        private const val QUIET_LOG_MS: Long = 15000L

        private fun isInstalled(pm: PackageManager, packageName: String?): Boolean {
            try {
                pm.getPackageInfo(packageName!!, 0)
                return true
            } catch (ignored: Exception) {
                return false
            }
        }

        private fun first(vararg values: String?): String {
            for (value: String? in values) {
                val text = DgisTripClient.clean(value)
                if (!TextUtils.isEmpty(text)) return text
            }
            return ""
        }

        private fun shortText(value: String?): String {
            val text = DgisTripClient.clean(value)
            return if (text.length <= 160) text else text.substring(0, 157) + "..."
        }

        private fun destinationPoint(root: JSONObject?): DoubleArray? {
            val point: kia.app.navigation.capture.DgisTripClient.Point =
                kia.app.navigation.capture.DgisTripClient.Point()
            DgisTripClient.scanDestination(root, "", point)
            return if (point.valid()) doubleArrayOf(point.lat, point.lon) else null
        }

        private fun scanDestination(
            value: Any?,
            path: String?,
            out: kia.app.navigation.capture.DgisTripClient.Point,
        ) {
            if (value == null || out.valid()) return
            if (value is JSONObject) {
                val `object`: JSONObject = value as JSONObject
                val keys: Iterator<String?>? = `object`.keys()
                while (keys!!.hasNext() && !out.valid()) {
                    val key = keys.next()
                    DgisTripClient.scanDestination(
                        `object`.opt(key),
                        path.toString() + " " + key,
                        out,
                    )
                }
                return
            }
            if (value is JSONArray) {
                val array: JSONArray? = value as JSONArray?
                var i: Int = 0
                while (i < array!!.length() && !out.valid()) {
                    DgisTripClient.scanDestination(array.opt(i), path, out)
                    i++
                }
                return
            }
            val lower = DgisTripClient.clean(path).lowercase(Locale.US)
            if (
                !(lower.contains("destination") ||
                    lower.contains("finish") ||
                    lower.contains("target") ||
                    lower.contains("end"))
            )
                return
            val number: Double = DgisTripClient.doubleValue(value)
            if (java.lang.Double.isNaN(number)) return
            if (lower.contains("lat")) out.lat = number
            if (lower.contains("lon") || lower.contains("lng")) out.lon = number
        }

        private fun doubleValue(value: Any?): Double {
            if (value is Number) return value.toDouble()
            try {
                return java.lang.Double.parseDouble(
                    DgisTripClient.clean(java.lang.String.valueOf(value)).replace(',', '.')
                )
            } catch (ignored: Exception) {
                return Double.NaN
            }
        }

        private fun clean(value: String?): String {
            if (value == null) return ""
            return value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
        }
    }
}
