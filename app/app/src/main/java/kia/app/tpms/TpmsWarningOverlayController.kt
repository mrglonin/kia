package kia.app.tpms

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kia.app.R
import kia.app.core.AppLog
import kia.app.core.model.TpmsState
import kia.app.core.settings.AppSettings

class TpmsWarningOverlayController private constructor(context: Context) {
    private val app: Context
    private val windowContext: Context?
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager?
    private var overlay: LinearLayout? = null
    private var detailsText: TextView? = null
    private var player: MediaPlayer? = null
    private val lifecycle: TpmsAlertLifecycle = TpmsAlertLifecycle(TpmsAlertController.CAUSE_SLOTS)
    private var lifecycleGeneration: Long = 0
    private var permissionLogged: Boolean = false

    init {
        this.app = context.applicationContext ?: context
        this.windowContext = TpmsWarningOverlayController.createWindowContext(app)
        this.windowManager =
            windowContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
    }

    fun apply(state: TpmsState?, key: String?) {
        apply(
            TpmsAlertController.warningLevels(app, state)!!,
            TpmsAlertController.warningDetails(app, state),
        )
    }

    @kotlin.jvm.Synchronized
    fun apply(levels: IntArray, details: String?) {
        val copy: IntArray? = levels.clone()
        val generation: Long = lifecycleGeneration
        handler.post({
            synchronized(this) {
                if (generation != lifecycleGeneration) return@post
                applyOnMain(copy, details)
            }
        })
    }

    @kotlin.jvm.Synchronized
    fun stopSound() {
        val generation: Long = lifecycleGeneration
        handler.post({
            synchronized(this) { if (generation == lifecycleGeneration) stopSoundOnMain() }
        })
    }

    @kotlin.jvm.Synchronized
    fun dismissCurrent() {
        val generation: Long = lifecycleGeneration
        handler.post({ synchronized(this) { if (generation == lifecycleGeneration) dismiss() } })
    }

    @kotlin.jvm.Synchronized
    fun clear() {
        val generation: Long = ++lifecycleGeneration
        handler.removeCallbacksAndMessages(null)
        val cleanup: Runnable = Runnable {
            synchronized(this) {
                if (generation != lifecycleGeneration) return@Runnable
                lifecycle.reset()
                hide()
                stopSoundOnMain()
                permissionLogged = false
            }
        }
        if (Looper.myLooper() === Looper.getMainLooper()) cleanup.run() else handler.post(cleanup)
    }

    fun setActivityVisible(visible: Boolean) {
        // TPMS warnings must stay in the real overlay layer, even while Kia is open.
    }

    /** Main-thread window visibility, including acknowledgement and lost permission. */
    fun isShowing(): Boolean {
        return overlay != null && overlay!!.isAttachedToWindow()
    }

    private fun applyOnMain(levels: IntArray?, details: String?) {
        lifecycle.observe(levels, SystemClock.elapsedRealtime())
        if (!lifecycle.shouldShow()) {
            hide()
            stopSoundOnMain()
            return
        }
        if (TpmsWarningOverlayController.canDrawOverlays(app)) {
            show(details)
        } else if (!permissionLogged) {
            permissionLogged = true
            AppLog.line(app, "TPMS overlay: нужно разрешение поверх окон")
        }
        if (AppSettings.tpmsSoundAlertsEnabled(app)) {
            startSoundOnMain()
        } else {
            stopSoundOnMain()
        }
    }

    @kotlin.jvm.Synchronized
    private fun dismiss() {
        lifecycle.dismiss()
        hide()
        stopSoundOnMain()
        AppLog.line(app, "TPMS overlay dismissed")
    }

