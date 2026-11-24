package kia.app.adas

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kia.app.core.AppLog
import kia.app.core.settings.AppSettings

/** Owns a single TEYES subscription shared by the background service and the diagnostic tab. */
class AdasController private constructor(context: Context) : kia.app.adas.TeyesAdasClient.Listener {
    private val context: Context?
    private val client: TeyesAdasClient
    private val history: SharedPreferences?
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val endDiagnostic: Runnable = Runnable { this.finishDiagnostic() }
    private var listening: Boolean = false
    private var phase: String? = "stopped"
    private var detail: String = ""
    private var recognition: Boolean? = null
    private var packageCheckedAt: Long = -AdasController.PACKAGE_CACHE_MS
    private var packageStatus: String = ""

    private var events: Long = 0
    private var lastEventAt: Long = 0
    private var lastEvent: TeyesAdasFrontEvent? = null
    private var diagnosticEnd: Long = 0
    private var diagnosticFrames: Long = 0
    private var diagnosticMalformed: Boolean = false
    private var diagnosticConnected: Boolean = false
    private var diagnosticTestFrames: Long = 0
    private var diagnosticWarnings: Int = 0
    private var diagnosticDirections: Int = 0
    private var lastDiagnostic: String? = "Ещё не запускалась"
    private var diagnosticRecord: String? = ""

    init {
        this.context = context
        client = TeyesAdasClient(context, this)
        history = context.getSharedPreferences("teyes_adas_diagnostic", Context.MODE_PRIVATE)
        lastDiagnostic = history.getString("summary", lastDiagnostic)
        diagnosticRecord = history.getString("report", "")
    }

    @kotlin.jvm.Synchronized
    fun applySettings() {
        val wanted: Boolean = AppSettings.adasEnabled(context!!) || diagnosticEnd != 0L
        if (wanted == listening) return
        listening = wanted
        if (wanted) client.start() else client.stop()
    }

    @kotlin.jvm.Synchronized
    fun diagnose() {
        if (diagnosticEnd != 0L) return
        client.flushPending()
        diagnosticEnd = SystemClock.elapsedRealtime() + AdasController.DIAGNOSTIC_MS
        diagnosticFrames = 0
        diagnosticTestFrames = 0
        diagnosticMalformed = false
        diagnosticConnected = "connected".equals(phase)
        diagnosticWarnings = 0
        diagnosticDirections = 0
        diagnosticRecord = ""
        refreshPackage()
        if (!listening) applySettings()
        else if (!"connected".equals(phase) && !"binding".equals(phase)) client.retry()
        handler.postDelayed(endDiagnostic, AdasController.DIAGNOSTIC_MS)
    }

    @kotlin.jvm.Synchronized
    private fun finishDiagnostic() {
        if (diagnosticEnd == 0L) return
        client.flushPending()
        recordDiagnostic(diagnosticResult())
        applySettings()
    }

    private fun diagnosticResult(): String? {
        if (diagnosticFrames == 0L) {
            if (diagnosticMalformed) return "Получен неизвестный формат фронтального сообщения"
            if ("connected".equals(phase))
                return "Связь есть, за 10 с фронтальные сообщения не пришли"
            if (diagnosticConnected)
                return "Подписка прервалась до получения сообщений. " + connection()
            return "За 10 с связь не подтверждена. " + connection()
        }
        var summary: String = "За 10 с получено сообщений: " + diagnosticFrames
        if (diagnosticTestFrames > 0) summary += ", из них тестовых: " + diagnosticTestFrames
        if (diagnosticFrames == 1L) summary += ". Первое сообщение может быть сохранено TEYES"
        if (TeyesAdasFrontEvent.hasLaneWarning(diagnosticWarnings)) {
            summary +=
                ". Зафиксировано: " +
                    TeyesAdasFrontEvent.warningText(
                        diagnosticWarnings or diagnosticDirections,
                        null,
                    )
        }
        if (diagnosticMalformed) summary += ". Также получено сообщение неизвестного формата"
        return summary
    }

