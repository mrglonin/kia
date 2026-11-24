package kia.app.navigation.overlay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kia.app.core.AppIds
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.settings.AppSettings
import kia.app.navigation.domain.NavigationFeature
import kia.app.navigation.domain.NavigationModeSettings

class NavigationOverlayController private constructor(context: Context) {
    private val app: Context?
    private val windowContext: Context?
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager?
    private var overlay: LinearLayout? = null
    private var closeButton: TextView? = null
    private var modeText: TextView? = null
    private var statusText: TextView? = null
    private var txText: TextView? = null
    private var compassView: CompassMiniView? = null
    private var receiver: BroadcastReceiver? = null
    private var receiverRegistered: Boolean = false
    private var permissionLogged: Boolean = false

    private val refreshTick: Runnable =
        object : Runnable {
            override fun run() {
                updateText()
                if (overlay != null) handler.postDelayed(this, 1000L)
            }
        }

    init {
        this.app = context.getApplicationContext()
        this.windowContext = NavigationOverlayController.createWindowContext(app)
        this.windowManager =
            windowContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
    }

    @kotlin.jvm.Synchronized
    fun start() {
        registerReceiver()
        apply()
    }

    @kotlin.jvm.Synchronized
    fun apply() {
        if (!AppSettings.navOverlayEnabled(app!!)) {
            hide()
            return
        }
        if (!NavigationOverlayController.canDrawOverlays(app)) {
            hide()
            if (!permissionLogged) {
                permissionLogged = true
                AppLog.line(app, "Nav overlay: нужно разрешение поверх окон")
            }
            return
        }
        permissionLogged = false
        show()
    }

    @kotlin.jvm.Synchronized
    fun stop() {
        hide()
        unregisterReceiver()
    }

    private fun show() {
        if (windowManager == null) return
        if (overlay == null) {
            overlay = createOverlay()
            try {
                windowManager.addView(overlay, layoutParams())
                showCloseButton()
            } catch (e: Exception) {
                hideCloseButton()
                overlay = null
                AppLog.line(app, "Nav overlay: add failed " + e.javaClass.getSimpleName())
                return
            }
            handler.removeCallbacks(refreshTick)
            handler.post(refreshTick)
        } else {
            try {
                windowManager.updateViewLayout(overlay, layoutParams())
                showCloseButton()
            } catch (ignored: Exception) {}
        }
        updateText()
    }

    private fun hide() {
        handler.removeCallbacks(refreshTick)
        if (overlay == null || windowManager == null) {
            hideCloseButton()
            overlay = null
            statusText = null
            return
        }
        try {
            windowManager.removeView(overlay)
        } catch (ignored: Exception) {}
        hideCloseButton()
        overlay = null
        modeText = null
        statusText = null
        txText = null
        compassView = null
    }

    private fun showCloseButton() {
        if (windowManager == null) return
        if (closeButton == null) {
            closeButton = closeButton("Отключить", Color.argb(220, 18, 23, 31))
            try {
                windowManager.addView(closeButton, closeButtonLayoutParams())
            } catch (e: Exception) {
                closeButton = null
                AppLog.line(app, "Nav overlay close: add failed " + e.javaClass.getSimpleName())
            }
            return
        }
        try {
            windowManager.updateViewLayout(closeButton, closeButtonLayoutParams())
        } catch (ignored: Exception) {}
    }

    private fun hideCloseButton() {
        if (closeButton == null || windowManager == null) {
            closeButton = null
            return
        }
        try {
            windowManager.removeView(closeButton)
        } catch (ignored: Exception) {}
        closeButton = null
    }

    private fun closeButton(text: String?, color: Int): TextView {
        val button: TextView = TextView(windowContext)
        button.setText(text)
        button.setTextColor(Color.WHITE)
        button.setTextSize(12f)
        button.setTypeface(Typeface.DEFAULT_BOLD)
        button.setIncludeFontPadding(false)
        button.setGravity(Gravity.CENTER)
        button.setPadding(dp(10), dp(7), dp(10), dp(7))
        button.setBackground(closeButtonBackground(color))
        button.setClickable(true)
        button.setFocusable(false)
        button.setOnClickListener({ v -> disableOverlayFromButton() })
        return button
    }

    private fun disableOverlayFromButton() {
        AppSettings.setNavDebugVisible(app!!, false)
        AppSettings.setNavOverlayEnabled(app, false)
        AppLog.line(app, "Nav overlay: disabled from overlay button")
        app.sendBroadcast(Intent(AppIds.ACTION_STATE_CHANGED).setPackage(AppIds.PACKAGE))
        hide()
    }

