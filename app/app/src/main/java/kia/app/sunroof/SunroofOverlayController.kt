package kia.app.sunroof

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import kia.app.core.AppIds
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.VehicleState
import kia.app.core.settings.AppSettings
import kia.app.protocol.adapter.AdapterGateway
import kia.app.sunroof.SunroofAlertPolicy.Input
import kia.app.tpms.TpmsAlertController
import kia.app.tpms.TpmsWarningOverlayController

/** Owns roof windows and audio; all vehicle decisions and Android mutations run on main. */
class SunroofOverlayController private constructor(context: Context) {
    private val app: Context
    private val windowContext: Context?
    private val windowManager: WindowManager?
    private val main: Handler = Handler(Looper.getMainLooper())
    private val policy: SunroofAlertPolicy = SunroofAlertPolicy()
    private val qaPolicy: SunroofAlertPolicy = SunroofAlertPolicy()
    private val sound: SunroofSoundPlayer
    private val observer: TeyesSunroofObserver
    @kotlin.concurrent.Volatile private var generation: Long = 0
    @kotlin.concurrent.Volatile private var status: String = "Люк: наблюдение выключено"
    @kotlin.concurrent.Volatile private var diagnostics: String = "running=false window=false"
    private var lastDiagnosticKey: String = ""
    private var running: Boolean = false
    private var activityVisible = false
    private var observerRunning: Boolean = false
    private var accKnown: Boolean = false
    private var accOn: Boolean = false
    private var asleep: Boolean = false
    private var screenOff: Boolean = false
    private var permissionLogged: Boolean = false
    private var windowFailureLogged: Boolean = false
    private var qaMode: Boolean = false
    private var qaRoofKnown: Boolean = false
    private var qaRoofOpen: Boolean = false
    private var qaAccKnown: Boolean = false
    private var qaAccOn: Boolean = true
    private var qaClock: Long = 0
    private var realSnapshotTaken: Boolean = false
    private var lastEnabled: Boolean = false
    private var lastRealRoofKnown: Boolean = false
    private var lastRealRoofOpen: Boolean = false
    private var lastRealEpoch: Long = 0
    private var demo: kia.app.sunroof.SunroofAlertPolicy.Decision? = null
    private var overlay: SunroofOverlayView? = null
    private var overlayWasAttached: Boolean = false
    private var shownSequence: Long = Long.MIN_VALUE
    private var shownKind: kia.app.sunroof.SunroofAlertPolicy.Kind? =
        kia.app.sunroof.SunroofAlertPolicy.Kind.NONE
    private var shownDemo: Boolean = false
    private var audioEnabled: Boolean = false
    // Audio belongs to a real warning episode, independently of its window attachment.
    private var realWarningAudioSequence: Long = Long.MIN_VALUE
    private var realSequence: Long = Long.MIN_VALUE
    private var realSequenceStartedAt: Long = 0
    private var realSequenceDeferred: Boolean = false
    private var stateReceiver: BroadcastReceiver? = null
    private var systemReceiver: BroadcastReceiver? = null
    private var finishTransient: Runnable? = null
    private val refresh: Runnable =
        object : Runnable {
            override fun run() {
                if (!running) return
                applyOnMain()
                if (running) main.postDelayed(this, 1000L)
            }
        }

    init {
        val application = context.getApplicationContext()
        app = if (application == null) context else application
        windowContext = SunroofOverlayController.createWindowContext(app)
        windowManager = windowContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
        sound = SunroofSoundPlayer(app)
        observer =
            TeyesSunroofObserver(
                app,
                object : kia.app.sunroof.TeyesSunroofObserver.Listener {
                    override fun onAcc(
                        known: Boolean,
                        on: Boolean,
                        elapsed: Long,
                        source: String?,
                    ) {
                        postCurrent(
                            Runnable {
                                if (!running || !observerRunning) return@Runnable
                                SunroofStateMemory.observeAcc(app, known, on)
                                if (known && accKnown && on != accOn) cancelDemo()
                                accKnown = known
                                accOn = on
                                applyOnMain()
                            }
                        )
                    }

                    override fun onHardwareAction(keyCode: Int, action: Int) {
                        if (action == KeyEvent.ACTION_DOWN) dismissCurrent()
                    }

                    override fun onSleep(sleeping: Boolean) {
                        postCurrent(
                            Runnable {
                                if (!running || !observerRunning) return@Runnable
                                asleep = sleeping
                                if (sleeping) {
                                    cancelDemo()
                                }
                                applyOnMain()
                            }
                        )
                    }
                },
            )
    }

