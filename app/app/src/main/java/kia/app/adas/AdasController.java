package kia.app.adas;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import kia.app.core.AppLog;
import kia.app.core.settings.AppSettings;

/** Owns a single TEYES subscription shared by the background service and the diagnostic tab. */
public final class AdasController implements TeyesAdasClient.Listener {
    private static final long DIAGNOSTIC_MS = 10_000L;
    private static final long PACKAGE_CACHE_MS = 5_000L;
    private static AdasController instance;

    private final Context context;
    private final TeyesAdasClient client;
    private final SharedPreferences history;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable endDiagnostic = this::finishDiagnostic;
    private boolean listening;
    private String phase = "stopped";
    private String detail = "";
    private Boolean recognition;
    private long packageCheckedAt = -PACKAGE_CACHE_MS;
    private String packageStatus = "";

    private long events;
    private long lastEventAt;
    private TeyesAdasFrontEvent lastEvent;
    private long diagnosticEnd;
    private long diagnosticFrames;
    private boolean diagnosticMalformed;
    private boolean diagnosticConnected;
    private long diagnosticTestFrames;
    private int diagnosticWarnings;
    private int diagnosticDirections;
    private String lastDiagnostic = "Ещё не запускалась";
    private String diagnosticRecord = "";

    public static synchronized AdasController get(Context context) {
        if (instance == null) instance = new AdasController(context.getApplicationContext());
        return instance;
    }

    private AdasController(Context context) {
        this.context = context;
        client = new TeyesAdasClient(context, this);
        history = context.getSharedPreferences("teyes_adas_diagnostic", Context.MODE_PRIVATE);
        lastDiagnostic = history.getString("summary", lastDiagnostic);
        diagnosticRecord = history.getString("report", "");
    }

    public synchronized void applySettings() {
        boolean wanted = AppSettings.adasEnabled(context) || diagnosticEnd != 0;
        if (wanted == listening) return;
        listening = wanted;
        if (wanted) client.start();
        else client.stop();
    }

    public synchronized void diagnose() {
        if (diagnosticEnd != 0) return;
        client.flushPending();
        diagnosticEnd = SystemClock.elapsedRealtime() + DIAGNOSTIC_MS;
        diagnosticFrames = 0;
        diagnosticTestFrames = 0;
        diagnosticMalformed = false;
        diagnosticConnected = "connected".equals(phase);
        diagnosticWarnings = 0;
        diagnosticDirections = 0;
        diagnosticRecord = "";
        refreshPackage();
        if (!listening) applySettings();
        else if (!"connected".equals(phase) && !"binding".equals(phase)) client.retry();
        handler.postDelayed(endDiagnostic, DIAGNOSTIC_MS);
    }

    private synchronized void finishDiagnostic() {
        if (diagnosticEnd == 0) return;
        client.flushPending();
        recordDiagnostic(diagnosticResult());
        applySettings();
    }

    private String diagnosticResult() {
        if (diagnosticFrames == 0) {
            if (diagnosticMalformed) return "Получен неизвестный формат фронтального сообщения";
            if ("connected".equals(phase)) return "Связь есть, за 10 с фронтальные сообщения не пришли";
            if (diagnosticConnected) return "Подписка прервалась до получения сообщений. " + connection();
            return "За 10 с связь не подтверждена. " + connection();
        }
        String summary = "За 10 с получено сообщений: " + diagnosticFrames;
        if (diagnosticTestFrames > 0) summary += ", из них тестовых: " + diagnosticTestFrames;
        if (diagnosticFrames == 1) summary += ". Первое сообщение может быть сохранено TEYES";
        if (TeyesAdasFrontEvent.hasLaneWarning(diagnosticWarnings)) {
            summary += ". Зафиксировано: " + TeyesAdasFrontEvent.warningText(diagnosticWarnings | diagnosticDirections, null);
        }
        if (diagnosticMalformed) summary += ". Также получено сообщение неизвестного формата";
        return summary;
    }

    private void recordDiagnostic(String result) {
        diagnosticEnd = 0;
        lastDiagnostic = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(new Date())
                + " · " + result;
        diagnosticRecord = "TEYES ADAS · " + lastDiagnostic + "\nПриложение: " + packageStatus
                + "\nСвязь на момент окончания: " + connection()
                + "\nМаска предупреждений за проверку: " + hex(diagnosticWarnings)
                + "\nСообщения неизвестного формата: " + (diagnosticMalformed ? "да" : "нет");
        if (diagnosticFrames > 0 && lastEventAt > 0) {
            diagnosticRecord += "\nПоследнее сообщение: " + time(lastEventAt)
                    + (lastEvent.testEvent ? " (тест TEYES)" : "") + ", LDW: " + laneDetails() + ", предупреждения: " + hex(lastEvent.warningType);
        }
        history.edit().putString("summary", lastDiagnostic).putString("report", diagnosticRecord).apply();
        AppLog.line(context, "ADAS · " + lastDiagnostic);
    }

    public synchronized void stop() {
        handler.removeCallbacks(endDiagnostic);
        if (diagnosticEnd != 0) recordDiagnostic("Проверка прервана при остановке службы Kia");
        listening = false;
        client.stop();
    }