    private fun createOverlay(): LinearLayout {
        val metrics: DisplayMetrics? = displayMetrics()
        val panelWidth: Int = Math.max(1, Math.round(overlayWidth(metrics) * 0.5f))
        val padX: Int = Math.max(dp(6), Math.round(panelWidth * 0.025f))
        val padY: Int = Math.max(dp(5), Math.round(metrics!!.heightPixels * 0.012f))
        val modePx: Float = responsiveTextPx(metrics, 0.042f, 17, 25)
        val bodyPx: Float = responsiveTextPx(metrics, 0.038f, 15, 22)

        val root: LinearLayout = LinearLayout(windowContext)
        root.setOrientation(LinearLayout.HORIZONTAL)
        root.setGravity(Gravity.START)

        val left: LinearLayout = (panel(redBackground(), padX, padY))
        val right: LinearLayout = (panel(greenBackground(), padX, padY))

        val modeRow: LinearLayout = LinearLayout(windowContext)
        modeRow.setOrientation(LinearLayout.HORIZONTAL)
        modeRow.setGravity(Gravity.CENTER_VERTICAL)

        modeText = TextView(windowContext)
        modeText!!.setTextColor(Color.WHITE)
        modeText!!.setTextSize(TypedValue.COMPLEX_UNIT_PX, modePx)
        modeText!!.setIncludeFontPadding(false)
        modeText!!.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
        modeRow.addView(
            modeText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        compassView = CompassMiniView(windowContext)
        val compassSize: Int = Math.max(dp(24), Math.min(dp(42), Math.round(panelWidth * 0.075f)))
        val compassLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(compassSize, compassSize)
        compassLp.setMargins(dp(8), 0, 0, 0)
        modeRow.addView(compassView, compassLp)
        left.addView(
            modeRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        statusText = TextView(windowContext)
        statusText!!.setTextColor(Color.WHITE)
        statusText!!.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
        statusText!!.setLineSpacing(0f, 0.95f)
        statusText!!.setIncludeFontPadding(false)
        statusText!!.setGravity(Gravity.START)
        statusText!!.setSingleLine(false)
        if (Build.VERSION.SDK_INT >= 26) {
            statusText!!.setAutoSizeTextTypeUniformWithConfiguration(
                Math.max(1, Math.round(bodyPx * 0.72f)),
                Math.max(2, Math.round(bodyPx)),
                1,
                TypedValue.COMPLEX_UNIT_PX,
            )
        }
        val textLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        textLp.setMargins(0, Math.max(dp(4), Math.round(metrics.heightPixels * 0.006f)), 0, 0)
        left.addView(statusText, textLp)

        val txHeader: TextView = TextView(windowContext)
        txHeader.setTextColor(Color.WHITE)
        txHeader.setTextSize(TypedValue.COMPLEX_UNIT_PX, modePx)
        txHeader.setIncludeFontPadding(false)
        txHeader.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
        txHeader.setText("Приборка")
        right.addView(
            txHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        txText = TextView(windowContext)
        txText!!.setTextColor(Color.WHITE)
        txText!!.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
        txText!!.setLineSpacing(0f, 0.95f)
        txText!!.setIncludeFontPadding(false)
        txText!!.setGravity(Gravity.START)
        txText!!.setSingleLine(false)
        if (Build.VERSION.SDK_INT >= 26) {
            txText!!.setAutoSizeTextTypeUniformWithConfiguration(
                Math.max(1, Math.round(bodyPx * 0.66f)),
                Math.max(2, Math.round(bodyPx)),
                1,
                TypedValue.COMPLEX_UNIT_PX,
            )
        }
        val txLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        txLp.setMargins(0, Math.max(dp(4), Math.round(metrics.heightPixels * 0.006f)), 0, 0)
        right.addView(txText, txLp)

        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val metrics: DisplayMetrics? = displayMetrics()
        val flags: Int =
            (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        val params: WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                overlayWidth(metrics),
                ViewGroup.LayoutParams.MATCH_PARENT,
                NavigationOverlayController.overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        return params
    }

    private fun closeButtonLayoutParams(): WindowManager.LayoutParams {
        val flags: Int =
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        val params: WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34),
                NavigationOverlayController.overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = dp(12)
        return params
    }

    private fun updateText() {
        if (statusText == null || modeText == null || txText == null) return
        val finishDirection: Boolean = NavigationModeSettings.isFinishDirection(app)
        modeText!!.setText("Режим: " + navigationModeLabel())
        val step: Int = NavigationFeature.get(app!!).finishDirectionOverlayStep()
        if (compassView != null) {
            compassView!!.setVisibility(if (finishDirection) View.VISIBLE else View.GONE)
            compassView!!.setStep(step)
        }
        statusText!!.setText(
            NavigationOverlayController.styledOverlayDetails(
                StateStore.navigation()!!.overlayDetails()
            )
        )
        txText!!.setText(
            StateStore.navigation()!!.clusterTxDetails(
                NavigationModeSettings.isTbt(app),
                finishDirection,
            )
        )
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
            app!!.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app!!.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            app!!.unregisterReceiver(receiver)
        } catch (ignored: Exception) {}
        receiverRegistered = false
        receiver = null
    }

    private fun redBackground(): GradientDrawable {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(Color.argb(170, 170, 0, 0))
        return drawable
    }

    private fun greenBackground(): GradientDrawable {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(Color.argb(170, 0, 120, 0))
        return drawable
    }

    private fun closeButtonBackground(color: Int): GradientDrawable {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(color)
        drawable.setStroke(dp(1), Color.argb(150, 255, 255, 255))
        drawable.setCornerRadius(dp(10).toFloat())
        return drawable
    }

    private fun panel(background: GradientDrawable?, padX: Int, padY: Int): LinearLayout {
        val panel: LinearLayout = LinearLayout(windowContext)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setGravity(Gravity.START)
        panel.setPadding(padX, padY, padX, padY)
        panel.setBackground(background)
        return panel
    }

    private fun navigationModeLabel(): String? {
        return NavigationModeSettings.label(app)
    }

    private fun displayMetrics(): DisplayMetrics? {
        val metrics: DisplayMetrics = DisplayMetrics()
        if (windowManager != null) {
            try {
                windowManager.getDefaultDisplay().getMetrics(metrics)
                return metrics
            } catch (ignored: Exception) {}
        }
        return app!!.getResources().getDisplayMetrics()
    }

    private fun overlayWidth(metrics: DisplayMetrics?): Int {
        val width: Int =
            if (metrics == null) app!!.getResources().getDisplayMetrics().widthPixels
            else metrics.widthPixels
        return Math.max(1, width)
    }

    private fun responsiveTextPx(
        metrics: DisplayMetrics?,
        heightRatio: Float,
        minDp: Int,
        maxDp: Int,
    ): Float {
        val height: Int =
            if (metrics == null) app!!.getResources().getDisplayMetrics().heightPixels
            else metrics.heightPixels
        val px: Float = height * heightRatio
        return (Math.max(dp(minDp).toFloat(), Math.min(dp(maxDp).toFloat(), px)))
    }

    private class CompassMiniView(context: Context?) : View(context) {
        private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val oval: RectF = RectF()
        private var step: Int = Integer.MIN_VALUE

        fun setStep(value: Int) {
            if (step == value) return
            step = value
            invalidate()
        }

        protected override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w: Int = getWidth()
            val h: Int = getHeight()
            val size: Float = Math.max(1f, Math.min(w, h).toFloat())
            val cx: Float = w / 2f
            val cy: Float = h / 2f
            val radius: Float = size * 0.42f

            paint.setStyle(Paint.Style.STROKE)
            paint.setStrokeWidth(Math.max(2f, size * 0.055f))
            paint.setColor(Color.argb(190, 255, 255, 255))
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawOval(oval, paint)

            if (step == Integer.MIN_VALUE) {
                paint.setStyle(Paint.Style.FILL)
                paint.setColor(Color.argb(150, 255, 255, 255))
                canvas.drawCircle(cx, cy, Math.max(2f, size * 0.06f), paint)
                return
            }

            canvas.save()
            canvas.rotate(step * 10f, cx, cy)
            paint.setStyle(Paint.Style.STROKE)
            paint.setStrokeCap(Paint.Cap.ROUND)
            paint.setStrokeJoin(Paint.Join.ROUND)
            paint.setStrokeWidth(Math.max(2f, size * 0.07f))
            paint.setColor(Color.WHITE)
            val tipY: Float = cy - radius * 0.74f
            val tailY: Float = cy + radius * 0.48f
            canvas.drawLine(cx, tailY, cx, tipY, paint)
            val wing: Float = radius * 0.28f
            canvas.drawLine(cx, tipY, cx - wing, tipY + wing, paint)
            canvas.drawLine(cx, tipY, cx + wing, tipY + wing, paint)
            canvas.restore()
        }
    }

    private fun dp(value: Int): Int {
        return Math.round(value * app!!.getResources().getDisplayMetrics().density)
    }

    companion object {
        private var instance: NavigationOverlayController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): NavigationOverlayController {
            if (NavigationOverlayController.instance == null)
                NavigationOverlayController.instance = NavigationOverlayController(context)
            return NavigationOverlayController.instance!!
        }

        @JvmStatic
        fun canDrawOverlays(context: Context?): Boolean {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        }

        private fun styledOverlayDetails(text: String?): CharSequence {
            val out: SpannableStringBuilder = SpannableStringBuilder(if (text == null) "" else text)
            NavigationOverlayController.boldLine(out, "Манёвр")
            NavigationOverlayController.boldLine(out, "Микра")
            NavigationOverlayController.boldLine(out, "Серая дорога")
            NavigationOverlayController.boldLine(out, "События")
            return out
        }

        private fun boldLine(text: SpannableStringBuilder, line: String) {
            val all = text.toString()
            var start: Int = all.indexOf(line)
            while (start >= 0) {
                val end: Int = start + line.length
                val startsLine: Boolean = start == 0 || all.get(start - 1) == '\n'
                val endsLine: Boolean = end == all.length || all.get(end) == '\n'
                if (startsLine && endsLine) {
                    text.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                start = all.indexOf(line, end)
            }
        }

        private fun createWindowContext(context: Context): Context? {
            if (Build.VERSION.SDK_INT < 30) return context
            val type: Int = NavigationOverlayController.overlayWindowType()
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