    private fun recordDiagnostic(result: String?) {
        diagnosticEnd = 0
        lastDiagnostic =
            (SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date()) + " · " + result)
        diagnosticRecord =
            ("TEYES ADAS · " +
                lastDiagnostic +
                "\nПриложение: " +
                packageStatus +
                "\nСвязь на момент окончания: " +
                connection() +
                "\nМаска предупреждений за проверку: " +
                AdasController.hex(diagnosticWarnings) +
                "\nСообщения неизвестного формата: " +
                (if (diagnosticMalformed) "да" else "нет"))
        if (diagnosticFrames > 0 && lastEventAt > 0) {
            diagnosticRecord +=
                ("\nПоследнее сообщение: " +
                    AdasController.time(lastEventAt) +
                    (if (lastEvent!!.testEvent) " (тест TEYES)" else "") +
                    ", LDW: " +
                    laneDetails() +
                    ", предупреждения: " +
                    AdasController.hex(lastEvent!!.warningType))
        }
        history!!
            .edit()
            .putString("summary", lastDiagnostic)
            .putString("report", diagnosticRecord)
            .apply()
        AppLog.line(context, "ADAS · " + lastDiagnostic)
    }

    @kotlin.jvm.Synchronized
    fun stop() {
        handler.removeCallbacks(endDiagnostic)
        if (diagnosticEnd != 0L) recordDiagnostic("Проверка прервана при остановке службы Kia")
        listening = false
        client.stop()
    }

    @kotlin.jvm.Synchronized
    override fun onStatus(phase: String?, detail: String?) {
        if ("binding".equals(phase)) {
            events = 0
            lastEventAt = 0
            lastEvent = null
        }
        if ("connected".equals(phase) && diagnosticEnd != 0L) diagnosticConnected = true
        if (!"connected".equals(phase)) recognition = null
        this.phase = phase
        this.detail = if (detail == null) "" else detail
    }

    @kotlin.jvm.Synchronized
    override fun onRunning(running: Boolean) {
        recognition = running
    }

    @kotlin.jvm.Synchronized
    override fun onFrontEvent(
        event: TeyesAdasFrontEvent?,
        frames: Int,
        warningFlags: Int,
        directions: Int,
        testFrames: Int,
    ) {
        lastEvent = event
        lastEventAt = System.currentTimeMillis()
        events += frames.toLong()
        if (diagnosticEnd != 0L) {
            diagnosticFrames += frames.toLong()
            diagnosticTestFrames += testFrames.toLong()
            diagnosticWarnings = diagnosticWarnings or warningFlags
            diagnosticDirections = diagnosticDirections or directions
        }
    }

    @kotlin.jvm.Synchronized
    override fun onMalformedFrontEvent() {
        if (diagnosticEnd != 0L) diagnosticMalformed = true
    }

    @kotlin.jvm.Synchronized
    fun snapshot(): kia.app.adas.AdasController.Snapshot {
        val now: Long = SystemClock.elapsedRealtime()
        if (now - packageCheckedAt >= AdasController.PACKAGE_CACHE_MS) refreshPackage()
        val event: String =
            if (events == 0L) "Сообщений пока нет"
            else
                ("Получено: " +
                    events +
                    " · последнее в " +
                    AdasController.time(lastEventAt) +
                    (if (lastEvent!!.testEvent) " · тест TEYES" else ""))
        val diagnostic =
            if (diagnosticEnd == 0L) lastDiagnostic
            else
                ("Идёт проверка · " +
                    Math.max(0L, (diagnosticEnd - now + 999L) / 1000L) +
                    " с · сообщений: " +
                    diagnosticFrames)
        return kia.app.adas.AdasController.Snapshot(
            packageStatus,
            connection(),
            event,
            if (lastEventAt == 0L) "Нет данных" else laneText(),
            if (lastEventAt == 0L) "Нет данных"
            else TeyesAdasFrontEvent.warningText(lastEvent!!.warningType, lastEvent!!.laneState),
            diagnostic,
            diagnosticEnd != 0L,
        )
    }

    @kotlin.jvm.Synchronized
    fun report(): String? {
        val state: kia.app.adas.AdasController.Snapshot = snapshot()
        val text: String =
            ("TEYES ADAS · " +
                AdasController.time(System.currentTimeMillis()) +
                "\nПриложение: " +
                state.packageStatus +
                " (" +
                TeyesAdasClient.PACKAGE +
                ")" +
                "\nФоновая подписка: " +
                (if (AppSettings.adasEnabled(context!!)) "вкл" else "выкл") +
                "\nПодключение: " +
                state.connectionStatus +
                "\nСобытия: " +
                state.eventStatus +
                "\nРазметка в последнем сообщении: " +
                (if (lastEventAt == 0L) "Нет данных" else laneDetails()) +
                "\nПредупреждение в последнем сообщении: " +
                state.warningStatus +
                (if (lastEventAt == 0L) ""
                else " (" + AdasController.hex(lastEvent!!.warningType) + ")") +
                "\nПередача ADAS в приборку: в этой версии отсутствует")
        return text +
            "\n\n" +
            (if (diagnosticRecord!!.isEmpty()) "Диагностика: " + state.diagnosticStatus
            else diagnosticRecord)
    }

    private fun refreshPackage() {
        packageCheckedAt = SystemClock.elapsedRealtime()
        try {
            val info = context!!.getPackageManager().getPackageInfo(TeyesAdasClient.PACKAGE, 0)
            packageStatus =
                "Установлено" + (if (info!!.versionName == null) "" else " · " + info.versionName)
            if (info.applicationInfo != null && !info.applicationInfo!!.enabled)
                packageStatus += " · отключено"
        } catch (e: PackageManager.NameNotFoundException) {
            packageStatus = "Пакет не найден"
        } catch (e: RuntimeException) {
            packageStatus = "Ошибка проверки пакета · " + e.javaClass.getSimpleName()
        }
    }

    private fun connection(): String? {
        when (phase) {
            "connected" -> {
                val state: String =
                    if (recognition == null) "состояние распознавания неизвестно"
                    else if (recognition == true) "распознавание включено"
                    else "распознавание выключено"
                return "Подписка активна · " + state
            }
            "binding" -> return "Подключение…"
            "stopped" -> return "Отключено"
            else -> return if (detail.isEmpty()) "Сервис недоступен" else detail
        }
    }

    private fun laneText(): String? {
        if (lastEvent!!.lineCount == null) return "В сообщении нет данных о разметке"
        return if (lastEvent!!.lineCount == 0) "Линии не обнаружены"
        else "Линий в последнем сообщении: " + lastEvent!!.lineCount
    }

    private fun laneDetails(): String? {
        return if (lastEvent!!.lineCount == null) laneText()
        else
            laneText().toString() +
                " (state=" +
                lastEvent!!.laneState +
                ", lanes_change=" +
                lastEvent!!.laneChange +
                ")"
    }

    class Snapshot
    internal constructor(
        pkg: String?,
        connection: String?,
        event: String?,
        lane: String?,
        warning: String?,
        diagnostic: String?,
        running: Boolean,
    ) {
        @JvmField val packageStatus: String?
        @JvmField val connectionStatus: String?
        @JvmField val eventStatus: String?
        @JvmField val laneStatus: String?
        @JvmField val warningStatus: String?
        @JvmField val diagnosticStatus: String?
        @JvmField val diagnosticRunning: Boolean

        init {
            packageStatus = pkg
            connectionStatus = connection
            eventStatus = event
            laneStatus = lane
            warningStatus = warning
            diagnosticStatus = diagnostic
            diagnosticRunning = running
        }
    }

    companion object {
        private const val DIAGNOSTIC_MS: Long = 10000L
        private const val PACKAGE_CACHE_MS: Long = 5000L
        private var instance: AdasController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): AdasController {
            if (AdasController.instance == null)
                AdasController.instance = AdasController(context.getApplicationContext())
            return AdasController.instance!!
        }

        private fun hex(value: Int): String? {
            return String.format(Locale.ROOT, "0x%X", value)
        }

        private fun time(`when`: Long): String? {
            return SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(`when`))
        }
    }
}
