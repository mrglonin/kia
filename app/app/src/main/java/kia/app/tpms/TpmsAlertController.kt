package kia.app.tpms

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import java.util.Locale
import kia.app.core.AppLog
import kia.app.core.model.TpmsState
import kia.app.core.settings.AppSettings
import kia.app.entry.MainActivity

class TpmsAlertController private constructor(context: Context) {
    private val app: Context
    private val notificationLifecycle: TpmsAlertLifecycle =
        TpmsAlertLifecycle(TpmsAlertController.CAUSE_SLOTS)

    init {
        this.app = context.applicationContext ?: context
    }

    @kotlin.jvm.Synchronized
    fun apply(state: TpmsState?) {
        if (!AppSettings.tpmsAlertsEnabled(app)) {
            clear()
            return
        }
        val levels: IntArray = TpmsAlertController.warningLevels(app, state)!!
        val details: String? = TpmsAlertController.warningDetails(app, state)
        TpmsWarningOverlayController.get(app).apply(levels, details)
        val now: Long = SystemClock.elapsedRealtime()
        notificationLifecycle.observe(levels, now)
        if (TextUtils.isEmpty(details)) {
            cancel()
            return
        }
        if (!notificationLifecycle.shouldNotify(now)) return
        show(
            "TPMS: " +
                TpmsAlertController.warningText(TpmsAlertController.firstWarning(app, state)),
            details,
        )
        AppLog.line(app, "TPMS alert: " + details)
    }

    /** Clears every alert surface and invalidates queued overlay/sound updates. */
    @kotlin.jvm.Synchronized
    fun clear() {
        notificationLifecycle.reset()
        TpmsWarningOverlayController.get(app).clear()
        cancel()
    }

    private fun show(title: String?, text: String?) {
        if (!canNotify()) return
        val manager: NotificationManager? =
            app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (manager == null) return
        if (Build.VERSION.SDK_INT >= 26) {
            val channel: NotificationChannel =
                NotificationChannel(
                    TpmsAlertController.CHANNEL,
                    "KIA TPMS",
                    NotificationManager.IMPORTANCE_LOW,
                )
            channel.enableVibration(false)
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
        val open: Intent = Intent(app, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var flags: Int = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val contentIntent: PendingIntent? =
            PendingIntent.getActivity(app, TpmsAlertController.NOTIFICATION_ID, open, flags)
        var icon: Int = app.getApplicationInfo().icon
        if (icon == 0) icon = android.R.drawable.stat_sys_warning
        val builder: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, TpmsAlertController.CHANNEL)
            else Notification.Builder(app)
        builder
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_LOW)
            .setDefaults(0)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(contentIntent)
        try {
            manager.notify(TpmsAlertController.NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            AppLog.line(app, "TPMS alert notify failed: " + e.javaClass.getSimpleName())
        }
    }

    private fun cancel() {
        val manager: NotificationManager? =
            app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (manager != null) manager.cancel(TpmsAlertController.NOTIFICATION_ID)
    }

    private fun canNotify(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)
    }

    companion object {
        const val WARNING_NONE: Int = 0
        const val WARNING_FAST_LEAKAGE: Int = 1
        const val WARNING_HIGH_PRESSURE: Int = 2
        const val WARNING_HIGH_TEMP: Int = 3
        const val WARNING_LOW_PRESSURE: Int = 4
        const val WARNING_LOW_BATTERY: Int = 5
        const val WARNING_LOW_TEMP: Int = 6

        const val SEVERITY_NONE: Int = 0
        const val SEVERITY_WARNING: Int = 1
        const val SEVERITY_CRITICAL: Int = 2

        private val CHANNEL: String = "kia_tpms_status"
        private const val NOTIFICATION_ID: Int = 4418
        const val CAUSE_SLOTS: Int = TpmsState.WHEEL_COUNT * TpmsWarningPolicy.CAUSE_COUNT

        private var instance: TpmsAlertController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): TpmsAlertController {
            if (TpmsAlertController.instance == null)
                TpmsAlertController.instance = TpmsAlertController(context)
            return TpmsAlertController.instance!!
        }

