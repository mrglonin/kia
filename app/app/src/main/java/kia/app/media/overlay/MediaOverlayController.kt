package kia.app.media.overlay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kia.app.core.AppIds
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.CallState
import kia.app.core.model.MediaState
import kia.app.core.settings.AppSettings
import kia.app.media.domain.RadioStationStore

class MediaOverlayController private constructor(context: Context) {
    private val app: Context
    private val windowContext: Context?
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager?
    private var overlay: LinearLayout? = null
    private var closeButton: TextView? = null
    private var modeText: TextView? = null
    private var sourceText: TextView? = null
    private var txText: TextView? = null
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
        this.app = context.applicationContext ?: context
        this.windowContext = MediaOverlayController.createWindowContext(app)
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
        if (!AppSettings.mediaOverlayEnabled(app)) {
            hide()
            return
        }
        if (!MediaOverlayController.canDrawOverlays(app)) {
            hide()
            if (!permissionLogged) {
                permissionLogged = true
                AppLog.line(app, "Media overlay: нужно разрешение поверх окон")
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
                AppLog.line(app, "Media overlay: add failed " + e.javaClass.getSimpleName())
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
            modeText = null
            sourceText = null
            txText = null
            return
        }
        try {
            windowManager.removeView(overlay)
        } catch (ignored: Exception) {}
        hideCloseButton()
        overlay = null
        modeText = null
        sourceText = null
        txText = null
    }

