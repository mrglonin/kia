package kia.app.navigation.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import java.util.ArrayList
import java.util.Collections
import java.util.Locale
import kia.app.core.AppLog
import kia.app.core.settings.AppSettings
import kia.app.entry.AppService
import kia.app.navigation.domain.NavigationFeature

class NavBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.getAction()
        if (!YandexCoreBridgeContract.ACTION_V2_SNAPSHOT.equals(action)) {
            AppLog.navigation(
                context,
                "Yandex Core Bridge broadcast rejected: unexpected action=" + action,
            )
            return
        }
        val extras: Bundle? = intent.getExtras()
        val invalidReason: String = NavBroadcastReceiver.invalidCoreBridgeEnvelope(extras)
        if (!invalidReason.isEmpty()) {
            AppLog.navigation(context, "Yandex Core Bridge broadcast rejected: " + invalidReason)
            return
        }
        if (
            !NavBroadcastReceiver.sourceIngressAllowed(
                context,
                NavigationSourcePolicy.SOURCE_YANDEX,
            )
        ) {
            AppLog.navigation(
                context,
                "Yandex Core Bridge broadcast ignored: navigation/source disabled",
            )
            return
        }
        NavBroadcastReceiver.prepareService(context)
        if (NavBroadcastReceiver.duplicate(intent)) return
        NavBroadcastReceiver.lastCoreBridgeSnapshotAt = SystemClock.elapsedRealtime()
        YandexCoreBridgeClient.get(context).acceptBroadcastSnapshot(extras)
    }

    companion object {
        private const val DUPLICATE_WINDOW_MS: Long = 120L
        private const val SERVICE_START_MIN_INTERVAL_MS: Long = 5000L
        private const val NAV_WAKE_MS: Long = 12000L
        private const val CORE_BRIDGE_FALLBACK_SUPPRESS_MS: Long = 5000L
        private const val CORE_BRIDGE_MAX_ENVELOPE_AGE_MS: Long = 2500L
        private const val SUPPRESSED_LOG_MS: Long = 10000L

        private var lastSignature: String = ""
        private var lastSignatureAt: Long = 0
        private var lastServiceStartAt: Long = 0
        private var lastCoreBridgeSnapshotAt: Long = 0
        private var lastSuppressedLogAt: Long = 0
        private var wakeLock: PowerManager.WakeLock? = null

        @JvmStatic
        fun receiveLegacy(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            val action = intent.getAction()
            if (MviteNavigationMessage.ACTION.equals(action)) {
                if (
                    !NavBroadcastReceiver.sourceIngressAllowed(
                        context,
                        NavigationSourcePolicy.SOURCE_MVITE,
                    )
                )
                    return
                val payload = intent.getStringExtra("payload")
                val message: MviteNavigationMessage? = MviteNavigationMessage.parse(payload)
                if (message == null || duplicate(action + '|' + payload)) return
                NavBroadcastReceiver.prepareService(context)
                NavigationFeature.get(context).handleMvite(message)
                return
            }
            if (!NavBroadcastReceiver.knownLegacyAction(action)) {
                AppLog.navigation(context, "Navigation legacy broadcast rejected: action=" + action)
                return
            }
            if (
                !NavBroadcastReceiver.sourceIngressAllowed(
                    context,
                    NavigationSourcePolicy.SOURCE_YANDEX,
                )
            ) {
                AppLog.navigation(
                    context,
                    "Navigation legacy broadcast ignored by source gate: action=" + action,
                )
                return
            }
            NavBroadcastReceiver.prepareService(context)
            if (NavBroadcastReceiver.duplicate(intent)) return
            if (NavBroadcastReceiver.legacyFallbackSuppressed(context, action)) return
            val feature: NavigationFeature? = NavigationFeature.get(context)
            if (
                "com.yf.navinfo".equals(action) ||
                    "com.teyes.MapAssistantService".equals(action) ||
                    "android.action.MOBILE_NAVIGATION".equals(action)
            ) {
                feature!!.handleTeyes(intent)
                feature.resendForBackgroundDelivery(action)
                return
            }
            feature!!.handle(intent)
            feature.resendForBackgroundDelivery(action)
        }

        @JvmStatic
        fun invalidCoreBridgeEnvelope(extras: Bundle?): String {
            if (extras == null || extras.isEmpty()) return "empty envelope"
            val schema: Long = NavBroadcastReceiver.longValue(extras, "schema_version", -1L)
            if (schema != 2L) return "schema_version=" + schema
            val source = NavBroadcastReceiver.stringValue(extras, "source")
            if (!YandexCoreBridgeContract.SOURCE.equals(source)) return "source=" + source
            val seq: Long = NavBroadcastReceiver.longValue(extras, "seq", -1L)
            if (seq < 0L) return "missing seq"
            val timestamp: Long =
                NavBroadcastReceiver.longValue(extras, "timestamp_elapsed_ms", -1L)
            if (timestamp <= 0L) return "missing timestamp_elapsed_ms"
            val now: Long = SystemClock.elapsedRealtime()
            if (timestamp > now + 60000L) return "future timestamp"
            if (now - timestamp > NavBroadcastReceiver.CORE_BRIDGE_MAX_ENVELOPE_AGE_MS) {
                return "stale timestamp age=" + (now - timestamp)
            }
            val state = NavBroadcastReceiver.stringValue(extras, "state").lowercase(Locale.US)
            if (
                !(YandexCoreBridgeContract.STATE_OFF.equals(state) ||
                    YandexCoreBridgeContract.STATE_LOADING.equals(state) ||
                    YandexCoreBridgeContract.STATE_ACTIVE.equals(state) ||
                    YandexCoreBridgeContract.STATE_REROUTING.equals(state) ||
                    YandexCoreBridgeContract.STATE_FINISHED.equals(state))
            ) {
                return "state=" + state
            }
            return ""
        }

        private fun knownLegacyAction(action: String?): Boolean {
            return NavigationFeature.ACTION_MANEUVER.equals(action) ||
                NavigationFeature.ACTION_ETA.equals(action) ||
                NavigationFeature.ACTION_NAVI_ON.equals(action) ||
                NavigationFeature.ACTION_SPEED.equals(action) ||
                NavigationFeature.ACTION_EXCEEDED.equals(action) ||
                NavigationFeature.KIA_ACTION_MANEUVER.equals(action) ||
                NavigationFeature.KIA_ACTION_ETA.equals(action) ||
                NavigationFeature.KIA_ACTION_NAVI_ON.equals(action) ||
                NavigationFeature.KIA_ACTION_SPEED.equals(action) ||
                NavigationFeature.KIA_ACTION_EXCEEDED.equals(action) ||
                "com.yf.navinfo".equals(action) ||
                "com.teyes.MapAssistantService".equals(action) ||
                "android.action.MOBILE_NAVIGATION".equals(action)
        }

        private fun stringValue(extras: Bundle?, key: String?): String {
            val value: Any? = if (extras == null) null else extras.get(key)
            return if (value == null) "" else java.lang.String.valueOf(value).trim()
        }

        private fun longValue(extras: Bundle?, key: String?, fallback: Long): Long {
            val value: Any? = if (extras == null) null else extras.get(key)
            if (value is Number) return value.toLong()
            if (value == null) return fallback
            try {
                return java.lang.Long.parseLong(java.lang.String.valueOf(value).trim())
            } catch (ignored: NumberFormatException) {
                return fallback
            }
        }

        private fun ensureServiceStarted(context: Context): Boolean {
            val now: Long = SystemClock.elapsedRealtime()
            synchronized(NavBroadcastReceiver::class.java) {
                if (
                    !NavServiceStartPolicy.shouldStart(
                        AppService.isRunning(),
                        now,
                        NavBroadcastReceiver.lastServiceStartAt,
                        NavBroadcastReceiver.SERVICE_START_MIN_INTERVAL_MS,
                    )
                ) {
                    return false
                }
                NavBroadcastReceiver.lastServiceStartAt = now
            }
            return AppService.start(context.getApplicationContext())
        }

        private fun prepareService(context: Context) {
            val running: Boolean = AppService.isRunning()
            val startAccepted: Boolean =
                if (running) false else NavBroadcastReceiver.ensureServiceStarted(context)
            if (NavServiceStartPolicy.shouldAcquireReceiverWakeLock(running, startAccepted)) {
                NavBroadcastReceiver.keepCpuAwake(context)
            }
        }

        private fun keepCpuAwake(context: Context) {
            try {
                val app: Context? = context.getApplicationContext()
                synchronized(NavBroadcastReceiver::class.java) {
                    if (NavBroadcastReceiver.wakeLock == null) {
                        val pm: PowerManager? =
                            app!!.getSystemService(Context.POWER_SERVICE) as PowerManager?
                        if (pm != null) {
                            NavBroadcastReceiver.wakeLock =
                                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kia:NavBroadcast")
                            NavBroadcastReceiver.wakeLock!!.setReferenceCounted(false)
                        }
                    }
                    if (NavBroadcastReceiver.wakeLock != null)
                        NavBroadcastReceiver.wakeLock!!.acquire(NavBroadcastReceiver.NAV_WAKE_MS)
                }
            } catch (e: Exception) {
                AppLog.line(
                    context,
                    "Navigation receiver wakelock failed: " + e.javaClass.getSimpleName(),
                )
            }
        }

        private fun duplicate(intent: Intent): Boolean {
            return NavBroadcastReceiver.duplicate(NavBroadcastReceiver.signature(intent))
        }

        private fun duplicate(signature: String): Boolean {
            val now: Long = System.currentTimeMillis()
            synchronized(NavBroadcastReceiver::class.java) {
                if (
                    signature.equals(NavBroadcastReceiver.lastSignature) &&
                        now - NavBroadcastReceiver.lastSignatureAt <=
                            NavBroadcastReceiver.DUPLICATE_WINDOW_MS
                ) {
                    return true
                }
                NavBroadcastReceiver.lastSignature = signature
                NavBroadcastReceiver.lastSignatureAt = now
                return false
            }
        }

        private fun signature(intent: Intent): String {
            val out: StringBuilder = StringBuilder()
            out.append(intent.getAction()).append('|')
            val extras: Bundle? = intent.getExtras()
            if (extras == null || extras.isEmpty()) return out.toString()
            val keys: MutableList<String> = ArrayList(extras.keySet())
            Collections.sort(keys)
            for (key: String? in keys) {
                val value: Any? = extras.get(key)
                out.append(key)
                    .append('=')
                    .append(if (value == null) "null" else java.lang.String.valueOf(value))
                    .append(';')
            }
            return out.toString()
        }

        private fun legacyFallbackSuppressed(context: Context?, action: String?): Boolean {
            val mode: Int = AppSettings.navSourceMode(context!!)
            val now: Long = SystemClock.elapsedRealtime()
            val strictYandex: Boolean = mode == AppSettings.NAV_SOURCE_YANDEX
            val recentCoreBridge: Boolean =
                mode == AppSettings.NAV_SOURCE_AUTO &&
                    NavBroadcastReceiver.lastCoreBridgeSnapshotAt > 0L &&
                    now - NavBroadcastReceiver.lastCoreBridgeSnapshotAt <
                        NavBroadcastReceiver.CORE_BRIDGE_FALLBACK_SUPPRESS_MS
            if (!strictYandex && !recentCoreBridge) return false
            if (
                now - NavBroadcastReceiver.lastSuppressedLogAt >=
                    NavBroadcastReceiver.SUPPRESSED_LOG_MS
            ) {
                NavBroadcastReceiver.lastSuppressedLogAt = now
                AppLog.navigation(
                    context,
                    ("Navigation legacy fallback suppressed: " +
                        (if (strictYandex) "strict_yandex" else "core_bridge_recent") +
                        " action=" +
                        action),
                )
            }
            return true
        }

        private fun sourceIngressAllowed(context: Context?, source: Int): Boolean {
            return context != null &&
                NavigationSourcePolicy.ingressAllowed(
                    AppSettings.navigationEnabled(context),
                    AppSettings.navSourceMode(context),
                    source,
                )
        }
    }
}
