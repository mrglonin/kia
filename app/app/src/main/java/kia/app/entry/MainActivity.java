package kia.app.entry;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import kia.app.R;
import kia.app.amp.AmpController;
import kia.app.amp.AmpVisualizerView;
import kia.app.core.AppIds;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.AmpState;
import kia.app.core.model.CallState;
import kia.app.core.model.MediaState;
import kia.app.core.model.TpmsState;
import kia.app.core.model.UpdateState;
import kia.app.core.model.VehicleState;
import kia.app.core.settings.AppSettings;
import kia.app.diagnostics.CanLogger;
import kia.app.diagnostics.GsUsbCanLogger;
import kia.app.media.capture.MediaNotificationListener;
import kia.app.media.domain.CallFeature;
import kia.app.media.domain.MediaFeature;
import kia.app.media.domain.RadioStationStore;
import kia.app.media.overlay.MediaOverlayController;
import kia.app.navigation.domain.NavigationFeature;
import kia.app.navigation.domain.NavigationModeSettings;
import kia.app.navigation.domain.NavigationOutputMode;
import kia.app.navigation.overlay.NavigationOverlayController;
import kia.app.protocol.adapter.AdapterCommand;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;
import kia.app.rcta.BlindSpotOverlayView;
import kia.app.rcta.RctaOverlayController;
import kia.app.tpms.TpmsAlertController;
import kia.app.tpms.TpmsController;
import kia.app.tpms.TpmsDashboardView;
import kia.app.tpms.TpmsWarningOverlayController;
import kia.app.update.AppUpdateController;
import kia.app.update.FirmwareUpdateController;
import kia.app.update.NavigatorUpdateController;

public final class MainActivity extends Activity {
    private static final int COLOR_BG = Color.rgb(13, 15, 19);
    private static final int COLOR_SURFACE = Color.argb(150, 15, 22, 32);
    private static final int COLOR_PANEL = Color.argb(132, 23, 31, 43);
    private static final int COLOR_PANEL_SOFT = Color.argb(165, 36, 48, 62);
    private static final int COLOR_STROKE = Color.argb(92, 178, 211, 230);
    private static final int COLOR_SETTINGS_BG = Color.rgb(8, 11, 15);
    private static final int COLOR_SETTINGS_TOP = Color.rgb(11, 15, 21);
    private static final int COLOR_SETTINGS_PANEL = Color.rgb(18, 23, 31);
    private static final int COLOR_SETTINGS_PANEL_ALT = Color.rgb(25, 32, 42);
    private static final int COLOR_SETTINGS_SELECTED = Color.rgb(35, 48, 61);
    private static final int COLOR_SETTINGS_DIVIDER = Color.rgb(47, 58, 70);
    private static final int COLOR_SETTINGS_STROKE = Color.rgb(49, 63, 78);
    private static final int COLOR_TEXT = Color.rgb(246, 248, 252);
    private static final int COLOR_MUTED = Color.rgb(157, 172, 190);
    private static final int COLOR_ACCENT = Color.rgb(57, 211, 190);
    private static final int COLOR_ACCENT_BLUE = Color.rgb(82, 156, 255);
    private static final int COLOR_WARNING = Color.rgb(245, 176, 78);
    private static final int COLOR_VIOLET = Color.rgb(151, 116, 255);
    private static final int COLOR_ROSE = Color.rgb(242, 92, 139);
    private static final int COLOR_SUCCESS = Color.rgb(57, 211, 141);
    private static final int COLOR_DANGER = Color.rgb(235, 83, 91);

    private static final int TAB_TPMS = 0;
    private static final int TAB_MEDIA = 1;
    private static final int TAB_NAVIGATION = 2;
    private static final int TAB_CANBUS = 3;
    private static final int TAB_SETTINGS = 4;
    private static final int TAB_LOG = 5;

    private static final int SETTINGS_TPMS = 0;
    private static final int SETTINGS_MEDIA = 1;
    private static final int SETTINGS_NAVIGATION = 2;
    private static final int SETTINGS_CANBUS = 3;
    private static final int SETTINGS_RCTA = 4;
    private static final int SETTINGS_GENERAL = 5;
    private static final int SETTINGS_LOG = 6;
    private static final int REQUEST_FIRMWARE_FILE = 42;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout screenFrame;
    private LinearLayout rootLayout;
    private LinearLayout tabContent;
    private TpmsDashboardView tpmsDashboard;
    private TextView[] tabButtons;
    private int selectedTab = TAB_TPMS;
    private boolean settingsMode;
    private int settingsTab = SETTINGS_TPMS;
    private int rootInsetTop;
    private int rootInsetBottom;
    private TextView status;
    private TextView tpmsStatus;
    private CompoundButton mediaTabToggle;
    private CompoundButton callEnabledToggle;
    private CompoundButton mediaDebugToggle;
    private TextView mediaDebugStatus;
    private TextView navigationStatus;
    private TextView navigationDebugStatus;
    private CompoundButton navigationDebugToggle;
    private CompoundButton navigationOverlayToggle;
    private CompoundButton microManeuverToggle;
    private CompoundButton finishCompassAutoToggle;
    private CompoundButton navTbtToggle;
    private CompoundButton overspeedToggle;
    private CompoundButton autoStartToggle;
    private CompoundButton logTabToggle;
    private TextView diagnosticsStatus;
    private TextView canbusDebugStatus;
    private CompoundButton canbusDebugToggle;
    private CompoundButton ampEnabledToggle;
    private AmpVisualizerView ampVisualizer;
    private TextView ampSummary;
    private CompoundButton tpmsAlertsToggle;
    private CompoundButton tpmsSoundToggle;
    private TextView loggerStatus;
    private CompoundButton rawCanToggle;
    private TextView updatesStatus;
    private TextView firmwareStatus;
    private FrameLayout firmwareActionButton;
    private View firmwareProgressFill;
    private TextView firmwareActionText;
    private TextView firmwareActionHintText;
    private TextView log;
    private TextView permissionSummary;
    private TextView sasRatioStatus;
    private EditText sasRatioInput;
    private View sasRatioPreview;
    private final View[] navSourceModeViews = new View[3];
    private final TextView[] navSourceModeChecks = new TextView[3];
    private final View[] navTextModeViews = new View[3];
    private final TextView[] navTextModeChecks = new TextView[3];
    private final View[] navRouteModeViews = new View[3];
    private final TextView[] navRouteModeChecks = new TextView[3];
    private final View[] mediaWizardProfileViews = new View[4];
    private final TextView[] mediaWizardProfileChecks = new TextView[4];
    private ScrollView activeScrollView;
    private BlindSpotOverlayView rctaPreview;
    private BlindSpotOverlayView rctaDemoOverlay;
    private MediaPlayer rctaPlayer;
    private boolean rctaDiagnosticsActive;
    private final ArrayList<EditText> ampInputs = new ArrayList<>();
    private final ArrayList<EditText> tpmsInputs = new ArrayList<>();
    private Uri pendingFirmwareUri;
    private String pendingFirmwareLabel = "";
    private AppUpdateController appUpdater;
    private NavigatorUpdateController navigatorUpdater;
    private FirmwareUpdateController firmwareUpdater;
    private long lastAmpRequestAt;
    private boolean specialPermissionWaiting;
    private boolean launchUpdateCheckStarted;
    private boolean appUpdatePromptShown;
    private boolean navigatorUpdatePromptShown;
    private boolean activityVisible;
    private AlertDialog updatePromptDialog;
    private boolean askedWriteSettings;
    private boolean askedOverlay;
    private boolean askedBatteryOptimization;
    private boolean askedNotificationListener;
    private long renderedTpmsAt = -1L;
    private boolean renderedTpmsAlerts;
    private String renderedTpmsWarningKey = "";
    private String dismissedInlineTpmsWarningKey = "";
    private int mainScrollY;
    private int settingsScrollY;

    private interface IntSetter {
        void set(int value);
    }