    fun start() {
        postCurrent(
            Runnable {
                ensureStarted()
                applyOnMain()
            }
        )
    }

    fun apply() {
        postCurrent(Runnable { if (running) applyOnMain() })
    }

    fun setActivityVisible(visible: Boolean) {
        postCurrent(
            Runnable {
                if (activityVisible == visible) return@Runnable
                activityVisible = visible
                if (running) applyOnMain()
            }
        )
    }

    @kotlin.jvm.Synchronized
    fun stop() {
        val stopped: Long = ++generation
        main.removeCallbacksAndMessages(null)
        val cleanup: Runnable = Runnable {
            if (generation != stopped) return@Runnable
            running = false
            observerRunning = false
            observer.stop()
            unregisterReceivers()
            cancelDemo()
            policy.reset()
            hide()
            accKnown = false
            asleep = false
            screenOff = false
            realSnapshotTaken = false
            realSequence = Long.MIN_VALUE
            realWarningAudioSequence = Long.MIN_VALUE
            realSequenceDeferred = false
            status = "Люк: наблюдение выключено"
            reportDecision(
                "stopped",
                null,
                AppSettings.sunroofOverlayEnabled(app),
                false,
                lastRealEpoch,
                StateStore.vehicle(),
            )
        }
        if (Looper.myLooper() === Looper.getMainLooper()) cleanup.run() else main.post(cleanup)
    }

    fun dismissCurrent() {
        postCurrent(Runnable { this.dismissOnMain() })
    }

    fun preview(open: Boolean) {
        postCurrent(Runnable { previewOnMain(open, false) })
    }

    fun previewParkingWarning() {
        postCurrent(Runnable { previewOnMain(true, true) })
    }

    /**
     * Shell-secured QaReceiver entry; samples stay in a separate policy and never alter CAN state.
     */
    fun runQaScenario(scenario: String?) {
        postCurrent(
            Runnable {
                ensureStarted()
                if ("sunroof_qa_reset".equals(scenario)) {
                    cancelDemo()
                    hide()
                    applyOnMain()
                    return@Runnable
                }
                if ("sunroof_qa_dismiss".equals(scenario)) {
                    dismissOnMain()
                    return@Runnable
                }
                if (
                    !"sunroof_qa_open".equals(scenario) &&
                        !"sunroof_qa_closed".equals(scenario) &&
                        !"sunroof_qa_acc_on".equals(scenario) &&
                        !"sunroof_qa_acc_off".equals(scenario)
                )
                    return@Runnable
                if (!qaMode) {
                    cancelDemo()
                    qaMode = true
                    qaRoofKnown = true
                    qaRoofOpen = false
                    qaAccKnown = true
                    qaAccOn = true
                    qaPolicy.update(qaInput())
                }
                if ("sunroof_qa_open".equals(scenario) || "sunroof_qa_closed".equals(scenario)) {
                    qaRoofKnown = true
                    qaRoofOpen = "sunroof_qa_open".equals(scenario)
                } else {
                    qaAccKnown = true
                    qaAccOn = "sunroof_qa_acc_on".equals(scenario)
                }
                demo = qaPolicy.update(qaInput())
                applyOnMain()
            }
        )
    }

    fun statusSummary(): String? {
        return status + "\n" + diagnostics + "\n" + observer.statusSummary()
    }