    @Override
    public synchronized void onStatus(String phase, String detail) {
        if ("binding".equals(phase)) {
            events = 0;
            lastEventAt = 0;
            lastEvent = null;
        }
        if ("connected".equals(phase) && diagnosticEnd != 0) diagnosticConnected = true;
        if (!"connected".equals(phase)) recognition = null;
        this.phase = phase;
        this.detail = detail == null ? "" : detail;
    }

    @Override
    public synchronized void onRunning(boolean running) {
        recognition = running;
    }

    @Override
    public synchronized void onFrontEvent(TeyesAdasFrontEvent event, int frames, int warningFlags,
                                         int directions, int testFrames) {
        lastEvent = event;
        lastEventAt = System.currentTimeMillis();
        events += frames;
        if (diagnosticEnd != 0) {
            diagnosticFrames += frames;
            diagnosticTestFrames += testFrames;
            diagnosticWarnings |= warningFlags;
            diagnosticDirections |= directions;
        }
    }

    @Override
    public synchronized void onMalformedFrontEvent() {
        if (diagnosticEnd != 0) diagnosticMalformed = true;
    }

    public synchronized Snapshot snapshot() {
        long now = SystemClock.elapsedRealtime();
        if (now - packageCheckedAt >= PACKAGE_CACHE_MS) refreshPackage();
        String event = events == 0 ? "Сообщений пока нет"
                : "Получено: " + events + " · последнее в " + time(lastEventAt)
                        + (lastEvent.testEvent ? " · тест TEYES" : "");
        String diagnostic = diagnosticEnd == 0 ? lastDiagnostic : "Идёт проверка · "
                + Math.max(0L, (diagnosticEnd - now + 999L) / 1000L) + " с · сообщений: "
                + diagnosticFrames;
        return new Snapshot(packageStatus, connection(), event,
                lastEventAt == 0 ? "Нет данных" : laneText(),
                lastEventAt == 0 ? "Нет данных" : TeyesAdasFrontEvent.warningText(lastEvent.warningType, lastEvent.laneState), diagnostic,
                diagnosticEnd != 0);
    }

    public synchronized String report() {
        Snapshot state = snapshot();
        String text = "TEYES ADAS · " + time(System.currentTimeMillis())
                + "\nПриложение: " + state.packageStatus + " (" + TeyesAdasClient.PACKAGE + ")"
                + "\nФоновая подписка: " + (AppSettings.adasEnabled(context) ? "вкл" : "выкл")
                + "\nПодключение: " + state.connectionStatus + "\nСобытия: " + state.eventStatus
                + "\nРазметка в последнем сообщении: " + (lastEventAt == 0 ? "Нет данных" : laneDetails())
                + "\nПредупреждение в последнем сообщении: " + state.warningStatus
                + (lastEventAt == 0 ? "" : " (" + hex(lastEvent.warningType) + ")")
                + "\nПередача ADAS в приборку: в этой версии отсутствует";
        return text + "\n\n" + (diagnosticRecord.isEmpty()
                ? "Диагностика: " + state.diagnosticStatus : diagnosticRecord);
    }

    private void refreshPackage() {
        packageCheckedAt = SystemClock.elapsedRealtime();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TeyesAdasClient.PACKAGE, 0);
            packageStatus = "Установлено" + (info.versionName == null ? "" : " · " + info.versionName);
            if (info.applicationInfo != null && !info.applicationInfo.enabled) packageStatus += " · отключено";
        } catch (PackageManager.NameNotFoundException e) {
            packageStatus = "Пакет не найден";
        } catch (RuntimeException e) {
            packageStatus = "Ошибка проверки пакета · " + e.getClass().getSimpleName();
        }
    }

    private String connection() {
        switch (phase) {
            case "connected":
                String state = recognition == null ? "состояние распознавания неизвестно"
                        : recognition ? "распознавание включено" : "распознавание выключено";
                return "Подписка активна · " + state;
            case "binding": return "Подключение…";
            case "stopped": return "Отключено";
            default: return detail.isEmpty() ? "Сервис недоступен" : detail;
        }
    }

    private String laneText() {
        if (lastEvent.lineCount == null) return "В сообщении нет данных о разметке";
        return lastEvent.lineCount == 0 ? "Линии не обнаружены" : "Линий в последнем сообщении: " + lastEvent.lineCount;
    }

    private String laneDetails() {
        return lastEvent.lineCount == null ? laneText()
                : laneText() + " (state=" + lastEvent.laneState + ", lanes_change=" + lastEvent.laneChange + ")";
    }

    private static String hex(int value) {
        return String.format(Locale.ROOT, "0x%X", value);
    }

    private static String time(long when) {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(when));
    }

    public static final class Snapshot {
        public final String packageStatus, connectionStatus, eventStatus, laneStatus, warningStatus, diagnosticStatus;
        public final boolean diagnosticRunning;

        private Snapshot(String pkg, String connection, String event, String lane, String warning,
                         String diagnostic, boolean running) {
            packageStatus = pkg;
            connectionStatus = connection;
            eventStatus = event;
            laneStatus = lane;
            warningStatus = warning;
            diagnosticStatus = diagnostic;
            diagnosticRunning = running;
        }
    }
}