    private fun showCloseButton() {
        if (windowManager == null) return
        if (closeButton == null) {
            closeButton = closeButton("Отключить", Color.argb(220, 18, 23, 31))
            try {
                windowManager.addView(closeButton, closeButtonLayoutParams())
            } catch (e: Exception) {
                closeButton = null
                AppLog.line(app, "Media overlay close: add failed " + e.javaClass.getSimpleName())
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

    private fun closeButton(text: String?, color: Int): TextView? {
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
        AppSettings.setMediaOverlayEnabled(app, false)
        AppLog.line(app, "Media overlay: disabled from overlay button")
        app.sendBroadcast(Intent(AppIds.ACTION_STATE_CHANGED).setPackage(AppIds.PACKAGE))
        hide()
    }

    private fun createOverlay(): LinearLayout? {
        val metrics: DisplayMetrics? = displayMetrics()
        val panelWidth: Int = Math.max(1, Math.round(overlayWidth(metrics) * 0.5f))
        val padX: Int = Math.max(dp(6), Math.round(panelWidth * 0.025f))
        val padY: Int = Math.max(dp(5), Math.round(metrics!!.heightPixels * 0.012f))
        val modePx: Float = responsiveTextPx(metrics, 0.042f, 17, 25)
        val bodyPx: Float = responsiveTextPx(metrics, 0.038f, 15, 22)
        val txPx: Float = responsiveTextPx(metrics, 0.034f, 13, 19)

        val root: LinearLayout = LinearLayout(windowContext)
        root.setOrientation(LinearLayout.HORIZONTAL)
        root.setGravity(Gravity.START)

        val left: LinearLayout = (panel(redBackground(), padX, padY))!!
        val right: LinearLayout = (panel(greenBackground(), padX, padY))!!

        modeText = headerText(modePx)
        left.addView(
            modeText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        sourceText = bodyText(bodyPx, false)
        val sourceLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        sourceLp.setMargins(0, Math.max(dp(4), Math.round(metrics.heightPixels * 0.006f)), 0, 0)
        left.addView(sourceText, sourceLp)

        val txHeader: TextView = headerText(modePx)!!
        txHeader.setText("Приборка")
        right.addView(
            txHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        txText = bodyText(txPx, true)
        val txLp: LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        txLp.setMargins(0, Math.max(dp(4), Math.round(metrics.heightPixels * 0.006f)), 0, 0)
        right.addView(txText, txLp)

        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    private fun layoutParams(): WindowManager.LayoutParams? {
        val metrics: DisplayMetrics? = displayMetrics()
        val flags: Int =
            (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        val params: WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                overlayWidth(metrics),
                ViewGroup.LayoutParams.MATCH_PARENT,
                MediaOverlayController.overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        return params
    }

    private fun closeButtonLayoutParams(): WindowManager.LayoutParams? {
        val flags: Int =
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        val params: WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34),
                MediaOverlayController.overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = dp(12)
        return params
    }

    private fun updateText() {
        if (modeText == null || sourceText == null || txText == null) return
        modeText!!.setText("Режим: " + AppSettings.mediaProfileLabel(app))
        sourceText!!.setText(mediaDetails().toString() + "\n\n" + callDetails())
        txText!!.setText(txDetails())
    }

    private fun mediaDetails(): String? {
        if (!AppSettings.mediaEnabled(app)) {
            return ("Профиль: " +
                AppSettings.mediaProfileLabel(app) +
                "\nРежим: Kia media выключен" +
                "\nЗахват: остановлен" +
                "\nОтправка: нет")
        }
        val media: MediaState? = StateStore.media()
        val out: StringBuilder = StringBuilder()
        out.append("Профиль: ").append(AppSettings.mediaProfileLabel(app))
        out.append("\nРежим: ").append(profileModeText())
        out.append("\nЗахват: ").append(captureText())
        out.append("\nТекст: ").append(AppSettings.mediaTextModeLabel(app))
        out.append("\nOther: ").append(AppSettings.otherMediaSourceLabel(app))
        if (
            media == null ||
                (media.title.isEmpty() && media.artist.isEmpty() && media.source.isEmpty())
        ) {
            out.append("\n\nМузыка: нет данных")
            appendRadioDetails(out, media)
            return out.toString()
        }
        val artist: String =
            if (media.artist.isEmpty() || "<unknown>".equals(media.artist, ignoreCase = true)) "-"
            else media.artist
        val title: String = if (media.title.isEmpty()) "-" else media.title
        val source: String = if (media.source.isEmpty()) "-" else media.source
        out.append("\n\nМузыка: ").append(if (media.playing) "играет" else "пауза")
        out.append("\nИсточник: ").append(source)
        if (!media.packageName.isEmpty()) out.append("\nПакет: ").append(media.packageName)
        out.append("\nТрек: ").append(title)
        out.append("\nИсполнитель: ").append(artist)
        if (media.durationMs >= 0L)
            out.append("\nДлительность: ")
                .append(MediaOverlayController.formatDuration(media.durationMs))
        appendRadioDetails(out, media)
        return out.toString()
    }

    private fun callDetails(): String? {
        if (!AppSettings.callEnabled(app)) return "BT звонок: выключен"
        val call: CallState? = StateStore.call()
        if (call == null || !call.active) {
            return ("BT звонок: нет активного звонка" +
                "\nШильдик: " +
                AppSettings.callSourceLabel(app) +
                "\nTEYES CC4 Pro")
        }
        val now: Long = System.currentTimeMillis()
        return ("BT звонок: активен" +
            "\nИмя: " +
            call.displayName() +
            "\nИсточник: " +
            call.subtitle() +
            "\nВремя: " +
            call.elapsedText(now) +
            "\nШильдик: " +
            AppSettings.callSourceLabel(app))
    }

    private fun txDetails(): String? {
        if (!AppSettings.mediaEnabled(app)) {
            return "Media выключено\nTX не отправляется"
        }
        val media: MediaState? = StateStore.media()
        val tx: String? = if (media == null) "" else media.clusterTx
        if (tx == null || tx.trim().isEmpty()) {
            return ("Профиль: " +
                AppSettings.mediaProfileLabel(app) +
                "\nTX: ожидание" +
                "\n\nКоманды появятся после смены источника, трека, радио или звонка.")
        }
        return formatTxFrames(tx)
    }

    private fun profileModeText(): String? {
        val profile: Int = AppSettings.mediaProfile(app)
        if (profile == AppSettings.MEDIA_PROFILE_TEYES) return "TEYES / CC4 Pro"
        if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID) return "Universal Android"
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL) return "UART real + Android"
        return "выключен"
    }

    private fun captureText(): String? {
        val profile: Int = AppSettings.mediaProfile(app)
        if (profile == AppSettings.MEDIA_PROFILE_TEYES) return "TEYES widget + SPD media/radio/bt"
        if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID)
            return "MediaSession + радио по базе Kia"
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL)
            return "MediaSession, source 0x7A не трогаем"
        return "нет"
    }

    private fun appendRadioDetails(out: StringBuilder, media: MediaState?) {
        val frequency: String? = RadioStationStore.currentFrequency(media)
        if (frequency == null || frequency.isEmpty()) return
        val band: String = RadioStationStore.currentBand(media)!!
        val station: String? = RadioStationStore.resolve(app, band, frequency, "")
        out.append("\nРадио: ").append(band).append(' ').append(frequency)
        if (station != null && !station.isEmpty()) out.append(" -> ").append(station)
        out.append("\nБаза станций: ").append(RadioStationStore.summary(app, 3))
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

    private fun redBackground(): GradientDrawable? {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(Color.argb(170, 170, 0, 0))
        return drawable
    }

    private fun greenBackground(): GradientDrawable? {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(Color.argb(170, 0, 120, 0))
        return drawable
    }

    private fun panel(background: GradientDrawable?, padX: Int, padY: Int): LinearLayout? {
        val panel: LinearLayout = LinearLayout(windowContext)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setGravity(Gravity.START)
        panel.setPadding(padX, padY, padX, padY)
        panel.setBackground(background)
        return panel
    }

    private fun headerText(sizePx: Float): TextView? {
        val view: TextView = TextView(windowContext)
        view.setTextColor(Color.WHITE)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        view.setIncludeFontPadding(false)
        view.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
        return view
    }

    private fun bodyText(sizePx: Float, mono: Boolean): TextView? {
        val view: TextView = TextView(windowContext)
        view.setTextColor(Color.WHITE)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        view.setLineSpacing(0f, 0.95f)
        view.setIncludeFontPadding(false)
        view.setGravity(if (mono) (Gravity.START or Gravity.BOTTOM) else Gravity.START)
        view.setSingleLine(false)
        if (mono) view.setTypeface(Typeface.MONOSPACE)
        if (Build.VERSION.SDK_INT >= 26) {
            view.setAutoSizeTextTypeUniformWithConfiguration(
                Math.max(1, Math.round(sizePx * (if (mono) 0.58f else 0.66f))),
                Math.max(2, Math.round(sizePx)),
                1,
                TypedValue.COMPLEX_UNIT_PX,
            )
        }
        return view
    }

    private fun closeButtonBackground(color: Int): GradientDrawable? {
        val drawable: GradientDrawable = GradientDrawable()
        drawable.setColor(color)
        drawable.setStroke(dp(1), Color.argb(150, 255, 255, 255))
        drawable.setCornerRadius(dp(10).toFloat())
        return drawable
    }

    private fun formatTxFrames(tx: String?): String? {
        val clean: String? = if (tx == null) "" else tx.trim()
        if (clean!!.isEmpty()) return "TX: ожидание"
        val lines: kotlin.Array<out String?>? = java.util.regex.Pattern.compile("\\n").split(clean)
        val out: StringBuilder = StringBuilder()
        val start: Int = Math.max(0, lines!!.size - 5)
        var visibleIndex: Int = 1
        for (i in start..<lines.size) {
            val line: String? = if (lines.get(i) == null) "" else lines.get(i)!!.trim()
            if (line!!.isEmpty()) continue
            val bytes: Int = line.indexOf(" bytes=")
            val label: String? = if (bytes >= 0) line.substring(0, bytes).trim() else line
            val frame: String? =
                if (bytes >= 0) line.substring(bytes + " bytes=".length).trim() else ""
            if (out.length > 0) out.append("\n\n")
            out.append("Кадр ").append(visibleIndex++).append(": ").append(label)
            if (!frame!!.isEmpty()) {
                out.append('\n').append(frame)
            }
        }
        return out.toString()
    }

    private fun displayMetrics(): DisplayMetrics? {
        val metrics: DisplayMetrics = DisplayMetrics()
        if (windowManager != null) {
            try {
                windowManager.getDefaultDisplay().getMetrics(metrics)
                return metrics
            } catch (ignored: Exception) {}
        }
        return app.getResources().getDisplayMetrics()
    }

    private fun overlayWidth(metrics: DisplayMetrics?): Int {
        val width: Int =
            if (metrics == null) app.getResources().getDisplayMetrics().widthPixels
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
            if (metrics == null) app.getResources().getDisplayMetrics().heightPixels
            else metrics.heightPixels
        val px: Float = height * heightRatio
        return (Math.max(dp(minDp).toFloat(), Math.min(dp(maxDp).toFloat(), px)))
    }

    private fun dp(value: Int): Int {
        return Math.round(value * app.getResources().getDisplayMetrics().density)
    }

    companion object {
        private var instance: MediaOverlayController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): MediaOverlayController {
            if (MediaOverlayController.instance == null)
                MediaOverlayController.instance = MediaOverlayController(context)
            return MediaOverlayController.instance!!
        }

        @JvmStatic
        fun canDrawOverlays(context: Context?): Boolean {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        }

        private fun createWindowContext(context: Context): Context? {
            if (Build.VERSION.SDK_INT < 30) return context
            val type: Int = MediaOverlayController.overlayWindowType()
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

        private fun formatDuration(value: Long): String? {
            if (value < 0L) return "-"
            val seconds: Long = value / 1000L
            val minutes: Long = seconds / 60L
            val rest: Long = seconds % 60L
            return minutes.toString() + ":" + (if (rest < 10) "0" else "") + rest
        }
    }
}