        @JvmStatic
        fun warningState(context: Context?, state: TpmsState?, wheel: Int): Int {
            if (
                state == null ||
                    state.known == null ||
                    state.pressureKpa == null ||
                    state.temperatureC == null ||
                    wheel < 0 ||
                    wheel >= TpmsState.WHEEL_COUNT ||
                    !state.known.get(wheel) ||
                    !state.isWheelFresh(wheel)
            ) {
                return TpmsAlertController.WARNING_NONE
            }
            val flags: Int = if (state.flags == null) 0 else state.flags.get(wheel)
            return TpmsAlertController.warningState(
                context,
                state.pressureKpa.get(wheel),
                state.temperatureC.get(wheel),
                flags,
            )
        }

        @JvmStatic
        fun warningState(context: Context?, pressureKpa: Int, tempC: Int, flags: Int): Int {
            return TpmsAlertController.evaluate(context, pressureKpa, tempC, flags).warning()
        }

        @JvmStatic
        fun warningSeverity(context: Context?, state: TpmsState?, wheel: Int): Int {
            if (
                state == null ||
                    state.known == null ||
                    state.pressureKpa == null ||
                    state.temperatureC == null ||
                    wheel < 0 ||
                    wheel >= TpmsState.WHEEL_COUNT ||
                    !state.known.get(wheel) ||
                    !state.isWheelFresh(wheel)
            ) {
                return TpmsAlertController.SEVERITY_NONE
            }
            val flags: Int = if (state.flags == null) 0 else state.flags.get(wheel)
            return TpmsAlertController.warningSeverity(
                context,
                state.pressureKpa.get(wheel),
                state.temperatureC.get(wheel),
                flags,
            )
        }

        @JvmStatic
        fun warningSeverity(context: Context?, pressureKpa: Int, tempC: Int, flags: Int): Int {
            return TpmsAlertController.evaluate(context, pressureKpa, tempC, flags).severity()
        }

        private fun evaluate(
            context: Context?,
            pressureKpa: Int,
            tempC: Int,
            flags: Int,
        ): TpmsWarningPolicy.Result {
            if (context == null || !AppSettings.tpmsAlertsEnabled(context)) {
                return TpmsWarningPolicy.Result()
            }
            return TpmsWarningPolicy.evaluate(
                pressureKpa,
                tempC,
                flags,
                AppSettings.tpmsLowPressureKpa(context),
                AppSettings.tpmsHighPressureKpa(context),
                AppSettings.tpmsLowTempC(context),
                AppSettings.tpmsHighTempC(context),
            )
        }

        @JvmStatic
        fun warningLevels(context: Context?, state: TpmsState?): IntArray? {
            val levels: IntArray = IntArray(TpmsAlertController.CAUSE_SLOTS)
            if (state == null || context == null || !AppSettings.tpmsAlertsEnabled(context))
                return levels
            for (wheel in 0..<TpmsState.WHEEL_COUNT) {
                if (!state.isWheelFresh(wheel)) continue
                val result: TpmsWarningPolicy.Result =
                    TpmsAlertController.evaluate(
                        context,
                        state.pressureKpa.get(wheel),
                        state.temperatureC.get(wheel),
                        state.flags.get(wheel),
                    )
                System.arraycopy(
                    result.levels,
                    0,
                    levels,
                    wheel * TpmsWarningPolicy.CAUSE_COUNT,
                    result.levels.size,
                )
            }
            return levels
        }

        @JvmStatic
        fun hasWarnings(context: Context?, state: TpmsState?): Boolean {
            return TpmsAlertController.firstWarning(context, state) !=
                TpmsAlertController.WARNING_NONE
        }