    private fun show(details: String?) {
        permissionLogged = false
        if (windowManager == null) return
        if (overlay == null) {
            overlay = createOverlay()
            try {
                windowManager.addView(overlay, layoutParams())
            } catch (e: Exception) {
                overlay = null
                detailsText = null
                AppLog.line(app, "TPMS overlay add failed " + e.javaClass.getSimpleName())
                return
            }
        } else {
            try {
                windowManager.updateViewLayout(overlay, layoutParams())
            } catch (ignored: Exception) {}
        }
        if (detailsText != null) {
            detailsText!!.setText(
                if (details == null || details.length == 0) "TPMS warning" else details
            )
        }
    }

    private fun hide() {
        if (overlay == null || windowManager == null) {
            overlay = null
            detailsText = null
            return
        }
        try {
            windowManager.removeView(overlay)
        } catch (ignored: Exception) {}
        overlay = null
        detailsText = null
    }

    private fun createOverlay(): LinearLayout? {
        val box: LinearLayout = LinearLayout(windowContext)
        box.setOrientation(LinearLayout.HORIZONTAL)
        box.setGravity(Gravity.CENTER_VERTICAL)
        box.setPadding(dp(28), dp(14), dp(28), dp(14))
        box.setBackground(redBackground())
        box.setClickable(false)
        box.setFocusable(false)

        val texts: LinearLayout = LinearLayout(windowContext)
        texts.setOrientation(LinearLayout.VERTICAL)
        val title: TextView = TextView(windowContext)
        title.setText("Предупреждение TPMS")
        title.setTextColor(Color.WHITE)
        title.setTextSize(21f)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        texts.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        detailsText = TextView(windowContext)
        detailsText!!.setTextColor(Color.rgb(255, 229, 229))
        detailsText!!.setTextSize(15f)
        detailsText!!.setSingleLine(true)
        val detailsLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        detailsLp.setMargins(0, dp(4), 0, 0)
        texts.addView(detailsText, detailsLp)

        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close: TextView = TextView(windowContext)
        close.setText("×")
        close.setTextColor(Color.WHITE)
        close.setTextSize(36f)
        close.setGravity(Gravity.CENTER)
        close.setTypeface(Typeface.DEFAULT_BOLD)
        close.setClickable(true)
        close.setFocusable(true)
        close.setContentDescription("Закрыть предупреждение TPMS")
        close.setOnClickListener({ v -> dismiss() })
        box.addView(close, LinearLayout.LayoutParams(dp(74), dp(74)))
        return box
    }

    private fun layoutParams(): WindowManager.LayoutParams? {
        val flags: Int =
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        val params: WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(124),
                TpmsWarningOverlayController.overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        return params
    }

    private fun startSoundOnMain() {
        if (player != null) {
            if (!player!!.isPlaying()) {
                try {
                    player!!.start()
                } catch (ignored: Exception) {
                    stopSoundOnMain()
                }
            }
            return
        }
        try {
            player = MediaPlayer.create(app, R.raw.tpms6_error)
            if (player == null) return
            player!!.setLooping(true)
            player!!.start()
        } catch (e: Exception) {
            stopSoundOnMain()
            AppLog.line(app, "TPMS sound failed " + e.javaClass.getSimpleName())
        }
    }

    private fun stopSoundOnMain() {
        if (player == null) return
        try {
            player!!.stop()
        } catch (ignored: Exception) {}
        try {
            player!!.release()
        } catch (ignored: Exception) {}
        player = null
    }

    private fun redBackground(): GradientDrawable? {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(Color.argb(225, 174, 20, 30))
        return drawable
    }

    private fun dp(value: Int): Int {
        return Math.round(value * app.getResources().getDisplayMetrics().density)
    }

    companion object {
        private var instance: TpmsWarningOverlayController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): TpmsWarningOverlayController {
            if (TpmsWarningOverlayController.instance == null)
                TpmsWarningOverlayController.instance = TpmsWarningOverlayController(context)
            return TpmsWarningOverlayController.instance!!
        }

        @JvmStatic
        fun canDrawOverlays(context: Context?): Boolean {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        }

        private fun createWindowContext(context: Context): Context? {
            if (Build.VERSION.SDK_INT < 30) return context
            val type: Int = TpmsWarningOverlayController.overlayWindowType()
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
