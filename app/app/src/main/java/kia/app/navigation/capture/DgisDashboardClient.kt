package kia.app.navigation.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.text.TextUtils
import java.util.Locale
import kia.app.core.AppLog
import kia.app.core.settings.AppSettings
import kia.app.navigation.domain.NavigationFeature
import org.json.JSONArray
import org.json.JSONObject

class DgisDashboardClient(context: Context) : ServiceConnection {
    private val app: Context?
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var boundPackage: String? = ""
    private var lastJson: String = ""
    private var lastQuietLogAt: Long = 0
    private var lastOwnerHeartbeatAt: Long = 0
    private var lastDashboardRouteActive: Boolean = false
    private var running: Boolean = false
    private var bound: Boolean = false

    private val poll: Runnable =
        object : Runnable {
            override fun run() {
                if (!running) return
                if (!sourceEnabled()) {
                    stop()
                    return
                }
                readDashboard()
                handler.postDelayed(this, DgisDashboardClient.POLL_MS)
            }
        }

    init {
        this.app = context.getApplicationContext()
    }

    fun start() {
        if (!sourceEnabled()) {
            stop()
            return
        }
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
        lastJson = ""
        lastOwnerHeartbeatAt = 0L
        lastDashboardRouteActive = false
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        remote = service
        if (!sourceEnabled()) {
            stop()
            return
        }
        boundPackage = if (name == null) "" else name.getPackageName()
        AppLog.line(
            app,
            ("2GIS dashboard: connected " +
                boundPackage +
                " api=" +
                transactInt(DgisDashboardClient.TRANSACTION_GET_API_VERSION, 0) +
                " mapActive=" +
                transactBoolean(DgisDashboardClient.TRANSACTION_IS_MAP_ACTIVE, false)),
        )
        transactSetClient("kia.app")
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        remote = null
        AppLog.line(app, "2GIS dashboard: disconnected")
        if (running && sourceEnabled()) {
            bound = false
            handler.removeCallbacks(poll)
            handler.postDelayed({ this.bind() }, 2000L)
        } else {
            stop()
        }
    }

    private fun bind() {
        if (!running || bound) return
        if (!sourceEnabled()) {
            stop()
            return
        }
        val pkg = installedPackage()
        if (TextUtils.isEmpty(pkg)) {
            logQuiet("2GIS dashboard: package not installed")
            handler.postDelayed({ this.bind() }, 5000L)
            return
        }
        val intent: Intent = Intent(DgisDashboardClient.ACTION)
        intent.setPackage(pkg)
        try {
            bound = app!!.bindService(intent, this, Context.BIND_AUTO_CREATE)
            if (!bound) {
                logQuiet("2GIS dashboard: bind returned false for " + pkg)
                handler.postDelayed({ this.bind() }, 5000L)
            }
        } catch (e: Exception) {
            bound = false
            logQuiet("2GIS dashboard: bind failed " + e.javaClass.getSimpleName())
            handler.postDelayed({ this.bind() }, 5000L)
        }
    }

    private fun installedPackage(): String? {
        val pm: PackageManager? = app!!.getPackageManager()
        if (DgisDashboardClient.isInstalled(pm!!, DgisDashboardClient.PACKAGE))
            return DgisDashboardClient.PACKAGE
        if (DgisDashboardClient.isInstalled(pm, DgisDashboardClient.PACKAGE_PREVIEW))
            return DgisDashboardClient.PACKAGE_PREVIEW
        return ""
    }

    private fun readDashboard() {
        if (remote == null || !remote!!.isBinderAlive()) {
            remote = null
            bound = false
            bind()
            return
        }
        var json: String? = transactString(DgisDashboardClient.TRANSACTION_GET_JSON)
        if (json == null) return
        json = json.trim()
        if (json.equals(lastJson)) {
            if (!lastDashboardRouteActive) return
            val now: Long = SystemClock.elapsedRealtime()
            if (now - lastOwnerHeartbeatAt < DgisDashboardClient.OWNER_HEARTBEAT_MS) return
            lastOwnerHeartbeatAt = now
            if (NavigationFeature.get(app!!).touchDgisDashboardHeartbeat()) {
                handleJson(json, false)
            }
            return
        }
        lastJson = json
        lastOwnerHeartbeatAt = SystemClock.elapsedRealtime()
        handleJson(json, true)
    }

    private fun sourceEnabled(): Boolean {
        return NavigationSourceGate.dgisEnabled(
            AppSettings.navigationEnabled(app!!),
            AppSettings.dgisNavigationEnabled(app),
        )
    }

