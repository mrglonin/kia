package kia.app.rcta

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import kia.app.core.AppIds
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.RctaState
import kia.app.core.settings.AppSettings

class RctaOverlayController private constructor(context: Context) {
    private val app: Context
    private val windowContext: Context?
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager?
    private var overlay: BlindSpotOverlayView? = null
    private val sound: RctaSoundPlayer
    private var receiver: BroadcastReceiver? = null
    private var receiverRegistered: Boolean = false
    private var permissionLogged: Boolean = false

    init {
        this.app = context.applicationContext ?: context
        this.sound = RctaSoundPlayer(app, true)
        this.windowContext = RctaOverlayController.createWindowContext(app)
        this.windowManager =
            windowContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
    }

    @kotlin.jvm.Synchronized
    fun start() {
        registerReceiver()
        apply()
    }

    fun apply() {
        handler.post({ this.applyOnMain() })
    }

    @kotlin.jvm.Synchronized
    fun stop() {
        handler.post({
            hide()
            sound.stop()
            unregisterReceiver()
        })
    }

    private fun applyOnMain() {
        val state: RctaState? = StateStore.rcta()
        val active: Boolean = state != null && state.active()
        sound.apply(
            AppSettings.rctaStyle(app),
            AppSettings.rctaSoundMode(app),
            active && state.left,
            active && state.right,
            AppSettings.rctaSoundEnabled(app),
        )

        if (!active || !AppSettings.rctaOverlayEnabled(app)) {
            hide()
            return
        }
        if (!RctaOverlayController.canDrawOverlays(app)) {
            hide()
            if (!permissionLogged) {
                permissionLogged = true
                AppLog.line(app, "RCTA overlay: нужно разрешение поверх окон")
            }
            return
        }
        permissionLogged = false
        show(state.left, state.right)
    }

    private fun show(left: Boolean, right: Boolean) {
        if (windowManager == null) return
        if (overlay == null) {
            overlay = BlindSpotOverlayView(windowContext)
            overlay!!.setClickable(false)
            overlay!!.setBottomLiftDp(0)
            applyVisualSettings(overlay!!)
            overlay!!.setPreview(left, right, false)
            try {
                windowManager.addView(overlay, layoutParams())
            } catch (e: Exception) {
                overlay = null
                AppLog.line(app, "RCTA overlay: add failed " + e.javaClass.getSimpleName())
                return
            }
        } else {
            applyVisualSettings(overlay!!)
            overlay!!.setPreview(left, right, false)
            try {
                windowManager.updateViewLayout(overlay, layoutParams())
            } catch (ignored: Exception) {}
        }
        overlay!!.postInvalidateOnAnimation()
    }

    private fun applyVisualSettings(view: BlindSpotOverlayView) {
        view.setStyleType(AppSettings.rctaStyle(app))
        view.setAlertColor(AppSettings.rctaColor(app))
        view.setBackgroundAlpha(AppSettings.rctaBackgroundAlpha(app))
        view.setArrowCount(AppSettings.rctaArrowCount(app))
    }

    private fun hide() {
        if (overlay == null || windowManager == null) {
            overlay = null
            return
        }
        try {
            windowManager.removeView(overlay)
        } catch (ignored: Exception) {}
        overlay = null
    }

    private fun layoutParams(): WindowManager.LayoutParams? {
        val flags: Int =
            (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        val params: WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                RctaOverlayController.overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        return params
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver() {
        if (receiverRegistered) return
        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    apply()
                }
            }
        val filter: IntentFilter = IntentFilter(AppIds.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            app.unregisterReceiver(receiver)
        } catch (ignored: Exception) {}
        receiverRegistered = false
        receiver = null
    }

    companion object {
        private var instance: RctaOverlayController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): RctaOverlayController {
            if (RctaOverlayController.instance == null)
                RctaOverlayController.instance = RctaOverlayController(context)
            return RctaOverlayController.instance!!
        }

        @JvmStatic
        fun canDrawOverlays(context: Context?): Boolean {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        }

        private fun createWindowContext(context: Context): Context? {
            if (Build.VERSION.SDK_INT < 30) return context
            val type: Int = RctaOverlayController.overlayWindowType()
            try {
                val wm: WindowManager? =
                    context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
                val display: Display? = if (wm == null) null else wm.getDefaultDisplay()
                val displayContext: Context? =
                    if (display == null) context else context.createDisplayContext(display)
                return displayContext!!.createWindowContext(type, null)
            } catch (ignored: Exception) {
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