        @JvmStatic
        fun hasCriticalWarnings(context: Context?, state: TpmsState?): Boolean {
            if (state == null || state.known == null) return false
            for (wheel in 0..<TpmsState.WHEEL_COUNT) {
                if (
                    TpmsAlertController.warningSeverity(context, state, wheel) ==
                        TpmsAlertController.SEVERITY_CRITICAL
                )
                    return true
            }
            return false
        }

        @JvmStatic
        fun firstWarning(context: Context?, state: TpmsState?): Int {
            val levels: IntArray = TpmsAlertController.warningLevels(context, state)!!
            var selected: Int = TpmsAlertController.WARNING_NONE
            var highest: Int = TpmsAlertController.SEVERITY_NONE
            for (slot in levels.indices) {
                if (levels.get(slot) > highest) {
                    highest = levels.get(slot)
                    selected = slot % TpmsWarningPolicy.CAUSE_COUNT
                }
            }
            return selected
        }

        @JvmStatic
        /** Identity contains wheel/cause/severity only; current readings remain in details. */
        fun warningKey(context: Context?, state: TpmsState?): String? {
            val levels: IntArray = TpmsAlertController.warningLevels(context, state)!!
            val out: StringBuilder = StringBuilder()
            for (slot in levels.indices) {
                if (levels.get(slot) == TpmsAlertController.SEVERITY_NONE) continue
                if (out.length > 0) out.append('|')
                out.append(slot / TpmsWarningPolicy.CAUSE_COUNT)
                    .append(':')
                    .append(slot % TpmsWarningPolicy.CAUSE_COUNT)
                    .append(':')
                    .append(levels.get(slot))
            }
            return out.toString()
        }

        @JvmStatic
        fun warningDetails(context: Context?, state: TpmsState?): String {
            if (context == null || state == null || state.known == null) return ""
            val out: StringBuilder = StringBuilder()
            for (wheel in 0..<TpmsState.WHEEL_COUNT) {
                val warning: Int = TpmsAlertController.warningState(context, state, wheel)
                if (warning == TpmsAlertController.WARNING_NONE) continue
                if (out.length > 0) out.append("; ")
                val severity: Int = TpmsAlertController.warningSeverity(context, state, wheel)
                out.append(TpmsAlertController.wheelName(wheel))
                    .append(' ')
                    .append(TpmsAlertController.barText(state.pressureKpa.get(wheel)))
                    .append(" / ")
                    .append(state.temperatureC.get(wheel))
                    .append("C, ")
                    .append(TpmsAlertController.severityText(severity))
                    .append(": ")
                    .append(TpmsAlertController.warningText(warning))
            }
            return out.toString()
        }

        @JvmStatic
        fun severityText(severity: Int): String {
            when (severity) {
                TpmsAlertController.SEVERITY_CRITICAL -> return "критично"
                TpmsAlertController.SEVERITY_WARNING -> return "внимание"
                else -> return "норма"
            }
        }

        @JvmStatic
        fun warningText(warning: Int): String {
            when (warning) {
                TpmsAlertController.WARNING_FAST_LEAKAGE -> return "утечка давления"
                TpmsAlertController.WARNING_HIGH_PRESSURE -> return "высокое давление"
                TpmsAlertController.WARNING_HIGH_TEMP -> return "высокая температура"
                TpmsAlertController.WARNING_LOW_PRESSURE -> return "низкое давление"
                TpmsAlertController.WARNING_LOW_BATTERY -> return "низкий заряд датчика"
                TpmsAlertController.WARNING_LOW_TEMP -> return "низкая температура"
                else -> return "предупреждение"
            }
        }

        @JvmStatic
        fun wheelName(wheel: Int): String {
            when (wheel) {
                TpmsState.WHEEL_FL -> return "FL"
                TpmsState.WHEEL_FR -> return "FR"
                TpmsState.WHEEL_RL -> return "RL"
                TpmsState.WHEEL_RR -> return "RR"
                else -> return "Tire"
            }
        }

        @JvmStatic
        fun barText(pressureKpa: Int): String {
            return String.format(Locale.US, "%.2f bar", pressureKpa / 100f)
        }
    }
}
