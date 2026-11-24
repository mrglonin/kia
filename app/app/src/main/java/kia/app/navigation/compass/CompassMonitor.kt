package kia.app.navigation.compass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.NavigationState
import kia.app.core.settings.AppSettings
import kia.app.navigation.cluster.NavigationClusterSender
import kia.app.navigation.domain.NavigationFeature
import kia.app.protocol.adapter.AdapterGateway
import kia.app.protocol.adapter.AdapterTxOutcome

class CompassMonitor(context: Context) : LocationListener, SensorEventListener {
    private val app: Context?
    private val sender: NavigationClusterSender
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var headingSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val rotationMatrix: FloatArray = FloatArray(9)
    private val displayMatrix: FloatArray = FloatArray(9)
    private val orientation: FloatArray = FloatArray(3)
    private var displayedStep: Int = -1
    private var targetStep: Int = -1
    private var lastSensorHeadingAt: Long = 0
    private var lastRawSensorEventAt: Long = 0
    private var lastSensorPublishAt: Long = 0
    private var sensorRegisteredAt: Long = 0
    private var lastSensorRecoveryAt: Long = 0
    private var lastGpsBearingAt: Long = 0
    private var lastRegisterAt: Long = 0
    private var startedAt: Long = 0
    private var lastUsbEpoch: Long = -1L
    private var lastLocationElapsedNanos: Long = 0
    private var lastGpsBearingElapsedNanos: Long = 0
    private var lastGpsSpeedElapsedNanos: Long = 0
    private var lastStationaryLocationElapsedNanos: Long = 0
    private var lastAcceptedGpsSpeed: Float = Float.NaN
    private var lastSuccessfulCompassTxAt: Long = 0
    private var lastCompassTxAttemptAt: Long = 0
    private var lastTransmittedStep: Int = -1
    private var lastAttemptedStep: Int = -1
    private var lastHeading: Float = Float.NaN
    private var smoothedSensorHeading: Float = Float.NaN
    private var lastPublishedSensorHeading: Float = Float.NaN
    private var sensorAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var running: Boolean = false
    private var registered: Boolean = false
    private var sensorRegistered: Boolean = false
    private var animationRunning: Boolean = false
    private var lastCompassSendAllowed: Boolean = false
    private var kiaNaviFormat: Boolean = false
    private var preferGeomagneticSensor: Boolean = false
    private var lastLocation: Location? = null

    private val animate: Runnable =
        object : Runnable {
            override fun run() {
                animationRunning = false
                refreshCompassFormat()
                if (!running || targetStep < 0 || !canSendCompass()) return
                if (displayedStep < 0) {
                    sendStep(targetStep)
                    return
                }
                if (displayedStep == targetStep) return
                if (!sendStep(CompassTxPolicy.nextStep(displayedStep, targetStep, kiaNaviFormat)))
                    return
                if (displayedStep != targetStep) {
                    animationRunning = true
                    handler.postDelayed(this, CompassMonitor.ANIMATION_STEP_MS)
                }
            }
        }