    private final Runnable rctaDemoLeft = new Runnable() {
        @Override
        public void run() {
            showRctaDemoAlert(true, false);
        }
    };
    private final Runnable rctaDemoRight = new Runnable() {
        @Override
        public void run() {
            showRctaDemoAlert(false, true);
        }
    };
    private final Runnable rctaDemoBoth = new Runnable() {
        @Override
        public void run() {
            showRctaDemoAlert(true, true);
        }
    };
    private final Runnable rctaDemoHide = new Runnable() {
        @Override
        public void run() {
            hideRctaDemoAlert();
        }
    };
    private final Runnable pendingStateRefresh = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                refresh();
            }
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && AppIds.ACTION_RCTA_DEMO.equals(intent.getAction())) {
                startRctaDemoSequence();
                return;
            }
            handler.removeCallbacks(pendingStateRefresh);
            handler.post(pendingStateRefresh);
        }
    };

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppSettings.applyDefaults(this);
        StateStore.restoreNavigation(this);
        appUpdater = new AppUpdateController(this);
        navigatorUpdater = new NavigatorUpdateController(this);
        firmwareUpdater = FirmwareUpdateController.get(this);
        setContentView(buildUi());
        applyImmersiveMode();
        AppService.start(this);
        requestRuntimePermissions();
        handler.postDelayed(this::maybeShowMediaProfileWizard, 900L);
        handler.postDelayed(this::checkUpdatesOnLaunch, 1600L);
        refresh();
    }

    private void checkUpdatesOnLaunch() {
        if (isFinishing() || isDestroyed() || launchUpdateCheckStarted) return;
        launchUpdateCheckStarted = true;
        AppLog.line(this, "Startup update check: Kia + Yandex");
        appUpdater.checkAsync();
        navigatorUpdater.checkAsync();
        handler.postDelayed(this::maybeShowLaunchUpdatePrompt, 1300L);
    }

    private void maybeShowLaunchUpdatePrompt() {
        if (!launchUpdateCheckStarted || !activityVisible || isFinishing() || isDestroyed()) return;
        if (updatePromptDialog != null && updatePromptDialog.isShowing()) return;
        UpdateState s = StateStore.updates();
        if (!appUpdatePromptShown && !s.appChecking && !s.appDownloading && s.appAvailable) {
            showLaunchUpdatePrompt(true, "Обновление Kia",
                    displayStatus(s.appStatus) + "\n\nНажмите Обновить, чтобы скачать APK и открыть установщик.");
            return;
        }
        if (!navigatorUpdatePromptShown
                && !s.navigatorChecking
                && !s.navigatorDownloading
                && !s.navigatorInstalling
                && s.navigatorAvailable) {
            showLaunchUpdatePrompt(false, "Обновление Yandex Navigator",
                    displayStatus(s.navigatorStatus) + "\n\nНажмите Обновить, чтобы скачать mod APK и открыть установщик.");
        }
    }

    private void showLaunchUpdatePrompt(boolean appUpdate, String title, String message) {
        if (appUpdate) appUpdatePromptShown = true;
        else navigatorUpdatePromptShown = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Обновить", (d, which) -> {
                    if (appUpdate) appUpdater.downloadAndInstall(this);
                    else navigatorUpdater.downloadAndInstall(this);
                    refresh();
                })
                .setNegativeButton("Позже", null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (updatePromptDialog == dialog) updatePromptDialog = null;
            handler.postDelayed(this::maybeShowLaunchUpdatePrompt, 500L);
        });
        updatePromptDialog = dialog;
        dialog.show();
    }

    private void maybeShowMediaProfileWizard() {
        if (isFinishing() || isDestroyed() || AppSettings.mediaProfileConfigured(this)) return;
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setView(mediaProfileWizardView(dialog), 0, 0, 0, 0);
        dialog.setOnCancelListener(d -> AppSettings.setMediaProfileConfigured(this, true));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.68f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            lp.width = Math.min(screenWidth - dp(36), dp(mediaProfileWizardWideLayout() ? 560 : 620));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(lp);
        }
    }

    private View mediaProfileWizardView(AlertDialog dialog) {
        boolean wide = mediaProfileWizardWideLayout();
        clearChoiceViews(mediaWizardProfileViews, mediaWizardProfileChecks);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(wide ? 18 : 22), dp(wide ? 16 : 18),
                dp(wide ? 18 : 22), dp(wide ? 18 : 22));
        panel.setBackground(round(COLOR_SETTINGS_PANEL, dp(8), COLOR_SETTINGS_DIVIDER, dp(1)));

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Профиль магнитолы", wide ? 16 : (isCompact() ? 18 : 20), Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        titleBox.addView(title);
        TextView hint = text("Выберите источник музыки для приборки. Потом можно изменить в настройках.",
                wide ? 10 : (isCompact() ? 11 : 12), COLOR_MUTED);
        hint.setIncludeFontPadding(false);
        hint.setMaxLines(2);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(2), 0, 0);
        titleBox.addView(hint, hintLp);
        head.addView(titleBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("×", wide ? 20 : 24, COLOR_MUTED);
        close.setGravity(Gravity.CENTER);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setClickable(true);
        close.setFocusable(true);
        close.setBackground(settingsButtonBackground(false));
        close.setOnClickListener(v -> closeMediaProfileWizard(dialog));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(wide ? 34 : 40),
                dp(wide ? 34 : 40));
        head.addView(close, closeLp);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.setMargins(0, 0, 0, dp(wide ? 4 : 6));
        panel.addView(head, headLp);

        if (wide) {
            panel.addView(mediaProfileWizardChoiceRow(dialog,
                    mediaProfileWizardChoice(dialog, AppSettings.MEDIA_PROFILE_TEYES,
                            "TEYES / CC4 Pro", "Музыка, радио и Bluetooth с магнитолы TEYES",
                            COLOR_ACCENT_BLUE),
                    mediaProfileWizardChoice(dialog, AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID,
                            "Universal Android", "Трек и источник из Android-приложений",
                            COLOR_ACCENT)));
            panel.addView(mediaProfileWizardChoiceRow(dialog,
                    mediaProfileWizardChoice(dialog, AppSettings.MEDIA_PROFILE_UART_REAL,
                            "UART real + Android", "Магнитола ведет режим, Kia показывает трек",
                            COLOR_WARNING),
                    mediaProfileWizardChoice(dialog, AppSettings.MEDIA_PROFILE_OFF,
                            "Media выключено", "Не отправлять музыку на приборку",
                            COLOR_MUTED)));
        } else {
            panel.addView(mediaProfileWizardChoice(dialog, AppSettings.MEDIA_PROFILE_TEYES,
                    "TEYES / CC4 Pro", "Музыка, радио и Bluetooth с магнитолы TEYES",
                    COLOR_ACCENT_BLUE));
            panel.addView(mediaProfileWizardChoice(dialog, AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID,
                    "Universal Android", "Трек и источник из Android-приложений",
                    COLOR_ACCENT));
            panel.addView(mediaProfileWizardChoice(dialog, AppSettings.MEDIA_PROFILE_UART_REAL,
                    "UART real + Android", "Магнитола ведет режим, Kia показывает трек",
                    COLOR_WARNING));
            panel.addView(mediaProfileWizardChoice(dialog, AppSettings.MEDIA_PROFILE_OFF,
                    "Media выключено", "Не отправлять музыку на приборку",
                    COLOR_MUTED));
        }
        Space bottomGuard = new Space(this);
        panel.addView(bottomGuard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        return panel;
    }

    private LinearLayout mediaProfileWizardChoiceRow(AlertDialog dialog, View left, View right) {
        LinearLayout line = row();
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.setMargins(0, dp(6), dp(5), dp(4));
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.setMargins(dp(5), dp(6), 0, dp(4));
        line.addView(left, leftLp);
        line.addView(right, rightLp);
        return line;
    }

    private View mediaProfileWizardChoice(AlertDialog dialog, int profile, String title,
                                          String hint, int color) {
        boolean wide = mediaProfileWizardWideLayout();
        boolean selected = AppSettings.mediaProfile(this) == profile;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(wide ? 14 : 16), dp(wide ? 8 : 12),
                dp(wide ? 14 : 16), dp(wide ? 8 : 12));
        item.setMinimumHeight(dp(wide ? 58 : 78));
        item.setClickable(true);
        item.setFocusable(true);
        item.setBackground(settingsButtonBackground(selected));

        LinearLayout header = row();
        TextView name = text(title, wide ? 12 : (isCompact() ? 15 : 16), Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(false);
        name.setMaxLines(1);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(name, nameLp);
        TextView check = text("выбрано", wide ? 10 : 11, selected ? COLOR_ACCENT_BLUE : COLOR_MUTED);
        check.setGravity(Gravity.CENTER);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        header.addView(check);

        TextView sub = text(hint, wide ? 9 : (isCompact() ? 11 : 12), COLOR_MUTED);
        sub.setMaxLines(2);
        item.addView(header);
        item.addView(sub);

        int index = mediaWizardProfileIndex(profile);
        if (index >= 0) {
            mediaWizardProfileViews[index] = item;
            mediaWizardProfileChecks[index] = check;
        }
        item.setOnClickListener(v -> {
            setMediaProfile(profile);
            updateMediaWizardChoices(profile);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(4));
        item.setLayoutParams(lp);
        return item;
    }

    private void closeMediaProfileWizard(AlertDialog dialog) {
        AppSettings.setMediaProfileConfigured(this, true);
        dialog.dismiss();
    }

    private void updateMediaWizardChoices(int selectedProfile) {
        int selectedIndex = mediaWizardProfileIndex(selectedProfile);
        for (int i = 0; i < mediaWizardProfileViews.length; i++) {
            View view = mediaWizardProfileViews[i];
            TextView check = mediaWizardProfileChecks[i];
            if (view == null || check == null) continue;
            boolean selected = i == selectedIndex;
            view.setBackground(settingsButtonBackground(selected));
            check.setTextColor(selected ? COLOR_ACCENT_BLUE : COLOR_MUTED);
            check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private int mediaWizardProfileIndex(int profile) {
        return profile >= 0 && profile < mediaWizardProfileViews.length ? profile : -1;
    }

    private boolean mediaProfileWizardWideLayout() {
        return isLandscapeWindow() || screenWidthDp() >= 620;
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;
        IntentFilter filter = new IntentFilter(AppIds.ACTION_STATE_CHANGED);
        filter.addAction(AppIds.ACTION_RCTA_DEMO);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        handler.post(refreshTick);
        normalizeModeForWindow();
        TpmsController.get(this).setForegroundActive(true, tpmsWidgetMode());
        TpmsWarningOverlayController.get(this).setActivityVisible(true);
        NavigationOverlayController.get(this).apply();
        applyImmersiveMode();
        if ((settingsMode || selectedTab == TAB_SETTINGS) && tabContent != null) renderTab();
        if (specialPermissionWaiting) {
            specialPermissionWaiting = false;
            handler.postDelayed(this::requestStartupSpecialPermissions, 700L);
        }
    }

    @Override
    protected void onPause() {
        activityVisible = false;
        handler.removeCallbacks(refreshTick);
        handler.removeCallbacks(pendingStateRefresh);
        cancelRctaDemoSequence();
        hideRctaDemoAlert();
        TpmsController.get(this).setForegroundActive(false, false);
        TpmsWarningOverlayController.get(this).setActivityVisible(false);
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        normalizeModeForWindow();
        TpmsController.get(this).setForegroundActive(true, tpmsWidgetMode());
        renderTab();
        refresh();
        applyImmersiveMode();
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        normalizeModeForWindow();
        TpmsController.get(this).setForegroundActive(true, tpmsWidgetMode());
        renderTab();
        refresh();
        applyImmersiveMode();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10 || requestCode == 11) {
            AppService.start(this);
            if (requestCode == 11 || !requestBackgroundLocationPermission()) {
                handler.postDelayed(this::requestStartupSpecialPermissions, 700L);
            }
            refresh();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FIRMWARE_FILE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            AppLog.line(this, "Firmware file: selection cancelled");
            refresh();
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        String label = firmwareFileName(uri);
        AppLog.line(this, "Firmware file selected: " + label);
        pendingFirmwareUri = uri;
        pendingFirmwareLabel = label;
        renderTab();
        refresh();
    }

    private View buildUi() {
        screenFrame = new FrameLayout(this);
        screenFrame.setBackgroundColor(COLOR_BG);
        renderTab();
        return screenFrame;
    }

    private void startRctaDemoSequence() {
        cancelRctaDemoSequence();
        settingsMode = false;
        selectedTab = TAB_TPMS;
        renderTab();
        refresh();
        handler.postDelayed(rctaDemoLeft, 250L);
        handler.postDelayed(rctaDemoRight, 5250L);
        handler.postDelayed(rctaDemoBoth, 10250L);
        handler.postDelayed(rctaDemoHide, 20250L);
        AppLog.line(this, "RCTA demo: left 5s, right 5s, both 10s");
    }

    private void cancelRctaDemoSequence() {
        handler.removeCallbacks(rctaDemoLeft);
        handler.removeCallbacks(rctaDemoRight);
        handler.removeCallbacks(rctaDemoBoth);
        handler.removeCallbacks(rctaDemoHide);
    }

    private void showRctaDemoAlert(boolean left, boolean right) {
        showRctaDemoAlert(left, right, false);
    }

    private void showRctaDemoAlert(boolean left, boolean right, boolean forceSound) {
        if (screenFrame == null) return;
        if (rctaDemoOverlay == null) {
            rctaDemoOverlay = new BlindSpotOverlayView(this);
            rctaDemoOverlay.setClickable(false);
        }
        rctaDemoOverlay.setStyleType(AppSettings.rctaStyle(this));
        rctaDemoOverlay.setAlertColor(AppSettings.rctaColor(this));
        rctaDemoOverlay.setBackgroundAlpha(AppSettings.rctaBackgroundAlpha(this));
        rctaDemoOverlay.setArrowCount(AppSettings.rctaArrowCount(this));
        rctaDemoOverlay.setPreview(left, right, false);
        if (rctaDemoOverlay.getParent() == null) {
            screenFrame.addView(rctaDemoOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            rctaDemoOverlay.bringToFront();
        }
        rctaDemoOverlay.postInvalidateOnAnimation();
        startRctaSound(forceSound);
    }

    private void hideRctaDemoAlert() {
        if (rctaDemoOverlay != null && rctaDemoOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) rctaDemoOverlay.getParent()).removeView(rctaDemoOverlay);
        }
        rctaDemoOverlay = null;
        stopRctaSound();
    }

    private void startRctaSound() {
        startRctaSound(false);
    }

    private void startRctaSound(boolean force) {
        if (!force && !AppSettings.rctaSoundEnabled(this)) {
            stopRctaSound();
            return;
        }
        if (rctaPlayer != null) {
            if (!rctaPlayer.isPlaying()) {
                try {
                    rctaPlayer.start();
                } catch (Exception ignored) {
                    stopRctaSound();
                }
            }
            return;
        }
        try {
            rctaPlayer = MediaPlayer.create(this, R.raw.rcta_warning);
            if (rctaPlayer == null) return;
            rctaPlayer.setLooping(true);
            rctaPlayer.start();
        } catch (Exception e) {
            stopRctaSound();
            AppLog.line(this, "RCTA sound failed " + e.getClass().getSimpleName());
        }
    }

    private void stopRctaSound() {
        if (rctaPlayer == null) return;
        try {
            rctaPlayer.stop();
        } catch (Exception ignored) {
        }
        try {
            rctaPlayer.release();
        } catch (Exception ignored) {
        }
        rctaPlayer = null;
    }

    private void applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void prepareContentHost(boolean fixedScreen) {
        if (screenFrame == null) return;
        screenFrame.removeAllViews();
        activeScrollView = null;
        screenFrame.setBackgroundColor(settingsMode ? COLOR_SETTINGS_BG : COLOR_BG);
        LinearLayout root = new LinearLayout(this);
        rootLayout = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(settingsMode ? COLOR_SETTINGS_BG : COLOR_BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            rootInsetTop = 0;
            rootInsetBottom = 0;
            applyRootPadding();
            return insets;
        });
        applyRootPadding();

        if (settingsMode) {
            tabContent = new LinearLayout(this);
            tabContent.setOrientation(LinearLayout.VERTICAL);
            screenFrame.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        tabContent = new LinearLayout(this);
        tabContent.setOrientation(LinearLayout.VERTICAL);
        root.addView(tabContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                fixedScreen ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT));

        if (fixedScreen) {
            screenFrame.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.setClipToPadding(true);
        scroll.setPadding(0, 0, 0, dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        screenFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        restoreScrollPosition(scroll, false);
    }

    private View tabBar() {
        tabButtons = new TextView[6];
        LinearLayout row = row();
        row.setPadding(dp(5), dp(5), dp(5), dp(5));
        row.setBackground(glassPanel(COLOR_ACCENT_BLUE));
        if (AppSettings.mediaTabVisible(this)) addTab(row, TAB_MEDIA, "Медиа");
        addTab(row, TAB_NAVIGATION, "Нави");
        addTab(row, TAB_CANBUS, "CAN");
        addTab(row, TAB_SETTINGS, "Общее");
        if (AppSettings.logTabVisible(this)) addTab(row, TAB_LOG, "Диаг.");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(14));
        row.setLayoutParams(lp);
        return row;
    }

    private View settingsHeader() {
        LinearLayout panel = row();
        panel.setPadding(dp(16), dp(12), dp(12), dp(12));
        panel.setBackground(glassPanel(COLOR_ACCENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        panel.setLayoutParams(lp);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("KIA", isCompact() ? 22 : 27, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView subtitle = text("Настройки  ·  kia canbus", isCompact() ? 12 : 14, COLOR_MUTED);
        titleBox.addView(title);
        titleBox.addView(subtitle);
        panel.addView(titleBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = text("×", isCompact() ? 28 : 32, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setClickable(true);
        close.setFocusable(true);
        close.setBackground(glassButton(COLOR_PANEL_SOFT));
        close.setOnClickListener(v -> {
            selectedTab = TAB_TPMS;
            renderTab();
            refresh();
        });
        panel.addView(close, new LinearLayout.LayoutParams(
                isCompact() ? dp(54) : dp(62), isCompact() ? dp(48) : dp(54)));
        return panel;
    }

    private LinearLayout mediaRadioStationPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Радиостанции",
                "Kia хранит названия по частоте и заполняет новые частоты автоматически",
                COLOR_ACCENT_BLUE);
        TextView summary = text(RadioStationStore.summary(this, 5), isCompact() ? 13 : 15,
                Color.rgb(235, 241, 246));
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.setMargins(0, dp(12), 0, 0);
        panel.addView(summary, summaryLp);

        MediaState media = StateStore.media();
        String frequency = RadioStationStore.currentFrequency(media);
        String band = RadioStationStore.currentBand(media);
        String currentHint = frequency.isEmpty()
                ? "включите радио, чтобы появилась частота"
                : band + " " + frequency + " -> " + emptyDash(media.title);
        addActionGrid(panel,
                action("Текущая", currentHint, frequency.isEmpty() ? COLOR_MUTED : COLOR_ACCENT_BLUE,
                        this::showCurrentRadioStationDialog),
                action("Список", "все сохранённые частоты", COLOR_ACCENT_BLUE,
                        this::showRadioStationListDialog));
        return panel;
    }

    private View mainGearButton() {
        LinearLayout row = row();
        row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(lp);
        TextView settings = text("⚙", isCompact() ? 25 : 29, Color.WHITE);
        settings.setGravity(Gravity.CENTER);
        settings.setTypeface(Typeface.DEFAULT_BOLD);
        settings.setClickable(true);
        settings.setFocusable(true);
        settings.setBackground(glassButton(COLOR_PANEL_SOFT));
        settings.setOnClickListener(v -> {
            settingsMode = true;
            settingsTab = SETTINGS_TPMS;
            renderTab();
            refresh();
        });
        row.addView(settings, new LinearLayout.LayoutParams(
                isCompact() ? dp(54) : dp(62), isCompact() ? dp(48) : dp(54)));
        return row;
    }

    private void addTab(LinearLayout row, int tab, String title) {
        TextView view = text(iconForTab(tab) + "  " + title, isCompact() ? 15 : 17, Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setClickable(true);
        view.setFocusable(true);
        view.setSingleLine(true);
        view.setOnClickListener(v -> {
            selectedTab = tab;
            renderTab();
            refresh();
        });
        tabButtons[tab] = view;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                isCompact() ? dp(56) : dp(66), 1f);
        lp.setMargins(tab == TAB_MEDIA ? 0 : dp(4), 0, dp(4), 0);
        row.addView(view, lp);
    }

    private void renderTab() {
        if (screenFrame == null) return;
        rememberScrollPosition();
        if (selectedTab == TAB_MEDIA && !AppSettings.mediaTabVisible(this)) selectedTab = TAB_TPMS;
        if (selectedTab == TAB_LOG && !AppSettings.logTabVisible(this)) selectedTab = TAB_SETTINGS;
        clearTabViews();
        prepareContentHost(!settingsMode && selectedTab == TAB_TPMS);
        if (settingsMode) {
            renderSettingsShell();
            restoreRctaDiagnosticsOverlay();
            return;
        }
        if (selectedTab != TAB_TPMS) {
            tabContent.addView(settingsHeader());
            tabContent.addView(tabBar());
        }
        updateTabBar();
        switch (selectedTab) {
            case TAB_TPMS:
                renderTpmsTab();
                break;
            case TAB_MEDIA:
                renderMediaTab();
                break;
            case TAB_NAVIGATION:
                renderNavigationTab();
                break;
            case TAB_CANBUS:
                renderCanbusTab();
                break;
            case TAB_LOG:
                renderLogTab();
                break;
            case TAB_SETTINGS:
                renderSettingsTab();
                break;
            default:
                renderTpmsTab();
                break;
        }
        restoreRctaDiagnosticsOverlay();
    }

    private void restoreRctaDiagnosticsOverlay() {
        if (!rctaDiagnosticsActive) return;
        showRctaDemoAlert(true, true, true);
    }

    private void rememberScrollPosition() {
        if (activeScrollView == null) return;
        if (settingsMode) settingsScrollY = activeScrollView.getScrollY();
        else mainScrollY = activeScrollView.getScrollY();
    }

    private void restoreScrollPosition(ScrollView scroll, boolean settings) {
        activeScrollView = scroll;
        final int target = settings ? settingsScrollY : mainScrollY;
        if (target <= 0) return;
        scroll.scrollTo(0, target);
        scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (scroll.getViewTreeObserver().isAlive()) {
                    scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                restoreScrollY(scroll, target);
                return true;
            }
        });
        scroll.post(() -> restoreScrollY(scroll, target));
        scroll.postDelayed(() -> restoreScrollY(scroll, target), 80L);
    }

    private void restoreScrollY(ScrollView scroll, int target) {
        int max = 0;
        if (scroll.getChildCount() > 0) {
            max = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
        }
        scroll.scrollTo(0, Math.min(target, max));
    }

    private void clearTabViews() {
        status = null;
        tpmsStatus = null;
        tpmsDashboard = null;
        mediaTabToggle = null;
        callEnabledToggle = null;
        mediaDebugToggle = null;
        mediaDebugStatus = null;
        navigationStatus = null;
        navigationDebugStatus = null;
        navigationDebugToggle = null;
        navigationOverlayToggle = null;
        microManeuverToggle = null;
        finishCompassAutoToggle = null;
        navTbtToggle = null;
        overspeedToggle = null;
        tpmsAlertsToggle = null;
        tpmsSoundToggle = null;
        autoStartToggle = null;
        logTabToggle = null;
        diagnosticsStatus = null;
        canbusDebugStatus = null;
        canbusDebugToggle = null;
        ampEnabledToggle = null;
        ampVisualizer = null;
        ampSummary = null;
        loggerStatus = null;
        rawCanToggle = null;
        updatesStatus = null;
        firmwareStatus = null;
        firmwareActionButton = null;
        firmwareProgressFill = null;
        firmwareActionText = null;
        firmwareActionHintText = null;
        log = null;
        permissionSummary = null;
        sasRatioStatus = null;
        sasRatioInput = null;
        sasRatioPreview = null;
        clearChoiceViews(navSourceModeViews, navSourceModeChecks);
        clearChoiceViews(navTextModeViews, navTextModeChecks);
        clearChoiceViews(navRouteModeViews, navRouteModeChecks);
        rctaPreview = null;
        ampInputs.clear();
        tpmsInputs.clear();
        tabButtons = null;
    }

    private void updateTabBar() {
        if (tabButtons == null) return;
        for (int i = 0; i < tabButtons.length; i++) {
            TextView tab = tabButtons[i];
            if (tab == null) continue;
            boolean selected = i == selectedTab;
            tab.setTextColor(selected ? Color.WHITE : COLOR_MUTED);
            tab.setBackground(selected
                    ? glassButton(COLOR_ACCENT)
                    : round(Color.TRANSPARENT, dp(7), Color.TRANSPARENT, 0));
        }
    }

    private void renderTpmsTab() {
        TpmsState state = StateStore.tpms();
        rememberTpmsRenderState(state);
        tabContent.addView(tpmsKia130Main(state), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void rememberTpmsRenderState(TpmsState state) {
        TpmsState safe = state == null ? TpmsState.empty() : state;
        renderedTpmsAt = safe.updatedAt;
        renderedTpmsAlerts = AppSettings.tpmsAlertsEnabled(this);
        renderedTpmsWarningKey = TpmsAlertController.warningKey(this, safe);
    }

    private boolean shouldRerenderTpms() {
        if (settingsMode || selectedTab != TAB_TPMS || tabContent == null) return false;
        TpmsState state = StateStore.tpms();
        if (state.updatedAt != renderedTpmsAt) return true;
        if (AppSettings.tpmsAlertsEnabled(this) != renderedTpmsAlerts) return true;
        return !TpmsAlertController.warningKey(this, state).equals(renderedTpmsWarningKey);
    }

    private View tpmsKia130Main(TpmsState state) {
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        stage.setBackgroundColor(Color.rgb(13, 15, 19));

        TpmsDashboardView dashboard = new TpmsDashboardView(this);
        tpmsDashboard = dashboard;
        dashboard.setState(state);
        dashboard.setWidgetMode(tpmsWidgetMode());
        dashboard.setMotionSpeedKmh(navigationSpeedKmh());
        dashboard.setNavigationState(StateStore.navigation());
        dashboard.setVehicleState(StateStore.vehicle());
        stage.addView(dashboard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String key = TpmsAlertController.warningKey(this, state);
        boolean showBanner = key.length() > 0 && !key.equals(dismissedInlineTpmsWarningKey);
        if (showBanner) {
            stage.addView(tpmsKia130WarningBanner(state, key), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(75), Gravity.TOP));
        } else if (!tpmsWidgetLocksSettings()) {
            int gearSize = isCompact() ? dp(36) : dp(48);
            FrameLayout.LayoutParams gearLp = new FrameLayout.LayoutParams(gearSize, gearSize,
                    Gravity.RIGHT | Gravity.TOP);
            gearLp.setMargins(0, isCompact() ? dp(8) : dp(16), isCompact() ? dp(8) : dp(18), 0);
            stage.addView(tpmsKia130GearButton(), gearLp);
        }
        return stage;
    }

    private View tpmsKia130WarningBanner(TpmsState state, String key) {
        FrameLayout banner = new FrameLayout(this);
        banner.setBackground(gradient(Color.rgb(255, 102, 116), Color.rgb(255, 36, 93),
                Color.TRANSPARENT, 0, 0));
        banner.setClickable(true);

        ImageView sign = new ImageView(this);
        sign.setImageResource(R.drawable.tpms_overlay_logo);
        sign.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams signLp = new FrameLayout.LayoutParams(dp(62), dp(62),
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        signLp.leftMargin = dp(32);
        banner.addView(sign, signLp);

        TextView title = text(tpmsKia130WarningTitle(state), 27, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setTypeface(Typeface.DEFAULT);
        title.setShadowLayer(dp(2), dp(1), dp(1), Color.argb(170, 0, 0, 0));
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        titleLp.leftMargin = dp(126);
        titleLp.rightMargin = dp(126);
        banner.addView(title, titleLp);

        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.tpms_warning_close);
        close.setScaleType(ImageView.ScaleType.FIT_CENTER);
        close.setClickable(true);
        close.setFocusable(true);
        close.setOnClickListener(v -> {
            dismissedInlineTpmsWarningKey = key;
            TpmsWarningOverlayController.get(this).dismissCurrent();
            renderTab();
            refresh();
        });
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(43), dp(43),
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        closeLp.rightMargin = dp(24);
        banner.addView(close, closeLp);
        return banner;
    }

    private View tpmsKia130GearButton() {
        ImageView settings = new ImageView(this);
        settings.setImageResource(R.drawable.ic_tpms_settings);
        settings.setColorFilter(Color.WHITE);
        settings.setScaleType(ImageView.ScaleType.CENTER);
        settings.setPadding(dp(11), dp(11), dp(11), dp(11));
        settings.setClickable(true);
        settings.setFocusable(true);
        settings.setBackground(tpmsGearBackground());
        settings.setOnClickListener(v -> {
            if (tpmsWidgetLocksSettings()) return;
            settingsMode = true;
            settingsTab = SETTINGS_TPMS;
            renderTab();
            refresh();
        });
        return settings;
    }

    private GradientDrawable tpmsGearBackground() {
        return gradient(Color.argb(132, 18, 23, 30), Color.argb(98, 48, 60, 74),
                Color.argb(132, 244, 241, 234), dp(1), dp(40));
    }

    private String tpmsKia130WarningTitle(TpmsState state) {
        int wheel = firstWarningWheel(state);
        int warning = wheel >= 0 ? TpmsAlertController.warningState(this, state, wheel)
                : TpmsAlertController.WARNING_NONE;
        return tpmsKia130WheelLabel(wheel) + ": " + TpmsAlertController.warningText(warning).toLowerCase(Locale.ROOT);
    }

    private int firstWarningWheel(TpmsState state) {
        if (state == null) return -1;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            if (TpmsAlertController.warningState(this, state, wheel) != TpmsAlertController.WARNING_NONE) {
                return wheel;
            }
        }
        return -1;
    }

    private String tpmsKia130WheelLabel(int wheel) {
        switch (wheel) {
            case TpmsState.WHEEL_FL:
                return "Переднее левое";
            case TpmsState.WHEEL_FR:
                return "Переднее правое";
            case TpmsState.WHEEL_RL:
                return "Заднее левое";
            case TpmsState.WHEEL_RR:
                return "Заднее правое";
            default:
                return "TPMS";
        }
    }

    private View tpmsLegacyKiaMain(TpmsState state) {
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        stage.setBackgroundColor(Color.rgb(7, 10, 14));
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        stage.setLayoutParams(stageLp);

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(dp(18), dp(12), dp(18), dp(12));
        title.setBackground(legacyPanelBackground(COLOR_ACCENT));
        TextView brand = text("KIA", tpmsLegacyCompact() ? 24 : 30, Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = text("TPMS", tpmsLegacyCompact() ? 12 : 13, COLOR_MUTED);
        sub.setTypeface(Typeface.DEFAULT_BOLD);
        title.addView(brand);
        title.addView(sub);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                tpmsLegacyCompact() ? dp(126) : dp(154), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP);
        titleLp.setMargins(dp(34), dp(24), 0, 0);
        stage.addView(title, titleLp);

        FrameLayout.LayoutParams gearLp = new FrameLayout.LayoutParams(
                tpmsLegacyCompact() ? dp(56) : dp(64),
                tpmsLegacyCompact() ? dp(50) : dp(58),
                Gravity.RIGHT | Gravity.TOP);
        gearLp.setMargins(0, dp(24), dp(34), 0);
        stage.addView(tpmsLegacyGearButton(), gearLp);

        LinearLayout content = new LinearLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setGravity(Gravity.CENTER);
        content.setOrientation(tpmsLegacyCompact() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int padH = tpmsLegacyCompact() ? dp(18) : dp(62);
        content.setPadding(padH, tpmsLegacyCompact() ? dp(94) : dp(124),
                padH, tpmsLegacyCompact() ? dp(26) : dp(54));
        stage.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (tpmsLegacyCompact()) {
            content.addView(tpmsLegacyCarPanel(state), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.82f));
            content.addView(tpmsLegacyWheelGrid(state), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            content.addView(tpmsLegacyWheelColumn(state, TpmsState.WHEEL_FL, TpmsState.WHEEL_RL),
                    tpmsLegacyColumnLayout(true));
            content.addView(tpmsLegacyCarPanel(state), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.82f));
            content.addView(tpmsLegacyWheelColumn(state, TpmsState.WHEEL_FR, TpmsState.WHEEL_RR),
                    tpmsLegacyColumnLayout(false));
        }
        return stage;
    }

    private LinearLayout.LayoutParams tpmsLegacyColumnLayout(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(left ? 0 : dp(34), 0, left ? dp(34) : 0, 0);
        return lp;
    }

    private LinearLayout tpmsLegacyWheelColumn(TpmsState state, int topWheel, int bottomWheel) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setClipChildren(false);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        topLp.setMargins(0, 0, 0, dp(14));
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bottomLp.setMargins(0, dp(14), 0, 0);
        column.addView(tpmsLegacyWheelCard(state, topWheel), topLp);
        column.addView(tpmsLegacyWheelCard(state, bottomWheel), bottomLp);
        return column;
    }

    private LinearLayout tpmsLegacyWheelGrid(TpmsState state) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout line = row();
            int first = rowIndex * 2;
            line.addView(tpmsLegacyWheelCard(state, first), tpmsLegacyGridLayout(true, rowIndex == 0));
            line.addView(tpmsLegacyWheelCard(state, first + 1), tpmsLegacyGridLayout(false, rowIndex == 0));
            grid.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return grid;
    }

    private LinearLayout.LayoutParams tpmsLegacyGridLayout(boolean left, boolean top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(left ? 0 : dp(6), top ? 0 : dp(6), left ? dp(6) : 0, top ? dp(6) : 0);
        return lp;
    }

    private View tpmsLegacyWheelCard(TpmsState state, int wheel) {
        boolean known = state != null && state.known != null
                && wheel >= 0 && wheel < state.known.length && state.known[wheel];
        int warning = TpmsAlertController.warningState(this, state, wheel);
        boolean bad = warning != TpmsAlertController.WARNING_NONE;
        int accent = bad ? warningColor(warning) : COLOR_ACCENT;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClipChildren(false);
        card.setPadding(dp(22), dp(18), dp(22), dp(18));
        card.setBackground(legacyPanelBackground(accent));

        LinearLayout header = row();
        header.addView(tpmsLegacyBadge(wheelName(wheel), accent));
        TextView label = text(wheelFullName(wheel), tpmsLegacyCompact() ? 30 : 20, COLOR_TEXT);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMargins(dp(12), 0, dp(10), 0);
        header.addView(label, labelLp);
        header.addView(tpmsLegacyStatusPill(known, warning));
        card.addView(header);

        LinearLayout value = row();
        value.setGravity(Gravity.LEFT | Gravity.BOTTOM);
        TextView pressure = text(known ? pressureBarValue(state.pressureKpa[wheel]) : "--.--",
                tpmsLegacyCompact() ? 62 : 54, Color.WHITE);
        pressure.setTypeface(Typeface.DEFAULT_BOLD);
        pressure.setIncludeFontPadding(false);
        value.addView(pressure);
        TextView unit = text("bar", tpmsLegacyCompact() ? 26 : 18, COLOR_MUTED);
        unit.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        unitLp.setMargins(dp(10), 0, 0, dp(7));
        value.addView(unit, unitLp);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.setMargins(0, tpmsLegacyCompact() ? dp(14) : dp(22), 0, 0);
        card.addView(value, valueLp);

        String metaText = known ? state.temperatureC[wheel] + "C" : "--C";
        String detail = bad ? TpmsAlertController.warningText(warning) : (known ? "норма" : "нет данных");
        TextView meta = text(metaText + "  /  " + detail,
                tpmsLegacyCompact() ? 22 : 15, bad ? accent : COLOR_MUTED);
        meta.setTypeface(Typeface.DEFAULT_BOLD);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.setMargins(0, dp(7), 0, 0);
        card.addView(meta, metaLp);
        return card;
    }

    private TextView tpmsLegacyBadge(String value, int color) {
        TextView badge = text(value, tpmsLegacyCompact() ? 28 : 18, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setMinWidth(tpmsLegacyCompact() ? dp(68) : dp(48));
        badge.setMinHeight(tpmsLegacyCompact() ? dp(48) : dp(40));
        badge.setPadding(dp(4), dp(2), dp(4), dp(2));
        badge.setBackground(gradient(softColor(color, 150), softColor(COLOR_PANEL_SOFT, 175),
                softColor(Color.WHITE, 36), dp(1), dp(8)));
        return badge;
    }

    private TextView tpmsLegacyStatusPill(boolean known, int warning) {
        String value = !known ? "нет"
                : warning == TpmsAlertController.WARNING_NONE ? "ok"
                : TpmsAlertController.warningText(warning);
        int color = !known ? COLOR_MUTED : warningColor(warning);
        TextView pill = text(value, tpmsLegacyCompact() ? 20 : 13, Color.WHITE);
        pill.setGravity(Gravity.CENTER);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setSingleLine(true);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(round(softColor(color, warning == TpmsAlertController.WARNING_NONE ? 88 : 118),
                dp(8), softColor(Color.WHITE, 30), dp(1)));
        return pill;
    }

    private LinearLayout tpmsLegacyCarPanel(TpmsState state) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        FrameLayout carWrap = new FrameLayout(this);
        carWrap.setClipChildren(false);
        carWrap.setClipToPadding(false);
        TpmsDashboardView car = new TpmsDashboardView(this);
        car.setState(state);
        FrameLayout.LayoutParams carLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        carWrap.addView(car, carLp);
        panel.addView(carWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = row();
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(12), 0, 0);
        footer.addView(tpmsLegacyFooterChip(tpmsCenterText(state),
                TpmsAlertController.hasWarnings(this, state) ? COLOR_DANGER : COLOR_ACCENT));
        footer.addView(tpmsLegacyFooterChip("RX " + timeText(state == null ? 0L : state.updatedAt),
                COLOR_ACCENT_BLUE));
        panel.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private TextView tpmsLegacyFooterChip(String value, int color) {
        TextView chip = text(value, tpmsLegacyCompact() ? 11 : 13, Color.WHITE);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setBackground(round(softColor(color, 104), dp(8), softColor(Color.WHITE, 28), dp(1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(5), 0, dp(5), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    private TextView tpmsLegacyGearButton() {
        TextView settings = text("⚙", tpmsLegacyCompact() ? 25 : 29, Color.WHITE);
        settings.setGravity(Gravity.CENTER);
        settings.setTypeface(Typeface.DEFAULT_BOLD);
        settings.setClickable(true);
        settings.setFocusable(true);
        settings.setBackground(legacyPanelBackground(COLOR_ACCENT_BLUE));
        settings.setOnClickListener(v -> {
            settingsMode = true;
            settingsTab = SETTINGS_TPMS;
            renderTab();
            refresh();
        });
        return settings;
    }

    private GradientDrawable legacyPanelBackground(int tint) {
        return gradient(softColor(Color.rgb(13, 22, 30), 238),
                softColor(Color.rgb(20, 27, 36), 238),
                softColor(tint, 90), dp(1), dp(8));
    }

    private boolean tpmsLegacyCompact() {
        return screenWidthDp() < 900;
    }

    private View tpmsLiveMain(TpmsState state) {
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        stage.setBackground(tpmsMainBackground());

        LinearLayout content = new LinearLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setGravity(Gravity.CENTER);
        content.setOrientation(tpmsNarrowLayout() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int padH = tpmsNarrowLayout() ? dp(20) : dp(54);
        content.setPadding(padH, dp(88), padH, dp(42));
        stage.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (tpmsNarrowLayout()) {
            content.addView(tpmsLiveCarPanel(state), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.92f));
            content.addView(tpmsLiveWheelGrid(state), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            content.addView(tpmsLiveWheelColumn(state, TpmsState.WHEEL_FL, TpmsState.WHEEL_RL),
                    tpmsLiveColumnLayout(true));
            content.addView(tpmsLiveCarPanel(state), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.96f));
            content.addView(tpmsLiveWheelColumn(state, TpmsState.WHEEL_FR, TpmsState.WHEEL_RR),
                    tpmsLiveColumnLayout(false));
        }

        FrameLayout.LayoutParams gearLp = new FrameLayout.LayoutParams(
                tpmsNarrowLayout() ? dp(54) : dp(66), tpmsNarrowLayout() ? dp(50) : dp(60),
                Gravity.RIGHT | Gravity.TOP);
        gearLp.setMargins(0, dp(24), dp(34), 0);
        stage.addView(tpmsLiveGearButton(), gearLp);
        return stage;
    }

    private GradientDrawable tpmsMainBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.rgb(3, 7, 12),
                        Color.rgb(7, 18, 26),
                        Color.rgb(13, 23, 28)
                });
        drawable.setCornerRadius(0);
        return drawable;
    }

    private LinearLayout.LayoutParams tpmsLiveColumnLayout(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.78f);
        lp.setMargins(left ? 0 : dp(28), 0, left ? dp(28) : 0, 0);
        return lp;
    }

    private LinearLayout tpmsLiveWheelColumn(TpmsState state, int topWheel, int bottomWheel) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setClipChildren(false);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        topLp.setMargins(0, 0, 0, dp(14));
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bottomLp.setMargins(0, dp(14), 0, 0);
        column.addView(tpmsLiveWheelCard(state, topWheel), topLp);
        column.addView(tpmsLiveWheelCard(state, bottomWheel), bottomLp);
        return column;
    }

    private LinearLayout tpmsLiveWheelGrid(TpmsState state) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = row();
            row.setClipChildren(false);
            int first = rowIndex * 2;
            row.addView(tpmsLiveWheelCard(state, first), tpmsLiveGridCardLayout(true, rowIndex == 0));
            row.addView(tpmsLiveWheelCard(state, first + 1), tpmsLiveGridCardLayout(false, rowIndex == 0));
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return grid;
    }

    private LinearLayout.LayoutParams tpmsLiveGridCardLayout(boolean left, boolean top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(left ? 0 : dp(7), top ? 0 : dp(7), left ? dp(7) : 0, top ? dp(7) : 0);
        return lp;
    }

    private View tpmsLiveWheelCard(TpmsState state, int wheel) {
        boolean known = state != null && state.known != null
                && wheel >= 0 && wheel < state.known.length && state.known[wheel];
        int warning = TpmsAlertController.warningState(this, state, wheel);
        int color = warningColor(warning);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClipChildren(false);
        card.setPadding(dp(24), dp(20), dp(24), dp(18));
        card.setBackground(tpmsGlassSurface(color, warning != TpmsAlertController.WARNING_NONE));

        LinearLayout header = row();
        header.addView(tpmsWheelDot(wheel, color));
        TextView label = text(wheelFullName(wheel), tpmsSmallText(), COLOR_TEXT);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMargins(dp(12), 0, dp(8), 0);
        header.addView(label, labelLp);
        header.addView(tpmsStatePill(known, warning));
        card.addView(header);

        LinearLayout valueRow = row();
        valueRow.setGravity(Gravity.LEFT | Gravity.BOTTOM);
        TextView pressure = text(known ? pressureBarValue(state.pressureKpa[wheel]) : "--.--",
                tpmsPressureText(), Color.WHITE);
        pressure.setTypeface(Typeface.DEFAULT_BOLD);
        pressure.setIncludeFontPadding(false);
        valueRow.addView(pressure);
        TextView unit = text("bar", tpmsUnitText(), COLOR_MUTED);
        unit.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        unitLp.setMargins(dp(10), 0, 0, dp(7));
        valueRow.addView(unit, unitLp);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.setMargins(0, dp(22), 0, 0);
        card.addView(valueRow, valueLp);

        String temp = known ? state.temperatureC[wheel] + "C" : "--C";
        String detail = warning == TpmsAlertController.WARNING_NONE
                ? (known ? "норма" : "нет данных")
                : TpmsAlertController.warningText(warning);
        TextView meta = text(temp + "  /  " + detail, tpmsMetaText(),
                warning == TpmsAlertController.WARNING_NONE ? COLOR_MUTED : color);
        meta.setTypeface(Typeface.DEFAULT_BOLD);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.setMargins(0, dp(8), 0, 0);
        card.addView(meta, metaLp);
        return card;
    }

    private TextView tpmsWheelDot(int wheel, int color) {
        TextView dot = text(wheelName(wheel), tpmsNarrowLayout() ? 15 : 18, Color.WHITE);
        dot.setGravity(Gravity.CENTER);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setMinWidth(tpmsNarrowLayout() ? dp(42) : dp(50));
        dot.setMinHeight(tpmsNarrowLayout() ? dp(34) : dp(40));
        dot.setBackground(gradient(softColor(color, 155), softColor(COLOR_PANEL_SOFT, 160),
                softColor(Color.WHITE, 48), dp(1), dp(8)));
        return dot;
    }

    private TextView tpmsStatePill(boolean known, int warning) {
        String value = !known ? "NO DATA"
                : warning == TpmsAlertController.WARNING_NONE ? "OK"
                : TpmsAlertController.warningText(warning).toUpperCase(Locale.US);
        int color = !known ? COLOR_MUTED : warningColor(warning);
        TextView pill = text(value, tpmsNarrowLayout() ? 10 : 12, Color.WHITE);
        pill.setGravity(Gravity.CENTER);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setSingleLine(true);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(gradient(softColor(color, 88), softColor(COLOR_PANEL_SOFT, 128),
                softColor(Color.WHITE, 34), dp(1), dp(8)));
        return pill;
    }

    private LinearLayout tpmsLiveCarPanel(TpmsState state) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding(0, 0, 0, 0);

        panel.addView(tpmsGlassCar(state), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        panel.addView(tpmsLiveFooter(state), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private FrameLayout tpmsGlassCar(TpmsState state) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);

        float scale = tpmsCarScale();
        FrameLayout car = new FrameLayout(this);
        car.setClipChildren(false);
        FrameLayout.LayoutParams carLp = new FrameLayout.LayoutParams(
                Math.round(470 * scale), Math.round(760 * scale), Gravity.CENTER);
        wrap.addView(car, carLp);

        addCarTire(car, state, TpmsState.WHEEL_FL, 0, 20, scale);
        addCarTire(car, state, TpmsState.WHEEL_FR, 350, 20, scale);
        addCarTire(car, state, TpmsState.WHEEL_RL, 0, 520, scale);
        addCarTire(car, state, TpmsState.WHEEL_RR, 350, 520, scale);
        addCarPart(car, 112, 48, 246, 654, COLOR_ACCENT_BLUE, 40, 138, scale);
        addCarPart(car, 136, 18, 198, 130, COLOR_ACCENT, 50, 122, scale);
        addCarPart(car, 98, 140, 274, 148, COLOR_ACCENT_BLUE, 48, 132, scale);
        addCarPart(car, 96, 474, 278, 248, COLOR_ACCENT, 58, 148, scale);
        addCarPart(car, 142, 572, 186, 96, Color.WHITE, 70, 24, scale);

        TextView mark = text("KIA", Math.round(26 * scale), Color.WHITE);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        car.addView(mark, carLp(160, 60, 155, 356, scale));

        if (TpmsAlertController.hasWarnings(this, state)) {
            TextView warn = text("!", Math.round(42 * scale), Color.WHITE);
            warn.setGravity(Gravity.CENTER);
            warn.setTypeface(Typeface.DEFAULT_BOLD);
            warn.setBackground(gradient(softColor(COLOR_DANGER, 210), softColor(COLOR_ROSE, 160),
                    softColor(Color.WHITE, 80), dp(1), Math.round(44 * scale)));
            car.addView(warn, carLp(88, 88, 191, 334, scale));
        }
        return wrap;
    }

    private void addCarTire(FrameLayout car, TpmsState state, int wheel, int left, int top, float scale) {
        int warning = TpmsAlertController.warningState(this, state, wheel);
        int color = warning == TpmsAlertController.WARNING_NONE ? Color.rgb(26, 35, 42) : warningColor(warning);
        View tire = new View(this);
        tire.setBackground(gradient(softColor(color, 230), softColor(Color.rgb(4, 8, 12), 245),
                softColor(Color.WHITE, warning == TpmsAlertController.WARNING_NONE ? 34 : 72),
                dp(1), Math.round(28 * scale)));
        car.addView(tire, carLp(118, 218, left, top, scale));
    }

    private void addCarPart(FrameLayout car, int width, int height, int left, int top,
                            int color, int radius, int alpha, float scale) {
        View part = new View(this);
        part.setBackground(gradient(softColor(color, alpha), softColor(COLOR_PANEL_SOFT, 118),
                softColor(Color.WHITE, 38), dp(1), Math.round(radius * scale)));
        car.addView(part, carLp(width, height, left, top, scale));
    }

    private FrameLayout.LayoutParams carLp(int width, int height, int left, int top, float scale) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(width * scale), Math.round(height * scale));
        lp.leftMargin = Math.round(left * scale);
        lp.topMargin = Math.round(top * scale);
        return lp;
    }

    private LinearLayout tpmsLiveFooter(TpmsState state) {
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, dp(16), 0, 0);
        String stateText = tpmsCenterText(state);
        footer.addView(tpmsFooterChip(stateText,
                TpmsAlertController.hasWarnings(this, state) ? COLOR_DANGER : COLOR_ACCENT));
        footer.addView(tpmsFooterChip("RX " + timeText(state == null ? 0L : state.updatedAt), COLOR_ACCENT_BLUE));
        footer.addView(tpmsFooterChip("опрос 5 сек", COLOR_PANEL_SOFT));
        return footer;
    }

    private TextView tpmsFooterChip(String value, int color) {
        TextView chip = text(value, tpmsNarrowLayout() ? 11 : 13, Color.WHITE);
        chip.setGravity(Gravity.CENTER);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setSingleLine(true);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setBackground(gradient(softColor(color, 82), softColor(COLOR_PANEL_SOFT, 122),
                softColor(Color.WHITE, 30), dp(1), dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(5), 0, dp(5), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    private TextView tpmsLiveGearButton() {
        TextView settings = text("⚙", tpmsNarrowLayout() ? 25 : 31, Color.WHITE);
        settings.setGravity(Gravity.CENTER);
        settings.setTypeface(Typeface.DEFAULT_BOLD);
        settings.setClickable(true);
        settings.setFocusable(true);
        settings.setBackground(gradient(softColor(COLOR_PANEL_SOFT, 170), softColor(COLOR_ACCENT_BLUE, 76),
                softColor(Color.WHITE, 44), dp(1), dp(8)));
        settings.setOnClickListener(v -> {
            settingsMode = true;
            settingsTab = SETTINGS_TPMS;
            renderTab();
            refresh();
        });
        return settings;
    }

    private GradientDrawable tpmsGlassSurface(int tint, boolean warning) {
        int main = warning ? tint : COLOR_ACCENT_BLUE;
        return gradient(softColor(main, warning ? 96 : 54), softColor(COLOR_PANEL_SOFT, 172),
                softColor(Color.WHITE, warning ? 76 : 44), dp(1), dp(8));
    }

    private boolean tpmsNarrowLayout() {
        return screenWidthDp() < 900;
    }

    private float tpmsCarScale() {
        int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int height = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        return Math.max(0.48f, Math.min(1.12f, Math.min(width / 2000f, height / 1200f)));
    }

    private int tpmsPressureText() {
        int widthDp = screenWidthDp();
        if (widthDp >= 1300) return 58;
        if (widthDp >= 1000) return 48;
        return 34;
    }

    private int tpmsSmallText() {
        int widthDp = screenWidthDp();
        if (widthDp >= 1300) return 21;
        if (widthDp >= 1000) return 18;
        return 15;
    }

    private int tpmsUnitText() {
        int widthDp = screenWidthDp();
        if (widthDp >= 1300) return 21;
        if (widthDp >= 1000) return 18;
        return 14;
    }

    private int tpmsMetaText() {
        int widthDp = screenWidthDp();
        if (widthDp >= 1300) return 17;
        if (widthDp >= 1000) return 15;
        return 12;
    }

    private void renderSettingsShell() {
        if (rootLayout == null) return;
        rootLayout.removeAllViews();
        rootLayout.setBackgroundColor(COLOR_SETTINGS_BG);
        rootLayout.addView(settingsTopRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, settingsHeaderHeight()));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_SETTINGS_BG);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(18));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipToPadding(false);
        content.setPadding(settingsContentPadding(), dp(12), settingsContentPadding(), dp(18));
        renderSettingsContent(content);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootLayout.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tabContent = content;
        restoreScrollPosition(scroll, true);
    }

    private TextView settingsMenuItem(int tab, String label) {
        boolean selected = settingsTab == tab;
        TextView item = text(settingsMenuLabel(tab, label), isCompact() ? 14 : 17,
                selected ? Color.WHITE : COLOR_MUTED);
        item.setGravity(Gravity.CENTER);
        item.setTypeface(Typeface.DEFAULT_BOLD);
        item.setSingleLine(true);
        item.setPadding(dp(10), 0, dp(10), 0);
        item.setClickable(true);
        item.setFocusable(true);
        item.setBackground(selected
                ? settingsButtonBackground(true)
                : round(Color.TRANSPARENT, dp(7), Color.TRANSPARENT, 0));
        item.setOnClickListener(v -> {
            if (settingsTab != tab) {
                settingsScrollY = 0;
                activeScrollView = null;
            }
            settingsTab = tab;
            renderTab();
            refresh();
        });
        return item;
    }

    private String settingsMenuLabel(int tab, String label) {
        return label;
    }

    private View settingsTopRow() {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(12), dp(8));
        row.setBackgroundColor(COLOR_SETTINGS_BG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 0);
        row.setLayoutParams(lp);

        row.addView(settingsMenuItem(SETTINGS_TPMS, "TPMS"), settingsHeaderItemLayout(true));
        if (AppSettings.mediaTabVisible(this)) {
            row.addView(settingsMenuItem(SETTINGS_MEDIA, "Медиа"), settingsHeaderItemLayout(false));
        }
        row.addView(settingsMenuItem(SETTINGS_NAVIGATION, "Навигация"), settingsHeaderItemLayout(false));
        row.addView(settingsMenuItem(SETTINGS_CANBUS, "Canbus"), settingsHeaderItemLayout(false));
        row.addView(settingsMenuItem(SETTINGS_RCTA, "RCTA"), settingsHeaderItemLayout(false));
        row.addView(settingsMenuItem(SETTINGS_GENERAL, "Общее"), settingsHeaderItemLayout(false));
        if (AppSettings.logTabVisible(this)) {
            row.addView(settingsMenuItem(SETTINGS_LOG, "Диагностика"), settingsHeaderItemLayout(false));
        }

        TextView close = text("×", isCompact() ? 30 : 34, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setClickable(true);
        close.setFocusable(true);
        close.setBackground(settingsButtonBackground(false));
        close.setOnClickListener(v -> closeSettings());
        row.addView(close, new LinearLayout.LayoutParams(
                isCompact() ? dp(52) : dp(60), isCompact() ? dp(46) : dp(52)));
        return row;
    }

    private LinearLayout.LayoutParams settingsHeaderItemLayout(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                isCompact() ? dp(46) : dp(52), 1f);
        lp.setMargins(first ? 0 : dp(3), 0, dp(3), 0);
        return lp;
    }

    private int settingsHeaderHeight() {
        return isCompact() ? dp(62) : dp(68);
    }

    private int settingsContentPadding() {
        int widthDp = screenWidthDp();
        if (widthDp >= 1200) return dp(22);
        if (widthDp < 560) return dp(10);
        return dp(16);
    }

    private void closeSettings() {
        settingsMode = false;
        selectedTab = TAB_TPMS;
        renderTab();
        refresh();
    }

    private void renderSettingsContent(LinearLayout root) {
        switch (settingsTab) {
            case SETTINGS_MEDIA:
                renderMediaTab(root);
                break;
            case SETTINGS_NAVIGATION:
                renderNavigationTab(root);
                break;
            case SETTINGS_CANBUS:
                renderCanbusTab(root);
                break;
            case SETTINGS_RCTA:
                renderRctaSettingsTab(root);
                break;
            case SETTINGS_GENERAL:
                renderGeneralSettingsTab(root);
                break;
            case SETTINGS_LOG:
                renderLogTab(root);
                break;
            case SETTINGS_TPMS:
            default:
                renderTpmsSettingsTab(root);
                break;
        }
    }

    private String settingsTitle(int tab) {
        switch (tab) {
            case SETTINGS_MEDIA:
                return "Медиа";
            case SETTINGS_NAVIGATION:
                return "Навигация";
            case SETTINGS_CANBUS:
                return "Canbus";
            case SETTINGS_RCTA:
                return "RCTA";
            case SETTINGS_GENERAL:
                return "Общее";
            case SETTINGS_LOG:
                return "Диагностика CAN";
            case SETTINGS_TPMS:
            default:
                return "TPMS";
        }
    }

    private static String pressureBarValue(int pressureKpa) {
        return String.format(Locale.US, "%.2f", pressureKpa / 100f);
    }

    private float navigationSpeedKmh() {
        String raw = StateStore.navigation().currentSpeed;
        if (raw == null) return 0f;
        StringBuilder number = new StringBuilder();
        boolean decimal = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') {
                number.append(ch);
            } else if ((ch == '.' || ch == ',') && !decimal && number.length() > 0) {
                number.append('.');
                decimal = true;
            } else if (number.length() > 0) {
                break;
            }
        }
        if (number.length() == 0) return 0f;
        try {
            return Math.max(0f, Math.min(240f, Float.parseFloat(number.toString())));
        } catch (NumberFormatException ignored) {
            return 0f;
        }
    }

    private LinearLayout tpmsMainStage(TpmsState state) {
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(isCompact() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        stage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(8));
        stage.setLayoutParams(lp);

        if (isCompact()) {
            stage.addView(tpmsCarPanel(state), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            stage.addView(tpmsWheelPanel(state), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return stage;
        }

        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(dp(255), dp(430));
        LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(0, dp(430), 1f);
        centerLp.setMargins(dp(10), 0, dp(10), 0);
        stage.addView(tpmsWheelColumn(state, TpmsState.WHEEL_FL, TpmsState.WHEEL_RL), sideLp);
        stage.addView(tpmsCarPanel(state), centerLp);
        stage.addView(tpmsWheelColumn(state, TpmsState.WHEEL_FR, TpmsState.WHEEL_RR), sideLp);
        return stage;
    }

    private LinearLayout tpmsWheelColumn(TpmsState state, int topWheel, int bottomWheel) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        topLp.setMargins(0, 0, 0, dp(6));
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bottomLp.setMargins(0, dp(6), 0, 0);
        column.addView(tpmsMainWheelCard(state, topWheel), topLp);
        column.addView(tpmsMainWheelCard(state, bottomWheel), bottomLp);
        return column;
    }

    private View tpmsMainWheelCard(TpmsState state, int wheel) {
        boolean known = state != null && state.known != null && wheel < state.known.length && state.known[wheel];
        int warning = TpmsAlertController.warningState(this, state, wheel);
        int color = warningColor(warning);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(glassPanel(color));

        LinearLayout header = row();
        header.addView(iconBadge(wheelName(wheel), color));
        TextView label = text(wheelFullName(wheel), isCompact() ? 15 : 17, COLOR_TEXT);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMargins(dp(10), 0, 0, 0);
        header.addView(label, labelLp);
        card.addView(header);

        TextView pressure = text(known ? TpmsAlertController.barText(state.pressureKpa[wheel]) : "-- bar",
                isCompact() ? 27 : 34, Color.WHITE);
        pressure.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams pressureLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pressureLp.setMargins(0, dp(16), 0, 0);
        card.addView(pressure, pressureLp);

        String temp = known ? state.temperatureC[wheel] + "C" : "--C";
        String detail = warning == TpmsAlertController.WARNING_NONE
                ? (known ? "норма" : "нет данных")
                : TpmsAlertController.warningText(warning);
        TextView meta = text(temp + "  ·  " + detail, isCompact() ? 13 : 15,
                warning == TpmsAlertController.WARNING_NONE ? COLOR_MUTED : color);
        meta.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.setMargins(0, dp(4), 0, 0);
        card.addView(meta, metaLp);
        return card;
    }

    private LinearLayout tpmsCarPanel(TpmsState state) {
        int color = TpmsAlertController.hasWarnings(this, state) ? COLOR_DANGER : COLOR_ACCENT;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(glassPanel(color));

        TextView brand = text("KIA", isCompact() ? 24 : 30, Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setGravity(Gravity.CENTER);
        panel.addView(brand);

        TextView statusLine = text(tpmsCenterText(state), isCompact() ? 13 : 15, COLOR_MUTED);
        statusLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(2), 0, dp(14));
        panel.addView(statusLine, statusLp);

        panel.addView(carShape(color), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout chips = row();
        chips.setGravity(Gravity.CENTER);
        chips.addView(pill(AppSettings.tpmsAlertsEnabled(this) ? "alerts on" : "alerts off",
                AppSettings.tpmsAlertsEnabled(this) ? COLOR_ACCENT : COLOR_MUTED),
                chipLayout(true));
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsLp.setMargins(0, dp(14), 0, 0);
        panel.addView(chips, chipsLp);
        return panel;
    }

    private LinearLayout carShape(int color) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.addView(carShapePart(dp(86), dp(18), color, 38));
        wrap.addView(carShapePart(dp(138), dp(56), COLOR_ACCENT_BLUE, 34));
        wrap.addView(carShapePart(dp(206), dp(130), color, 42));
        wrap.addView(carShapePart(dp(150), dp(34), color, 28));
        TextView mark = text("KIA", 18, Color.WHITE);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        markLp.setMargins(0, dp(8), 0, 0);
        wrap.addView(mark, markLp);
        return wrap;
    }

    private View carShapePart(int width, int height, int color, int radiusDp) {
        View view = new View(this);
        view.setBackground(gradient(softColor(color, 92), softColor(COLOR_PANEL_SOFT, 176),
                softColor(Color.WHITE, 48), dp(1), dp(radiusDp)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.setMargins(0, dp(4), 0, dp(4));
        view.setLayoutParams(lp);
        return view;
    }

    private String tpmsCenterText(TpmsState state) {
        if (state == null || !state.hasData()) return "нет данных от датчиков";
        String detail = TpmsAlertController.warningDetails(this, state);
        if (detail.length() > 0) {
            return TpmsAlertController.hasCriticalWarnings(this, state)
                    ? "критичное предупреждение"
                    : "есть предупреждение";
        }
        return "все колеса в норме";
    }

    private LinearLayout tpmsDashboardPanel(TpmsState state) {
        int color = TpmsAlertController.hasWarnings(this, state) ? COLOR_DANGER : COLOR_ACCENT;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(isCompact() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(glassPanel(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(10));
        panel.setLayoutParams(lp);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("TPMS", isCompact() ? 26 : 34, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        tpmsStatus = text(tpmsStatusText(), isCompact() ? 13 : 15, COLOR_TEXT);
        textBox.addView(title);
        textBox.addView(tpmsStatus);
        LinearLayout.LayoutParams textLp = isCompact()
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        panel.addView(textBox, textLp);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(isCompact() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        chips.setGravity(isCompact() ? Gravity.LEFT : Gravity.RIGHT);
        chips.addView(pill(AppSettings.tpmsAlertsEnabled(this) ? "alerts on" : "alerts off",
                AppSettings.tpmsAlertsEnabled(this) ? COLOR_ACCENT : COLOR_MUTED),
                chipLayout(true));
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                isCompact() ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsLp.setMargins(isCompact() ? 0 : dp(14), isCompact() ? dp(12) : 0, 0, 0);
        panel.addView(chips, chipsLp);
        return panel;
    }

    private LinearLayout tpmsWheelPanel(TpmsState state) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int columns = tpmsColumns();
        for (int i = 0; i < TpmsState.WHEEL_COUNT; i += columns) {
            LinearLayout line = row();
            for (int column = 0; column < columns; column++) {
                int wheel = i + column;
                line.addView(wheel < TpmsState.WHEEL_COUNT ? tpmsWheelCard(state, wheel) : new View(this),
                        actionLayout(column, columns));
            }
            panel.addView(line);
        }
        return panel;
    }

    private View tpmsWheelCard(TpmsState state, int wheel) {
        boolean known = state != null && state.known != null && wheel < state.known.length && state.known[wheel];
        int warning = TpmsAlertController.warningState(this, state, wheel);
        int color = warningColor(warning);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setMinimumHeight(isCompact() ? dp(112) : dp(132));
        card.setBackground(glassPanel(color));

        LinearLayout header = row();
        TextView badge = iconBadge(wheelName(wheel), color);
        header.addView(badge);
        TextView label = text(wheelFullName(wheel), isCompact() ? 15 : 17, COLOR_TEXT);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMargins(dp(10), 0, 0, 0);
        header.addView(label, labelLp);
        card.addView(header);

        TextView pressure = text(known ? TpmsAlertController.barText(state.pressureKpa[wheel]) : "-- bar",
                isCompact() ? 24 : 30, Color.WHITE);
        pressure.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams pressureLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pressureLp.setMargins(0, dp(10), 0, 0);
        card.addView(pressure, pressureLp);

        String temp = known ? state.temperatureC[wheel] + "C" : "--C";
        String detail = warning == TpmsAlertController.WARNING_NONE
                ? (known ? "норма" : "нет данных")
                : TpmsAlertController.warningText(warning);
        TextView meta = text(temp + "  ·  " + detail, isCompact() ? 13 : 15,
                warning == TpmsAlertController.WARNING_NONE ? COLOR_MUTED : color);
        meta.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(meta);
        return card;
    }

    private LinearLayout settingsPanel(int tint) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipToPadding(false);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(settingsPanelBackground(tint));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        panel.setLayoutParams(lp);
        return panel;
    }

    private void addSettingsPanelHeader(LinearLayout panel, String title, String hint, int color) {
        LinearLayout header = row();
        int accent = settingsAccent(color);
        TextView marker = text(" ", 1, accent);
        marker.setBackground(round(accent, dp(3), Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams markerLp = new LinearLayout.LayoutParams(dp(4),
                isCompact() ? dp(24) : dp(28));
        markerLp.setMargins(0, 0, dp(10), 0);
        header.addView(marker, markerLp);

        TextView label = text(title, isCompact() ? 18 : 21, Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(label, labelLp);
        panel.addView(header);

        if (hint == null || hint.length() == 0) return;
        TextView description = text(hint, isCompact() ? 12 : 14, COLOR_MUTED);
        description.setMaxLines(2);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(dp(14), dp(6), 0, 0);
        panel.addView(description, hintLp);
    }

    private CompoundButton addSettingsPanelSwitchHeader(LinearLayout panel, String title, String hint,
                                                       boolean checked,
                                                       CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(isCompact() ? dp(60) : dp(68));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(title, isCompact() ? 18 : 21, Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(label);

        if (hint != null && hint.length() > 0) {
            TextView description = text(hint, isCompact() ? 12 : 14, COLOR_MUTED);
            description.setMaxLines(2);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.setMargins(0, dp(4), 0, 0);
            texts.addView(description, hintLp);
        }

        header.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        CompoundButton toggle = checkBox("", checked);
        toggle.setOnCheckedChangeListener(listener);
        header.addView(toggle, switchLayout(true));
        panel.addView(header);
        return toggle;
    }

    private void addSettingsSubHeader(LinearLayout panel, String title, String hint) {
        TextView label = text(title, isCompact() ? 14 : 16, Color.rgb(231, 239, 247));
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.setMargins(0, dp(16), 0, 0);
        panel.addView(label, labelLp);

        if (hint == null || hint.length() == 0) return;
        TextView description = text(hint, isCompact() ? 12 : 13, COLOR_MUTED);
        description.setMaxLines(2);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(3), 0, dp(8));
        panel.addView(description, hintLp);
    }

    private LinearLayout statusBox(String title, TextView content, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(13), dp(15), dp(13));
        box.setBackground(settingsInsetBackground(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        box.setLayoutParams(lp);

        LinearLayout header = row();
        TextView label = text(title, isCompact() ? 14 : 16, Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(label, labelLp);
        box.addView(header);

        content.setTextColor(Color.rgb(235, 241, 246));
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentLp.setMargins(0, dp(8), 0, 0);
        box.addView(content, contentLp);
        return box;
    }

    private View settingsDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(softColor(COLOR_SETTINGS_DIVIDER, 120));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(10), 0, dp(10));
        divider.setLayoutParams(lp);
        return divider;
    }

    private CompoundButton addInlineSwitch(LinearLayout root, String title, String hint, boolean checked,
                                           CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(isCompact() ? dp(50) : dp(56));
        line.setPadding(0, dp(4), 0, dp(4));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, isCompact() ? 15 : 17, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = text(hint, isCompact() ? 12 : 13, COLOR_MUTED);
        sub.setMaxLines(2);
        texts.addView(name);
        texts.addView(sub);
        line.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        CompoundButton toggle = checkBox("", checked);
        toggle.setOnCheckedChangeListener(listener);
        line.addView(toggle, switchLayout(true));
        root.addView(line);
        return toggle;
    }

    private LinearLayout tpmsSettingsAlertsPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Оповещения",
                "оверлей сверху экрана и повтор звука до физического закрытия", COLOR_ACCENT_BLUE);
        tpmsAlertsToggle = addInlineSwitch(panel, "Красная плашка",
                "показывать предупреждение поверх экрана при выходе за пороги",
                AppSettings.tpmsAlertsEnabled(this), this::toggleTpmsAlerts);
        panel.addView(settingsDivider());
        tpmsSoundToggle = addInlineSwitch(panel, "Звуковое уведомление",
                "повторять звук, пока красная плашка не закрыта",
                AppSettings.tpmsSoundAlertsEnabled(this), this::toggleTpmsSoundAlerts);
        return panel;
    }

    private LinearLayout tpmsThresholdPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);

        addSettingsPanelHeader(panel, "Пороги давления и температуры",
                "жёлтый: вышли за диапазон; красный: дальше порога на 0.3 bar или 10C",
                COLOR_ACCENT_BLUE);

        TpmsSettingsPreviewView preview = new TpmsSettingsPreviewView(this);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, isCompact() ? dp(184) : dp(210));
        previewLp.setMargins(0, dp(12), 0, 0);
        panel.addView(preview, previewLp);

        TextView summary = text("Сейчас: " + tpmsThresholdText(), isCompact() ? 14 : 16, Color.WHITE);
        summary.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.setMargins(0, dp(10), 0, 0);
        panel.addView(summary, summaryLp);

        TextView logic = text("При жёлтом или красном состоянии TPMS переходит на опрос 1 сек; без предупреждений: фон 60 сек, открытый экран 5 сек.",
                isCompact() ? 12 : 14, COLOR_MUTED);
        LinearLayout.LayoutParams logicLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logicLp.setMargins(0, dp(6), 0, 0);
        panel.addView(logic, logicLp);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(isCompact() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        grid.setClipToPadding(false);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridLp.setMargins(0, dp(12), 0, 0);
        panel.addView(grid, gridLp);

        grid.addView(tpmsThresholdGroup("Давление", "bar",
                        tpmsPressureThresholdControl("lowP", "Минимум", AppSettings.tpmsLowPressureKpa(this),
                                80, 450, this::setTpmsLowPressure),
                        tpmsPressureThresholdControl("highP", "Максимум", AppSettings.tpmsHighPressureKpa(this),
                                120, 600, this::setTpmsHighPressure)),
                tpmsThresholdGroupLayout(true));
        grid.addView(tpmsThresholdGroup("Температура", "C",
                        tpmsThresholdControl("lowT", "Минимум", AppSettings.tpmsLowTempC(this),
                                -60, 80, this::setTpmsLowTemp),
                        tpmsThresholdControl("highT", "Максимум", AppSettings.tpmsHighTempC(this),
                                -20, 160, this::setTpmsHighTemp)),
                tpmsThresholdGroupLayout(false));
        return panel;
    }

    private LinearLayout tpmsThresholdGroup(String title, String unit, View low, View high) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(14), dp(12), dp(14), dp(14));
        group.setBackground(settingsInsetBackground());

        LinearLayout header = row();
        TextView name = text(title, isCompact() ? 15 : 17, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(pill(unit, COLOR_PANEL_SOFT));
        group.addView(header);

        group.addView(low);
        group.addView(settingsDivider());
        group.addView(high);
        return group;
    }

    private LinearLayout.LayoutParams tpmsThresholdGroupLayout(boolean first) {
        LinearLayout.LayoutParams lp = isCompact()
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(isCompact() || first ? 0 : dp(8), first ? 0 : (isCompact() ? dp(8) : 0),
                isCompact() || !first ? 0 : dp(8), 0);
        return lp;
    }

    private LinearLayout tpmsThresholdControl(String tag, String label, int value,
                                              int min, int max, IntSetter setter) {
        return tpmsThresholdControl(tag, label, value, min, max, setter, false);
    }

    private LinearLayout tpmsPressureThresholdControl(String tag, String label, int value,
                                                      int min, int max, IntSetter setter) {
        return tpmsThresholdControl(tag, label, value, min, max, setter, true);
    }

    private LinearLayout tpmsThresholdControl(String tag, String label, int value,
                                              int min, int max, IntSetter setter,
                                              boolean pressureBar) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(isCompact() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(isCompact() ? dp(58) : dp(62));
        line.setPadding(0, dp(8), 0, 0);

        TextView name = text(label, isCompact() ? 14 : 16, COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nameLp = isCompact()
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        line.addView(name, nameLp);

        EditText input = pressureBar
                ? pressureInput("tpmsBar:" + tag, value, min, max, setter)
                : numericInput("tpms:" + tag, value, min, max, setter);
        tpmsInputs.add(input);
        LinearLayout.LayoutParams rowLp = isCompact()
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(dp(274), ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(isCompact() ? 0 : dp(12), isCompact() ? dp(6) : 0, 0, 0);
        line.addView(pressureBar
                ? pressureRow(input, value, min, max, setter)
                : numericRow(input, value, min, max, setter), rowLp);
        return line;
    }

    private void renderMediaTab() {
        renderMediaTab(tabContent);
    }

    private void renderMediaTab(LinearLayout root) {
        root.addView(mediaMusicPanel());
        if (AppSettings.universalMediaProfile(this)) {
            root.addView(mediaRadioStationPanel());
        }
        if (AppSettings.teyesMediaProfile(this)) {
            root.addView(mediaCallPanel());
        }
        root.addView(mediaDebugPanel());
    }

    private LinearLayout mediaMusicPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Музыка и текст",
                "профиль магнитолы, трек, артист и отправка в машину",
                COLOR_ACCENT_BLUE);

        panel.addView(settingsDivider());
        addSettingsSubHeader(panel, "Профиль магнитолы", "старый TEYES не смешивается с универсальными режимами");
        addActionGridColumns(panel, 2,
                mediaProfileAction(AppSettings.MEDIA_PROFILE_TEYES, "TEYES / CC4", "магнитола TEYES, радио, USB, BT"),
                mediaProfileAction(AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID, "Android", "музыка из Android и база радио"),
                mediaProfileAction(AppSettings.MEDIA_PROFILE_UART_REAL, "UART real", "оставить штатный режим, менять только текст"),
                mediaProfileAction(AppSettings.MEDIA_PROFILE_OFF, "Выкл", "Kia не отправляет медиа"));

        if (!AppSettings.mediaEnabled(this)) {
            return panel;
        }
        int profile = AppSettings.mediaProfile(this);
        panel.addView(settingsDivider());
        addSettingsSubHeader(panel, otherMediaSourceTitle(profile), otherMediaSourceHint(profile));
        addActionGridColumns(panel, 3,
                action("Android", "обычная Android-музыка", otherModeColor(AppSettings.OTHER_SOURCE_ANDROID),
                        () -> setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_ANDROID)),
                action("Bluetooth", "показать как BT Audio", otherModeColor(AppSettings.OTHER_SOURCE_BLUETOOTH),
                        () -> setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_BLUETOOTH)),
                action("USB", "показать как USB Music", otherModeColor(AppSettings.OTHER_SOURCE_USB),
                        () -> setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_USB)),
                action("My Music", "показать как штатную музыку", otherModeColor(AppSettings.OTHER_SOURCE_MY_MUSIC),
                        () -> setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_MY_MUSIC)),
                action("CarPlay", "показать как CarPlay", otherModeColor(AppSettings.OTHER_SOURCE_CARPLAY),
                        () -> setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_CARPLAY)));

        panel.addView(settingsDivider());
        addSettingsSubHeader(panel, "Формат строки", "как показывать текст мультимедиа");
        addActionGrid(panel,
                action("Автор + трек", "исполнитель, потом название", mediaTextModeColor(AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK),
                        () -> setMediaTextMode(AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK)),
                action("Только трек", "без исполнителя", mediaTextModeColor(AppSettings.MEDIA_TEXT_TRACK_ONLY),
                        () -> setMediaTextMode(AppSettings.MEDIA_TEXT_TRACK_ONLY)));

        return panel;
    }

    private String otherMediaSourceTitle(int profile) {
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL) return "Обычные Android-плееры";
        return "Обычная музыка";
    }

    private String otherMediaSourceHint(int profile) {
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL) {
            return "как показать трек, не меняя штатный режим магнитолы";
        }
        if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID) {
            return "каким штатным источником показать музыку без точного source";
        }
        return "какой штатный режим имитировать для Yandex Music, Spotify и Android-плееров";
    }

    private void showCurrentRadioStationDialog() {
        MediaState media = StateStore.media();
        String frequency = RadioStationStore.currentFrequency(media);
        if (frequency.isEmpty()) {
            AppLog.line(this, "Radio stations: no current frequency");
            refresh();
            return;
        }
        showRadioStationEditDialog(RadioStationStore.currentBand(media), frequency, media.title);
    }

    private void showRadioStationListDialog() {
        List<RadioStationStore.Entry> entries = RadioStationStore.entries(this);
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Радиостанции")
                    .setMessage("Станции появятся автоматически после включения радио.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            RadioStationStore.Entry entry = entries.get(i);
            labels[i] = entry.band + " " + entry.frequency + " -> " + entry.name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Радиостанции")
                .setItems(labels, (dialog, which) -> {
                    RadioStationStore.Entry entry = entries.get(which);
                    showRadioStationEditDialog(entry.band, entry.frequency, entry.name);
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showRadioStationEditDialog(String band, String frequency, String currentName) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(currentName == null ? "" : currentName);
        input.setSelectAllOnFocus(true);
        int pad = dp(18);
        input.setPadding(pad, dp(8), pad, dp(8));
        new AlertDialog.Builder(this)
                .setTitle(band + " " + frequency)
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    RadioStationStore.setStationName(this, band, frequency, input.getText().toString());
                    AppLog.line(this, "Radio station saved: " + band + " " + frequency);
                    resendRadioStationName(band, frequency, "radio station saved");
                    renderTab();
                    refresh();
                })
                .setNeutralButton("Сбросить", (dialog, which) -> {
                    RadioStationStore.clearStationName(this, band, frequency);
                    AppLog.line(this, "Radio station reset: " + band + " " + frequency);
                    resendRadioStationName(band, frequency, "radio station reset");
                    renderTab();
                    refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void resendRadioStationName(String band, String frequency, String reason) {
        MediaState media = StateStore.media();
        if (frequency.equals(RadioStationStore.currentFrequency(media))) {
            String station = RadioStationStore.resolve(this, band, frequency, "");
            MediaFeature.get(this).report(media.source, media.packageName, media.artist, station,
                    media.durationMs, media.playing);
        } else {
            MediaFeature.get(this).resendCurrent(reason);
        }
    }

    private LinearLayout mediaCallPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        callEnabledToggle = addSettingsPanelSwitchHeader(panel, "BT звонок",
                "только TEYES CC4 Pro; отслеживание и передача состояния на приборку",
                AppSettings.callEnabled(this), this::toggleCallEnabled);
        if (!AppSettings.callEnabled(this)) {
            return panel;
        }
        panel.addView(settingsDivider());
        addActionGrid(panel,
                callSourceAction(AppSettings.CALL_SOURCE_ANDROID_AUTO, "Android Auto", "шильдик Android Auto"),
                callSourceAction(AppSettings.CALL_SOURCE_CARPLAY, "CarPlay", "подтверждённый рабочий режим"),
                callSourceAction(AppSettings.CALL_SOURCE_BLUETOOTH, "BT Audio", "режим по умолчанию"));

        return panel;
    }

    private LinearLayout mediaDebugPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        mediaDebugToggle = addSettingsPanelSwitchHeader(panel, "Отладка overlay",
                "показывает media-состояние для скриншота и разбора",
                AppSettings.mediaOverlayEnabled(this), this::toggleMediaDebug);
        if (AppSettings.mediaOverlayEnabled(this)) {
            panel.addView(settingsDivider());
            mediaDebugStatus = text(mediaDebugText(), isCompact() ? 12 : 14,
                    Color.rgb(235, 241, 246));
            mediaDebugStatus.setTypeface(Typeface.MONOSPACE);
            mediaDebugStatus.setLineSpacing(0f, 1.08f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(10), 0, 0);
            panel.addView(mediaDebugStatus, lp);
        }
        return panel;
    }

    private void renderNavigationTab() {
        renderNavigationTab(tabContent);
    }

    private void renderNavigationTab(LinearLayout root) {
        root.addView(navigationOutputPanel());
        addSection(root, "Источник", "Yandex использует прямой Core Bridge; Auto оставлен как резервный режим.",
                navSourceAction(AppSettings.NAV_SOURCE_AUTO, "Auto", "fallback Yandex + 2GIS"),
                navSourceAction(AppSettings.NAV_SOURCE_YANDEX, "Yandex", "только Yandex Core Bridge"),
                navSourceAction(AppSettings.NAV_SOURCE_2GIS, "2GIS", "только dashboard 2GIS"));
        root.addView(navigationOptionsPanel());
        navigationDebugToggle = addSettingSwitch(root, "Панель диагностики",
                "служебные данные навигации поверх карты",
                AppSettings.navOverlayEnabled(this), this::toggleNavDebug);
    }

    private LinearLayout navigationOutputPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        int routeMode = NavigationModeSettings.mode(this);
        panel.addView(navigationOutputHero(routeMode));
        addActionGridColumns(panel, 3,
                navigationOutputTile("Источник", AppSettings.navSourceLabel(this),
                        navigationSourceOutputHint(), COLOR_ACCENT_BLUE, true),
                navigationOutputTile(navigationTextTileTitle(routeMode), navigationTextTileValue(routeMode),
                        navigationTextTileHint(routeMode), COLOR_ACCENT_BLUE, true),
                navigationOutputTile(navigationAssistTileTitle(routeMode), navigationAssistTileValue(routeMode),
                        navigationAssistTileHint(routeMode), navigationAssistTileColor(routeMode),
                        navigationAssistTileActive(routeMode)));
        return panel;
    }

    private View navigationOutputHero(int routeMode) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setMinimumHeight(navigationOutputHeroHeight());
        card.setBackground(round(softColor(COLOR_ACCENT_BLUE, 44), dp(8),
                Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, navigationOutputHeroHeight());
        cardLp.setMargins(0, 0, 0, dp(4));
        card.setLayoutParams(cardLp);

        TextView value = text(navigationOutputMainValue(routeMode), isCompact() ? 19 : 23, Color.WHITE);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setMaxLines(2);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(value, valueLp);

        TextView hint = text(navigationOutputMainHint(routeMode), isCompact() ? 12 : 14, COLOR_MUTED);
        hint.setMaxLines(2);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(3), 0, 0);
        card.addView(hint, hintLp);
        return card;
    }

    private int navigationOutputHeroHeight() {
        return isCompact() ? dp(82) : dp(98);
    }

    private View navigationOutputTile(String title, String value, String hint, int color, boolean active) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(14), dp(11), dp(14), dp(11));
        tile.setMinimumHeight(isCompact() ? dp(72) : dp(88));
        int accent = settingsAccent(color);
        tile.setBackground(round(active ? softColor(accent, 38) : COLOR_SETTINGS_PANEL_ALT,
                dp(7), Color.TRANSPARENT, 0));

        TextView label = text(title, isCompact() ? 11 : 12, active ? accent : COLOR_MUTED);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        TextView main = text(value, isCompact() ? 15 : 17, Color.WHITE);
        main.setTypeface(Typeface.DEFAULT_BOLD);
        main.setMaxLines(2);
        TextView sub = text(hint, isCompact() ? 11 : 12, COLOR_MUTED);
        sub.setMaxLines(2);
        tile.addView(label);
        tile.addView(main);
        tile.addView(sub);
        return tile;
    }

    private String navigationOutputMainValue(int mode) {
        if (mode == NavigationOutputMode.TBT) return "TBT-иконки поворотов";
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "стрелка к точке финиша";
        return "манёвр, lane и серая дорога";
    }

    private String navigationOutputMainHint(int mode) {
        String source = AppSettings.navSourceLabel(this);
        String time = AppSettings.navEtaTimeModeLabel(this).toLowerCase(Locale.ROOT);
        if (mode == NavigationOutputMode.TBT) return source + " · дорогу не трогать · " + time;
        if (mode == NavigationOutputMode.FINISH_DIRECTION) {
            return source + " · старт " + navFinishLeadText(AppSettings.navFinishDirectionLeadMeters(this))
                    + " · маршрут скрыт";
        }
        return source + " · " + navigationTextOutputText(AppSettings.navTextMode(this))
                + " · " + time;
    }

    private String navigationSourceOutputHint() {
        int mode = AppSettings.navSourceMode(this);
        if (mode == AppSettings.NAV_SOURCE_YANDEX) return "только Yandex";
        if (mode == AppSettings.NAV_SOURCE_2GIS) return "только 2GIS";
        return "Yandex и 2GIS";
    }

    private String navigationTextTileTitle(int mode) {
        if (mode == NavigationOutputMode.TBT) return "Дорога";
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "Старт";
        return "Строка";
    }

    private String navigationTextTileValue(int mode) {
        if (mode == NavigationOutputMode.TBT) return "не трогать";
        if (mode == NavigationOutputMode.FINISH_DIRECTION) {
            return navFinishLeadText(AppSettings.navFinishDirectionLeadMeters(this));
        }
        return navigationTextOutputText(AppSettings.navTextMode(this));
    }

    private String navigationTextTileHint(int mode) {
        if (mode == NavigationOutputMode.TBT) return "без подмены обычного экрана";
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "когда включать стрелку";
        return "что писать на панели";
    }

    private String navigationAssistTileTitle(int mode) {
        if (mode == NavigationOutputMode.TBT) return "Время";
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "Маршрут";
        return "Дополнительно";
    }

    private String navigationAssistTileValue(int mode) {
        if (mode == NavigationOutputMode.TBT) {
            return AppSettings.navEtaTimeModeLabel(this).toLowerCase(Locale.ROOT);
        }
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "скрыт";
        return navigationNormalExtraText();
    }

    private String navigationAssistTileHint(int mode) {
        if (mode == NavigationOutputMode.TBT) return "ETA в TBT";
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "заменён стрелкой";
        return AppSettings.navMicroManeuvers(this)
                ? "ассистент до " + AppSettings.navMicroMaxDistanceMeters(this)
                + " м, " + AppSettings.navMicroHoldSeconds(this) + "с"
                : "ассистент выключен";
    }

    private int navigationAssistTileColor(int mode) {
        if (mode == NavigationOutputMode.TBT) return COLOR_WARNING;
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return COLOR_ACCENT;
        return AppSettings.navOverspeedTextEnabled(this) ? COLOR_WARNING : COLOR_ACCENT;
    }

    private boolean navigationAssistTileActive(int mode) {
        return true;
    }

    private String navigationNormalExtraText() {
        String speed = AppSettings.navOverspeedTextEnabled(this) ? "warning" : "знак скорости";
        if (!AppSettings.navMicroManeuvers(this)) return speed;
        return speed + " + ассистент";
    }

    private String navigationTextOutputText(int mode) {
        switch (mode) {
            case 1:
                return "после манёвра";
            case 2:
                return "финиш";
            case 0:
            default:
                return "текущая улица";
        }
    }

    private String navigationAssistantOutputText() {
        if (!AppSettings.navMicroManeuvers(this)) return "выключен";
        return "подсказка до " + AppSettings.navMicroMaxDistanceMeters(this)
                + " м, держать " + AppSettings.navMicroHoldSeconds(this) + "с";
    }

    private boolean navNormalSettingsEnabled() {
        return AppSettings.navOutputMode(this) == NavigationOutputMode.NORMAL;
    }

    private LinearLayout navigationOptionsPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Опции панели",
                "что именно отправлять на приборку во время маршрута", COLOR_ACCENT_BLUE);
        addSettingsSubHeader(panel, "Режим маршрута", "активен только один режим");
        boolean normalMode = navNormalSettingsEnabled();
        addActionGridColumns(panel, 3,
                navRouteModeAction(NavigationOutputMode.NORMAL, "Обычный", "манёвр + серая дорога"),
                navRouteModeAction(NavigationOutputMode.TBT, "TBT", "отдельные TBT-иконки"),
                navRouteModeAction(NavigationOutputMode.FINISH_DIRECTION, "К флагу", "стрелка к точке финиша"));
        finishCompassAutoToggle = addSettingsPanelSwitchHeader(panel, "Компас к финишу",
                "300 м всегда, до 1 км после последнего манёвра",
                AppSettings.navFinishCompassAuto(this), this::toggleFinishCompassAuto);
        panel.addView(settingsDivider());
        addSettingsSubHeader(panel, "1. Настройки основной навигации",
                normalMode ? "манёвр маршрута, серая дорога и текстовые строки"
                        : "доступно только в режиме маршрута Обычный");
        addActionGrid(panel,
                navSettingsAction(navAddressAction(0, "Улица сейчас", "текущая улица"), normalMode),
                navSettingsAction(navAddressAction(1, "После манёвра", "улица после поворота"), normalMode),
                navSettingsAction(navAddressAction(2, "Улица финиша", "адрес или место назначения"), normalMode),
                navSettingsAction(navEtaTimeModeAction(), normalMode),
                navSettingsAction(action(AppSettings.navOverspeedTextEnabled(this) ? "Превышение: вкл" : "Превышение: выкл",
                        "предупреждение на панели", choiceColor(AppSettings.navOverspeedTextEnabled(this)),
                        AppSettings.navOverspeedTextEnabled(this), () -> {
                            toggleOverspeedText(null, !AppSettings.navOverspeedTextEnabled(this));
                            renderTab();
                            refresh();
                        }), normalMode));
        panel.addView(settingsDivider());
        addSettingsSubHeader(panel, "2. Ассистент манёвров",
                normalMode ? "короткая жёлтая подсказка перед сложным манёвром"
                        : "доступно только в режиме маршрута Обычный");
        microManeuverToggle = addSettingsPanelSwitchHeader(panel, "Ассистент подсказок",
                normalMode ? "может заменить основной манёвр рядом с lane-подсказкой"
                        : "сначала выберите режим маршрута Обычный",
                AppSettings.navMicroManeuvers(this), this::toggleMicroManeuvers);
        microManeuverToggle.setEnabled(normalMode);
        microManeuverToggle.setAlpha(normalMode ? 1f : 0.45f);
        addActionGrid(panel,
                navMicroDistanceCycleAction(normalMode),
                navMicroHoldAfterCycleAction(normalMode));
        return panel;
    }

    private void renderCanbusTab() {
        renderCanbusTab(tabContent);
    }

    private void renderCanbusTab(LinearLayout root) {
        if (AppSettings.ampEnabled(this)) requestAmpSettingsIfNeeded();
        root.addView(canbusTemperaturePanel());
        sasRatioStatus = text("", 14, Color.rgb(235, 241, 246));
        sasRatioInput = sasRatioInput();
        root.addView(sasRatioPanel());
        addAmpSection(root);
    }

    private LinearLayout canbusAdapterPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Адаптер Canbus",
                "подключение USB-адаптера, ID, версия прошивки и последний RX", COLOR_ACCENT_BLUE);

        diagnosticsStatus = text(canbusStatusText(), isCompact() ? 14 : 16, Color.rgb(235, 241, 246));
        diagnosticsStatus.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(12), 0, 0);
        panel.addView(diagnosticsStatus, statusLp);
        return panel;
    }

    private LinearLayout canbusTemperaturePanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Температура на панели",
                "старый параметр Sportage: что адаптер отдаёт в слот температуры", COLOR_ACCENT_BLUE);

        addActionGrid(panel,
                action("Улица", "наружная температура", canbusTempSourceColor(AppSettings.CANBUS_TEMP_OUTSIDE),
                        AppSettings.canbusTemperatureSource(this) == AppSettings.CANBUS_TEMP_OUTSIDE,
                        () -> setCanbusTemperatureSource(AppSettings.CANBUS_TEMP_OUTSIDE)),
                action("Двигатель", "температура двигателя", canbusTempSourceColor(AppSettings.CANBUS_TEMP_ENGINE),
                        AppSettings.canbusTemperatureSource(this) == AppSettings.CANBUS_TEMP_ENGINE,
                        () -> setCanbusTemperatureSource(AppSettings.CANBUS_TEMP_ENGINE)));
        return panel;
    }

    private void renderLogTab() {
        renderLogTab(tabContent);
    }

    private void renderLogTab(LinearLayout root) {
        loggerStatus = text("", 15, Color.rgb(235, 241, 246));
        root.addView(infoPanel("Состояние диагностики", loggerStatus));
        rawCanToggle = addSettingSwitch(root, "Запись CAN",
                "включает сбор кадров через gs_usb", AppSettings.debugCan(this), this::toggleRaw);
        addSection(root, "Шина CAN", "C-CAN это ch1 500k, M-CAN это ch0 100k.",
                action("C-CAN", "channel 1, 500k", loggerBusColor(AppSettings.LOGGER_BUS_C),
                        () -> setLoggerBusMode(AppSettings.LOGGER_BUS_C)),
                action("M-CAN", "channel 0, 100k", loggerBusColor(AppSettings.LOGGER_BUS_M),
                        () -> setLoggerBusMode(AppSettings.LOGGER_BUS_M)),
                action("Обе", "M ch0 100k + C ch1 500k", loggerBusColor(AppSettings.LOGGER_BUS_BOTH),
                        () -> setLoggerBusMode(AppSettings.LOGGER_BUS_BOTH)));
        addSection(root, "Прошивка gs_usb", loggerWarningText(),
                action("Прошить gs_usb", "gs_updated.bin, режим записи CAN",
                        Color.rgb(145, 54, 54), this::confirmFlashLoggerFirmware));
        addSection(root, "Файл записи", "Сохранение текущего буфера в Downloads.",
                action("Сохранить .log.gz", "записать текущий буфер", Color.rgb(76, 94, 119),
                        this::saveCanLog),
                action("Очистить", "сбросить текущий буфер", Color.rgb(74, 86, 98),
                        this::clearCanLog));
    }

    private void renderSettingsTab() {
        renderGeneralSettingsTab(tabContent);
    }

    private void renderTpmsSettingsTab(LinearLayout root) {
        root.addView(tpmsThresholdPanel());
        root.addView(tpmsSettingsAlertsPanel());
    }

    private void renderGeneralSettingsTab(LinearLayout root) {
        root.addView(generalAppPanel());
        root.addView(canbusAdapterPanel());
        root.addView(firmwarePanel());
        addPermissionSection(root);
        root.addView(generalUpdatesPanel());
        root.addView(mediaVisibilityPanel());
        root.addView(logVisibilityPanel());
        root.addView(generalVersionFooter());
    }

    private void renderRctaSettingsTab(LinearLayout root) {
        LinearLayout preview = settingsPanel(COLOR_WARNING);
        addSettingsPanelHeader(preview, "RCTA",
                "preview поверх камеры: чистый центр, предупреждение только по бокам",
                COLOR_WARNING);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackground(settingsInsetBackground());
        BlindSpotOverlayView overlay = new BlindSpotOverlayView(this);
        rctaPreview = overlay;
        overlay.setStyleType(AppSettings.rctaStyle(this));
        overlay.setAlertColor(AppSettings.rctaColor(this));
        overlay.setBackgroundAlpha(AppSettings.rctaBackgroundAlpha(this));
        overlay.setArrowCount(AppSettings.rctaArrowCount(this));
        overlay.setPreview(true, true, false);
        overlay.setBottomLiftDp(0);
        stage.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rctaPreviewHeight());
        stageLp.setMargins(0, dp(12), 0, dp(10));
        preview.addView(stage, stageLp);

        TextView status = text("CAN: BB A1 41 08 75 RL RR · 00 нет · 01 предупреждение · "
                        + AppSettings.rctaStyleLabel(this) + " · "
                        + AppSettings.rctaColorLabel(this) + " · фон "
                        + rctaBackgroundPercent(AppSettings.rctaBackgroundAlpha(this)) + "%"
                        + " · стрелок " + AppSettings.rctaArrowCount(this),
                isCompact() ? 13 : 15, COLOR_MUTED);
        preview.addView(status);
        root.addView(preview);

        LinearLayout settings = settingsPanel(COLOR_WARNING);
        addSettingsPanelHeader(settings, "Настройки RCTA",
                "эти параметры сохраняются сейчас и будут использоваться после подключения событий",
                COLOR_WARNING);
        addSettingsSubHeader(settings, "Тип анимации",
                "Тип 1 как сейчас; Тип 2 без угловых линий и со стрелками ближе к углам");
        addActionGrid(settings,
                action("Тип 1", "угловые линии + стрелки", rctaStyleColor(AppSettings.RCTA_STYLE_TYPE_1),
                        AppSettings.rctaStyle(this) == AppSettings.RCTA_STYLE_TYPE_1,
                        () -> setRctaStyle(AppSettings.RCTA_STYLE_TYPE_1)),
                action("Тип 2", "без линий, ближе к углам", rctaStyleColor(AppSettings.RCTA_STYLE_TYPE_2),
                        AppSettings.rctaStyle(this) == AppSettings.RCTA_STYLE_TYPE_2,
                        () -> setRctaStyle(AppSettings.RCTA_STYLE_TYPE_2)));
        settings.addView(settingsDivider());
        addSettingsSubHeader(settings, "Цвет предупреждения",
                "меняется только цвет RCTA; геометрия стрелок и углов остаётся плотной");
        addActionGrid(settings,
                action("Янтарный", "штатный вид", rctaColorChoice(AppSettings.RCTA_COLOR_AMBER),
                        AppSettings.rctaColor(this) == AppSettings.RCTA_COLOR_AMBER,
                        () -> setRctaColor(AppSettings.RCTA_COLOR_AMBER)),
                action("Красный", "самый заметный", rctaColorChoice(AppSettings.RCTA_COLOR_RED),
                        AppSettings.rctaColor(this) == AppSettings.RCTA_COLOR_RED,
                        () -> setRctaColor(AppSettings.RCTA_COLOR_RED)),
                action("Голубой", "холодный акцент", rctaColorChoice(AppSettings.RCTA_COLOR_CYAN),
                        AppSettings.rctaColor(this) == AppSettings.RCTA_COLOR_CYAN,
                        () -> setRctaColor(AppSettings.RCTA_COLOR_CYAN)),
                action("Зелёный", "мягкий акцент", rctaColorChoice(AppSettings.RCTA_COLOR_GREEN),
                        AppSettings.rctaColor(this) == AppSettings.RCTA_COLOR_GREEN,
                        () -> setRctaColor(AppSettings.RCTA_COLOR_GREEN)));
        settings.addView(rctaArrowCountSlider());
        settings.addView(rctaBackgroundAlphaSlider());
        settings.addView(settingsDivider());
        addInlineSwitch(settings, "Показывать предупреждение",
                "анимация слева/справа поверх экрана заднего хода",
                AppSettings.rctaOverlayEnabled(this), (button, checked) -> {
                    AppSettings.setRctaOverlayEnabled(this, checked);
                    if (checked && !RctaOverlayController.canDrawOverlays(this)) {
                        AppLog.line(this, "RCTA overlay: открой разрешение поверх окон");
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        try {
                            startActivity(intent);
                        } catch (Exception e) {
                            AppLog.line(this, "RCTA overlay settings failed "
                                    + e.getClass().getSimpleName());
                        }
                    }
                    RctaOverlayController.get(this).apply();
                    refresh();
                });
        settings.addView(settingsDivider());
        addInlineSwitch(settings, "Звуковое предупреждение",
                "звуковой сигнал RCTA после появления реального события",
                AppSettings.rctaSoundEnabled(this), (button, checked) -> {
                    AppSettings.setRctaSoundEnabled(this, checked);
                    RctaOverlayController.get(this).apply();
                    refresh();
                });
        settings.addView(settingsDivider());
        addInlineSwitch(settings, "Панель диагностики",
                "сразу показывает RCTA слева и справа на весь экран со звуком, пока не выключить",
                rctaDiagnosticsActive, this::toggleRctaDiagnostics);
        root.addView(settings);
    }

    private void toggleRctaDiagnostics(CompoundButton button, boolean enabled) {
        rctaDiagnosticsActive = enabled;
        cancelRctaDemoSequence();
        if (enabled) {
            showRctaDemoAlert(true, true, true);
        } else {
            hideRctaDemoAlert();
        }
        AppLog.line(this, "RCTA diagnostics panel: " + enabled);
        refresh();
    }

    private void setRctaStyle(int style) {
        AppSettings.setRctaStyle(this, style);
        if (rctaPreview != null) {
            rctaPreview.setStyleType(AppSettings.rctaStyle(this));
        }
        RctaOverlayController.get(this).apply();
        AppLog.line(this, "RCTA style: " + AppSettings.rctaStyleLabel(this));
        renderTab();
        refresh();
    }

    private void setRctaColor(int color) {
        AppSettings.setRctaColor(this, color);
        if (rctaPreview != null) {
            rctaPreview.setAlertColor(AppSettings.rctaColor(this));
        }
        RctaOverlayController.get(this).apply();
        AppLog.line(this, "RCTA color: " + AppSettings.rctaColorLabel(this));
        renderTab();
        refresh();
    }

    private void setRctaArrowCount(int value) {
        AppSettings.setRctaArrowCount(this, value);
        if (rctaPreview != null) {
            rctaPreview.setArrowCount(AppSettings.rctaArrowCount(this));
        }
        RctaOverlayController.get(this).apply();
        AppLog.line(this, "RCTA arrows: " + AppSettings.rctaArrowCount(this));
        refresh();
    }

    private int rctaPreviewHeight() {
        if (isLandscapeWindow()) {
            return dp(screenWidthDp() >= 1100 ? 330 : 300);
        }
        return isCompact() ? dp(240) : dp(300);
    }

    private int rctaStyleColor(int style) {
        return choiceColor(AppSettings.rctaStyle(this) == style);
    }

    private int rctaColorChoice(int color) {
        return choiceColor(AppSettings.rctaColor(this) == color);
    }

    private View rctaArrowCountSlider() {
        LinearLayout box = rctaSliderBox();
        TextView title = text("Количество стрелок", isCompact() ? 15 : 17, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView value = text(rctaArrowCountText(AppSettings.rctaArrowCount(this)),
                isCompact() ? 12 : 14, COLOR_MUTED);

        SeekBar slider = new SeekBar(this);
        styleSettingsSeekBar(slider);
        int min = AppSettings.RCTA_ARROW_COUNT_MIN;
        int max = AppSettings.RCTA_ARROW_COUNT_MAX;
        slider.setMax(max - min);
        slider.setProgress(AppSettings.rctaArrowCount(this) - min);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int next = min + progress;
                setRctaArrowCount(next);
                value.setText(rctaArrowCountText(AppSettings.rctaArrowCount(MainActivity.this)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        box.addView(title);
        box.addView(value);
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sliderLp.setMargins(0, dp(6), 0, 0);
        box.addView(slider, sliderLp);
        return box;
    }

    private View rctaBackgroundAlphaSlider() {
        LinearLayout box = rctaSliderBox();

        TextView title = text("Прозрачность фона", isCompact() ? 15 : 17, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView value = text(rctaBackgroundAlphaText(AppSettings.rctaBackgroundAlpha(this)),
                isCompact() ? 12 : 14, COLOR_MUTED);
        value.setMaxLines(2);

        SeekBar slider = new SeekBar(this);
        styleSettingsSeekBar(slider);
        int min = AppSettings.RCTA_BACKGROUND_ALPHA_MIN;
        int max = AppSettings.RCTA_BACKGROUND_ALPHA_MAX;
        slider.setMax(max - min);
        slider.setProgress(AppSettings.rctaBackgroundAlpha(this) - min);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int next = min + progress;
                AppSettings.setRctaBackgroundAlpha(MainActivity.this, next);
                if (rctaPreview != null) {
                    rctaPreview.setBackgroundAlpha(AppSettings.rctaBackgroundAlpha(MainActivity.this));
                }
                RctaOverlayController.get(MainActivity.this).apply();
                value.setText(rctaBackgroundAlphaText(AppSettings.rctaBackgroundAlpha(MainActivity.this)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                AppLog.line(MainActivity.this, "RCTA background: "
                        + rctaBackgroundAlphaText(AppSettings.rctaBackgroundAlpha(MainActivity.this)));
                refresh();
            }
        });

        box.addView(title);
        box.addView(value);
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sliderLp.setMargins(0, dp(6), 0, 0);
        box.addView(slider, sliderLp);
        return box;
    }

    private LinearLayout rctaSliderBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setMinimumHeight(isCompact() ? dp(78) : dp(96));
        box.setBackground(settingsButtonBackground(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(4));
        box.setLayoutParams(lp);
        return box;
    }

    private void styleSettingsSeekBar(SeekBar slider) {
        if (Build.VERSION.SDK_INT < 21 || slider == null) return;
        slider.setThumbTintList(ColorStateList.valueOf(COLOR_ACCENT_BLUE));
        slider.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT_BLUE));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(63, 74, 89)));
    }

    private String rctaArrowCountText(int value) {
        return clamp(value, AppSettings.RCTA_ARROW_COUNT_MIN,
                AppSettings.RCTA_ARROW_COUNT_MAX) + " стрелки, диапазон 3-6";
    }

    private String rctaBackgroundAlphaText(int value) {
        return "фон " + rctaBackgroundPercent(value) + "%, стрелки и углы без прозрачности";
    }

    private int rctaBackgroundPercent(int value) {
        int max = Math.max(1, AppSettings.RCTA_BACKGROUND_ALPHA_MAX);
        return Math.round(clamp(value, AppSettings.RCTA_BACKGROUND_ALPHA_MIN,
                AppSettings.RCTA_BACKGROUND_ALPHA_MAX) * 100f / max);
    }

    private void refresh() {
        if (shouldRerenderTpms()) {
            renderTab();
        }
        if (status != null) status.setText(fullStatusText());
        if (tpmsStatus != null) {
            tpmsStatus.setText(tpmsStatusText());
        }
        if (tpmsDashboard != null) {
            tpmsDashboard.setWidgetMode(tpmsWidgetMode());
            tpmsDashboard.setMotionSpeedKmh(navigationSpeedKmh());
            tpmsDashboard.setNavigationState(StateStore.navigation());
            tpmsDashboard.setVehicleState(StateStore.vehicle());
        }
        if (navigationStatus != null) {
            navigationStatus.setText(navigationStatusText());
        }
        if (navigationDebugStatus != null) {
            navigationDebugStatus.setText(navigationDebugText());
        }
        if (mediaDebugStatus != null) {
            mediaDebugStatus.setText(mediaDebugText());
        }
        if (diagnosticsStatus != null) {
            diagnosticsStatus.setText(canbusStatusText());
        }
        if (canbusDebugStatus != null) {
            canbusDebugStatus.setText(canbusDebugText());
        }
        if (loggerStatus != null) {
            loggerStatus.setText(loggerText());
        }
        if (updatesStatus != null) {
            updatesStatus.setText(updatesText());
        }
        maybeShowLaunchUpdatePrompt();
        if (firmwareStatus != null) {
            firmwareStatus.setText(firmwareStatusText());
        }
        if (permissionSummary != null) {
            permissionSummary.setText(permissionSummaryText());
        }
        if (sasRatioStatus != null) {
            sasRatioStatus.setText(sasRatioText());
        }
        if (sasRatioInput != null && !sasRatioInput.hasFocus()) {
            sasRatioInput.setText(String.valueOf(AppSettings.sasRatio(this)));
        }
        updateOverlayToggle();
        updateMicroManeuverToggle();
        updateNavTbtToggle();
        updateOverspeedToggle();
        updateNavDebugToggle();
        updateCanbusDebugToggle();
        updateAmpEnabledToggle();
        updateMediaTabToggle();
        updateCallEnabledToggle();
        updateMediaDebugToggle();
        updateTpmsAlertsToggle();
        updateTpmsSoundToggle();
        updateAutoStartToggle();
        updateLogTabToggle();
        updateRawCanToggle();
        updateTpmsInputs();
        updateAmpInputs();
        updateFirmwareActionButton();
        if (log != null) log.setText(AppLog.lastLines(36));
    }

    private String fullStatusText() {
        return ""
                + "Версия: " + appVersionText() + "\n"
                + "Адаптер: " + StateStore.adapter().usbText
                + " | UID " + StateStore.adapter().uid
                + " | FW " + StateStore.adapter().firmware
                + " | RX " + timeText(StateStore.adapter().lastFrameAt)
                + " | Health " + StateStore.adapter().health + "\n"
                + "Разрешения: notify=" + yesNo(notificationPermissionGranted())
                + " listener=" + yesNo(notificationListenerEnabled())
                + " gps=" + yesNo(locationPermissionGranted()) + "\n"
                + "CAN: SAS ratio=" + AppSettings.sasRatio(this)
                + " | автозапуск=" + (AppSettings.autoStart(this) ? "фон" : "выкл") + "\n"
                + "AMP: " + StateStore.amp().summary() + "\n"
                + StateStore.tpms().summary() + "\n"
                + "Настройки: " + displayStatus(StateStore.updates().appStatus)
                + " | " + displayStatus(StateStore.updates().firmwareStatus);
    }

    private String navigationStatusText() {
        return StateStore.navigation().summary()
                + "\nИсточник: " + AppSettings.navSourceLabel(this)
                + "\nАдрес на панели: " + navTextModeText(AppSettings.navTextMode(this))
                + "\nРежим маршрута: " + NavigationModeSettings.label(this)
                + "\nПревышение: " + yesNo(AppSettings.navOverspeedTextEnabled(this));
    }

    private String navigationDebugText() {
        return StateStore.navigation().details()
                + "\nКомпас: " + compassStatusText()
                + "\nПоверх экрана: " + yesNo(AppSettings.navOverlayEnabled(this))
                + "\nРежим маршрута: " + NavigationModeSettings.label(this)
                + " | превышение: " + yesNo(AppSettings.navOverspeedTextEnabled(this))
                + "\n2GIS: " + dgisStatusText();
    }

    private String canbusStatusText() {
        return (StateStore.adapter().usbConnected ? "Подключён" : "Не подключён")
                + "  ·  " + StateStore.adapter().usbText
                + "\nID: " + emptyDash(StateStore.adapter().uid)
                + "  ·  FW: " + emptyDash(StateStore.adapter().firmware)
                + "  ·  RX: " + timeText(StateStore.adapter().lastFrameAt);
    }

    private String canbusTemperatureText() {
        VehicleState vehicle = StateStore.vehicle();
        String outside = vehicle.outsideTempKnown ? vehicle.outsideTempC + "C" : "нет данных";
        String engine = vehicle.engineTempKnown ? vehicle.engineTempC + "C" : "нет данных";
        return "Выбрано: " + AppSettings.canbusTemperatureSourceLabel(this)
                + "\nУлица: " + outside + "  ·  двигатель: " + engine;
    }

    private String sasRatioText() {
        int ratio = AppSettings.sasRatio(this);
        String level;
        if (ratio < 8) level = "мягкое смещение";
        else if (ratio <= 18) level = "среднее смещение";
        else level = "сильное смещение";
        return "ratio " + ratio + " · " + level + " · сохраняется в адаптере";
    }

    private String canbusDebugText() {
        return "UID: " + emptyDash(StateStore.adapter().uid)
                + " | FW: " + emptyDash(StateStore.adapter().firmware)
                + " | RX: " + timeText(StateStore.adapter().lastFrameAt)
                + " | health: " + StateStore.adapter().health
                + "\n" + StateStore.vehicle().summary()
                + "\nЗапись CAN: " + yesNo(StateStore.diagnostics().rawCanEnabled)
                + " | кадров: " + StateStore.diagnostics().capturedFrames
                + "\nПоследний кадр: " + emptyDash(StateStore.diagnostics().lastFrame)
                + "\nФайл: " + emptyDash(StateStore.diagnostics().lastSaved);
    }

    private String compassStatusText() {
        return "авто, во время активного маршрута не перекрывает навигацию";
    }

    private String appVersionText() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            String name = info.versionName == null ? "" : info.versionName;
            return name + " (" + code + ")";
        } catch (Exception ignored) {
            return "не получена";
        }
    }

    private String appBuildText() {
        return "Kia " + appVersionText()
                + "\nПакет: " + getPackageName()
                + "\nAPK: kia_" + appVersionCodeText() + ".apk";
    }

    private String appVersionCodeText() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return String.valueOf(code);
        } catch (Exception ignored) {
            return "?";
        }
    }

    private String dgisStatusText() {
        return "mode=" + AppSettings.navSourceLabel(this)
                + "; dashboard service + уведомления; listener=" + yesNo(notificationListenerEnabled())
                + " notify=" + yesNo(notificationPermissionGranted())
                + " gps=" + yesNo(locationPermissionGranted());
    }

    private boolean notificationPermissionGranted() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean mediaAudioPermissionGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean bluetoothPermissionGranted() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationPermissionGranted() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean backgroundLocationPermissionGranted() {
        return Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean gpsPermissionReady() {
        return locationPermissionGranted() && backgroundLocationPermissionGranted();
    }

    private boolean writeSettingsGranted() {
        return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
    }

    private boolean overlayPermissionReady() {
        return (!AppSettings.navOverlayEnabled(this) && !AppSettings.mediaOverlayEnabled(this))
                || Build.VERSION.SDK_INT < 23
                || Settings.canDrawOverlays(this);
    }

    private boolean notificationListenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        String flat = new ComponentName(this, MediaNotificationListener.class).flattenToString();
        return enabled.contains(flat);
    }

    private int missingPermissionCount() {
        int count = 0;
        if (!notificationPermissionGranted()) count++;
        if (!mediaAudioPermissionGranted() || !notificationListenerEnabled()) count++;
        if (!bluetoothPermissionGranted()) count++;
        if (!gpsPermissionReady()) count++;
        if (!batteryOptimizationIgnored()) count++;
        if (!overlayPermissionReady()) count++;
        return count;
    }

    private String permissionSummaryText() {
        int missing = missingPermissionCount();
        if (missing == 0) return "Все основные разрешения активны";
        return "Нужно включить: " + missing;
    }

    private String overlayPermissionHint() {
        boolean nav = AppSettings.navOverlayEnabled(this);
        boolean media = AppSettings.mediaOverlayEnabled(this);
        if (nav && media) return "навигация и медиа";
        if (nav) return "навигация";
        if (media) return "медиа";
        return "не нужно";
    }

    private static String yesNo(boolean value) {
        return value ? "ok" : "нет";
    }

    private void setMediaProfile(int profile) {
        AppSettings.setMediaProfile(this, profile);
        AppService.start(this);
        AppLog.line(this, "Media profile: " + AppSettings.mediaProfileLabel(this));
        MediaFeature.get(this).resendCurrent("media profile " + AppSettings.mediaProfileLabel(this));
        renderTab();
        refresh();
    }

    private void toggleCallEnabled(CompoundButton button, boolean enabled) {
        AppSettings.setCallEnabled(this, enabled);
        AppService.start(this);
        if (!enabled) {
            CallFeature.get(this).stop();
        }
        AppLog.line(this, "BT call feature: " + enabled);
        renderTab();
        refresh();
    }

    private void toggleMediaDebug(CompoundButton button, boolean enabled) {
        AppSettings.setMediaOverlayEnabled(this, enabled);
        AppService.start(this);
        if (enabled && !MediaOverlayController.canDrawOverlays(this)) {
            AppLog.line(this, "Media overlay: открой разрешение поверх окон");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                AppLog.line(this, "Media overlay settings failed " + e.getClass().getSimpleName());
            }
        }
        MediaOverlayController.get(this).apply();
        AppLog.line(this, "Media debug overlay: " + enabled);
        refresh();
    }

    private void setOtherMediaSourceMode(int mode) {
        AppSettings.setOtherMediaSourceMode(this, mode);
        AppLog.line(this, "Media OTHER source mode: " + AppSettings.otherMediaSourceLabel(this));
        MediaFeature.get(this).resendCurrent("other source mode " + AppSettings.otherMediaSourceLabel(this));
        renderTab();
        refresh();
    }

    private void setMediaTextMode(int mode) {
        AppSettings.setMediaTextMode(this, mode);
        AppLog.line(this, "Media text mode: " + AppSettings.mediaTextModeLabel(this));
        MediaFeature.get(this).resendCurrent("media text mode " + AppSettings.mediaTextModeLabel(this));
        renderTab();
        refresh();
    }

    private View mediaProfileAction(int profile, String title, String hint) {
        boolean selected = AppSettings.mediaProfile(this) == profile;
        return action(title, hint, choiceColor(selected), selected, () -> setMediaProfile(profile));
    }

    private void setCallSourceMode(int mode) {
        AppSettings.setCallSourceMode(this, mode);
        AppLog.line(this, "Call source mode: " + AppSettings.callSourceLabel(this));
        CallFeature.get(this).tick();
        renderTab();
        refresh();
    }

    private int otherModeColor(int mode) {
        return choiceColor(AppSettings.otherMediaSourceMode(this) == mode);
    }

    private int mediaTextModeColor(int mode) {
        return choiceColor(AppSettings.mediaTextMode(this) == mode);
    }

    private View callSourceAction(int mode, String title, String hint) {
        boolean selected = AppSettings.callSourceMode(this) == mode;
        return action(title, hint, choiceColor(selected), selected,
                () -> setCallSourceMode(mode));
    }

    private String mediaStatusText() {
        MediaState media = StateStore.media();
        StringBuilder out = new StringBuilder();
        boolean hasTitle = !media.title.isEmpty();
        boolean hasArtist = !media.artist.isEmpty() && !"<unknown>".equalsIgnoreCase(media.artist);
        boolean hasSource = !media.source.isEmpty();
        if (!hasTitle && !hasArtist && !hasSource) {
            out.append("нет данных");
        } else {
            if (hasTitle) out.append("Трек: ").append(media.title).append('\n');
            if (hasArtist) out.append("Исполнитель: ").append(media.artist).append('\n');
            if (hasSource) out.append("Источник: ").append(media.source).append('\n');
            out.append(media.playing ? "Играет" : "Пауза");
            if (media.durationMs >= 0L) out.append(" | ").append(formatMediaDuration(media.durationMs));
        }
        out.append("\nПрофиль: ").append(AppSettings.mediaProfileLabel(this));
        out.append("\nРежим other: ").append(AppSettings.otherMediaSourceLabel(this));
        out.append("\nТекст панели: ").append(AppSettings.mediaTextModeLabel(this));
        return out.toString();
    }

    private String mediaDebugText() {
        MediaState media = StateStore.media();
        String frequency = RadioStationStore.currentFrequency(media);
        StringBuilder out = new StringBuilder();
        out.append("profile=").append(AppSettings.mediaProfileLabel(this));
        out.append("\ncapture=").append(mediaCaptureModeText());
        out.append("\ntextId=").append(mediaTextCommandDebug(media));
        out.append(" other=").append(AppSettings.otherMediaSourceLabel(this));
        out.append("\nsource=").append(emptyDash(media.source));
        out.append("\npackage=").append(emptyDash(media.packageName));
        out.append("\nartist=").append(emptyDash(media.artist));
        out.append("\ntitle=").append(emptyDash(media.title));
        out.append("\nplaying=").append(media.playing);
        out.append(" duration=").append(media.durationMs);
        out.append("\nradio=").append(frequency.isEmpty()
                ? "-"
                : RadioStationStore.currentBand(media) + " " + frequency);
        out.append(" stations=").append(RadioStationStore.entries(this).size());
        out.append("\nnotify=").append(yesNo(notificationPermissionGranted()));
        out.append(" listener=").append(yesNo(notificationListenerEnabled()));
        out.append(" audio=").append(yesNo(mediaAudioPermissionGranted()));
        out.append("\nusb=").append(StateStore.adapter().usbText);
        out.append(" rx=").append(timeText(StateStore.adapter().lastFrameAt));
        return out.toString();
    }

    private String mediaCaptureModeText() {
        int profile = AppSettings.mediaProfile(this);
        if (profile == AppSettings.MEDIA_PROFILE_TEYES) return "teyes_widget+spd";
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL) return "media_session+uart_real_text_only";
        if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID) return "media_session+radio_db";
        return "off";
    }

    private String mediaTextCommandDebug(MediaState media) {
        String text = ((media == null ? "" : media.source) + " "
                + (media == null ? "" : media.packageName)).toLowerCase(Locale.US);
        if (text.contains("fm") || text.contains("am") || text.contains("radio")
                || text.contains("радио")) return "0x20 radio";
        if (text.contains("usb") || text.contains("spd.media")) return "0x22 usb";
        if (text.contains("yandex") || text.contains("яндекс")
                || text.contains("my music") || text.contains("teyes")) return "0x24 my_music";
        if (text.contains("carplay") || text.contains("car play")) return "0x25 carplay";
        if (text.contains("bluetooth") || text.contains("btmusic")
                || text.contains("a2dp") || text.contains("avrcp")) return "0x21 bt";
        switch (AppSettings.otherMediaSourceMode(this)) {
            case AppSettings.OTHER_SOURCE_USB:
                return "0x22 usb";
            case AppSettings.OTHER_SOURCE_BLUETOOTH:
                return "0x21 bt";
            case AppSettings.OTHER_SOURCE_MY_MUSIC:
                return "0x24 my_music";
            case AppSettings.OTHER_SOURCE_CARPLAY:
                return "0x25 carplay";
            case AppSettings.OTHER_SOURCE_ANDROID:
            default:
                return "0x23 android_auto";
        }
    }

    private String callStatusText() {
        CallState call = StateStore.call();
        if (call == null || !call.active) {
            return "нет активного звонка\nИсточник панели: " + AppSettings.callSourceLabel(this);
        }
        return call.summary(System.currentTimeMillis())
                + "\nИсточник панели: " + AppSettings.callSourceLabel(this);
    }

    private String tpmsStatusText() {
        TpmsState state = StateStore.tpms();
        if (state == null || !state.hasData()) {
            return "нет данных от датчиков\nИсточник: " + emptyDash(state == null ? "" : state.source)
                    + " | RX " + timeText(state == null ? 0L : state.updatedAt);
        }
        return "Источник: " + emptyDash(state.source)
                + " | RX " + timeText(state.updatedAt)
                + "\n" + (TpmsAlertController.hasCriticalWarnings(this, state)
                ? "критичное предупреждение"
                : (TpmsAlertController.hasWarnings(this, state) ? "есть предупреждение" : "все колеса в норме"));
    }

    private String tpmsAlertText() {
        TpmsState state = StateStore.tpms();
        if (!AppSettings.tpmsAlertsEnabled(this)) {
            return "Оповещения выключены\n" + tpmsThresholdText();
        }
        if (state == null || !state.hasData()) {
            return "Ожидаю данные TPMS\n" + tpmsThresholdText();
        }
        String details = TpmsAlertController.warningDetails(this, state);
        if (details.length() == 0) return "Все колеса в норме\n" + tpmsThresholdText();
        return details + "\n" + tpmsThresholdText();
    }

    private String tpmsThresholdText() {
        return "давление " + pressureBarText(AppSettings.tpmsLowPressureKpa(this)) + "-"
                + pressureBarText(AppSettings.tpmsHighPressureKpa(this)) + " bar"
                + " | температура " + AppSettings.tpmsLowTempC(this) + "-"
                + AppSettings.tpmsHighTempC(this) + "C";
    }

    private static String pressureBarText(int pressureKpa) {
        return String.format(Locale.US, "%.1f", pressureKpa / 100f);
    }

    private static String wheelName(int wheel) {
        switch (wheel) {
            case TpmsState.WHEEL_FL:
                return "FL";
            case TpmsState.WHEEL_FR:
                return "FR";
            case TpmsState.WHEEL_RL:
                return "RL";
            case TpmsState.WHEEL_RR:
                return "RR";
            default:
                return "--";
        }
    }

    private static String wheelFullName(int wheel) {
        switch (wheel) {
            case TpmsState.WHEEL_FL:
                return "Перед лев.";
            case TpmsState.WHEEL_FR:
                return "Перед прав.";
            case TpmsState.WHEEL_RL:
                return "Зад лев.";
            case TpmsState.WHEEL_RR:
                return "Зад прав.";
            default:
                return "Колесо";
        }
    }

    private int warningColor(int warning) {
        switch (warning) {
            case TpmsAlertController.WARNING_LOW_PRESSURE:
            case TpmsAlertController.WARNING_LOW_TEMP:
                return COLOR_WARNING;
            case TpmsAlertController.WARNING_HIGH_PRESSURE:
                return COLOR_ROSE;
            case TpmsAlertController.WARNING_HIGH_TEMP:
            case TpmsAlertController.WARNING_FAST_LEAKAGE:
                return COLOR_DANGER;
            case TpmsAlertController.WARNING_LOW_BATTERY:
                return COLOR_VIOLET;
            default:
                return COLOR_ACCENT;
        }
    }

    private static String formatMediaDuration(long value) {
        long seconds = Math.max(0L, value) / 1000L;
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        return minutes + ":" + (rest < 10 ? "0" : "") + rest;
    }

    private String timeText(long value) {
        if (value <= 0L) return "нет";
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(value));
    }

    private String loggerWarningText() {
        return "Переводит адаптер в режим gs_usb для записи CAN. Для возврата к обычной прошивке отключи адаптер, подключи его с зажатой кнопкой и прошей штатный BIN в разделе Общие.";
    }

    private String loggerText() {
        return "Прошивка: " + displayStatus(StateStore.updates().firmwareStatus)
                + "\nUSB gs_usb: " + GsUsbCanLogger.get(this).statusText()
                + "\nШина: " + AppSettings.loggerBusLabel(this)
                + "\nЗапись: " + StateStore.diagnostics().rawCanEnabled
                + " | кадров: " + StateStore.diagnostics().capturedFrames
                + "\nПоследний кадр: " + emptyDash(StateStore.diagnostics().lastFrame)
                + "\nФайл: " + emptyDash(StateStore.diagnostics().lastSaved);
    }

    private static String emptyDash(String value) {
        return value == null || value.length() == 0 ? "-" : value;
    }

    private void confirmFlashLoggerFirmware() {
        new AlertDialog.Builder(this)
                .setTitle("Прошить gs_usb?")
                .setMessage(loggerWarningText())
                .setPositiveButton("Прошить", (dialog, which) -> flashLoggerFirmware())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void flashLoggerFirmware() {
        AppLog.line(this, "CAN diagnostics firmware: flash gs_updated.bin");
        firmwareUpdater.flashBundled("logger");
        refresh();
    }

    private void toggleRaw(CompoundButton button, boolean enabled) {
        setRawLogging(enabled);
    }

    private void setLoggerBusMode(int mode) {
        AppSettings.setLoggerBusMode(this, mode);
        AppLog.line(this, "CAN diagnostics bus: " + AppSettings.loggerBusLabel(this));
        renderTab();
        refresh();
    }

    private int loggerBusColor(int mode) {
        return choiceColor(AppSettings.loggerBusMode(this) == mode);
    }

    private void setRawLogging(boolean enabled) {
        AppSettings.setDebugCan(this, enabled);
        AdapterGateway.get(this).setRawStream(false);
        GsUsbCanLogger.get(this).setRecording(enabled);
        AppLog.line(this, "CAN diagnostics gs_usb: " + enabled);
        refresh();
    }

    private void saveCanLog() {
        new Thread(() -> {
            try {
                CanLogger.get(this).saveCompressed();
            } catch (Exception e) {
                AppLog.line(this, "CAN log save failed " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }, "kia-canbus-save-can").start();
    }

    private void clearCanLog() {
        CanLogger.get(this).clear();
        refresh();
    }

    private void adjustSasRatio(int delta) {
        setSasRatio(AppSettings.sasRatio(this) + delta);
    }

    private void resetSasRatio() {
        setSasRatio(10);
    }

    private void toggleAutoStart(CompoundButton button, boolean enabled) {
        AppSettings.setAutoStart(this, enabled);
        if (enabled) AppService.start(this);
        AppLog.line(this, "Autostart background: " + enabled);
        refresh();
    }

    private void toggleMediaTab(CompoundButton button, boolean enabled) {
        AppSettings.setMediaTabVisible(this, enabled);
        if (!enabled && selectedTab == TAB_MEDIA) selectedTab = TAB_TPMS;
        if (!enabled && settingsTab == SETTINGS_MEDIA) settingsTab = SETTINGS_GENERAL;
        AppLog.line(this, "Media settings tab visible: " + enabled);
        setContentView(buildUi());
        applyImmersiveMode();
        refresh();
    }

    private void toggleLogTab(CompoundButton button, boolean enabled) {
        AppSettings.setLogTabVisible(this, enabled);
        if (!enabled && selectedTab == TAB_LOG) selectedTab = TAB_SETTINGS;
        if (!enabled && settingsTab == SETTINGS_LOG) settingsTab = SETTINGS_GENERAL;
        AppLog.line(this, "CAN diagnostics tab visible: " + enabled);
        setContentView(buildUi());
        applyImmersiveMode();
        refresh();
    }

    private void toggleTpmsAlerts(CompoundButton button, boolean enabled) {
        AppSettings.setTpmsAlertsEnabled(this, enabled);
        TpmsAlertController.get(this).apply(StateStore.tpms());
        AppLog.line(this, "TPMS alerts: " + enabled);
        renderTab();
        refresh();
    }

    private void toggleTpmsSoundAlerts(CompoundButton button, boolean enabled) {
        AppSettings.setTpmsSoundAlertsEnabled(this, enabled);
        if (!enabled) {
            TpmsWarningOverlayController.get(this).stopSound();
        } else {
            TpmsAlertController.get(this).apply(StateStore.tpms());
        }
        AppLog.line(this, "TPMS sound alerts: " + enabled);
        refresh();
    }

    private void setTpmsLowPressure(int value) {
        AppSettings.setTpmsLowPressureKpa(this, value);
        onTpmsThresholdChanged("low pressure " + AppSettings.tpmsLowPressureKpa(this));
    }

    private void setTpmsHighPressure(int value) {
        AppSettings.setTpmsHighPressureKpa(this, value);
        onTpmsThresholdChanged("high pressure " + AppSettings.tpmsHighPressureKpa(this));
    }

    private void setTpmsLowTemp(int value) {
        AppSettings.setTpmsLowTempC(this, value);
        onTpmsThresholdChanged("low temp " + AppSettings.tpmsLowTempC(this));
    }

    private void setTpmsHighTemp(int value) {
        AppSettings.setTpmsHighTempC(this, value);
        onTpmsThresholdChanged("high temp " + AppSettings.tpmsHighTempC(this));
    }

    private void onTpmsThresholdChanged(String label) {
        TpmsAlertController.get(this).apply(StateStore.tpms());
        AppLog.line(this, "TPMS threshold: " + label);
        renderTab();
        refresh();
    }

    private void toggleNavDebug(CompoundButton button, boolean enabled) {
        AppSettings.setNavDebugVisible(this, enabled);
        AppSettings.setNavOverlayEnabled(this, enabled);
        AppService.start(this);
        if (enabled && !NavigationOverlayController.canDrawOverlays(this)) {
            AppLog.line(this, "Nav overlay: открой разрешение поверх окон");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                AppLog.line(this, "Nav overlay settings failed " + e.getClass().getSimpleName());
            }
        }
        NavigationOverlayController.get(this).apply();
        AppLog.line(this, "Navigation debug visible: " + enabled);
        refresh();
    }

    private void toggleCanbusDebug(CompoundButton button, boolean enabled) {
        AppSettings.setCanbusDebugVisible(this, enabled);
        AppLog.line(this, "CANBUS debug visible: " + enabled);
        renderTab();
        refresh();
    }

    private void toggleAmpEnabled(CompoundButton button, boolean enabled) {
        AppSettings.setAmpEnabled(this, enabled);
        AppService.start(this);
        if (enabled) {
            lastAmpRequestAt = 0L;
            requestAmpSettingsIfNeeded();
        }
        AppLog.line(this, "AMP feature: " + enabled);
        renderTab();
        refresh();
    }

    private void requestMissingPermissionsFromSettings() {
        askedWriteSettings = false;
        askedOverlay = false;
        askedBatteryOptimization = false;
        askedNotificationListener = false;
        specialPermissionWaiting = false;
        requestRuntimePermissions();
        handler.postDelayed(() -> {
            if (selectedTab == TAB_SETTINGS) renderTab();
            refresh();
        }, 700L);
    }

    private void setSasRatio(int value) {
        int ratio = clamp(value, 1, 255);
        AppSettings.setSasRatio(this, ratio);
        if (sasRatioInput != null) {
            sasRatioInput.setText(String.valueOf(ratio));
            sasRatioInput.setSelection(sasRatioInput.getText().length());
        }
        if (sasRatioStatus != null) sasRatioStatus.setText(sasRatioText());
        if (sasRatioPreview != null) sasRatioPreview.invalidate();
        AppService.start(this);
        AdapterGateway.get(this).send(AdapterCommand.loud("SAS ratio " + ratio,
                AdapterProtocol.sasRatio(ratio)));
        AppLog.line(this, "SAS ratio TX: " + ratio + " (adapter save)");
        refresh();
    }

    private void setCanbusTemperatureSource(int mode) {
        int clean = mode == AppSettings.CANBUS_TEMP_ENGINE
                ? AppSettings.CANBUS_TEMP_ENGINE : AppSettings.CANBUS_TEMP_OUTSIDE;
        AppSettings.setCanbusTemperatureSource(this, clean);
        AppService.start(this);
        AdapterGateway.get(this).send(AdapterCommand.loud(
                "CANBUS temperature source " + AppSettings.canbusTemperatureSourceLabel(this),
                AdapterProtocol.canbusTemperatureEngine(clean == AppSettings.CANBUS_TEMP_ENGINE)));
        AppLog.line(this, "CANBUS temp source TX: " + AppSettings.canbusTemperatureSourceLabel(this));
        renderTab();
        refresh();
    }

    private int canbusTempSourceColor(int mode) {
        return choiceColor(AppSettings.canbusTemperatureSource(this) == mode);
    }

    private int readSasRatioInput(int fallback) {
        if (sasRatioInput == null) return fallback;
        String raw = sasRatioInput.getText() == null ? "" : sasRatioInput.getText().toString().trim();
        if (raw.isEmpty()) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void ampBassUp() {
        AmpState s = StateStore.amp();
        AmpController.get(this).setSettings(new AmpState(s.volume, s.balance, s.fader,
                Math.min(20, s.bass + 1), s.mid, s.treble, s.mode, System.currentTimeMillis()), true);
    }

    private void toggleNavTbt(CompoundButton button, boolean enabled) {
        NavigationFeature.get(this).setTbtMode(enabled);
        refresh();
    }

    private void toggleNavFinishDirection(CompoundButton button, boolean enabled) {
        NavigationFeature.get(this).setFinishDirectionMode(enabled);
        AppService.start(this);
        refresh();
    }

    private void toggleFinishCompassAuto(CompoundButton button, boolean enabled) {
        NavigationFeature.get(this).setFinishCompassAuto(enabled);
        AppService.start(this);
        renderTab();
        refresh();
    }

    private void nextNavTextMode() {
        NavigationFeature.get(this).setTextMode((AppSettings.navTextMode(this) + 1) % 3);
    }

    private void setNavTextMode(int mode) {
        NavigationFeature.get(this).setTextMode(mode);
        updateNavTextModeButtons();
        refresh();
    }

    private void setNavSourceMode(int mode) {
        AppService.start(this);
        NavigationFeature.get(this).setSourceMode(mode);
        updateNavSourceModeButtons();
        refresh();
    }

    private void setNavRouteOutputMode(int mode) {
        AppService.start(this);
        NavigationFeature.get(this).setOutputMode(mode);
        updateNavRouteModeButtons();
        renderTab();
        refresh();
    }

    private int navSourceColor(int mode) {
        return choiceColor(AppSettings.navSourceMode(this) == mode);
    }

    private void updateNavSourceModeButtons() {
        updateChoiceButtons(navSourceModeViews, navSourceModeChecks, AppSettings.navSourceMode(this));
    }

    private void updateNavTextModeButtons() {
        updateChoiceButtons(navTextModeViews, navTextModeChecks, AppSettings.navTextMode(this));
    }

    private void updateNavRouteModeButtons() {
        updateChoiceButtons(navRouteModeViews, navRouteModeChecks, NavigationModeSettings.mode(this));
    }

    private View navSourceAction(int mode, String title, String hint) {
        return choiceAction(title, hint, AppSettings.navSourceMode(this) == mode,
                () -> setNavSourceMode(mode), navSourceModeViews, navSourceModeChecks, mode);
    }

    private View navRouteModeAction(int mode, String title, String hint) {
        return choiceAction(title, hint, NavigationModeSettings.mode(this) == mode,
                () -> setNavRouteOutputMode(mode), navRouteModeViews, navRouteModeChecks, mode);
    }

    private View navAddressAction(int mode, String title, String hint) {
        return choiceAction(title, hint, AppSettings.navTextMode(this) == mode,
                () -> setNavTextMode(mode), navTextModeViews, navTextModeChecks, mode);
    }

    private View navFinishDirectionModeAction() {
        boolean enabled = AppSettings.navFinishDirectionMode(this);
        return action(enabled ? "Стрелка вкл" : "Стрелка выкл",
                "отдельно затирает обычную навигацию и TBT",
                choiceColor(enabled), enabled, () -> {
                    toggleNavFinishDirection(null, !AppSettings.navFinishDirectionMode(this));
                    renderTab();
                    refresh();
                });
    }

    private View navMicroDistanceCycleAction(boolean normalMode) {
        int meters = AppSettings.navMicroMaxDistanceMeters(this);
        boolean enabled = normalMode && AppSettings.navMicroManeuvers(this);
        String hint = !normalMode ? "доступно только в обычном режиме"
                : enabled ? "когда ассистент может заменить основной манёвр"
                : "сначала включите ассистент подсказок";
        View button = action("Порог " + navMicroDistanceText(meters), hint,
                enabled ? COLOR_ACCENT_BLUE : COLOR_MUTED, false, () -> {
                    if (!navNormalSettingsEnabled() || !AppSettings.navMicroManeuvers(this)) return;
                    setNavMicroMaxDistanceMeters(nextValue(meters, 150, 200, 250));
                });
        return navSettingsAction(button, enabled);
    }

    private View navMicroHoldAfterCycleAction(boolean normalMode) {
        int seconds = AppSettings.navMicroHoldSeconds(this);
        boolean enabled = normalMode && AppSettings.navMicroManeuvers(this);
        String hint = !normalMode ? "доступно только в обычном режиме"
                : enabled ? "сколько держать подсказку после проезда"
                : "сначала включите ассистент подсказок";
        View button = action("После проезда " + seconds + "с", hint,
                enabled ? COLOR_ACCENT_BLUE : COLOR_MUTED, false, () -> {
                    if (!navNormalSettingsEnabled() || !AppSettings.navMicroManeuvers(this)) return;
                    setNavMicroHoldSeconds(nextValue(seconds, 5, 10, 15));
                });
        return navSettingsAction(button, enabled);
    }

    private View navManeuverTextCycleAction() {
        int seconds = AppSettings.navManeuverTextSeconds(this);
        String title = seconds <= 0 ? "Текст выкл" : "Текст " + seconds + "с";
        return action(title, "сменить текст подсказки", seconds <= 0 ? COLOR_MUTED : COLOR_ACCENT_BLUE,
                seconds > 0, () -> setNavManeuverTextSeconds(nextValue(seconds, 0, 5, 10, 15)));
    }

    private View navEtaTimeModeAction() {
        int mode = AppSettings.navEtaTimeMode(this);
        boolean remaining = mode == AppSettings.NAV_ETA_TIME_REMAINING;
        return action("Время: " + AppSettings.navEtaTimeModeLabel(this).toLowerCase(Locale.ROOT),
                remaining ? "показывать сколько осталось ехать" : "показывать час прибытия",
                remaining ? COLOR_ACCENT : COLOR_ACCENT_BLUE, true,
                () -> setNavEtaTimeMode(remaining
                        ? AppSettings.NAV_ETA_TIME_ARRIVAL
                        : AppSettings.NAV_ETA_TIME_REMAINING));
    }

    private View navFinishLeadCycleAction() {
        int meters = AppSettings.navFinishDirectionLeadMeters(this);
        return action("Старт: " + navFinishLeadText(meters), "сменить порог",
                AppSettings.navFinishDirectionMode(this) ? COLOR_ACCENT_BLUE : COLOR_MUTED,
                AppSettings.navFinishDirectionMode(this), () -> setNavFinishLeadMeters(nextValue(meters, 0, 50, 100, 200, 500)));
    }

    private void setNavFinishLeadMeters(int meters) {
        AppSettings.setNavFinishDirectionLeadMeters(this, meters);
        AppLog.line(this, "Navigation finish direction lead meters: "
                + AppSettings.navFinishDirectionLeadMeters(this));
        renderTab();
        refresh();
    }

    private void setNavMicroHoldSeconds(int seconds) {
        AppSettings.setNavMicroHoldSeconds(this, seconds);
        AppLog.line(this, "Navigation maneuver assistant hold after seconds: "
                + AppSettings.navMicroHoldSeconds(this));
        renderTab();
        refresh();
    }

    private void setNavMicroMaxDistanceMeters(int meters) {
        AppSettings.setNavMicroMaxDistanceMeters(this, meters);
        AppLog.line(this, "Navigation maneuver assistant max distance meters: "
                + AppSettings.navMicroMaxDistanceMeters(this));
        renderTab();
        refresh();
    }

    private void setNavManeuverTextSeconds(int seconds) {
        AppSettings.setNavManeuverTextSeconds(this, seconds);
        AppLog.line(this, "Navigation maneuver text seconds: "
                + AppSettings.navManeuverTextSeconds(this));
        renderTab();
        refresh();
    }

    private void setNavEtaTimeMode(int mode) {
        AppSettings.setNavEtaTimeMode(this, mode);
        AppLog.line(this, "Navigation ETA time mode: " + AppSettings.navEtaTimeModeLabel(this));
        NavigationFeature.get(this).resendKnownRouteData();
        renderTab();
        refresh();
    }

    private String navFinishLeadText(int meters) {
        return meters <= 0 ? "сразу" : "за " + meters + " м";
    }

    private String navMicroDistanceText(int meters) {
        return meters + " м";
    }

    private String navManeuverTextText(int seconds) {
        return seconds <= 0 ? "выкл" : seconds + " сек";
    }

    private int nextValue(int current, int... values) {
        if (values == null || values.length == 0) return current;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    private int choiceColor(boolean selected) {
        return selected ? COLOR_ACCENT : COLOR_MUTED;
    }

    private String navTextModeText(int mode) {
        switch (mode) {
            case 1:
                return "после манёвра";
            case 2:
                return "финиш";
            case 0:
            default:
                return "текущая";
        }
    }

    private void toggleNavOverlay(CompoundButton button, boolean enabled) {
        AppSettings.setNavOverlayEnabled(this, enabled);
        AppService.start(this);
        if (enabled && !NavigationOverlayController.canDrawOverlays(this)) {
            AppLog.line(this, "Nav overlay: открой разрешение поверх окон");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                AppLog.line(this, "Nav overlay settings failed " + e.getClass().getSimpleName());
            }
        }
        NavigationOverlayController.get(this).apply();
        AppLog.line(this, "Nav overlay: " + enabled);
        refresh();
    }

    private void toggleMicroManeuvers(CompoundButton button, boolean enabled) {
        if (!navNormalSettingsEnabled()) {
            updateMicroManeuverToggle();
            return;
        }
        AppSettings.setNavMicroManeuvers(this, enabled);
        AppLog.line(this, "Navigation maneuver assistant: " + enabled);
        refresh();
    }

    private void toggleOverspeedText(CompoundButton button, boolean enabled) {
        AppSettings.setNavOverspeedTextEnabled(this, enabled);
        AppLog.line(this, "Navigation overspeed text: " + enabled);
        refresh();
    }

    private void addAmpSection() {
        addAmpSection(tabContent);
    }

    private void addAmpSection(LinearLayout root) {
        AmpState s = StateStore.amp();
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        ampEnabledToggle = addSettingsPanelSwitchHeader(panel, "AMP",
                "штатный усилитель: громкость, баланс, фейдер, тембр и режим",
                AppSettings.ampEnabled(this), this::toggleAmpEnabled);

        ampVisualizer = new AmpVisualizerView(this);
        ampVisualizer.setState(s, AppSettings.ampEnabled(this));
        LinearLayout.LayoutParams visualLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        visualLp.setMargins(0, dp(12), 0, dp(10));
        panel.addView(ampVisualizer, visualLp);

        if (!AppSettings.ampEnabled(this)) {
            root.addView(panel);
            return;
        }

        TextView summary = text(ampUiText(s), isCompact() ? 13 : 15, COLOR_MUTED);
        ampSummary = summary;
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.setMargins(0, dp(12), 0, dp(8));
        panel.addView(summary, summaryLp);

        View[] controls = new View[]{
                ampControl("volume", "Громкость", s.volume, 0, 40),
                ampControl("balance", "Баланс L/R", s.balance - 10, -10, 10),
                ampControl("fader", "Фейдер F/R", s.fader - 10, -10, 10),
                ampControl("bass", "Низкие", s.bass - 10, -10, 10),
                ampControl("mid", "Средние", s.mid - 10, -10, 10),
                ampControl("treble", "Высокие", s.treble - 10, -10, 10),
                ampControl("mode", "Режим", s.mode, 0, 32)
        };
        int columns = ampColumns();
        for (int i = 0; i < controls.length; i += columns) {
            LinearLayout line = row();
            for (int column = 0; column < columns; column++) {
                int index = i + column;
                line.addView(index < controls.length ? controls[index] : new View(this),
                        ampGridLayout(column, columns));
            }
            panel.addView(line);
        }
        root.addView(panel);
    }

    private void adjustAmp(String field, int delta) {
        setAmpValue(field, displayAmpValue(field) + delta);
    }

    private void setAmpValue(String field, int displayValue) {
        if (!AppSettings.ampEnabled(this)) return;
        AmpState s = StateStore.amp();
        int volume = s.volume;
        int balance = s.balance;
        int fader = s.fader;
        int bass = s.bass;
        int mid = s.mid;
        int treble = s.treble;
        int mode = s.mode;
        if ("volume".equals(field)) volume = clamp(displayValue, 0, 40);
        else if ("balance".equals(field)) balance = clamp(displayValue, -10, 10) + 10;
        else if ("fader".equals(field)) fader = clamp(displayValue, -10, 10) + 10;
        else if ("bass".equals(field)) bass = clamp(displayValue, -10, 10) + 10;
        else if ("mid".equals(field)) mid = clamp(displayValue, -10, 10) + 10;
        else if ("treble".equals(field)) treble = clamp(displayValue, -10, 10) + 10;
        else if ("mode".equals(field)) mode = clamp(displayValue, 0, 32);
        AmpController.get(this).setSettings(new AmpState(volume, balance, fader, bass, mid, treble,
                mode, System.currentTimeMillis()), true);
        refresh();
    }

    private void requestAmpSettingsIfNeeded() {
        if (!AppSettings.ampEnabled(this)) return;
        long now = System.currentTimeMillis();
        if (now - lastAmpRequestAt < 15000L) return;
        lastAmpRequestAt = now;
        AppService.start(this);
        AmpController.get(this).requestSettings();
    }

    private static String ampSigned(int value) {
        int signed = value - 10;
        return signed > 0 ? "+" + signed : String.valueOf(signed);
    }

    private static String ampUiText(AmpState s) {
        return "Сейчас: громкость " + s.volume
                + " · баланс " + ampSigned(s.balance)
                + " · фейдер " + ampSigned(s.fader)
                + " · низ " + ampSigned(s.bass)
                + " · середина " + ampSigned(s.mid)
                + " · верх " + ampSigned(s.treble)
                + " · режим " + s.mode;
    }

    private String updatesText() {
        UpdateState s = StateStore.updates();
        return "Kia: " + displayStatus(s.appStatus)
                + "\nYandex Navigator: " + displayStatus(s.navigatorStatus);
    }

    private String firmwareStatusText() {
        UpdateState s = StateStore.updates();
        String selected = pendingFirmwareUri == null ? "файл не выбран" : pendingFirmwareLabel;
        return "Статус: " + displayStatus(s.firmwareStatus)
                + "\nBIN: " + selected
                + (s.exclusiveUsbMode ? "\nUSB занят прошивкой" : "");
    }

    private static String displayStatus(String value) {
        if (value == null) return "";
        return value.replace("App update: ", "")
                .replace("Navigator update: ", "")
                .replace("Firmware update: ", "");
    }

    private String appUpdateTitle() {
        UpdateState s = StateStore.updates();
        if (s.appDownloading) return "APK загружается";
        if (s.appChecking) return "APK проверяется";
        return s.appAvailable || s.appDownloaded ? "Обновить APK" : "Проверить APK";
    }

    private String appUpdateHint() {
        UpdateState s = StateStore.updates();
        if (s.appAvailable || s.appDownloaded) return "скачать и открыть установщик";
        return "сравнить с GitHub";
    }

    private void runAppUpdateAction() {
        UpdateState s = StateStore.updates();
        if (s.appAvailable || s.appDownloaded) appUpdater.downloadAndInstall(this);
        else appUpdater.checkAsync(this);
        refresh();
    }

    private String navigatorUpdateTitle() {
        UpdateState s = StateStore.updates();
        if (s.navigatorDownloading) return "Скачиваю Навигатор";
        if (s.navigatorChecking) return "Проверяю Навигатор";
        if (s.navigatorInstalling) return "Установка Навигатора";
        return s.navigatorAvailable || s.navigatorDownloaded ? "Обновить Навигатор" : "Проверить Навигатор";
    }

    private String navigatorUpdateHint() {
        UpdateState s = StateStore.updates();
        if (s.navigatorDownloading || s.navigatorChecking || s.navigatorInstalling) {
            return displayStatus(s.navigatorStatus);
        }
        if (s.navigatorDownloaded) return "файлы готовы, открыть установщик Android";
        if (s.navigatorAvailable) return "последний Yandex Navigator с KIA-хуками";
        return "сравнить с GitHub";
    }

    private void runNavigatorUpdateAction() {
        UpdateState s = StateStore.updates();
        if (s.navigatorAvailable || s.navigatorDownloaded) navigatorUpdater.downloadAndInstall(this);
        else navigatorUpdater.checkAsync(this);
        refresh();
    }

    private LinearLayout firmwarePanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Прошивка адаптера",
                "ручной BIN-файл; после выбора кнопка меняется на прошивку", COLOR_ACCENT_BLUE);

        firmwareStatus = text(firmwareStatusText(), isCompact() ? 13 : 15, COLOR_MUTED);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(10), 0, dp(8));
        panel.addView(firmwareStatus, statusLp);

        panel.addView(firmwareManualAction(), singleActionLayout());
        return panel;
    }

    private void openManualFirmwarePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_FIRMWARE_FILE);
        } catch (Exception e) {
            AppLog.line(this, "Firmware file picker failed " + e.getClass().getSimpleName());
        }
    }

    private View firmwareManualAction() {
        firmwareActionButton = new FrameLayout(this);
        firmwareActionButton.setClickable(true);
        firmwareActionButton.setFocusable(true);
        firmwareActionButton.setMinimumHeight(isCompact() ? dp(74) : dp(90));
        firmwareActionButton.setBackground(settingsButtonBackground(pendingFirmwareUri != null
                || StateStore.updates().firmwareFlashing));

        firmwareProgressFill = new View(this);
        firmwareProgressFill.setBackground(round(softColor(COLOR_ACCENT, 145), dp(7),
                Color.TRANSPARENT, 0));
        firmwareActionButton.addView(firmwareProgressFill, new FrameLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.setPadding(dp(16), dp(10), dp(16), dp(10));
        firmwareActionText = text("", isCompact() ? 17 : 19, Color.WHITE);
        firmwareActionText.setTypeface(Typeface.DEFAULT_BOLD);
        firmwareActionHintText = text("", isCompact() ? 12 : 14, COLOR_MUTED);
        texts.addView(firmwareActionText);
        texts.addView(firmwareActionHintText);
        firmwareActionButton.addView(texts, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        firmwareActionButton.setOnClickListener(v -> runManualFirmwareAction());
        updateFirmwareActionButton();
        return firmwareActionButton;
    }

    private String firmwareFileName(Uri uri) {
        if (uri == null) return "manual.bin";
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && value.trim().length() > 0) return value.trim();
                }
            }
        } catch (Exception ignored) {
        }
        String tail = uri.getLastPathSegment();
        return tail == null || tail.trim().length() == 0 ? "manual.bin" : tail.trim();
    }

    private void runManualFirmwareAction() {
        if (firmwareUpdater.busy()) return;
        if (pendingFirmwareUri == null) {
            openManualFirmwarePicker();
            return;
        }
        Uri uri = pendingFirmwareUri;
        String label = pendingFirmwareLabel;
        pendingFirmwareUri = null;
        pendingFirmwareLabel = "";
        firmwareUpdater.flashFile(uri, label);
        refresh();
    }

    private LinearLayout generalAppPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Приложение",
                "фоновые режимы без отдельного блока состояния", COLOR_ACCENT_BLUE);

        autoStartToggle = addInlineSwitch(panel, "Автозапуск", "старт сервиса после загрузки",
                AppSettings.autoStart(this), this::toggleAutoStart);
        return panel;
    }

    private LinearLayout logVisibilityPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        logTabToggle = addSettingsPanelSwitchHeader(panel, "Диагностика CAN",
                "показывать отдельный пункт в верхнем меню настроек для записи шин",
                AppSettings.logTabVisible(this),
                this::toggleLogTab);
        return panel;
    }

    private LinearLayout mediaVisibilityPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        mediaTabToggle = addSettingsPanelSwitchHeader(panel, "Медиа",
                "показывать отдельный пункт в верхнем меню настроек",
                AppSettings.mediaTabVisible(this),
                this::toggleMediaTab);
        return panel;
    }

    private TextView generalVersionFooter() {
        TextView version = text("KIA " + appVersionText() + "  ·  " + getPackageName(),
                isCompact() ? 12 : 14, COLOR_MUTED);
        version.setGravity(Gravity.CENTER);
        version.setTypeface(Typeface.DEFAULT_BOLD);
        version.setPadding(dp(12), dp(12), dp(12), dp(22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(8));
        version.setLayoutParams(lp);
        return version;
    }

    private LinearLayout generalUpdatesPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Обновления приложений",
                "Kia APK и наш Yandex Navigator устанавливаются отсюда", COLOR_ACCENT_BLUE);

        updatesStatus = text(updatesText(), isCompact() ? 13 : 15, COLOR_MUTED);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(10), 0, dp(6));
        panel.addView(updatesStatus, statusLp);

        addActionGrid(panel,
                action(appUpdateTitle(), appUpdateHint(), Color.rgb(32, 108, 126), this::runAppUpdateAction),
                action(navigatorUpdateTitle(), navigatorUpdateHint(), Color.rgb(32, 108, 126), this::runNavigatorUpdateAction));
        return panel;
    }

    private void addPermissionSection(LinearLayout root) {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "Разрешения",
                "только то, что реально влияет на медиа, звонки, навигацию и фон", COLOR_ACCENT_BLUE);

        permissionSummary = text(permissionSummaryText(), isCompact() ? 13 : 15, COLOR_MUTED);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.setMargins(0, dp(10), 0, dp(8));
        panel.addView(permissionSummary, summaryLp);

        View[] items = new View[]{
                permissionTile("Уведомления", notificationPermissionGranted(), "фоновые события"),
                permissionTile("Медиа", mediaAudioPermissionGranted() && notificationListenerEnabled(), "трек и артист"),
                permissionTile("Bluetooth", bluetoothPermissionGranted(), "BT звонок"),
                permissionTile("GPS", gpsPermissionReady(), "навигация в фоне"),
                permissionTile("Батарея", batteryOptimizationIgnored(), "не выгружать"),
                permissionTile("Поверх окон", overlayPermissionReady(),
                        overlayPermissionHint())
        };
        int columns = actionColumns(items.length);
        for (int i = 0; i < items.length; i += columns) {
            LinearLayout line = row();
            for (int column = 0; column < columns; column++) {
                int index = i + column;
                line.addView(index < items.length ? items[index] : new View(this),
                        actionLayout(column, columns));
            }
            panel.addView(line);
        }
        if (missingPermissionCount() > 0) {
            panel.addView(action("Запросить", "включить недостающие разрешения", COLOR_WARNING,
                    this::requestMissingPermissionsFromSettings),
                    singleActionLayout());
        }
        root.addView(panel);
    }

    private View permissionTile(String title, boolean granted, String hint) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        tile.setBackground(settingsButtonBackground(granted));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, isCompact() ? 13 : 15, COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView state = text((granted ? "есть" : "нужно") + " · " + hint,
                isCompact() ? 11 : 12, granted ? COLOR_MUTED : COLOR_WARNING);
        texts.addView(name);
        texts.addView(state);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tile.addView(texts, textLp);
        if (!granted) {
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setOnClickListener(v -> requestMissingPermissionsFromSettings());
        }
        tile.setMinimumHeight(isCompact() ? dp(58) : dp(66));
        return tile;
    }

    private void addSection(LinearLayout root, String title, String description, View... actions) {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, title, description, COLOR_ACCENT_BLUE);
        addActionGrid(panel, actions);
        root.addView(panel);
    }

    private void addActionGrid(LinearLayout root, View... actions) {
        int columns = actionColumns(actions.length);
        addActionGridColumns(root, columns, actions);
    }

    private void addActionGridColumns(LinearLayout root, int columns, View... actions) {
        columns = Math.max(1, Math.min(columns, Math.max(1, actions.length)));
        for (int i = 0; i < actions.length; i += columns) {
            LinearLayout line = row();
            for (int column = 0; column < columns; column++) {
                int index = i + column;
                View item = index < actions.length ? actions[index] : new View(this);
                line.addView(item, actionLayout(column, columns));
            }
            root.addView(line);
        }
    }

    private View action(String title, String hint, int color, Runnable action) {
        return action(title, hint, color, color == COLOR_ACCENT, action);
    }

    private View action(String title, String hint, int color, boolean selected, Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setMinimumHeight(isCompact() ? dp(74) : dp(94));
        box.setClickable(true);
        box.setFocusable(true);
        box.setBackground(settingsActionBackground(selected, color));
        View.OnClickListener click = v -> {
            rememberScrollPosition();
            action.run();
        };
        box.setOnClickListener(click);

        LinearLayout header = row();
        TextView name = text(title, isCompact() ? 16 : 18, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(name, nameLp);
        TextView check = text("выбрано", isCompact() ? 11 : 12, selected ? COLOR_ACCENT_BLUE : COLOR_MUTED);
        check.setGravity(Gravity.CENTER);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setMinWidth(dp(58));
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        header.addView(check);
        TextView sub = text(hint, isCompact() ? 12 : 14, COLOR_MUTED);
        sub.setMaxLines(2);
        box.addView(header);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(4), 0, 0);
        box.addView(sub, subLp);
        return box;
    }

    private View navSettingsAction(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
        if (!enabled) {
            view.setClickable(false);
            view.setFocusable(false);
            view.setOnClickListener(null);
        }
        return view;
    }

    private View choiceAction(String title, String hint, boolean selected, Runnable action,
                              View[] views, TextView[] checks, int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setMinimumHeight(isCompact() ? dp(74) : dp(94));
        box.setClickable(true);
        box.setFocusable(true);
        box.setBackground(settingsActionBackground(selected, COLOR_ACCENT_BLUE));
        View.OnClickListener click = v -> {
            rememberScrollPosition();
            action.run();
        };
        box.setOnClickListener(click);

        LinearLayout header = row();
        TextView name = text(title, isCompact() ? 16 : 18, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(name, nameLp);
        TextView check = text("выбрано", isCompact() ? 11 : 12, selected ? COLOR_ACCENT_BLUE : COLOR_MUTED);
        check.setGravity(Gravity.CENTER);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setMinWidth(dp(58));
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        header.addView(check);

        TextView sub = text(hint, isCompact() ? 12 : 14, COLOR_MUTED);
        sub.setMaxLines(2);
        box.addView(header);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(4), 0, 0);
        box.addView(sub, subLp);
        if (index >= 0 && index < views.length) {
            views[index] = box;
            checks[index] = check;
        }
        return box;
    }

    private void updateChoiceButtons(View[] views, TextView[] checks, int selectedIndex) {
        for (int i = 0; i < views.length; i++) {
            View view = views[i];
            TextView check = checks[i];
            if (view == null || check == null) continue;
            boolean selected = i == selectedIndex;
            view.setBackground(settingsActionBackground(selected, COLOR_ACCENT_BLUE));
            check.setTextColor(selected ? COLOR_ACCENT_BLUE : COLOR_MUTED);
            check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private static void clearChoiceViews(View[] views, TextView[] checks) {
        for (int i = 0; i < views.length; i++) {
            views[i] = null;
            checks[i] = null;
        }
    }

    private CompoundButton addSettingSwitch(LinearLayout root, String title, String hint, boolean checked,
                                            CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(16), dp(13), dp(16), dp(13));
        panel.setMinimumHeight(isCompact() ? dp(70) : dp(82));
        panel.setBackground(settingsActionBackground(checked, COLOR_ACCENT_BLUE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(9), 0, dp(5));
        panel.setLayoutParams(lp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, isCompact() ? 15 : 17, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = text(hint, isCompact() ? 12 : 13, COLOR_MUTED);
        texts.addView(name);
        texts.addView(sub);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        panel.addView(texts, textsLp);

        CompoundButton toggle = checkBox("", checked);
        toggle.setOnCheckedChangeListener(listener);
        panel.addView(toggle, switchLayout(false));
        root.addView(panel);
        return toggle;
    }

    private LinearLayout.LayoutParams switchLayout(boolean separated) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                isCompact() ? dp(58) : dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (separated) lp.setMargins(dp(18), 0, 0, 0);
        return lp;
    }

    private CompoundButton checkBox(String title, boolean checked) {
        Switch box = new Switch(this);
        box.setText(title);
        box.setChecked(checked);
        box.setTextColor(Color.WHITE);
        box.setTextSize(15f);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setMinWidth(isCompact() ? dp(58) : dp(64));
        box.setMinHeight(dp(48));
        box.setPadding(0, dp(4), 0, dp(4));
        if (Build.VERSION.SDK_INT >= 21) {
            box.setShowText(false);
            box.setTextOn("");
            box.setTextOff("");
            box.setSplitTrack(false);
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked}
            };
            box.setThumbTintList(new ColorStateList(states, new int[]{
                    COLOR_ACCENT_BLUE,
                    Color.rgb(86, 100, 116)
            }));
            box.setTrackTintList(new ColorStateList(states, new int[]{
                    softColor(COLOR_ACCENT_BLUE, 92),
                    Color.rgb(35, 43, 55)
            }));
        }
        return box;
    }

    private int actionColumns(int count) {
        int widthDp = screenWidthDp();
        if (widthDp < 560) return 1;
        if (widthDp >= 820 && count >= 3) return 3;
        return 2;
    }

    private int tpmsColumns() {
        int widthDp = screenWidthDp();
        if (widthDp >= 1100) return 4;
        if (widthDp >= 560) return 2;
        return 1;
    }

    private LinearLayout.LayoutParams actionLayout(int column, int columns) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int gap = columns == 1 ? 0 : dp(5);
        lp.setMargins(column == 0 ? 0 : gap, dp(4), column == columns - 1 ? 0 : gap, dp(4));
        return lp;
    }

    private LinearLayout.LayoutParams singleActionLayout() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(4));
        return lp;
    }

    private LinearLayout infoPanel(String title, TextView content) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setBackground(settingsPanelBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, dp(4));
        panel.setLayoutParams(lp);

        LinearLayout header = row();
        TextView label = text(title, isCompact() ? 16 : 18, Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(label, labelLp);
        panel.addView(header);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentLp.setMargins(0, dp(8), 0, 0);
        panel.addView(content, contentLp);
        return panel;
    }

    private LinearLayout infoPanel(String title, CompoundButton content) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(settingsPanelBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, dp(2));
        panel.setLayoutParams(lp);

        TextView label = text(title, 14, Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(label);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentLp.setMargins(0, dp(4), 0, 0);
        panel.addView(content, contentLp);
        return panel;
    }

    private LinearLayout sasRatioPanel() {
        LinearLayout panel = settingsPanel(COLOR_ACCENT_BLUE);
        addSettingsPanelHeader(panel, "SAS - парковочные линии",
                "датчик угла руля; ratio меняет чувствительность смещения линий заднего хода",
                COLOR_ACCENT_BLUE);

        SasGuidePreviewView preview = new SasGuidePreviewView(this);
        sasRatioPreview = preview;
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewLp.setMargins(0, dp(12), 0, dp(12));
        preview.setLayoutParams(previewLp);
        panel.addView(preview);

        LinearLayout controls = isCompact() ? new LinearLayout(this) : row();
        controls.setOrientation(isCompact() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        sasRatioStatus = text(sasRatioText(), isCompact() ? 13 : 15, COLOR_MUTED);
        LinearLayout.LayoutParams statusLp = isCompact()
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        controls.addView(sasRatioStatus, statusLp);

        LinearLayout.LayoutParams inputLp = isCompact()
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(dp(470), ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(isCompact() ? 0 : dp(14), isCompact() ? dp(10) : 0, 0, 0);
        controls.addView(numericRow(sasRatioInput, AppSettings.sasRatio(this), 1, 255,
                this::setSasRatio), inputLp);
        panel.addView(controls);
        return panel;
    }

    private EditText sasRatioInput() {
        return numericInput("sas", AppSettings.sasRatio(this), 1, 255, this::setSasRatio);
    }

    private LinearLayout ampControl(String field, String label, int value, int min, int max) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        line.setPadding(0, dp(8), 0, 0);
        TextView name = text(label, isCompact() ? 13 : 14, COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        line.addView(name);

        EditText input = numericInput("amp:" + field, value, min, max,
                next -> setAmpValue(field, next));
        ampInputs.add(input);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dp(4), 0, 0);
        line.addView(numericRow(input, value, min, max, next -> setAmpValue(field, next)),
                rowLp);
        return line;
    }

    private int ampColumns() {
        int widthDp = screenWidthDp();
        if (widthDp >= 1050) return 4;
        if (widthDp >= 720) return 2;
        return 1;
    }

    private LinearLayout.LayoutParams ampGridLayout(int column, int columns) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int gap = columns == 1 ? 0 : dp(6);
        lp.setMargins(column == 0 ? 0 : gap, 0, column == columns - 1 ? 0 : gap, 0);
        return lp;
    }

    private LinearLayout numericRow(EditText input, int fallback, int min, int max, IntSetter setter) {
        LinearLayout row = row();
        row.addView(stepButton("-", () -> setter.set(clamp(readNumeric(input, fallback) - 1, min, max))));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, isCompact() ? dp(42) : dp(46), 1f);
        inputLp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(input, inputLp);
        row.addView(stepButton("+", () -> setter.set(clamp(readNumeric(input, fallback) + 1, min, max))));
        return row;
    }

    private LinearLayout pressureRow(EditText input, int fallbackKpa, int minKpa, int maxKpa,
                                     IntSetter setter) {
        LinearLayout row = row();
        row.addView(stepButton("-", () -> setter.set(
                clamp(readPressureKpa(input, fallbackKpa) - 10, minKpa, maxKpa))));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0,
                isCompact() ? dp(42) : dp(46), 1f);
        inputLp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(input, inputLp);
        row.addView(stepButton("+", () -> setter.set(
                clamp(readPressureKpa(input, fallbackKpa) + 10, minKpa, maxKpa))));
        return row;
    }

    private TextView stepButton(String value, Runnable action) {
        TextView button = text(value, 22, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(settingsActionBackground(false, COLOR_ACCENT_BLUE));
        button.setOnClickListener(v -> {
            rememberScrollPosition();
            action.run();
        });
        button.setMinWidth(isCompact() ? dp(42) : dp(46));
        button.setMinHeight(isCompact() ? dp(42) : dp(46));
        return button;
    }

    private EditText numericInput(String tag, int value, int min, int max, IntSetter setter) {
        EditText input = new EditText(this);
        input.setTag(tag);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(String.valueOf(clamp(value, min, max)));
        input.setTextColor(Color.WHITE);
        input.setTextSize(17f);
        input.setGravity(Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        input.setPadding(dp(12), dp(7), dp(12), dp(7));
        input.setBackground(settingsActionBackground(true, COLOR_ACCENT_BLUE));
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                setter.set(clamp(readNumeric(input, value), min, max));
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) setter.set(clamp(readNumeric(input, value), min, max));
        });
        return input;
    }

    private EditText pressureInput(String tag, int valueKpa, int minKpa, int maxKpa, IntSetter setter) {
        EditText input = new EditText(this);
        input.setTag(tag);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(pressureBarText(clamp(valueKpa, minKpa, maxKpa)));
        input.setTextColor(Color.WHITE);
        input.setTextSize(17f);
        input.setGravity(Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        input.setPadding(dp(12), dp(7), dp(12), dp(7));
        input.setBackground(settingsActionBackground(true, COLOR_ACCENT_BLUE));
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                setter.set(clamp(readPressureKpa(input, valueKpa), minKpa, maxKpa));
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) setter.set(clamp(readPressureKpa(input, valueKpa), minKpa, maxKpa));
        });
        return input;
    }

    private int readNumeric(EditText input, int fallback) {
        if (input == null || input.getText() == null) return fallback;
        String raw = input.getText().toString().trim();
        if (raw.isEmpty() || "-".equals(raw) || "+".equals(raw)) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int readPressureKpa(EditText input, int fallbackKpa) {
        if (input == null || input.getText() == null) return fallbackKpa;
        String raw = input.getText().toString().trim().replace(',', '.');
        if (raw.isEmpty() || ".".equals(raw)) return fallbackKpa;
        try {
            float bar = Float.parseFloat(raw);
            return Math.round(bar * 10f) * 10;
        } catch (NumberFormatException e) {
            return fallbackKpa;
        }
    }

    private void updateOverlayToggle() {
        if (navigationOverlayToggle == null) return;
        boolean enabled = AppSettings.navOverlayEnabled(this);
        if (navigationOverlayToggle.isChecked() == enabled) return;
        navigationOverlayToggle.setOnCheckedChangeListener(null);
        navigationOverlayToggle.setChecked(enabled);
        navigationOverlayToggle.setOnCheckedChangeListener(this::toggleNavOverlay);
    }

    private void updateMicroManeuverToggle() {
        if (microManeuverToggle == null) return;
        boolean enabled = AppSettings.navMicroManeuvers(this);
        if (microManeuverToggle.isChecked() == enabled) return;
        microManeuverToggle.setOnCheckedChangeListener(null);
        microManeuverToggle.setChecked(enabled);
        microManeuverToggle.setOnCheckedChangeListener(this::toggleMicroManeuvers);
    }

    private void updateNavTbtToggle() {
        if (navTbtToggle == null) return;
        boolean enabled = AppSettings.navTbt(this);
        if (navTbtToggle.isChecked() == enabled) return;
        navTbtToggle.setOnCheckedChangeListener(null);
        navTbtToggle.setChecked(enabled);
        navTbtToggle.setOnCheckedChangeListener(this::toggleNavTbt);
    }

    private void updateOverspeedToggle() {
        if (overspeedToggle == null) return;
        boolean enabled = AppSettings.navOverspeedTextEnabled(this);
        if (overspeedToggle.isChecked() == enabled) return;
        overspeedToggle.setOnCheckedChangeListener(null);
        overspeedToggle.setChecked(enabled);
        overspeedToggle.setOnCheckedChangeListener(this::toggleOverspeedText);
    }

    private void updateNavDebugToggle() {
        if (navigationDebugToggle == null) return;
        boolean enabled = AppSettings.navOverlayEnabled(this);
        if (navigationDebugToggle.isChecked() == enabled) return;
        navigationDebugToggle.setOnCheckedChangeListener(null);
        navigationDebugToggle.setChecked(enabled);
        navigationDebugToggle.setOnCheckedChangeListener(this::toggleNavDebug);
    }

    private void updateCanbusDebugToggle() {
        if (canbusDebugToggle == null) return;
        boolean enabled = AppSettings.canbusDebugVisible(this);
        if (canbusDebugToggle.isChecked() == enabled) return;
        canbusDebugToggle.setOnCheckedChangeListener(null);
        canbusDebugToggle.setChecked(enabled);
        canbusDebugToggle.setOnCheckedChangeListener(this::toggleCanbusDebug);
    }

    private void updateAmpEnabledToggle() {
        if (ampEnabledToggle == null) return;
        boolean enabled = AppSettings.ampEnabled(this);
        if (ampEnabledToggle.isChecked() == enabled) return;
        ampEnabledToggle.setOnCheckedChangeListener(null);
        ampEnabledToggle.setChecked(enabled);
        ampEnabledToggle.setOnCheckedChangeListener(this::toggleAmpEnabled);
    }

    private void updateCallEnabledToggle() {
        if (callEnabledToggle == null) return;
        boolean enabled = AppSettings.callEnabled(this);
        if (callEnabledToggle.isChecked() == enabled) return;
        callEnabledToggle.setOnCheckedChangeListener(null);
        callEnabledToggle.setChecked(enabled);
        callEnabledToggle.setOnCheckedChangeListener(this::toggleCallEnabled);
    }

    private void updateMediaDebugToggle() {
        if (mediaDebugToggle == null) return;
        boolean enabled = AppSettings.mediaOverlayEnabled(this);
        if (mediaDebugToggle.isChecked() == enabled) return;
        mediaDebugToggle.setOnCheckedChangeListener(null);
        mediaDebugToggle.setChecked(enabled);
        mediaDebugToggle.setOnCheckedChangeListener(this::toggleMediaDebug);
    }

    private void updateTpmsAlertsToggle() {
        if (tpmsAlertsToggle == null) return;
        boolean enabled = AppSettings.tpmsAlertsEnabled(this);
        if (tpmsAlertsToggle.isChecked() == enabled) return;
        tpmsAlertsToggle.setOnCheckedChangeListener(null);
        tpmsAlertsToggle.setChecked(enabled);
        tpmsAlertsToggle.setOnCheckedChangeListener(this::toggleTpmsAlerts);
    }

    private void updateTpmsSoundToggle() {
        if (tpmsSoundToggle == null) return;
        boolean enabled = AppSettings.tpmsSoundAlertsEnabled(this);
        if (tpmsSoundToggle.isChecked() == enabled) return;
        tpmsSoundToggle.setOnCheckedChangeListener(null);
        tpmsSoundToggle.setChecked(enabled);
        tpmsSoundToggle.setOnCheckedChangeListener(this::toggleTpmsSoundAlerts);
    }

    private void updateAutoStartToggle() {
        if (autoStartToggle == null) return;
        boolean enabled = AppSettings.autoStart(this);
        if (autoStartToggle.isChecked() == enabled) return;
        autoStartToggle.setOnCheckedChangeListener(null);
        autoStartToggle.setChecked(enabled);
        autoStartToggle.setOnCheckedChangeListener(this::toggleAutoStart);
    }

    private void updateMediaTabToggle() {
        if (mediaTabToggle == null) return;
        boolean enabled = AppSettings.mediaTabVisible(this);
        if (mediaTabToggle.isChecked() == enabled) return;
        mediaTabToggle.setOnCheckedChangeListener(null);
        mediaTabToggle.setChecked(enabled);
        mediaTabToggle.setOnCheckedChangeListener(this::toggleMediaTab);
    }

    private void updateLogTabToggle() {
        if (logTabToggle == null) return;
        boolean enabled = AppSettings.logTabVisible(this);
        if (logTabToggle.isChecked() == enabled) return;
        logTabToggle.setOnCheckedChangeListener(null);
        logTabToggle.setChecked(enabled);
        logTabToggle.setOnCheckedChangeListener(this::toggleLogTab);
    }

    private void updateRawCanToggle() {
        if (rawCanToggle == null) return;
        boolean enabled = AppSettings.debugCan(this);
        if (rawCanToggle.isChecked() == enabled) return;
        rawCanToggle.setOnCheckedChangeListener(null);
        rawCanToggle.setChecked(enabled);
        rawCanToggle.setOnCheckedChangeListener(this::toggleRaw);
    }

    private void updateAmpInputs() {
        AmpState state = StateStore.amp();
        if (ampSummary != null) {
            ampSummary.setText(ampUiText(state));
        }
        if (ampVisualizer != null) {
            ampVisualizer.setState(state, AppSettings.ampEnabled(this));
        }
        if (ampInputs.isEmpty()) return;
        for (EditText input : ampInputs) {
            if (input == null || input.hasFocus() || input.getTag() == null) continue;
            String tag = String.valueOf(input.getTag());
            if (!tag.startsWith("amp:")) continue;
            String field = tag.substring(4);
            String value = String.valueOf(displayAmpValue(field));
            if (!value.contentEquals(input.getText())) input.setText(value);
        }
    }

    private void updateTpmsInputs() {
        if (tpmsInputs.isEmpty()) return;
        for (EditText input : tpmsInputs) {
            if (input == null || input.hasFocus() || input.getTag() == null) continue;
            String tag = String.valueOf(input.getTag());
            String value;
            if (tag.startsWith("tpmsBar:")) {
                value = pressureBarText(displayTpmsValue(tag.substring(8)));
            } else if (tag.startsWith("tpms:")) {
                value = String.valueOf(displayTpmsValue(tag.substring(5)));
            } else {
                continue;
            }
            if (!value.contentEquals(input.getText())) input.setText(value);
        }
    }

    private int displayAmpValue(String field) {
        AmpState s = StateStore.amp();
        if ("volume".equals(field)) return s.volume;
        if ("balance".equals(field)) return s.balance - 10;
        if ("fader".equals(field)) return s.fader - 10;
        if ("bass".equals(field)) return s.bass - 10;
        if ("mid".equals(field)) return s.mid - 10;
        if ("treble".equals(field)) return s.treble - 10;
        if ("mode".equals(field)) return s.mode;
        return 0;
    }

    private int displayTpmsValue(String field) {
        if ("lowP".equals(field)) return AppSettings.tpmsLowPressureKpa(this);
        if ("highP".equals(field)) return AppSettings.tpmsHighPressureKpa(this);
        if ("lowT".equals(field)) return AppSettings.tpmsLowTempC(this);
        if ("highT".equals(field)) return AppSettings.tpmsHighTempC(this);
        return 0;
    }

    private void updateFirmwareActionButton() {
        if (firmwareActionButton == null || firmwareActionText == null || firmwareActionHintText == null) return;
        UpdateState s = StateStore.updates();
        boolean busy = firmwareUpdater != null && firmwareUpdater.busy();
        int progress = firmwareProgressPercent(s);
        if (busy) {
            firmwareActionText.setText(s.firmwareFlashing ? "Прошивка " + progress + "%" : "Подготовка BIN");
            firmwareActionHintText.setText(displayStatus(s.firmwareStatus));
        } else if (pendingFirmwareUri != null) {
            firmwareActionText.setText("Прошить BIN");
            firmwareActionHintText.setText(pendingFirmwareLabel);
        } else {
            firmwareActionText.setText("Выбрать BIN");
            firmwareActionHintText.setText("ручной файл прошивки до 112 KB");
        }
        firmwareActionButton.setEnabled(!busy);
        firmwareActionButton.setBackground(settingsButtonBackground(busy || pendingFirmwareUri != null));
        if (firmwareProgressFill == null) return;
        int parentWidth = firmwareActionButton.getWidth();
        int fillWidth = busy ? Math.round(parentWidth * progress / 100f) : 0;
        ViewGroup.LayoutParams lp = firmwareProgressFill.getLayoutParams();
        if (lp != null && lp.width != fillWidth) {
            lp.width = fillWidth;
            firmwareProgressFill.setLayoutParams(lp);
        }
    }

    private int firmwareProgressPercent(UpdateState s) {
        if (s == null || s.firmwareTotalBytes <= 0) return 0;
        long done = Math.max(0, Math.min(s.firmwareDownloadedBytes, s.firmwareTotalBytes));
        return Math.max(0, Math.min(100, Math.round(done * 100f / s.firmwareTotalBytes)));
    }

    private int outerPadding() {
        int widthDp = screenWidthDp();
        if (widthDp >= 1200) return dp(24);
        if (widthDp < 560) return dp(10);
        return dp(16);
    }

    private void applyRootPadding() {
        if (rootLayout == null) return;
        if (!settingsMode && selectedTab == TAB_TPMS) {
            rootLayout.setPadding(0, 0, 0, 0);
            return;
        }
        if (settingsMode) {
            rootLayout.setPadding(0, rootInsetTop, 0, rootInsetBottom);
            return;
        }
        rootLayout.setPadding(outerPadding(), (isCompact() ? dp(10) : dp(18)) + rootInsetTop,
                outerPadding(), dp(22) + rootInsetBottom);
    }

    private boolean isCompact() {
        return screenWidthDp() < 760;
    }

    private boolean tpmsWidgetMode() {
        return isCompact() || isLandscapeWindow();
    }

    private boolean tpmsWidgetLocksSettings() {
        return screenWidthDp() < 560 && !isLandscapeWindow();
    }

    private void normalizeModeForWindow() {
        if (!tpmsWidgetLocksSettings() || !settingsMode) return;
        settingsMode = false;
        selectedTab = TAB_TPMS;
    }

    private boolean isLandscapeWindow() {
        int widthDp = screenWidthDp();
        int heightDp = screenHeightDp();
        return widthDp > 0 && heightDp > 0 && widthDp > heightDp;
    }

    private int tabButtonWidth() {
        int widthDp = screenWidthDp();
        if (widthDp >= 1200) return dp(148);
        if (widthDp >= 900) return dp(132);
        return dp(116);
    }

    private int screenWidthDp() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp > 0) return widthDp;
        float density = getResources().getDisplayMetrics().density;
        return Math.round(getResources().getDisplayMetrics().widthPixels / density);
    }

    private int screenHeightDp() {
        int heightDp = getResources().getConfiguration().screenHeightDp;
        if (heightDp > 0) return heightDp;
        float density = getResources().getDisplayMetrics().density;
        return Math.round(getResources().getDisplayMetrics().heightPixels / density);
    }

    private TextView pill(String value, int color) {
        TextView view = text(value, isCompact() ? 12 : 13, Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
        view.setBackground(glassButton(color));
        return view;
    }

    private LinearLayout.LayoutParams chipLayout(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(first ? 0 : dp(6), 0, first ? dp(6) : 0, 0);
        return lp;
    }

    private GradientDrawable gradient(int startColor, int endColor, int strokeColor,
                                      int strokeWidth, int radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{startColor, endColor});
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private GradientDrawable glassPanel(int tint) {
        if (settingsMode) return settingsPanelBackground();
        return gradient(softColor(tint, 46), softColor(COLOR_PANEL_SOFT, 166),
                softColor(Color.WHITE, 42), dp(1), dp(8));
    }

    private GradientDrawable glassButton(int tint) {
        if (settingsMode) return settingsButtonBackground(tint == COLOR_ACCENT);
        return gradient(softColor(tint, 64), softColor(COLOR_PANEL_SOFT, 132),
                softColor(Color.WHITE, 36), dp(1), dp(8));
    }

    private GradientDrawable settingsPanelBackground() {
        return settingsPanelBackground(COLOR_ACCENT_BLUE);
    }

    private GradientDrawable settingsPanelBackground(int tint) {
        return gradient(COLOR_SETTINGS_PANEL, Color.rgb(20, 28, 38),
                Color.TRANSPARENT, 0, dp(12));
    }

    private GradientDrawable settingsInsetBackground() {
        return settingsInsetBackground(COLOR_ACCENT_BLUE);
    }

    private GradientDrawable settingsInsetBackground(int tint) {
        return gradient(COLOR_SETTINGS_PANEL_ALT, Color.rgb(23, 29, 38),
                Color.TRANSPARENT, 0, dp(10));
    }

    private GradientDrawable settingsButtonBackground(boolean selected) {
        return settingsActionBackground(selected, COLOR_ACCENT_BLUE);
    }

    private GradientDrawable settingsActionBackground(boolean selected, int tint) {
        int accent = settingsAccent(tint);
        int start = selected ? softColor(accent, 66) : COLOR_SETTINGS_PANEL_ALT;
        int end = selected ? Color.rgb(22, 31, 48) : Color.rgb(21, 27, 36);
        return gradient(start, end, Color.TRANSPARENT, 0, dp(10));
    }

    private int settingsAccent(int tint) {
        return COLOR_ACCENT_BLUE;
    }

    private int softColor(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private GradientDrawable round(int fill, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView iconBadge(String value, int color) {
        TextView badge = text(value, isCompact() ? 17 : 19, color);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setMinWidth(isCompact() ? dp(24) : dp(28));
        badge.setMinHeight(isCompact() ? dp(24) : dp(28));
        badge.setPadding(dp(2), dp(2), dp(2), dp(2));
        return badge;
    }

    private abstract class SettingsPreviewView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final Path path = new Path();

        SettingsPreviewView(Context context) {
            super(context);
        }

        void card(Canvas canvas, float left, float top, float right, float bottom) {
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_SETTINGS_PANEL_ALT);
            r.set(left, top, right, bottom);
            canvas.drawRoundRect(r, dp(8), dp(8), p);
        }

        void fill(Canvas canvas, int color, float left, float top, float right, float bottom, float radius) {
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            r.set(left, top, right, bottom);
            canvas.drawRoundRect(r, radius, radius, p);
        }

        void stroke(Canvas canvas, int color, float width, float left, float top, float right, float bottom, float radius) {
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(width);
            p.setColor(color);
            r.set(left, top, right, bottom);
            canvas.drawRoundRect(r, radius, radius, p);
            p.setStyle(Paint.Style.FILL);
        }

        void label(Canvas canvas, String value, float x, float y, float sp, int color, boolean bold) {
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            p.setTextSize(sp);
            p.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(value, x, y, p);
        }

        void center(Canvas canvas, String value, float x, float y, float sp, int color, boolean bold) {
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            p.setTextSize(sp);
            p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = p.getFontMetrics();
            canvas.drawText(value, x, y - (fm.ascent + fm.descent) / 2f, p);
        }

        void chip(Canvas canvas, String value, float x, float y, int fill, int color) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(isCompact() ? dp(10) : dp(11));
            p.setTextAlign(Paint.Align.LEFT);
            float w = Math.max(dp(58), p.measureText(value) + dp(18));
            fill(canvas, fill, x, y, x + w, y + dp(28), dp(8));
            center(canvas, value, x + w / 2f, y + dp(14), isCompact() ? dp(10) : dp(11), color, true);
        }
    }

    private final class TpmsSettingsPreviewView extends SettingsPreviewView {
        TpmsSettingsPreviewView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            card(canvas, 0, 0, w, h);
            float pad = dp(18);
            label(canvas, "Карта срабатывания", pad, dp(25), dp(15), Color.WHITE, true);
            legend(canvas, w - pad - dp(270), dp(10));
            float firstY = dp(48);
            float gap = isCompact() ? dp(46) : dp(54);
            drawScale(canvas, "Давление", "норма "
                            + pressureBarText(AppSettings.tpmsLowPressureKpa(MainActivity.this)) + "-"
                            + pressureBarText(AppSettings.tpmsHighPressureKpa(MainActivity.this)) + " bar",
                    pressureBarText(AppSettings.tpmsLowPressureKpa(MainActivity.this)),
                    pressureBarText(AppSettings.tpmsHighPressureKpa(MainActivity.this)),
                    pad, firstY, w - pad);
            drawScale(canvas, "Температура", "норма "
                            + AppSettings.tpmsLowTempC(MainActivity.this) + "-"
                            + AppSettings.tpmsHighTempC(MainActivity.this) + "C",
                    String.valueOf(AppSettings.tpmsLowTempC(MainActivity.this)),
                    String.valueOf(AppSettings.tpmsHighTempC(MainActivity.this)),
                    pad, firstY + gap, w - pad);
            drawOverlay(canvas, pad, h - dp(50), w - pad, h - dp(12));
        }

        private void legend(Canvas canvas, float x, float y) {
            if (x < dp(220)) return;
            legendItem(canvas, x, y, 0xff1d3b38, "норма");
            legendItem(canvas, x + dp(86), y, 0xff453b24, "жёлтая");
            legendItem(canvas, x + dp(184), y, 0xff462d34, "красная");
        }

        private void legendItem(Canvas canvas, float x, float y, int color, String text) {
            fill(canvas, color, x, y + dp(5), x + dp(18), y + dp(13), dp(4));
            label(canvas, text, x + dp(24), y + dp(14), dp(11), COLOR_MUTED, true);
        }

        private void drawScale(Canvas canvas, String title, String range, String low, String high,
                               float left, float y, float right) {
            float labelW = isCompact() ? dp(124) : dp(172);
            label(canvas, title, left, y + dp(17), dp(13), Color.WHITE, true);
            label(canvas, range, left, y + dp(36), dp(11), COLOR_MUTED, false);
            float barLeft = left + labelW;
            float barRight = right;
            float barTop = y + dp(5);
            float barBottom = y + dp(23);
            float redW = (barRight - barLeft) * 0.18f;
            float yellowW = (barRight - barLeft) * 0.15f;
            p.setShader(new LinearGradient(barLeft, 0, barRight, 0,
                    new int[]{0xff30232a, 0xff39331f, 0xff193532, 0xff39331f, 0xff30232a},
                    new float[]{0f, 0.22f, 0.50f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            r.set(barLeft, barTop, barRight, barBottom);
            canvas.drawRoundRect(r, dp(9), dp(9), p);
            p.setShader(null);

            float lowX = barLeft + (barRight - barLeft) * 0.33f;
            float highX = barLeft + (barRight - barLeft) * 0.67f;
            p.setStrokeWidth(dp(2));
            p.setColor(0xb8d9e4ef);
            canvas.drawLine(lowX, barTop - dp(7), lowX, barBottom + dp(10), p);
            canvas.drawLine(highX, barTop - dp(7), highX, barBottom + dp(10), p);
            valuePill(canvas, low, lowX, barBottom + dp(20));
            valuePill(canvas, high, highX, barBottom + dp(20));
        }

        private void drawOverlay(Canvas canvas, float left, float top, float right, float bottom) {
            fill(canvas, 0xff442832, left, top, right, bottom, dp(8));
            fill(canvas, 0xff7d3b48, left, top, left + dp(10), bottom, dp(8));
            label(canvas, "красная плашка поверх экрана", left + dp(14), top + dp(24),
                    dp(12), Color.rgb(238, 232, 235), true);
            center(canvas, "×", right - dp(22), top + (bottom - top) / 2f, dp(22),
                    Color.rgb(238, 232, 235), true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0x9fe8d7dc);
            float sx = right - dp(92);
            canvas.drawArc(sx, top + dp(10), sx + dp(18), top + dp(28), -36, 72, false, p);
            canvas.drawArc(sx - dp(7), top + dp(6), sx + dp(30), top + dp(32), -36, 72, false, p);
            p.setStyle(Paint.Style.FILL);
            center(canvas, "звук идёт, пока не нажать ×", right - dp(185),
                    top + (bottom - top) / 2f, dp(12), Color.rgb(238, 232, 235), true);
        }

        private void valuePill(Canvas canvas, String value, float cx, float cy) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(11));
            p.setTextAlign(Paint.Align.LEFT);
            float width = Math.max(dp(42), p.measureText(value) + dp(16));
            fill(canvas, COLOR_SETTINGS_SELECTED, cx - width / 2f, cy - dp(12),
                    cx + width / 2f, cy + dp(12), dp(7));
            center(canvas, value, cx, cy, dp(11), Color.WHITE, true);
        }
    }

    private final class NavigationSettingsPreviewView extends SettingsPreviewView {
        NavigationSettingsPreviewView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            int routeMode = NavigationModeSettings.mode(MainActivity.this);
            int textMode = AppSettings.navTextMode(MainActivity.this);
            int sourceMode = AppSettings.navSourceMode(MainActivity.this);
            boolean normalMode = routeMode == NavigationOutputMode.NORMAL;
            boolean assistant = AppSettings.navMicroManeuvers(MainActivity.this);
            boolean overspeed = AppSettings.navOverspeedTextEnabled(MainActivity.this);
            float phase = (System.currentTimeMillis() % 1800L) / 1800f;

            float pad = dp(18);
            p.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xff111823, 0xff0b1017, 0xff151b24},
                    new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, dp(8), dp(8), p);
            p.setShader(null);
            stroke(canvas, 0x28ffffff, dp(1), 0, 0, w, h, dp(8));

            float left = pad;
            float top = pad;
            float bottom = h - pad;
            float routeW = w * (isCompact() ? 0.55f : 0.58f);
            float rightX = left + routeW + dp(16);
            float rightW = w - rightX - pad;

            drawRouteSurface(canvas, left, top, left + routeW, bottom, routeMode,
                    normalMode && assistant, phase);
            drawSourceCard(canvas, rightX, top, rightX + rightW, top + dp(42), sourceMode);
            drawTxCard(canvas, rightX, top + dp(50), rightX + rightW, top + dp(100),
                    routeMode, textMode);
            drawAssistantCard(canvas, rightX, top + dp(108), rightX + rightW, top + dp(150),
                    normalMode, assistant);
            drawSpeedCard(canvas, rightX, top + dp(158), rightX + rightW, bottom, overspeed);

            postInvalidateDelayed(700L);
        }

        private void drawRouteSurface(Canvas canvas, float left, float top, float right, float bottom,
                                      int routeMode, boolean assistant, float phase) {
            fill(canvas, 0xff0d131b, left, top, right, bottom, dp(10));
            stroke(canvas, 0x22ffffff, dp(1), left, top, right, bottom, dp(10));
            label(canvas, routeModeTitle(routeMode), left + dp(14), top + dp(24), dp(14), Color.WHITE, true);
            label(canvas, routeModeHint(routeMode), left + dp(14), top + dp(43), dp(11), COLOR_MUTED, false);
            if (routeMode == NavigationOutputMode.TBT) {
                drawTbtPreview(canvas, left, top + dp(56), right, bottom, phase);
            } else if (routeMode == NavigationOutputMode.FINISH_DIRECTION) {
                drawFinishPreview(canvas, left, top + dp(54), right, bottom, phase);
            } else {
                drawNormalPreview(canvas, left, top + dp(54), right, bottom, assistant, phase);
            }
        }

        private void drawNormalPreview(Canvas canvas, float left, float top, float right, float bottom,
                                       boolean assistant, float phase) {
            float width = right - left;
            float roadCx = left + width * 0.50f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0x36ffffff);
            canvas.drawLine(roadCx - width * 0.26f, bottom, roadCx - width * 0.07f, top, p);
            canvas.drawLine(roadCx + width * 0.26f, bottom, roadCx + width * 0.07f, top, p);
            p.setStrokeWidth(dp(3));
            p.setColor(0xff3b4655);
            canvas.drawLine(roadCx, bottom - dp(18), roadCx, top + dp(8), p);
            p.setStyle(Paint.Style.FILL);

            fill(canvas, 0xff4a3712, left + dp(14), top + dp(6), left + width * 0.54f, top + dp(56), dp(9));
            center(canvas, "↱", left + dp(39), top + dp(32), dp(28), 0xffffc43b, true);
            label(canvas, "240 м", left + dp(70), top + dp(29), dp(20), Color.WHITE, true);
            label(canvas, "направо, затем прямо", left + dp(70), top + dp(48), dp(11), 0xffffd77a, true);

            float laneTop = bottom - dp(74);
            fill(canvas, 0xff171f2a, left + dp(14), laneTop, right - dp(14), bottom - dp(16), dp(9));
            label(canvas, "серая дорога", left + dp(28), laneTop + dp(22), dp(11), COLOR_MUTED, true);
            drawLane(canvas, left + width * 0.43f, laneTop + dp(30), "←");
            drawLane(canvas, left + width * 0.53f, laneTop + dp(30), "↑");
            drawLane(canvas, left + width * 0.63f, laneTop + dp(30), "→");
            if (assistant) {
                float pulse = 0.70f + 0.30f * (float) Math.sin(phase * Math.PI * 2f);
                fill(canvas, Color.argb(Math.round(170 * pulse),
                                Color.red(COLOR_ACCENT_BLUE),
                                Color.green(COLOR_ACCENT_BLUE),
                                Color.blue(COLOR_ACCENT_BLUE)),
                        right - dp(126), top + dp(8), right - dp(14), top + dp(56), dp(9));
                label(canvas, "ассистент", right - dp(112), top + dp(28), dp(11), Color.WHITE, true);
                label(canvas, "прямо", right - dp(112), top + dp(47), dp(14), Color.WHITE, true);
            } else {
                fill(canvas, 0xff202733, right - dp(126), top + dp(8), right - dp(14), top + dp(56), dp(9));
                label(canvas, "ассистент", right - dp(112), top + dp(29), dp(11), COLOR_MUTED, true);
                label(canvas, "выкл", right - dp(112), top + dp(47), dp(13), COLOR_MUTED, true);
            }
        }

        private void drawTbtPreview(Canvas canvas, float left, float top, float right, float bottom, float phase) {
            float gap = dp(8);
            float itemW = (right - left - dp(28) - gap * 3f) / 4f;
            float y = top + dp(10);
            drawTbtItem(canvas, left + dp(14), y, itemW, "↱", "сейчас", true);
            drawTbtItem(canvas, left + dp(14) + (itemW + gap), y, itemW, "↑", "2", false);
            drawTbtItem(canvas, left + dp(14) + (itemW + gap) * 2f, y, itemW, "↰", "3", false);
            drawTbtItem(canvas, left + dp(14) + (itemW + gap) * 3f, y, itemW, "⚑", "финиш", false);
            fill(canvas, 0xff151d27, left + dp(14), bottom - dp(58), right - dp(14), bottom - dp(16), dp(9));
            label(canvas, "TBT отправляет отдельные иконки без подмены серой дороги",
                    left + dp(28), bottom - dp(32), dp(12), COLOR_MUTED, true);
        }

        private void drawTbtItem(Canvas canvas, float x, float y, float width, String icon,
                                 String text, boolean selected) {
            fill(canvas, selected ? 0xff20304a : 0xff151d27, x, y, x + width, y + dp(70), dp(10));
            stroke(canvas, selected ? COLOR_ACCENT_BLUE : 0x22ffffff, dp(1), x, y, x + width, y + dp(70), dp(10));
            center(canvas, icon, x + width / 2f, y + dp(31), dp(25),
                    selected ? COLOR_ACCENT_BLUE : COLOR_MUTED, true);
            center(canvas, text, x + width / 2f, y + dp(55), dp(11),
                    selected ? Color.WHITE : COLOR_MUTED, true);
        }

        private void drawFinishPreview(Canvas canvas, float left, float top, float right, float bottom,
                                       float phase) {
            float cx = left + (right - left) * 0.42f;
            float cy = top + (bottom - top) * 0.48f;
            float radius = Math.min(right - left, bottom - top) * 0.27f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0x30ffffff);
            canvas.drawCircle(cx, cy, radius, p);
            canvas.drawCircle(cx, cy, radius * 0.72f, p);
            p.setStyle(Paint.Style.FILL);
            center(canvas, "N", cx, cy - radius - dp(8), dp(11), COLOR_MUTED, true);
            center(canvas, "⚑", right - dp(52), top + dp(44), dp(28), 0xffffc43b, true);
            drawCompassArrow(canvas, cx, cy, radius * 0.84f, -38f + phase * 6f);
            label(canvas, "к флагу", left + dp(18), bottom - dp(44), dp(15), Color.WHITE, true);
            label(canvas, "1.2 км до точки финиша", left + dp(18), bottom - dp(23), dp(12), COLOR_MUTED, true);
        }

        private void drawCompassArrow(Canvas canvas, float cx, float cy, float length, float angleDeg) {
            canvas.save();
            canvas.rotate(angleDeg, cx, cy);
            path.reset();
            path.moveTo(cx, cy - length);
            path.lineTo(cx - dp(11), cy + dp(20));
            path.lineTo(cx, cy + dp(9));
            path.lineTo(cx + dp(11), cy + dp(20));
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_ACCENT_BLUE);
            canvas.drawPath(path, p);
            canvas.restore();
        }

        private void drawLane(Canvas canvas, float cx, float cy, String value) {
            fill(canvas, 0xff263241, cx - dp(18), cy - dp(17), cx + dp(18), cy + dp(17), dp(8));
            center(canvas, value, cx, cy + dp(1), dp(17), Color.WHITE, true);
        }

        private void drawSourceCard(Canvas canvas, float left, float top, float right, float bottom, int sourceMode) {
            fill(canvas, 0xff151d27, left, top, right, bottom, dp(9));
            label(canvas, "Источник", left + dp(12), top + dp(17), dp(11), COLOR_MUTED, true);
            label(canvas, sourceTitle(sourceMode), left + dp(12), top + dp(35), dp(15), Color.WHITE, true);
            previewPill(canvas, sourceHint(sourceMode), right - dp(86), top + dp(22), COLOR_SETTINGS_SELECTED);
        }

        private void drawTxCard(Canvas canvas, float left, float top, float right, float bottom,
                                int routeMode, int textMode) {
            fill(canvas, 0xff101720, left, top, right, bottom, dp(9));
            label(canvas, "TX приборки", left + dp(12), top + dp(17), dp(11), COLOR_MUTED, true);
            label(canvas, panelPreviewText(routeMode, textMode), left + dp(12), top + dp(39),
                    dp(17), Color.WHITE, true);
            float chipW = (right - left - dp(32)) / 3f;
            float chipY = bottom - dp(16);
            modeChip(canvas, left + dp(12), chipY, chipW, "сейчас", 0, textMode);
            modeChip(canvas, left + dp(18) + chipW, chipY, chipW, "после", 1, textMode);
            modeChip(canvas, left + dp(24) + chipW * 2f, chipY, chipW, "финиш", 2, textMode);
        }

        private void drawAssistantCard(Canvas canvas, float left, float top, float right, float bottom,
                                       boolean normalMode, boolean assistant) {
            int color = normalMode && assistant ? 0xff183a35 : 0xff202733;
            fill(canvas, color, left, top, right, bottom, dp(9));
            label(canvas, "Ассистент", left + dp(12), top + dp(17), dp(11), COLOR_MUTED, true);
            String value = !normalMode ? "недоступен в этом режиме"
                    : assistant ? "до " + AppSettings.navMicroMaxDistanceMeters(MainActivity.this)
                    + " м, после " + AppSettings.navMicroHoldSeconds(MainActivity.this) + "с"
                    : "выключен";
            label(canvas, value, left + dp(12), top + dp(36), dp(14),
                    normalMode && assistant ? Color.WHITE : COLOR_MUTED, true);
        }

        private void drawSpeedCard(Canvas canvas, float left, float top, float right, float bottom,
                                   boolean overspeed) {
            fill(canvas, overspeed ? 0xff351d25 : 0xff151d27, left, top, right, bottom, dp(9));
            drawSpeedLimit(canvas, left + dp(30), top + (bottom - top) / 2f);
            label(canvas, overspeed ? "Превышение видно" : "Лимит без текста",
                    left + dp(70), top + dp(22), dp(14),
                    overspeed ? 0xffffc43b : Color.WHITE, true);
            label(canvas, overspeed ? "+ warning строка" : "только знак 60",
                    left + dp(70), top + dp(41), dp(11), COLOR_MUTED, true);
        }

        private void drawSpeedLimit(Canvas canvas, float cx, float cy) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xfff7f7f7);
            canvas.drawCircle(cx, cy, dp(28), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(5));
            p.setColor(0xffd83b45);
            canvas.drawCircle(cx, cy, dp(24), p);
            p.setStyle(Paint.Style.FILL);
            center(canvas, "60", cx, cy, dp(17), Color.BLACK, true);
        }

        private void modeChip(Canvas canvas, float x, float y, float width, String title, int mode, int textMode) {
            boolean selected = textMode == mode;
            fill(canvas, selected ? COLOR_SETTINGS_SELECTED : 0xff161c24,
                    x, y - dp(11), x + width, y + dp(11), dp(7));
            stroke(canvas, selected ? COLOR_ACCENT_BLUE : 0x22ffffff, dp(1),
                    x, y - dp(11), x + width, y + dp(11), dp(7));
            center(canvas, title, x + width / 2f, y, dp(10),
                    selected ? Color.WHITE : COLOR_MUTED, selected);
        }

        private void previewPill(Canvas canvas, String value, float cx, float cy, int color) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(10));
            p.setTextAlign(Paint.Align.LEFT);
            float width = Math.max(dp(58), p.measureText(value) + dp(18));
            fill(canvas, color, cx - width / 2f, cy - dp(13), cx + width / 2f, cy + dp(13), dp(7));
            center(canvas, value, cx, cy, dp(10), Color.WHITE, true);
        }

        private String routeModeTitle(int routeMode) {
            if (routeMode == NavigationOutputMode.TBT) return "TBT-иконки";
            if (routeMode == NavigationOutputMode.FINISH_DIRECTION) return "Стрелка к флагу";
            return "Обычная навигация";
        }

        private String routeModeHint(int routeMode) {
            if (routeMode == NavigationOutputMode.TBT) return "отдельные события поворотов";
            if (routeMode == NavigationOutputMode.FINISH_DIRECTION) return "компас на точку финиша";
            return "манёвр, lane и серая дорога";
        }

        private String sourceTitle(int sourceMode) {
            if (sourceMode == AppSettings.NAV_SOURCE_YANDEX) return "Yandex";
            if (sourceMode == AppSettings.NAV_SOURCE_2GIS) return "2GIS";
            return "Auto";
        }

        private String sourceHint(int sourceMode) {
            if (sourceMode == AppSettings.NAV_SOURCE_YANDEX) return "только Y";
            if (sourceMode == AppSettings.NAV_SOURCE_2GIS) return "только 2G";
            return "Y + 2G";
        }

        private String panelPreviewText(int routeMode, int textMode) {
            if (routeMode == NavigationOutputMode.TBT) return "TBT: направо";
            if (routeMode == NavigationOutputMode.FINISH_DIRECTION) return "Флаг 1.2 км";
            switch (textMode) {
                case 1:
                    return "После: ул. Абая";
                case 2:
                    return "Финиш: Дом 12";
                case 0:
                default:
                    return "Сейчас: пр. Достык";
            }
        }
    }

    private final class SasGuidePreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        SasGuidePreviewView(Context context) {
            super(context);
            setMinimumHeight(isCompact() ? dp(160) : dp(200));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = isCompact() ? dp(170) : dp(210);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float radius = dp(8);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COLOR_SETTINGS_PANEL_ALT);
            canvas.drawRoundRect(0, 0, w, h, radius, radius, paint);

            int ratio = AppSettings.sasRatio(MainActivity.this);
            float strength = Math.max(0.04f, Math.min(1f, ratio / 64f));
            float curve = w * (0.04f + 0.18f * strength);
            float center = w * 0.5f;
            float top = h * 0.18f;
            float bottom = h * 0.88f;
            float topGap = w * 0.12f;
            float bottomGap = w * 0.32f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(52, 255, 255, 255));
            drawGuide(canvas, center - topGap, top, center - bottomGap, bottom, 0f, 0f);
            drawGuide(canvas, center + topGap, top, center + bottomGap, bottom, 0f, 0f);

            paint.setStrokeWidth(dp(4));
            paint.setColor(COLOR_ACCENT_BLUE);
            drawGuide(canvas, center - topGap, top, center - bottomGap + curve, bottom, curve, -1f);
            drawGuide(canvas, center + topGap, top, center + bottomGap + curve, bottom, curve, 1f);

            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(120, 255, 255, 255));
            for (int i = 1; i <= 3; i++) {
                float y = top + (bottom - top) * i / 4f;
                float shift = curve * i / 4f;
                canvas.drawLine(center - bottomGap * (0.48f + i * 0.18f) + shift, y,
                        center + bottomGap * (0.48f + i * 0.18f) + shift, y, paint);
            }
        }

        private void drawGuide(Canvas canvas, float startX, float startY, float endX, float endY,
                               float curve, float side) {
            path.reset();
            path.moveTo(startX, startY);
            path.cubicTo(startX + curve * (0.15f + side * 0.18f), startY + (endY - startY) * 0.30f,
                    endX - curve * (0.25f - side * 0.12f), startY + (endY - startY) * 0.72f,
                    endX, endY);
            canvas.drawPath(path, paint);
        }
    }

    private String iconForTab(int tab) {
        switch (tab) {
            case TAB_TPMS:
                return "◉";
            case TAB_MEDIA:
                return "♪";
            case TAB_NAVIGATION:
                return "⌖";
            case TAB_CANBUS:
                return "◇";
            case TAB_SETTINGS:
                return "⚙";
            case TAB_LOG:
                return "≡";
            default:
                return "•";
        }
    }

    private String sectionIcon(String title) {
        if (title.contains("TPMS") || title.contains("Порог") || title.contains("Оповещ")) return "◉";
        if (title.contains("Медиа") || title.contains("Музык")) return "♪";
        if (title.contains("звон") || title.contains("Звон") || title.contains("BT")) return "☎";
        if (title.contains("Разреш")) return "✓";
        if (title.contains("Источник")) return "◇";
        if (title.contains("Адрес")) return "⌖";
        if (title.contains("Текст")) return "T";
        if (title.contains("APK") || title.contains("Система")) return "↻";
        if (title.contains("Прошив")) return "⇧";
        if (title.contains("Диаг") || title.contains("gs_usb") || title.contains("Отлад")) return "≡";
        return "•";
    }

    private String iconForTitle(String title) {
        if (title.contains("TPMS") || title.contains("Давл") || title.contains("Темп")
                || title.contains("TEYES")) return "◉";
        if (title.contains("Медиа") || title.contains("Музык") || title.contains("Music")
                || title.contains("трек") || title.contains("музык")) return "♪";
        if (title.contains("звон") || title.contains("Звон") || title.contains("BT Audio")) return "☎";
        if (title.contains("Нав") || title.contains("Yandex") || title.contains("2GIS")
                || title.contains("улица") || title.contains("Финиш")
                || title.contains("Текущ") || title.contains("манёвра")) return "⌖";
        if (title.contains("CAN") || title.contains("SAS") || title.contains("AMP")
                || title.contains("адаптер")) return "◇";
        if (title.contains("Наст") || title.contains("Система") || title.contains("Авто")) return "⚙";
        if (title.contains("Отлад") || title.contains("Диаг") || title.contains("gs_usb")
                || title.contains("Запись")) return "≡";
        if (title.contains("Запрос") || title.contains("Разреш")) return "✓";
        if (title.contains("Прош") || title.contains("Обнов") || title.contains("APK")) return "⇧";
        if (title.contains("CarPlay") || title.contains("Android")) return "▣";
        return "•";
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(2f, 1.0f);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void requestRuntimePermissions() {
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_MEDIA_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        AppSettings.setRuntimePermissionsRequested(this, true);
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 10);
            return;
        }
        if (!requestBackgroundLocationPermission()) {
            handler.postDelayed(this::requestStartupSpecialPermissions, 900L);
        }
    }

    private boolean requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < 29) return false;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) return false;
        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 11);
        return true;
    }

    private void requestStartupSpecialPermissions() {
        if (isFinishing() || specialPermissionWaiting) return;
        Intent intent = nextSpecialPermissionIntent();
        if (intent == null) return;
        try {
            specialPermissionWaiting = true;
            startActivity(intent);
            AppLog.line(this, "Permissions: requested " + cleanAction(intent.getAction()));
        } catch (Exception e) {
            specialPermissionWaiting = false;
            AppLog.line(this, "Permissions: request failed " + e.getClass().getSimpleName());
            handler.postDelayed(this::requestStartupSpecialPermissions, 500L);
        }
    }

    private Intent nextSpecialPermissionIntent() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this) && !askedWriteSettings) {
            askedWriteSettings = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            return intent;
        }
        if ((AppSettings.navOverlayEnabled(this) || AppSettings.mediaOverlayEnabled(this))
                && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            return intent;
        }
        if (!batteryOptimizationIgnored() && !askedBatteryOptimization) {
            askedBatteryOptimization = true;
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            AppSettings.setBatteryOptimizationRequested(this, true);
            return intent;
        }
        if (!notificationListenerEnabled() && !askedNotificationListener) {
            askedNotificationListener = true;
            return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        return null;
    }

    private boolean batteryOptimizationIgnored() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
            return true;
        }
    }

    private static String cleanAction(String action) {
        return action == null ? "special" : action.replace("android.settings.", "");
    }
}
