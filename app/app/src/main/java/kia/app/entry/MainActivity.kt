package kia.app.entry

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kia.app.R
import kia.app.adas.AdasController
import kia.app.amp.AmpController
import kia.app.amp.AmpVisualizerView
import kia.app.core.AppIds
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.AmpState
import kia.app.core.model.MediaState
import kia.app.core.model.TpmsState
import kia.app.core.model.UpdateState
import kia.app.core.settings.AppSettings
import kia.app.diagnostics.DiagnosticBundleExporter
import kia.app.diagnostics.SettingsTransfer
import kia.app.media.capture.MediaNotificationListener
import kia.app.media.domain.CallFeature
import kia.app.media.domain.MediaFeature
import kia.app.media.domain.MediaSettingsVisibilityPolicy
import kia.app.media.domain.RadioStationStore
import kia.app.media.overlay.MediaOverlayController
import kia.app.navigation.domain.NavigationFeature
import kia.app.navigation.domain.NavigationModeSettings
import kia.app.navigation.domain.NavigationMotionPolicy
import kia.app.navigation.domain.NavigationOutputMode
import kia.app.navigation.overlay.NavigationOverlayController
import kia.app.protocol.adapter.AdapterCommand
import kia.app.protocol.adapter.AdapterGateway
import kia.app.protocol.adapter.AdapterProtocol
import kia.app.rcta.BlindSpotOverlayView
import kia.app.rcta.RctaOverlayController
import kia.app.rcta.RctaSoundPlayer
import kia.app.sunroof.SunroofOverlayController
import kia.app.tpms.DashboardNavigationSnapshot
import kia.app.tpms.TeyesTpmsWidgetForwarder
import kia.app.tpms.TpmsAlertController
import kia.app.tpms.TpmsController
import kia.app.tpms.TpmsDashboardView
import kia.app.tpms.TpmsPollingMode
import kia.app.tpms.TpmsWarningOverlayController
import kia.app.update.AppUpdateCheckPolicy
import kia.app.update.AppUpdateController
import kia.app.update.FirmwareUpdateController
import kia.app.update.NavigatorUpdateController
import kia.app.update.UpdateNotificationController
import kia.app.update.UpdateRuntimeStore

class MainActivity : Activity() {
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val fileExecutor: ExecutorService? =
        Executors.newSingleThreadExecutor({ runnable ->
            val thread: Thread = Thread(runnable, "kia-user-file")
            thread.setDaemon(true)
            thread
        })
    private var screenFrame: FrameLayout? = null
    private var rootLayout: LinearLayout? = null
    private var tabContent: LinearLayout? = null
    private var tpmsDashboard: TpmsDashboardView? = null
    private var tabButtons: kotlin.Array<TextView?>? = null
    private var selectedTab: Int = MainActivity.TAB_TPMS
    private var settingsMode: Boolean = false
    private var settingsTab: Int = MainActivity.SETTINGS_TPMS
    private var rootInsetTop: Int = 0
    private var rootInsetBottom: Int = 0
    private var status: TextView? = null
    private var tpmsStatus: TextView? = null
    private var mediaTabToggle: CompoundButton? = null
    private var callEnabledToggle: CompoundButton? = null
    private var mediaDebugToggle: CompoundButton? = null
    private var mediaDebugStatus: TextView? = null
    private var navigationStatus: TextView? = null
    private var navigationDebugStatus: TextView? = null
    private var navigationDebugToggle: CompoundButton? = null
    private var navigationOverlayToggle: CompoundButton? = null
    private var microManeuverToggle: CompoundButton? = null
    private var straightUntilMainToggle: CompoundButton? = null
    private var finishCompassAutoToggle: CompoundButton? = null
    private var navTbtToggle: CompoundButton? = null
    private var overspeedToggle: CompoundButton? = null
    private var autoStartToggle: CompoundButton? = null
    private var diagnosticsStatus: TextView? = null
    private var adasStatus: TextView? = null
    private var adasDiagnosticStatus: TextView? = null
    private var adasDiagnosticButton: View? = null
    private var canbusDebugStatus: TextView? = null
    private var canbusDebugToggle: CompoundButton? = null
    private var ampEnabledToggle: CompoundButton? = null
    private var ampVisualizer: AmpVisualizerView? = null
    private var ampSummary: TextView? = null
    private var tpmsAlertsToggle: CompoundButton? = null
    private var tpmsSoundToggle: CompoundButton? = null
    private var updatesStatus: TextView? = null
    private var updatesPanelView: LinearLayout? = null
    private var firmwareStatus: TextView? = null
    private var firmwareActionButton: FrameLayout? = null
    private var firmwareProgressFill: View? = null
    private var firmwareActionText: TextView? = null
    private var firmwareActionHintText: TextView? = null
    private var log: TextView? = null
    private var permissionSummary: TextView? = null
    private var healthSummary: TextView? = null
    private var sasRatioStatus: TextView? = null
    private var sasRatioInput: EditText? = null
    private var sasRatioPreview: View? = null
    private val navSourceModeViews: kotlin.Array<View?> = arrayOfNulls<View>(4)
    private val navSourceModeChecks: kotlin.Array<TextView?> = arrayOfNulls<TextView>(4)
    private val navTextModeViews: kotlin.Array<View?> = arrayOfNulls<View>(3)
    private val navTextModeChecks: kotlin.Array<TextView?> = arrayOfNulls<TextView>(3)
    private val navRouteModeViews: kotlin.Array<View?> = arrayOfNulls<View>(3)
    private val navRouteModeChecks: kotlin.Array<TextView?> = arrayOfNulls<TextView>(3)
    private val mediaWizardProfileViews: kotlin.Array<View?> = arrayOfNulls<View>(4)
    private val mediaWizardProfileChecks: kotlin.Array<TextView?> = arrayOfNulls<TextView>(4)
    private var activeScrollView: ScrollView? = null
    private var rctaPreview: BlindSpotOverlayView? = null
    private var rctaDemoOverlay: BlindSpotOverlayView? = null
    private var rctaSound: RctaSoundPlayer? = null
    private var rctaDemoLeftActive: Boolean = false
    private var rctaDemoRightActive: Boolean = false
    private var rctaDemoForceSound: Boolean = false
    private var rctaDiagnosticsActive: Boolean = false
    private val ampInputs: ArrayList<EditText?> = ArrayList()
    private val tpmsInputs: ArrayList<EditText?> = ArrayList()
    private var pendingFirmwareUri: Uri? = null
    private var pendingFirmwareLabel: String? = ""
    private var appUpdater: AppUpdateController? = null
    private var navigatorUpdater: NavigatorUpdateController? = null
    private var firmwareUpdater: FirmwareUpdateController? = null
    private var lastAmpRequestAt: Long = 0
    private var specialPermissionWaiting: Boolean = false
    private var launchUpdateCheckStarted: Boolean = false
    private var appUpdatePromptShown: Boolean = false
    private var navigatorUpdatePromptShown: Boolean = false
    private var activityVisible: Boolean = false
    private var updatePromptDialog: AlertDialog? = null
    private var askedOverlay: Boolean = false
    private var askedBackgroundLocationSettings: Boolean = false
    private var askedBatteryOptimization: Boolean = false
    private var askedNotificationListener: Boolean = false
    private var renderedTpmsAt: Long = -1L
    private var renderedTpmsAlerts: Boolean = false
    private var renderedTpmsWarningKey: String? = ""
    private var mainScrollY: Int = 0
    private var settingsScrollY: Int = 0
    private var focusUpdatesAfterRender: Boolean = false
    private var radioStationManagerScrollY: Int = 0
    private var restoreRadioStationManagerScroll: Boolean = false
    private val backCallback: OnBackInvokedCallback = OnBackInvokedCallback { this.handleAppBack() }

    private fun interface IntSetter {
        fun set(value: Int)
    }

    private val rctaDemoLeft: Runnable =
        object : Runnable {
            override fun run() {
                showRctaDemoAlert(true, false)
            }
        }
    private val rctaDemoRight: Runnable =
        object : Runnable {
            override fun run() {
                showRctaDemoAlert(false, true)
            }
        }
    private val rctaDemoBoth: Runnable =
        object : Runnable {
            override fun run() {
                showRctaDemoAlert(true, true)
            }
        }
    private val rctaDemoHide: Runnable =
        object : Runnable {
            override fun run() {
                hideRctaDemoAlert()
            }
        }
    private val pendingStateRefresh: Runnable =
        object : Runnable {
            override fun run() {
                if (!isFinishing() && !isDestroyed()) {
                    refresh()
                }
            }
        }

    private val stateReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null && AppIds.ACTION_RCTA_DEMO.equals(intent.getAction())) {
                    startRctaDemoSequence()
                    return
                }
                handler.removeCallbacks(pendingStateRefresh)
                handler.post(pendingStateRefresh)
            }
        }

    private val refreshTick: Runnable =
        object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 1000L)
            }
        }

    override protected fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.applyDefaults(this)
        StateStore.restoreNavigation(this)
        appUpdater = AppUpdateController(this)
        navigatorUpdater = NavigatorUpdateController(this)
        firmwareUpdater = FirmwareUpdateController.get(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher()
                .registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backCallback,
                )
        }
        applyImmersiveMode()
        AppService.start(this)
        handleUpdateIntent(getIntent())
        handler.postDelayed({ this.requestUpdateNotificationPermissionIfNeeded() }, 400L)
        handler.postDelayed({ this.maybeShowMediaProfileWizard() }, 900L)
        handler.postDelayed({ this.checkUpdatesOnLaunch() }, 1600L)
        refresh()
    }

    private fun checkUpdatesOnLaunch() {
        checkUpdates(false, "Startup")
    }

    private fun checkUpdates(force: Boolean, reason: String?) {
        if (isFinishing() || isDestroyed() || (!force && launchUpdateCheckStarted)) return
        val now: Long = System.currentTimeMillis()
        val appDue: Boolean =
            force || AppUpdateCheckPolicy.shouldCheck(now, AppSettings.lastAppUpdateCheckAt(this))
        val navigatorDue: Boolean =
            force ||
                AppUpdateCheckPolicy.shouldCheck(now, AppSettings.lastNavigatorUpdateCheckAt(this))
        if (!appDue && !navigatorDue) return
        launchUpdateCheckStarted = true
        AppLog.line(
            this,
            (reason.toString() + " update check: Kia=" + appDue + " Yandex=" + navigatorDue),
        )
        if (appDue) appUpdater!!.checkAsync()
        if (navigatorDue) navigatorUpdater!!.checkAsync()
        handler.postDelayed({ this.maybeShowLaunchUpdatePrompt() }, 1300L)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent == null || !AppIds.ACTION_OPEN_UPDATES.equals(intent.getAction())) return
        intent.setAction(null)
        appUpdatePromptShown = true
        navigatorUpdatePromptShown = true
        activeScrollView = null
        settingsMode = true
        settingsTab = MainActivity.SETTINGS_GENERAL
        settingsScrollY = 0
        focusUpdatesAfterRender = true
        if (screenFrame != null) {
            renderTab()
            refresh()
        }
        handler.post({ checkUpdates(true, "Notification tap") })
    }

    private fun requestUpdateNotificationPermissionIfNeeded() {
        UpdateNotificationController.prepareChannel(this)
        if (
            Build.VERSION.SDK_INT < 33 ||
                (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) ||
                UpdateRuntimeStore.notificationPermissionAsked(this)
        ) {
            return
        }
        UpdateRuntimeStore.markNotificationPermissionAsked(this)
        requestPermissions(
            arrayOf<String?>(Manifest.permission.POST_NOTIFICATIONS),
            MainActivity.REQUEST_UPDATE_NOTIFICATIONS,
        )
    }

    private fun maybeShowLaunchUpdatePrompt() {
        if (!activityVisible || isFinishing() || isDestroyed()) return
        if (
            NavigationFeature.get(this).freshMotionState(5) !=
                NavigationMotionPolicy.State.STATIONARY
        )
            return
        if (updatePromptDialog != null && updatePromptDialog!!.isShowing()) return
        val s = StateStore.updates()
        if (!appUpdatePromptShown && !s.appChecking && !s.appDownloading && s.appAvailable) {
            showLaunchUpdatePrompt(
                true,
                "Обновление Kia",
                MainActivity.displayStatus(s.appStatus) +
                    "\n\nНажмите Обновить, чтобы скачать APK и открыть установщик.",
            )
            return
        }
        if (
            !navigatorUpdatePromptShown &&
                !s.navigatorChecking &&
                !s.navigatorDownloading &&
                !s.navigatorInstalling &&
                s.navigatorAvailable
        ) {
            showLaunchUpdatePrompt(
                false,
                "Обновление Yandex Navigator",
                MainActivity.displayStatus(s.navigatorStatus) +
                    "\n\nНажмите Обновить, чтобы скачать mod APK и открыть установщик.",
            )
        }
    }

    private fun showLaunchUpdatePrompt(appUpdate: Boolean, title: String?, message: String?) {
        if (appUpdate) appUpdatePromptShown = true else navigatorUpdatePromptShown = true
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(
                    "Обновить",
                    { d, which ->
                        if (appUpdate) appUpdater!!.downloadAndInstall(this)
                        else navigatorUpdater!!.downloadAndInstall(this)
                        refresh()
                    },
                )
                .setNegativeButton("Позже", null)
                .create()
        dialog.setOnDismissListener({ d ->
            if (updatePromptDialog === dialog) updatePromptDialog = null
            handler.postDelayed({ this.maybeShowLaunchUpdatePrompt() }, 500L)
        })
        updatePromptDialog = dialog
        dialog.show()
    }

    private fun maybeShowMediaProfileWizard() {
        if (isFinishing() || isDestroyed() || AppSettings.mediaProfileConfigured(this)) return
        val dialog = AlertDialog.Builder(this).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setView(mediaProfileWizardView(dialog), 0, 0, 0, 0)
        dialog.setOnCancelListener({ d -> AppSettings.setMediaProfileConfigured(this, true) })
        dialog.show()
        val window: Window? = dialog.getWindow()
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.68f)
            val lp: WindowManager.LayoutParams = WindowManager.LayoutParams()
            lp.copyFrom(window.getAttributes())
            val screenWidth: Int = getResources().getDisplayMetrics().widthPixels
            lp.width =
                Math.min(screenWidth - dp(36), dp(if (mediaProfileWizardWideLayout()) 560 else 620))
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            window.setAttributes(lp)
        }
    }

    private fun mediaProfileWizardView(dialog: AlertDialog): View {
        val wide: Boolean = mediaProfileWizardWideLayout()
        MainActivity.clearChoiceViews(mediaWizardProfileViews, mediaWizardProfileChecks)
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setPadding(
            dp(if (wide) 18 else 22),
            dp(if (wide) 16 else 18),
            dp(if (wide) 18 else 22),
            dp(if (wide) 18 else 22),
        )
        panel.setBackground(
            round(
                MainActivity.COLOR_SETTINGS_PANEL,
                dp(8),
                MainActivity.COLOR_SETTINGS_DIVIDER,
                dp(1),
            )
        )

        val head: LinearLayout = row()
        head.setGravity(Gravity.CENTER_VERTICAL)
        val titleBox: LinearLayout = LinearLayout(this)
        titleBox.setOrientation(LinearLayout.VERTICAL)
        val title: TextView =
            text("Профиль магнитолы", if (wide) 16 else (if (isCompact()) 18 else 20), Color.WHITE)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setIncludeFontPadding(false)
        titleBox.addView(title)
        val hint: TextView =
            text(
                "Выберите источник музыки для приборки. Потом можно изменить в настройках.",
                if (wide) 10 else (if (isCompact()) 11 else 12),
                MainActivity.COLOR_MUTED,
            )
        hint.setIncludeFontPadding(false)
        hint.setMaxLines(2)
        val hintLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        hintLp.setMargins(0, dp(2), 0, 0)
        titleBox.addView(hint, hintLp)
        head.addView(
            titleBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val close: TextView = text("×", if (wide) 20 else 24, MainActivity.COLOR_MUTED)
        close.setGravity(Gravity.CENTER)
        close.setTypeface(Typeface.DEFAULT_BOLD)
        close.setClickable(true)
        close.setFocusable(true)
        close.setContentDescription("Закрыть мастер")
        close.setBackground(settingsButtonBackground(false))
        close.setOnClickListener({ v -> closeMediaProfileWizard(dialog) })
        val closeLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        head.addView(close, closeLp)
        val headLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        headLp.setMargins(0, 0, 0, dp(if (wide) 4 else 6))
        panel.addView(head, headLp)

        if (wide) {
            panel.addView(
                mediaProfileWizardChoiceRow(
                    dialog,
                    mediaProfileWizardChoice(
                        dialog,
                        AppSettings.MEDIA_PROFILE_TEYES,
                        "TEYES / CC4 Pro",
                        "Музыка, радио и Bluetooth с магнитолы TEYES",
                        MainActivity.COLOR_ACCENT_BLUE,
                    ),
                    mediaProfileWizardChoice(
                        dialog,
                        AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID,
                        "Музыка Android",
                        "Трек и источник из Android-приложений",
                        MainActivity.COLOR_ACCENT,
                    ),
                )
            )
            panel.addView(
                mediaProfileWizardChoiceRow(
                    dialog,
                    mediaProfileWizardChoice(
                        dialog,
                        AppSettings.MEDIA_PROFILE_UART_REAL,
                        "Штатный источник + Android",
                        "Магнитола ведёт режим, Kia показывает трек",
                        MainActivity.COLOR_WARNING,
                    ),
                    mediaProfileWizardChoice(
                        dialog,
                        AppSettings.MEDIA_PROFILE_OFF,
                        "Media выключено",
                        "Не отправлять музыку на приборку",
                        MainActivity.COLOR_MUTED,
                    ),
                )
            )
        } else {
            panel.addView(
                mediaProfileWizardChoice(
                    dialog,
                    AppSettings.MEDIA_PROFILE_TEYES,
                    "TEYES / CC4 Pro",
                    "Музыка, радио и Bluetooth с магнитолы TEYES",
                    MainActivity.COLOR_ACCENT_BLUE,
                )
            )
            panel.addView(
                mediaProfileWizardChoice(
                    dialog,
                    AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID,
                    "Музыка Android",
                    "Трек и источник из Android-приложений",
                    MainActivity.COLOR_ACCENT,
                )
            )
            panel.addView(
                mediaProfileWizardChoice(
                    dialog,
                    AppSettings.MEDIA_PROFILE_UART_REAL,
                    "Штатный источник + Android",
                    "Магнитола ведёт режим, Kia показывает трек",
                    MainActivity.COLOR_WARNING,
                )
            )
            panel.addView(
                mediaProfileWizardChoice(
                    dialog,
                    AppSettings.MEDIA_PROFILE_OFF,
                    "Media выключено",
                    "Не отправлять музыку на приборку",
                    MainActivity.COLOR_MUTED,
                )
            )
        }

        val actions: LinearLayout = row()
        val permissions: TextView = wizardActionButton("Проверить разрешения", false)
        permissions.setOnClickListener({ v ->
            closeMediaProfileWizard(dialog)
            requestRuntimePermissions()
        })
        val done: TextView = wizardActionButton("Готово", true)
        done.setOnClickListener({ v -> closeMediaProfileWizard(dialog) })
        val permissionsLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        permissionsLp.setMargins(0, dp(10), dp(5), 0)
        val doneLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        doneLp.setMargins(dp(5), dp(10), 0, 0)
        actions.addView(permissions, permissionsLp)
        actions.addView(done, doneLp)
        panel.addView(actions)

        val bottomGuard: Space = Space(this)
        panel.addView(
            bottomGuard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)),
        )

        return panel
    }

    private fun wizardActionButton(label: String?, primary: Boolean): TextView {
        val button: TextView = text(label, if (isCompact()) 14 else 15, Color.WHITE)
        button.setGravity(Gravity.CENTER)
        button.setTypeface(Typeface.DEFAULT_BOLD)
        button.setClickable(true)
        button.setFocusable(true)
        button.setContentDescription(label)
        button.setBackground(
            settingsActionBackground(
                primary,
                if (primary) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED,
            )
        )
        return button
    }

    private fun mediaProfileWizardChoiceRow(
        dialog: AlertDialog?,
        left: View?,
        right: View?,
    ): LinearLayout {
        val line: LinearLayout = row()
        val leftLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        leftLp.setMargins(0, dp(6), dp(5), dp(4))
        val rightLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rightLp.setMargins(dp(5), dp(6), 0, dp(4))
        line.addView(left, leftLp)
        line.addView(right, rightLp)
        return line
    }

    private fun mediaProfileWizardChoice(
        dialog: AlertDialog?,
        profile: Int,
        title: String?,
        hint: String?,
        color: Int,
    ): View {
        val wide: Boolean = mediaProfileWizardWideLayout()
        val selected: Boolean = AppSettings.mediaProfile(this) == profile
        val item: LinearLayout = LinearLayout(this)
        item.setOrientation(LinearLayout.VERTICAL)
        item.setGravity(Gravity.CENTER_VERTICAL)
        item.setPadding(
            dp(if (wide) 14 else 16),
            dp(if (wide) 8 else 12),
            dp(if (wide) 14 else 16),
            dp(if (wide) 8 else 12),
        )
        item.setMinimumHeight(dp(if (wide) 58 else 78))
        item.setClickable(true)
        item.setFocusable(true)
        item.setBackground(settingsButtonBackground(selected))

        val header: LinearLayout = row()
        val name: TextView =
            text(title, if (wide) 12 else (if (isCompact()) 15 else 16), Color.WHITE)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        name.setSingleLine(false)
        name.setMaxLines(1)
        val nameLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(name, nameLp)
        val check: TextView =
            text(
                "выбрано",
                if (wide) 10 else 11,
                if (selected) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED,
            )
        check.setGravity(Gravity.CENTER)
        check.setTypeface(Typeface.DEFAULT_BOLD)
        check.setVisibility(if (selected) View.VISIBLE else View.INVISIBLE)
        header.addView(check)

        val sub: TextView =
            text(hint, if (wide) 9 else (if (isCompact()) 11 else 12), MainActivity.COLOR_MUTED)
        sub.setMaxLines(2)
        item.addView(header)
        item.addView(sub)

        val index: Int = mediaWizardProfileIndex(profile)
        if (index >= 0) {
            mediaWizardProfileViews[index] = item
            mediaWizardProfileChecks[index] = check
        }
        item.setOnClickListener({ v ->
            setMediaProfile(profile)
            updateMediaWizardChoices(profile)
        })
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(6), 0, dp(4))
        item.setLayoutParams(lp)
        return item
    }

    private fun closeMediaProfileWizard(dialog: AlertDialog) {
        AppSettings.setMediaProfileConfigured(this, true)
        dialog.dismiss()
    }

    private fun updateMediaWizardChoices(selectedProfile: Int) {
        val selectedIndex: Int = mediaWizardProfileIndex(selectedProfile)
        for (i in mediaWizardProfileViews.indices) {
            val view: View? = mediaWizardProfileViews.get(i)
            val check: TextView? = mediaWizardProfileChecks.get(i)
            if (view == null || check == null) continue
            val selected: Boolean = i == selectedIndex
            view.setBackground(settingsButtonBackground(selected))
            check.setTextColor(
                if (selected) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED
            )
            check.setVisibility(if (selected) View.VISIBLE else View.INVISIBLE)
        }
    }

    private fun mediaWizardProfileIndex(profile: Int): Int {
        return if (profile >= 0 && profile < mediaWizardProfileViews.size) profile else -1
    }

    private fun mediaProfileWizardWideLayout(): Boolean {
        return isLandscapeWindow() || screenWidthDp() >= 620
    }

    override fun onStart() {
        super.onStart()
        SunroofOverlayController.get(this).setActivityVisible(true)
    }

    override @SuppressLint("UnspecifiedRegisterReceiverFlag") protected fun onResume() {
        super.onResume()
        activityVisible = true
        val filter: IntentFilter = IntentFilter(AppIds.ACTION_STATE_CHANGED)
        filter.addAction(AppIds.ACTION_RCTA_DEMO)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        handler.post(refreshTick)
        normalizeModeForWindow()
        syncTpmsPollingMode()
        TpmsWarningOverlayController.get(this).setActivityVisible(true)
        NavigationOverlayController.get(this).apply()
        SunroofOverlayController.get(this).apply()
        applyImmersiveMode()
        if ((settingsMode || selectedTab == MainActivity.TAB_SETTINGS) && tabContent != null)
            renderTab()
        if (specialPermissionWaiting) {
            specialPermissionWaiting = false
            handler.postDelayed({ this.requestStartupSpecialPermissions() }, 700L)
        }
    }

    override protected fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    override protected fun onPause() {
        activityVisible = false
        handler.removeCallbacks(refreshTick)
        handler.removeCallbacks(pendingStateRefresh)
        cancelRctaDemoSequence()
        hideRctaDemoAlert()
        syncTpmsPollingMode()
        TpmsWarningOverlayController.get(this).setActivityVisible(false)
        try {
            unregisterReceiver(stateReceiver)
        } catch (ignored: Exception) {}
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        SunroofOverlayController.get(this).setActivityVisible(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun syncTpmsPollingMode() {
        TpmsController.get(this)
            .setPollingMode(
                if (activityVisible) TpmsPollingMode.FULL_DASHBOARD else TpmsPollingMode.BACKGROUND
            )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        normalizeModeForWindow()
        syncTpmsPollingMode()
        renderTab()
        refresh()
        applyImmersiveMode()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration?) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        normalizeModeForWindow()
        syncTpmsPollingMode()
        renderTab()
        refresh()
        applyImmersiveMode()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MainActivity.REQUEST_UPDATE_NOTIFICATIONS) {
            AppService.start(this)
            if (notificationPermissionGranted()) {
                checkUpdates(true, "Notification permission")
            }
            UpdateNotificationController.refresh(this)
            refresh()
            return
        }
        if (requestCode == 10 || requestCode == 11) {
            AppService.start(this)
            if (requestCode == 11 || !requestBackgroundLocationPermission()) {
                handler.postDelayed({ this.requestStartupSpecialPermissions() }, 700L)
            }
            refresh()
        }
    }

    override protected fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (
            requestCode == MainActivity.REQUEST_EXPORT_SETTINGS ||
                requestCode == MainActivity.REQUEST_IMPORT_SETTINGS ||
                requestCode == MainActivity.REQUEST_DIAGNOSTICS_FILE
        ) {
            handleUserFileResult(requestCode, resultCode, data)
            return
        }
        if (requestCode != MainActivity.REQUEST_FIRMWARE_FILE) return
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            AppLog.line(this, "Firmware file: selection cancelled")
            refresh()
            return
        }
        val uri: Uri? = data.getData()
        try {
            getContentResolver()
                .takePersistableUriPermission(uri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: Exception) {}
        val label = firmwareFileName(uri)
        AppLog.line(this, "Firmware file selected: " + label)
        pendingFirmwareUri = uri
        pendingFirmwareLabel = label
        renderTab()
        refresh()
    }

    private fun handleUserFileResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return
        val uri: Uri? = data.getData()
        if (requestCode == MainActivity.REQUEST_EXPORT_SETTINGS) {
            runUserFileTask(
                "Настройки сохранены",
                FileTask { SettingsTransfer.write(this, uri) },
                false,
            )
        } else if (requestCode == MainActivity.REQUEST_IMPORT_SETTINGS) {
            runUserFileTask(
                "Настройки восстановлены",
                FileTask {
                    val result = SettingsTransfer.read(this, uri)
                    AppLog.line(this, result.summary())
                },
                true,
            )
        } else if (requestCode == MainActivity.REQUEST_DIAGNOSTICS_FILE) {
            runUserFileTask(
                "Диагностика сохранена",
                FileTask { DiagnosticBundleExporter.write(this, uri) },
                false,
            )
        }
    }

    private fun runUserFileTask(success: String?, task: FileTask, restartFeatures: Boolean) {
        Toast.makeText(this, "Подготовка файла…", Toast.LENGTH_SHORT).show()
        fileExecutor!!.execute({
            var failure: String? = null
            try {
                task.run()
            } catch (e: Exception) {
                failure = e.message
                if (failure == null || failure.trim { it <= ' ' }.isEmpty()) {
                    failure = e.javaClass.getSimpleName()
                }
                AppLog.line(
                    this,
                    ("User file operation failed: " + e.javaClass.getSimpleName() + " " + failure),
                )
            }
            val finalFailure: String? = failure
            handler.post({
                if (isFinishing() || isDestroyed()) return@post
                if (finalFailure == null) {
                    if (restartFeatures) {
                        AppService.start(this)
                        renderTab()
                        refresh()
                    }
                    Toast.makeText(this, success, Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Не удалось обработать файл")
                        .setMessage(finalFailure)
                        .setPositiveButton("Закрыть", null)
                        .show()
                }
            })
        })
    }

    private fun interface FileTask {
        @Throws(Exception::class) fun run()
    }

    override protected fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        fileExecutor!!.shutdownNow()
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback)
        }
        super.onDestroy()
    }

    override @SuppressLint("GestureBackNavigation") fun onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) {
            handleAppBack()
            return
        }
        handleAppBack()
    }

    private fun handleAppBack() {
        if (settingsMode) {
            closeSettings()
            return
        }
        if (selectedTab != MainActivity.TAB_TPMS) {
            selectedTab = MainActivity.TAB_TPMS
            renderTab()
            refresh()
            return
        }
        finishAfterTransition()
    }

    private fun buildUi(): View? {
        screenFrame = FrameLayout(this)
        screenFrame!!.setBackgroundColor(MainActivity.COLOR_BG)
        renderTab()
        return screenFrame
    }

    private fun startRctaDemoSequence() {
        cancelRctaDemoSequence()
        settingsMode = false
        selectedTab = MainActivity.TAB_TPMS
        renderTab()
        refresh()
        handler.postDelayed(rctaDemoLeft, 250L)
        handler.postDelayed(rctaDemoRight, 5250L)
        handler.postDelayed(rctaDemoBoth, 10250L)
        handler.postDelayed(rctaDemoHide, 20250L)
        AppLog.line(this, "RCTA demo: left 5s, right 5s, both 10s")
    }

    private fun cancelRctaDemoSequence() {
        handler.removeCallbacks(rctaDemoLeft)
        handler.removeCallbacks(rctaDemoRight)
        handler.removeCallbacks(rctaDemoBoth)
        handler.removeCallbacks(rctaDemoHide)
    }

    private fun showRctaDemoAlert(left: Boolean, right: Boolean, forceSound: Boolean = false) {
        if (screenFrame == null) return
        rctaDemoLeftActive = left
        rctaDemoRightActive = right
        rctaDemoForceSound = forceSound
        if (rctaDemoOverlay == null) {
            rctaDemoOverlay = BlindSpotOverlayView(this)
            rctaDemoOverlay!!.setClickable(false)
        }
        rctaDemoOverlay!!.setStyleType(AppSettings.rctaStyle(this))
        rctaDemoOverlay!!.setAlertColor(AppSettings.rctaColor(this))
        rctaDemoOverlay!!.setBackgroundAlpha(AppSettings.rctaBackgroundAlpha(this))
        rctaDemoOverlay!!.setArrowCount(AppSettings.rctaArrowCount(this))
        rctaDemoOverlay!!.setPreview(left, right, false)
        if (rctaDemoOverlay!!.getParent() == null) {
            screenFrame!!.addView(
                rctaDemoOverlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        } else {
            rctaDemoOverlay!!.bringToFront()
        }
        rctaDemoOverlay!!.postInvalidateOnAnimation()
        applyRctaDemoSound()
    }

    private fun hideRctaDemoAlert() {
        if (rctaDemoOverlay != null && rctaDemoOverlay!!.getParent() is ViewGroup) {
            (rctaDemoOverlay!!.getParent() as ViewGroup).removeView(rctaDemoOverlay)
        }
        rctaDemoOverlay = null
        rctaDemoLeftActive = false
        rctaDemoRightActive = false
        rctaDemoForceSound = false
        if (rctaSound != null) rctaSound!!.stop()
    }

    private fun applyRctaDemoSound() {
        if (rctaSound == null) rctaSound = RctaSoundPlayer(this)
        val style: Int = AppSettings.rctaStyle(this)
        val mode: Int = AppSettings.rctaSoundMode(this)
        // Preserve the existing forced diagnostic tone for types 1/2. TEYES previews
        // and voice warnings always follow the sound switch, including diagnostics.
        val enabled: Boolean =
            AppSettings.rctaSoundEnabled(this) ||
                (rctaDemoForceSound &&
                    style != AppSettings.RCTA_STYLE_TEYES &&
                    mode == AppSettings.RCTA_SOUND_TONE)
        rctaSound!!.apply(style, mode, rctaDemoLeftActive, rctaDemoRightActive, enabled)
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false)
            val controller = getWindow().getDecorView().getWindowInsetsController()
            if (controller != null) {
                if (isInMultiWindowMode()) {
                    controller.show(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                    )
                } else {
                    controller.hide(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                    )
                }
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                )
            }
        }
        if (Build.VERSION.SDK_INT < 30 || !isInMultiWindowMode()) {
            getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                )
        }
    }

    private fun prepareContentHost(fixedScreen: Boolean) {
        if (screenFrame == null) return
        screenFrame!!.removeAllViews()
        activeScrollView = null
        screenFrame!!.setBackgroundColor(
            if (settingsMode) MainActivity.COLOR_SETTINGS_BG else MainActivity.COLOR_BG
        )
        val root: LinearLayout = LinearLayout(this)
        rootLayout = root
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(
            if (settingsMode) MainActivity.COLOR_SETTINGS_BG else MainActivity.COLOR_BG
        )
        root.setOnApplyWindowInsetsListener({ view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe: android.graphics.Insets? =
                    insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                rootInsetTop = Math.max(0, safe!!.top)
                rootInsetBottom = Math.max(0, safe.bottom)
            } else {
                rootInsetTop = Math.max(0, insets.getSystemWindowInsetTop())
                rootInsetBottom = Math.max(0, insets.getSystemWindowInsetBottom())
            }
            applyRootPadding()
            insets
        })
        applyRootPadding()

        if (settingsMode) {
            tabContent = LinearLayout(this)
            tabContent!!.setOrientation(LinearLayout.VERTICAL)
            screenFrame!!.addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            return
        }

        tabContent = LinearLayout(this)
        tabContent!!.setOrientation(LinearLayout.VERTICAL)
        root.addView(
            tabContent,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (fixedScreen) ViewGroup.LayoutParams.MATCH_PARENT
                else ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        if (fixedScreen) {
            screenFrame!!.addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            return
        }

        val scroll: ScrollView = ScrollView(this)
        scroll.setFillViewport(true)
        scroll.setBackgroundColor(MainActivity.COLOR_BG)
        scroll.setClipToPadding(true)
        scroll.setPadding(0, 0, 0, dp(36))
        scroll.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        screenFrame!!.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        restoreScrollPosition(scroll, false)
    }

    private fun tabBar(): View {
        tabButtons = arrayOfNulls<TextView>(6)
        val row: LinearLayout = row()
        row.setPadding(dp(5), dp(5), dp(5), dp(5))
        row.setBackground(glassPanel(MainActivity.COLOR_ACCENT_BLUE))
        if (AppSettings.mediaTabVisible(this)) addTab(row, MainActivity.TAB_MEDIA, "Медиа")
        addTab(row, MainActivity.TAB_NAVIGATION, "Нави")
        addTab(row, MainActivity.TAB_ADAS, "ADAS")
        addTab(row, MainActivity.TAB_CANBUS, "CAN")
        addTab(row, MainActivity.TAB_SETTINGS, "Общее")
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, 0, 0, dp(14))
        row.setLayoutParams(lp)
        return row
    }

    private fun settingsHeader(): View {
        val panel: LinearLayout = row()
        panel.setPadding(dp(16), dp(12), dp(12), dp(12))
        panel.setBackground(glassPanel(MainActivity.COLOR_ACCENT))
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, 0, 0, dp(10))
        panel.setLayoutParams(lp)

        val titleBox: LinearLayout = LinearLayout(this)
        titleBox.setOrientation(LinearLayout.VERTICAL)
        val title: TextView = text("KIA", if (isCompact()) 22 else 27, Color.WHITE)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        val subtitle: TextView =
            text("Настройки  ·  kia canbus", if (isCompact()) 12 else 14, MainActivity.COLOR_MUTED)
        titleBox.addView(title)
        titleBox.addView(subtitle)
        panel.addView(
            titleBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val close: TextView = text("×", if (isCompact()) 28 else 32, Color.WHITE)
        close.setGravity(Gravity.CENTER)
        close.setTypeface(Typeface.DEFAULT_BOLD)
        close.setClickable(true)
        close.setFocusable(true)
        close.setBackground(glassButton(MainActivity.COLOR_PANEL_SOFT))
        close.setOnClickListener({ v ->
            selectedTab = MainActivity.TAB_TPMS
            renderTab()
            refresh()
        })
        panel.addView(
            close,
            LinearLayout.LayoutParams(
                if (isCompact()) dp(54) else dp(62),
                if (isCompact()) dp(48) else dp(54),
            ),
        )
        return panel
    }

    private fun mediaRadioStationPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Радиостанции",
            "названия по частоте для Android и UART Real; ручное имя всегда важнее авто",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        val entries: List<RadioStationStore.Entry?>? = RadioStationStore.entries(this)
        val media = StateStore.media()
        val frequency = RadioStationStore.currentFrequency(media)
        val band: String = RadioStationStore.currentBand(media)
        panel.addView(radioStationStats(entries!!))
        panel.addView(currentRadioStationCard(band, frequency, media.title))

        addActionGrid(
            panel,
            action(
                "Открыть список",
                if (entries.isEmpty()) "станций пока нет" else "Сохранено: " + entries.size,
                if (entries.isEmpty()) MainActivity.COLOR_MUTED else MainActivity.COLOR_ACCENT_BLUE,
                Runnable { this.showRadioStationListDialogFresh() },
            ),
            action(
                "Добавить станцию",
                "FM/AM, частота и своё название",
                MainActivity.COLOR_ACCENT_BLUE,
                Runnable { this.showAddRadioStationBandDialog() },
            ),
        )
        return panel
    }

    private fun radioStationStats(entries: List<RadioStationStore.Entry?>): View {
        var manual: Int = 0
        for (entry: RadioStationStore.Entry? in entries) {
            if (entry != null && entry.manual) manual++
        }
        val chips: LinearLayout = row()
        chips.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
        chips.addView(
            pill("Всего: " + entries.size, MainActivity.COLOR_ACCENT_BLUE),
            chipLayout(true),
        )
        chips.addView(
            pill(
                "Своих: " + manual,
                if (manual > 0) MainActivity.COLOR_SUCCESS else MainActivity.COLOR_PANEL_SOFT,
            ),
            chipLayout(false),
        )
        chips.addView(
            pill("Авто: " + (entries.size - manual), MainActivity.COLOR_PANEL_SOFT),
            chipLayout(false),
        )
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(12), 0, 0)
        chips.setLayoutParams(lp)
        return chips
    }

    private fun currentRadioStationCard(
        band: String?,
        frequency: String?,
        stationName: String?,
    ): View {
        val card: LinearLayout = LinearLayout(this)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setPadding(dp(16), dp(13), dp(16), dp(13))
        card.setMinimumHeight(if (isCompact()) dp(82) else dp(94))
        val available: Boolean = frequency != null && !frequency.isEmpty()
        card.setBackground(
            settingsActionBackground(
                available,
                if (available) MainActivity.COLOR_SUCCESS else MainActivity.COLOR_ACCENT_BLUE,
            )
        )
        val displayName = RadioStationStore.displayName(band, frequency, stationName)

        val header: LinearLayout = row()
        header.setGravity(Gravity.CENTER_VERTICAL)
        val label: TextView =
            text(
                if (available) "Сейчас в радио" else "Текущая станция",
                if (isCompact()) 13 else 15,
                if (available) MainActivity.COLOR_SUCCESS else MainActivity.COLOR_MUTED,
            )
        label.setTypeface(Typeface.DEFAULT_BOLD)
        header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (available) header.addView(pill(band, MainActivity.COLOR_ACCENT_BLUE))
        card.addView(header)

        val title: TextView =
            text(
                if (available)
                    (if (displayName.isEmpty()) "Название ещё не определено" else displayName)
                else "Включите FM или AM — частота появится здесь",
                if (isCompact()) 17 else 20,
                if (available) Color.WHITE else MainActivity.COLOR_TEXT,
            )
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setMaxLines(2)
        val titleLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        titleLp.setMargins(0, dp(7), 0, 0)
        card.addView(title, titleLp)

        if (available) {
            val unit: String = if ("AM".equals(band, ignoreCase = true)) "кГц" else "МГц"
            val detail: TextView =
                text(
                    frequency + " " + unit + "  ·  Нажмите, чтобы изменить имя",
                    if (isCompact()) 12 else 14,
                    MainActivity.COLOR_MUTED,
                )
            val detailLp: LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            detailLp.setMargins(0, dp(4), 0, 0)
            card.addView(detail, detailLp)
            card.setClickable(true)
            card.setFocusable(true)
            card.setContentDescription(
                (band.toString() +
                    " " +
                    frequency +
                    ". " +
                    (if (displayName.isEmpty()) "Без названия" else displayName) +
                    ". Изменить название")
            )
            card.setOnClickListener({ v -> showCurrentRadioStationDialog() })
        }
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(12), 0, dp(4))
        card.setLayoutParams(lp)
        return card
    }

    private fun mediaSessionPriorityPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT)
        addSettingsPanelHeader(
            panel,
            "Выбор музыкального приложения",
            "действует только для Android и «Штатный источник + Android»; TEYES не меняется",
            MainActivity.COLOR_ACCENT,
        )
        val currentPackage = StateStore.media().packageName
        val currentLabel = mediaSessionAppLabel(currentPackage)
        val summary: TextView =
            text(
                (AppSettings.mediaSessionSelectionSummary(this).toString() +
                    "\nСейчас: " +
                    (if (currentPackage == null || currentPackage.isEmpty())
                        "нет активной MediaSession"
                    else currentLabel.toString() + " · " + currentPackage) +
                    "\nИсключения: " +
                    AppSettings.mediaBlockedSessionsSummary(this)),
                if (isCompact()) 12 else 14,
                Color.rgb(235, 241, 246),
            )
        val summaryLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        summaryLp.setMargins(0, dp(10), 0, dp(6))
        panel.addView(summary, summaryLp)

        val packageReady: Boolean =
            currentPackage != null &&
                !currentPackage.trim { it <= ' ' }.isEmpty() &&
                !getPackageName().equals(currentPackage) &&
                !currentPackage.lowercase(Locale.ROOT).contains("spd.radio")
        val blocked: Boolean =
            packageReady && AppSettings.isMediaSessionPackageBlocked(this, currentPackage)
        val prefer: View =
            action(
                "Предпочитать текущее",
                if (packageReady) currentLabel else "сначала запустите музыку",
                if (packageReady) MainActivity.COLOR_ACCENT else MainActivity.COLOR_MUTED,
                false,
                Runnable {
                    if (!packageReady) return@Runnable
                    AppSettings.setMediaPreferredSessionPackage(this, currentPackage)
                    AppLog.line(this, "Media preferred session: " + currentPackage)
                    renderTab()
                    refresh()
                },
            )
        val block: View =
            action(
                if (blocked) "Вернуть текущее" else "Игнорировать текущее",
                if (packageReady) currentLabel else "сначала запустите музыку",
                if (packageReady)
                    (if (blocked) MainActivity.COLOR_SUCCESS else MainActivity.COLOR_WARNING)
                else MainActivity.COLOR_MUTED,
                false,
                Runnable {
                    if (!packageReady) return@Runnable
                    AppSettings.setMediaSessionPackageBlocked(this, currentPackage, !blocked)
                    AppLog.line(this, "Media session blocked " + currentPackage + ": " + !blocked)
                    renderTab()
                    refresh()
                },
            )
        val reset: View =
            action(
                "Автовыбор",
                "сбросить приоритет и исключения",
                MainActivity.COLOR_ACCENT_BLUE,
                false,
                Runnable { this.resetMediaSessionSelection() },
            )
        addActionGrid(
            panel,
            navSettingsAction(prefer, packageReady),
            navSettingsAction(block, packageReady),
            reset,
        )
        return panel
    }

    private fun mediaSessionAppLabel(packageName: String?): String? {
        if (packageName == null || packageName.trim { it <= ' ' }.isEmpty()) return "приложение"
        try {
            val info: android.content.pm.ApplicationInfo? =
                getPackageManager().getApplicationInfo(packageName, 0)
            val label: CharSequence? = getPackageManager().getApplicationLabel(info!!)
            if (label != null && !label.toString().trim { it <= ' ' }.isEmpty()) {
                return label.toString().trim { it <= ' ' }
            }
        } catch (ignored: Exception) {}
        return packageName
    }

    private fun resetMediaSessionSelection() {
        AppSettings.clearMediaPreferredSessionPackage(this)
        for (packageName: String? in ArrayList(AppSettings.mediaBlockedSessionPackages(this))) {
            AppSettings.setMediaSessionPackageBlocked(this, packageName, false)
        }
        AppLog.line(this, "Media session selection reset")
        renderTab()
        refresh()
    }

    private fun mainGearButton(): View {
        val row: LinearLayout = row()
        row.setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, 0, 0, dp(6))
        row.setLayoutParams(lp)
        val settings: TextView = text("⚙", if (isCompact()) 25 else 29, Color.WHITE)
        settings.setGravity(Gravity.CENTER)
        settings.setTypeface(Typeface.DEFAULT_BOLD)
        settings.setClickable(true)
        settings.setFocusable(true)
        settings.setBackground(glassButton(MainActivity.COLOR_PANEL_SOFT))
        settings.setOnClickListener({ v ->
            settingsMode = true
            settingsTab = MainActivity.SETTINGS_TPMS
            renderTab()
            refresh()
        })
        row.addView(
            settings,
            LinearLayout.LayoutParams(
                if (isCompact()) dp(54) else dp(62),
                if (isCompact()) dp(48) else dp(54),
            ),
        )
        return row
    }

    private fun addTab(row: LinearLayout, tab: Int, title: String?) {
        val view: TextView =
            text(iconForTab(tab) + "  " + title, if (isCompact()) 15 else 17, Color.WHITE)
        view.setGravity(Gravity.CENTER)
        view.setTypeface(Typeface.DEFAULT_BOLD)
        view.setPadding(dp(10), 0, dp(10), 0)
        view.setClickable(true)
        view.setFocusable(true)
        view.setSingleLine(true)
        view.setOnClickListener({ v ->
            selectedTab = tab
            renderTab()
            refresh()
        })
        tabButtons!![tab] = view
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, if (isCompact()) dp(56) else dp(66), 1f)
        lp.setMargins(if (tab == MainActivity.TAB_MEDIA) 0 else dp(4), 0, dp(4), 0)
        row.addView(view, lp)
    }

    private fun renderTab() {
        if (screenFrame == null) return
        rememberScrollPosition()
        if (selectedTab == MainActivity.TAB_MEDIA && !AppSettings.mediaTabVisible(this))
            selectedTab = MainActivity.TAB_TPMS
        clearTabViews()
        prepareContentHost(!settingsMode && selectedTab == MainActivity.TAB_TPMS)
        if (settingsMode) {
            renderSettingsShell()
            restoreRctaDiagnosticsOverlay()
            return
        }
        if (selectedTab != MainActivity.TAB_TPMS) {
            tabContent!!.addView(settingsHeader())
            tabContent!!.addView(tabBar())
        }
        updateTabBar()
        when (selectedTab) {
            MainActivity.TAB_TPMS -> renderTpmsTab()
            MainActivity.TAB_MEDIA -> renderMediaTab()
            MainActivity.TAB_NAVIGATION -> renderNavigationTab()
            MainActivity.TAB_ADAS -> renderAdasSettingsTab(tabContent!!)
            MainActivity.TAB_CANBUS -> renderCanbusTab()
            MainActivity.TAB_SETTINGS -> renderSettingsTab()
            else -> renderTpmsTab()
        }
        restoreRctaDiagnosticsOverlay()
    }

    private fun restoreRctaDiagnosticsOverlay() {
        if (!rctaDiagnosticsActive) return
        showRctaDemoAlert(true, true, true)
    }

    private fun rememberScrollPosition() {
        if (activeScrollView == null) return
        if (settingsMode) settingsScrollY = activeScrollView!!.getScrollY()
        else mainScrollY = activeScrollView!!.getScrollY()
    }

    private fun restoreScrollPosition(scroll: ScrollView, settings: Boolean) {
        activeScrollView = scroll
        val target: Int = if (settings) settingsScrollY else mainScrollY
        if (target <= 0) return
        scroll.scrollTo(0, target)
        scroll
            .getViewTreeObserver()
            .addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (scroll.getViewTreeObserver().isAlive()) {
                            scroll.getViewTreeObserver().removeOnPreDrawListener(this)
                        }
                        restoreScrollY(scroll, target)
                        return true
                    }
                }
            )
        scroll.post({ restoreScrollY(scroll, target) })
        scroll.postDelayed({ restoreScrollY(scroll, target) }, 80L)
    }

    private fun restoreScrollY(scroll: ScrollView, target: Int) {
        var max: Int = 0
        if (scroll.getChildCount() > 0) {
            max = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight())
        }
        scroll.scrollTo(0, Math.min(target, max))
    }

    private fun clearTabViews() {
        status = null
        tpmsStatus = null
        tpmsDashboard = null
        mediaTabToggle = null
        callEnabledToggle = null
        mediaDebugToggle = null
        mediaDebugStatus = null
        navigationStatus = null
        navigationDebugStatus = null
        navigationDebugToggle = null
        navigationOverlayToggle = null
        microManeuverToggle = null
        straightUntilMainToggle = null
        finishCompassAutoToggle = null
        navTbtToggle = null
        overspeedToggle = null
        tpmsAlertsToggle = null
        tpmsSoundToggle = null
        autoStartToggle = null
        diagnosticsStatus = null
        adasStatus = null
        adasDiagnosticStatus = null
        adasDiagnosticButton = null
        canbusDebugStatus = null
        canbusDebugToggle = null
        ampEnabledToggle = null
        ampVisualizer = null
        ampSummary = null
        updatesStatus = null
        updatesPanelView = null
        firmwareStatus = null
        firmwareActionButton = null
        firmwareProgressFill = null
        firmwareActionText = null
        firmwareActionHintText = null
        log = null
        permissionSummary = null
        sasRatioStatus = null
        sasRatioInput = null
        sasRatioPreview = null
        MainActivity.clearChoiceViews(navSourceModeViews, navSourceModeChecks)
        MainActivity.clearChoiceViews(navTextModeViews, navTextModeChecks)
        MainActivity.clearChoiceViews(navRouteModeViews, navRouteModeChecks)
        rctaPreview = null
        ampInputs.clear()
        tpmsInputs.clear()
        tabButtons = null
    }

    private fun updateTabBar() {
        if (tabButtons == null) return
        for (i in tabButtons!!.indices) {
            val tab: TextView? = tabButtons!!.get(i)
            if (tab == null) continue
            val selected: Boolean = i == selectedTab
            tab.setTextColor(if (selected) Color.WHITE else MainActivity.COLOR_MUTED)
            tab.setBackground(
                if (selected) glassButton(MainActivity.COLOR_ACCENT)
                else round(Color.TRANSPARENT, dp(7), Color.TRANSPARENT, 0)
            )
        }
    }

    private fun renderTpmsTab() {
        val state = StateStore.tpms()
        rememberTpmsRenderState(state)
        tabContent!!.addView(
            tpmsKia130Main(state),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun rememberTpmsRenderState(state: TpmsState?) {
        val safe: TpmsState = if (state == null) TpmsState.empty() else state
        renderedTpmsAt = safe.updatedAt
        renderedTpmsAlerts = AppSettings.tpmsAlertsEnabled(this)
        renderedTpmsWarningKey = TpmsAlertController.warningKey(this, safe)
    }

    private fun shouldRerenderTpms(): Boolean {
        if (settingsMode || selectedTab != MainActivity.TAB_TPMS || tabContent == null) return false
        val state = StateStore.tpms()
        if (state.updatedAt != renderedTpmsAt) return true
        if (AppSettings.tpmsAlertsEnabled(this) != renderedTpmsAlerts) return true
        return !TpmsAlertController.warningKey(this, state).equals(renderedTpmsWarningKey)
    }

    private fun tpmsKia130Main(state: TpmsState?): View {
        val stage: FrameLayout = FrameLayout(this)
        stage.setClipChildren(false)
        stage.setClipToPadding(false)
        stage.setBackgroundColor(Color.rgb(13, 15, 19))

        val dashboard: TpmsDashboardView = TpmsDashboardView(this)
        tpmsDashboard = dashboard
        dashboard.setState(state)
        dashboard.setWidgetMode(tpmsWidgetMode())
        dashboard.setMotionSpeedKmh(navigationSpeedKmh())
        dashboard.setNavigationState(
            StateStore.navigation(),
            NavigationFeature.get(this).dashboardRoadSpeedLimit(),
        )
        dashboard.setVehicleState(StateStore.vehicle())
        stage.addView(
            dashboard,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val gearSize: Int = if (isCompact()) dp(48) else dp(56)
        val gearLp: FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(gearSize, gearSize, Gravity.RIGHT or Gravity.TOP)
        gearLp.setMargins(
            0,
            if (isCompact()) dp(8) else dp(16),
            if (isCompact()) dp(8) else dp(18),
            0,
        )
        stage.addView(tpmsKia130GearButton(), gearLp)
        return stage
    }

    private fun tpmsKia130GearButton(): View {
        val settings: ImageView = ImageView(this)
        settings.setImageResource(R.drawable.ic_tpms_settings)
        settings.setColorFilter(Color.WHITE)
        settings.setScaleType(ImageView.ScaleType.CENTER)
        settings.setPadding(dp(11), dp(11), dp(11), dp(11))
        settings.setClickable(true)
        settings.setFocusable(true)
        settings.setContentDescription("Открыть настройки")
        settings.setBackground(tpmsGearBackground())
        settings.setOnClickListener({ v ->
            settingsMode = true
            settingsTab = MainActivity.SETTINGS_TPMS
            renderTab()
            refresh()
        })
        return settings
    }

    private fun tpmsGearBackground(): GradientDrawable {
        return gradient(
            Color.argb(132, 18, 23, 30),
            Color.argb(98, 48, 60, 74),
            Color.argb(132, 244, 241, 234),
            dp(1),
            dp(40),
        )
    }

    private fun tpmsLegacyKiaMain(state: TpmsState?): View {
        val stage: FrameLayout = FrameLayout(this)
        stage.setClipChildren(false)
        stage.setClipToPadding(false)
        stage.setBackgroundColor(Color.rgb(7, 10, 14))
        val stageLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        stage.setLayoutParams(stageLp)

        val title: LinearLayout = LinearLayout(this)
        title.setOrientation(LinearLayout.VERTICAL)
        title.setPadding(dp(18), dp(12), dp(18), dp(12))
        title.setBackground(legacyPanelBackground(MainActivity.COLOR_ACCENT))
        val brand: TextView = text("KIA", if (tpmsLegacyCompact()) 24 else 30, Color.WHITE)
        brand.setTypeface(Typeface.DEFAULT_BOLD)
        val sub: TextView =
            text("TPMS", if (tpmsLegacyCompact()) 12 else 13, MainActivity.COLOR_MUTED)
        sub.setTypeface(Typeface.DEFAULT_BOLD)
        title.addView(brand)
        title.addView(sub)
        val titleLp: FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(
                if (tpmsLegacyCompact()) dp(126) else dp(154),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT or Gravity.TOP,
            )
        titleLp.setMargins(dp(34), dp(24), 0, 0)
        stage.addView(title, titleLp)

        val gearLp: FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(
                if (tpmsLegacyCompact()) dp(56) else dp(64),
                if (tpmsLegacyCompact()) dp(50) else dp(58),
                Gravity.RIGHT or Gravity.TOP,
            )
        gearLp.setMargins(0, dp(24), dp(34), 0)
        stage.addView(tpmsLegacyGearButton(), gearLp)

        val content: LinearLayout = LinearLayout(this)
        content.setClipChildren(false)
        content.setClipToPadding(false)
        content.setGravity(Gravity.CENTER)
        content.setOrientation(
            if (tpmsLegacyCompact()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        )
        val padH: Int = if (tpmsLegacyCompact()) dp(18) else dp(62)
        content.setPadding(
            padH,
            if (tpmsLegacyCompact()) dp(94) else dp(124),
            padH,
            if (tpmsLegacyCompact()) dp(26) else dp(54),
        )
        stage.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        if (tpmsLegacyCompact()) {
            content.addView(
                tpmsLegacyCarPanel(state),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.82f),
            )
            content.addView(
                tpmsLegacyWheelGrid(state),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        } else {
            content.addView(
                tpmsLegacyWheelColumn(state, TpmsState.WHEEL_FL, TpmsState.WHEEL_RL),
                tpmsLegacyColumnLayout(true),
            )
            content.addView(
                tpmsLegacyCarPanel(state),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.82f),
            )
            content.addView(
                tpmsLegacyWheelColumn(state, TpmsState.WHEEL_FR, TpmsState.WHEEL_RR),
                tpmsLegacyColumnLayout(false),
            )
        }
        return stage
    }

    private fun tpmsLegacyColumnLayout(left: Boolean): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        lp.setMargins(if (left) 0 else dp(34), 0, if (left) dp(34) else 0, 0)
        return lp
    }

    private fun tpmsLegacyWheelColumn(
        state: TpmsState?,
        topWheel: Int,
        bottomWheel: Int,
    ): LinearLayout {
        val column: LinearLayout = LinearLayout(this)
        column.setOrientation(LinearLayout.VERTICAL)
        column.setGravity(Gravity.CENTER)
        column.setClipChildren(false)
        val topLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        topLp.setMargins(0, 0, 0, dp(14))
        val bottomLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        bottomLp.setMargins(0, dp(14), 0, 0)
        column.addView(tpmsLegacyWheelCard(state, topWheel), topLp)
        column.addView(tpmsLegacyWheelCard(state, bottomWheel), bottomLp)
        return column
    }

    private fun tpmsLegacyWheelGrid(state: TpmsState?): LinearLayout {
        val grid: LinearLayout = LinearLayout(this)
        grid.setOrientation(LinearLayout.VERTICAL)
        for (rowIndex in 0..1) {
            val line: LinearLayout = row()
            val first: Int = rowIndex * 2
            line.addView(
                tpmsLegacyWheelCard(state, first),
                tpmsLegacyGridLayout(true, rowIndex == 0),
            )
            line.addView(
                tpmsLegacyWheelCard(state, first + 1),
                tpmsLegacyGridLayout(false, rowIndex == 0),
            )
            grid.addView(
                line,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        return grid
    }

    private fun tpmsLegacyGridLayout(left: Boolean, top: Boolean): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        lp.setMargins(
            if (left) 0 else dp(6),
            if (top) 0 else dp(6),
            if (left) dp(6) else 0,
            if (top) dp(6) else 0,
        )
        return lp
    }

    private fun tpmsLegacyWheelCard(state: TpmsState?, wheel: Int): View {
        val known: Boolean =
            state != null &&
                state.known != null &&
                wheel >= 0 &&
                wheel < state.known.size &&
                state.known.get(wheel)
        val warning: Int = TpmsAlertController.warningState(this, state, wheel)
        val bad: Boolean = warning != TpmsAlertController.WARNING_NONE
        val accent: Int = if (bad) warningColor(warning) else MainActivity.COLOR_ACCENT

        val card: LinearLayout = LinearLayout(this)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setGravity(Gravity.CENTER_VERTICAL)
        card.setClipChildren(false)
        card.setPadding(dp(22), dp(18), dp(22), dp(18))
        card.setBackground(legacyPanelBackground(accent))

        val header: LinearLayout = row()
        header.addView(tpmsLegacyBadge(MainActivity.wheelName(wheel), accent))
        val label: TextView =
            text(
                MainActivity.wheelFullName(wheel),
                if (tpmsLegacyCompact()) 30 else 20,
                MainActivity.COLOR_TEXT,
            )
        label.setTypeface(Typeface.DEFAULT_BOLD)
        label.setSingleLine(true)
        val labelLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        labelLp.setMargins(dp(12), 0, dp(10), 0)
        header.addView(label, labelLp)
        header.addView(tpmsLegacyStatusPill(known, warning))
        card.addView(header)

        val value: LinearLayout = row()
        value.setGravity(Gravity.LEFT or Gravity.BOTTOM)
        val pressure: TextView =
            text(
                if (known) MainActivity.pressureBarValue(state.pressureKpa.get(wheel)) else "--.--",
                if (tpmsLegacyCompact()) 62 else 54,
                Color.WHITE,
            )
        pressure.setTypeface(Typeface.DEFAULT_BOLD)
        pressure.setIncludeFontPadding(false)
        value.addView(pressure)
        val unit: TextView =
            text("bar", if (tpmsLegacyCompact()) 26 else 18, MainActivity.COLOR_MUTED)
        unit.setTypeface(Typeface.DEFAULT_BOLD)
        val unitLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        unitLp.setMargins(dp(10), 0, 0, dp(7))
        value.addView(unit, unitLp)
        val valueLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        valueLp.setMargins(0, if (tpmsLegacyCompact()) dp(14) else dp(22), 0, 0)
        card.addView(value, valueLp)

        val metaText: String = if (known) state.temperatureC.get(wheel).toString() + "C" else "--C"
        val detail: String =
            if (bad) TpmsAlertController.warningText(warning)
            else (if (known) "норма" else "нет данных")
        val meta: TextView =
            text(
                metaText + "  /  " + detail,
                if (tpmsLegacyCompact()) 22 else 15,
                if (bad) accent else MainActivity.COLOR_MUTED,
            )
        meta.setTypeface(Typeface.DEFAULT_BOLD)
        meta.setSingleLine(true)
        val metaLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        metaLp.setMargins(0, dp(7), 0, 0)
        card.addView(meta, metaLp)
        return card
    }

    private fun tpmsLegacyBadge(value: String?, color: Int): TextView {
        val badge: TextView = text(value, if (tpmsLegacyCompact()) 28 else 18, Color.WHITE)
        badge.setGravity(Gravity.CENTER)
        badge.setTypeface(Typeface.DEFAULT_BOLD)
        badge.setMinWidth(if (tpmsLegacyCompact()) dp(68) else dp(48))
        badge.setMinHeight(if (tpmsLegacyCompact()) dp(48) else dp(40))
        badge.setPadding(dp(4), dp(2), dp(4), dp(2))
        badge.setBackground(
            gradient(
                softColor(color, 150),
                softColor(MainActivity.COLOR_PANEL_SOFT, 175),
                softColor(Color.WHITE, 36),
                dp(1),
                dp(8),
            )
        )
        return badge
    }

    private fun tpmsLegacyStatusPill(known: Boolean, warning: Int): TextView {
        val value: String =
            if (!known) "нет"
            else if (warning == TpmsAlertController.WARNING_NONE) "ok"
            else TpmsAlertController.warningText(warning)
        val color: Int = if (!known) MainActivity.COLOR_MUTED else warningColor(warning)
        val pill: TextView = text(value, if (tpmsLegacyCompact()) 20 else 13, Color.WHITE)
        pill.setGravity(Gravity.CENTER)
        pill.setTypeface(Typeface.DEFAULT_BOLD)
        pill.setSingleLine(true)
        pill.setPadding(dp(10), dp(5), dp(10), dp(5))
        pill.setBackground(
            round(
                softColor(color, if (warning == TpmsAlertController.WARNING_NONE) 88 else 118),
                dp(8),
                softColor(Color.WHITE, 30),
                dp(1),
            )
        )
        return pill
    }

    private fun tpmsLegacyCarPanel(state: TpmsState?): LinearLayout {
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setGravity(Gravity.CENTER)
        panel.setClipChildren(false)
        panel.setClipToPadding(false)

        val carWrap: FrameLayout = FrameLayout(this)
        carWrap.setClipChildren(false)
        carWrap.setClipToPadding(false)
        val car: TpmsDashboardView = TpmsDashboardView(this)
        car.setState(state)
        val carLp: FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        carWrap.addView(car, carLp)
        panel.addView(
            carWrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val footer: LinearLayout = row()
        footer.setGravity(Gravity.CENTER)
        footer.setPadding(0, dp(12), 0, 0)
        footer.addView(
            tpmsLegacyFooterChip(
                tpmsCenterText(state),
                if (TpmsAlertController.hasWarnings(this, state)) MainActivity.COLOR_DANGER
                else MainActivity.COLOR_ACCENT,
            )
        )
        footer.addView(
            tpmsLegacyFooterChip(
                "RX " + timeText(if (state == null) 0L else state.updatedAt),
                MainActivity.COLOR_ACCENT_BLUE,
            )
        )
        panel.addView(
            footer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return panel
    }

    private fun tpmsLegacyFooterChip(value: String?, color: Int): TextView {
        val chip: TextView = text(value, if (tpmsLegacyCompact()) 11 else 13, Color.WHITE)
        chip.setGravity(Gravity.CENTER)
        chip.setSingleLine(true)
        chip.setTypeface(Typeface.DEFAULT_BOLD)
        chip.setPadding(dp(12), dp(7), dp(12), dp(7))
        chip.setBackground(round(softColor(color, 104), dp(8), softColor(Color.WHITE, 28), dp(1)))
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(dp(5), 0, dp(5), 0)
        chip.setLayoutParams(lp)
        return chip
    }

    private fun tpmsLegacyGearButton(): TextView {
        val settings: TextView = text("⚙", if (tpmsLegacyCompact()) 25 else 29, Color.WHITE)
        settings.setGravity(Gravity.CENTER)
        settings.setTypeface(Typeface.DEFAULT_BOLD)
        settings.setClickable(true)
        settings.setFocusable(true)
        settings.setBackground(legacyPanelBackground(MainActivity.COLOR_ACCENT_BLUE))
        settings.setOnClickListener({ v ->
            settingsMode = true
            settingsTab = MainActivity.SETTINGS_TPMS
            renderTab()
            refresh()
        })
        return settings
    }

    private fun legacyPanelBackground(tint: Int): GradientDrawable {
        return gradient(
            softColor(Color.rgb(13, 22, 30), 238),
            softColor(Color.rgb(20, 27, 36), 238),
            softColor(tint, 90),
            dp(1),
            dp(8),
        )
    }

    private fun tpmsLegacyCompact(): Boolean {
        return screenWidthDp() < 900
    }

    private fun tpmsLiveMain(state: TpmsState?): View {
        val stage: FrameLayout = FrameLayout(this)
        stage.setClipChildren(false)
        stage.setClipToPadding(false)
        stage.setBackground(tpmsMainBackground())

        val content: LinearLayout = LinearLayout(this)
        content.setClipChildren(false)
        content.setClipToPadding(false)
        content.setGravity(Gravity.CENTER)
        content.setOrientation(
            if (tpmsNarrowLayout()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        )
        val padH: Int = if (tpmsNarrowLayout()) dp(20) else dp(54)
        content.setPadding(padH, dp(88), padH, dp(42))
        stage.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        if (tpmsNarrowLayout()) {
            content.addView(
                tpmsLiveCarPanel(state),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.92f),
            )
            content.addView(
                tpmsLiveWheelGrid(state),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        } else {
            content.addView(
                tpmsLiveWheelColumn(state, TpmsState.WHEEL_FL, TpmsState.WHEEL_RL),
                tpmsLiveColumnLayout(true),
            )
            content.addView(
                tpmsLiveCarPanel(state),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.96f),
            )
            content.addView(
                tpmsLiveWheelColumn(state, TpmsState.WHEEL_FR, TpmsState.WHEEL_RR),
                tpmsLiveColumnLayout(false),
            )
        }

        val gearLp: FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(
                if (tpmsNarrowLayout()) dp(54) else dp(66),
                if (tpmsNarrowLayout()) dp(50) else dp(60),
                Gravity.RIGHT or Gravity.TOP,
            )
        gearLp.setMargins(0, dp(24), dp(34), 0)
        stage.addView(tpmsLiveGearButton(), gearLp)
        return stage
    }

    private fun tpmsMainBackground(): GradientDrawable {
        val drawable: GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(3, 7, 12), Color.rgb(7, 18, 26), Color.rgb(13, 23, 28)),
            )
        drawable.setCornerRadius(0f)
        return drawable
    }

    private fun tpmsLiveColumnLayout(left: Boolean): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.78f)
        lp.setMargins(if (left) 0 else dp(28), 0, if (left) dp(28) else 0, 0)
        return lp
    }

    private fun tpmsLiveWheelColumn(
        state: TpmsState?,
        topWheel: Int,
        bottomWheel: Int,
    ): LinearLayout {
        val column: LinearLayout = LinearLayout(this)
        column.setOrientation(LinearLayout.VERTICAL)
        column.setGravity(Gravity.CENTER)
        column.setClipChildren(false)
        val topLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        topLp.setMargins(0, 0, 0, dp(14))
        val bottomLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        bottomLp.setMargins(0, dp(14), 0, 0)
        column.addView(tpmsLiveWheelCard(state, topWheel), topLp)
        column.addView(tpmsLiveWheelCard(state, bottomWheel), bottomLp)
        return column
    }

    private fun tpmsLiveWheelGrid(state: TpmsState?): LinearLayout {
        val grid: LinearLayout = LinearLayout(this)
        grid.setOrientation(LinearLayout.VERTICAL)
        grid.setClipChildren(false)
        for (rowIndex in 0..1) {
            val row: LinearLayout = row()
            row.setClipChildren(false)
            val first: Int = rowIndex * 2
            row.addView(
                tpmsLiveWheelCard(state, first),
                tpmsLiveGridCardLayout(true, rowIndex == 0),
            )
            row.addView(
                tpmsLiveWheelCard(state, first + 1),
                tpmsLiveGridCardLayout(false, rowIndex == 0),
            )
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return grid
    }

    private fun tpmsLiveGridCardLayout(left: Boolean, top: Boolean): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        lp.setMargins(
            if (left) 0 else dp(7),
            if (top) 0 else dp(7),
            if (left) dp(7) else 0,
            if (top) dp(7) else 0,
        )
        return lp
    }

    private fun tpmsLiveWheelCard(state: TpmsState?, wheel: Int): View {
        val known: Boolean =
            state != null &&
                state.known != null &&
                wheel >= 0 &&
                wheel < state.known.size &&
                state.known.get(wheel)
        val warning: Int = TpmsAlertController.warningState(this, state, wheel)
        val color: Int = warningColor(warning)

        val card: LinearLayout = LinearLayout(this)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setGravity(Gravity.CENTER_VERTICAL)
        card.setClipChildren(false)
        card.setPadding(dp(24), dp(20), dp(24), dp(18))
        card.setBackground(tpmsGlassSurface(color, warning != TpmsAlertController.WARNING_NONE))

        val header: LinearLayout = row()
        header.addView(tpmsWheelDot(wheel, color))
        val label: TextView =
            text(MainActivity.wheelFullName(wheel), tpmsSmallText(), MainActivity.COLOR_TEXT)
        label.setTypeface(Typeface.DEFAULT_BOLD)
        label.setSingleLine(true)
        val labelLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        labelLp.setMargins(dp(12), 0, dp(8), 0)
        header.addView(label, labelLp)
        header.addView(tpmsStatePill(known, warning))
        card.addView(header)

        val valueRow: LinearLayout = row()
        valueRow.setGravity(Gravity.LEFT or Gravity.BOTTOM)
        val pressure: TextView =
            text(
                if (known) MainActivity.pressureBarValue(state.pressureKpa.get(wheel)) else "--.--",
                tpmsPressureText(),
                Color.WHITE,
            )
        pressure.setTypeface(Typeface.DEFAULT_BOLD)
        pressure.setIncludeFontPadding(false)
        valueRow.addView(pressure)
        val unit: TextView = text("bar", tpmsUnitText(), MainActivity.COLOR_MUTED)
        unit.setTypeface(Typeface.DEFAULT_BOLD)
        val unitLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        unitLp.setMargins(dp(10), 0, 0, dp(7))
        valueRow.addView(unit, unitLp)
        val valueLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        valueLp.setMargins(0, dp(22), 0, 0)
        card.addView(valueRow, valueLp)

        val temp: String = if (known) state.temperatureC.get(wheel).toString() + "C" else "--C"
        val detail: String =
            if (warning == TpmsAlertController.WARNING_NONE) (if (known) "норма" else "нет данных")
            else TpmsAlertController.warningText(warning)
        val meta: TextView =
            text(
                temp + "  /  " + detail,
                tpmsMetaText(),
                if (warning == TpmsAlertController.WARNING_NONE) MainActivity.COLOR_MUTED else color,
            )
        meta.setTypeface(Typeface.DEFAULT_BOLD)
        meta.setSingleLine(true)
        val metaLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        metaLp.setMargins(0, dp(8), 0, 0)
        card.addView(meta, metaLp)
        return card
    }

    private fun tpmsWheelDot(wheel: Int, color: Int): TextView {
        val dot: TextView =
            text(MainActivity.wheelName(wheel), if (tpmsNarrowLayout()) 15 else 18, Color.WHITE)
        dot.setGravity(Gravity.CENTER)
        dot.setTypeface(Typeface.DEFAULT_BOLD)
        dot.setMinWidth(if (tpmsNarrowLayout()) dp(42) else dp(50))
        dot.setMinHeight(if (tpmsNarrowLayout()) dp(34) else dp(40))
        dot.setBackground(
            gradient(
                softColor(color, 155),
                softColor(MainActivity.COLOR_PANEL_SOFT, 160),
                softColor(Color.WHITE, 48),
                dp(1),
                dp(8),
            )
        )
        return dot
    }

    private fun tpmsStatePill(known: Boolean, warning: Int): TextView {
        val value: String? =
            if (!known) "NO DATA"
            else if (warning == TpmsAlertController.WARNING_NONE) "OK"
            else TpmsAlertController.warningText(warning).uppercase(Locale.US)
        val color: Int = if (!known) MainActivity.COLOR_MUTED else warningColor(warning)
        val pill: TextView = text(value, if (tpmsNarrowLayout()) 10 else 12, Color.WHITE)
        pill.setGravity(Gravity.CENTER)
        pill.setTypeface(Typeface.DEFAULT_BOLD)
        pill.setSingleLine(true)
        pill.setPadding(dp(10), dp(5), dp(10), dp(5))
        pill.setBackground(
            gradient(
                softColor(color, 88),
                softColor(MainActivity.COLOR_PANEL_SOFT, 128),
                softColor(Color.WHITE, 34),
                dp(1),
                dp(8),
            )
        )
        return pill
    }

    private fun tpmsLiveCarPanel(state: TpmsState?): LinearLayout {
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setGravity(Gravity.CENTER)
        panel.setClipChildren(false)
        panel.setClipToPadding(false)
        panel.setPadding(0, 0, 0, 0)

        panel.addView(
            tpmsGlassCar(state),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        panel.addView(
            tpmsLiveFooter(state),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return panel
    }

    private fun tpmsGlassCar(state: TpmsState?): FrameLayout {
        val wrap: FrameLayout = FrameLayout(this)
        wrap.setClipChildren(false)
        wrap.setClipToPadding(false)

        val scale: Float = tpmsCarScale()
        val car: FrameLayout = FrameLayout(this)
        car.setClipChildren(false)
        val carLp: FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(
                Math.round(470 * scale),
                Math.round(760 * scale),
                Gravity.CENTER,
            )
        wrap.addView(car, carLp)

        addCarTire(car, state, TpmsState.WHEEL_FL, 0, 20, scale)
        addCarTire(car, state, TpmsState.WHEEL_FR, 350, 20, scale)
        addCarTire(car, state, TpmsState.WHEEL_RL, 0, 520, scale)
        addCarTire(car, state, TpmsState.WHEEL_RR, 350, 520, scale)
        addCarPart(car, 112, 48, 246, 654, MainActivity.COLOR_ACCENT_BLUE, 40, 138, scale)
        addCarPart(car, 136, 18, 198, 130, MainActivity.COLOR_ACCENT, 50, 122, scale)
        addCarPart(car, 98, 140, 274, 148, MainActivity.COLOR_ACCENT_BLUE, 48, 132, scale)
        addCarPart(car, 96, 474, 278, 248, MainActivity.COLOR_ACCENT, 58, 148, scale)
        addCarPart(car, 142, 572, 186, 96, Color.WHITE, 70, 24, scale)

        val mark: TextView = text("KIA", Math.round(26 * scale), Color.WHITE)
        mark.setGravity(Gravity.CENTER)
        mark.setTypeface(Typeface.DEFAULT_BOLD)
        car.addView(mark, carLp(160, 60, 155, 356, scale))

        if (TpmsAlertController.hasWarnings(this, state)) {
            val warn: TextView = text("!", Math.round(42 * scale), Color.WHITE)
            warn.setGravity(Gravity.CENTER)
            warn.setTypeface(Typeface.DEFAULT_BOLD)
            warn.setBackground(
                gradient(
                    softColor(MainActivity.COLOR_DANGER, 210),
                    softColor(MainActivity.COLOR_ROSE, 160),
                    softColor(Color.WHITE, 80),
                    dp(1),
                    Math.round(44 * scale),
                )
            )
            car.addView(warn, carLp(88, 88, 191, 334, scale))
        }
        return wrap
    }

    private fun addCarTire(
        car: FrameLayout,
        state: TpmsState?,
        wheel: Int,
        left: Int,
        top: Int,
        scale: Float,
    ) {
        val warning: Int = TpmsAlertController.warningState(this, state, wheel)
        val color: Int =
            if (warning == TpmsAlertController.WARNING_NONE) Color.rgb(26, 35, 42)
            else warningColor(warning)
        val tire: View = View(this)
        tire.setBackground(
            gradient(
                softColor(color, 230),
                softColor(Color.rgb(4, 8, 12), 245),
                softColor(Color.WHITE, if (warning == TpmsAlertController.WARNING_NONE) 34 else 72),
                dp(1),
                Math.round(28 * scale),
            )
        )
        car.addView(tire, carLp(118, 218, left, top, scale))
    }

    private fun addCarPart(
        car: FrameLayout,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        color: Int,
        radius: Int,
        alpha: Int,
        scale: Float,
    ) {
        val part: View = View(this)
        part.setBackground(
            gradient(
                softColor(color, alpha),
                softColor(MainActivity.COLOR_PANEL_SOFT, 118),
                softColor(Color.WHITE, 38),
                dp(1),
                Math.round(radius * scale),
            )
        )
        car.addView(part, carLp(width, height, left, top, scale))
    }

    private fun carLp(
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        scale: Float,
    ): FrameLayout.LayoutParams {
        val lp: FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(Math.round(width * scale), Math.round(height * scale))
        lp.leftMargin = Math.round(left * scale)
        lp.topMargin = Math.round(top * scale)
        return lp
    }

    private fun tpmsLiveFooter(state: TpmsState?): LinearLayout {
        val footer: LinearLayout = LinearLayout(this)
        footer.setGravity(Gravity.CENTER)
        footer.setOrientation(LinearLayout.HORIZONTAL)
        footer.setPadding(0, dp(16), 0, 0)
        val stateText: String = tpmsCenterText(state)
        footer.addView(
            tpmsFooterChip(
                stateText,
                if (TpmsAlertController.hasWarnings(this, state)) MainActivity.COLOR_DANGER
                else MainActivity.COLOR_ACCENT,
            )
        )
        footer.addView(
            tpmsFooterChip(
                "RX " + timeText(if (state == null) 0L else state.updatedAt),
                MainActivity.COLOR_ACCENT_BLUE,
            )
        )
        footer.addView(tpmsFooterChip("опрос 5 сек", MainActivity.COLOR_PANEL_SOFT))
        return footer
    }

    private fun tpmsFooterChip(value: String?, color: Int): TextView {
        val chip: TextView = text(value, if (tpmsNarrowLayout()) 11 else 13, Color.WHITE)
        chip.setGravity(Gravity.CENTER)
        chip.setTypeface(Typeface.DEFAULT_BOLD)
        chip.setSingleLine(true)
        chip.setPadding(dp(12), dp(7), dp(12), dp(7))
        chip.setBackground(
            gradient(
                softColor(color, 82),
                softColor(MainActivity.COLOR_PANEL_SOFT, 122),
                softColor(Color.WHITE, 30),
                dp(1),
                dp(8),
            )
        )
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(dp(5), 0, dp(5), 0)
        chip.setLayoutParams(lp)
        return chip
    }

    private fun tpmsLiveGearButton(): TextView {
        val settings: TextView = text("⚙", if (tpmsNarrowLayout()) 25 else 31, Color.WHITE)
        settings.setGravity(Gravity.CENTER)
        settings.setTypeface(Typeface.DEFAULT_BOLD)
        settings.setClickable(true)
        settings.setFocusable(true)
        settings.setContentDescription("Открыть настройки")
        settings.setBackground(
            gradient(
                softColor(MainActivity.COLOR_PANEL_SOFT, 170),
                softColor(MainActivity.COLOR_ACCENT_BLUE, 76),
                softColor(Color.WHITE, 44),
                dp(1),
                dp(8),
            )
        )
        settings.setOnClickListener({ v ->
            settingsMode = true
            settingsTab = MainActivity.SETTINGS_TPMS
            renderTab()
            refresh()
        })
        return settings
    }

    private fun tpmsGlassSurface(tint: Int, warning: Boolean): GradientDrawable {
        val main: Int = if (warning) tint else MainActivity.COLOR_ACCENT_BLUE
        return gradient(
            softColor(main, if (warning) 96 else 54),
            softColor(MainActivity.COLOR_PANEL_SOFT, 172),
            softColor(Color.WHITE, if (warning) 76 else 44),
            dp(1),
            dp(8),
        )
    }

    private fun tpmsNarrowLayout(): Boolean {
        return screenWidthDp() < 900
    }

    private fun tpmsCarScale(): Float {
        val width: Int = Math.max(1, getResources().getDisplayMetrics().widthPixels)
        val height: Int = Math.max(1, getResources().getDisplayMetrics().heightPixels)
        return Math.max(0.48f, Math.min(1.12f, Math.min(width / 2000f, height / 1200f)))
    }

    private fun tpmsPressureText(): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp >= 1300) return 58
        if (widthDp >= 1000) return 48
        return 34
    }

    private fun tpmsSmallText(): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp >= 1300) return 21
        if (widthDp >= 1000) return 18
        return 15
    }

    private fun tpmsUnitText(): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp >= 1300) return 21
        if (widthDp >= 1000) return 18
        return 14
    }

    private fun tpmsMetaText(): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp >= 1300) return 17
        if (widthDp >= 1000) return 15
        return 12
    }

    private fun renderSettingsShell() {
        if (rootLayout == null) return
        rootLayout!!.removeAllViews()
        rootLayout!!.setBackgroundColor(MainActivity.COLOR_SETTINGS_BG)
        rootLayout!!.addView(
            settingsTopRow(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, settingsHeaderHeight()),
        )

        val scroll: ScrollView = ScrollView(this)
        scroll.setFillViewport(true)
        scroll.setBackgroundColor(MainActivity.COLOR_SETTINGS_BG)
        scroll.setClipToPadding(false)
        scroll.setPadding(0, 0, 0, dp(18))
        val content: LinearLayout = LinearLayout(this)
        content.setOrientation(LinearLayout.VERTICAL)
        content.setClipToPadding(false)
        content.setPadding(settingsContentPadding(), dp(12), settingsContentPadding(), dp(18))
        renderSettingsContent(content)
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        rootLayout!!.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        tabContent = content
        restoreScrollPosition(scroll, true)
    }

    private fun settingsMenuItem(tab: Int, label: String?): TextView {
        val selected: Boolean = settingsTab == tab
        val item: TextView =
            text(
                settingsMenuLabel(tab, label),
                if (isCompact()) 14 else 17,
                if (selected) Color.WHITE else MainActivity.COLOR_MUTED,
            )
        item.setGravity(Gravity.CENTER)
        item.setTypeface(Typeface.DEFAULT_BOLD)
        item.setSingleLine(true)
        item.setPadding(dp(10), 0, dp(10), 0)
        item.setClickable(true)
        item.setFocusable(true)
        item.setSelected(selected)
        item.setContentDescription("Раздел настроек: " + label)
        item.setBackground(
            if (selected) settingsButtonBackground(true)
            else round(Color.TRANSPARENT, dp(7), Color.TRANSPARENT, 0)
        )
        item.setOnClickListener({ v ->
            if (settingsTab != tab) {
                settingsScrollY = 0
                activeScrollView = null
            }
            settingsTab = tab
            renderTab()
            refresh()
        })
        return item
    }

    private fun settingsMenuLabel(tab: Int, label: String?): String? {
        return label
    }

    private fun settingsTopRow(): View {
        val outer: LinearLayout = row()
        outer.setGravity(Gravity.CENTER_VERTICAL)
        outer.setPadding(dp(10), dp(8), dp(10), dp(8))
        outer.setBackgroundColor(MainActivity.COLOR_SETTINGS_BG)
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, 0, 0, 0)
        outer.setLayoutParams(lp)

        val scroller: HorizontalScrollView = HorizontalScrollView(this)
        scroller.setHorizontalScrollBarEnabled(false)
        scroller.setFillViewport(false)
        val tabs: LinearLayout = row()
        tabs.setGravity(Gravity.CENTER_VERTICAL)
        tabs.addView(
            settingsMenuItem(MainActivity.SETTINGS_TPMS, "TPMS"),
            settingsHeaderItemLayout(true),
        )
        if (AppSettings.mediaTabVisible(this)) {
            tabs.addView(
                settingsMenuItem(MainActivity.SETTINGS_MEDIA, "Медиа"),
                settingsHeaderItemLayout(false),
            )
        }
        tabs.addView(
            settingsMenuItem(MainActivity.SETTINGS_NAVIGATION, "Навигация"),
            settingsHeaderItemLayout(false),
        )
        tabs.addView(
            settingsMenuItem(MainActivity.SETTINGS_ADAS, "ADAS"),
            settingsHeaderItemLayout(false),
        )
        tabs.addView(
            settingsMenuItem(MainActivity.SETTINGS_CANBUS, "Canbus"),
            settingsHeaderItemLayout(false),
        )
        tabs.addView(
            settingsMenuItem(MainActivity.SETTINGS_RCTA, "RCTA"),
            settingsHeaderItemLayout(false),
        )
        tabs.addView(
            settingsMenuItem(MainActivity.SETTINGS_GENERAL, "Общее"),
            settingsHeaderItemLayout(false),
        )
        scroller.addView(
            tabs,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        outer.addView(
            scroller,
            LinearLayout.LayoutParams(0, if (isCompact()) dp(46) else dp(52), 1f),
        )
        scroller.post({
            for (i in 0..<tabs.getChildCount()) {
                val item: View? = tabs.getChildAt(i)
                if (item!!.isSelected()) {
                    scroller.scrollTo(Math.max(0, item.getRight() - scroller.getWidth()), 0)
                    break
                }
            }
        })

        val search: TextView = text("⌕", if (isCompact()) 25 else 28, Color.WHITE)
        search.setGravity(Gravity.CENTER)
        search.setTypeface(Typeface.DEFAULT_BOLD)
        search.setClickable(true)
        search.setFocusable(true)
        search.setContentDescription("Поиск настроек")
        search.setBackground(settingsButtonBackground(false))
        search.setOnClickListener({ v -> showSettingsSearch() })
        val searchLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                if (isCompact()) dp(48) else dp(54),
                if (isCompact()) dp(46) else dp(52),
            )
        searchLp.setMargins(dp(5), 0, dp(5), 0)
        outer.addView(search, searchLp)

        val close: TextView = text("×", if (isCompact()) 30 else 34, Color.WHITE)
        close.setGravity(Gravity.CENTER)
        close.setTypeface(Typeface.DEFAULT_BOLD)
        close.setClickable(true)
        close.setFocusable(true)
        close.setContentDescription("Закрыть настройки")
        close.setBackground(settingsButtonBackground(false))
        close.setOnClickListener({ v -> closeSettings() })
        outer.addView(
            close,
            LinearLayout.LayoutParams(
                if (isCompact()) dp(52) else dp(60),
                if (isCompact()) dp(46) else dp(52),
            ),
        )
        return outer
    }

    private fun settingsHeaderItemLayout(first: Boolean): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                if (isCompact()) dp(94) else dp(122),
                if (isCompact()) dp(46) else dp(52),
            )
        lp.setMargins(if (first) 0 else dp(3), 0, dp(3), 0)
        return lp
    }

    private fun settingsHeaderHeight(): Int {
        return if (isCompact()) dp(62) else dp(68)
    }

    private fun settingsContentPadding(): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp >= 1200) return dp(22)
        if (widthDp < 560) return dp(10)
        return dp(16)
    }

    private fun closeSettings() {
        settingsMode = false
        selectedTab = MainActivity.TAB_TPMS
        renderTab()
        refresh()
    }

    private fun showSettingsSearch() {
        val input: EditText = EditText(this)
        input.setSingleLine(true)
        input.setHint("Например: Yandex, давление, радио, обновление")
        input.setInputType(InputType.TYPE_CLASS_TEXT)
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH)
        val pad: Int = dp(18)
        input.setPadding(pad, dp(10), pad, dp(10))
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Найти настройку")
                .setView(input)
                .setPositiveButton("Открыть", null)
                .setNegativeButton("Отмена", null)
                .create()
        dialog.setOnShowListener({ ignored ->
            val open: View? = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            open!!.setOnClickListener({ v ->
                val query: String? = input.getText().toString()
                val target: Int = settingsTabForQuery(query)
                if (target < 0) {
                    input.setError("Не найдено. Попробуйте TPMS, медиа, Yandex, ADAS, CAN или RCTA")
                    return@setOnClickListener
                }
                settingsTab = target
                settingsScrollY = 0
                dialog.dismiss()
                renderTab()
                refresh()
            })
            input.setOnEditorActionListener({ view, actionId, event ->
                if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
                open.performClick()
                true
            })
            input.requestFocus()
        })
        dialog.show()
    }

    private fun settingsTabForQuery(query: String?): Int {
        val value: String? = if (query == null) "" else query.trim { it <= ' ' }.lowercase(Locale.ROOT)
        if (value!!.isEmpty()) return settingsTab
        if (MainActivity.containsAny(value, "adas", "adac", "адас", "ldw", "полос", "разметк")) {
            return MainActivity.SETTINGS_ADAS
        }
        if (MainActivity.containsAny(value, "tpms", "шин", "давлен", "колес", "температур")) {
            return MainActivity.SETTINGS_TPMS
        }
        if (
            MainActivity.containsAny(
                value,
                "медиа",
                "music",
                "музык",
                "радио",
                "трек",
                "артист",
                "uart",
                "teyes",
                "android",
                "задерж",
                "пауза",
                "синхрон",
            )
        ) {
            return MainActivity.SETTINGS_MEDIA
        }
        if (
            MainActivity.containsAny(
                value,
                "нави",
                "yandex",
                "яндекс",
                "2gis",
                "манев",
                "маршрут",
                "скорост",
                "съезд",
                "микро",
            )
        ) {
            return MainActivity.SETTINGS_NAVIGATION
        }
        if (
            MainActivity.containsAny(
                value,
                "can",
                "адаптер",
                "двигател",
                "наружн",
                "sas",
                "усилител",
                "amp",
            )
        ) {
            return MainActivity.SETTINGS_CANBUS
        }
        if (MainActivity.containsAny(value, "rcta", "слеп", "задн", "стрелк")) {
            return MainActivity.SETTINGS_RCTA
        }
        if (
            MainActivity.containsAny(
                value,
                "люк",
                "sunroof",
                "обнов",
                "разреш",
                "прошив",
                "диагност",
                "экспорт",
                "импорт",
                "backup",
                "резерв",
                "эксперт",
                "автозапуск",
                "версия",
            )
        ) {
            return MainActivity.SETTINGS_GENERAL
        }
        return -1
    }

    private fun renderSettingsContent(root: LinearLayout) {
        when (settingsTab) {
            MainActivity.SETTINGS_MEDIA -> renderMediaTab(root)
            MainActivity.SETTINGS_NAVIGATION -> renderNavigationTab(root)
            MainActivity.SETTINGS_ADAS -> renderAdasSettingsTab(root)
            MainActivity.SETTINGS_CANBUS -> renderCanbusTab(root)
            MainActivity.SETTINGS_RCTA -> renderRctaSettingsTab(root)
            MainActivity.SETTINGS_GENERAL -> renderGeneralSettingsTab(root)
            MainActivity.SETTINGS_TPMS -> renderTpmsSettingsTab(root)
            else -> renderTpmsSettingsTab(root)
        }
    }

    private fun settingsTitle(tab: Int): String {
        when (tab) {
            MainActivity.SETTINGS_MEDIA -> return "Медиа"
            MainActivity.SETTINGS_NAVIGATION -> return "Навигация"
            MainActivity.SETTINGS_ADAS -> return "ADAS"
            MainActivity.SETTINGS_CANBUS -> return "Canbus"
            MainActivity.SETTINGS_RCTA -> return "RCTA"
            MainActivity.SETTINGS_GENERAL -> return "Общее"
            MainActivity.SETTINGS_TPMS -> return "TPMS"
            else -> return "TPMS"
        }
    }

    private fun navigationSpeedKmh(): Float {
        val raw = StateStore.navigation().currentSpeed
        if (raw == null) return 0f
        val number: StringBuilder = StringBuilder()
        var decimal: Boolean = false
        for (i in 0..<raw.length) {
            val ch: Char = raw[i]
            if (ch >= '0' && ch <= '9') {
                number.append(ch)
            } else if ((ch == '.' || ch == ',') && !decimal && number.length > 0) {
                number.append('.')
                decimal = true
            } else if (number.length > 0) {
                break
            }
        }
        if (number.length == 0) return 0f
        try {
            return Math.max(0f, Math.min(240f, java.lang.Float.parseFloat(number.toString())))
        } catch (ignored: NumberFormatException) {
            return 0f
        }
    }

    private fun tpmsMainStage(state: TpmsState?): LinearLayout {
        val stage: LinearLayout = LinearLayout(this)
        stage.setOrientation(if (isCompact()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL)
        stage.setGravity(Gravity.CENTER)
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(2), 0, dp(8))
        stage.setLayoutParams(lp)

        if (isCompact()) {
            stage.addView(
                tpmsCarPanel(state),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            stage.addView(
                tpmsWheelPanel(state),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            return stage
        }

        val sideLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(dp(255), dp(430))
        val centerLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(430), 1f)
        centerLp.setMargins(dp(10), 0, dp(10), 0)
        stage.addView(tpmsWheelColumn(state, TpmsState.WHEEL_FL, TpmsState.WHEEL_RL), sideLp)
        stage.addView(tpmsCarPanel(state), centerLp)
        stage.addView(tpmsWheelColumn(state, TpmsState.WHEEL_FR, TpmsState.WHEEL_RR), sideLp)
        return stage
    }

    private fun tpmsWheelColumn(state: TpmsState?, topWheel: Int, bottomWheel: Int): LinearLayout {
        val column: LinearLayout = LinearLayout(this)
        column.setOrientation(LinearLayout.VERTICAL)
        column.setGravity(Gravity.CENTER)
        val topLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        topLp.setMargins(0, 0, 0, dp(6))
        val bottomLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        bottomLp.setMargins(0, dp(6), 0, 0)
        column.addView(tpmsMainWheelCard(state, topWheel), topLp)
        column.addView(tpmsMainWheelCard(state, bottomWheel), bottomLp)
        return column
    }

    private fun tpmsMainWheelCard(state: TpmsState?, wheel: Int): View {
        val known: Boolean =
            state != null &&
                state.known != null &&
                wheel < state.known.size &&
                state.known.get(wheel)
        val warning: Int = TpmsAlertController.warningState(this, state, wheel)
        val color: Int = warningColor(warning)
        val card: LinearLayout = LinearLayout(this)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setGravity(Gravity.CENTER_VERTICAL)
        card.setPadding(dp(18), dp(16), dp(18), dp(16))
        card.setBackground(glassPanel(color))

        val header: LinearLayout = row()
        header.addView(iconBadge(MainActivity.wheelName(wheel), color))
        val label: TextView =
            text(
                MainActivity.wheelFullName(wheel),
                if (isCompact()) 15 else 17,
                MainActivity.COLOR_TEXT,
            )
        label.setTypeface(Typeface.DEFAULT_BOLD)
        val labelLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        labelLp.setMargins(dp(10), 0, 0, 0)
        header.addView(label, labelLp)
        card.addView(header)

        val pressure: TextView =
            text(
                if (known) TpmsAlertController.barText(state.pressureKpa.get(wheel)) else "-- bar",
                if (isCompact()) 27 else 34,
                Color.WHITE,
            )
        pressure.setTypeface(Typeface.DEFAULT_BOLD)
        val pressureLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        pressureLp.setMargins(0, dp(16), 0, 0)
        card.addView(pressure, pressureLp)

        val temp: String = if (known) state.temperatureC.get(wheel).toString() + "C" else "--C"
        val detail: String =
            if (warning == TpmsAlertController.WARNING_NONE) (if (known) "норма" else "нет данных")
            else TpmsAlertController.warningText(warning)
        val meta: TextView =
            text(
                temp + "  ·  " + detail,
                if (isCompact()) 13 else 15,
                if (warning == TpmsAlertController.WARNING_NONE) MainActivity.COLOR_MUTED else color,
            )
        meta.setTypeface(Typeface.DEFAULT_BOLD)
        val metaLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        metaLp.setMargins(0, dp(4), 0, 0)
        card.addView(meta, metaLp)
        return card
    }

    private fun tpmsCarPanel(state: TpmsState?): LinearLayout {
        val color: Int =
            if (TpmsAlertController.hasWarnings(this, state)) MainActivity.COLOR_DANGER
            else MainActivity.COLOR_ACCENT
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setGravity(Gravity.CENTER)
        panel.setPadding(dp(18), dp(18), dp(18), dp(18))
        panel.setBackground(glassPanel(color))

        val brand: TextView = text("KIA", if (isCompact()) 24 else 30, Color.WHITE)
        brand.setTypeface(Typeface.DEFAULT_BOLD)
        brand.setGravity(Gravity.CENTER)
        panel.addView(brand)

        val statusLine: TextView =
            text(tpmsCenterText(state), if (isCompact()) 13 else 15, MainActivity.COLOR_MUTED)
        statusLine.setGravity(Gravity.CENTER)
        val statusLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        statusLp.setMargins(0, dp(2), 0, dp(14))
        panel.addView(statusLine, statusLp)

        panel.addView(
            carShape(color),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val chips: LinearLayout = row()
        chips.setGravity(Gravity.CENTER)
        chips.addView(
            pill(
                if (AppSettings.tpmsAlertsEnabled(this)) "alerts on" else "alerts off",
                if (AppSettings.tpmsAlertsEnabled(this)) MainActivity.COLOR_ACCENT
                else MainActivity.COLOR_MUTED,
            ),
            chipLayout(true),
        )
        val chipsLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        chipsLp.setMargins(0, dp(14), 0, 0)
        panel.addView(chips, chipsLp)
        return panel
    }

    private fun carShape(color: Int): LinearLayout {
        val wrap: LinearLayout = LinearLayout(this)
        wrap.setOrientation(LinearLayout.VERTICAL)
        wrap.setGravity(Gravity.CENTER)
        wrap.addView(carShapePart(dp(86), dp(18), color, 38))
        wrap.addView(carShapePart(dp(138), dp(56), MainActivity.COLOR_ACCENT_BLUE, 34))
        wrap.addView(carShapePart(dp(206), dp(130), color, 42))
        wrap.addView(carShapePart(dp(150), dp(34), color, 28))
        val mark: TextView = text("KIA", 18, Color.WHITE)
        mark.setGravity(Gravity.CENTER)
        mark.setTypeface(Typeface.DEFAULT_BOLD)
        val markLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        markLp.setMargins(0, dp(8), 0, 0)
        wrap.addView(mark, markLp)
        return wrap
    }

    private fun carShapePart(width: Int, height: Int, color: Int, radiusDp: Int): View {
        val view: View = View(this)
        view.setBackground(
            gradient(
                softColor(color, 92),
                softColor(MainActivity.COLOR_PANEL_SOFT, 176),
                softColor(Color.WHITE, 48),
                dp(1),
                dp(radiusDp),
            )
        )
        val lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height)
        lp.setMargins(0, dp(4), 0, dp(4))
        view.setLayoutParams(lp)
        return view
    }

    private fun tpmsCenterText(state: TpmsState?): String {
        if (state == null || !state.hasData()) return "нет данных от датчиков"
        val detail = TpmsAlertController.warningDetails(this, state)
        if (detail.length > 0) {
            return if (TpmsAlertController.hasCriticalWarnings(this, state))
                "критичное предупреждение"
            else "есть предупреждение"
        }
        return "все колеса в норме"
    }

    private fun tpmsDashboardPanel(state: TpmsState?): LinearLayout {
        val color: Int =
            if (TpmsAlertController.hasWarnings(this, state)) MainActivity.COLOR_DANGER
            else MainActivity.COLOR_ACCENT
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(if (isCompact()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL)
        panel.setGravity(Gravity.CENTER_VERTICAL)
        panel.setPadding(dp(18), dp(16), dp(18), dp(16))
        panel.setBackground(glassPanel(color))
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(2), 0, dp(10))
        panel.setLayoutParams(lp)

        val textBox: LinearLayout = LinearLayout(this)
        textBox.setOrientation(LinearLayout.VERTICAL)
        val title: TextView = text("TPMS", if (isCompact()) 26 else 34, Color.WHITE)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        tpmsStatus = text(tpmsStatusText(), if (isCompact()) 13 else 15, MainActivity.COLOR_TEXT)
        textBox.addView(title)
        textBox.addView(tpmsStatus)
        val textLp: LinearLayout.LayoutParams =
            if (isCompact())
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        panel.addView(textBox, textLp)

        val chips: LinearLayout = LinearLayout(this)
        chips.setOrientation(if (isCompact()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL)
        chips.setGravity(if (isCompact()) Gravity.LEFT else Gravity.RIGHT)
        chips.addView(
            pill(
                if (AppSettings.tpmsAlertsEnabled(this)) "alerts on" else "alerts off",
                if (AppSettings.tpmsAlertsEnabled(this)) MainActivity.COLOR_ACCENT
                else MainActivity.COLOR_MUTED,
            ),
            chipLayout(true),
        )
        val chipsLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                if (isCompact()) ViewGroup.LayoutParams.MATCH_PARENT
                else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        chipsLp.setMargins(if (isCompact()) 0 else dp(14), if (isCompact()) dp(12) else 0, 0, 0)
        panel.addView(chips, chipsLp)
        return panel
    }

    private fun tpmsWheelPanel(state: TpmsState?): LinearLayout {
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        val columns: Int = tpmsColumns()
        var i: Int = 0
        while (i < TpmsState.WHEEL_COUNT) {
            val line: LinearLayout = row()
            for (column in 0..<columns) {
                val wheel: Int = i + column
                line.addView(
                    if (wheel < TpmsState.WHEEL_COUNT) tpmsWheelCard(state, wheel) else View(this),
                    actionLayout(column, columns),
                )
            }
            panel.addView(line)
            i += columns
        }
        return panel
    }

    private fun tpmsWheelCard(state: TpmsState?, wheel: Int): View {
        val known: Boolean =
            state != null &&
                state.known != null &&
                wheel < state.known.size &&
                state.known.get(wheel)
        val now: Long = System.currentTimeMillis()
        val fresh: Boolean = known && state.isWheelFresh(wheel)
        val warning: Int = TpmsAlertController.warningState(this, state, wheel)
        val color: Int = if (fresh) warningColor(warning) else MainActivity.COLOR_MUTED
        val card: LinearLayout = LinearLayout(this)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.setMinimumHeight(if (isCompact()) dp(112) else dp(132))
        card.setBackground(glassPanel(color))

        val header: LinearLayout = row()
        val badge: TextView = iconBadge(MainActivity.wheelName(wheel), color)
        header.addView(badge)
        val label: TextView =
            text(
                MainActivity.wheelFullName(wheel),
                if (isCompact()) 15 else 17,
                MainActivity.COLOR_TEXT,
            )
        label.setTypeface(Typeface.DEFAULT_BOLD)
        val labelLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        labelLp.setMargins(dp(10), 0, 0, 0)
        header.addView(label, labelLp)
        card.addView(header)

        val pressure: TextView =
            text(
                if (known) TpmsAlertController.barText(state.pressureKpa.get(wheel)) else "-- bar",
                if (isCompact()) 24 else 30,
                if (fresh) Color.WHITE else MainActivity.COLOR_MUTED,
            )
        pressure.setTypeface(Typeface.DEFAULT_BOLD)
        val pressureLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        pressureLp.setMargins(0, dp(10), 0, 0)
        card.addView(pressure, pressureLp)

        val temp: String = if (known) state.temperatureC.get(wheel).toString() + "C" else "--C"
        val detail: String =
            if (!known) "нет данных"
            else if (!fresh)
                ("данные устарели · " + MainActivity.ageText(now, now - state.wheelAgeMs(wheel)))
            else if (warning == TpmsAlertController.WARNING_NONE) "норма"
            else TpmsAlertController.warningText(warning)
        val meta: TextView =
            text(
                temp + "  ·  " + detail,
                if (isCompact()) 13 else 15,
                if (warning == TpmsAlertController.WARNING_NONE || !fresh) MainActivity.COLOR_MUTED
                else color,
            )
        meta.setTypeface(Typeface.DEFAULT_BOLD)
        card.addView(meta)
        return card
    }

    private fun settingsPanel(tint: Int): LinearLayout {
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setClipToPadding(false)
        panel.setPadding(dp(18), dp(16), dp(18), dp(16))
        panel.setBackground(settingsPanelBackground(tint))
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, 0, 0, dp(12))
        panel.setLayoutParams(lp)
        return panel
    }

    private fun addSettingsPanelHeader(
        panel: LinearLayout,
        title: String?,
        hint: String?,
        color: Int,
    ) {
        val header: LinearLayout = row()
        val accent: Int = settingsAccent(color)
        val marker: TextView = text(" ", 1, accent)
        marker.setBackground(round(accent, dp(3), Color.TRANSPARENT, 0))
        val markerLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(dp(4), if (isCompact()) dp(24) else dp(28))
        markerLp.setMargins(0, 0, dp(10), 0)
        header.addView(marker, markerLp)

        val label: TextView = text(title, if (isCompact()) 18 else 21, Color.WHITE)
        label.setTypeface(Typeface.DEFAULT_BOLD)
        val labelLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(label, labelLp)
        panel.addView(header)

        if (hint == null || hint.length == 0) return
        val description: TextView =
            text(hint, if (isCompact()) 12 else 14, MainActivity.COLOR_MUTED)
        description.setMaxLines(2)
        val hintLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        hintLp.setMargins(dp(14), dp(6), 0, 0)
        panel.addView(description, hintLp)
    }

    private fun addSettingsPanelSwitchHeader(
        panel: LinearLayout,
        title: String?,
        hint: String?,
        checked: Boolean,
        listener: CompoundButton.OnCheckedChangeListener?,
    ): CompoundButton {
        val header: LinearLayout = row()
        header.setGravity(Gravity.CENTER_VERTICAL)
        header.setMinimumHeight(if (isCompact()) dp(60) else dp(68))

        val texts: LinearLayout = LinearLayout(this)
        texts.setOrientation(LinearLayout.VERTICAL)
        val label: TextView = text(title, if (isCompact()) 18 else 21, Color.WHITE)
        label.setTypeface(Typeface.DEFAULT_BOLD)
        texts.addView(label)

        if (hint != null && hint.length > 0) {
            val description: TextView =
                text(hint, if (isCompact()) 12 else 14, MainActivity.COLOR_MUTED)
            description.setMaxLines(2)
            val hintLp: LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            hintLp.setMargins(0, dp(4), 0, 0)
            texts.addView(description, hintLp)
        }

        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle: CompoundButton = checkBox("", checked)
        toggle.setId(View.generateViewId())
        toggle.setContentDescription(title)
        label.setLabelFor(toggle.getId())
        toggle.setOnCheckedChangeListener(listener)
        header.addView(toggle, switchLayout(true))
        panel.addView(header)
        return toggle
    }

    private fun addSettingsSubHeader(panel: LinearLayout, title: String?, hint: String?) {
        val label: TextView = text(title, if (isCompact()) 14 else 16, Color.rgb(231, 239, 247))
        label.setTypeface(Typeface.DEFAULT_BOLD)
        val labelLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        labelLp.setMargins(0, dp(16), 0, 0)
        panel.addView(label, labelLp)

        if (hint == null || hint.length == 0) return
        val description: TextView =
            text(hint, if (isCompact()) 12 else 13, MainActivity.COLOR_MUTED)
        description.setMaxLines(2)
        val hintLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        hintLp.setMargins(0, dp(3), 0, dp(8))
        panel.addView(description, hintLp)
    }

    private fun statusBox(title: String?, content: TextView, color: Int): LinearLayout {
        val box: LinearLayout = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setPadding(dp(15), dp(13), dp(15), dp(13))
        box.setBackground(settingsInsetBackground(color))
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(12), 0, 0)
        box.setLayoutParams(lp)

        val header: LinearLayout = row()
        val label: TextView = text(title, if (isCompact()) 14 else 16, Color.WHITE)
        label.setTypeface(Typeface.DEFAULT_BOLD)
        val labelLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(label, labelLp)
        box.addView(header)

        content.setTextColor(Color.rgb(235, 241, 246))
        val contentLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        contentLp.setMargins(0, dp(8), 0, 0)
        box.addView(content, contentLp)
        return box
    }

    private fun settingsDivider(): View {
        val divider: View = View(this)
        divider.setBackgroundColor(softColor(MainActivity.COLOR_SETTINGS_DIVIDER, 120))
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        lp.setMargins(0, dp(10), 0, dp(10))
        divider.setLayoutParams(lp)
        return divider
    }

    private fun addInlineSwitch(
        root: LinearLayout,
        title: String?,
        hint: String?,
        checked: Boolean,
        listener: CompoundButton.OnCheckedChangeListener?,
    ): CompoundButton {
        val line: LinearLayout = LinearLayout(this)
        line.setOrientation(LinearLayout.HORIZONTAL)
        line.setGravity(Gravity.CENTER_VERTICAL)
        line.setMinimumHeight(if (isCompact()) dp(50) else dp(56))
        line.setPadding(0, dp(4), 0, dp(4))

        val texts: LinearLayout = LinearLayout(this)
        texts.setOrientation(LinearLayout.VERTICAL)
        val name: TextView = text(title, if (isCompact()) 15 else 17, Color.WHITE)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        val sub: TextView = text(hint, if (isCompact()) 12 else 13, MainActivity.COLOR_MUTED)
        sub.setMaxLines(2)
        texts.addView(name)
        texts.addView(sub)
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle: CompoundButton = checkBox("", checked)
        toggle.setId(View.generateViewId())
        toggle.setContentDescription(title)
        name.setLabelFor(toggle.getId())
        toggle.setOnCheckedChangeListener(listener)
        line.addView(toggle, switchLayout(true))
        root.addView(line)
        return toggle
    }

    private fun tpmsSettingsAlertsPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Оповещения",
            "оверлей сверху экрана и повтор звука до физического закрытия",
            MainActivity.COLOR_ACCENT_BLUE,
        )
        tpmsAlertsToggle =
            addInlineSwitch(
                panel,
                "Красная плашка",
                "показывать предупреждение поверх экрана при выходе за пороги",
                AppSettings.tpmsAlertsEnabled(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleTpmsAlerts(button, enabled)
                },
            )
        panel.addView(settingsDivider())
        tpmsSoundToggle =
            addInlineSwitch(
                panel,
                "Звуковое уведомление",
                "повторять звук, пока красная плашка не закрыта",
                AppSettings.tpmsSoundAlertsEnabled(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleTpmsSoundAlerts(button, enabled)
                },
            )
        return panel
    }

    private fun tpmsThresholdPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)

        addSettingsPanelHeader(
            panel,
            "Пороги давления и температуры",
            "жёлтый: вышли за диапазон; красный: дальше порога на 0.3 bar или 10C",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        val preview: TpmsSettingsPreviewView = TpmsSettingsPreviewView(this)
        val previewLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (isCompact()) dp(184) else dp(210),
            )
        previewLp.setMargins(0, dp(12), 0, 0)
        panel.addView(preview, previewLp)

        val summary: TextView =
            text("Сейчас: " + tpmsThresholdText(), if (isCompact()) 14 else 16, Color.WHITE)
        summary.setTypeface(Typeface.DEFAULT_BOLD)
        val summaryLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        summaryLp.setMargins(0, dp(10), 0, 0)
        panel.addView(summary, summaryLp)

        val logic: TextView =
            text(
                ("Опрос: красное состояние 1 сек, жёлтое 5 сек, " +
                    "открытый экран 5 сек, встроенный виджет 30 сек, фон 120 сек. " +
                    "Через 3 минуты без ответа колесо помечается как устаревшее."),
                if (isCompact()) 12 else 14,
                MainActivity.COLOR_MUTED,
            )
        val logicLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        logicLp.setMargins(0, dp(6), 0, 0)
        panel.addView(logic, logicLp)

        val grid: LinearLayout = LinearLayout(this)
        grid.setOrientation(if (isCompact()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL)
        grid.setClipToPadding(false)
        val gridLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        gridLp.setMargins(0, dp(12), 0, 0)
        panel.addView(grid, gridLp)

        grid.addView(
            tpmsThresholdGroup(
                "Давление",
                "bar",
                tpmsPressureThresholdControl(
                    "lowP",
                    "Минимум",
                    AppSettings.tpmsLowPressureKpa(this),
                    80,
                    450,
                    IntSetter { value: Int -> this.setTpmsLowPressure(value) },
                ),
                tpmsPressureThresholdControl(
                    "highP",
                    "Максимум",
                    AppSettings.tpmsHighPressureKpa(this),
                    120,
                    600,
                    IntSetter { value: Int -> this.setTpmsHighPressure(value) },
                ),
            ),
            tpmsThresholdGroupLayout(true),
        )
        grid.addView(
            tpmsThresholdGroup(
                "Температура",
                "C",
                tpmsThresholdControl(
                    "lowT",
                    "Минимум",
                    AppSettings.tpmsLowTempC(this),
                    -60,
                    80,
                    IntSetter { value: Int -> this.setTpmsLowTemp(value) },
                ),
                tpmsThresholdControl(
                    "highT",
                    "Максимум",
                    AppSettings.tpmsHighTempC(this),
                    -20,
                    160,
                    IntSetter { value: Int -> this.setTpmsHighTemp(value) },
                ),
            ),
            tpmsThresholdGroupLayout(false),
        )
        return panel
    }

    private fun tpmsThresholdGroup(
        title: String?,
        unit: String?,
        low: View?,
        high: View?,
    ): LinearLayout {
        val group: LinearLayout = LinearLayout(this)
        group.setOrientation(LinearLayout.VERTICAL)
        group.setPadding(dp(14), dp(12), dp(14), dp(14))
        group.setBackground(settingsInsetBackground())

        val header: LinearLayout = row()
        val name: TextView = text(title, if (isCompact()) 15 else 17, Color.WHITE)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        header.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill(unit, MainActivity.COLOR_PANEL_SOFT))
        group.addView(header)

        group.addView(low)
        group.addView(settingsDivider())
        group.addView(high)
        return group
    }

    private fun tpmsThresholdGroupLayout(first: Boolean): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            if (isCompact())
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(
            if (isCompact() || first) 0 else dp(8),
            if (first) 0 else (if (isCompact()) dp(8) else 0),
            if (isCompact() || !first) 0 else dp(8),
            0,
        )
        return lp
    }

    private fun tpmsThresholdControl(
        tag: String?,
        label: String?,
        value: Int,
        min: Int,
        max: Int,
        setter: IntSetter,
    ): LinearLayout? {
        return tpmsThresholdControl(tag, label, value, min, max, setter, false)
    }

    private fun tpmsPressureThresholdControl(
        tag: String?,
        label: String?,
        value: Int,
        min: Int,
        max: Int,
        setter: IntSetter,
    ): LinearLayout? {
        return tpmsThresholdControl(tag, label, value, min, max, setter, true)
    }

    private fun tpmsThresholdControl(
        tag: String?,
        label: String?,
        value: Int,
        min: Int,
        max: Int,
        setter: IntSetter,
        pressureBar: Boolean,
    ): LinearLayout? {
        val line: LinearLayout = LinearLayout(this)
        line.setOrientation(if (isCompact()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL)
        line.setGravity(Gravity.CENTER_VERTICAL)
        line.setMinimumHeight(if (isCompact()) dp(58) else dp(62))
        line.setPadding(0, dp(8), 0, 0)

        val name: TextView = text(label, if (isCompact()) 14 else 16, MainActivity.COLOR_TEXT)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        val nameLp: LinearLayout.LayoutParams =
            if (isCompact())
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        line.addView(name, nameLp)

        val input: EditText =
            if (pressureBar) pressureInput("tpmsBar:" + tag, value, min, max, setter)
            else numericInput("tpms:" + tag, value, min, max, setter)
        tpmsInputs.add(input)
        val rowLp: LinearLayout.LayoutParams =
            if (isCompact())
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            else LinearLayout.LayoutParams(dp(274), ViewGroup.LayoutParams.WRAP_CONTENT)
        rowLp.setMargins(if (isCompact()) 0 else dp(12), if (isCompact()) dp(6) else 0, 0, 0)
        line.addView(
            if (pressureBar) pressureRow(input, value, min, max, setter)
            else numericRow(input, value, min, max, setter),
            rowLp,
        )
        return line
    }

    private fun renderMediaTab(root: LinearLayout = tabContent!!) {
        val profile: Int = AppSettings.mediaProfile(this)
        root.addView(mediaMusicPanel())
        if (MediaSettingsVisibilityPolicy.showsTimingControls(profile)) {
            root.addView(mediaTimingPanel(profile))
        }
        if (MediaSettingsVisibilityPolicy.showsUniversalControls(profile)) {
            root.addView(mediaSessionPriorityPanel())
            root.addView(mediaRadioStationPanel())
        }
        if (AppSettings.teyesMediaProfile(this)) {
            root.addView(mediaCallPanel())
        }
        if (AppSettings.expertMode(this)) {
            root.addView(mediaDebugPanel())
        }
    }

    private fun mediaMusicPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Музыка и текст",
            "профиль магнитолы, трек, артист и отправка в машину",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        panel.addView(settingsDivider())
        addSettingsSubHeader(
            panel,
            "Профиль магнитолы",
            "старый TEYES не смешивается с универсальными режимами",
        )
        addActionGridColumns(
            panel,
            2,
            mediaProfileAction(
                AppSettings.MEDIA_PROFILE_TEYES,
                "TEYES / CC4",
                "магнитола TEYES, радио, USB, BT",
            ),
            mediaProfileAction(
                AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID,
                "Android",
                "музыка из Android и база радио",
            ),
            mediaProfileAction(
                AppSettings.MEDIA_PROFILE_UART_REAL,
                "Штатный источник + Android",
                "магнитола ведёт режим, Kia меняет только текст",
            ),
            mediaProfileAction(AppSettings.MEDIA_PROFILE_OFF, "Выкл", "Kia не отправляет медиа"),
        )

        if (!AppSettings.mediaEnabled(this)) {
            return panel
        }
        val profile: Int = AppSettings.mediaProfile(this)
        panel.addView(settingsDivider())
        addSettingsSubHeader(panel, otherMediaSourceTitle(profile), otherMediaSourceHint(profile))
        addActionGridColumns(
            panel,
            3,
            action(
                "Android",
                "обычная Android-музыка",
                otherModeColor(AppSettings.OTHER_SOURCE_ANDROID),
                Runnable { setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_ANDROID) },
            ),
            action(
                "Bluetooth",
                "показать как BT Audio",
                otherModeColor(AppSettings.OTHER_SOURCE_BLUETOOTH),
                Runnable { setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_BLUETOOTH) },
            ),
            action(
                "USB",
                "показать как USB Music",
                otherModeColor(AppSettings.OTHER_SOURCE_USB),
                Runnable { setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_USB) },
            ),
            action(
                "My Music",
                "показать как штатную музыку",
                otherModeColor(AppSettings.OTHER_SOURCE_MY_MUSIC),
                Runnable { setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_MY_MUSIC) },
            ),
            action(
                "CarPlay",
                "показать как CarPlay",
                otherModeColor(AppSettings.OTHER_SOURCE_CARPLAY),
                Runnable { setOtherMediaSourceMode(AppSettings.OTHER_SOURCE_CARPLAY) },
            ),
        )

        panel.addView(settingsDivider())
        addSettingsSubHeader(panel, "Формат строки", "как показывать текст мультимедиа")
        addActionGrid(
            panel,
            action(
                "Автор + трек",
                "исполнитель, потом название",
                mediaTextModeColor(AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK),
                Runnable { setMediaTextMode(AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK) },
            ),
            action(
                "Только трек",
                "без исполнителя",
                mediaTextModeColor(AppSettings.MEDIA_TEXT_TRACK_ONLY),
                Runnable { setMediaTextMode(AppSettings.MEDIA_TEXT_TRACK_ONLY) },
            ),
        )

        return panel
    }

    private fun mediaTimingPanel(profile: Int): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        val profileName: String =
            if (profile == AppSettings.MEDIA_PROFILE_UART_REAL) "UART Real" else "Android"
        addSettingsPanelHeader(
            panel,
            "Синхронизация отправки трека",
            ("активный профиль: " + profileName + "; TEYES / CC4 не изменяется"),
            MainActivity.COLOR_ACCENT_BLUE,
        )

        val artistThenTrack: Boolean =
            (AppSettings.mediaTextMode(this) == AppSettings.MEDIA_TEXT_ARTIST_THEN_TRACK)
        val current: TextView =
            text(
                ("После смены источника (" +
                    profileName +
                    "): " +
                    MainActivity.mediaDelayText(AppSettings.mediaSourceDelayMs(this, profile)) +
                    "\nАвтор перед названием: " +
                    (if (artistThenTrack)
                        MainActivity.mediaDelayText(AppSettings.mediaArtistDelayMs(this)) +
                            " (общая)"
                    else "не используется в режиме «Только трек»")),
                if (isCompact()) 13 else 15,
                Color.rgb(235, 241, 246),
            )
        panel.addView(statusBox("Сейчас", current, MainActivity.COLOR_ACCENT_BLUE))

        panel.addView(settingsDivider())
        addSettingsSubHeader(
            panel,
            "После смены источника",
            if (profile == AppSettings.MEDIA_PROFILE_UART_REAL)
                "пауза после реального UART source перед отправкой текста"
            else "пауза после переключения штатного media source",
        )
        addActionGridColumns(
            panel,
            3,
            mediaSourceDelayAction(0),
            mediaSourceDelayAction(300),
            mediaSourceDelayAction(600),
            mediaSourceDelayAction(1000),
            mediaSourceDelayAction(1500),
            mediaSourceDelayAction(2000),
            mediaSourceDelayAction(3000),
        )

        panel.addView(settingsDivider())
        addSettingsSubHeader(
            panel,
            "Автор → название трека",
            if (artistThenTrack) "общая для Android и UART Real; 0 сразу отправляет название"
            else "не используется, пока выбран режим «Только трек»",
        )
        if (artistThenTrack) {
            addActionGridColumns(
                panel,
                2,
                mediaArtistDelayAction(0),
                mediaArtistDelayAction(2500),
                mediaArtistDelayAction(5000),
                mediaArtistDelayAction(8000),
            )
        } else {
            val inactive: TextView =
                text(
                    "Выберите выше «Автор + трек», чтобы настроить время показа исполнителя.",
                    if (isCompact()) 13 else 15,
                    MainActivity.COLOR_MUTED,
                )
            panel.addView(statusBox("Сейчас выключено", inactive, MainActivity.COLOR_PANEL_SOFT))
        }

        if (
            MediaSettingsVisibilityPolicy.showsSourceReassertControl(
                profile,
                AppSettings.expertMode(this),
            )
        ) {
            panel.addView(settingsDivider())
            addSettingsSubHeader(
                panel,
                "Дополнительно",
                "контрольный повтор нужен только некоторым приборным панелям",
            )
            addActionGrid(
                panel,
                action(
                    "Контрольный повтор",
                    if (AppSettings.mediaSourceReassertEnabled(this))
                        "один повтор после смены source"
                    else "выключен",
                    choiceColor(AppSettings.mediaSourceReassertEnabled(this)),
                    Runnable { this.toggleMediaSourceReassert() },
                ),
            )
        }
        return panel
    }

    private fun otherMediaSourceTitle(profile: Int): String {
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL) return "Обычные Android-плееры"
        return "Обычная музыка"
    }

    private fun otherMediaSourceHint(profile: Int): String {
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL) {
            return "как показать трек, не меняя штатный режим магнитолы"
        }
        if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID) {
            return "каким штатным источником показать музыку без точного source"
        }
        return "какой штатный режим имитировать для Yandex Music, Spotify и Android-плееров"
    }

    private fun showCurrentRadioStationDialog() {
        val media = StateStore.media()
        val frequency = RadioStationStore.currentFrequency(media)
        if (frequency.isEmpty()) {
            AppLog.line(this, "Radio stations: no current frequency")
            refresh()
            return
        }
        showRadioStationEditDialog(RadioStationStore.currentBand(media), frequency, media.title)
    }

    private fun showRadioStationListDialog() {
        val entries: List<RadioStationStore.Entry?>? = RadioStationStore.entries(this)
        val dialog = AlertDialog.Builder(this).create()
        dialog.setView(radioStationManagerView(entries!!, dialog), 0, 0, 0, 0)
        dialog.show()
        val window: Window? = dialog.getWindow()
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.68f)
            val lp: WindowManager.LayoutParams = WindowManager.LayoutParams()
            lp.copyFrom(window.getAttributes())
            val screenWidth: Int = getResources().getDisplayMetrics().widthPixels
            val screenHeight: Int = getResources().getDisplayMetrics().heightPixels
            lp.width = Math.min(screenWidth - dp(28), dp(680))
            val targetHeightDp: Int =
                if (entries.isEmpty()) 420 else if (entries.size <= 2) 540 else 720
            lp.height = Math.min(screenHeight - dp(28), dp(targetHeightDp))
            window.setAttributes(lp)
        }
    }

    private fun showRadioStationListDialogFresh() {
        radioStationManagerScrollY = 0
        restoreRadioStationManagerScroll = false
        showRadioStationListDialog()
    }

    private fun radioStationManagerView(
        entries: List<RadioStationStore.Entry?>,
        dialog: AlertDialog,
    ): View {
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setPadding(dp(18), dp(16), dp(18), dp(18))
        panel.setBackground(
            round(
                MainActivity.COLOR_SETTINGS_PANEL,
                dp(10),
                MainActivity.COLOR_SETTINGS_DIVIDER,
                dp(1),
            )
        )
        panel.setAccessibilityPaneTitle("Радиостанции")

        val header: LinearLayout = row()
        header.setGravity(Gravity.CENTER_VERTICAL)
        val titleBox: LinearLayout = LinearLayout(this)
        titleBox.setOrientation(LinearLayout.VERTICAL)
        val title: TextView = text("Радиостанции", if (isCompact()) 19 else 22, Color.WHITE)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        titleBox.addView(title)
        val hint: TextView =
            text(
                "Android и UART Real · ручные имена защищены от автообновления",
                if (isCompact()) 11 else 13,
                MainActivity.COLOR_MUTED,
            )
        hint.setMaxLines(2)
        titleBox.addView(hint)
        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val close: TextView = text("×", if (isCompact()) 24 else 28, MainActivity.COLOR_MUTED)
        close.setGravity(Gravity.CENTER)
        close.setTypeface(Typeface.DEFAULT_BOLD)
        close.setClickable(true)
        close.setFocusable(true)
        close.setContentDescription("Закрыть список радиостанций")
        close.setBackground(settingsButtonBackground(false))
        close.setOnClickListener({ v -> dialog.dismiss() })
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header)

        panel.addView(radioStationStats(entries))
        val list: ScrollView = radioStationListView(entries, dialog) as ScrollView
        if (restoreRadioStationManagerScroll) {
            val scrollY: Int = radioStationManagerScrollY
            list.post({ list.scrollTo(0, scrollY) })
            restoreRadioStationManagerScroll = false
        }
        val listLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        listLp.setMargins(0, dp(8), 0, dp(10))
        panel.addView(list, listLp)

        val actions: LinearLayout = row()
        val add: TextView = wizardActionButton("Добавить станцию", true)
        add.setOnClickListener({ v ->
            rememberRadioStationManagerScroll(list)
            dialog.dismiss()
            showRadioStationEditorDialog("", "", "", true)
        })
        val done: TextView = wizardActionButton("Готово", false)
        done.setOnClickListener({ v -> dialog.dismiss() })
        val addLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        addLp.setMargins(0, 0, dp(5), 0)
        val doneLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        doneLp.setMargins(dp(5), 0, 0, 0)
        actions.addView(add, addLp)
        actions.addView(done, doneLp)
        panel.addView(actions)
        return panel
    }

    private fun radioStationListView(
        entries: List<RadioStationStore.Entry?>,
        dialog: AlertDialog,
    ): View {
        val scroll: ScrollView = ScrollView(this)
        scroll.setFillViewport(true)
        scroll.setClipToPadding(false)

        val root: LinearLayout = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setPadding(0, 0, 0, dp(4))

        if (entries.isEmpty()) {
            val empty: LinearLayout = LinearLayout(this)
            empty.setOrientation(LinearLayout.VERTICAL)
            empty.setGravity(Gravity.CENTER)
            empty.setPadding(dp(20), dp(34), dp(20), dp(34))
            empty.setBackground(settingsInsetBackground(MainActivity.COLOR_ACCENT_BLUE))
            val title: TextView = text("Список пока пуст", if (isCompact()) 18 else 21, Color.WHITE)
            title.setTypeface(Typeface.DEFAULT_BOLD)
            title.setGravity(Gravity.CENTER)
            val hint: TextView =
                text(
                    "Добавьте FM/AM вручную или включите радио — " +
                        "Kia запомнит найденную частоту автоматически",
                    if (isCompact()) 13 else 15,
                    MainActivity.COLOR_MUTED,
                )
            hint.setGravity(Gravity.CENTER)
            val hintLp: LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            hintLp.setMargins(0, dp(8), 0, 0)
            empty.addView(title)
            empty.addView(hint, hintLp)
            val emptyLp: LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            emptyLp.setMargins(0, dp(14), 0, 0)
            root.addView(empty, emptyLp)
        } else {
            addRadioStationBandSection(root, "FM", entries, dialog, scroll)
            addRadioStationBandSection(root, "AM", entries, dialog, scroll)
        }
        scroll.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return scroll
    }

    private fun addRadioStationBandSection(
        root: LinearLayout,
        band: String,
        entries: List<RadioStationStore.Entry?>,
        dialog: AlertDialog,
        scroll: ScrollView?,
    ) {
        var count: Int = 0
        for (entry: RadioStationStore.Entry? in entries) {
            if (entry != null && band.equals(entry.band, ignoreCase = true)) count++
        }
        if (count == 0) return

        val header: TextView =
            text(band + "  ·  " + count, if (isCompact()) 15 else 17, Color.WHITE)
        header.setTypeface(Typeface.DEFAULT_BOLD)
        val headerLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        headerLp.setMargins(0, dp(18), 0, dp(7))
        root.addView(header, headerLp)

        for (entry: RadioStationStore.Entry? in entries) {
            if (entry == null || !band.equals(entry.band, ignoreCase = true)) continue
            root.addView(radioStationListCard(entry, dialog, scroll))
        }
    }

    private fun radioStationListCard(
        entry: RadioStationStore.Entry,
        dialog: AlertDialog,
        scroll: ScrollView?,
    ): View {
        val currentMedia = StateStore.media()
        val current: Boolean =
            entry.band.equals(RadioStationStore.currentBand(currentMedia), ignoreCase = true) &&
                entry.frequency.equals(RadioStationStore.currentFrequency(currentMedia))
        val displayName = RadioStationStore.displayName(entry.band, entry.frequency, entry.name)

        val card: LinearLayout = LinearLayout(this)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setPadding(dp(15), dp(12), dp(15), dp(12))
        card.setMinimumHeight(if (isCompact()) dp(78) else dp(88))
        card.setClickable(true)
        card.setFocusable(true)
        card.setBackground(
            settingsActionBackground(
                current,
                if (current) MainActivity.COLOR_SUCCESS else MainActivity.COLOR_ACCENT_BLUE,
            )
        )
        card.setContentDescription(
            (entry.band.toString() +
                " " +
                entry.frequency +
                ". " +
                (if (displayName.isEmpty()) "Без названия" else displayName) +
                ". " +
                (if (entry.manual) "Своё название" else "Авто") +
                (if (current) ". Сейчас включена" else "") +
                ". Изменить")
        )
        card.setOnClickListener({ v ->
            rememberRadioStationManagerScroll(scroll)
            dialog.dismiss()
            showRadioStationEditorDialog(entry.band, entry.frequency, entry.name, true)
        })

        val header: LinearLayout = row()
        header.setGravity(Gravity.CENTER_VERTICAL)
        val unit: String = if ("AM".equals(entry.band, ignoreCase = true)) "кГц" else "МГц"
        val frequency: TextView =
            text(entry.frequency.toString() + " " + unit, if (isCompact()) 18 else 21, Color.WHITE)
        frequency.setTypeface(Typeface.DEFAULT_BOLD)
        header.addView(
            frequency,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (current) {
            header.addView(pill("сейчас", MainActivity.COLOR_SUCCESS), chipLayout(true))
        }
        header.addView(
            pill(
                if (entry.manual) "своё" else if (displayName.isEmpty()) "без имени" else "авто",
                if (entry.manual) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_PANEL_SOFT,
            ),
            chipLayout(!current),
        )
        card.addView(header)

        val name: TextView =
            text(
                if (displayName.isEmpty()) "Название ещё не определено" else displayName,
                if (isCompact()) 15 else 17,
                MainActivity.COLOR_TEXT,
            )
        name.setTypeface(Typeface.DEFAULT_BOLD)
        name.setMaxLines(2)
        name.setEllipsize(android.text.TextUtils.TruncateAt.END)
        val nameLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        nameLp.setMargins(0, dp(6), 0, 0)
        card.addView(name, nameLp)

        val edit: TextView =
            text("Изменить название  ›", if (isCompact()) 12 else 13, MainActivity.COLOR_MUTED)
        val editLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        editLp.setMargins(0, dp(4), 0, 0)
        card.addView(edit, editLp)

        val cardLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        cardLp.setMargins(0, 0, 0, dp(7))
        card.setLayoutParams(cardLp)
        return card
    }

    private fun rememberRadioStationManagerScroll(scroll: ScrollView?) {
        radioStationManagerScrollY = if (scroll == null) 0 else scroll.getScrollY()
        restoreRadioStationManagerScroll = true
    }

    private fun showAddRadioStationBandDialog() {
        showRadioStationEditorDialog("", "", "", false)
    }

    private fun showRadioStationEditDialog(
        band: String?,
        frequency: String?,
        currentName: String?,
    ) {
        showRadioStationEditorDialog(band, frequency, currentName, false)
    }

    private fun showRadioStationEditorDialog(
        band: String?,
        frequency: String?,
        currentName: String?,
        returnToManager: Boolean,
    ) {
        val editing: Boolean = frequency != null && !frequency.trim { it <= ' ' }.isEmpty()
        val cleanBand: String = if ("AM".equals(band, ignoreCase = true)) "AM" else "FM"
        val cleanFrequency: String? =
            if (editing) RadioStationStore.normalizeFrequencyInput(cleanBand, frequency) else ""
        val initialName: String? =
            if (editing) RadioStationStore.displayName(cleanBand, cleanFrequency, currentName)
            else ""
        val currentMedia = StateStore.media()
        val currentStation: Boolean =
            editing &&
                cleanBand.equals(RadioStationStore.currentBand(currentMedia), ignoreCase = true) &&
                cleanFrequency.equals(RadioStationStore.currentFrequency(currentMedia))

        val dialog = AlertDialog.Builder(this).create()
        val selectedBand: kotlin.Array<String?> = arrayOf<String?>(cleanBand)
        val destructiveArmed: BooleanArray = booleanArrayOf(false)

        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setPadding(dp(18), dp(16), dp(18), dp(18))
        panel.setBackground(
            round(
                MainActivity.COLOR_SETTINGS_PANEL,
                dp(10),
                MainActivity.COLOR_SETTINGS_DIVIDER,
                dp(1),
            )
        )
        panel.setAccessibilityPaneTitle(
            if (editing) "Редактирование радиостанции" else "Новая радиостанция"
        )

        val header: LinearLayout = row()
        header.setGravity(Gravity.CENTER_VERTICAL)
        val titleBox: LinearLayout = LinearLayout(this)
        titleBox.setOrientation(LinearLayout.VERTICAL)
        val title: TextView =
            text(
                if (editing) "Изменить станцию" else "Новая радиостанция",
                if (isCompact()) 19 else 22,
                Color.WHITE,
            )
        title.setTypeface(Typeface.DEFAULT_BOLD)
        titleBox.addView(title)
        val hint: TextView =
            text(
                if (editing)
                    (cleanBand +
                        " " +
                        cleanFrequency +
                        (if ("AM".equals(cleanBand)) " кГц" else " МГц") +
                        " · ручное имя важнее авто")
                else "Выберите FM/AM, укажите частоту и своё название",
                if (isCompact()) 11 else 13,
                MainActivity.COLOR_MUTED,
            )
        hint.setMaxLines(2)
        titleBox.addView(hint)
        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val close: TextView = text("×", if (isCompact()) 24 else 28, MainActivity.COLOR_MUTED)
        close.setGravity(Gravity.CENTER)
        close.setTypeface(Typeface.DEFAULT_BOLD)
        close.setClickable(true)
        close.setFocusable(true)
        close.setContentDescription("Закрыть редактор станции")
        close.setBackground(settingsButtonBackground(false))
        close.setOnClickListener({ v -> dialog.dismiss() })
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header)

        val bandLabel: TextView =
            radioStationFieldLabel(if (editing) "Диапазон (не изменяется)" else "Диапазон")
        panel.addView(bandLabel, radioStationFieldLabelLayout(true))
        val bandRow: LinearLayout = row()
        val fm: TextView = wizardActionButton("FM", "FM".equals(cleanBand))
        val am: TextView = wizardActionButton("AM", "AM".equals(cleanBand))
        fm.setEnabled(!editing)
        am.setEnabled(!editing)
        if (editing) {
            fm.setVisibility(if ("FM".equals(cleanBand)) View.VISIBLE else View.GONE)
            am.setVisibility(if ("AM".equals(cleanBand)) View.VISIBLE else View.GONE)
        }
        val fmLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        fmLp.setMargins(0, 0, dp(5), 0)
        val amLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        amLp.setMargins(dp(5), 0, 0, 0)
        bandRow.addView(fm, fmLp)
        bandRow.addView(am, amLp)
        panel.addView(bandRow)

        val frequencyLabel: TextView = radioStationFieldLabel("Частота")
        val frequencyInput: EditText =
            radioStationEditorInput(
                if ("FM".equals(cleanBand)) "Например, 101.0" else "Например, 1584",
                InputType.TYPE_CLASS_NUMBER or
                    (if ("FM".equals(cleanBand)) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0),
                7,
            )
        frequencyInput.setId(View.generateViewId())
        frequencyLabel.setLabelFor(frequencyInput.getId())
        if (editing) {
            frequencyInput.setText(cleanFrequency)
            frequencyInput.setEnabled(false)
        }
        panel.addView(frequencyLabel, radioStationFieldLabelLayout(false))
        val frequencyRow: LinearLayout = row()
        frequencyRow.setGravity(Gravity.CENTER_VERTICAL)
        frequencyRow.addView(frequencyInput, LinearLayout.LayoutParams(0, dp(56), 1f))
        val unit: TextView =
            text(
                if ("FM".equals(cleanBand)) "МГц" else "кГц",
                if (isCompact()) 14 else 16,
                Color.WHITE,
            )
        unit.setGravity(Gravity.CENTER)
        unit.setTypeface(Typeface.DEFAULT_BOLD)
        unit.setBackground(settingsInsetBackground(MainActivity.COLOR_ACCENT_BLUE))
        val unitLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(dp(82), dp(56))
        unitLp.setMargins(dp(10), 0, 0, 0)
        frequencyRow.addView(unit, unitLp)
        panel.addView(frequencyRow)

        val nameLabel: TextView = radioStationFieldLabel("Название")
        val nameInput: EditText =
            radioStationEditorInput(
                "Например, Europa Plus",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                64,
            )
        nameInput.setId(View.generateViewId())
        nameLabel.setLabelFor(nameInput.getId())
        nameInput.setText(initialName)
        nameInput.setSelectAllOnFocus(editing)
        nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE)
        panel.addView(nameLabel, radioStationFieldLabelLayout(false))
        panel.addView(
            nameInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
        )

        val updateBandUi: Runnable = Runnable {
            val fmSelected: Boolean = "FM".equals(selectedBand.get(0))
            fm.setBackground(settingsActionBackground(fmSelected, MainActivity.COLOR_ACCENT_BLUE))
            am.setBackground(settingsActionBackground(!fmSelected, MainActivity.COLOR_ACCENT_BLUE))
            fm.setContentDescription("FM" + (if (fmSelected) ". Выбрано" else ""))
            am.setContentDescription("AM" + (if (!fmSelected) ". Выбрано" else ""))
            frequencyInput.setHint(if (fmSelected) "Например, 101.0" else "Например, 1584")
            frequencyInput.setInputType(
                InputType.TYPE_CLASS_NUMBER or
                    (if (fmSelected) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            )
            unit.setText(if (fmSelected) "МГц" else "кГц")
            frequencyInput.setError(null)
        }
        fm.setOnClickListener({ v ->
            if (!"FM".equals(selectedBand.get(0))) frequencyInput.setText("")
            selectedBand[0] = "FM"
            updateBandUi.run()
            frequencyInput.requestFocus()
        })
        am.setOnClickListener({ v ->
            if (!"AM".equals(selectedBand.get(0))) frequencyInput.setText("")
            selectedBand[0] = "AM"
            updateBandUi.run()
            frequencyInput.requestFocus()
        })
        updateBandUi.run()

        if (editing) {
            val destructive: TextView =
                wizardActionButton(
                    if (currentStation) "Очистить название" else "Удалить станцию",
                    false,
                )
            destructive.setBackground(settingsActionBackground(false, MainActivity.COLOR_DANGER))
            destructive.setOnClickListener({ v ->
                if (!destructiveArmed.get(0)) {
                    destructiveArmed[0] = true
                    destructive.setText(
                        if (currentStation) "Подтвердить очистку" else "Подтвердить удаление"
                    )
                    destructive.setContentDescription(
                        destructive.getText().toString() + ". Нажмите ещё раз"
                    )
                    destructive.setBackground(
                        settingsActionBackground(true, MainActivity.COLOR_DANGER)
                    )
                    Toast.makeText(this, "Нажмите ещё раз для подтверждения", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                RadioStationStore.clearStationName(this, cleanBand, cleanFrequency)
                val action: String? = if (currentStation) "reset to auto" else "deleted"
                AppLog.line(
                    this,
                    ("Radio station " + action + ": " + cleanBand + " " + cleanFrequency),
                )
                resendRadioStationName(cleanBand, cleanFrequency!!)
                renderTab()
                refresh()
                dialog.dismiss()
            })
            val destructiveLp: LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            destructiveLp.setMargins(0, dp(14), 0, 0)
            panel.addView(destructive, destructiveLp)
        }

        val actions: LinearLayout = row()
        val cancel: TextView = wizardActionButton("Отмена", false)
        cancel.setOnClickListener({ v -> dialog.dismiss() })
        val save: TextView = wizardActionButton("Сохранить", true)
        save.setOnClickListener({ v ->
            val targetBand: String? = if (editing) cleanBand else selectedBand.get(0)
            val normalized: String? =
                if (editing) cleanFrequency
                else
                    RadioStationStore.normalizeFrequencyInput(
                        targetBand,
                        frequencyInput.getText().toString(),
                    )
            if (normalized!!.isEmpty()) {
                frequencyInput.setError("Проверьте частоту")
                frequencyInput.requestFocus()
                return@setOnClickListener
            }
            if (
                !RadioStationStore.saveManualStation(
                    this,
                    targetBand,
                    normalized,
                    nameInput.getText().toString(),
                )
            ) {
                nameInput.setError("Введите название, а не только частоту")
                nameInput.requestFocus()
                return@setOnClickListener
            }
            AppLog.line(
                this,
                ("Radio station " +
                    (if (editing) "saved" else "added") +
                    ": " +
                    targetBand +
                    " " +
                    normalized),
            )
            resendRadioStationName(targetBand!!, normalized)
            renderTab()
            refresh()
            dialog.dismiss()
        })
        val cancelLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        cancelLp.setMargins(0, dp(14), dp(5), 0)
        val saveLp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        saveLp.setMargins(dp(5), dp(14), 0, 0)
        actions.addView(cancel, cancelLp)
        actions.addView(save, saveLp)
        panel.addView(actions)

        val scroll: ScrollView = ScrollView(this)
        scroll.setFillViewport(true)
        scroll.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dialog.setView(scroll, 0, 0, 0, 0)
        dialog.setOnDismissListener({ ignored ->
            if (!returnToManager) return@setOnDismissListener
            handler.post({
                if (
                    activityVisible &&
                        !isFinishing() &&
                        !isDestroyed() &&
                        AppSettings.universalMediaProfile(this)
                ) {
                    showRadioStationListDialog()
                }
            })
        })
        dialog.show()
        val window: Window? = dialog.getWindow()
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.68f)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val lp: WindowManager.LayoutParams = WindowManager.LayoutParams()
            lp.copyFrom(window.getAttributes())
            val screenWidth: Int = getResources().getDisplayMetrics().widthPixels
            val screenHeight: Int = getResources().getDisplayMetrics().heightPixels
            lp.width = Math.min(screenWidth - dp(28), dp(620))
            lp.height = Math.min(screenHeight - dp(28), dp(if (editing) 620 else 570))
            window.setAttributes(lp)
        }
    }

    private fun radioStationFieldLabel(value: String?): TextView {
        val label: TextView = text(value, if (isCompact()) 13 else 15, MainActivity.COLOR_TEXT)
        label.setTypeface(Typeface.DEFAULT_BOLD)
        return label
    }

    private fun radioStationFieldLabelLayout(first: Boolean): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(if (first) 14 else 12), 0, dp(6))
        return lp
    }

    private fun radioStationEditorInput(hint: String?, inputType: Int, maxLength: Int): EditText {
        val input: EditText = EditText(this)
        input.setSingleLine(true)
        input.setHint(hint)
        input.setInputType(inputType)
        input.setImeOptions(EditorInfo.IME_ACTION_NEXT)
        input.setFilters(arrayOf<InputFilter?>(InputFilter.LengthFilter(maxLength)))
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(MainActivity.COLOR_MUTED)
        input.setTextSize(if (isCompact()) 15f else 17f)
        input.setPadding(dp(14), dp(8), dp(14), dp(8))
        input.setBackground(
            round(MainActivity.COLOR_PANEL_SOFT, dp(8), MainActivity.COLOR_SETTINGS_DIVIDER, dp(1))
        )
        return input
    }

    private fun resendRadioStationName(band: String, frequency: String) {
        if (!AppSettings.universalMediaProfile(this)) return
        val media = StateStore.media()
        if (
            !band.equals(RadioStationStore.currentBand(media), ignoreCase = true) ||
                !frequency.equals(RadioStationStore.currentFrequency(media))
        )
            return
        val station = RadioStationStore.resolveUniversal(this, band, frequency, "")
        MediaFeature.get(this)
            .report(
                media.source,
                media.packageName,
                media.artist,
                station,
                media.durationMs,
                media.playing,
            )
    }

    private fun mediaCallPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        callEnabledToggle =
            addSettingsPanelSwitchHeader(
                panel,
                "BT звонок",
                "только TEYES CC4 Pro; отслеживание и передача состояния на приборку",
                AppSettings.callEnabled(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleCallEnabled(button, enabled)
                },
            )
        if (!AppSettings.callEnabled(this)) {
            return panel
        }
        panel.addView(settingsDivider())
        addActionGrid(
            panel,
            callSourceAction(
                AppSettings.CALL_SOURCE_ANDROID_AUTO,
                "Android Auto",
                "шильдик Android Auto",
            ),
            callSourceAction(
                AppSettings.CALL_SOURCE_CARPLAY,
                "CarPlay",
                "подтверждённый рабочий режим",
            ),
            callSourceAction(AppSettings.CALL_SOURCE_BLUETOOTH, "BT Audio", "режим по умолчанию"),
        )

        return panel
    }

    private fun mediaDebugPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        mediaDebugToggle =
            addSettingsPanelSwitchHeader(
                panel,
                "Отладка overlay",
                "показывает media-состояние для скриншота и разбора",
                AppSettings.mediaOverlayEnabled(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleMediaDebug(button, enabled)
                },
            )
        if (AppSettings.mediaOverlayEnabled(this)) {
            panel.addView(settingsDivider())
            mediaDebugStatus =
                text(mediaDebugText(), if (isCompact()) 12 else 14, Color.rgb(235, 241, 246))
            mediaDebugStatus!!.setTypeface(Typeface.MONOSPACE)
            mediaDebugStatus!!.setLineSpacing(0f, 1.08f)
            val lp: LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            lp.setMargins(0, dp(10), 0, 0)
            panel.addView(mediaDebugStatus, lp)
        }
        return panel
    }

    private fun renderNavigationTab(root: LinearLayout = tabContent!!) {
        root.addView(navigationLivePanel())
        root.addView(navigationOutputPanel())
        addSection(
            root,
            "Источник",
            "Для YNavi MVite выберите отдельный источник.",
            navSourceAction(AppSettings.NAV_SOURCE_AUTO, "Auto", "fallback Yandex + 2GIS"),
            navSourceAction(AppSettings.NAV_SOURCE_YANDEX, "Yandex", "только Yandex Core Bridge"),
            navSourceAction(AppSettings.NAV_SOURCE_2GIS, "2GIS", "только dashboard 2GIS"),
            navSourceAction(AppSettings.NAV_SOURCE_MVITE, "MVite", "данные интеграции YNavi MVite"),
        )
        root.addView(navigationOptionsPanel())
        if (AppSettings.expertMode(this)) {
            navigationDebugToggle =
                addSettingSwitch(
                    root,
                    "Панель диагностики",
                    "служебные данные навигации поверх карты",
                    AppSettings.navOverlayEnabled(this),
                    CompoundButton.OnCheckedChangeListener {
                        button: CompoundButton?,
                        enabled: Boolean ->
                        this.toggleNavDebug(button, enabled)
                    },
                )
        }
    }

    private fun navigationLivePanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT)
        addSettingsPanelHeader(
            panel,
            "Сейчас в приборке",
            "живой снимок фактического состояния; пример ниже не отправляет TX",
            MainActivity.COLOR_ACCENT,
        )

        val live: TextView =
            text(navigationLiveText(), if (isCompact()) 12 else 14, Color.rgb(235, 241, 246))
        live.setTypeface(Typeface.MONOSPACE)
        live.setLineSpacing(0f, 1.08f)
        panel.addView(
            statusBox(
                "Последнее решение",
                live,
                if (StateStore.navigation().active) MainActivity.COLOR_SUCCESS
                else MainActivity.COLOR_WARNING,
            )
        )

        val preview: NavigationSettingsPreviewView = NavigationSettingsPreviewView(this)
        preview.setContentDescription("Визуальный пример выбранного режима навигации")
        val previewLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (isCompact()) dp(230) else dp(250),
            )
        previewLp.setMargins(0, dp(12), 0, 0)
        panel.addView(preview, previewLp)
        return panel
    }

    private fun navigationLiveText(): String {
        val state = StateStore.navigation()
        if (!state.active) {
            return ("Маршрут не активен\n" +
                "Источник: " +
                AppSettings.navSourceLabel(this) +
                " · последний снимок " +
                timeText(state.updatedAt) +
                "\nПоследний TX: " +
                MainActivity.lastLineOrDash(state.clusterTx))
        }
        val actual: DashboardNavigationSnapshot = DashboardNavigationSnapshot.resolve(state)
        val maneuver: String? =
            if (actual.maneuverId.isEmpty()) "—" else actual.presentation.fallbackLabel
        val distance: String = MainActivity.emptyDash(actual.distance)
        val gray: String = MainActivity.emptyDash(actual.grayRoad)
        val micro: String =
            if (state.microManeuverId.isEmpty()) "нет"
            else
                state.microManeuverId.toString() +
                    (if (state.microDistance.isEmpty()) "" else " · " + state.microDistance)
        return ("Знак: " +
            maneuver +
            " · дистанция " +
            distance +
            "\nСерая дорога: " +
            gray +
            " · micro " +
            micro +
            "\nСкорость: " +
            MainActivity.emptyDash(state.currentSpeed) +
            " · лимит " +
            MainActivity.emptyDash(state.speedLimit) +
            "\nПоследний TX: " +
            MainActivity.lastLineOrDash(state.clusterTx))
    }

    private fun navigationOutputPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        val routeMode: Int = NavigationModeSettings.mode(this)
        panel.addView(navigationOutputHero(routeMode))
        addActionGridColumns(
            panel,
            3,
            navigationOutputTile(
                "Источник",
                AppSettings.navSourceLabel(this),
                navigationSourceOutputHint(),
                MainActivity.COLOR_ACCENT_BLUE,
                true,
            ),
            navigationOutputTile(
                navigationTextTileTitle(routeMode),
                navigationTextTileValue(routeMode),
                navigationTextTileHint(routeMode),
                MainActivity.COLOR_ACCENT_BLUE,
                true,
            ),
            navigationOutputTile(
                navigationAssistTileTitle(routeMode),
                navigationAssistTileValue(routeMode),
                navigationAssistTileHint(routeMode),
                navigationAssistTileColor(routeMode),
                navigationAssistTileActive(routeMode),
            ),
        )
        return panel
    }

    private fun navigationOutputHero(routeMode: Int): View {
        val card: LinearLayout = LinearLayout(this)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.setMinimumHeight(navigationOutputHeroHeight())
        card.setBackground(
            round(softColor(MainActivity.COLOR_ACCENT_BLUE, 44), dp(8), Color.TRANSPARENT, 0)
        )
        val cardLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                navigationOutputHeroHeight(),
            )
        cardLp.setMargins(0, 0, 0, dp(4))
        card.setLayoutParams(cardLp)

        val value: TextView =
            text(navigationOutputMainValue(routeMode), if (isCompact()) 19 else 23, Color.WHITE)
        value.setTypeface(Typeface.DEFAULT_BOLD)
        value.setMaxLines(2)
        val valueLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        card.addView(value, valueLp)

        val hint: TextView =
            text(
                navigationOutputMainHint(routeMode),
                if (isCompact()) 12 else 14,
                MainActivity.COLOR_MUTED,
            )
        hint.setMaxLines(2)
        val hintLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        hintLp.setMargins(0, dp(3), 0, 0)
        card.addView(hint, hintLp)
        return card
    }

    private fun navigationOutputHeroHeight(): Int {
        return if (isCompact()) dp(82) else dp(98)
    }

    private fun navigationOutputTile(
        title: String?,
        value: String?,
        hint: String?,
        color: Int,
        active: Boolean,
    ): View {
        val tile: LinearLayout = LinearLayout(this)
        tile.setOrientation(LinearLayout.VERTICAL)
        tile.setGravity(Gravity.CENTER_VERTICAL)
        tile.setPadding(dp(14), dp(11), dp(14), dp(11))
        tile.setMinimumHeight(if (isCompact()) dp(72) else dp(88))
        val accent: Int = settingsAccent(color)
        tile.setBackground(
            round(
                if (active) softColor(accent, 38) else MainActivity.COLOR_SETTINGS_PANEL_ALT,
                dp(7),
                Color.TRANSPARENT,
                0,
            )
        )

        val label: TextView =
            text(
                title,
                if (isCompact()) 11 else 12,
                if (active) accent else MainActivity.COLOR_MUTED,
            )
        label.setTypeface(Typeface.DEFAULT_BOLD)
        val main: TextView = text(value, if (isCompact()) 15 else 17, Color.WHITE)
        main.setTypeface(Typeface.DEFAULT_BOLD)
        main.setMaxLines(2)
        val sub: TextView = text(hint, if (isCompact()) 11 else 12, MainActivity.COLOR_MUTED)
        sub.setMaxLines(2)
        tile.addView(label)
        tile.addView(main)
        tile.addView(sub)
        return tile
    }

    private fun navigationOutputMainValue(mode: Int): String {
        if (mode == NavigationOutputMode.TBT) return "TBT-иконки поворотов"
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "стрелка к точке финиша"
        return "манёвр, lane и серая дорога"
    }

    private fun navigationOutputMainHint(mode: Int): String {
        val source: String = AppSettings.navSourceLabel(this)
        val time = AppSettings.navEtaTimeModeLabel(this).lowercase(Locale.ROOT)
        if (mode == NavigationOutputMode.TBT) return source + " · дорогу не трогать · " + time
        if (mode == NavigationOutputMode.FINISH_DIRECTION) {
            return (source +
                " · старт " +
                navFinishLeadText(AppSettings.navFinishDirectionLeadMeters(this)) +
                " · маршрут скрыт")
        }
        return (source +
            " · " +
            navigationTextOutputText(AppSettings.navTextMode(this)) +
            " · " +
            time)
    }

    private fun navigationSourceOutputHint(): String {
        val mode: Int = AppSettings.navSourceMode(this)
        if (mode == AppSettings.NAV_SOURCE_YANDEX) return "только Yandex"
        if (mode == AppSettings.NAV_SOURCE_2GIS) return "только 2GIS"
        if (mode == AppSettings.NAV_SOURCE_MVITE) return "только MVite"
        return "Yandex и 2GIS"
    }

    private fun navigationTextTileTitle(mode: Int): String {
        if (mode == NavigationOutputMode.TBT) return "Дорога"
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "Старт"
        return "Строка"
    }

    private fun navigationTextTileValue(mode: Int): String {
        if (mode == NavigationOutputMode.TBT) return "не трогать"
        if (mode == NavigationOutputMode.FINISH_DIRECTION) {
            return navFinishLeadText(AppSettings.navFinishDirectionLeadMeters(this))
        }
        return navigationTextOutputText(AppSettings.navTextMode(this))
    }

    private fun navigationTextTileHint(mode: Int): String {
        if (mode == NavigationOutputMode.TBT) return "без подмены обычного экрана"
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "когда включать стрелку"
        return "что писать на панели"
    }

    private fun navigationAssistTileTitle(mode: Int): String {
        if (mode == NavigationOutputMode.TBT) return "Время"
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "Маршрут"
        return "Дополнительно"
    }

    private fun navigationAssistTileValue(mode: Int): String {
        if (mode == NavigationOutputMode.TBT) {
            return AppSettings.navEtaTimeModeLabel(this).lowercase(Locale.ROOT)
        }
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "скрыт"
        return navigationNormalExtraText()
    }

    private fun navigationAssistTileHint(mode: Int): String {
        if (mode == NavigationOutputMode.TBT) return "ETA в TBT"
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return "заменён стрелкой"
        val main: String =
            if (AppSettings.yandexNavigationEnabled(this) && AppSettings.navStraightUntilMain(this))
                "основной за " + AppSettings.navMainRevealDistanceMeters(this) + " м"
            else "основной сразу"
        val micro: String =
            if (AppSettings.navMicroManeuvers(this))
                "микро до " + AppSettings.navMicroMaxDistanceMeters(this) + " м"
            else "микро выкл"
        return main + " · " + micro
    }

    private fun navigationAssistTileColor(mode: Int): Int {
        if (mode == NavigationOutputMode.TBT) return MainActivity.COLOR_WARNING
        if (mode == NavigationOutputMode.FINISH_DIRECTION) return MainActivity.COLOR_ACCENT
        return if (AppSettings.navOverspeedTextEnabled(this)) MainActivity.COLOR_WARNING
        else MainActivity.COLOR_ACCENT
    }

    private fun navigationAssistTileActive(mode: Int): Boolean {
        return true
    }

    private fun navigationNormalExtraText(): String {
        var speed: String =
            if (AppSettings.navOverspeedTextEnabled(this)) "warning" else "знак скорости"
        if (AppSettings.navStraightUntilMain(this)) speed += " + прямо"
        if (AppSettings.navMicroManeuvers(this)) speed += " + ассистент"
        return speed
    }

    private fun navigationTextOutputText(mode: Int): String {
        when (mode) {
            1 -> return "после манёвра"
            2 -> return "финиш"
            0 -> return "текущая улица"
            else -> return "текущая улица"
        }
    }

    private fun navigationAssistantOutputText(): String {
        if (!AppSettings.navMicroManeuvers(this)) return "выключен"
        return ("подсказка до " +
            AppSettings.navMicroMaxDistanceMeters(this) +
            " м, держать " +
            AppSettings.navMicroHoldSeconds(this) +
            "с")
    }

    private fun navNormalSettingsEnabled(): Boolean {
        return AppSettings.navOutputMode(this) == NavigationOutputMode.NORMAL
    }

    private fun navigationOptionsPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Опции панели",
            "навигационные подсказки и ограничение скорости",
            MainActivity.COLOR_ACCENT_BLUE,
        )
        addSettingsSubHeader(panel, "Режим маршрута", "активен только один режим")
        val normalMode: Boolean = navNormalSettingsEnabled()
        addActionGridColumns(
            panel,
            3,
            navRouteModeAction(NavigationOutputMode.NORMAL, "Обычный", "манёвр + серая дорога"),
            navRouteModeAction(NavigationOutputMode.TBT, "TBT", "отдельные TBT-иконки"),
            navRouteModeAction(
                NavigationOutputMode.FINISH_DIRECTION,
                "К флагу",
                "стрелка к точке финиша",
            ),
        )
        finishCompassAutoToggle =
            addSettingsPanelSwitchHeader(
                panel,
                "Компас к финишу",
                "300 м всегда, до 1 км после последнего манёвра",
                AppSettings.navFinishCompassAuto(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleFinishCompassAuto(button, enabled)
                },
            )
        addInlineSwitch(
            panel,
            "Компас Kia Navi",
            "16 секторов для совместимой прошивки адаптера",
            AppSettings.compassKiaNaviFormat(this),
            CompoundButton.OnCheckedChangeListener { button, checked ->
                AppSettings.setCompassKiaNaviFormat(this, checked)
            },
        )
        panel.addView(settingsDivider())
        addSettingsSubHeader(
            panel,
            "Ограничение скорости в приборке",
            "Передаётся известный лимит при изменении. Пустые значения не отправляются.",
        )
        val speedLimitRouteOnly: Boolean = AppSettings.navSpeedLimitRouteOnly(this)
        addActionGridColumns(
            panel,
            1,
            action(
                "Только при активном маршруте",
                "во время ведения по маршруту",
                choiceColor(speedLimitRouteOnly),
                speedLimitRouteOnly,
                Runnable { setNavSpeedLimitRouteOnly(true) },
            ),
            action(
                "Всегда, когда известен лимит",
                "в том числе без активного маршрута",
                choiceColor(!speedLimitRouteOnly),
                !speedLimitRouteOnly,
                Runnable { setNavSpeedLimitRouteOnly(false) },
            ),
        )
        addInlineSwitch(
            panel,
            "Повторять ограничение каждые 15 секунд",
            "Периодически отправлять известный лимит в приборку",
            AppSettings.navSpeedLimitRepeatEnabled(this),
            CompoundButton.OnCheckedChangeListener { button, checked ->
                setNavSpeedLimitRepeatEnabled(checked)
            },
        )
        panel.addView(settingsDivider())
        addSettingsSubHeader(
            panel,
            "1. Настройки основной навигации",
            if (normalMode) "манёвр маршрута, серая дорога и текстовые строки"
            else "доступно только в режиме маршрута Обычный",
        )
        val yandexNormalMode: Boolean = normalMode && AppSettings.yandexNavigationEnabled(this)
        addActionGrid(
            panel,
            navSettingsAction(navAddressAction(0, "Улица сейчас", "текущая улица"), normalMode),
            navSettingsAction(
                navAddressAction(1, "После манёвра", "улица после поворота"),
                normalMode,
            ),
            navSettingsAction(
                navAddressAction(2, "Улица финиша", "адрес или место назначения"),
                normalMode,
            ),
            navSettingsAction(navEtaTimeModeAction()!!, normalMode),
            navSettingsAction(
                (action(
                    if (AppSettings.navOverspeedTextEnabled(this)) "Превышение: вкл"
                    else "Превышение: выкл",
                    "предупреждение на панели",
                    choiceColor(AppSettings.navOverspeedTextEnabled(this)),
                    AppSettings.navOverspeedTextEnabled(this),
                    Runnable {
                        toggleOverspeedText(null, !AppSettings.navOverspeedTextEnabled(this))
                        renderTab()
                        refresh()
                    },
                )),
                normalMode,
            ),
        )
        straightUntilMainToggle =
            addSettingsPanelSwitchHeader(
                panel,
                "До манёвра показывать прямо · Яндекс",
                if (yandexNormalMode)
                    "основной знак появится только за выбранное расстояние; микроподсказки сохраняются"
                else if (!normalMode) "доступно только в режиме маршрута Обычный"
                else "доступно для источника Яндекс",
                AppSettings.navStraightUntilMain(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleStraightUntilMain(button, enabled)
                },
            )
        straightUntilMainToggle!!.setEnabled(yandexNormalMode)
        straightUntilMainToggle!!.setAlpha(if (yandexNormalMode) 1f else 0.45f)
        addActionGrid(panel, navMainRevealDistanceCycleAction(yandexNormalMode))
        panel.addView(settingsDivider())
        addSettingsSubHeader(
            panel,
            "2. Ассистент манёвров",
            if (normalMode) "короткая жёлтая подсказка перед сложным манёвром"
            else "доступно только в режиме маршрута Обычный",
        )
        microManeuverToggle =
            addSettingsPanelSwitchHeader(
                panel,
                "Ассистент подсказок",
                if (normalMode) "может заменить основной манёвр рядом с lane-подсказкой"
                else "сначала выберите режим маршрута Обычный",
                AppSettings.navMicroManeuvers(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleMicroManeuvers(button, enabled)
                },
            )
        microManeuverToggle!!.setEnabled(normalMode)
        microManeuverToggle!!.setAlpha(if (normalMode) 1f else 0.45f)
        addActionGrid(
            panel,
            navMicroDistanceCycleAction(normalMode),
            navMicroHoldAfterCycleAction(normalMode),
        )
        return panel
    }

    private fun renderAdasSettingsTab(root: LinearLayout) {
        val controller = AdasController.get(this)
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelSwitchHeader(
            panel,
            "Подключаться к TEYES ADAS",
            "Получать от штатного приложения данные о разметке и выезде из полосы",
            AppSettings.adasEnabled(this),
            CompoundButton.OnCheckedChangeListener { button, enabled ->
                AppSettings.setAdasEnabled(this, enabled)
                if (enabled) AppService.start(this)
                controller.applySettings()
                refreshAdasPanel()
            },
        )
        adasStatus = adasStatusView(panel)
        root.addView(panel)

        val diagnostics: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT)
        addSettingsPanelHeader(
            diagnostics,
            "Диагностика ADAS",
            "Проверка на 10 секунд доступна и при выключенном постоянном подключении",
            MainActivity.COLOR_ACCENT,
        )
        adasDiagnosticStatus = adasStatusView(diagnostics)
        adasDiagnosticButton =
            action(
                "Диагностика",
                "проверить связь и приём событий",
                MainActivity.COLOR_SUCCESS,
                Runnable {
                    controller.diagnose()
                    refreshAdasPanel()
                },
            )
        addActionGrid(
            diagnostics,
            adasDiagnosticButton,
            action(
                "Скопировать отчёт",
                "состояние связи и данные последней проверки",
                MainActivity.COLOR_ACCENT_BLUE,
                Runnable { this.copyAdasReport() },
            ),
        )
        root.addView(diagnostics)
        refreshAdasPanel()
    }

    private fun adasStatusView(panel: LinearLayout): TextView {
        val view: TextView = text("", if (isCompact()) 14 else 16, MainActivity.COLOR_TEXT)
        view.setLineSpacing(dp(3).toFloat(), 1f)
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(10), 0, dp(8))
        panel.addView(view, lp)
        return view
    }

    private fun refreshAdasPanel() {
        if (adasStatus == null || adasDiagnosticButton == null) return
        val state: kia.app.adas.AdasController.Snapshot = AdasController.get(this).snapshot()
        val statusText: String =
            ("Приложение · " +
                state.packageStatus +
                "\nПодключение · " +
                state.connectionStatus +
                "\nСобытия · " +
                state.eventStatus +
                "\nРазметка · " +
                state.laneStatus +
                "\nВыезд по последнему сообщению · " +
                state.warningStatus)
        if (!statusText.contentEquals(adasStatus!!.getText())) adasStatus!!.setText(statusText)
        if (!state.diagnosticStatus.contentEquals(adasDiagnosticStatus!!.getText())) {
            adasDiagnosticStatus!!.setText(state.diagnosticStatus)
        }
        adasDiagnosticButton!!.setEnabled(!state.diagnosticRunning)
        adasDiagnosticButton!!.setAlpha(if (state.diagnosticRunning) 0.6f else 1f)
    }

    private fun copyAdasReport() {
        val clipboard: ClipboardManager? = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager?
        if (clipboard == null) return
        clipboard.setPrimaryClip(
            ClipData.newPlainText("TEYES ADAS", AdasController.get(this).report())
        )
        Toast.makeText(this, "Отчёт ADAS скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun renderCanbusTab(root: LinearLayout = tabContent!!) {
        if (AppSettings.ampEnabled(this)) requestAmpSettingsIfNeeded()
        root.addView(canbusAdapterPanel())
        root.addView(canbusTemperaturePanel())
        if (AppSettings.expertMode(this)) {
            sasRatioStatus = text("", 14, Color.rgb(235, 241, 246))
            sasRatioInput = sasRatioInput()
            root.addView(sasRatioPanel())
            addAmpSection(root)
        }
    }

    private fun canbusAdapterPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Адаптер Canbus",
            "подключение USB-адаптера, ID, версия прошивки и последний RX",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        diagnosticsStatus =
            text(canbusStatusText(), if (isCompact()) 14 else 16, Color.rgb(235, 241, 246))
        diagnosticsStatus!!.setTypeface(Typeface.DEFAULT_BOLD)
        val statusLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        statusLp.setMargins(0, dp(12), 0, 0)
        panel.addView(diagnosticsStatus, statusLp)
        return panel
    }

    private fun canbusTemperaturePanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Температура на панели",
            "выберите значение, которое автомобиль покажет как температуру",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        addActionGrid(
            panel,
            action(
                "Наружная",
                "температура за бортом",
                canbusTempSourceColor(AppSettings.CANBUS_TEMP_OUTSIDE),
                AppSettings.canbusTemperatureSource(this) == AppSettings.CANBUS_TEMP_OUTSIDE,
                Runnable { setCanbusTemperatureSource(AppSettings.CANBUS_TEMP_OUTSIDE) },
            ),
            action(
                "Двигатель",
                "температура двигателя",
                canbusTempSourceColor(AppSettings.CANBUS_TEMP_ENGINE),
                AppSettings.canbusTemperatureSource(this) == AppSettings.CANBUS_TEMP_ENGINE,
                Runnable { setCanbusTemperatureSource(AppSettings.CANBUS_TEMP_ENGINE) },
            ),
        )
        return panel
    }

    private fun renderSettingsTab() {
        renderGeneralSettingsTab(tabContent!!)
    }

    private fun renderTpmsSettingsTab(root: LinearLayout) {
        root.addView(tpmsThresholdPanel())
        root.addView(tpmsSettingsAlertsPanel())
        root.addView(teyesTpmsWidgetPanel())
    }

    private fun teyesTpmsWidgetPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Виджет TEYES",
            "Давление из KIA в штатном виджете на главном экране",
            MainActivity.COLOR_ACCENT_BLUE,
        )
        addInlineSwitch(
            panel,
            "Передавать давление в виджет TEYES",
            "Давление в Bar; температура пока показывается как «__»",
            AppSettings.teyesTpmsWidgetForwardEnabled(this),
            CompoundButton.OnCheckedChangeListener { button, checked ->
                AppSettings.setTeyesTpmsWidgetForwardEnabled(this, checked)
                if (checked) AppService.start(this)
                TeyesTpmsWidgetForwarder.get(this).applySettings()
                refresh()
            },
        )
        val hint: TextView =
            text(
                ("Пока включено, источник виджета — USB-адаптер KIA. " +
                    "Добавьте штатный виджет TEYES на главный экран. " +
                    "При принудительной остановке KIA последнее значение может остаться в виджете."),
                if (isCompact()) 13 else 15,
                MainActivity.COLOR_MUTED,
            )
        val hintLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        hintLp.setMargins(0, dp(10), 0, 0)
        panel.addView(hint, hintLp)
        return panel
    }

    private fun renderGeneralSettingsTab(root: LinearLayout) {
        root.addView(generalHealthPanel())
        root.addView(sunroofSettingsPanel())
        addPermissionSection(root)
        updatesPanelView = generalUpdatesPanel()
        root.addView(updatesPanelView)
        if (focusUpdatesAfterRender) {
            focusUpdatesAfterRender = false
            val scrollToUpdates: Runnable = Runnable {
                if (activeScrollView == null) {
                    AppLog.line(this, "Update notification focus: scroll unavailable")
                    return@Runnable
                }
                val currentPanel: LinearLayout? = updatesPanelView
                if (currentPanel == null || !currentPanel.isAttachedToWindow()) {
                    AppLog.line(this, "Update notification focus: panel unavailable")
                    return@Runnable
                }
                val target: Int = Math.max(0, currentPanel.getTop() - dp(12))
                AppLog.line(
                    this,
                    ("Update notification focus: panelTop=" +
                        currentPanel.getTop() +
                        " target=" +
                        target +
                        " before=" +
                        activeScrollView!!.getScrollY()),
                )
                settingsScrollY = target
                activeScrollView!!.scrollTo(0, target)
            }
            handler.postDelayed(scrollToUpdates, 120L)
            handler.postDelayed(scrollToUpdates, 450L)
        }
        root.addView(generalDataPanel())
        root.addView(generalAppPanel())
        root.addView(mediaVisibilityPanel())
        if (AppSettings.expertMode(this)) {
            root.addView(firmwarePanel())
        }
        root.addView(generalVersionFooter())
    }

    private fun sunroofSettingsPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelSwitchHeader(
            panel,
            "Люк",
            "анимация белого Sportage поверх главного экрана",
            AppSettings.sunroofOverlayEnabled(this),
            CompoundButton.OnCheckedChangeListener { button, checked ->
                AppSettings.setSunroofOverlayEnabled(this, checked)
                if (checked) AppService.start(this)
                SunroofOverlayController.get(this).apply()
                if (checked && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(
                            this,
                            "Разрешите показ поверх других приложений",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
            },
        )
        addInlineSwitch(
            panel,
            "Предупреждать после выключения зажигания",
            "если люк открыт — показывать до касания, кнопки или закрытия люка",
            AppSettings.sunroofParkingWarningEnabled(this),
            CompoundButton.OnCheckedChangeListener { button, checked ->
                AppSettings.setSunroofParkingWarningEnabled(this, checked)
                SunroofOverlayController.get(this).apply()
            },
        )
        addInlineSwitch(
            panel,
            "Звук открытия и закрытия",
            "голос «Люк открыт / Люк закрыт»; звук предупреждения работает всегда",
            AppSettings.sunroofTransitionSoundEnabled(this),
            CompoundButton.OnCheckedChangeListener { button, checked ->
                AppSettings.setSunroofTransitionSoundEnabled(this, checked)
                SunroofOverlayController.get(this).apply()
            },
        )
        return panel
    }

    private fun generalHealthPanel(): LinearLayout {
        val panel: LinearLayout =
            settingsPanel(
                if (StateStore.adapter().usbConnected) MainActivity.COLOR_SUCCESS
                else MainActivity.COLOR_WARNING
            )
        addSettingsPanelHeader(
            panel,
            "Состояние системы",
            "один экран для адаптера, навигации, медиа и разрешений",
            if (StateStore.adapter().usbConnected) MainActivity.COLOR_SUCCESS
            else MainActivity.COLOR_WARNING,
        )
        healthSummary =
            text(healthSummaryText(), if (isCompact()) 13 else 15, Color.rgb(235, 241, 246))
        healthSummary!!.setTypeface(Typeface.DEFAULT_BOLD)
        healthSummary!!.setLineSpacing(0f, 1.08f)
        val statusLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        statusLp.setMargins(0, dp(12), 0, dp(8))
        panel.addView(healthSummary, statusLp)
        addActionGrid(
            panel,
            action(
                "Самопроверка",
                "обновить сервисы и состояния",
                MainActivity.COLOR_SUCCESS,
                Runnable { this.runSystemSelfCheck() },
            ),
            action(
                "Краткий отчёт",
                "скопировать без адресов и названий треков",
                MainActivity.COLOR_ACCENT_BLUE,
                Runnable { this.copyShortDiagnostic() },
            ),
        )
        return panel
    }

    private fun healthSummaryText(): String {
        val now: Long = System.currentTimeMillis()
        val adapter = StateStore.adapter()
        val navigation = StateStore.navigation()
        val media = StateStore.media()
        val tpms = StateStore.tpms()
        val rcta = StateStore.rcta()
        return (healthLine(
            "Адаптер",
            adapter.usbConnected,
            if (adapter.usbConnected) "RX " + MainActivity.ageText(now, adapter.lastFrameAt)
            else adapter.usbText,
        ) +
            "\n" +
            healthLine(
                "Yandex / маршрут",
                navigation.active || !AppSettings.navigationEnabled(this),
                if (navigation.active) "данные " + MainActivity.ageText(now, navigation.updatedAt)
                else "маршрут не активен",
            ) +
            "\n" +
            healthLine(
                "Медиа",
                !AppSettings.mediaEnabled(this) || media.updatedAt > 0L,
                AppSettings.mediaProfileLabel(this).toString() +
                    " · " +
                    (if (media.updatedAt > 0L) MainActivity.ageText(now, media.updatedAt)
                    else "нет данных"),
            ) +
            "\n" +
            healthLine(
                "TPMS",
                tpms.hasFreshData(),
                if (tpms.hasFreshData()) "данные " + MainActivity.ageText(now, tpms.updatedAt)
                else if (tpms.hasData()) "данные устарели" else "ожидание датчиков",
            ) +
            "\n" +
            healthLine(
                "RCTA",
                true,
                if (rcta.active())
                    rcta.summary().toString() + " · " + MainActivity.ageText(now, rcta.updatedAt)
                else "готов",
            ) +
            "\n" +
            healthLine(
                "Разрешения",
                missingPermissionCount() == 0,
                if (missingPermissionCount() == 0) "всё готово"
                else "нужно включить " + missingPermissionCount(),
            ))
    }

    private fun healthLine(title: String?, ok: Boolean, details: String?): String {
        return (if (ok) "✓ " else "! ") + title + ": " + details
    }

    private fun runSystemSelfCheck() {
        AppService.start(this)
        NavigationOverlayController.get(this).apply()
        RctaOverlayController.get(this).apply()
        SunroofOverlayController.get(this).apply()
        TpmsWarningOverlayController.get(this).setActivityVisible(activityVisible)
        AppLog.line(this, "Self-check requested from UI")
        Toast.makeText(this, "Состояния обновлены", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ this.refresh() }, 600L)
    }

    private fun copyShortDiagnostic() {
        val clipboard: ClipboardManager? =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        if (clipboard == null) return
        clipboard.setPrimaryClip(
            ClipData.newPlainText("KIA diagnostic", DiagnosticBundleExporter.summary(this))
        )
        Toast.makeText(this, "Краткий отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun generalDataPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Данные и диагностика",
            "перенос настроек и один архив для разбора проблемы",
            MainActivity.COLOR_ACCENT_BLUE,
        )
        addActionGrid(
            panel,
            action(
                "Сохранить настройки",
                "включая ручные названия радио",
                MainActivity.COLOR_ACCENT_BLUE,
                Runnable { this.openSettingsExport() },
            ),
            action(
                "Восстановить",
                "проверить и импортировать JSON",
                MainActivity.COLOR_ACCENT_BLUE,
                Runnable { this.openSettingsImport() },
            ),
            action(
                "Сохранить проблему",
                "ZIP: состояния, настройки и ограниченные логи",
                MainActivity.COLOR_WARNING,
                Runnable { this.openDiagnosticsExport() },
            ),
        )
        val privacy: TextView =
            text(
                "Архив создаётся только по нажатию. Он может содержать служебные " +
                    "строки навигационного журнала; автоматически никуда не отправляется.",
                if (isCompact()) 11 else 12,
                MainActivity.COLOR_MUTED,
            )
        val privacyLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        privacyLp.setMargins(0, dp(8), 0, 0)
        panel.addView(privacy, privacyLp)
        return panel
    }

    private fun openSettingsExport() {
        openCreateDocument(
            MainActivity.REQUEST_EXPORT_SETTINGS,
            "application/json",
            SettingsTransfer.FILE_NAME,
        )
    }

    private fun openSettingsImport() {
        val intent: Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("application/json")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, MainActivity.REQUEST_IMPORT_SETTINGS)
        } catch (e: Exception) {
            Toast.makeText(this, "Не найден выбор файлов", Toast.LENGTH_LONG).show()
        }
    }

    private fun openDiagnosticsExport() {
        openCreateDocument(
            MainActivity.REQUEST_DIAGNOSTICS_FILE,
            "application/zip",
            DiagnosticBundleExporter.suggestedFileName(),
        )
    }

    private fun openCreateDocument(requestCode: Int, type: String?, fileName: String?) {
        val intent: Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType(type)
        intent.putExtra(Intent.EXTRA_TITLE, fileName)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Toast.makeText(this, "Не найден выбор файлов", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderRctaSettingsTab(root: LinearLayout) {
        val teyesStyle: Boolean = AppSettings.rctaStyle(this) == AppSettings.RCTA_STYLE_TEYES
        val voiceMode: Boolean = AppSettings.rctaSoundMode(this) == AppSettings.RCTA_SOUND_VOICE
        val preview: LinearLayout = settingsPanel(MainActivity.COLOR_WARNING)
        addSettingsPanelHeader(
            preview,
            "RCTA",
            "preview поверх камеры: чистый центр, предупреждение только по бокам",
            MainActivity.COLOR_WARNING,
        )

        val stage: FrameLayout = FrameLayout(this)
        stage.setBackground(settingsInsetBackground())
        val overlay: BlindSpotOverlayView = BlindSpotOverlayView(this)
        rctaPreview = overlay
        overlay.setStyleType(AppSettings.rctaStyle(this))
        overlay.setAlertColor(AppSettings.rctaColor(this))
        overlay.setBackgroundAlpha(AppSettings.rctaBackgroundAlpha(this))
        overlay.setArrowCount(AppSettings.rctaArrowCount(this))
        overlay.setPreview(true, true, false)
        overlay.setBottomLiftDp(0)
        stage.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val stageLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rctaPreviewHeight())
        stageLp.setMargins(0, dp(12), 0, dp(10))
        preview.addView(stage, stageLp)

        val appearance: String =
            if (teyesStyle) "TEYES · красная пульсация по стороне опасности"
            else
                (AppSettings.rctaStyleLabel(this).toString() +
                    " · " +
                    AppSettings.rctaColorLabel(this) +
                    " · фон " +
                    rctaBackgroundPercent(AppSettings.rctaBackgroundAlpha(this)) +
                    "% · стрелок " +
                    AppSettings.rctaArrowCount(this))
        val status: TextView =
            text(
                (if (AppSettings.expertMode(this))
                    "CAN: BB A1 41 08 75 RL RR · 00 нет · 01 предупреждение · "
                else "Предпросмотр: левая и правая опасная зона · ") + appearance,
                if (isCompact()) 13 else 15,
                MainActivity.COLOR_MUTED,
            )
        preview.addView(status)
        root.addView(preview)

        val settings: LinearLayout = settingsPanel(MainActivity.COLOR_WARNING)
        addSettingsPanelHeader(
            settings,
            "Настройки RCTA",
            "эти параметры сохраняются сейчас и будут использоваться после подключения событий",
            MainActivity.COLOR_WARNING,
        )
        addSettingsSubHeader(
            settings,
            "Тип анимации",
            "Тип 1 — угловые линии; Тип 2 — стрелки; TEYES — пульсация по краям",
        )
        addActionGrid(
            settings,
            action(
                "Тип 1",
                "угловые линии + стрелки",
                rctaStyleColor(AppSettings.RCTA_STYLE_TYPE_1),
                AppSettings.rctaStyle(this) == AppSettings.RCTA_STYLE_TYPE_1,
                Runnable { setRctaStyle(AppSettings.RCTA_STYLE_TYPE_1) },
            ),
            action(
                "Тип 2",
                "без линий, ближе к углам",
                rctaStyleColor(AppSettings.RCTA_STYLE_TYPE_2),
                AppSettings.rctaStyle(this) == AppSettings.RCTA_STYLE_TYPE_2,
                Runnable { setRctaStyle(AppSettings.RCTA_STYLE_TYPE_2) },
            ),
            action(
                "TEYES",
                "оригинальная красная пульсация",
                rctaStyleColor(AppSettings.RCTA_STYLE_TEYES),
                teyesStyle,
                Runnable { setRctaStyle(AppSettings.RCTA_STYLE_TEYES) },
            ),
        )
        if (teyesStyle) {
            addSettingsSubHeader(
                settings,
                "Стиль TEYES",
                "Оригинальные цвет и форма. Ниже можно выбрать стереосигнал или голосовое предупреждение.",
            )
        } else {
            settings.addView(settingsDivider())
            addSettingsSubHeader(
                settings,
                "Цвет предупреждения",
                "меняется только цвет RCTA; геометрия стрелок и углов остаётся плотной",
            )
            addActionGrid(
                settings,
                action(
                    "Янтарный",
                    "штатный вид",
                    rctaColorChoice(AppSettings.RCTA_COLOR_AMBER),
                    AppSettings.rctaColor(this) == AppSettings.RCTA_COLOR_AMBER,
                    Runnable { setRctaColor(AppSettings.RCTA_COLOR_AMBER) },
                ),
                action(
                    "Красный",
                    "самый заметный",
                    rctaColorChoice(AppSettings.RCTA_COLOR_RED),
                    AppSettings.rctaColor(this) == AppSettings.RCTA_COLOR_RED,
                    Runnable { setRctaColor(AppSettings.RCTA_COLOR_RED) },
                ),
                action(
                    "Голубой",
                    "холодный акцент",
                    rctaColorChoice(AppSettings.RCTA_COLOR_CYAN),
                    AppSettings.rctaColor(this) == AppSettings.RCTA_COLOR_CYAN,
                    Runnable { setRctaColor(AppSettings.RCTA_COLOR_CYAN) },
                ),
                action(
                    "Зелёный",
                    "мягкий акцент",
                    rctaColorChoice(AppSettings.RCTA_COLOR_GREEN),
                    AppSettings.rctaColor(this) == AppSettings.RCTA_COLOR_GREEN,
                    Runnable { setRctaColor(AppSettings.RCTA_COLOR_GREEN) },
                ),
            )
            settings.addView(rctaArrowCountSlider())
            settings.addView(rctaBackgroundAlphaSlider())
        }
        settings.addView(settingsDivider())
        addInlineSwitch(
            settings,
            "Показывать предупреждение",
            "анимация слева/справа поверх экрана заднего хода",
            AppSettings.rctaOverlayEnabled(this),
            CompoundButton.OnCheckedChangeListener { button, checked ->
                AppSettings.setRctaOverlayEnabled(this, checked)
                if (checked && !RctaOverlayController.canDrawOverlays(this)) {
                    AppLog.line(this, "RCTA overlay: открой разрешение поверх окон")
                    val intent: Intent =
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()),
                        )
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        AppLog.line(
                            this,
                            "RCTA overlay settings failed " + e.javaClass.getSimpleName(),
                        )
                    }
                }
                RctaOverlayController.get(this).apply()
                refresh()
            },
        )
        settings.addView(settingsDivider())
        addSettingsSubHeader(
            settings,
            "Тип звукового предупреждения",
            "Для любого стиля RCTA: сигнал или русская голосовая подсказка о стороне опасности",
        )
        addActionGrid(
            settings,
            action(
                "Сигнал",
                "TEYES: по стороне опасности",
                choiceColor(!voiceMode),
                !voiceMode,
                Runnable { setRctaSoundMode(AppSettings.RCTA_SOUND_TONE) },
            ),
            action(
                "Голос",
                "русские подсказки слева / справа",
                choiceColor(voiceMode),
                voiceMode,
                Runnable { setRctaSoundMode(AppSettings.RCTA_SOUND_VOICE) },
            ),
        )
        if (voiceMode) {
            addSettingsSubHeader(
                settings,
                "Голосовые подсказки",
                "При опасности с обеих сторон фразы звучат по очереди, без наложения.",
            )
        }
        settings.addView(settingsDivider())
        addInlineSwitch(
            settings,
            "Звуковое предупреждение",
            if (voiceMode) "голосовая подсказка RCTA после появления реального события"
            else "звуковой сигнал RCTA после появления реального события",
            AppSettings.rctaSoundEnabled(this),
            CompoundButton.OnCheckedChangeListener { button, checked ->
                AppSettings.setRctaSoundEnabled(this, checked)
                RctaOverlayController.get(this).apply()
                if (rctaDemoOverlay != null) applyRctaDemoSound()
                refresh()
            },
        )
        settings.addView(settingsDivider())
        addInlineSwitch(
            settings,
            "Панель диагностики",
            if (teyesStyle || voiceMode)
                "показывает предупреждения с обеих сторон; звук следует переключателю выше"
            else "сразу показывает RCTA слева и справа на весь экран со звуком, пока не выключить",
            rctaDiagnosticsActive,
            CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean ->
                this.toggleRctaDiagnostics(button, enabled)
            },
        )
        root.addView(settings)
    }

    private fun toggleRctaDiagnostics(button: CompoundButton?, enabled: Boolean) {
        rctaDiagnosticsActive = enabled
        cancelRctaDemoSequence()
        if (enabled) {
            showRctaDemoAlert(true, true, true)
        } else {
            hideRctaDemoAlert()
        }
        AppLog.line(this, "RCTA diagnostics panel: " + enabled)
        refresh()
    }

    private fun setRctaStyle(style: Int) {
        AppSettings.setRctaStyle(this, style)
        if (rctaPreview != null) {
            rctaPreview!!.setStyleType(AppSettings.rctaStyle(this))
        }
        RctaOverlayController.get(this).apply()
        AppLog.line(this, "RCTA style: " + AppSettings.rctaStyleLabel(this))
        renderTab()
        if (rctaDemoOverlay != null && !rctaDiagnosticsActive) {
            showRctaDemoAlert(rctaDemoLeftActive, rctaDemoRightActive, rctaDemoForceSound)
        }
        refresh()
    }

    private fun setRctaSoundMode(mode: Int) {
        AppSettings.setRctaSoundMode(this, mode)
        RctaOverlayController.get(this).apply()
        AppLog.line(this, "RCTA sound mode: " + AppSettings.rctaSoundModeLabel(this))
        renderTab()
        if (rctaDemoOverlay != null && !rctaDiagnosticsActive) {
            showRctaDemoAlert(rctaDemoLeftActive, rctaDemoRightActive, rctaDemoForceSound)
        }
        refresh()
    }

    private fun setRctaColor(color: Int) {
        AppSettings.setRctaColor(this, color)
        if (rctaPreview != null) {
            rctaPreview!!.setAlertColor(AppSettings.rctaColor(this))
        }
        RctaOverlayController.get(this).apply()
        AppLog.line(this, "RCTA color: " + AppSettings.rctaColorLabel(this))
        renderTab()
        refresh()
    }

    private fun setRctaArrowCount(value: Int) {
        AppSettings.setRctaArrowCount(this, value)
        if (rctaPreview != null) {
            rctaPreview!!.setArrowCount(AppSettings.rctaArrowCount(this))
        }
        RctaOverlayController.get(this).apply()
        AppLog.line(this, "RCTA arrows: " + AppSettings.rctaArrowCount(this))
        refresh()
    }

    private fun rctaPreviewHeight(): Int {
        if (isLandscapeWindow()) {
            return dp(if (screenWidthDp() >= 1100) 220 else 200)
        }
        return if (isCompact()) dp(180) else dp(220)
    }

    private fun rctaStyleColor(style: Int): Int {
        return choiceColor(AppSettings.rctaStyle(this) == style)
    }

    private fun rctaColorChoice(color: Int): Int {
        return choiceColor(AppSettings.rctaColor(this) == color)
    }

    private fun rctaArrowCountSlider(): View {
        val box: LinearLayout = rctaSliderBox()
        val title: TextView = text("Количество стрелок", if (isCompact()) 15 else 17, Color.WHITE)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        val value: TextView =
            text(
                rctaArrowCountText(AppSettings.rctaArrowCount(this)),
                if (isCompact()) 12 else 14,
                MainActivity.COLOR_MUTED,
            )

        val slider: SeekBar = SeekBar(this)
        styleSettingsSeekBar(slider)
        val min: Int = AppSettings.RCTA_ARROW_COUNT_MIN
        val max: Int = AppSettings.RCTA_ARROW_COUNT_MAX
        slider.setMax(max - min)
        slider.setProgress(AppSettings.rctaArrowCount(this) - min)
        slider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    val next: Int = min + progress
                    setRctaArrowCount(next)
                    value.setText(rctaArrowCountText(AppSettings.rctaArrowCount(this@MainActivity)))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        box.addView(title)
        box.addView(value)
        val sliderLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        sliderLp.setMargins(0, dp(6), 0, 0)
        box.addView(slider, sliderLp)
        return box
    }

    private fun rctaBackgroundAlphaSlider(): View {
        val box: LinearLayout = rctaSliderBox()

        val title: TextView = text("Прозрачность фона", if (isCompact()) 15 else 17, Color.WHITE)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        val value: TextView =
            text(
                rctaBackgroundAlphaText(AppSettings.rctaBackgroundAlpha(this)),
                if (isCompact()) 12 else 14,
                MainActivity.COLOR_MUTED,
            )
        value.setMaxLines(2)

        val slider: SeekBar = SeekBar(this)
        styleSettingsSeekBar(slider)
        val min: Int = AppSettings.RCTA_BACKGROUND_ALPHA_MIN
        val max: Int = AppSettings.RCTA_BACKGROUND_ALPHA_MAX
        slider.setMax(max - min)
        slider.setProgress(AppSettings.rctaBackgroundAlpha(this) - min)
        slider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    val next: Int = min + progress
                    AppSettings.setRctaBackgroundAlpha(this@MainActivity, next)
                    if (rctaPreview != null) {
                        rctaPreview!!.setBackgroundAlpha(
                            AppSettings.rctaBackgroundAlpha(this@MainActivity)
                        )
                    }
                    RctaOverlayController.get(this@MainActivity).apply()
                    value.setText(
                        rctaBackgroundAlphaText(AppSettings.rctaBackgroundAlpha(this@MainActivity))
                    )
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    AppLog.line(
                        this@MainActivity,
                        "RCTA background: " +
                            rctaBackgroundAlphaText(
                                AppSettings.rctaBackgroundAlpha(this@MainActivity)
                            ),
                    )
                    refresh()
                }
            }
        )

        box.addView(title)
        box.addView(value)
        val sliderLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        sliderLp.setMargins(0, dp(6), 0, 0)
        box.addView(slider, sliderLp)
        return box
    }

    private fun rctaSliderBox(): LinearLayout {
        val box: LinearLayout = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setPadding(dp(16), dp(12), dp(16), dp(12))
        box.setMinimumHeight(if (isCompact()) dp(78) else dp(96))
        box.setBackground(settingsButtonBackground(false))
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(8), 0, dp(4))
        box.setLayoutParams(lp)
        return box
    }

    private fun styleSettingsSeekBar(slider: SeekBar?) {
        if (Build.VERSION.SDK_INT < 21 || slider == null) return
        slider.setThumbTintList(ColorStateList.valueOf(MainActivity.COLOR_ACCENT_BLUE))
        slider.setProgressTintList(ColorStateList.valueOf(MainActivity.COLOR_ACCENT_BLUE))
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(63, 74, 89)))
    }

    private fun rctaArrowCountText(value: Int): String {
        return MainActivity.clamp(
                value,
                AppSettings.RCTA_ARROW_COUNT_MIN,
                AppSettings.RCTA_ARROW_COUNT_MAX,
            )
            .toString() + " стрелки, диапазон 3-6"
    }

    private fun rctaBackgroundAlphaText(value: Int): String {
        return "фон " + rctaBackgroundPercent(value) + "%, стрелки и углы без прозрачности"
    }

    private fun rctaBackgroundPercent(value: Int): Int {
        val max: Int = Math.max(1, AppSettings.RCTA_BACKGROUND_ALPHA_MAX)
        return Math.round(
            MainActivity.clamp(
                value,
                AppSettings.RCTA_BACKGROUND_ALPHA_MIN,
                AppSettings.RCTA_BACKGROUND_ALPHA_MAX,
            ) * 100f / max
        )
    }

    private fun refresh() {
        if (tpmsDashboard != null && shouldRerenderTpms()) {
            val tires = StateStore.tpms()
            rememberTpmsRenderState(tires)
            tpmsDashboard!!.setState(tires)
        }
        if (status != null) status!!.setText(fullStatusText())
        if (tpmsStatus != null) {
            tpmsStatus!!.setText(tpmsStatusText())
        }
        if (tpmsDashboard != null) {
            tpmsDashboard!!.setWidgetMode(tpmsWidgetMode())
            tpmsDashboard!!.setMotionSpeedKmh(navigationSpeedKmh())
            tpmsDashboard!!.setNavigationState(
                StateStore.navigation(),
                NavigationFeature.get(this).dashboardRoadSpeedLimit(),
            )
            tpmsDashboard!!.setVehicleState(StateStore.vehicle())
        }
        if (navigationStatus != null) {
            navigationStatus!!.setText(navigationStatusText())
        }
        if (navigationDebugStatus != null) {
            navigationDebugStatus!!.setText(navigationDebugText())
        }
        if (mediaDebugStatus != null) {
            mediaDebugStatus!!.setText(mediaDebugText())
        }
        if (diagnosticsStatus != null) {
            diagnosticsStatus!!.setText(canbusStatusText())
        }
        if (adasStatus != null) refreshAdasPanel()
        if (canbusDebugStatus != null) {
            canbusDebugStatus!!.setText(canbusDebugText())
        }
        if (updatesStatus != null) {
            updatesStatus!!.setText(updatesText())
        }
        maybeShowLaunchUpdatePrompt()
        if (firmwareStatus != null) {
            firmwareStatus!!.setText(firmwareStatusText())
        }
        if (permissionSummary != null) {
            permissionSummary!!.setText(permissionSummaryText())
        }
        if (healthSummary != null) {
            healthSummary!!.setText(healthSummaryText())
        }
        if (sasRatioStatus != null) {
            sasRatioStatus!!.setText(sasRatioText())
        }
        if (sasRatioInput != null && !sasRatioInput!!.hasFocus()) {
            sasRatioInput!!.setText(java.lang.String.valueOf(AppSettings.sasRatio(this)))
        }
        updateOverlayToggle()
        updateMicroManeuverToggle()
        updateStraightUntilMainToggle()
        updateNavTbtToggle()
        updateOverspeedToggle()
        updateNavDebugToggle()
        updateCanbusDebugToggle()
        updateAmpEnabledToggle()
        updateMediaTabToggle()
        updateCallEnabledToggle()
        updateMediaDebugToggle()
        updateTpmsAlertsToggle()
        updateTpmsSoundToggle()
        updateAutoStartToggle()
        updateTpmsInputs()
        updateAmpInputs()
        updateFirmwareActionButton()
        if (log != null) log!!.setText(AppLog.lastLines(36))
    }

    private fun fullStatusText(): String {
        return ("" +
            "Версия: " +
            appVersionText() +
            "\n" +
            "Адаптер: " +
            StateStore.adapter().usbText +
            " | UID " +
            StateStore.adapter().uid +
            " | FW " +
            StateStore.adapter().firmware +
            " | RX " +
            timeText(StateStore.adapter().lastFrameAt) +
            " | Health " +
            StateStore.adapter().health +
            "\n" +
            "Разрешения: notify=" +
            MainActivity.yesNo(notificationPermissionGranted()) +
            " listener=" +
            MainActivity.yesNo(notificationListenerEnabled()) +
            " gps=" +
            MainActivity.yesNo(locationPermissionGranted()) +
            "\n" +
            "CAN: SAS ratio=" +
            AppSettings.sasRatio(this) +
            " | автозапуск=" +
            (if (AppSettings.autoStart(this)) "фон" else "выкл") +
            "\n" +
            "AMP: " +
            StateStore.amp().summary() +
            "\n" +
            StateStore.tpms().summary() +
            "\n" +
            "Настройки: " +
            MainActivity.displayStatus(StateStore.updates().appStatus) +
            " | " +
            MainActivity.displayStatus(StateStore.updates().firmwareStatus))
    }

    private fun navigationStatusText(): String {
        return (StateStore.navigation().summary().toString() +
            "\nИсточник: " +
            AppSettings.navSourceLabel(this) +
            "\nАдрес на панели: " +
            navTextModeText(AppSettings.navTextMode(this)) +
            "\nРежим маршрута: " +
            NavigationModeSettings.label(this) +
            "\nПревышение: " +
            MainActivity.yesNo(AppSettings.navOverspeedTextEnabled(this)))
    }

    private fun navigationDebugText(): String {
        return (StateStore.navigation().details().toString() +
            "\nКомпас: " +
            compassStatusText() +
            "\nПоверх экрана: " +
            MainActivity.yesNo(AppSettings.navOverlayEnabled(this)) +
            "\nРежим маршрута: " +
            NavigationModeSettings.label(this) +
            " | превышение: " +
            MainActivity.yesNo(AppSettings.navOverspeedTextEnabled(this)) +
            "\n2GIS: " +
            dgisStatusText())
    }

    private fun canbusStatusText(): String {
        return ((if (StateStore.adapter().usbConnected) "Подключён" else "Не подключён") +
            "  ·  " +
            StateStore.adapter().usbText +
            "\nID: " +
            MainActivity.emptyDash(StateStore.adapter().uid) +
            "  ·  FW: " +
            MainActivity.emptyDash(StateStore.adapter().firmware) +
            "  ·  RX: " +
            timeText(StateStore.adapter().lastFrameAt))
    }

    private fun canbusTemperatureText(): String {
        val vehicle = StateStore.vehicle()
        val outside: String =
            if (vehicle.outsideTempKnown) vehicle.outsideTempC.toString() + "C" else "нет данных"
        val engine: String =
            if (vehicle.engineTempKnown) vehicle.engineTempC.toString() + "C" else "нет данных"
        return ("Выбрано: " +
            AppSettings.canbusTemperatureSourceLabel(this) +
            "\nУлица: " +
            outside +
            "  ·  двигатель: " +
            engine)
    }

    private fun sasRatioText(): String {
        val ratio: Int = AppSettings.sasRatio(this)
        val level: String?
        if (ratio < 8) level = "мягкое смещение"
        else if (ratio <= 18) level = "среднее смещение" else level = "сильное смещение"
        return "ratio " + ratio + " · " + level + " · сохраняется в адаптере"
    }

    private fun canbusDebugText(): String {
        return ("UID: " +
            MainActivity.emptyDash(StateStore.adapter().uid) +
            " | FW: " +
            MainActivity.emptyDash(StateStore.adapter().firmware) +
            " | RX: " +
            timeText(StateStore.adapter().lastFrameAt) +
            " | health: " +
            StateStore.adapter().health +
            "\n" +
            StateStore.vehicle().summary())
    }

    private fun compassStatusText(): String {
        return "авто, во время активного маршрута не перекрывает навигацию"
    }

    private fun appVersionText(): String {
        try {
            val info: android.content.pm.PackageInfo? =
                getPackageManager().getPackageInfo(getPackageName(), 0)
            val code: Long =
                if (Build.VERSION.SDK_INT >= 28) info!!.getLongVersionCode()
                else info!!.versionCode.toLong()
            val name: String = info.versionName.orEmpty()
            return name + " (" + code + ")"
        } catch (ignored: Exception) {
            return "не получена"
        }
    }

    private fun appBuildText(): String {
        return ("Kia " +
            appVersionText() +
            "\nПакет: " +
            getPackageName() +
            "\nAPK: kia_" +
            appVersionCodeText() +
            ".apk")
    }

    private fun appVersionCodeText(): String {
        try {
            val info: android.content.pm.PackageInfo? =
                getPackageManager().getPackageInfo(getPackageName(), 0)
            val code: Long =
                if (Build.VERSION.SDK_INT >= 28) info!!.getLongVersionCode()
                else info!!.versionCode.toLong()
            return java.lang.String.valueOf(code)
        } catch (ignored: Exception) {
            return "?"
        }
    }

    private fun dgisStatusText(): String {
        return ("mode=" +
            AppSettings.navSourceLabel(this) +
            "; dashboard service + уведомления; listener=" +
            MainActivity.yesNo(notificationListenerEnabled()) +
            " notify=" +
            MainActivity.yesNo(notificationPermissionGranted()) +
            " gps=" +
            MainActivity.yesNo(locationPermissionGranted()))
    }

    private fun notificationPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun bluetoothPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun locationPermissionGranted(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun backgroundLocationPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < 29 ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun gpsPermissionReady(): Boolean {
        return locationPermissionGranted() && backgroundLocationPermissionGranted()
    }

    private fun overlayPermissionReady(): Boolean {
        return !overlayRequired() || Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
    }

    private fun overlayRequired(): Boolean {
        return AppSettings.navOverlayEnabled(this) ||
            AppSettings.mediaOverlayEnabled(this) ||
            AppSettings.tpmsAlertsEnabled(this) ||
            AppSettings.rctaOverlayEnabled(this) ||
            AppSettings.sunroofOverlayEnabled(this)
    }

    private fun mediaListenerRequired(): Boolean {
        return AppSettings.universalMediaProfile(this)
    }

    private fun gpsRequired(): Boolean {
        return AppSettings.navigationEnabled(this)
    }

    private fun bluetoothRequired(): Boolean {
        return AppSettings.callEnabled(this)
    }

    private fun notificationListenerEnabled(): Boolean {
        val enabled =
            Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners")
        if (enabled == null) return false
        val flat = ComponentName(this, MediaNotificationListener::class.java).flattenToString()
        return enabled.contains(flat)
    }

    private fun missingPermissionCount(): Int {
        var count: Int = 0
        if (!notificationPermissionGranted()) count++
        if (mediaListenerRequired() && !notificationListenerEnabled()) count++
        if (bluetoothRequired() && !bluetoothPermissionGranted()) count++
        if (gpsRequired() && !gpsPermissionReady()) count++
        if (AppSettings.autoStart(this) && !batteryOptimizationIgnored()) count++
        if (!overlayPermissionReady()) count++
        return count
    }

    private fun permissionSummaryText(): String {
        val missing: Int = missingPermissionCount()
        if (missing == 0) return "Все основные разрешения активны"
        return "Нужно включить: " + missing
    }

    private fun overlayPermissionHint(): String {
        val names: ArrayList<String?> = ArrayList()
        if (AppSettings.tpmsAlertsEnabled(this)) names.add("TPMS")
        if (AppSettings.rctaOverlayEnabled(this)) names.add("RCTA")
        if (AppSettings.navOverlayEnabled(this)) names.add("навигация")
        if (AppSettings.mediaOverlayEnabled(this)) names.add("медиа")
        if (AppSettings.sunroofOverlayEnabled(this)) names.add("люк")
        if (names.isEmpty()) return "не нужно"
        val out: StringBuilder = StringBuilder()
        for (name: String? in names) {
            if (out.length > 0) out.append(", ")
            out.append(name)
        }
        return out.toString()
    }

    private fun setMediaProfile(profile: Int) {
        AppSettings.setMediaProfile(this, profile)
        AppService.start(this)
        AppLog.line(this, "Media profile: " + AppSettings.mediaProfileLabel(this))
        MediaFeature.get(this).onProfileChanged()
        renderTab()
        refresh()
    }

    private fun toggleCallEnabled(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setCallEnabled(this, enabled)
        AppService.start(this)
        if (!enabled) {
            CallFeature.get(this).stop()
        }
        AppLog.line(this, "BT call feature: " + enabled)
        renderTab()
        refresh()
    }

    private fun toggleMediaDebug(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setMediaOverlayEnabled(this, enabled)
        AppService.start(this)
        if (enabled && !MediaOverlayController.canDrawOverlays(this)) {
            AppLog.line(this, "Media overlay: открой разрешение поверх окон")
            val intent: Intent =
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()),
                )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                AppLog.line(this, "Media overlay settings failed " + e.javaClass.getSimpleName())
            }
        }
        MediaOverlayController.get(this).apply()
        AppLog.line(this, "Media debug overlay: " + enabled)
        refresh()
    }

    private fun setOtherMediaSourceMode(mode: Int) {
        AppSettings.setOtherMediaSourceMode(this, mode)
        AppLog.line(this, "Media OTHER source mode: " + AppSettings.otherMediaSourceLabel(this))
        MediaFeature.get(this)
            .resendCurrent("other source mode " + AppSettings.otherMediaSourceLabel(this))
        renderTab()
        refresh()
    }

    private fun setMediaTextMode(mode: Int) {
        AppSettings.setMediaTextMode(this, mode)
        AppLog.line(this, "Media text mode: " + AppSettings.mediaTextModeLabel(this))
        MediaFeature.get(this)
            .resendCurrent("media text mode " + AppSettings.mediaTextModeLabel(this))
        renderTab()
        refresh()
    }

    private fun mediaSourceDelayAction(delayMs: Int): View? {
        val title: String =
            if (delayMs == 0) "Без паузы"
            else
                (if (delayMs < 1000) delayMs.toString() + " мс"
                else MainActivity.formatSeconds(delayMs) + " с")
        val selected: Boolean = AppSettings.mediaSourceDelayMs(this) == delayMs
        return action(
            title,
            if (selected) "выбрано" else "после source",
            choiceColor(selected),
            Runnable { setMediaSourceDelayMs(delayMs) },
        )
    }

    private fun setMediaSourceDelayMs(delayMs: Int) {
        val profile: Int = AppSettings.mediaProfile(this)
        AppSettings.setMediaSourceDelayMs(this, profile, delayMs)
        AppLog.line(
            this,
            ("Media source delay: " + delayMs + " ms / " + AppSettings.mediaProfileLabel(this)),
        )
        Toast.makeText(this, "Применится при следующей смене источника", Toast.LENGTH_SHORT).show()
        renderTab()
        refresh()
    }

    private fun mediaArtistDelayAction(delayMs: Int): View? {
        val title: String =
            if (delayMs == 0) "Сразу трек" else MainActivity.formatSeconds(delayMs) + " с"
        val selected: Boolean = AppSettings.mediaArtistDelayMs(this) == delayMs
        return action(
            title,
            if (selected) "выбрано" else "показывать автора",
            choiceColor(selected),
            Runnable { setMediaArtistDelayMs(delayMs) },
        )
    }

    private fun setMediaArtistDelayMs(delayMs: Int) {
        AppSettings.setMediaArtistDelayMs(this, delayMs)
        AppLog.line(this, "Media artist delay: " + delayMs + " ms")
        MediaFeature.get(this).resendCurrent("media artist delay " + delayMs + "ms")
        renderTab()
        refresh()
    }

    private fun toggleMediaSourceReassert() {
        val enabled: Boolean = !AppSettings.mediaSourceReassertEnabled(this)
        AppSettings.setMediaSourceReassertEnabled(this, enabled)
        AppLog.line(this, "Media source reassert: " + enabled)
        Toast.makeText(this, "Применится при следующей смене источника", Toast.LENGTH_SHORT).show()
        renderTab()
        refresh()
    }

    private fun mediaProfileAction(profile: Int, title: String?, hint: String?): View? {
        val selected: Boolean = AppSettings.mediaProfile(this) == profile
        return action(
            title,
            hint,
            choiceColor(selected),
            selected,
            Runnable { setMediaProfile(profile) },
        )
    }

    private fun setCallSourceMode(mode: Int) {
        AppSettings.setCallSourceMode(this, mode)
        AppLog.line(this, "Call source mode: " + AppSettings.callSourceLabel(this))
        CallFeature.get(this).tick()
        renderTab()
        refresh()
    }

    private fun otherModeColor(mode: Int): Int {
        return choiceColor(AppSettings.otherMediaSourceMode(this) == mode)
    }

    private fun mediaTextModeColor(mode: Int): Int {
        return choiceColor(AppSettings.mediaTextMode(this) == mode)
    }

    private fun callSourceAction(mode: Int, title: String?, hint: String?): View? {
        val selected: Boolean = AppSettings.callSourceMode(this) == mode
        return action(
            title,
            hint,
            choiceColor(selected),
            selected,
            Runnable { setCallSourceMode(mode) },
        )
    }

    private fun mediaStatusText(): String {
        val media = StateStore.media()
        val out: StringBuilder = StringBuilder()
        val hasTitle: Boolean = !media.title.isEmpty()
        val hasArtist: Boolean =
            !media.artist.isEmpty() && !"<unknown>".equals(media.artist, ignoreCase = true)
        val hasSource: Boolean = !media.source.isEmpty()
        if (!hasTitle && !hasArtist && !hasSource) {
            out.append("нет данных")
        } else {
            if (hasTitle) out.append("Трек: ").append(media.title).append('\n')
            if (hasArtist) out.append("Исполнитель: ").append(media.artist).append('\n')
            if (hasSource) out.append("Источник: ").append(media.source).append('\n')
            out.append(if (media.playing) "Играет" else "Пауза")
            if (media.durationMs >= 0L)
                out.append(" | ").append(MainActivity.formatMediaDuration(media.durationMs))
        }
        out.append("\nПрофиль: ").append(AppSettings.mediaProfileLabel(this))
        out.append("\nРежим other: ").append(AppSettings.otherMediaSourceLabel(this))
        out.append("\nТекст панели: ").append(AppSettings.mediaTextModeLabel(this))
        if (AppSettings.universalMediaProfile(this)) {
            out.append("\nSource delay: ")
                .append(AppSettings.mediaSourceDelayMs(this))
                .append(" мс")
            out.append(" | автор: ").append(AppSettings.mediaArtistDelayMs(this)).append(" мс")
            out.append(" | повтор: ")
                .append(MainActivity.yesNo(AppSettings.mediaSourceReassertEnabled(this)))
        }
        return out.toString()
    }

    private fun mediaDebugText(): String {
        val media = StateStore.media()
        val frequency = RadioStationStore.currentFrequency(media)
        val out: StringBuilder = StringBuilder()
        out.append("profile=").append(AppSettings.mediaProfileLabel(this))
        out.append("\ncapture=").append(mediaCaptureModeText())
        out.append("\ntextId=").append(mediaTextCommandDebug(media))
        out.append(" other=").append(AppSettings.otherMediaSourceLabel(this))
        if (AppSettings.universalMediaProfile(this)) {
            out.append("\ndelay=").append(AppSettings.mediaSourceDelayMs(this))
            out.append(" artistDelay=").append(AppSettings.mediaArtistDelayMs(this))
            out.append(" reassert=").append(AppSettings.mediaSourceReassertEnabled(this))
        }
        out.append("\nsource=").append(MainActivity.emptyDash(media.source))
        out.append("\npackage=").append(MainActivity.emptyDash(media.packageName))
        out.append("\nartist=").append(MainActivity.emptyDash(media.artist))
        out.append("\ntitle=").append(MainActivity.emptyDash(media.title))
        out.append("\nplaying=").append(media.playing)
        out.append(" duration=").append(media.durationMs)
        out.append("\nradio=")
            .append(
                if (frequency.isEmpty()) "-"
                else RadioStationStore.currentBand(media).toString() + " " + frequency
            )
        out.append(" stations=").append(RadioStationStore.entries(this).size)
        out.append("\nnotify=").append(MainActivity.yesNo(notificationPermissionGranted()))
        out.append(" listener=").append(MainActivity.yesNo(notificationListenerEnabled()))
        out.append("\nusb=").append(StateStore.adapter().usbText)
        out.append(" rx=").append(timeText(StateStore.adapter().lastFrameAt))
        return out.toString()
    }

    private fun mediaCaptureModeText(): String {
        val profile: Int = AppSettings.mediaProfile(this)
        if (profile == AppSettings.MEDIA_PROFILE_TEYES) return "teyes_widget+spd"
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL)
            return "media_session+uart_real_text_only"
        if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID) return "media_session+radio_db"
        return "off"
    }

    private fun mediaTextCommandDebug(media: MediaState?): String {
        val text: String? =
            (((if (media == null) "" else media.source) +
                    " " +
                    (if (media == null) "" else media.packageName)))
                .lowercase(Locale.US)
        if (
            text!!.contains("fm") ||
                text.contains("am") ||
                text.contains("radio") ||
                text.contains("радио")
        )
            return "0x20 radio"
        if (text.contains("usb") || text.contains("spd.media")) return "0x22 usb"
        if (
            text.contains("yandex") ||
                text.contains("яндекс") ||
                text.contains("my music") ||
                text.contains("teyes")
        )
            return "0x24 my_music"
        if (text.contains("carplay") || text.contains("car play")) return "0x25 carplay"
        if (
            text.contains("bluetooth") ||
                text.contains("btmusic") ||
                text.contains("a2dp") ||
                text.contains("avrcp")
        )
            return "0x21 bt"
        when (AppSettings.otherMediaSourceMode(this)) {
            AppSettings.OTHER_SOURCE_USB -> return "0x22 usb"
            AppSettings.OTHER_SOURCE_BLUETOOTH -> return "0x21 bt"
            AppSettings.OTHER_SOURCE_MY_MUSIC -> return "0x24 my_music"
            AppSettings.OTHER_SOURCE_CARPLAY -> return "0x25 carplay"
            AppSettings.OTHER_SOURCE_ANDROID -> return "0x23 android_auto"
            else -> return "0x23 android_auto"
        }
    }

    private fun callStatusText(): String {
        val call = StateStore.call()
        if (call == null || !call.active) {
            return "нет активного звонка\nИсточник панели: " + AppSettings.callSourceLabel(this)
        }
        return (call.summary(System.currentTimeMillis()).toString() +
            "\nИсточник панели: " +
            AppSettings.callSourceLabel(this))
    }

    private fun tpmsStatusText(): String {
        val state = StateStore.tpms()
        if (state == null || !state.hasData()) {
            return ("нет данных от датчиков\nИсточник: " +
                MainActivity.emptyDash(if (state == null) "" else state.source) +
                " | RX " +
                timeText(if (state == null) 0L else state.updatedAt))
        }
        return ("Источник: " +
            MainActivity.emptyDash(state.source) +
            " | RX " +
            timeText(state.updatedAt) +
            "\n" +
            (if (TpmsAlertController.hasCriticalWarnings(this, state)) "критичное предупреждение"
            else
                (if (TpmsAlertController.hasWarnings(this, state)) "есть предупреждение"
                else "все колеса в норме")))
    }

    private fun tpmsAlertText(): String {
        val state = StateStore.tpms()
        if (!AppSettings.tpmsAlertsEnabled(this)) {
            return "Оповещения выключены\n" + tpmsThresholdText()
        }
        if (state == null || !state.hasData()) {
            return "Ожидаю данные TPMS\n" + tpmsThresholdText()
        }
        val details = TpmsAlertController.warningDetails(this, state)
        if (details.length == 0) return "Все колеса в норме\n" + tpmsThresholdText()
        return details.toString() + "\n" + tpmsThresholdText()
    }

    private fun tpmsThresholdText(): String {
        return ("давление " +
            MainActivity.pressureBarText(AppSettings.tpmsLowPressureKpa(this)) +
            "-" +
            MainActivity.pressureBarText(AppSettings.tpmsHighPressureKpa(this)) +
            " bar" +
            " | температура " +
            AppSettings.tpmsLowTempC(this) +
            "-" +
            AppSettings.tpmsHighTempC(this) +
            "C")
    }

    private fun warningColor(warning: Int): Int {
        when (warning) {
            TpmsAlertController.WARNING_LOW_PRESSURE,
            TpmsAlertController.WARNING_LOW_TEMP -> return MainActivity.COLOR_WARNING
            TpmsAlertController.WARNING_HIGH_PRESSURE -> return MainActivity.COLOR_ROSE
            TpmsAlertController.WARNING_HIGH_TEMP,
            TpmsAlertController.WARNING_FAST_LEAKAGE -> return MainActivity.COLOR_DANGER
            TpmsAlertController.WARNING_LOW_BATTERY -> return MainActivity.COLOR_VIOLET
            else -> return MainActivity.COLOR_ACCENT
        }
    }

    private fun timeText(value: Long): String? {
        if (value <= 0L) return "нет"
        return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(value))
    }

    private fun adjustSasRatio(delta: Int) {
        setSasRatio(AppSettings.sasRatio(this) + delta)
    }

    private fun resetSasRatio() {
        setSasRatio(10)
    }

    private fun toggleAutoStart(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setAutoStart(this, enabled)
        if (enabled) AppService.start(this)
        AppLog.line(this, "Autostart background: " + enabled)
        refresh()
    }

    private fun toggleMediaTab(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setMediaTabVisible(this, enabled)
        if (!enabled && selectedTab == MainActivity.TAB_MEDIA) selectedTab = MainActivity.TAB_TPMS
        if (!enabled && settingsTab == MainActivity.SETTINGS_MEDIA)
            settingsTab = MainActivity.SETTINGS_GENERAL
        AppLog.line(this, "Media settings tab visible: " + enabled)
        setContentView(buildUi())
        applyImmersiveMode()
        refresh()
    }

    private fun toggleTpmsAlerts(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setTpmsAlertsEnabled(this, enabled)
        TpmsController.get(this).onSettingsChanged()
        AppLog.line(this, "TPMS alerts: " + enabled)
        renderTab()
        refresh()
    }

    private fun toggleTpmsSoundAlerts(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setTpmsSoundAlertsEnabled(this, enabled)
        if (!enabled) {
            TpmsWarningOverlayController.get(this).stopSound()
        } else {
            TpmsController.get(this).onSettingsChanged()
        }
        AppLog.line(this, "TPMS sound alerts: " + enabled)
        refresh()
    }

    private fun setTpmsLowPressure(value: Int) {
        AppSettings.setTpmsLowPressureKpa(this, value)
        onTpmsThresholdChanged("low pressure " + AppSettings.tpmsLowPressureKpa(this))
    }

    private fun setTpmsHighPressure(value: Int) {
        AppSettings.setTpmsHighPressureKpa(this, value)
        onTpmsThresholdChanged("high pressure " + AppSettings.tpmsHighPressureKpa(this))
    }

    private fun setTpmsLowTemp(value: Int) {
        AppSettings.setTpmsLowTempC(this, value)
        onTpmsThresholdChanged("low temp " + AppSettings.tpmsLowTempC(this))
    }

    private fun setTpmsHighTemp(value: Int) {
        AppSettings.setTpmsHighTempC(this, value)
        onTpmsThresholdChanged("high temp " + AppSettings.tpmsHighTempC(this))
    }

    private fun onTpmsThresholdChanged(label: String?) {
        TpmsController.get(this).onSettingsChanged()
        AppLog.line(this, "TPMS threshold: " + label)
        renderTab()
        refresh()
    }

    private fun toggleNavDebug(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setNavDebugVisible(this, enabled)
        AppSettings.setNavOverlayEnabled(this, enabled)
        AppService.start(this)
        if (enabled && !NavigationOverlayController.canDrawOverlays(this)) {
            AppLog.line(this, "Nav overlay: открой разрешение поверх окон")
            val intent: Intent =
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()),
                )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                AppLog.line(this, "Nav overlay settings failed " + e.javaClass.getSimpleName())
            }
        }
        NavigationOverlayController.get(this).apply()
        AppLog.line(this, "Navigation debug visible: " + enabled)
        refresh()
    }

    private fun toggleCanbusDebug(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setCanbusDebugVisible(this, enabled)
        AppLog.line(this, "CANBUS debug visible: " + enabled)
        renderTab()
        refresh()
    }

    private fun toggleAmpEnabled(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setAmpEnabled(this, enabled)
        AppService.start(this)
        if (enabled) {
            lastAmpRequestAt = 0L
            requestAmpSettingsIfNeeded()
        }
        AppLog.line(this, "AMP feature: " + enabled)
        renderTab()
        refresh()
    }

    private fun requestMissingPermissionsFromSettings() {
        askedOverlay = false
        askedBackgroundLocationSettings = false
        askedBatteryOptimization = false
        askedNotificationListener = false
        specialPermissionWaiting = false
        requestRuntimePermissions()
        handler.postDelayed(
            {
                if (selectedTab == MainActivity.TAB_SETTINGS) renderTab()
                refresh()
            },
            700L,
        )
    }

    private fun setSasRatio(value: Int) {
        val ratio: Int = MainActivity.clamp(value, 1, 255)
        AppSettings.setSasRatio(this, ratio)
        if (sasRatioInput != null) {
            sasRatioInput!!.setText(java.lang.String.valueOf(ratio))
            sasRatioInput!!.setSelection(sasRatioInput!!.getText().length)
        }
        if (sasRatioStatus != null) sasRatioStatus!!.setText(sasRatioText())
        if (sasRatioPreview != null) sasRatioPreview!!.invalidate()
        AppService.start(this)
        AdapterGateway.get(this)
            .send(AdapterCommand.loud("SAS ratio " + ratio, AdapterProtocol.sasRatio(ratio)))
        AppLog.line(this, "SAS ratio TX: " + ratio + " (adapter save)")
        refresh()
    }

    private fun setCanbusTemperatureSource(mode: Int) {
        val clean: Int =
            if (mode == AppSettings.CANBUS_TEMP_ENGINE) AppSettings.CANBUS_TEMP_ENGINE
            else AppSettings.CANBUS_TEMP_OUTSIDE
        AppSettings.setCanbusTemperatureSource(this, clean)
        AppService.start(this)
        AdapterGateway.get(this)
            .send(
                AdapterCommand.loud(
                    "CANBUS temperature source " + AppSettings.canbusTemperatureSourceLabel(this),
                    AdapterProtocol.canbusTemperatureEngine(clean == AppSettings.CANBUS_TEMP_ENGINE),
                )
            )
        AppLog.line(
            this,
            "CANBUS temp source TX: " + AppSettings.canbusTemperatureSourceLabel(this),
        )
        renderTab()
        refresh()
    }

    private fun canbusTempSourceColor(mode: Int): Int {
        return choiceColor(AppSettings.canbusTemperatureSource(this) == mode)
    }

    private fun readSasRatioInput(fallback: Int): Int {
        if (sasRatioInput == null) return fallback
        val raw: String? =
            if (sasRatioInput!!.getText() == null) ""
            else sasRatioInput!!.getText().toString().trim { it <= ' ' }
        if (raw!!.isEmpty()) return fallback
        try {
            return Integer.parseInt(raw)
        } catch (e: NumberFormatException) {
            return fallback
        }
    }

    private fun ampBassUp() {
        val s = StateStore.amp()
        AmpController.get(this)
            .setSettings(
                AmpState(
                    s.volume,
                    s.balance,
                    s.fader,
                    Math.min(20, s.bass + 1),
                    s.mid,
                    s.treble,
                    s.mode,
                    System.currentTimeMillis(),
                ),
                true,
            )
    }

    private fun toggleNavTbt(button: CompoundButton?, enabled: Boolean) {
        NavigationFeature.get(this).setTbtMode(enabled)
        refresh()
    }

    private fun toggleNavFinishDirection(button: CompoundButton?, enabled: Boolean) {
        NavigationFeature.get(this).setFinishDirectionMode(enabled)
        AppService.start(this)
        refresh()
    }

    private fun toggleFinishCompassAuto(button: CompoundButton?, enabled: Boolean) {
        NavigationFeature.get(this).setFinishCompassAuto(enabled)
        AppService.start(this)
        renderTab()
        refresh()
    }

    private fun nextNavTextMode() {
        NavigationFeature.get(this).setTextMode((AppSettings.navTextMode(this) + 1) % 3)
    }

    private fun setNavTextMode(mode: Int) {
        NavigationFeature.get(this).setTextMode(mode)
        updateNavTextModeButtons()
        refresh()
    }

    private fun setNavSourceMode(mode: Int) {
        AppService.start(this)
        NavigationFeature.get(this).setSourceMode(mode)
        updateNavSourceModeButtons()
        refresh()
    }

    private fun setNavRouteOutputMode(mode: Int) {
        AppService.start(this)
        NavigationFeature.get(this).setOutputMode(mode)
        updateNavRouteModeButtons()
        renderTab()
        refresh()
    }

    private fun setNavSpeedLimitRouteOnly(routeOnly: Boolean) {
        if (AppSettings.navSpeedLimitRouteOnly(this) == routeOnly) return
        AppSettings.setNavSpeedLimitRouteOnly(this, routeOnly)
        NavigationFeature.get(this).onSpeedLimitSettingsChanged()
        renderTab()
        refresh()
    }

    private fun setNavSpeedLimitRepeatEnabled(enabled: Boolean) {
        if (AppSettings.navSpeedLimitRepeatEnabled(this) == enabled) return
        AppSettings.setNavSpeedLimitRepeatEnabled(this, enabled)
        NavigationFeature.get(this).onSpeedLimitSettingsChanged()
        refresh()
    }

    private fun navSourceColor(mode: Int): Int {
        return choiceColor(AppSettings.navSourceMode(this) == mode)
    }

    private fun updateNavSourceModeButtons() {
        updateChoiceButtons(
            navSourceModeViews,
            navSourceModeChecks,
            AppSettings.navSourceMode(this),
        )
    }

    private fun updateNavTextModeButtons() {
        updateChoiceButtons(navTextModeViews, navTextModeChecks, AppSettings.navTextMode(this))
    }

    private fun updateNavRouteModeButtons() {
        updateChoiceButtons(
            navRouteModeViews,
            navRouteModeChecks,
            NavigationModeSettings.mode(this),
        )
    }

    private fun navSourceAction(mode: Int, title: String?, hint: String?): View {
        return choiceAction(
            title,
            hint,
            AppSettings.navSourceMode(this) == mode,
            Runnable { setNavSourceMode(mode) },
            navSourceModeViews,
            navSourceModeChecks,
            mode,
        )
    }

    private fun navRouteModeAction(mode: Int, title: String?, hint: String?): View {
        return choiceAction(
            title,
            hint,
            NavigationModeSettings.mode(this) == mode,
            Runnable { setNavRouteOutputMode(mode) },
            navRouteModeViews,
            navRouteModeChecks,
            mode,
        )
    }

    private fun navAddressAction(mode: Int, title: String?, hint: String?): View {
        return choiceAction(
            title,
            hint,
            AppSettings.navTextMode(this) == mode,
            Runnable { setNavTextMode(mode) },
            navTextModeViews,
            navTextModeChecks,
            mode,
        )
    }

    private fun navFinishDirectionModeAction(): View? {
        val enabled: Boolean = AppSettings.navFinishDirectionMode(this)
        return action(
            if (enabled) "Стрелка вкл" else "Стрелка выкл",
            "отдельно затирает обычную навигацию и TBT",
            choiceColor(enabled),
            enabled,
            Runnable {
                toggleNavFinishDirection(null, !AppSettings.navFinishDirectionMode(this))
                renderTab()
                refresh()
            },
        )
    }

    private fun navMicroDistanceCycleAction(normalMode: Boolean): View? {
        val meters: Int = AppSettings.navMicroMaxDistanceMeters(this)
        val enabled: Boolean = normalMode && AppSettings.navMicroManeuvers(this)
        val hint: String =
            if (!normalMode) "доступно только в обычном режиме"
            else if (enabled) "максимальная дистанция для краткой подсказки"
            else "сначала включите ассистент подсказок"
        val button: View =
            action(
                "Микро до " + navMicroDistanceText(meters),
                hint,
                if (enabled) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED,
                false,
                Runnable {
                    if (!navNormalSettingsEnabled() || !AppSettings.navMicroManeuvers(this))
                        return@Runnable
                    showMeterChoice(
                        "Дистанция микроподсказки",
                        meters,
                        intArrayOf(100, 150, 200, 250, 300, 400, 500),
                        IntSetter { meters: Int -> this.setNavMicroMaxDistanceMeters(meters) },
                    )
                },
            )
        return navSettingsAction(button, enabled)
    }

    private fun navMainRevealDistanceCycleAction(available: Boolean): View? {
        val meters: Int = AppSettings.navMainRevealDistanceMeters(this)
        val enabled: Boolean = available && AppSettings.navStraightUntilMain(this)
        val hint: String =
            if (!navNormalSettingsEnabled()) "доступно только в обычном режиме"
            else if (!AppSettings.yandexNavigationEnabled(this)) "доступно для источника Яндекс"
            else if (enabled) "100 / 200 / 300 / 400 / 500 м"
            else "сначала включите показ прямо до манёвра"
        val button: View =
            action(
                "Основной за " + meters + " м",
                hint,
                if (enabled) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED,
                false,
                Runnable {
                    if (
                        !navNormalSettingsEnabled() ||
                            !AppSettings.yandexNavigationEnabled(this) ||
                            !AppSettings.navStraightUntilMain(this)
                    )
                        return@Runnable
                    showMeterChoice(
                        "Когда показать основной манёвр",
                        meters,
                        intArrayOf(100, 200, 300, 400, 500),
                        IntSetter { meters: Int -> this.setNavMainRevealDistanceMeters(meters) },
                    )
                },
            )
        return navSettingsAction(button, enabled)
    }

    private fun showMeterChoice(title: String?, current: Int, values: IntArray, setter: IntSetter) {
        val labels: kotlin.Array<String?> = arrayOfNulls<String>(values.size)
        var selected: Int = -1
        for (i in values.indices) {
            labels[i] = values.get(i).toString() + " м"
            if (values.get(i) == current) selected = i
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(
                labels,
                selected,
                { dialog, which ->
                    setter.set(values.get(which))
                    dialog.dismiss()
                },
            )
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun navMicroHoldAfterCycleAction(normalMode: Boolean): View? {
        val seconds: Int = AppSettings.navMicroHoldSeconds(this)
        val enabled: Boolean = normalMode && AppSettings.navMicroManeuvers(this)
        val hint: String =
            if (!normalMode) "доступно только в обычном режиме"
            else if (enabled) "сколько держать подсказку после проезда"
            else "сначала включите ассистент подсказок"
        val button: View =
            action(
                "После проезда " + seconds + "с",
                hint,
                if (enabled) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED,
                false,
                Runnable {
                    if (!navNormalSettingsEnabled() || !AppSettings.navMicroManeuvers(this))
                        return@Runnable
                    setNavMicroHoldSeconds(nextValue(seconds, intArrayOf(5, 10, 15)))
                },
            )
        return navSettingsAction(button, enabled)
    }

    private fun navManeuverTextCycleAction(): View? {
        val seconds: Int = AppSettings.navManeuverTextSeconds(this)
        val title: String = if (seconds <= 0) "Текст выкл" else "Текст " + seconds + "с"
        return action(
            title,
            "сменить текст подсказки",
            if (seconds <= 0) MainActivity.COLOR_MUTED else MainActivity.COLOR_ACCENT_BLUE,
            seconds > 0,
            Runnable { setNavManeuverTextSeconds(nextValue(seconds, intArrayOf(0, 5, 10, 15))) },
        )
    }

    private fun navEtaTimeModeAction(): View? {
        val mode: Int = AppSettings.navEtaTimeMode(this)
        val remaining: Boolean = mode == AppSettings.NAV_ETA_TIME_REMAINING
        return action(
            "Время: " + AppSettings.navEtaTimeModeLabel(this).lowercase(Locale.ROOT),
            if (remaining) "показывать сколько осталось ехать" else "показывать час прибытия",
            if (remaining) MainActivity.COLOR_ACCENT else MainActivity.COLOR_ACCENT_BLUE,
            true,
            Runnable {
                setNavEtaTimeMode(
                    if (remaining) AppSettings.NAV_ETA_TIME_ARRIVAL
                    else AppSettings.NAV_ETA_TIME_REMAINING
                )
            },
        )
    }

    private fun navFinishLeadCycleAction(): View? {
        val meters: Int = AppSettings.navFinishDirectionLeadMeters(this)
        return action(
            "Старт: " + navFinishLeadText(meters),
            "сменить порог",
            if (AppSettings.navFinishDirectionMode(this)) MainActivity.COLOR_ACCENT_BLUE
            else MainActivity.COLOR_MUTED,
            AppSettings.navFinishDirectionMode(this),
            Runnable { setNavFinishLeadMeters(nextValue(meters, intArrayOf(0, 50, 100, 200, 500))) },
        )
    }

    private fun setNavFinishLeadMeters(meters: Int) {
        AppSettings.setNavFinishDirectionLeadMeters(this, meters)
        AppLog.line(
            this,
            "Navigation finish direction lead meters: " +
                AppSettings.navFinishDirectionLeadMeters(this),
        )
        renderTab()
        refresh()
    }

    private fun setNavMicroHoldSeconds(seconds: Int) {
        AppSettings.setNavMicroHoldSeconds(this, seconds)
        AppLog.line(
            this,
            "Navigation maneuver assistant hold after seconds: " +
                AppSettings.navMicroHoldSeconds(this),
        )
        renderTab()
        refresh()
    }

    private fun setNavMicroMaxDistanceMeters(meters: Int) {
        AppSettings.setNavMicroMaxDistanceMeters(this, meters)
        AppLog.line(
            this,
            "Navigation maneuver assistant max distance meters: " +
                AppSettings.navMicroMaxDistanceMeters(this),
        )
        NavigationFeature.get(this).onManeuverDisplaySettingsChanged("micro_max_distance")
        renderTab()
        refresh()
    }

    private fun setNavMainRevealDistanceMeters(meters: Int) {
        AppSettings.setNavMainRevealDistanceMeters(this, meters)
        AppLog.line(
            this,
            "Navigation main maneuver reveal distance meters: " +
                AppSettings.navMainRevealDistanceMeters(this),
        )
        NavigationFeature.get(this).onManeuverDisplaySettingsChanged("main_reveal_distance")
        renderTab()
        refresh()
    }

    private fun setNavManeuverTextSeconds(seconds: Int) {
        AppSettings.setNavManeuverTextSeconds(this, seconds)
        AppLog.line(
            this,
            "Navigation maneuver text seconds: " + AppSettings.navManeuverTextSeconds(this),
        )
        renderTab()
        refresh()
    }

    private fun setNavEtaTimeMode(mode: Int) {
        AppSettings.setNavEtaTimeMode(this, mode)
        AppLog.line(this, "Navigation ETA time mode: " + AppSettings.navEtaTimeModeLabel(this))
        NavigationFeature.get(this).resendKnownRouteData()
        renderTab()
        refresh()
    }

    private fun navFinishLeadText(meters: Int): String {
        return if (meters <= 0) "сразу" else "за " + meters + " м"
    }

    private fun navMicroDistanceText(meters: Int): String {
        return meters.toString() + " м"
    }

    private fun navManeuverTextText(seconds: Int): String {
        return if (seconds <= 0) "выкл" else seconds.toString() + " сек"
    }

    private fun nextValue(current: Int, values: IntArray?): Int {
        if (values == null || values.size == 0) return current
        for (i in values.indices) {
            if (values.get(i) == current) return values.get((i + 1) % values.size)
        }
        return values.get(0)
    }

    private fun choiceColor(selected: Boolean): Int {
        return if (selected) MainActivity.COLOR_ACCENT else MainActivity.COLOR_MUTED
    }

    private fun navTextModeText(mode: Int): String {
        when (mode) {
            1 -> return "после манёвра"
            2 -> return "финиш"
            0 -> return "текущая"
            else -> return "текущая"
        }
    }

    private fun toggleNavOverlay(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setNavOverlayEnabled(this, enabled)
        AppService.start(this)
        if (enabled && !NavigationOverlayController.canDrawOverlays(this)) {
            AppLog.line(this, "Nav overlay: открой разрешение поверх окон")
            val intent: Intent =
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()),
                )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                AppLog.line(this, "Nav overlay settings failed " + e.javaClass.getSimpleName())
            }
        }
        NavigationOverlayController.get(this).apply()
        AppLog.line(this, "Nav overlay: " + enabled)
        refresh()
    }

    private fun toggleMicroManeuvers(button: CompoundButton?, enabled: Boolean) {
        if (!navNormalSettingsEnabled()) {
            updateMicroManeuverToggle()
            return
        }
        AppSettings.setNavMicroManeuvers(this, enabled)
        AppLog.line(this, "Navigation maneuver assistant: " + enabled)
        NavigationFeature.get(this).onManeuverDisplaySettingsChanged("micro_enabled")
        renderTab()
        refresh()
    }

    private fun toggleStraightUntilMain(button: CompoundButton?, enabled: Boolean) {
        if (!navNormalSettingsEnabled() || !AppSettings.yandexNavigationEnabled(this)) {
            updateStraightUntilMainToggle()
            return
        }
        AppSettings.setNavStraightUntilMain(this, enabled)
        AppLog.line(this, "Navigation straight until main maneuver: " + enabled)
        NavigationFeature.get(this).onManeuverDisplaySettingsChanged("straight_until_main")
        renderTab()
        refresh()
    }

    private fun toggleOverspeedText(button: CompoundButton?, enabled: Boolean) {
        AppSettings.setNavOverspeedTextEnabled(this, enabled)
        AppLog.line(this, "Navigation overspeed text: " + enabled)
        refresh()
    }

    private fun addAmpSection(root: LinearLayout = tabContent!!) {
        val s = StateStore.amp()
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        ampEnabledToggle =
            addSettingsPanelSwitchHeader(
                panel,
                "AMP",
                "штатный усилитель: громкость, баланс, фейдер, тембр и режим",
                AppSettings.ampEnabled(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleAmpEnabled(button, enabled)
                },
            )

        ampVisualizer = AmpVisualizerView(this)
        ampVisualizer!!.setState(s, AppSettings.ampEnabled(this))
        val visualLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        visualLp.setMargins(0, dp(12), 0, dp(10))
        panel.addView(ampVisualizer, visualLp)

        if (!AppSettings.ampEnabled(this)) {
            root.addView(panel)
            return
        }

        val summary: TextView =
            text(MainActivity.ampUiText(s), if (isCompact()) 13 else 15, MainActivity.COLOR_MUTED)
        ampSummary = summary
        val summaryLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        summaryLp.setMargins(0, dp(12), 0, dp(8))
        panel.addView(summary, summaryLp)

        val controls: kotlin.Array<View?> =
            arrayOf<View?>(
                ampControl("volume", "Громкость", s.volume, 0, 40),
                ampControl("balance", "Баланс L/R", s.balance - 10, -10, 10),
                ampControl("fader", "Фейдер F/R", s.fader - 10, -10, 10),
                ampControl("bass", "Низкие", s.bass - 10, -10, 10),
                ampControl("mid", "Средние", s.mid - 10, -10, 10),
                ampControl("treble", "Высокие", s.treble - 10, -10, 10),
                ampControl("mode", "Режим", s.mode, 0, 32),
            )
        val columns: Int = ampColumns()
        var i: Int = 0
        while (i < controls.size) {
            val line: LinearLayout = row()
            for (column in 0..<columns) {
                val index: Int = i + column
                line.addView(
                    if (index < controls.size) controls.get(index) else View(this),
                    ampGridLayout(column, columns),
                )
            }
            panel.addView(line)
            i += columns
        }
        root.addView(panel)
    }

    private fun adjustAmp(field: String?, delta: Int) {
        setAmpValue(field, displayAmpValue(field) + delta)
    }

    private fun setAmpValue(field: String?, displayValue: Int) {
        if (!AppSettings.ampEnabled(this)) return
        val s = StateStore.amp()
        var volume: Int = s.volume
        var balance: Int = s.balance
        var fader: Int = s.fader
        var bass: Int = s.bass
        var mid: Int = s.mid
        var treble: Int = s.treble
        var mode: Int = s.mode
        if ("volume".equals(field)) volume = MainActivity.clamp(displayValue, 0, 40)
        else if ("balance".equals(field)) balance = MainActivity.clamp(displayValue, -10, 10) + 10
        else if ("fader".equals(field)) fader = MainActivity.clamp(displayValue, -10, 10) + 10
        else if ("bass".equals(field)) bass = MainActivity.clamp(displayValue, -10, 10) + 10
        else if ("mid".equals(field)) mid = MainActivity.clamp(displayValue, -10, 10) + 10
        else if ("treble".equals(field)) treble = MainActivity.clamp(displayValue, -10, 10) + 10
        else if ("mode".equals(field)) mode = MainActivity.clamp(displayValue, 0, 32)
        AmpController.get(this)
            .setSettings(
                AmpState(
                    volume,
                    balance,
                    fader,
                    bass,
                    mid,
                    treble,
                    mode,
                    System.currentTimeMillis(),
                ),
                true,
            )
        refresh()
    }

    private fun requestAmpSettingsIfNeeded() {
        if (!AppSettings.ampEnabled(this)) return
        val now: Long = System.currentTimeMillis()
        if (now - lastAmpRequestAt < 15000L) return
        lastAmpRequestAt = now
        AppService.start(this)
        AmpController.get(this).requestSettings()
    }

    private fun updatesText(): String {
        val s = StateStore.updates()
        return ("Kia: " +
            MainActivity.displayStatus(s.appStatus) +
            "\nYandex Navigator: " +
            MainActivity.displayStatus(s.navigatorStatus))
    }

    private fun firmwareStatusText(): String {
        val s = StateStore.updates()
        val selected: String? =
            if (pendingFirmwareUri == null) "файл не выбран" else pendingFirmwareLabel
        return ("Статус: " +
            MainActivity.displayStatus(s.firmwareStatus) +
            "\nBIN: " +
            selected +
            (if (s.exclusiveUsbMode) "\nUSB занят прошивкой" else ""))
    }

    private fun appUpdateTitle(): String {
        val s = StateStore.updates()
        if (s.appDownloading) return "APK загружается"
        if (s.appChecking) return "APK проверяется"
        return if (s.appAvailable || s.appDownloaded) "Обновить APK" else "Проверить APK"
    }

    private fun appUpdateHint(): String {
        val s = StateStore.updates()
        if (s.appAvailable || s.appDownloaded) return "скачать и открыть установщик"
        return "сравнить с GitHub"
    }

    private fun runAppUpdateAction() {
        val s = StateStore.updates()
        if (s.appAvailable || s.appDownloaded) appUpdater!!.downloadAndInstall(this)
        else appUpdater!!.checkAsync(this)
        refresh()
    }

    private fun navigatorUpdateTitle(): String {
        val s = StateStore.updates()
        if (s.navigatorDownloading) return "Скачиваю Навигатор"
        if (s.navigatorChecking) return "Проверяю Навигатор"
        if (s.navigatorInstalling) return "Установка Навигатора"
        return if (s.navigatorAvailable || s.navigatorDownloaded) "Обновить Навигатор"
        else "Проверить Навигатор"
    }

    private fun navigatorUpdateHint(): String {
        val s = StateStore.updates()
        if (s.navigatorDownloading || s.navigatorChecking || s.navigatorInstalling) {
            return MainActivity.displayStatus(s.navigatorStatus)
        }
        if (s.navigatorDownloaded) return "файлы готовы, открыть установщик Android"
        if (s.navigatorAvailable) return "последний Yandex Navigator с KIA-хуками"
        return "сравнить с GitHub"
    }

    private fun runNavigatorUpdateAction() {
        val s = StateStore.updates()
        if (s.navigatorAvailable || s.navigatorDownloaded)
            navigatorUpdater!!.downloadAndInstall(this)
        else navigatorUpdater!!.checkAsync(this)
        refresh()
    }

    private fun firmwarePanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Прошивка адаптера",
            "ручной BIN-файл; после выбора кнопка меняется на прошивку",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        firmwareStatus =
            text(firmwareStatusText(), if (isCompact()) 13 else 15, MainActivity.COLOR_MUTED)
        val statusLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        statusLp.setMargins(0, dp(10), 0, dp(8))
        panel.addView(firmwareStatus, statusLp)

        panel.addView(firmwareManualAction(), singleActionLayout())
        return panel
    }

    private fun openManualFirmwarePicker() {
        val intent: Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("*/*")
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            startActivityForResult(intent, MainActivity.REQUEST_FIRMWARE_FILE)
        } catch (e: Exception) {
            AppLog.line(this, "Firmware file picker failed " + e.javaClass.getSimpleName())
        }
    }

    private fun firmwareManualAction(): View? {
        firmwareActionButton = FrameLayout(this)
        firmwareActionButton!!.setClickable(true)
        firmwareActionButton!!.setFocusable(true)
        firmwareActionButton!!.setMinimumHeight(if (isCompact()) dp(74) else dp(90))
        firmwareActionButton!!.setBackground(
            settingsButtonBackground(
                pendingFirmwareUri != null || StateStore.updates().firmwareFlashing
            )
        )

        firmwareProgressFill = View(this)
        firmwareProgressFill!!.setBackground(
            round(softColor(MainActivity.COLOR_ACCENT, 145), dp(7), Color.TRANSPARENT, 0)
        )
        firmwareActionButton!!.addView(
            firmwareProgressFill,
            FrameLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.LEFT or Gravity.CENTER_VERTICAL,
            ),
        )

        val texts: LinearLayout = LinearLayout(this)
        texts.setOrientation(LinearLayout.VERTICAL)
        texts.setGravity(Gravity.CENTER_VERTICAL)
        texts.setPadding(dp(16), dp(10), dp(16), dp(10))
        firmwareActionText = text("", if (isCompact()) 17 else 19, Color.WHITE)
        firmwareActionText!!.setTypeface(Typeface.DEFAULT_BOLD)
        firmwareActionHintText = text("", if (isCompact()) 12 else 14, MainActivity.COLOR_MUTED)
        texts.addView(firmwareActionText)
        texts.addView(firmwareActionHintText)
        firmwareActionButton!!.addView(
            texts,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        firmwareActionButton!!.setOnClickListener({ v -> runManualFirmwareAction() })
        updateFirmwareActionButton()
        return firmwareActionButton
    }

    private fun firmwareFileName(uri: Uri?): String {
        if (uri == null) return "manual.bin"
        try {
            getContentResolver()
                .query(uri, arrayOf<String?>(OpenableColumns.DISPLAY_NAME), null, null, null)
                .use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val index: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val value = cursor.getString(index)
                            if (value != null && value.trim { it <= ' ' }.length > 0) return value.trim { it <= ' ' }
                        }
                    }
                }
        } catch (ignored: Exception) {}
        val tail = uri.getLastPathSegment()
        return if (tail == null || tail.trim { it <= ' ' }.length == 0) "manual.bin" else tail.trim { it <= ' ' }
    }

    private fun runManualFirmwareAction() {
        if (firmwareUpdater!!.busy()) return
        if (pendingFirmwareUri == null) {
            openManualFirmwarePicker()
            return
        }
        if (!StateStore.adapter().usbConnected) {
            AlertDialog.Builder(this)
                .setTitle("Адаптер не подключён")
                .setMessage("Подключите USB-адаптер и дождитесь появления UID и версии FW.")
                .setPositiveButton("Понятно", null)
                .show()
            return
        }
        if (
            pendingFirmwareLabel == null ||
                !pendingFirmwareLabel!!.lowercase(Locale.ROOT).endsWith(".bin")
        ) {
            AlertDialog.Builder(this)
                .setTitle("Неверный тип файла")
                .setMessage("Для ручной прошивки нужен файл с расширением .bin.")
                .setPositiveButton(
                    "Выбрать другой",
                    { dialog, which ->
                        pendingFirmwareUri = null
                        pendingFirmwareLabel = ""
                        openManualFirmwarePicker()
                    },
                )
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        val size: Long = firmwareFileSize(pendingFirmwareUri)
        if (size == 0L || size > 112L * 1024L) {
            AlertDialog.Builder(this)
                .setTitle("Файл прошивки не подходит")
                .setMessage(
                    ("Допустимый размер BIN: от 1 байта до 112 КБ. Выбранный файл: " +
                        MainActivity.formatFileSize(size) +
                        ".")
                )
                .setPositiveButton(
                    "Выбрать другой",
                    { dialog, which ->
                        pendingFirmwareUri = null
                        pendingFirmwareLabel = ""
                        openManualFirmwarePicker()
                    },
                )
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        val uri: Uri? = pendingFirmwareUri
        val label: String? = pendingFirmwareLabel
        val message: String =
            ("Файл: " +
                label +
                "\nРазмер: " +
                MainActivity.formatFileSize(size) +
                "\nАдаптер: UID " +
                MainActivity.emptyDash(StateStore.adapter().uid) +
                " · FW " +
                MainActivity.emptyDash(StateStore.adapter().firmware) +
                "\n\nНе выключайте питание и не отсоединяйте USB до завершения.")
        AlertDialog.Builder(this)
            .setTitle("Начать прошивку адаптера?")
            .setMessage(message)
            .setPositiveButton(
                "Начать прошивку",
                { dialog, which ->
                    pendingFirmwareUri = null
                    pendingFirmwareLabel = ""
                    firmwareUpdater!!.flashFile(uri, label)
                    refresh()
                },
            )
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun firmwareFileSize(uri: Uri?): Long {
        if (uri == null) return -1L
        try {
            getContentResolver()
                .query(uri, arrayOf<String?>(OpenableColumns.SIZE), null, null, null)
                .use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val index: Int = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
                    }
                }
        } catch (ignored: Exception) {}
        return -1L
    }

    private fun generalAppPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Интерфейс и запуск",
            "обычный режим скрывает сервисные и отладочные параметры",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        autoStartToggle =
            addInlineSwitch(
                panel,
                "Автозапуск",
                "старт сервиса после загрузки",
                AppSettings.autoStart(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleAutoStart(button, enabled)
                },
            )
        panel.addView(settingsDivider())
        addInlineSwitch(
            panel,
            "Экспертные настройки",
            "прошивка адаптера, raw-диагностика, SAS и точные задержки",
            AppSettings.expertMode(this),
            CompoundButton.OnCheckedChangeListener { button, checked ->
                AppSettings.setExpertMode(this, checked)
                AppLog.line(this, "Expert settings: " + checked)
                renderTab()
                refresh()
            },
        )
        return panel
    }

    private fun mediaVisibilityPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        mediaTabToggle =
            addSettingsPanelSwitchHeader(
                panel,
                "Медиа",
                "показывать отдельный пункт в верхнем меню настроек",
                AppSettings.mediaTabVisible(this),
                CompoundButton.OnCheckedChangeListener { button: CompoundButton?, enabled: Boolean
                    ->
                    this.toggleMediaTab(button, enabled)
                },
            )
        return panel
    }

    private fun generalVersionFooter(): TextView {
        val version: TextView =
            text(
                "KIA " + appVersionText() + "  ·  " + getPackageName(),
                if (isCompact()) 12 else 14,
                MainActivity.COLOR_MUTED,
            )
        version.setGravity(Gravity.CENTER)
        version.setTypeface(Typeface.DEFAULT_BOLD)
        version.setPadding(dp(12), dp(12), dp(12), dp(22))
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(4), 0, dp(8))
        version.setLayoutParams(lp)
        return version
    }

    private fun generalUpdatesPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Обновления приложений",
            "Kia APK и наш Yandex Navigator устанавливаются отсюда",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        updatesStatus = text(updatesText(), if (isCompact()) 13 else 15, MainActivity.COLOR_MUTED)
        val statusLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        statusLp.setMargins(0, dp(10), 0, dp(6))
        panel.addView(updatesStatus, statusLp)

        addActionGrid(
            panel,
            action(
                appUpdateTitle(),
                appUpdateHint(),
                Color.rgb(32, 108, 126),
                Runnable { this.runAppUpdateAction() },
            ),
            action(
                navigatorUpdateTitle(),
                navigatorUpdateHint(),
                Color.rgb(32, 108, 126),
                Runnable { this.runNavigatorUpdateAction() },
            ),
        )
        return panel
    }

    private fun addPermissionSection(root: LinearLayout) {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "Разрешения",
            "только то, что реально влияет на медиа, звонки, навигацию и фон",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        permissionSummary =
            text(permissionSummaryText(), if (isCompact()) 13 else 15, MainActivity.COLOR_MUTED)
        val summaryLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        summaryLp.setMargins(0, dp(10), 0, dp(8))
        panel.addView(permissionSummary, summaryLp)

        val items: kotlin.Array<View?> =
            arrayOf<View?>(
                permissionTile("Уведомления", notificationPermissionGranted(), "фоновые события"),
                permissionTile(
                    "Медиа",
                    !mediaListenerRequired() || notificationListenerEnabled(),
                    if (mediaListenerRequired()) "трек и артист" else "не требуется профилю",
                ),
                permissionTile(
                    "Bluetooth",
                    !bluetoothRequired() || bluetoothPermissionGranted(),
                    if (bluetoothRequired()) "BT звонок" else "не требуется",
                ),
                permissionTile(
                    "GPS",
                    !gpsRequired() || gpsPermissionReady(),
                    if (gpsRequired()) "навигация в фоне" else "не требуется",
                ),
                permissionTile(
                    "Батарея",
                    !AppSettings.autoStart(this) || batteryOptimizationIgnored(),
                    if (AppSettings.autoStart(this)) "не выгружать" else "автозапуск выключен",
                ),
                permissionTile("Поверх окон", overlayPermissionReady(), overlayPermissionHint()),
            )
        val columns: Int = actionColumns(items.size)
        var i: Int = 0
        while (i < items.size) {
            val line: LinearLayout = row()
            for (column in 0..<columns) {
                val index: Int = i + column
                line.addView(
                    if (index < items.size) items.get(index) else View(this),
                    actionLayout(column, columns),
                )
            }
            panel.addView(line)
            i += columns
        }
        if (missingPermissionCount() > 0) {
            panel.addView(
                action(
                    "Запросить",
                    "включить недостающие разрешения",
                    MainActivity.COLOR_WARNING,
                    Runnable { this.requestMissingPermissionsFromSettings() },
                ),
                singleActionLayout(),
            )
        }
        root.addView(panel)
    }

    private fun permissionTile(title: String?, granted: Boolean, hint: String?): View {
        val tile: LinearLayout = LinearLayout(this)
        tile.setOrientation(LinearLayout.HORIZONTAL)
        tile.setGravity(Gravity.CENTER_VERTICAL)
        tile.setPadding(dp(12), dp(10), dp(12), dp(10))
        tile.setBackground(settingsButtonBackground(granted))

        val texts: LinearLayout = LinearLayout(this)
        texts.setOrientation(LinearLayout.VERTICAL)
        val name: TextView = text(title, if (isCompact()) 13 else 15, MainActivity.COLOR_TEXT)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        val state: TextView =
            text(
                (if (granted) "есть" else "нужно") + " · " + hint,
                if (isCompact()) 11 else 12,
                if (granted) MainActivity.COLOR_MUTED else MainActivity.COLOR_WARNING,
            )
        texts.addView(name)
        texts.addView(state)
        val textLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tile.addView(texts, textLp)
        if (!granted) {
            tile.setClickable(true)
            tile.setFocusable(true)
            tile.setOnClickListener({ v -> requestMissingPermissionsFromSettings() })
        }
        tile.setMinimumHeight(if (isCompact()) dp(58) else dp(66))
        return tile
    }

    private fun addSection(
        root: LinearLayout,
        title: String?,
        description: String?,
        vararg actions: View?,
    ) {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(panel, title, description, MainActivity.COLOR_ACCENT_BLUE)
        addActionGrid(panel, *actions)
        root.addView(panel)
    }

    private fun addActionGrid(root: LinearLayout, vararg actions: View?) {
        val columns: Int = actionColumns(actions.size)
        addActionGridColumns(root, columns, *actions)
    }

    private fun addActionGridColumns(root: LinearLayout, columns: Int, vararg actions: View?) {
        var columns: Int = columns
        columns = Math.max(1, Math.min(columns, Math.max(1, actions.size)))
        var i: Int = 0
        while (i < actions.size) {
            val line: LinearLayout = row()
            for (column in 0..<columns) {
                val index: Int = i + column
                val item: View? = if (index < actions.size) actions.get(index) else View(this)
                line.addView(item, actionLayout(column, columns))
            }
            root.addView(line)
            i += columns
        }
    }

    private fun action(title: String?, hint: String?, color: Int, action: Runnable): View {
        return action(title, hint, color, color == MainActivity.COLOR_ACCENT, action)
    }

    private fun action(
        title: String?,
        hint: String?,
        color: Int,
        selected: Boolean,
        action: Runnable,
    ): View {
        val box: LinearLayout = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setGravity(Gravity.CENTER_VERTICAL)
        box.setPadding(dp(16), dp(12), dp(16), dp(12))
        box.setMinimumHeight(if (isCompact()) dp(74) else dp(94))
        box.setClickable(true)
        box.setFocusable(true)
        box.setContentDescription(
            (title.toString() +
                (if (hint == null || hint.isEmpty()) "" else ". " + hint) +
                (if (selected) ". Выбрано" else ""))
        )
        box.setBackground(settingsActionBackground(selected, color))
        val click: View.OnClickListener =
            View.OnClickListener { v ->
                rememberScrollPosition()
                action.run()
            }
        box.setOnClickListener(click)

        val header: LinearLayout = row()
        val name: TextView = text(title, if (isCompact()) 16 else 18, Color.WHITE)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        name.setMaxLines(2)
        val nameLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(name, nameLp)
        val check: TextView =
            text(
                "выбрано",
                if (isCompact()) 11 else 12,
                if (selected) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED,
            )
        check.setGravity(Gravity.CENTER)
        check.setTypeface(Typeface.DEFAULT_BOLD)
        check.setMinWidth(dp(58))
        check.setVisibility(if (selected) View.VISIBLE else View.INVISIBLE)
        header.addView(check)
        val sub: TextView = text(hint, if (isCompact()) 12 else 14, MainActivity.COLOR_MUTED)
        sub.setMaxLines(2)
        box.addView(header)
        val subLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        subLp.setMargins(0, dp(4), 0, 0)
        box.addView(sub, subLp)
        return box
    }

    private fun navSettingsAction(view: View, enabled: Boolean): View? {
        view.setEnabled(enabled)
        view.setAlpha(if (enabled) 1f else 0.72f)
        if (!enabled) {
            view.setClickable(false)
            view.setFocusable(false)
            view.setOnClickListener(null)
        }
        return view
    }

    private fun choiceAction(
        title: String?,
        hint: String?,
        selected: Boolean,
        action: Runnable,
        views: kotlin.Array<View?>,
        checks: kotlin.Array<TextView?>,
        index: Int,
    ): View {
        val box: LinearLayout = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setGravity(Gravity.CENTER_VERTICAL)
        box.setPadding(dp(16), dp(12), dp(16), dp(12))
        box.setMinimumHeight(if (isCompact()) dp(74) else dp(94))
        box.setClickable(true)
        box.setFocusable(true)
        box.setContentDescription(
            (title.toString() +
                (if (hint == null || hint.isEmpty()) "" else ". " + hint) +
                (if (selected) ". Выбрано" else ""))
        )
        box.setBackground(settingsActionBackground(selected, MainActivity.COLOR_ACCENT_BLUE))
        val click: View.OnClickListener =
            View.OnClickListener { v ->
                rememberScrollPosition()
                action.run()
            }
        box.setOnClickListener(click)

        val header: LinearLayout = row()
        val name: TextView = text(title, if (isCompact()) 16 else 18, Color.WHITE)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        name.setMaxLines(2)
        val nameLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(name, nameLp)
        val check: TextView =
            text(
                "выбрано",
                if (isCompact()) 11 else 12,
                if (selected) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED,
            )
        check.setGravity(Gravity.CENTER)
        check.setTypeface(Typeface.DEFAULT_BOLD)
        check.setMinWidth(dp(58))
        check.setVisibility(if (selected) View.VISIBLE else View.INVISIBLE)
        header.addView(check)

        val sub: TextView = text(hint, if (isCompact()) 12 else 14, MainActivity.COLOR_MUTED)
        sub.setMaxLines(2)
        box.addView(header)
        val subLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        subLp.setMargins(0, dp(4), 0, 0)
        box.addView(sub, subLp)
        if (index >= 0 && index < views.size) {
            views[index] = box
            checks[index] = check
        }
        return box
    }

    private fun updateChoiceButtons(
        views: kotlin.Array<View?>,
        checks: kotlin.Array<TextView?>,
        selectedIndex: Int,
    ) {
        for (i in views.indices) {
            val view: View? = views.get(i)
            val check: TextView? = checks.get(i)
            if (view == null || check == null) continue
            val selected: Boolean = i == selectedIndex
            view.setBackground(settingsActionBackground(selected, MainActivity.COLOR_ACCENT_BLUE))
            check.setTextColor(
                if (selected) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED
            )
            check.setVisibility(if (selected) View.VISIBLE else View.INVISIBLE)
        }
    }

    private fun addSettingSwitch(
        root: LinearLayout,
        title: String?,
        hint: String?,
        checked: Boolean,
        listener: CompoundButton.OnCheckedChangeListener?,
    ): CompoundButton {
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.HORIZONTAL)
        panel.setGravity(Gravity.CENTER_VERTICAL)
        panel.setPadding(dp(16), dp(13), dp(16), dp(13))
        panel.setMinimumHeight(if (isCompact()) dp(70) else dp(82))
        panel.setBackground(settingsActionBackground(checked, MainActivity.COLOR_ACCENT_BLUE))
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(9), 0, dp(5))
        panel.setLayoutParams(lp)

        val texts: LinearLayout = LinearLayout(this)
        texts.setOrientation(LinearLayout.VERTICAL)
        val name: TextView = text(title, if (isCompact()) 15 else 17, Color.WHITE)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        val sub: TextView = text(hint, if (isCompact()) 12 else 13, MainActivity.COLOR_MUTED)
        texts.addView(name)
        texts.addView(sub)
        val textsLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        panel.addView(texts, textsLp)

        val toggle: CompoundButton = checkBox("", checked)
        toggle.setId(View.generateViewId())
        toggle.setContentDescription(title)
        name.setLabelFor(toggle.getId())
        toggle.setOnCheckedChangeListener(listener)
        panel.addView(toggle, switchLayout(false))
        root.addView(panel)
        return toggle
    }

    private fun switchLayout(separated: Boolean): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                if (isCompact()) dp(58) else dp(64),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        if (separated) lp.setMargins(dp(18), 0, 0, 0)
        return lp
    }

    private fun checkBox(title: String?, checked: Boolean): CompoundButton {
        val box: Switch = Switch(this)
        box.setText(title)
        box.setChecked(checked)
        box.setTextColor(Color.WHITE)
        box.setTextSize(15f)
        box.setGravity(Gravity.CENTER_VERTICAL)
        box.setMinWidth(if (isCompact()) dp(58) else dp(64))
        box.setMinHeight(dp(48))
        box.setPadding(0, dp(4), 0, dp(4))
        if (Build.VERSION.SDK_INT >= 21) {
            box.setShowText(false)
            box.setTextOn("")
            box.setTextOff("")
            box.setSplitTrack(false)
            val states: kotlin.Array<IntArray?> =
                arrayOf<IntArray?>(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked),
                )
            box.setThumbTintList(
                ColorStateList(
                    states,
                    intArrayOf(MainActivity.COLOR_ACCENT_BLUE, Color.rgb(86, 100, 116)),
                )
            )
            box.setTrackTintList(
                ColorStateList(
                    states,
                    intArrayOf(softColor(MainActivity.COLOR_ACCENT_BLUE, 92), Color.rgb(35, 43, 55)),
                )
            )
        }
        return box
    }

    private fun actionColumns(count: Int): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp < 560) return 1
        if (widthDp >= 820 && count >= 3) return 3
        return 2
    }

    private fun tpmsColumns(): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp >= 1100) return 4
        if (widthDp >= 560) return 2
        return 1
    }

    private fun actionLayout(column: Int, columns: Int): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val gap: Int = if (columns == 1) 0 else dp(5)
        lp.setMargins(
            if (column == 0) 0 else gap,
            dp(4),
            if (column == columns - 1) 0 else gap,
            dp(4),
        )
        return lp
    }

    private fun singleActionLayout(): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(8), 0, dp(4))
        return lp
    }

    private fun infoPanel(title: String?, content: TextView?): LinearLayout {
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setPadding(dp(16), dp(14), dp(16), dp(14))
        panel.setBackground(settingsPanelBackground())
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(10), 0, dp(4))
        panel.setLayoutParams(lp)

        val header: LinearLayout = row()
        val label: TextView = text(title, if (isCompact()) 16 else 18, Color.WHITE)
        label.setTypeface(Typeface.DEFAULT_BOLD)
        val labelLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(label, labelLp)
        panel.addView(header)
        val contentLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        contentLp.setMargins(0, dp(8), 0, 0)
        panel.addView(content, contentLp)
        return panel
    }

    private fun infoPanel(title: String?, content: CompoundButton?): LinearLayout {
        val panel: LinearLayout = LinearLayout(this)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setPadding(dp(12), dp(10), dp(12), dp(10))
        panel.setBackground(settingsPanelBackground())
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(0, dp(10), 0, dp(2))
        panel.setLayoutParams(lp)

        val label: TextView = text(title, 14, Color.WHITE)
        label.setTypeface(Typeface.DEFAULT_BOLD)
        panel.addView(label)
        val contentLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        contentLp.setMargins(0, dp(4), 0, 0)
        panel.addView(content, contentLp)
        return panel
    }

    private fun sasRatioPanel(): LinearLayout {
        val panel: LinearLayout = settingsPanel(MainActivity.COLOR_ACCENT_BLUE)
        addSettingsPanelHeader(
            panel,
            "SAS - парковочные линии",
            "датчик угла руля; ratio меняет чувствительность смещения линий заднего хода",
            MainActivity.COLOR_ACCENT_BLUE,
        )

        val preview: SasGuidePreviewView = SasGuidePreviewView(this)
        sasRatioPreview = preview
        val previewLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        previewLp.setMargins(0, dp(12), 0, dp(12))
        preview.setLayoutParams(previewLp)
        panel.addView(preview)

        val controls: LinearLayout = if (isCompact()) LinearLayout(this) else row()
        controls.setOrientation(if (isCompact()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL)
        controls.setGravity(Gravity.CENTER_VERTICAL)

        sasRatioStatus = text(sasRatioText(), if (isCompact()) 13 else 15, MainActivity.COLOR_MUTED)
        val statusLp: LinearLayout.LayoutParams =
            if (isCompact())
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        controls.addView(sasRatioStatus, statusLp)

        val inputLp: LinearLayout.LayoutParams =
            if (isCompact())
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            else LinearLayout.LayoutParams(dp(470), ViewGroup.LayoutParams.WRAP_CONTENT)
        inputLp.setMargins(if (isCompact()) 0 else dp(14), if (isCompact()) dp(10) else 0, 0, 0)
        controls.addView(
            numericRow(
                sasRatioInput,
                AppSettings.sasRatio(this),
                1,
                255,
                IntSetter { value: Int -> this.setSasRatio(value) },
            ),
            inputLp,
        )
        panel.addView(controls)
        return panel
    }

    private fun sasRatioInput(): EditText {
        return numericInput(
            "sas",
            AppSettings.sasRatio(this),
            1,
            255,
            IntSetter { value: Int -> this.setSasRatio(value) },
        )
    }

    private fun ampControl(
        field: String?,
        label: String?,
        value: Int,
        min: Int,
        max: Int,
    ): LinearLayout {
        val line: LinearLayout = LinearLayout(this)
        line.setOrientation(LinearLayout.VERTICAL)
        line.setPadding(0, dp(8), 0, 0)
        val name: TextView = text(label, if (isCompact()) 13 else 14, MainActivity.COLOR_TEXT)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        line.addView(name)

        val input: EditText =
            numericInput(
                "amp:" + field,
                value,
                min,
                max,
                IntSetter { next: Int -> setAmpValue(field, next) },
            )
        ampInputs.add(input)
        val rowLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        rowLp.setMargins(0, dp(4), 0, 0)
        line.addView(
            numericRow(input, value, min, max, IntSetter { next: Int -> setAmpValue(field, next) }),
            rowLp,
        )
        return line
    }

    private fun ampColumns(): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp >= 1050) return 4
        if (widthDp >= 720) return 2
        return 1
    }

    private fun ampGridLayout(column: Int, columns: Int): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val gap: Int = if (columns == 1) 0 else dp(6)
        lp.setMargins(if (column == 0) 0 else gap, 0, if (column == columns - 1) 0 else gap, 0)
        return lp
    }

    private fun numericRow(
        input: EditText?,
        fallback: Int,
        min: Int,
        max: Int,
        setter: IntSetter,
    ): LinearLayout {
        val row: LinearLayout = row()
        row.addView(
            stepButton(
                "-",
                Runnable {
                    setter.set(MainActivity.clamp(readNumeric(input, fallback) - 1, min, max))
                },
            )
        )
        val inputLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, if (isCompact()) dp(42) else dp(46), 1f)
        inputLp.setMargins(dp(6), 0, dp(6), 0)
        row.addView(input, inputLp)
        row.addView(
            stepButton(
                "+",
                Runnable {
                    setter.set(MainActivity.clamp(readNumeric(input, fallback) + 1, min, max))
                },
            )
        )
        return row
    }

    private fun pressureRow(
        input: EditText?,
        fallbackKpa: Int,
        minKpa: Int,
        maxKpa: Int,
        setter: IntSetter,
    ): LinearLayout {
        val row: LinearLayout = row()
        row.addView(
            stepButton(
                "-",
                Runnable {
                    setter.set(
                        MainActivity.clamp(readPressureKpa(input, fallbackKpa) - 10, minKpa, maxKpa)
                    )
                },
            )
        )
        val inputLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, if (isCompact()) dp(42) else dp(46), 1f)
        inputLp.setMargins(dp(6), 0, dp(6), 0)
        row.addView(input, inputLp)
        row.addView(
            stepButton(
                "+",
                Runnable {
                    setter.set(
                        MainActivity.clamp(readPressureKpa(input, fallbackKpa) + 10, minKpa, maxKpa)
                    )
                },
            )
        )
        return row
    }

    private fun stepButton(value: String?, action: Runnable): TextView {
        val button: TextView = text(value, 22, Color.WHITE)
        button.setGravity(Gravity.CENTER)
        button.setTypeface(Typeface.DEFAULT_BOLD)
        button.setClickable(true)
        button.setFocusable(true)
        button.setBackground(settingsActionBackground(false, MainActivity.COLOR_ACCENT_BLUE))
        button.setOnClickListener({ v ->
            rememberScrollPosition()
            action.run()
        })
        button.setMinWidth(dp(52))
        button.setMinHeight(dp(52))
        button.setContentDescription(
            if ("-".equals(value)) "Уменьшить значение" else "Увеличить значение"
        )
        return button
    }

    private fun numericInput(
        tag: String?,
        value: Int,
        min: Int,
        max: Int,
        setter: IntSetter,
    ): EditText {
        val input: EditText = EditText(this)
        input.setTag(tag)
        input.setSingleLine(true)
        input.setSelectAllOnFocus(true)
        input.setText(java.lang.String.valueOf(MainActivity.clamp(value, min, max)))
        input.setTextColor(Color.WHITE)
        input.setTextSize(17f)
        input.setGravity(Gravity.CENTER)
        input.setInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        input.setImeOptions(EditorInfo.IME_ACTION_DONE)
        input.setFilters(arrayOf<InputFilter?>(InputFilter.LengthFilter(4)))
        input.setPadding(dp(12), dp(7), dp(12), dp(7))
        input.setBackground(settingsActionBackground(true, MainActivity.COLOR_ACCENT_BLUE))
        input.setOnEditorActionListener({ view, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                setter.set(MainActivity.clamp(readNumeric(input, value), min, max))
                return@setOnEditorActionListener true
            }
            false
        })
        input.setOnFocusChangeListener({ view, hasFocus ->
            if (!hasFocus) setter.set(MainActivity.clamp(readNumeric(input, value), min, max))
        })
        return input
    }

    private fun pressureInput(
        tag: String?,
        valueKpa: Int,
        minKpa: Int,
        maxKpa: Int,
        setter: IntSetter,
    ): EditText {
        val input: EditText = EditText(this)
        input.setTag(tag)
        input.setSingleLine(true)
        input.setSelectAllOnFocus(true)
        input.setText(MainActivity.pressureBarText(MainActivity.clamp(valueKpa, minKpa, maxKpa)))
        input.setTextColor(Color.WHITE)
        input.setTextSize(17f)
        input.setGravity(Gravity.CENTER)
        input.setInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        input.setImeOptions(EditorInfo.IME_ACTION_DONE)
        input.setFilters(arrayOf<InputFilter?>(InputFilter.LengthFilter(4)))
        input.setPadding(dp(12), dp(7), dp(12), dp(7))
        input.setBackground(settingsActionBackground(true, MainActivity.COLOR_ACCENT_BLUE))
        input.setOnEditorActionListener({ view, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                setter.set(MainActivity.clamp(readPressureKpa(input, valueKpa), minKpa, maxKpa))
                return@setOnEditorActionListener true
            }
            false
        })
        input.setOnFocusChangeListener({ view, hasFocus ->
            if (!hasFocus)
                setter.set(MainActivity.clamp(readPressureKpa(input, valueKpa), minKpa, maxKpa))
        })
        return input
    }

    private fun readNumeric(input: EditText?, fallback: Int): Int {
        if (input == null || input.getText() == null) return fallback
        val raw = input.getText().toString().trim { it <= ' ' }
        if (raw.isEmpty() || "-".equals(raw) || "+".equals(raw)) return fallback
        try {
            return Integer.parseInt(raw)
        } catch (e: NumberFormatException) {
            return fallback
        }
    }

    private fun readPressureKpa(input: EditText?, fallbackKpa: Int): Int {
        if (input == null || input.getText() == null) return fallbackKpa
        val raw = input.getText().toString().trim { it <= ' ' }.replace(',', '.')
        if (raw.isEmpty() || ".".equals(raw)) return fallbackKpa
        try {
            val bar: Float = java.lang.Float.parseFloat(raw)
            return Math.round(bar * 10f) * 10
        } catch (e: NumberFormatException) {
            return fallbackKpa
        }
    }

    private fun updateOverlayToggle() {
        if (navigationOverlayToggle == null) return
        val enabled: Boolean = AppSettings.navOverlayEnabled(this)
        if (navigationOverlayToggle!!.isChecked() == enabled) return
        navigationOverlayToggle!!.setOnCheckedChangeListener(null)
        navigationOverlayToggle!!.setChecked(enabled)
        navigationOverlayToggle!!.setOnCheckedChangeListener({
            button: CompoundButton?,
            enabled: Boolean ->
            this.toggleNavOverlay(button, enabled)
        })
    }

    private fun updateMicroManeuverToggle() {
        if (microManeuverToggle == null) return
        val enabled: Boolean = AppSettings.navMicroManeuvers(this)
        if (microManeuverToggle!!.isChecked() == enabled) return
        microManeuverToggle!!.setOnCheckedChangeListener(null)
        microManeuverToggle!!.setChecked(enabled)
        microManeuverToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean
            ->
            this.toggleMicroManeuvers(button, enabled)
        })
    }

    private fun updateStraightUntilMainToggle() {
        if (straightUntilMainToggle == null) return
        val enabled: Boolean = AppSettings.navStraightUntilMain(this)
        if (straightUntilMainToggle!!.isChecked() == enabled) return
        straightUntilMainToggle!!.setOnCheckedChangeListener(null)
        straightUntilMainToggle!!.setChecked(enabled)
        straightUntilMainToggle!!.setOnCheckedChangeListener({
            button: CompoundButton?,
            enabled: Boolean ->
            this.toggleStraightUntilMain(button, enabled)
        })
    }

    private fun updateNavTbtToggle() {
        if (navTbtToggle == null) return
        val enabled: Boolean = AppSettings.navTbt(this)
        if (navTbtToggle!!.isChecked() == enabled) return
        navTbtToggle!!.setOnCheckedChangeListener(null)
        navTbtToggle!!.setChecked(enabled)
        navTbtToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean ->
            this.toggleNavTbt(button, enabled)
        })
    }

    private fun updateOverspeedToggle() {
        if (overspeedToggle == null) return
        val enabled: Boolean = AppSettings.navOverspeedTextEnabled(this)
        if (overspeedToggle!!.isChecked() == enabled) return
        overspeedToggle!!.setOnCheckedChangeListener(null)
        overspeedToggle!!.setChecked(enabled)
        overspeedToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean ->
            this.toggleOverspeedText(button, enabled)
        })
    }

    private fun updateNavDebugToggle() {
        if (navigationDebugToggle == null) return
        val enabled: Boolean = AppSettings.navOverlayEnabled(this)
        if (navigationDebugToggle!!.isChecked() == enabled) return
        navigationDebugToggle!!.setOnCheckedChangeListener(null)
        navigationDebugToggle!!.setChecked(enabled)
        navigationDebugToggle!!.setOnCheckedChangeListener({
            button: CompoundButton?,
            enabled: Boolean ->
            this.toggleNavDebug(button, enabled)
        })
    }

    private fun updateCanbusDebugToggle() {
        if (canbusDebugToggle == null) return
        val enabled: Boolean = AppSettings.canbusDebugVisible(this)
        if (canbusDebugToggle!!.isChecked() == enabled) return
        canbusDebugToggle!!.setOnCheckedChangeListener(null)
        canbusDebugToggle!!.setChecked(enabled)
        canbusDebugToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean
            ->
            this.toggleCanbusDebug(button, enabled)
        })
    }

    private fun updateAmpEnabledToggle() {
        if (ampEnabledToggle == null) return
        val enabled: Boolean = AppSettings.ampEnabled(this)
        if (ampEnabledToggle!!.isChecked() == enabled) return
        ampEnabledToggle!!.setOnCheckedChangeListener(null)
        ampEnabledToggle!!.setChecked(enabled)
        ampEnabledToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean ->
            this.toggleAmpEnabled(button, enabled)
        })
    }

    private fun updateCallEnabledToggle() {
        if (callEnabledToggle == null) return
        val enabled: Boolean = AppSettings.callEnabled(this)
        if (callEnabledToggle!!.isChecked() == enabled) return
        callEnabledToggle!!.setOnCheckedChangeListener(null)
        callEnabledToggle!!.setChecked(enabled)
        callEnabledToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean
            ->
            this.toggleCallEnabled(button, enabled)
        })
    }

    private fun updateMediaDebugToggle() {
        if (mediaDebugToggle == null) return
        val enabled: Boolean = AppSettings.mediaOverlayEnabled(this)
        if (mediaDebugToggle!!.isChecked() == enabled) return
        mediaDebugToggle!!.setOnCheckedChangeListener(null)
        mediaDebugToggle!!.setChecked(enabled)
        mediaDebugToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean ->
            this.toggleMediaDebug(button, enabled)
        })
    }

    private fun updateTpmsAlertsToggle() {
        if (tpmsAlertsToggle == null) return
        val enabled: Boolean = AppSettings.tpmsAlertsEnabled(this)
        if (tpmsAlertsToggle!!.isChecked() == enabled) return
        tpmsAlertsToggle!!.setOnCheckedChangeListener(null)
        tpmsAlertsToggle!!.setChecked(enabled)
        tpmsAlertsToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean ->
            this.toggleTpmsAlerts(button, enabled)
        })
    }

    private fun updateTpmsSoundToggle() {
        if (tpmsSoundToggle == null) return
        val enabled: Boolean = AppSettings.tpmsSoundAlertsEnabled(this)
        if (tpmsSoundToggle!!.isChecked() == enabled) return
        tpmsSoundToggle!!.setOnCheckedChangeListener(null)
        tpmsSoundToggle!!.setChecked(enabled)
        tpmsSoundToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean ->
            this.toggleTpmsSoundAlerts(button, enabled)
        })
    }

    private fun updateAutoStartToggle() {
        if (autoStartToggle == null) return
        val enabled: Boolean = AppSettings.autoStart(this)
        if (autoStartToggle!!.isChecked() == enabled) return
        autoStartToggle!!.setOnCheckedChangeListener(null)
        autoStartToggle!!.setChecked(enabled)
        autoStartToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean ->
            this.toggleAutoStart(button, enabled)
        })
    }

    private fun updateMediaTabToggle() {
        if (mediaTabToggle == null) return
        val enabled: Boolean = AppSettings.mediaTabVisible(this)
        if (mediaTabToggle!!.isChecked() == enabled) return
        mediaTabToggle!!.setOnCheckedChangeListener(null)
        mediaTabToggle!!.setChecked(enabled)
        mediaTabToggle!!.setOnCheckedChangeListener({ button: CompoundButton?, enabled: Boolean ->
            this.toggleMediaTab(button, enabled)
        })
    }

    private fun updateAmpInputs() {
        val state = StateStore.amp()
        if (ampSummary != null) {
            ampSummary!!.setText(MainActivity.ampUiText(state))
        }
        if (ampVisualizer != null) {
            ampVisualizer!!.setState(state, AppSettings.ampEnabled(this))
        }
        if (ampInputs.isEmpty()) return
        for (input: EditText? in ampInputs) {
            if (input == null || input.hasFocus() || input.getTag() == null) continue
            val tag = java.lang.String.valueOf(input.getTag())
            if (!tag.startsWith("amp:")) continue
            val field = tag.substring(4)
            val value = java.lang.String.valueOf(displayAmpValue(field))
            if (!value.contentEquals(input.getText())) input.setText(value)
        }
    }

    private fun updateTpmsInputs() {
        if (tpmsInputs.isEmpty()) return
        for (input: EditText? in tpmsInputs) {
            if (input == null || input.hasFocus() || input.getTag() == null) continue
            val tag = java.lang.String.valueOf(input.getTag())
            val value: String?
            if (tag.startsWith("tpmsBar:")) {
                value = MainActivity.pressureBarText(displayTpmsValue(tag.substring(8)))
            } else if (tag.startsWith("tpms:")) {
                value = java.lang.String.valueOf(displayTpmsValue(tag.substring(5)))
            } else {
                continue
            }
            if (!value.contentEquals(input.getText())) input.setText(value)
        }
    }

    private fun displayAmpValue(field: String?): Int {
        val s = StateStore.amp()
        if ("volume".equals(field)) return s.volume
        if ("balance".equals(field)) return s.balance - 10
        if ("fader".equals(field)) return s.fader - 10
        if ("bass".equals(field)) return s.bass - 10
        if ("mid".equals(field)) return s.mid - 10
        if ("treble".equals(field)) return s.treble - 10
        if ("mode".equals(field)) return s.mode
        return 0
    }

    private fun displayTpmsValue(field: String?): Int {
        if ("lowP".equals(field)) return AppSettings.tpmsLowPressureKpa(this)
        if ("highP".equals(field)) return AppSettings.tpmsHighPressureKpa(this)
        if ("lowT".equals(field)) return AppSettings.tpmsLowTempC(this)
        if ("highT".equals(field)) return AppSettings.tpmsHighTempC(this)
        return 0
    }

    private fun updateFirmwareActionButton() {
        if (
            firmwareActionButton == null ||
                firmwareActionText == null ||
                firmwareActionHintText == null
        )
            return
        val s = StateStore.updates()
        val busy: Boolean = firmwareUpdater != null && firmwareUpdater!!.busy()
        val progress: Int = firmwareProgressPercent(s)
        if (busy) {
            firmwareActionText!!.setText(
                if (s.firmwareFlashing) "Прошивка " + progress + "%" else "Подготовка BIN"
            )
            firmwareActionHintText!!.setText(MainActivity.displayStatus(s.firmwareStatus))
        } else if (pendingFirmwareUri != null) {
            firmwareActionText!!.setText("Прошить BIN")
            firmwareActionHintText!!.setText(pendingFirmwareLabel)
        } else {
            firmwareActionText!!.setText("Выбрать BIN")
            firmwareActionHintText!!.setText("ручной файл прошивки до 112 KB")
        }
        firmwareActionButton!!.setEnabled(!busy)
        firmwareActionButton!!.setBackground(
            settingsButtonBackground(busy || pendingFirmwareUri != null)
        )
        if (firmwareProgressFill == null) return
        val parentWidth: Int = firmwareActionButton!!.getWidth()
        val fillWidth: Int = if (busy) Math.round(parentWidth * progress / 100f) else 0
        val lp: ViewGroup.LayoutParams? = firmwareProgressFill!!.getLayoutParams()
        if (lp != null && lp.width != fillWidth) {
            lp.width = fillWidth
            firmwareProgressFill!!.setLayoutParams(lp)
        }
    }

    private fun firmwareProgressPercent(s: UpdateState?): Int {
        if (s == null || s.firmwareTotalBytes <= 0) return 0
        val done: Long = Math.max(0, Math.min(s.firmwareDownloadedBytes, s.firmwareTotalBytes))
        return Math.max(0, Math.min(100, Math.round(done * 100f / s.firmwareTotalBytes)))
    }

    private fun outerPadding(): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp >= 1200) return dp(24)
        if (widthDp < 560) return dp(10)
        return dp(16)
    }

    private fun applyRootPadding() {
        if (rootLayout == null) return
        if (!settingsMode && selectedTab == MainActivity.TAB_TPMS) {
            rootLayout!!.setPadding(0, rootInsetTop, 0, rootInsetBottom)
            return
        }
        if (settingsMode) {
            rootLayout!!.setPadding(0, rootInsetTop, 0, rootInsetBottom)
            return
        }
        rootLayout!!.setPadding(
            outerPadding(),
            (if (isCompact()) dp(10) else dp(18)) + rootInsetTop,
            outerPadding(),
            dp(22) + rootInsetBottom,
        )
    }

    private fun isCompact(): Boolean {
        return screenWidthDp() < 760
    }

    private fun tpmsWidgetMode(): Boolean {
        return isCompact() || isLandscapeWindow()
    }

    private fun tpmsWidgetLocksSettings(): Boolean {
        return false
    }

    private fun normalizeModeForWindow() {
        if (!tpmsWidgetLocksSettings() || !settingsMode) return
        settingsMode = false
        selectedTab = MainActivity.TAB_TPMS
    }

    private fun isLandscapeWindow(): Boolean {
        val widthDp: Int = screenWidthDp()
        val heightDp: Int = screenHeightDp()
        return widthDp > 0 && heightDp > 0 && widthDp > heightDp
    }

    private fun tabButtonWidth(): Int {
        val widthDp: Int = screenWidthDp()
        if (widthDp >= 1200) return dp(148)
        if (widthDp >= 900) return dp(132)
        return dp(116)
    }

    private fun screenWidthDp(): Int {
        val widthDp: Int = getResources().getConfiguration().screenWidthDp
        if (widthDp > 0) return widthDp
        val density: Float = getResources().getDisplayMetrics().density
        return Math.round(getResources().getDisplayMetrics().widthPixels / density)
    }

    private fun screenHeightDp(): Int {
        val heightDp: Int = getResources().getConfiguration().screenHeightDp
        if (heightDp > 0) return heightDp
        val density: Float = getResources().getDisplayMetrics().density
        return Math.round(getResources().getDisplayMetrics().heightPixels / density)
    }

    private fun pill(value: String?, color: Int): TextView {
        val view: TextView = text(value, if (isCompact()) 12 else 13, Color.WHITE)
        view.setGravity(Gravity.CENTER)
        view.setSingleLine(true)
        view.setTypeface(Typeface.DEFAULT_BOLD)
        view.setPadding(dp(10), dp(6), dp(10), dp(6))
        view.setBackground(glassButton(color))
        return view
    }

    private fun chipLayout(first: Boolean): LinearLayout.LayoutParams {
        val lp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.setMargins(if (first) 0 else dp(6), 0, if (first) dp(6) else 0, 0)
        return lp
    }

    private fun gradient(
        startColor: Int,
        endColor: Int,
        strokeColor: Int,
        strokeWidth: Int,
        radius: Int,
    ): GradientDrawable {
        val drawable: GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(startColor, endColor),
            )
        drawable.setCornerRadius(radius.toFloat())
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor)
        return drawable
    }

    private fun glassPanel(tint: Int): GradientDrawable? {
        if (settingsMode) return settingsPanelBackground()
        return gradient(
            softColor(tint, 46),
            softColor(MainActivity.COLOR_PANEL_SOFT, 166),
            softColor(Color.WHITE, 42),
            dp(1),
            dp(8),
        )
    }

    private fun glassButton(tint: Int): Drawable {
        if (settingsMode) return settingsButtonBackground(tint == MainActivity.COLOR_ACCENT)
        return gradient(
            softColor(tint, 64),
            softColor(MainActivity.COLOR_PANEL_SOFT, 132),
            softColor(Color.WHITE, 36),
            dp(1),
            dp(8),
        )
    }

    private fun settingsPanelBackground(): GradientDrawable? {
        return settingsPanelBackground(MainActivity.COLOR_ACCENT_BLUE)
    }

    private fun settingsPanelBackground(tint: Int): GradientDrawable? {
        return gradient(
            MainActivity.COLOR_SETTINGS_PANEL,
            Color.rgb(20, 28, 38),
            softColor(settingsAccent(tint), 72),
            dp(1),
            dp(12),
        )
    }

    private fun settingsInsetBackground(): GradientDrawable? {
        return settingsInsetBackground(MainActivity.COLOR_ACCENT_BLUE)
    }

    private fun settingsInsetBackground(tint: Int): GradientDrawable? {
        return gradient(
            MainActivity.COLOR_SETTINGS_PANEL_ALT,
            Color.rgb(23, 29, 38),
            softColor(settingsAccent(tint), 52),
            dp(1),
            dp(10),
        )
    }

    private fun settingsButtonBackground(selected: Boolean): Drawable {
        return settingsActionBackground(selected, MainActivity.COLOR_ACCENT_BLUE)
    }

    private fun settingsActionBackground(selected: Boolean, tint: Int): Drawable {
        val accent: Int = settingsAccent(tint)
        val start: Int =
            if (selected) softColor(accent, 66) else MainActivity.COLOR_SETTINGS_PANEL_ALT
        val end: Int = if (selected) Color.rgb(22, 31, 48) else Color.rgb(21, 27, 36)
        val content: GradientDrawable =
            gradient(
                start,
                end,
                if (selected) softColor(accent, 78) else Color.TRANSPARENT,
                if (selected) dp(1) else 0,
                dp(10),
            )
        return RippleDrawable(ColorStateList.valueOf(softColor(accent, 88)), content, null)
    }

    private fun settingsAccent(tint: Int): Int {
        return if (tint == Color.TRANSPARENT) MainActivity.COLOR_ACCENT_BLUE else tint
    }

    private fun softColor(color: Int, alpha: Int): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun round(
        fill: Int,
        radius: Int,
        strokeColor: Int,
        strokeWidth: Int,
    ): GradientDrawable {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(fill)
        drawable.setCornerRadius(radius.toFloat())
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor)
        return drawable
    }

    private fun row(): LinearLayout {
        val row: LinearLayout = LinearLayout(this)
        row.setOrientation(LinearLayout.HORIZONTAL)
        row.setGravity(Gravity.CENTER_VERTICAL)
        return row
    }

    private fun iconBadge(value: String?, color: Int): TextView {
        val badge: TextView = text(value, if (isCompact()) 17 else 19, color)
        badge.setGravity(Gravity.CENTER)
        badge.setTypeface(Typeface.DEFAULT_BOLD)
        badge.setMinWidth(if (isCompact()) dp(24) else dp(28))
        badge.setMinHeight(if (isCompact()) dp(24) else dp(28))
        badge.setPadding(dp(2), dp(2), dp(2), dp(2))
        return badge
    }

    private abstract inner class SettingsPreviewView(context: Context?) : View(context) {
        @JvmField val p: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
        @JvmField val r: RectF = RectF()
        @JvmField val path: Path = Path()

        fun card(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
            p.setShader(null)
            p.setStyle(Paint.Style.FILL)
            p.setColor(MainActivity.COLOR_SETTINGS_PANEL_ALT)
            r.set(left, top, right, bottom)
            canvas.drawRoundRect(r, dp(8).toFloat(), dp(8).toFloat(), p)
        }

        fun fill(
            canvas: Canvas,
            color: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
        ) {
            p.setShader(null)
            p.setStyle(Paint.Style.FILL)
            p.setColor(color)
            r.set(left, top, right, bottom)
            canvas.drawRoundRect(r, radius, radius, p)
        }

        fun stroke(
            canvas: Canvas,
            color: Int,
            width: Float,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
        ) {
            p.setShader(null)
            p.setStyle(Paint.Style.STROKE)
            p.setStrokeWidth(width)
            p.setColor(color)
            r.set(left, top, right, bottom)
            canvas.drawRoundRect(r, radius, radius, p)
            p.setStyle(Paint.Style.FILL)
        }

        fun label(
            canvas: Canvas,
            value: String?,
            x: Float,
            y: Float,
            sp: Float,
            color: Int,
            bold: Boolean,
        ) {
            p.setShader(null)
            p.setStyle(Paint.Style.FILL)
            p.setColor(color)
            p.setTypeface(if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            p.setTextSize(sp)
            p.setTextAlign(Paint.Align.LEFT)
            canvas.drawText(value!!, x, y, p)
        }

        fun center(
            canvas: Canvas,
            value: String?,
            x: Float,
            y: Float,
            sp: Float,
            color: Int,
            bold: Boolean,
        ) {
            p.setShader(null)
            p.setStyle(Paint.Style.FILL)
            p.setColor(color)
            p.setTypeface(if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            p.setTextSize(sp)
            p.setTextAlign(Paint.Align.CENTER)
            val fm: Paint.FontMetrics? = p.getFontMetrics()
            canvas.drawText(value!!, x, y - (fm!!.ascent + fm.descent) / 2f, p)
        }

        fun chip(canvas: Canvas, value: String?, x: Float, y: Float, fill: Int, color: Int) {
            p.setTypeface(Typeface.DEFAULT_BOLD)
            p.setTextSize((if (isCompact()) dp(10) else dp(11)).toFloat())
            p.setTextAlign(Paint.Align.LEFT)
            val w: Float = Math.max(dp(58).toFloat(), p.measureText(value) + dp(18))
            fill(canvas, fill, x, y, x + w, y + dp(28), dp(8).toFloat())
            center(
                canvas,
                value,
                x + w / 2f,
                y + dp(14),
                (if (isCompact()) dp(10) else dp(11)).toFloat(),
                color,
                true,
            )
        }
    }

    private inner class TpmsSettingsPreviewView(context: Context?) : SettingsPreviewView(context) {
        override protected fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w: Float = width.toFloat()
            val h: Float = height.toFloat()
            card(canvas, 0f, 0f, w, h)
            val pad: Float = dp(18).toFloat()
            label(
                canvas,
                "Карта срабатывания",
                pad,
                dp(25).toFloat(),
                dp(15).toFloat(),
                Color.WHITE,
                true,
            )
            legend(canvas, w - pad - dp(270), dp(10).toFloat())
            val firstY: Float = dp(48).toFloat()
            val gap: Float = (if (isCompact()) dp(46) else dp(54)).toFloat()
            drawScale(
                canvas,
                "Давление",
                ("норма " +
                    MainActivity.pressureBarText(
                        AppSettings.tpmsLowPressureKpa(this@MainActivity)
                    ) +
                    "-" +
                    MainActivity.pressureBarText(
                        AppSettings.tpmsHighPressureKpa(this@MainActivity)
                    ) +
                    " bar"),
                MainActivity.pressureBarText(AppSettings.tpmsLowPressureKpa(this@MainActivity)),
                MainActivity.pressureBarText(AppSettings.tpmsHighPressureKpa(this@MainActivity)),
                pad,
                firstY,
                w - pad,
            )
            drawScale(
                canvas,
                "Температура",
                ("норма " +
                    AppSettings.tpmsLowTempC(this@MainActivity) +
                    "-" +
                    AppSettings.tpmsHighTempC(this@MainActivity) +
                    "C"),
                java.lang.String.valueOf(AppSettings.tpmsLowTempC(this@MainActivity)),
                java.lang.String.valueOf(AppSettings.tpmsHighTempC(this@MainActivity)),
                pad,
                firstY + gap,
                w - pad,
            )
            drawOverlay(canvas, pad, h - dp(50), w - pad, h - dp(12))
        }

        fun legend(canvas: Canvas, x: Float, y: Float) {
            if (x < dp(220)) return
            legendItem(canvas, x, y, -0xe2c4c8, "норма")
            legendItem(canvas, x + dp(86), y, -0xbac4dc, "жёлтая")
            legendItem(canvas, x + dp(184), y, -0xb9d2cc, "красная")
        }

        fun legendItem(canvas: Canvas, x: Float, y: Float, color: Int, text: String?) {
            fill(canvas, color, x, y + dp(5), x + dp(18), y + dp(13), dp(4).toFloat())
            label(
                canvas,
                text,
                x + dp(24),
                y + dp(14),
                dp(11).toFloat(),
                MainActivity.COLOR_MUTED,
                true,
            )
        }

        fun drawScale(
            canvas: Canvas,
            title: String?,
            range: String?,
            low: String?,
            high: String?,
            left: Float,
            y: Float,
            right: Float,
        ) {
            val labelW: Float = (if (isCompact()) dp(124) else dp(172)).toFloat()
            label(canvas, title, left, y + dp(17), dp(13).toFloat(), Color.WHITE, true)
            label(
                canvas,
                range,
                left,
                y + dp(36),
                dp(11).toFloat(),
                MainActivity.COLOR_MUTED,
                false,
            )
            val barLeft: Float = left + labelW
            val barRight: Float = right
            val barTop: Float = y + dp(5)
            val barBottom: Float = y + dp(23)
            val redW: Float = (barRight - barLeft) * 0.18f
            val yellowW: Float = (barRight - barLeft) * 0.15f
            p.setShader(
                LinearGradient(
                    barLeft,
                    0f,
                    barRight,
                    0f,
                    intArrayOf(-0xcfdcd6, -0xc6cce1, -0xe6cace, -0xc6cce1, -0xcfdcd6),
                    floatArrayOf(0f, 0.22f, 0.50f, 0.78f, 1f),
                    Shader.TileMode.CLAMP,
                )
            )
            p.setStyle(Paint.Style.FILL)
            r.set(barLeft, barTop, barRight, barBottom)
            canvas.drawRoundRect(r, dp(9).toFloat(), dp(9).toFloat(), p)
            p.setShader(null)

            val lowX: Float = barLeft + (barRight - barLeft) * 0.33f
            val highX: Float = barLeft + (barRight - barLeft) * 0.67f
            p.setStrokeWidth(dp(2).toFloat())
            p.setColor(-0x47261b11)
            canvas.drawLine(lowX, barTop - dp(7), lowX, barBottom + dp(10), p)
            canvas.drawLine(highX, barTop - dp(7), highX, barBottom + dp(10), p)
            valuePill(canvas, low, lowX, barBottom + dp(20))
            valuePill(canvas, high, highX, barBottom + dp(20))
        }

        fun drawOverlay(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
            fill(canvas, -0xbbd7ce, left, top, right, bottom, dp(8).toFloat())
            fill(canvas, -0x82c4b8, left, top, left + dp(10), bottom, dp(8).toFloat())
            if (right - left < dp(620)) {
                label(
                    canvas,
                    "красная плашка · звук до ×",
                    left + dp(14),
                    top + dp(24),
                    dp(11).toFloat(),
                    Color.rgb(238, 232, 235),
                    true,
                )
                center(
                    canvas,
                    "×",
                    right - dp(22),
                    top + (bottom - top) / 2f,
                    dp(22).toFloat(),
                    Color.rgb(238, 232, 235),
                    true,
                )
                return
            }
            label(
                canvas,
                "красная плашка поверх экрана",
                left + dp(14),
                top + dp(24),
                dp(12).toFloat(),
                Color.rgb(238, 232, 235),
                true,
            )
            center(
                canvas,
                "×",
                right - dp(22),
                top + (bottom - top) / 2f,
                dp(22).toFloat(),
                Color.rgb(238, 232, 235),
                true,
            )
            p.setStyle(Paint.Style.STROKE)
            p.setStrokeWidth(dp(2).toFloat())
            p.setColor(-0x60172824)
            val sx: Float = right - dp(92)
            canvas.drawArc(sx, top + dp(10), sx + dp(18), top + dp(28), -36f, 72f, false, p)
            canvas.drawArc(sx - dp(7), top + dp(6), sx + dp(30), top + dp(32), -36f, 72f, false, p)
            p.setStyle(Paint.Style.FILL)
            center(
                canvas,
                "звук идёт, пока не нажать ×",
                right - dp(185),
                top + (bottom - top) / 2f,
                dp(12).toFloat(),
                Color.rgb(238, 232, 235),
                true,
            )
        }

        fun valuePill(canvas: Canvas, value: String?, cx: Float, cy: Float) {
            p.setTypeface(Typeface.DEFAULT_BOLD)
            p.setTextSize(dp(11).toFloat())
            p.setTextAlign(Paint.Align.LEFT)
            val width: Float = Math.max(dp(42).toFloat(), p.measureText(value) + dp(16))
            fill(
                canvas,
                MainActivity.COLOR_SETTINGS_SELECTED,
                cx - width / 2f,
                cy - dp(12),
                cx + width / 2f,
                cy + dp(12),
                dp(7).toFloat(),
            )
            center(canvas, value, cx, cy, dp(11).toFloat(), Color.WHITE, true)
        }
    }

    private inner class NavigationSettingsPreviewView(context: Context?) :
        SettingsPreviewView(context) {
        override protected fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w: Float = width.toFloat()
            val h: Float = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val routeMode: Int = NavigationModeSettings.mode(this@MainActivity)
            val textMode: Int = AppSettings.navTextMode(this@MainActivity)
            val sourceMode: Int = AppSettings.navSourceMode(this@MainActivity)
            val normalMode: Boolean = routeMode == NavigationOutputMode.NORMAL
            val assistant: Boolean = AppSettings.navMicroManeuvers(this@MainActivity)
            val overspeed: Boolean = AppSettings.navOverspeedTextEnabled(this@MainActivity)
            val phase: Float = (System.currentTimeMillis() % 1800L) / 1800f

            val pad: Float = dp(18).toFloat()
            p.setShader(
                LinearGradient(
                    0f,
                    0f,
                    w,
                    h,
                    intArrayOf(-0xeee7dd, -0xf4efe9, -0xeae4dc),
                    floatArrayOf(0f, 0.58f, 1f),
                    Shader.TileMode.CLAMP,
                )
            )
            p.setStyle(Paint.Style.FILL)
            r.set(0f, 0f, w, h)
            canvas.drawRoundRect(r, dp(8).toFloat(), dp(8).toFloat(), p)
            p.setShader(null)
            stroke(canvas, 0x28ffffff, dp(1).toFloat(), 0f, 0f, w, h, dp(8).toFloat())

            val left: Float = pad
            val top: Float = pad
            val bottom: Float = h - pad
            val routeW: Float = w * (if (isCompact()) 0.55f else 0.58f)
            val rightX: Float = left + routeW + dp(16)
            val rightW: Float = w - rightX - pad

            drawRouteSurface(
                canvas,
                left,
                top,
                left + routeW,
                bottom,
                routeMode,
                normalMode && assistant,
                phase,
            )
            drawSourceCard(canvas, rightX, top, rightX + rightW, top + dp(42), sourceMode)
            drawTxCard(
                canvas,
                rightX,
                top + dp(50),
                rightX + rightW,
                top + dp(100),
                routeMode,
                textMode,
            )
            drawAssistantCard(
                canvas,
                rightX,
                top + dp(108),
                rightX + rightW,
                top + dp(150),
                normalMode,
                assistant,
            )
            drawSpeedCard(canvas, rightX, top + dp(158), rightX + rightW, bottom, overspeed)

            postInvalidateDelayed(700L)
        }

        fun drawRouteSurface(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            routeMode: Int,
            assistant: Boolean,
            phase: Float,
        ) {
            fill(canvas, -0xf2ece5, left, top, right, bottom, dp(10).toFloat())
            stroke(canvas, 0x22ffffff, dp(1).toFloat(), left, top, right, bottom, dp(10).toFloat())
            label(
                canvas,
                routeModeTitle(routeMode),
                left + dp(14),
                top + dp(24),
                dp(14).toFloat(),
                Color.WHITE,
                true,
            )
            label(
                canvas,
                routeModeHint(routeMode),
                left + dp(14),
                top + dp(43),
                dp(11).toFloat(),
                MainActivity.COLOR_MUTED,
                false,
            )
            if (routeMode == NavigationOutputMode.TBT) {
                drawTbtPreview(canvas, left, top + dp(56), right, bottom, phase)
            } else if (routeMode == NavigationOutputMode.FINISH_DIRECTION) {
                drawFinishPreview(canvas, left, top + dp(54), right, bottom, phase)
            } else {
                drawNormalPreview(canvas, left, top + dp(54), right, bottom, assistant, phase)
            }
        }

        fun drawNormalPreview(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            assistant: Boolean,
            phase: Float,
        ) {
            val width: Float = right - left
            val roadCx: Float = left + width * 0.50f
            p.setStyle(Paint.Style.STROKE)
            p.setStrokeWidth(dp(2).toFloat())
            p.setColor(0x36ffffff)
            canvas.drawLine(roadCx - width * 0.26f, bottom, roadCx - width * 0.07f, top, p)
            canvas.drawLine(roadCx + width * 0.26f, bottom, roadCx + width * 0.07f, top, p)
            p.setStrokeWidth(dp(3).toFloat())
            p.setColor(-0xc4b9ab)
            canvas.drawLine(roadCx, bottom - dp(18), roadCx, top + dp(8), p)
            p.setStyle(Paint.Style.FILL)

            fill(
                canvas,
                -0xb5c8ee,
                left + dp(14),
                top + dp(6),
                left + width * 0.54f,
                top + dp(56),
                dp(9).toFloat(),
            )
            center(canvas, "↱", left + dp(39), top + dp(32), dp(28).toFloat(), -0x3bc5, true)
            label(canvas, "240 м", left + dp(70), top + dp(29), dp(20).toFloat(), Color.WHITE, true)
            label(
                canvas,
                "направо, затем прямо",
                left + dp(70),
                top + dp(48),
                dp(11).toFloat(),
                -0x2886,
                true,
            )

            val laneTop: Float = bottom - dp(74)
            fill(
                canvas,
                -0xe8e0d6,
                left + dp(14),
                laneTop,
                right - dp(14),
                bottom - dp(16),
                dp(9).toFloat(),
            )
            label(
                canvas,
                "серая дорога",
                left + dp(28),
                laneTop + dp(22),
                dp(11).toFloat(),
                MainActivity.COLOR_MUTED,
                true,
            )
            drawLane(canvas, left + width * 0.43f, laneTop + dp(30), "←")
            drawLane(canvas, left + width * 0.53f, laneTop + dp(30), "↑")
            drawLane(canvas, left + width * 0.63f, laneTop + dp(30), "→")
            if (assistant) {
                val pulse: Float = 0.70f + 0.30f * Math.sin(phase * Math.PI * 2f).toFloat()
                fill(
                    canvas,
                    Color.argb(
                        Math.round(170 * pulse),
                        Color.red(MainActivity.COLOR_ACCENT_BLUE),
                        Color.green(MainActivity.COLOR_ACCENT_BLUE),
                        Color.blue(MainActivity.COLOR_ACCENT_BLUE),
                    ),
                    right - dp(126),
                    top + dp(8),
                    right - dp(14),
                    top + dp(56),
                    dp(9).toFloat(),
                )
                label(
                    canvas,
                    "ассистент",
                    right - dp(112),
                    top + dp(28),
                    dp(11).toFloat(),
                    Color.WHITE,
                    true,
                )
                label(
                    canvas,
                    "прямо",
                    right - dp(112),
                    top + dp(47),
                    dp(14).toFloat(),
                    Color.WHITE,
                    true,
                )
            } else {
                fill(
                    canvas,
                    -0xdfd8cd,
                    right - dp(126),
                    top + dp(8),
                    right - dp(14),
                    top + dp(56),
                    dp(9).toFloat(),
                )
                label(
                    canvas,
                    "ассистент",
                    right - dp(112),
                    top + dp(29),
                    dp(11).toFloat(),
                    MainActivity.COLOR_MUTED,
                    true,
                )
                label(
                    canvas,
                    "выкл",
                    right - dp(112),
                    top + dp(47),
                    dp(13).toFloat(),
                    MainActivity.COLOR_MUTED,
                    true,
                )
            }
        }

        fun drawTbtPreview(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            phase: Float,
        ) {
            val gap: Float = dp(8).toFloat()
            val itemW: Float = (right - left - dp(28) - gap * 3f) / 4f
            val y: Float = top + dp(10)
            drawTbtItem(canvas, left + dp(14), y, itemW, "↱", "сейчас", true)
            drawTbtItem(canvas, left + dp(14) + (itemW + gap), y, itemW, "↑", "2", false)
            drawTbtItem(canvas, left + dp(14) + (itemW + gap) * 2f, y, itemW, "↰", "3", false)
            drawTbtItem(canvas, left + dp(14) + (itemW + gap) * 3f, y, itemW, "⚑", "финиш", false)
            fill(
                canvas,
                -0xeae2d9,
                left + dp(14),
                bottom - dp(58),
                right - dp(14),
                bottom - dp(16),
                dp(9).toFloat(),
            )
            label(
                canvas,
                "TBT отправляет отдельные иконки без подмены серой дороги",
                left + dp(28),
                bottom - dp(32),
                dp(12).toFloat(),
                MainActivity.COLOR_MUTED,
                true,
            )
        }

        fun drawTbtItem(
            canvas: Canvas,
            x: Float,
            y: Float,
            width: Float,
            icon: String?,
            text: String?,
            selected: Boolean,
        ) {
            fill(
                canvas,
                if (selected) -0xdfcfb6 else -0xeae2d9,
                x,
                y,
                x + width,
                y + dp(70),
                dp(10).toFloat(),
            )
            stroke(
                canvas,
                if (selected) MainActivity.COLOR_ACCENT_BLUE else 0x22ffffff,
                dp(1).toFloat(),
                x,
                y,
                x + width,
                y + dp(70),
                dp(10).toFloat(),
            )
            center(
                canvas,
                icon,
                x + width / 2f,
                y + dp(31),
                dp(25).toFloat(),
                if (selected) MainActivity.COLOR_ACCENT_BLUE else MainActivity.COLOR_MUTED,
                true,
            )
            center(
                canvas,
                text,
                x + width / 2f,
                y + dp(55),
                dp(11).toFloat(),
                if (selected) Color.WHITE else MainActivity.COLOR_MUTED,
                true,
            )
        }

        fun drawFinishPreview(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            phase: Float,
        ) {
            val cx: Float = left + (right - left) * 0.42f
            val cy: Float = top + (bottom - top) * 0.48f
            val radius: Float = Math.min(right - left, bottom - top) * 0.27f
            p.setStyle(Paint.Style.STROKE)
            p.setStrokeWidth(dp(2).toFloat())
            p.setColor(0x30ffffff)
            canvas.drawCircle(cx, cy, radius, p)
            canvas.drawCircle(cx, cy, radius * 0.72f, p)
            p.setStyle(Paint.Style.FILL)
            center(
                canvas,
                "N",
                cx,
                cy - radius - dp(8),
                dp(11).toFloat(),
                MainActivity.COLOR_MUTED,
                true,
            )
            center(canvas, "⚑", right - dp(52), top + dp(44), dp(28).toFloat(), -0x3bc5, true)
            drawCompassArrow(canvas, cx, cy, radius * 0.84f, -38f + phase * 6f)
            label(
                canvas,
                "к флагу",
                left + dp(18),
                bottom - dp(44),
                dp(15).toFloat(),
                Color.WHITE,
                true,
            )
            label(
                canvas,
                "1.2 км до точки финиша",
                left + dp(18),
                bottom - dp(23),
                dp(12).toFloat(),
                MainActivity.COLOR_MUTED,
                true,
            )
        }

        fun drawCompassArrow(canvas: Canvas, cx: Float, cy: Float, length: Float, angleDeg: Float) {
            canvas.save()
            canvas.rotate(angleDeg, cx, cy)
            path.reset()
            path.moveTo(cx, cy - length)
            path.lineTo(cx - dp(11), cy + dp(20))
            path.lineTo(cx, cy + dp(9))
            path.lineTo(cx + dp(11), cy + dp(20))
            path.close()
            p.setStyle(Paint.Style.FILL)
            p.setColor(MainActivity.COLOR_ACCENT_BLUE)
            canvas.drawPath(path, p)
            canvas.restore()
        }

        fun drawLane(canvas: Canvas, cx: Float, cy: Float, value: String?) {
            fill(
                canvas,
                -0xd9cdbf,
                cx - dp(18),
                cy - dp(17),
                cx + dp(18),
                cy + dp(17),
                dp(8).toFloat(),
            )
            center(canvas, value, cx, cy + dp(1), dp(17).toFloat(), Color.WHITE, true)
        }

        fun drawSourceCard(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            sourceMode: Int,
        ) {
            fill(canvas, -0xeae2d9, left, top, right, bottom, dp(9).toFloat())
            label(
                canvas,
                "Источник",
                left + dp(12),
                top + dp(17),
                dp(11).toFloat(),
                MainActivity.COLOR_MUTED,
                true,
            )
            label(
                canvas,
                sourceTitle(sourceMode),
                left + dp(12),
                top + dp(35),
                dp(15).toFloat(),
                Color.WHITE,
                true,
            )
            previewPill(
                canvas,
                sourceHint(sourceMode),
                right - dp(86),
                top + dp(22),
                MainActivity.COLOR_SETTINGS_SELECTED,
            )
        }

        fun drawTxCard(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            routeMode: Int,
            textMode: Int,
        ) {
            fill(canvas, -0xefe8e0, left, top, right, bottom, dp(9).toFloat())
            label(
                canvas,
                "TX приборки",
                left + dp(12),
                top + dp(17),
                dp(11).toFloat(),
                MainActivity.COLOR_MUTED,
                true,
            )
            label(
                canvas,
                panelPreviewText(routeMode, textMode),
                left + dp(12),
                top + dp(39),
                dp(17).toFloat(),
                Color.WHITE,
                true,
            )
            val chipW: Float = (right - left - dp(32)) / 3f
            val chipY: Float = bottom - dp(16)
            modeChip(canvas, left + dp(12), chipY, chipW, "сейчас", 0, textMode)
            modeChip(canvas, left + dp(18) + chipW, chipY, chipW, "после", 1, textMode)
            modeChip(canvas, left + dp(24) + chipW * 2f, chipY, chipW, "финиш", 2, textMode)
        }

        fun drawAssistantCard(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            normalMode: Boolean,
            assistant: Boolean,
        ) {
            val color: Int = if (normalMode && assistant) -0xe7c5cb else -0xdfd8cd
            fill(canvas, color, left, top, right, bottom, dp(9).toFloat())
            label(
                canvas,
                "Ассистент",
                left + dp(12),
                top + dp(17),
                dp(11).toFloat(),
                MainActivity.COLOR_MUTED,
                true,
            )
            val value: String =
                if (!normalMode) "недоступен в этом режиме"
                else if (assistant)
                    ("до " +
                        AppSettings.navMicroMaxDistanceMeters(this@MainActivity) +
                        " м, после " +
                        AppSettings.navMicroHoldSeconds(this@MainActivity) +
                        "с")
                else "выключен"
            label(
                canvas,
                value,
                left + dp(12),
                top + dp(36),
                dp(14).toFloat(),
                if (normalMode && assistant) Color.WHITE else MainActivity.COLOR_MUTED,
                true,
            )
        }

        fun drawSpeedCard(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            overspeed: Boolean,
        ) {
            fill(
                canvas,
                if (overspeed) -0xcae2db else -0xeae2d9,
                left,
                top,
                right,
                bottom,
                dp(9).toFloat(),
            )
            drawSpeedLimit(canvas, left + dp(30), top + (bottom - top) / 2f)
            label(
                canvas,
                if (overspeed) "Превышение видно" else "Лимит без текста",
                left + dp(70),
                top + dp(22),
                dp(14).toFloat(),
                if (overspeed) -0x3bc5 else Color.WHITE,
                true,
            )
            label(
                canvas,
                if (overspeed) "+ warning строка" else "только знак 60",
                left + dp(70),
                top + dp(41),
                dp(11).toFloat(),
                MainActivity.COLOR_MUTED,
                true,
            )
        }

        fun drawSpeedLimit(canvas: Canvas, cx: Float, cy: Float) {
            p.setStyle(Paint.Style.FILL)
            p.setColor(-0x80809)
            canvas.drawCircle(cx, cy, dp(28).toFloat(), p)
            p.setStyle(Paint.Style.STROKE)
            p.setStrokeWidth(dp(5).toFloat())
            p.setColor(-0x27c4bb)
            canvas.drawCircle(cx, cy, dp(24).toFloat(), p)
            p.setStyle(Paint.Style.FILL)
            center(canvas, "60", cx, cy, dp(17).toFloat(), Color.BLACK, true)
        }

        fun modeChip(
            canvas: Canvas,
            x: Float,
            y: Float,
            width: Float,
            title: String?,
            mode: Int,
            textMode: Int,
        ) {
            val selected: Boolean = textMode == mode
            fill(
                canvas,
                if (selected) MainActivity.COLOR_SETTINGS_SELECTED else -0xe9e3dc,
                x,
                y - dp(11),
                x + width,
                y + dp(11),
                dp(7).toFloat(),
            )
            stroke(
                canvas,
                if (selected) MainActivity.COLOR_ACCENT_BLUE else 0x22ffffff,
                dp(1).toFloat(),
                x,
                y - dp(11),
                x + width,
                y + dp(11),
                dp(7).toFloat(),
            )
            center(
                canvas,
                title,
                x + width / 2f,
                y,
                dp(10).toFloat(),
                if (selected) Color.WHITE else MainActivity.COLOR_MUTED,
                selected,
            )
        }

        fun previewPill(canvas: Canvas, value: String?, cx: Float, cy: Float, color: Int) {
            p.setTypeface(Typeface.DEFAULT_BOLD)
            p.setTextSize(dp(10).toFloat())
            p.setTextAlign(Paint.Align.LEFT)
            val width: Float = Math.max(dp(58).toFloat(), p.measureText(value) + dp(18))
            fill(
                canvas,
                color,
                cx - width / 2f,
                cy - dp(13),
                cx + width / 2f,
                cy + dp(13),
                dp(7).toFloat(),
            )
            center(canvas, value, cx, cy, dp(10).toFloat(), Color.WHITE, true)
        }

        fun routeModeTitle(routeMode: Int): String {
            if (routeMode == NavigationOutputMode.TBT) return "TBT-иконки"
            if (routeMode == NavigationOutputMode.FINISH_DIRECTION) return "Стрелка к флагу"
            return "Обычная навигация"
        }

        fun routeModeHint(routeMode: Int): String {
            if (routeMode == NavigationOutputMode.TBT) return "отдельные события поворотов"
            if (routeMode == NavigationOutputMode.FINISH_DIRECTION) return "компас на точку финиша"
            return "манёвр, lane и серая дорога"
        }

        fun sourceTitle(sourceMode: Int): String {
            if (sourceMode == AppSettings.NAV_SOURCE_YANDEX) return "Yandex"
            if (sourceMode == AppSettings.NAV_SOURCE_2GIS) return "2GIS"
            if (sourceMode == AppSettings.NAV_SOURCE_MVITE) return "MVite"
            return "Auto"
        }

        fun sourceHint(sourceMode: Int): String {
            if (sourceMode == AppSettings.NAV_SOURCE_YANDEX) return "только Y"
            if (sourceMode == AppSettings.NAV_SOURCE_2GIS) return "только 2G"
            if (sourceMode == AppSettings.NAV_SOURCE_MVITE) return "только MV"
            return "Y + 2G"
        }

        fun panelPreviewText(routeMode: Int, textMode: Int): String {
            if (routeMode == NavigationOutputMode.TBT) return "TBT: направо"
            if (routeMode == NavigationOutputMode.FINISH_DIRECTION) return "Флаг 1.2 км"
            when (textMode) {
                1 -> return "После: ул. Абая"
                2 -> return "Финиш: Дом 12"
                0 -> return "Сейчас: пр. Достык"
                else -> return "Сейчас: пр. Достык"
            }
        }
    }

    private inner class SasGuidePreviewView(context: Context?) : View(context) {
        private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path: Path = Path()

        init {
            setMinimumHeight(if (isCompact()) dp(160) else dp(200))
        }

        override protected fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width: Int = MeasureSpec.getSize(widthMeasureSpec)
            val height: Int = if (isCompact()) dp(170) else dp(210)
            setMeasuredDimension(width, height)
        }

        override protected fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w: Float = width.toFloat()
            val h: Float = height.toFloat()
            val radius: Float = dp(8).toFloat()

            paint.setStyle(Paint.Style.FILL)
            paint.setColor(MainActivity.COLOR_SETTINGS_PANEL_ALT)
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)

            val ratio: Int = AppSettings.sasRatio(this@MainActivity)
            val strength: Float = Math.max(0.04f, Math.min(1f, ratio / 64f))
            val curve: Float = w * (0.04f + 0.18f * strength)
            val center: Float = w * 0.5f
            val top: Float = h * 0.18f
            val bottom: Float = h * 0.88f
            val topGap: Float = w * 0.12f
            val bottomGap: Float = w * 0.32f

            paint.setStyle(Paint.Style.STROKE)
            paint.setStrokeWidth(dp(2).toFloat())
            paint.setColor(Color.argb(52, 255, 255, 255))
            drawGuide(canvas, center - topGap, top, center - bottomGap, bottom, 0f, 0f)
            drawGuide(canvas, center + topGap, top, center + bottomGap, bottom, 0f, 0f)

            paint.setStrokeWidth(dp(4).toFloat())
            paint.setColor(MainActivity.COLOR_ACCENT_BLUE)
            drawGuide(canvas, center - topGap, top, center - bottomGap + curve, bottom, curve, -1f)
            drawGuide(canvas, center + topGap, top, center + bottomGap + curve, bottom, curve, 1f)

            paint.setStrokeWidth(dp(2).toFloat())
            paint.setColor(Color.argb(120, 255, 255, 255))
            for (i in 1..3) {
                val y: Float = top + (bottom - top) * i / 4f
                val shift: Float = curve * i / 4f
                canvas.drawLine(
                    center - bottomGap * (0.48f + i * 0.18f) + shift,
                    y,
                    center + bottomGap * (0.48f + i * 0.18f) + shift,
                    y,
                    paint,
                )
            }
        }

        fun drawGuide(
            canvas: Canvas,
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            curve: Float,
            side: Float,
        ) {
            path.reset()
            path.moveTo(startX, startY)
            path.cubicTo(
                startX + curve * (0.15f + side * 0.18f),
                startY + (endY - startY) * 0.30f,
                endX - curve * (0.25f - side * 0.12f),
                startY + (endY - startY) * 0.72f,
                endX,
                endY,
            )
            canvas.drawPath(path, paint)
        }
    }

    private fun iconForTab(tab: Int): String {
        when (tab) {
            MainActivity.TAB_TPMS -> return "◉"
            MainActivity.TAB_MEDIA -> return "♪"
            MainActivity.TAB_NAVIGATION -> return "⌖"
            MainActivity.TAB_ADAS -> return "≋"
            MainActivity.TAB_CANBUS -> return "◇"
            MainActivity.TAB_SETTINGS -> return "⚙"
            else -> return "•"
        }
    }

    private fun sectionIcon(title: String): String {
        if (title.contains("TPMS") || title.contains("Порог") || title.contains("Оповещ"))
            return "◉"
        if (title.contains("Медиа") || title.contains("Музык")) return "♪"
        if (title.contains("звон") || title.contains("Звон") || title.contains("BT")) return "☎"
        if (title.contains("Разреш")) return "✓"
        if (title.contains("Источник")) return "◇"
        if (title.contains("Адрес")) return "⌖"
        if (title.contains("Текст")) return "T"
        if (title.contains("APK") || title.contains("Система")) return "↻"
        if (title.contains("Прошив")) return "⇧"
        if (title.contains("Отлад")) return "≡"
        return "•"
    }

    private fun iconForTitle(title: String): String {
        if (
            title.contains("TPMS") ||
                title.contains("Давл") ||
                title.contains("Темп") ||
                title.contains("TEYES")
        )
            return "◉"
        if (
            title.contains("Медиа") ||
                title.contains("Музык") ||
                title.contains("Music") ||
                title.contains("трек") ||
                title.contains("музык")
        )
            return "♪"
        if (title.contains("звон") || title.contains("Звон") || title.contains("BT Audio"))
            return "☎"
        if (
            title.contains("Нав") ||
                title.contains("Yandex") ||
                title.contains("2GIS") ||
                title.contains("улица") ||
                title.contains("Финиш") ||
                title.contains("Текущ") ||
                title.contains("манёвра")
        )
            return "⌖"
        if (
            title.contains("CAN") ||
                title.contains("SAS") ||
                title.contains("AMP") ||
                title.contains("адаптер")
        )
            return "◇"
        if (title.contains("Наст") || title.contains("Система") || title.contains("Авто"))
            return "⚙"
        if (title.contains("Отлад")) return "≡"
        if (title.contains("Запрос") || title.contains("Разреш")) return "✓"
        if (title.contains("Прош") || title.contains("Обнов") || title.contains("APK")) return "⇧"
        if (title.contains("CarPlay") || title.contains("Android")) return "▣"
        return "•"
    }

    private fun text(value: String?, sp: Int, color: Int): TextView {
        val view: TextView = TextView(this)
        view.setText(value)
        view.setTextSize(sp.toFloat())
        view.setTextColor(color)
        view.setLineSpacing(2f, 1.0f)
        return view
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }

    private fun requestRuntimePermissions() {
        val missing: ArrayList<String?> = ArrayList()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (
            gpsRequired() &&
                (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED)
        ) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (
            bluetoothRequired() &&
                Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        AppSettings.setRuntimePermissionsRequested(this, true)
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(arrayOfNulls<String>(0)), 10)
            return
        }
        if (!requestBackgroundLocationPermission()) {
            handler.postDelayed({ this.requestStartupSpecialPermissions() }, 900L)
        }
    }

    private fun requestBackgroundLocationPermission(): Boolean {
        if (!gpsRequired() || Build.VERSION.SDK_INT < 29) return false
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        )
            return false
        if (
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
            return false
        if (Build.VERSION.SDK_INT >= 30) return false
        requestPermissions(arrayOf<String?>(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 11)
        return true
    }

    private fun requestStartupSpecialPermissions() {
        if (isFinishing() || specialPermissionWaiting) return
        val intent = nextSpecialPermissionIntent()
        if (intent == null) return
        try {
            specialPermissionWaiting = true
            startActivity(intent)
            AppLog.line(
                this,
                "Permissions: requested " + MainActivity.cleanAction(intent.getAction()),
            )
        } catch (e: Exception) {
            specialPermissionWaiting = false
            AppLog.line(this, "Permissions: request failed " + e.javaClass.getSimpleName())
            handler.postDelayed({ this.requestStartupSpecialPermissions() }, 500L)
        }
    }

    private fun nextSpecialPermissionIntent(): Intent? {
        if (
            overlayRequired() &&
                Build.VERSION.SDK_INT >= 23 &&
                !Settings.canDrawOverlays(this) &&
                !askedOverlay
        ) {
            askedOverlay = true
            val intent: Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.setData(Uri.parse("package:" + getPackageName()))
            return intent
        }
        if (
            gpsRequired() &&
                Build.VERSION.SDK_INT >= 30 &&
                !backgroundLocationPermissionGranted() &&
                !askedBackgroundLocationSettings
        ) {
            askedBackgroundLocationSettings = true
            Toast.makeText(
                    this,
                    "В разрешении «Местоположение» выберите «Разрешать всегда»",
                    Toast.LENGTH_LONG,
                )
                .show()
            val intent: Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.setData(Uri.parse("package:" + getPackageName()))
            return intent
        }
        if (
            AppSettings.autoStart(this) &&
                !batteryOptimizationIgnored() &&
                !askedBatteryOptimization
        ) {
            askedBatteryOptimization = true
            val intent: Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.setData(Uri.parse("package:" + getPackageName()))
            AppSettings.setBatteryOptimizationRequested(this, true)
            return intent
        }
        if (
            mediaListenerRequired() && !notificationListenerEnabled() && !askedNotificationListener
        ) {
            askedNotificationListener = true
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        return null
    }

    private fun batteryOptimizationIgnored(): Boolean {
        try {
            val pm: PowerManager? = getSystemService(POWER_SERVICE) as PowerManager?
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())
        } catch (ignored: Exception) {
            return true
        }
    }

    companion object {
        private val COLOR_BG: Int = Color.rgb(13, 15, 19)
        private val COLOR_SURFACE: Int = Color.argb(150, 15, 22, 32)
        private val COLOR_PANEL: Int = Color.argb(132, 23, 31, 43)
        private val COLOR_PANEL_SOFT: Int = Color.argb(165, 36, 48, 62)
        private val COLOR_STROKE: Int = Color.argb(92, 178, 211, 230)
        private val COLOR_SETTINGS_BG: Int = Color.rgb(8, 11, 15)
        private val COLOR_SETTINGS_TOP: Int = Color.rgb(11, 15, 21)
        private val COLOR_SETTINGS_PANEL: Int = Color.rgb(18, 23, 31)
        private val COLOR_SETTINGS_PANEL_ALT: Int = Color.rgb(25, 32, 42)
        private val COLOR_SETTINGS_SELECTED: Int = Color.rgb(35, 48, 61)
        private val COLOR_SETTINGS_DIVIDER: Int = Color.rgb(47, 58, 70)
        private val COLOR_SETTINGS_STROKE: Int = Color.rgb(49, 63, 78)
        private val COLOR_TEXT: Int = Color.rgb(246, 248, 252)
        private val COLOR_MUTED: Int = Color.rgb(157, 172, 190)
        private val COLOR_ACCENT: Int = Color.rgb(57, 211, 190)
        private val COLOR_ACCENT_BLUE: Int = Color.rgb(82, 156, 255)
        private val COLOR_WARNING: Int = Color.rgb(245, 176, 78)
        private val COLOR_VIOLET: Int = Color.rgb(151, 116, 255)
        private val COLOR_ROSE: Int = Color.rgb(242, 92, 139)
        private val COLOR_SUCCESS: Int = Color.rgb(57, 211, 141)
        private val COLOR_DANGER: Int = Color.rgb(235, 83, 91)

        private const val TAB_TPMS: Int = 0
        private const val TAB_MEDIA: Int = 1
        private const val TAB_NAVIGATION: Int = 2
        private const val TAB_CANBUS: Int = 3
        private const val TAB_SETTINGS: Int = 4
        private const val TAB_ADAS: Int = 5

        private const val SETTINGS_TPMS: Int = 0
        private const val SETTINGS_MEDIA: Int = 1
        private const val SETTINGS_NAVIGATION: Int = 2
        private const val SETTINGS_CANBUS: Int = 3
        private const val SETTINGS_RCTA: Int = 4
        private const val SETTINGS_GENERAL: Int = 5
        private const val SETTINGS_ADAS: Int = 6
        private const val REQUEST_FIRMWARE_FILE: Int = 42
        private const val REQUEST_EXPORT_SETTINGS: Int = 43
        private const val REQUEST_IMPORT_SETTINGS: Int = 44
        private const val REQUEST_DIAGNOSTICS_FILE: Int = 45
        private const val REQUEST_UPDATE_NOTIFICATIONS: Int = 46

        private fun containsAny(value: String, vararg needles: String?): Boolean {
            for (needle: String? in needles) {
                if (value.contains(needle!!)) return true
            }
            return false
        }

        private fun pressureBarValue(pressureKpa: Int): String {
            return String.format(Locale.US, "%.2f", pressureKpa / 100f)
        }

        private fun lastLineOrDash(value: String?): String {
            if (value == null || value.trim { it <= ' ' }.isEmpty()) return "—"
            val clean = value.trim { it <= ' ' }
            val split: Int = clean.lastIndexOf('\n')
            return if (split >= 0) clean.substring(split + 1) else clean
        }

        private fun firstNonEmpty(values: Array<out String?>?): String {
            if (values == null) return ""
            for (value: String? in values) {
                if (value != null && !value.trim { it <= ' ' }.isEmpty()) return value.trim { it <= ' ' }
            }
            return ""
        }

        private fun ageText(now: Long, then: Long): String {
            if (then <= 0L) return "никогда"
            val seconds: Long = Math.max(0L, now - then) / 1000L
            if (seconds < 60L) return seconds.toString() + " сек назад"
            if (seconds < 3600L) return (seconds / 60L).toString() + " мин назад"
            return (seconds / 3600L).toString() + " ч назад"
        }

        private fun yesNo(value: Boolean): String {
            return if (value) "ok" else "нет"
        }

        private fun mediaDelayText(delayMs: Int): String {
            if (delayMs <= 0) return "без паузы"
            if (delayMs < 1000) return delayMs.toString() + " мс"
            return MainActivity.formatSeconds(delayMs) + " с"
        }

        private fun formatSeconds(delayMs: Int): String {
            if (delayMs % 1000 == 0) return java.lang.String.valueOf(delayMs / 1000)
            return String.format(Locale.US, "%.1f", delayMs / 1000f)
        }

        private fun pressureBarText(pressureKpa: Int): String {
            return String.format(Locale.US, "%.1f", pressureKpa / 100f)
        }

        private fun wheelName(wheel: Int): String {
            when (wheel) {
                TpmsState.WHEEL_FL -> return "FL"
                TpmsState.WHEEL_FR -> return "FR"
                TpmsState.WHEEL_RL -> return "RL"
                TpmsState.WHEEL_RR -> return "RR"
                else -> return "--"
            }
        }

        private fun wheelFullName(wheel: Int): String {
            when (wheel) {
                TpmsState.WHEEL_FL -> return "Перед лев."
                TpmsState.WHEEL_FR -> return "Перед прав."
                TpmsState.WHEEL_RL -> return "Зад лев."
                TpmsState.WHEEL_RR -> return "Зад прав."
                else -> return "Колесо"
            }
        }

        private fun formatMediaDuration(value: Long): String {
            val seconds: Long = Math.max(0L, value) / 1000L
            val minutes: Long = seconds / 60L
            val rest: Long = seconds % 60L
            return minutes.toString() + ":" + (if (rest < 10) "0" else "") + rest
        }

        private fun emptyDash(value: String?): String {
            return if (value == null || value.length == 0) "-" else value
        }

        private fun ampSigned(value: Int): String {
            val signed: Int = value - 10
            return if (signed > 0) "+" + signed else java.lang.String.valueOf(signed)
        }

        private fun ampUiText(s: AmpState): String {
            return ("Сейчас: громкость " +
                s.volume +
                " · баланс " +
                MainActivity.ampSigned(s.balance) +
                " · фейдер " +
                MainActivity.ampSigned(s.fader) +
                " · низ " +
                MainActivity.ampSigned(s.bass) +
                " · середина " +
                MainActivity.ampSigned(s.mid) +
                " · верх " +
                MainActivity.ampSigned(s.treble) +
                " · режим " +
                s.mode)
        }

        private fun displayStatus(value: String?): String {
            if (value == null) return ""
            return value
                .replace("App update: ", "")
                .replace("Navigator update: ", "")
                .replace("Firmware update: ", "")
        }

        private fun formatFileSize(value: Long): String {
            if (value < 0L) return "не определён"
            if (value < 1024L) return value.toString() + " Б"
            return String.format(Locale.US, "%.1f КБ", value / 1024f)
        }

        private fun clearChoiceViews(views: kotlin.Array<View?>, checks: kotlin.Array<TextView?>) {
            for (i in views.indices) {
                views[i] = null
                checks[i] = null
            }
        }

        private fun clamp(value: Int, min: Int, max: Int): Int {
            return Math.max(min, Math.min(max, value))
        }

        private fun cleanAction(action: String?): String {
            return if (action == null) "special" else action.replace("android.settings.", "")
        }
    }
}