    private fun handleJson(json: String?, newSnapshot: Boolean) {
        try {
            val obj: JSONObject = JSONObject(json)
            val mode = obj.optString("activeNavigationMode", "")
            val maneuverDistance = obj.optString("maneuverDistance", "")
            val maneuverIcon = obj.optString("maneuverIcon", "")
            val maneuverDescription = obj.optString("maneuverDescription", "")
            val totalDistance = obj.optString("totalDistance", "")
            val remainingTime = obj.optString("remainingTime", "")
            val arrivalTime = obj.optString("arrivalTime", "")
            val speedLimit: Double = obj.optDouble("speedLimit", 0.0)
            val exceeded: Boolean = obj.optBoolean("exceedingMaxSpeedLimit", false)
            val destinationPoint = DgisDashboardClient.destinationPoint(obj)
            val cleanMode = DgisDashboardClient.clean(mode).lowercase(Locale.US)
            lastDashboardRouteActive =
                (!TextUtils.isEmpty(cleanMode) &&
                    !"unknown".equals(cleanMode) &&
                    !"freeroam".equals(cleanMode)) ||
                    !TextUtils.isEmpty(DgisDashboardClient.clean(maneuverDistance)) ||
                    !TextUtils.isEmpty(DgisDashboardClient.clean(totalDistance)) ||
                    !TextUtils.isEmpty(DgisDashboardClient.clean(remainingTime)) ||
                    !TextUtils.isEmpty(DgisDashboardClient.clean(arrivalTime))
            NavigationFeature.get(app!!)
                .handleDgisDashboard(
                    mode,
                    maneuverIcon,
                    maneuverDescription,
                    maneuverDistance,
                    totalDistance,
                    remainingTime,
                    arrivalTime,
                    DgisDashboardClient.dashboardSpeed(speedLimit),
                    exceeded,
                    if (destinationPoint == null) Double.NaN else destinationPoint.get(0),
                    if (destinationPoint == null) Double.NaN else destinationPoint.get(1),
                    json,
                    newSnapshot,
                )
            logDashboard(
                mode,
                maneuverIcon,
                maneuverDescription,
                maneuverDistance,
                totalDistance,
                remainingTime,
                arrivalTime,
                speedLimit,
            )
        } catch (e: Exception) {
            lastDashboardRouteActive = false
            AppLog.line(
                app,
                ("2GIS dashboard JSON failed: " +
                    e.javaClass.getSimpleName() +
                    " " +
                    DgisDashboardClient.shortText(json)),
            )
        }
    }

    private fun logDashboard(
        mode: String?,
        icon: String?,
        description: String?,
        maneuverDistance: String?,
        totalDistance: String?,
        remainingTime: String?,
        arrivalTime: String?,
        speedLimit: Double,
    ) {
        val text: String =
            ("2GIS dashboard: mode=" +
                DgisDashboardClient.clean(mode) +
                " icon=" +
                DgisDashboardClient.clean(icon) +
                " desc=" +
                DgisDashboardClient.clean(description) +
                " manDist=" +
                DgisDashboardClient.clean(maneuverDistance) +
                " total=" +
                DgisDashboardClient.clean(totalDistance) +
                " time=" +
                DgisDashboardClient.clean(remainingTime) +
                " arrival=" +
                DgisDashboardClient.clean(arrivalTime) +
                " speedLimit=" +
                speedLimit)
        if (
            DgisDashboardClient.hasUsefulDashboardData(
                mode,
                icon,
                description,
                maneuverDistance,
                totalDistance,
                remainingTime,
                arrivalTime,
                speedLimit,
            )
        ) {
            AppLog.line(app, text)
        } else {
            logQuiet(text)
        }
    }