    private val watchdog: Runnable =
        object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    val now: Long = SystemClock.elapsedRealtime()
                    if (!locationNeeded()) {
                        unregisterLocation()
                        releaseWakeLock()
                        updateHeadingSensorRegistration()
                    } else {
                        acquireWakeLock()
                        updateHeadingSensorRegistration()
                        if (!registered || now - lastRegisterAt > CompassMonitor.REREGISTER_MS) {
                            registerLocation(true)
                        }
                    }
                    recoverStaleHeadingSensor(now)
                    refreshCompassTxContext(now)
                } catch (e: RuntimeException) {
                    AppLog.line(
                        app,
                        "Compass: watchdog recovered from " + e.javaClass.getSimpleName(),
                    )
                } finally {
                    if (running) handler.postDelayed(this, CompassMonitor.WATCHDOG_MS)
                }
            }
        }

    init {
        this.app = context.getApplicationContext()
        this.sender = NavigationClusterSender(app)
    }

    fun start() {
        if (running) {
            if (locationNeeded()) {
                acquireWakeLock()
                registerLocation(false)
            } else {
                unregisterLocation()
                releaseWakeLock()
            }
            updateHeadingSensorRegistration()
            armWatchdog()
            return
        }
        locationManager = app!!.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
        if (locationManager == null && sensorManager == null) {
            AppLog.line(app, "Compass: location and sensor services unavailable")
            return
        }
        if (!hasLocationPermission()) {
            AppLog.line(app, "Compass: location permission not granted; sensor-only mode")
        }
        running = true
        startedAt = SystemClock.elapsedRealtime()
        refreshCompassFormat()
        val gateway: AdapterGateway? = AdapterGateway.get(app)
        lastUsbEpoch = gateway!!.connectionEpoch()
        lastCompassSendAllowed = false
        if (locationNeeded()) acquireWakeLock()
        registerLocation(true)
        updateHeadingSensorRegistration()
        armWatchdog()
        AppLog.line(app, "Compass: GPS + sensor heading monitor started")
    }

    fun stop() {
        if (!running && !registered) return
        unregisterLocation()
        running = false
        displayedStep = -1
        targetStep = -1
        startedAt = 0L
        lastUsbEpoch = -1L
        lastLocationElapsedNanos = 0L
        lastGpsBearingElapsedNanos = 0L
        lastGpsSpeedElapsedNanos = 0L
        lastStationaryLocationElapsedNanos = 0L
        lastAcceptedGpsSpeed = Float.NaN
        lastTransmittedStep = -1
        animationRunning = false
        lastCompassSendAllowed = false
        lastSensorHeadingAt = 0L
        lastRawSensorEventAt = 0L
        lastSensorPublishAt = 0L
        sensorRegisteredAt = 0L
        lastSensorRecoveryAt = 0L
        lastGpsBearingAt = 0L
        lastSuccessfulCompassTxAt = 0L
        lastCompassTxAttemptAt = 0L
        lastAttemptedStep = -1
        lastHeading = Float.NaN
        smoothedSensorHeading = Float.NaN
        lastPublishedSensorHeading = Float.NaN
        preferGeomagneticSensor = false
        lastLocation = null
        handler.removeCallbacks(animate)
        handler.removeCallbacks(watchdog)
        unregisterHeadingSensor()
        releaseWakeLock()
    }

    override fun onLocationChanged(location: Location) {
        if (!running) return
        acceptLocation(location)
    }

    private fun acceptLocation(location: Location?) {
        if (!CompassMonitor.freshLocation(location, SystemClock.elapsedRealtimeNanos())) return
        val fixElapsedNanos: Long = location!!.getElapsedRealtimeNanos()
        rememberStationaryLocation(location)
        val updateSpeed: Boolean =
            CompassLocationPolicy.newerSpeedSample(
                location.hasSpeed(),
                location.getSpeed(),
                fixElapsedNanos,
                lastGpsSpeedElapsedNanos,
                lastAcceptedGpsSpeed,
            )
        val updateBearing: Boolean =
            CompassMonitor.usableGpsBearing(location) &&
                CompassLocationPolicy.newerSample(fixElapsedNanos, lastGpsBearingElapsedNanos) &&
                fixElapsedNanos > lastStationaryLocationElapsedNanos
        if (updateSpeed) {
            lastGpsSpeedElapsedNanos = fixElapsedNanos
            lastAcceptedGpsSpeed = location.getSpeed()
        }
        if (updateBearing) lastGpsBearingElapsedNanos = fixElapsedNanos
        if (CompassLocationPolicy.newerSample(fixElapsedNanos, lastLocationElapsedNanos)) {
            lastLocationElapsedNanos = fixElapsedNanos
            lastLocation = Location(location)
            NavigationFeature.get(app!!).updateGpsLocation(location, updateSpeed, updateBearing)
        } else if (updateSpeed || updateBearing) {
            NavigationFeature.get(app!!).updateGpsMotion(location, updateSpeed, updateBearing)
        }
        // A newer position/speed-only provider must not hide a usable course from
        // another provider. Track course order separately, without rewinding navigation.
        if (!updateBearing || sensorHeadingFresh()) return
        lastGpsBearingAt = fixElapsedNanos / 1000000L
        updateTargetStep(normalize(location.getBearing()))
    }

    private fun rememberStationaryLocation(location: Location) {
        if (CompassLocationPolicy.stationarySpeed(location.hasSpeed(), location.getSpeed())) {
            lastStationaryLocationElapsedNanos =
                Math.max(lastStationaryLocationElapsedNanos, location.getElapsedRealtimeNanos())
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!running || event == null || event.sensor == null) return
        val type: Int = event.sensor.getType()
        if (
            type != Sensor.TYPE_ROTATION_VECTOR && type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        ) {
            return
        }
        val activeSensor: Sensor? = headingSensor
        if (activeSensor != null && activeSensor.getType() != type) return
        val now: Long = SystemClock.elapsedRealtime()
        lastRawSensorEventAt = now
        var heading: Float = headingFromRotationVector(event.values)
        heading = trueNorthHeading(heading)
        if (!CompassLocationPolicy.usableSensorHeading(heading, event.accuracy)) return
        sensorAccuracy =
            CompassLocationPolicy.effectiveSensorAccuracy(sensorAccuracy, event.accuracy)
        val blendWeight: Float =
            if (sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) 0.18f else 0.32f
        smoothedSensorHeading =
            if (java.lang.Float.isNaN(smoothedSensorHeading)) heading
            else blendDegrees(smoothedSensorHeading, heading, blendWeight)
        lastSensorHeadingAt = now
        val delta: Float =
            if (java.lang.Float.isNaN(lastPublishedSensorHeading)) 999f
            else
                Math.abs(
                    CompassMonitor.signedDelta(smoothedSensorHeading, lastPublishedSensorHeading)
                )
        if (now - lastSensorPublishAt >= CompassMonitor.SENSOR_PUBLISH_MS || delta >= 1.2f) {
            lastSensorPublishAt = now
            lastPublishedSensorHeading = smoothedSensorHeading
            NavigationFeature.get(app!!)
                .updateDeviceHeading(
                    smoothedSensorHeading,
                    sensorAccuracyDegrees(),
                    event.sensor.getName(),
                )
        }
        if (
            CompassLocationPolicy.sensorMayDriveCluster(
                sensorAccuracy,
                now,
                lastGpsBearingAt,
                CompassMonitor.GPS_BEARING_PRIORITY_MS,
            )
        ) {
            updateTargetStep(smoothedSensorHeading)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (!running) return
        val activeSensor: Sensor? = headingSensor
        if (sensor != null && activeSensor != null && sensor.getType() != activeSensor.getType())
            return
        sensorAccuracy = accuracy
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        if (running) registerLocation(true)
    }

    override fun onProviderDisabled(provider: String) {}

    private fun registerLocation(force: Boolean) {
        if (!running || locationManager == null || !hasLocationPermission()) return
        if (!locationNeeded()) {
            unregisterLocation()
            return
        }
        val now: Long = SystemClock.elapsedRealtime()
        if (!force && registered && now - lastRegisterAt < CompassMonitor.REREGISTER_MS) return
        unregisterLocation()
        try {
            var any: Boolean = requestProvider(LocationManager.GPS_PROVIDER)
            any = any or requestProvider(CompassMonitor.FUSED_PROVIDER)
            any = any or requestProvider(LocationManager.NETWORK_PROVIDER)
            any = any or requestProvider(LocationManager.PASSIVE_PROVIDER)
            registered = any
            lastRegisterAt = now
            acceptLastKnownLocations()
        } catch (e: Exception) {
            registered = false
            AppLog.line(app, "Compass: GPS start failed " + e.javaClass.getSimpleName())
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestProvider(provider: String?): Boolean {
        if (!hasLocationPermission()) return false
        try {
            if (locationManager!!.getProvider(provider!!) == null) return false
            if (!locationManager!!.isProviderEnabled(provider)) return false
            locationManager!!.requestLocationUpdates(
                provider,
                CompassMonitor.MIN_TIME_MS,
                CompassMonitor.MIN_DISTANCE_M,
                this,
                Looper.getMainLooper(),
            )
            return true
        } catch (ignored: Exception) {
            return false
        }
    }

    private fun updateHeadingSensorRegistration() {
        if (!running || !headingSensorNeeded()) {
            unregisterHeadingSensor()
            return
        }
        registerHeadingSensor(false)
    }

    private fun recoverStaleHeadingSensor(now: Long) {
        if (!headingSensorNeeded()) return
        if (
            !CompassLocationPolicy.sensorStreamNeedsRecovery(
                sensorRegistered,
                now,
                sensorRegisteredAt,
                lastSensorHeadingAt,
                CompassMonitor.RAW_SENSOR_STALE_MS,
                lastSensorRecoveryAt,
                CompassMonitor.SENSOR_RECOVERY_COOLDOWN_MS,
            )
        ) {
            return
        }
        val staleSensor: Sensor? = headingSensor
        preferGeomagneticSensor =
            staleSensor != null && staleSensor.getType() == Sensor.TYPE_ROTATION_VECTOR
        lastSensorRecoveryAt = now
        val freshnessAnchor: Long =
            if (lastSensorHeadingAt > 0L) lastSensorHeadingAt else sensorRegisteredAt
        val rawAge: Long =
            if (lastRawSensorEventAt > 0L) Math.max(0L, now - lastRawSensorEventAt) else -1L
        AppLog.line(
            app,
            ("Compass: stale heading sensor " +
                (if (staleSensor == null) "unknown" else staleSensor.getName()) +
                " usableAge=" +
                Math.max(0L, now - freshnessAnchor) +
                "ms" +
                " rawAge=" +
                rawAge +
                "ms" +
                "; trying " +
                (if (preferGeomagneticSensor) "geomagnetic" else "rotation") +
                " fallback"),
        )
        registerHeadingSensor(true)
    }

    private fun headingSensorNeeded(): Boolean {
        return compassDirectionNeeded() ||
            NavigationFeature.get(app!!).finishDirectionHeadingNeeded()
    }

    private fun registerHeadingSensor(force: Boolean) {
        if (!running || sensorManager == null) return
        if (!force && sensorRegistered) return
        unregisterHeadingSensor()
        sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        val preferredType: Int =
            if (preferGeomagneticSensor) Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
            else Sensor.TYPE_ROTATION_VECTOR
        val fallbackType: Int =
            if (preferGeomagneticSensor) Sensor.TYPE_ROTATION_VECTOR
            else Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        headingSensor = sensorManager!!.getDefaultSensor(preferredType)
        if (headingSensor == null) headingSensor = sensorManager!!.getDefaultSensor(fallbackType)
        if (headingSensor == null) {
            AppLog.line(app, "Compass: rotation vector sensor unavailable")
            return
        }
        try {
            sensorRegistered =
                sensorManager!!.registerListener(
                    this,
                    headingSensor,
                    SensorManager.SENSOR_DELAY_GAME,
                    handler,
                )
            if (sensorRegistered) {
                sensorRegisteredAt = SystemClock.elapsedRealtime()
                lastRawSensorEventAt = 0L
                AppLog.line(app, "Compass: heading sensor " + headingSensor!!.getName())
            }
        } catch (e: Exception) {
            sensorRegistered = false
            AppLog.line(app, "Compass: heading sensor failed " + e.javaClass.getSimpleName())
        }
    }

    private fun unregisterHeadingSensor() {
        if (sensorManager != null && sensorRegistered) {
            try {
                sensorManager!!.unregisterListener(this)
            } catch (ignored: Exception) {}
        }
        sensorRegistered = false
        sensorRegisteredAt = 0L
        lastRawSensorEventAt = 0L
        headingSensor = null
    }

    private fun acceptLastKnownLocations() {
        val nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos()
        var latestLocation: Location? = null
        var latestSpeed: Location? = null
        var latestBearing: Location? = null
        val candidates: kotlin.Array<Location?> =
            arrayOf<Location?>(
                lastKnown(LocationManager.GPS_PROVIDER),
                lastKnown(CompassMonitor.FUSED_PROVIDER),
                lastKnown(LocationManager.PASSIVE_PROVIDER),
                lastKnown(LocationManager.NETWORK_PROVIDER),
            )
        for (candidate: Location? in candidates) {
            if (!CompassMonitor.freshLocation(candidate, nowElapsedNanos)) continue
            // Record every fresh stop before accepting any cached moving course.
            rememberStationaryLocation(candidate!!)
            if (CompassMonitor.newerLocation(candidate, latestLocation)) latestLocation = candidate
            if (
                CompassMonitor.knownGpsSpeed(candidate) &&
                    CompassMonitor.newerLocation(candidate, latestSpeed)
            )
                latestSpeed = candidate
            if (
                CompassMonitor.usableGpsBearing(candidate) &&
                    CompassMonitor.newerLocation(candidate, latestBearing)
            ) {
                latestBearing = candidate
            }
        }
        acceptLocation(latestLocation)
        if (latestSpeed !== latestLocation) acceptLocation(latestSpeed)
        if (latestBearing !== latestLocation && latestBearing !== latestSpeed)
            acceptLocation(latestBearing)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(provider: String?): Location? {
        if (!hasLocationPermission()) return null
        try {
            return if (locationManager == null) null
            else locationManager!!.getLastKnownLocation(provider!!)
        } catch (ignored: Exception) {
            return null
        }
    }

    private fun unregisterLocation() {
        if (locationManager == null || !registered) return
        try {
            locationManager!!.removeUpdates(this)
        } catch (ignored: Exception) {}
        registered = false
    }

    private fun hasLocationPermission(): Boolean {
        return app!!.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun headingFromRotationVector(values: FloatArray?): Float {
        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
            if (!remapForDisplay(rotationMatrix, displayMatrix)) return Float.NaN
            SensorManager.getOrientation(displayMatrix, orientation)
            return normalize(Math.toDegrees(orientation.get(0).toDouble()).toFloat())
        } catch (ignored: Exception) {
            return Float.NaN
        }
    }

    private fun remapForDisplay(`in`: FloatArray, out: FloatArray?): Boolean {
        val rotation: Int = displayRotation()
        when (rotation) {
            Surface.ROTATION_90 ->
                return SensorManager.remapCoordinateSystem(
                    `in`,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    out,
                )
            Surface.ROTATION_180 ->
                return SensorManager.remapCoordinateSystem(
                    `in`,
                    SensorManager.AXIS_MINUS_X,
                    SensorManager.AXIS_MINUS_Y,
                    out,
                )
            Surface.ROTATION_270 ->
                return SensorManager.remapCoordinateSystem(
                    `in`,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X,
                    out,
                )
            Surface.ROTATION_0 -> {
                System.arraycopy(`in`, 0, out, 0, `in`.size)
                return true
            }
            else -> {
                System.arraycopy(`in`, 0, out, 0, `in`.size)
                return true
            }
        }
    }

    private fun displayRotation(): Int {
        try {
            val wm: WindowManager? =
                app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
            return if (wm == null) Surface.ROTATION_0 else wm.getDefaultDisplay().getRotation()
        } catch (ignored: Exception) {
            return Surface.ROTATION_0
        }
    }

    private fun trueNorthHeading(magneticHeading: Float): Float {
        val location: Location? = lastLocation
        if (location == null) return normalize(magneticHeading)
        try {
            val field: GeomagneticField =
                GeomagneticField(
                    location.getLatitude().toFloat(),
                    location.getLongitude().toFloat(),
                    location.getAltitude().toFloat(),
                    if (location.getTime() > 0L) location.getTime() else System.currentTimeMillis(),
                )
            return normalize(magneticHeading + field.getDeclination())
        } catch (ignored: Exception) {
            return normalize(magneticHeading)
        }
    }

    private fun sensorHeadingFresh(): Boolean {
        return sensorAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
            lastSensorHeadingAt > 0L &&
            SystemClock.elapsedRealtime() - lastSensorHeadingAt <= CompassMonitor.SENSOR_STALE_MS
    }

    private fun sensorAccuracyDegrees(): Float {
        if (sensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH) return 6f
        if (sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) return 18f
        if (sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) return 35f
        return 60f
    }

    private fun updateTargetStep(heading: Float) {
        lastHeading = heading
        refreshCompassFormat()
        val step: Int = CompassTxPolicy.headingStep(heading, kiaNaviFormat)
        val sameTarget: Boolean = step == targetStep
        targetStep = step
        restoreDisplayedStepFromStore()
        if (!canSendCompass()) return
        if (sameTarget && displayedStep >= 0) {
            sendCurrentStepIfNeeded()
            startAnimationIfNeeded()
            return
        }
        if (displayedStep < 0) {
            sendStep(step)
            return
        }
        if (displayedStep == targetStep) {
            sendCurrentStepIfNeeded()
            return
        }
        sendCurrentStepIfNeeded()
        startAnimationIfNeeded()
    }

    private fun normalize(value: Float): Float {
        val out: Float = value % 360f
        return if (out < 0f) out + 360f else out
    }

    private fun blendDegrees(from: Float, to: Float, toWeight: Float): Float {
        return normalize(
            from + CompassMonitor.signedDelta(to, from) * Math.max(0f, Math.min(1f, toWeight))
        )
    }

    private fun restoreDisplayedStepFromStore() {
        if (displayedStep >= 0) return
        val navigation: NavigationState? = StateStore.navigation()
        if (navigation == null) return
        val stored: Int = CompassTxPolicy.storedStep(navigation.clusterTx, kiaNaviFormat)
        if (stored < 0) return
        displayedStep = stored
        AppLog.line(
            app,
            ("Compass: restored step=" +
                displayedStep +
                (if (targetStep >= 0) " target=" + targetStep else "")),
        )
    }

    private fun refreshCompassFormat() {
        val selected: Boolean = AppSettings.compassKiaNaviFormat(app!!)
        if (selected == kiaNaviFormat) return
        kiaNaviFormat = selected
        displayedStep = -1
        targetStep =
            if (java.lang.Float.isNaN(lastHeading)) -1
            else CompassTxPolicy.headingStep(lastHeading, selected)
        lastTransmittedStep = -1
        lastAttemptedStep = -1
        lastSuccessfulCompassTxAt = 0L
        lastCompassTxAttemptAt = 0L
        animationRunning = false
        handler.removeCallbacks(animate)
    }

    private fun sendStep(step: Int): Boolean {
        if (!canSendCompass()) return false
        val gateway: AdapterGateway? = AdapterGateway.get(app!!)
        if (!gateway!!.usbReady()) return false
        val now: Long = SystemClock.elapsedRealtime()
        if (
            !CompassTxPolicy.retryAllowed(
                step,
                lastAttemptedStep,
                now,
                lastCompassTxAttemptAt,
                CompassMonitor.COMPASS_TX_RETRY_MS,
            )
        ) {
            return false
        }
        lastAttemptedStep = step
        lastCompassTxAttemptAt = now
        val outcome: AdapterTxOutcome? = sender.sendCompassStep(step, kiaNaviFormat)
        if (outcome == AdapterTxOutcome.WRITTEN) {
            displayedStep = step
            lastTransmittedStep = step
            lastSuccessfulCompassTxAt = now
            lastCompassSendAllowed = true
            lastUsbEpoch = gateway.connectionEpoch()
            return true
        } else {
            lastTransmittedStep = -1
            return false
        }
    }

    private fun sendCurrentStepIfNeeded(force: Boolean = false) {
        if (displayedStep < 0 || (!force && displayedStep == lastTransmittedStep)) return
        sendStep(displayedStep)
    }

    private fun startAnimationIfNeeded() {
        if (
            targetStep < 0 ||
                displayedStep < 0 ||
                displayedStep == targetStep ||
                animationRunning ||
                !canSendCompass()
        ) {
            return
        }
        if (!AdapterGateway.get(app!!).usbReady()) return
        animationRunning = true
        handler.post(animate)
    }

    private fun refreshCompassTxContext(now: Long) {
        refreshCompassFormat()
        val gateway: AdapterGateway? = AdapterGateway.get(app!!)
        val sendAllowed: Boolean = canSendCompass()
        val usbReady: Boolean = gateway!!.usbReady()
        val usbEpoch: Long = gateway.connectionEpoch()
        val reassert: Boolean =
            CompassTxPolicy.shouldReassert(
                sendAllowed,
                lastCompassSendAllowed,
                usbReady,
                usbEpoch,
                lastUsbEpoch,
            )
        val keepAlive: Boolean =
            CompassTxPolicy.shouldKeepAlive(
                sendAllowed,
                usbReady,
                now,
                lastSuccessfulCompassTxAt,
                CompassMonitor.COMPASS_TX_KEEPALIVE_MS,
            )
        if (!sendAllowed && lastCompassSendAllowed) {
            lastTransmittedStep = -1
            animationRunning = false
            handler.removeCallbacks(animate)
        }
        lastCompassSendAllowed = sendAllowed
        lastUsbEpoch = usbEpoch
        val retry: Boolean = sendAllowed && usbReady && lastTransmittedStep < 0
        if (!reassert && !keepAlive && !retry) return
        if (reassert) lastTransmittedStep = -1
        restoreDisplayedStepFromStore()
        if (displayedStep < 0 && targetStep >= 0) displayedStep = targetStep
        sendCurrentStepIfNeeded(true)
        startAnimationIfNeeded()
    }

    private fun armWatchdog() {
        handler.removeCallbacks(watchdog)
        if (running) handler.postDelayed(watchdog, CompassMonitor.WATCHDOG_MS)
    }

    private fun canSendCompass(): Boolean {
        if (!running) return false
        val now: Long = SystemClock.elapsedRealtime()
        if (startedAt > 0L && now - startedAt < CompassMonitor.STARTUP_NAV_GRACE_MS) return false
        return compassDirectionNeeded()
    }

    private fun locationNeeded(): Boolean {
        return speedFallbackNeeded() || compassDirectionNeeded()
    }

    private fun speedFallbackNeeded(): Boolean {
        return AppSettings.navigationEnabled(app!!)
    }

    private fun compassDirectionNeeded(): Boolean {
        val navigation: NavigationFeature? = NavigationFeature.get(app!!)
        return (!navigation!!.activeForCompassSuppression() &&
            !navigation.finishCompassSuppressed()) || AppSettings.compassForceEnabled(app)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm: PowerManager? =
                    app!!.getSystemService(Context.POWER_SERVICE) as PowerManager?
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kia:Compass")
                    wakeLock!!.setReferenceCounted(false)
                }
            }
            if (wakeLock != null && !wakeLock!!.isHeld())
                wakeLock!!.acquire(CompassMonitor.WAKE_LOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            AppLog.line(app, "Compass: wakelock failed " + e.javaClass.getSimpleName())
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock!!.isHeld()) wakeLock!!.release()
        } catch (ignored: Exception) {}
    }

    companion object {
        private const val MIN_TIME_MS: Long = 500L
        private const val MIN_DISTANCE_M: Float = 0f
        private const val ANIMATION_STEP_MS: Long = 120L
        private const val WATCHDOG_MS: Long = 1000L
        private const val REREGISTER_MS: Long = 15000L
        private const val STARTUP_NAV_GRACE_MS: Long = 3000L
        private const val STALE_LOCATION_MS: Long = 2500L
        private val GPS_BEARING_PRIORITY_MS: Long = CompassMonitor.STALE_LOCATION_MS
        private const val SENSOR_STALE_MS: Long = 1500L
        private const val RAW_SENSOR_STALE_MS: Long = 3000L
        private const val SENSOR_RECOVERY_COOLDOWN_MS: Long = 15000L
        private const val SENSOR_PUBLISH_MS: Long = 120L
        private const val COMPASS_TX_KEEPALIVE_MS: Long = 5000L
        private const val COMPASS_TX_RETRY_MS: Long = 1000L
        private const val WAKE_LOCK_TIMEOUT_MS: Long = 15000L
        private val FUSED_PROVIDER: String = "fused"

        private fun freshLocation(location: Location?, nowElapsedNanos: Long): Boolean {
            return location != null &&
                CompassLocationPolicy.freshSample(
                    nowElapsedNanos,
                    location.getElapsedRealtimeNanos(),
                    CompassMonitor.STALE_LOCATION_MS,
                )
        }

        private fun usableGpsBearing(location: Location): Boolean {
            return CompassLocationPolicy.usableGpsBearing(
                location.hasBearing(),
                location.getBearing(),
                location.hasSpeed(),
                if (location.hasSpeed()) location.getSpeed() else Float.NaN,
            )
        }

        private fun knownGpsSpeed(location: Location): Boolean {
            return location.hasSpeed() &&
                java.lang.Float.isFinite(location.getSpeed()) &&
                location.getSpeed() >= 0f
        }

        private fun newerLocation(candidate: Location, current: Location?): Boolean {
            if (current == null) return true
            val candidateTime: Long = candidate.getElapsedRealtimeNanos()
            val currentTime: Long = current.getElapsedRealtimeNanos()
            if (candidateTime != currentTime) return candidateTime > currentTime
            val candidateStopped: Boolean =
                CompassLocationPolicy.stationarySpeed(candidate.hasSpeed(), candidate.getSpeed())
            val currentStopped: Boolean =
                CompassLocationPolicy.stationarySpeed(current.hasSpeed(), current.getSpeed())
            if (candidateStopped != currentStopped) return candidateStopped
            if (
                CompassMonitor.usableGpsBearing(candidate) !=
                    CompassMonitor.usableGpsBearing(current)
            )
                return CompassMonitor.usableGpsBearing(candidate)
            return CompassMonitor.knownGpsSpeed(candidate) && !CompassMonitor.knownGpsSpeed(current)
        }

        private fun signedDelta(to: Float, from: Float): Float {
            var out: Float = (to - from) % 360f
            if (out > 180f) out -= 360f
            if (out < -180f) out += 360f
            return out
        }
    }
}