    private fun ensureStarted() {
        if (running) return
        running = true
        registerReceivers()
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 1000L)
    }

    private fun applyOnMain() {
        if (!running) return
        val enabled: Boolean = AppSettings.sunroofOverlayEnabled(app)
        if (realSnapshotTaken && lastEnabled && !enabled) cancelDemo()
        lastEnabled = enabled
        if (enabled && !observerRunning) {
            observerRunning = true
            observer.start()
        } else if (!enabled && observerRunning) {
            observerRunning = false
            observer.stop()
            accKnown = false
            asleep = false
        }
        val gateway = AdapterGateway.get(app)
        val vehicle = StateStore.vehicle()
        val remembered: SunroofStateMemory.Snapshot = SunroofStateMemory.snapshot(app)
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager?
        if (power != null) screenOff = !power.isInteractive()
        val connected: Boolean = gateway!!.usbReady()
        val epoch: Long = gateway.connectionEpoch()
        val realChanged: Boolean =
            realSnapshotTaken &&
                (lastRealRoofKnown != vehicle.sunroofKnown ||
                    (vehicle.sunroofKnown && lastRealRoofOpen != vehicle.sunroofOpen) ||
                    lastRealEpoch != epoch)
        realSnapshotTaken = true
        lastRealRoofKnown = vehicle.sunroofKnown
        lastRealRoofOpen = vehicle.sunroofOpen
        lastRealEpoch = epoch
        if (realChanged) {
            cancelDemo()
            AppLog.line(
                app,
                ("Sunroof source: known=" +
                    vehicle.sunroofKnown +
                    " open=" +
                    vehicle.sunroofOpen +
                    " usbEpoch=" +
                    epoch +
                    " roofEpoch=" +
                    vehicle.sunroofConnectionEpoch +
                    " roofAt=" +
                    vehicle.sunroofReceivedElapsed),
            )
        }
        val now: Long = SystemClock.elapsedRealtime()
        var real: kia.app.sunroof.SunroofAlertPolicy.Decision? =
            policy.update(
                Input(
                    enabled,
                    AppSettings.sunroofParkingWarningEnabled(app),
                    connected,
                    epoch,
                    vehicle.sunroofKnown,
                    vehicle.sunroofOpen,
                    vehicle.sunroofConnectionEpoch,
                    vehicle.sunroofReceivedElapsed,
                    accKnown,
                    accOn,
                    now,
                    remembered.known,
                    remembered.open,
                    remembered.acknowledged,
                )
            )
        if (real!!.sequence != realSequence) {
            realSequence = real.sequence
            realSequenceStartedAt = now
            realSequenceDeferred = false
        }
        val hazard: Boolean = higherPriorityHazard()
        if (hazard) cancelDemo()
        if (asleep || screenOff) {
            if (
                real.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.OPENING ||
                    real.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.CLOSING
            )
                policy.finish(real.sequence)
            if (
                real.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING &&
                    !hazard &&
                    SunroofOverlayController.canDrawOverlays(app)
            ) {
                // CC4PRO delivers ACC OFF and cmd8 sleep almost together. Hide the window
                // without truncating the finite warning, including sleep-before-ACC order.
                hideWindow()
                sound.setEnabled(true)
                playDecisionAudio(real, false)
            } else {
                hide()
            }
            status = if (screenOff) "Люк: экран выключен" else "Люк: магнитола засыпает"
            reportDecision(
                if (screenOff) "screen_off" else "sleep",
                real,
                enabled,
                connected,
                epoch,
                vehicle,
            )
            return
        }
        if (hazard) {
            if (
                real.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.OPENING ||
                    real.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.CLOSING
            ) {
                realSequenceDeferred = true
                val handledInActivity =
                    shownSequence == real.sequence && !shownDemo && (activityVisible || overlay == null)
                if (
                    handledInActivity ||
                        now - realSequenceStartedAt >=
                            SunroofOverlayController.DEFERRED_TRANSITION_TTL_MS
                ) {
                    policy.finish(real.sequence)
                }
            }
            hide()
            status = "Люк: ожидает закрытия критического предупреждения TPMS/RCTA"
            reportDecision("critical_defer", real, enabled, connected, epoch, vehicle)
            return
        }
        if (realSequenceDeferred) {
            if (
                now - realSequenceStartedAt >=
                    SunroofOverlayController.DEFERRED_TRANSITION_TTL_MS &&
                    (real.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.OPENING ||
                        real.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.CLOSING)
            ) {
                real = policy.finish(real.sequence)
            }
            // Once an eligible deferred event is shown, let its full animation complete.
            realSequenceDeferred = false
        }
        if (real.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING) cancelDemo()
        val next: kia.app.sunroof.SunroofAlertPolicy.Decision = demo ?: real
        val usingMemory: Boolean =
            remembered.known &&
                (!connected || !vehicle.sunroofKnown || vehicle.sunroofConnectionEpoch != epoch)
        status =
            if (demo != null) "Люк: демонстрация, данные автомобиля не изменены"
            else if (!enabled) "Люк: оповещения выключены"
            else if (usingMemory)
                ("Люк: последнее сохранённое — " +
                    (if (remembered.open) "открыт" else "закрыт") +
                    (if (next.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING)
                        " · предупреждение"
                    else ""))
            else if (!connected) "Люк: ожидается USB CAN"
            else if (!vehicle.sunroofKnown) "Люк: ожидается состояние"
            else
                ("Люк: " +
                    (if (vehicle.sunroofOpen) "открыт" else "закрыт") +
                    (if (next.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING)
                        " · предупреждение"
                    else ""))
        if (next.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.NONE) {
            hide()
            reportDecision("none", next, enabled, connected, epoch, vehicle)
            return
        }
        if (!SunroofOverlayController.canDrawOverlays(app)) {
            hide()
            finishUnavailableTransient(next, demo != null)
            if (!permissionLogged) {
                permissionLogged = true
                AppLog.line(app, "Sunroof overlay: нужно разрешение поверх окон")
            }
            reportDecision("permission", next, enabled, connected, epoch, vehicle)
            return
        }
        permissionLogged = false
        val demonstration = demo != null
        val showWindow = !activityVisible || demonstration
        present(next, demonstration, showWindow)
        reportDecision(
            when {
                !showWindow -> "activity_visible"
                overlay != null -> "shown"
                shownSequence == next.sequence -> "transition_handled"
                else -> "window_failed"
            },
            next,
            enabled,
            connected,
            epoch,
            vehicle,
        )
    }

    private fun reportDecision(
        reason: String?,
        decision: kia.app.sunroof.SunroofAlertPolicy.Decision?,
        enabled: Boolean,
        connected: Boolean,
        epoch: Long,
        vehicle: VehicleState,
    ) {
        val permission: Boolean = SunroofOverlayController.canDrawOverlays(app)
        val shown: Boolean = overlay != null && overlay!!.isAttachedToWindow()
        val remembered: SunroofStateMemory.Snapshot = SunroofStateMemory.snapshot(app)
        val facts: String =
            ("reason=" +
                reason +
                " running=" +
                running +
                " master=" +
                enabled +
                " permission=" +
                permission +
                " window=" +
                shown +
                " usb=" +
                connected +
                " usbEpoch=" +
                epoch +
                " roofKnown=" +
                vehicle.sunroofKnown +
                " roofOpen=" +
                vehicle.sunroofOpen +
                " roofEpoch=" +
                vehicle.sunroofConnectionEpoch +
                " cacheKnown=" +
                remembered.known +
                " cacheOpen=" +
                remembered.open +
                " cacheAck=" +
                remembered.acknowledged +
                " accKnown=" +
                accKnown +
                " accOn=" +
                accOn +
                " asleep=" +
                asleep +
                " screenOff=" +
                screenOff +
                " kind=" +
                (if (decision == null) kia.app.sunroof.SunroofAlertPolicy.Kind.NONE
                else decision.kind) +
                " sequence=" +
                (if (decision == null) if (realSequence == Long.MIN_VALUE) -1L else realSequence
                else decision.sequence) +
                " demo=" +
                (demo != null))
        diagnostics =
            (facts +
                " roofAt=" +
                vehicle.sunroofReceivedElapsed +
                " savedAt=" +
                remembered.recordedAt)
        // Timestamp-only CAN repeats and the 1-second service tick must not flood the log.
        if (!facts.equals(lastDiagnosticKey)) {
            lastDiagnosticKey = facts
            AppLog.line(app, "Sunroof decision: " + diagnostics)
        }
    }

    private fun higherPriorityHazard(): Boolean {
        return (StateStore.rcta().active() && AppSettings.rctaOverlayEnabled(app)) ||
            (TpmsWarningOverlayController.get(app).isShowing() &&
                TpmsAlertController.hasCriticalWarnings(app, StateStore.tpms()))
    }

    private fun previewOnMain(open: Boolean, parking: Boolean) {
        ensureStarted()
        applyOnMain()
        if (higherPriorityHazard() || asleep) return
        cancelDemo()
        qaMode = true
        qaAccKnown = true
        qaAccOn = true
        qaRoofKnown = true
        qaRoofOpen = !open
        qaPolicy.update(qaInput())
        qaRoofOpen = open
        demo = qaPolicy.update(qaInput())
        if (parking) {
            qaAccOn = false
            demo = qaPolicy.update(qaInput())
        }
        applyOnMain()
    }

    private fun qaInput(): Input {
        // The independent QA clock also permits two opposite samples in the same UI tick.
        qaClock = Math.max(SystemClock.elapsedRealtime(), qaClock + 1L)
        val now: Long = qaClock
        return Input(
            true,
            true,
            true,
            1L,
            qaRoofKnown,
            qaRoofOpen,
            1L,
            now,
            qaAccKnown,
            qaAccOn,
            now,
        )
    }

    private fun cancelDemo() {
        demo = null
        qaMode = false
        qaRoofKnown = false
        qaRoofOpen = false
        qaAccKnown = false
        qaAccOn = true
        qaPolicy.reset()
    }

    private fun dismissOnMain() {
        if (demo != null) {
            demo = qaPolicy.dismiss()
            if (demo!!.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.NONE) demo = null
        } else {
            if (policy.current().kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING) {
                SunroofStateMemory.acknowledge(app)
            }
            policy.dismiss()
        }
        hide()
    }

    private fun present(
        decision: kia.app.sunroof.SunroofAlertPolicy.Decision,
        demonstration: Boolean,
        showWindow: Boolean,
    ) {
        val warning: Boolean =
            decision.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING
        val allowAudio: Boolean = warning || AppSettings.sunroofTransitionSoundEnabled(app)
        sound.setEnabled(allowAudio)
        if (!showWindow) removeOverlay()
        val preserveWarningAudio: Boolean =
            warning && !demonstration && realWarningAudioSequence == decision.sequence
        // addView can return before the first attachment callback. A second state
        // broadcast in that gap must keep the pending window and its one-shot audio.
        if (overlay != null && overlayWasAttached && !overlay!!.isAttachedToWindow()) {
            if (preserveWarningAudio) hideWindow() else hide()
        }
        if (
            shownSequence == decision.sequence &&
                shownKind == decision.kind &&
                shownDemo == demonstration &&
                (!showWindow || overlay != null || !warning)
        ) {
            // A transient already handled inside KIA should not animate again outside it.
            if (allowAudio && !audioEnabled) playDecisionAudio(decision, demonstration)
            audioEnabled = allowAudio
            return
        }
        if (preserveWarningAudio) hideWindow() else hide()
        if (showWindow && !attachOverlay(decision, demonstration, warning, preserveWarningAudio))
            return
        shownSequence = decision.sequence
        shownKind = decision.kind
        shownDemo = demonstration
        sound.setEnabled(allowAudio)
        audioEnabled = allowAudio
        if (allowAudio) playDecisionAudio(decision, demonstration)
        if (!warning) {
            val sequence: Long = decision.sequence
            val current: Long = generation
            finishTransient = Runnable {
                if (
                    generation != current || shownSequence != sequence || shownDemo != demonstration
                )
                    return@Runnable
                if (demonstration) demo = qaPolicy.finish(sequence) else policy.finish(sequence)
                hide()
                applyOnMain()
            }
            main.postDelayed(finishTransient!!, SunroofOverlayView.TRANSIENT_DURATION_MS)
        }
    }

    private fun attachOverlay(
        decision: kia.app.sunroof.SunroofAlertPolicy.Decision,
        demonstration: Boolean,
        warning: Boolean,
        preserveWarningAudio: Boolean,
    ): Boolean {
        if (windowManager == null) return false
        val next: SunroofOverlayView = SunroofOverlayView(windowContext!!)
        next.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    if (overlay == view) overlayWasAttached = true
                }

                override fun onViewDetachedFromWindow(view: View) {
                    // The next apply distinguishes a detached old window from a pending new one.
                }
            }
        )
        val carWidthPx: Float = SunroofOverlayGeometry.carWidthPx(displayWidthPx())
        next.setCompactCarWidthPx(if (warning) 0f else carWidthPx)
        next.setSystemUiVisibility(
            (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        )
        try {
            overlay = next
            windowManager.addView(next, layoutParams(warning, carWidthPx))
            overlayWasAttached = next.isAttachedToWindow()
            next.present(decision.kind!!, Runnable { this.dismissOnMain() })
        } catch (error: RuntimeException) {
            removeOverlay()
            if (!preserveWarningAudio) sound.stop()
            finishUnavailableTransient(decision, demonstration)
            status = "Люк: окно недоступно"
            if (!windowFailureLogged) {
                windowFailureLogged = true
                AppLog.line(app, "Sunroof overlay add failed " + error.javaClass.getSimpleName())
            }
            return false
        }
        windowFailureLogged = false
        return true
    }

    private fun hide() {
        sound.stop()
        hideWindow()
    }

    private fun playDecisionAudio(
        decision: kia.app.sunroof.SunroofAlertPolicy.Decision,
        demonstration: Boolean,
    ) {
        if (
            !demonstration &&
                decision.kind == kia.app.sunroof.SunroofAlertPolicy.Kind.PARKING_WARNING
        ) {
            if (realWarningAudioSequence == decision.sequence) return
            // Consume before attempting playback: failure, focus loss and later wake must
            // not retry the same warning. Closing/ACK/ACC ON still cancel through hide().
            realWarningAudioSequence = decision.sequence
        }
        sound.play(decision.kind)
    }

    private fun hideWindow() {
        if (finishTransient != null) main.removeCallbacks(finishTransient!!)
        finishTransient = null
        audioEnabled = false
        shownSequence = Long.MIN_VALUE
        shownKind = kia.app.sunroof.SunroofAlertPolicy.Kind.NONE
        removeOverlay()
    }

    private fun removeOverlay() {
        val previous = overlay
        overlay = null
        overlayWasAttached = false
        if (previous != null) {
            previous.stopAnimations()
            if (windowManager != null) {
                try {
                    windowManager.removeViewImmediate(previous)
                } catch (ignored: RuntimeException) {}
            }
        }
    }

    private fun finishUnavailableTransient(
        decision: kia.app.sunroof.SunroofAlertPolicy.Decision,
        demonstration: Boolean,
    ) {
        if (
            decision.kind != kia.app.sunroof.SunroofAlertPolicy.Kind.OPENING &&
                decision.kind != kia.app.sunroof.SunroofAlertPolicy.Kind.CLOSING
        )
            return
        if (demonstration) demo = qaPolicy.finish(decision.sequence)
        else policy.finish(decision.sequence)
    }

    private fun layoutParams(warning: Boolean, carWidthPx: Float): WindowManager.LayoutParams? {
        var flags: Int =
            (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (!warning)
            flags =
                flags or
                    (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val params: WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                if (warning) ViewGroup.LayoutParams.MATCH_PARENT
                else SunroofOverlayGeometry.compactWidthPx(carWidthPx),
                if (warning) ViewGroup.LayoutParams.MATCH_PARENT
                else SunroofOverlayGeometry.compactHeightPx(carWidthPx),
                SunroofOverlayController.overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = if (warning) Gravity.TOP or Gravity.START else Gravity.CENTER
        params.setTitle("Kia sunroof")
        params.alpha = 1f
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return params
    }

    private fun displayWidthPx(): Int {
        if (Build.VERSION.SDK_INT >= 30 && windowManager != null) {
            try {
                val width: Int = windowManager.getCurrentWindowMetrics().getBounds().width()
                if (width > 0) return width
            } catch (ignored: RuntimeException) {
                // Some vendor firmware does not supply metrics for an overlay window context.
            }
        }
        val metrics: DisplayMetrics = DisplayMetrics()
        val display = if (windowManager == null) null else windowManager.getDefaultDisplay()
        if (display != null) display.getRealMetrics(metrics)
        else metrics.setTo(app.getResources().getDisplayMetrics())
        return metrics.widthPixels
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {
        if (stateReceiver != null) return
        stateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    apply()
                }
            }
        systemReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent!!.getAction()
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        screenOff = true
                        cancelDemo()
                        applyOnMain()
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        screenOff = false
                        asleep = false
                        apply()
                    } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                        val reason = intent.getStringExtra("reason")
                        if (
                            overlay != null &&
                                ("homekey".equals(reason) || "recentapps".equals(reason))
                        ) {
                            dismissCurrent()
                        }
                    }
                }
            }
        val states: IntentFilter = IntentFilter(AppIds.ACTION_STATE_CHANGED)
        val system: IntentFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        system.addAction(Intent.ACTION_SCREEN_ON)
        system.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(stateReceiver, states, Context.RECEIVER_NOT_EXPORTED)
            app.registerReceiver(systemReceiver, system, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(stateReceiver, states)
            app.registerReceiver(systemReceiver, system)
        }
    }

    private fun unregisterReceivers() {
        if (stateReceiver != null) {
            try {
                app.unregisterReceiver(stateReceiver)
            } catch (ignored: RuntimeException) {}
        }
        if (systemReceiver != null) {
            try {
                app.unregisterReceiver(systemReceiver)
            } catch (ignored: RuntimeException) {}
        }
        stateReceiver = null
        systemReceiver = null
    }

    @kotlin.jvm.Synchronized
    private fun postCurrent(action: Runnable) {
        val current: Long = generation
        val guarded: Runnable = Runnable { if (current == generation) action.run() }
        if (Looper.myLooper() === Looper.getMainLooper()) guarded.run() else main.post(guarded)
    }

    companion object {
        private var instance: SunroofOverlayController? = null
        const val DEFERRED_TRANSITION_TTL_MS: Long = 10000L

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): SunroofOverlayController {
            if (SunroofOverlayController.instance == null)
                SunroofOverlayController.instance = SunroofOverlayController(context)
            return SunroofOverlayController.instance!!
        }

        @JvmStatic
        fun canDrawOverlays(context: Context?): Boolean {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        }

        private fun createWindowContext(context: Context): Context? {
            if (Build.VERSION.SDK_INT < 30) return context
            try {
                val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
                val display = if (manager == null) null else manager.getDefaultDisplay()
                val displayContext =
                    if (display == null) context else context.createDisplayContext(display)
                return displayContext!!.createWindowContext(
                    SunroofOverlayController.overlayWindowType(),
                    null,
                )
            } catch (ignored: RuntimeException) {
                return context
            }
        }

        private fun overlayWindowType(): Int {
            return if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        }
    }
}