    private fun transactString(code: Int): String? {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(DgisDashboardClient.DESCRIPTOR)
            if (!remote!!.transact(code, data, reply, 0)) return null
            reply!!.readException()
            return reply.readString()
        } catch (e: Exception) {
            logQuiet("2GIS dashboard transact " + code + " failed " + e.javaClass.getSimpleName())
            return null
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
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

    private fun transactInt(code: Int, fallback: Int): Int {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(DgisDashboardClient.DESCRIPTOR)
            if (!remote!!.transact(code, data, reply, 0)) return fallback
            reply!!.readException()
            return reply.readInt()
        } catch (ignored: Exception) {
            return fallback
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun transactBoolean(code: Int, fallback: Boolean): Boolean {
        return transactInt(code, if (fallback) 1 else 0) != 0
    }

    private fun transactSetClient(client: String?) {
        val data: Parcel? = Parcel.obtain()
        val reply: Parcel? = Parcel.obtain()
        try {
            data!!.writeInterfaceToken(DgisDashboardClient.DESCRIPTOR)
            data.writeString(client)
            if (remote!!.transact(DgisDashboardClient.TRANSACTION_SET_CLIENT, data, reply, 0)) {
                reply!!.readException()
                AppLog.line(app, "2GIS dashboard: setClient=" + (reply.readInt() != 0))
            }
        } catch (e: Exception) {
            AppLog.line(app, "2GIS dashboard setClient failed " + e.javaClass.getSimpleName())
        } finally {
            reply!!.recycle()
            data!!.recycle()
        }
    }

    private fun logQuiet(value: String?) {
        val now: Long = System.currentTimeMillis()
        if (now - lastQuietLogAt < DgisDashboardClient.QUIET_LOG_MS) return
        lastQuietLogAt = now
        AppLog.line(app, value)
    }

    companion object {
        private val ACTION: String = "ru.dublgis.api.ACTION_BIND_DASHBOARD_INFORMATION_SERVICE"
        private val PACKAGE: String = "ru.dublgis.dgismobile"
        private val PACKAGE_PREVIEW: String = "ru.dublgis.dgismobile4preview"
        private val DESCRIPTOR: String = "ru.dublgis.api.IDashboardInformationService"
        private const val TRANSACTION_GET_JSON: Int = 1
        private const val TRANSACTION_GET_API_VERSION: Int = 4
        private const val TRANSACTION_SET_CLIENT: Int = 5
        private const val TRANSACTION_IS_MAP_ACTIVE: Int = 6
        private const val POLL_MS: Long = 1000L
        private const val QUIET_LOG_MS: Long = 15000L
        private const val OWNER_HEARTBEAT_MS: Long = 5000L

        private fun isInstalled(pm: PackageManager, packageName: String?): Boolean {
            try {
                pm.getPackageInfo(packageName!!, 0)
                return true
            } catch (ignored: Exception) {
                return false
            }
        }

        private fun hasUsefulDashboardData(
            mode: String?,
            icon: String?,
            description: String?,
            maneuverDistance: String?,
            totalDistance: String?,
            remainingTime: String?,
            arrivalTime: String?,
            speedLimit: Double,
        ): Boolean {
            return !TextUtils.isEmpty(DgisDashboardClient.clean(mode)) ||
                !TextUtils.isEmpty(DgisDashboardClient.clean(icon)) ||
                !TextUtils.isEmpty(DgisDashboardClient.clean(description)) ||
                !TextUtils.isEmpty(DgisDashboardClient.clean(maneuverDistance)) ||
                !TextUtils.isEmpty(DgisDashboardClient.clean(totalDistance)) ||
                !TextUtils.isEmpty(DgisDashboardClient.clean(remainingTime)) ||
                !TextUtils.isEmpty(DgisDashboardClient.clean(arrivalTime)) ||
                speedLimit > 0.0
        }

        private fun destinationPoint(root: JSONObject?): DoubleArray? {
            val point: kia.app.navigation.capture.DgisDashboardClient.Point =
                kia.app.navigation.capture.DgisDashboardClient.Point()
            DgisDashboardClient.scanDestination(root, "", point)
            return if (point.valid()) doubleArrayOf(point.lat, point.lon) else null
        }

        private fun scanDestination(
            value: Any?,
            path: String?,
            out: kia.app.navigation.capture.DgisDashboardClient.Point,
        ) {
            if (value == null || out.valid()) return
            if (value is JSONObject) {
                val `object`: JSONObject = value as JSONObject
                val keys: Iterator<String?>? = `object`.keys()
                while (keys!!.hasNext() && !out.valid()) {
                    val key = keys.next()
                    DgisDashboardClient.scanDestination(
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
                    DgisDashboardClient.scanDestination(array.opt(i), path, out)
                    i++
                }
                return
            }
            val lower = DgisDashboardClient.clean(path).lowercase(Locale.US)
            if (
                !(lower.contains("destination") ||
                    lower.contains("finish") ||
                    lower.contains("target") ||
                    lower.contains("end"))
            )
                return
            val number: Double = DgisDashboardClient.doubleValue(value)
            if (java.lang.Double.isNaN(number)) return
            if (lower.contains("lat")) out.lat = number
            if (lower.contains("lon") || lower.contains("lng")) out.lon = number
        }

        private fun doubleValue(value: Any?): Double {
            if (value is Number) return value.toDouble()
            try {
                return java.lang.Double.parseDouble(
                    DgisDashboardClient.clean(java.lang.String.valueOf(value)).replace(',', '.')
                )
            } catch (ignored: Exception) {
                return Double.NaN
            }
        }

        private fun dashboardSpeed(value: Double): String {
            if (value <= 0.0 || java.lang.Double.isNaN(value) || java.lang.Double.isInfinite(value))
                return ""
            val rounded: Double = Math.rint(value)
            val kmh: Double =
                if (value < 30.0 && Math.abs(value - rounded) > 0.05) value * 3.6 else value
            if (kmh <= 0.0 || kmh > 160.0) return ""
            return java.lang.String.valueOf(Math.round(kmh))
        }

        private fun shortText(value: String?): String {
            val text = DgisDashboardClient.clean(value)
            return if (text.length <= 160) text else text.substring(0, 157) + "..."
        }

        private fun clean(value: String?): String {
            if (value == null) return ""
            return value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
        }
    }
}
