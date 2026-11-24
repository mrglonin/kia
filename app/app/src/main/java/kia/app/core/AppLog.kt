package kia.app.core

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AppLog {
    private const val MAX_LINES: Int = 250
    private const val NAV_LOG_MAX_BYTES: Int = 256 * 1024
    private const val NAV_LOG_BACKUPS: Int = 3
    private const val NAV_LOG_DIR: String = "navigation-logs"
    private const val NAV_LOG_FILE: String = "navigation.log"
    private val lines: ArrayDeque<String?> = ArrayDeque()
    private val navigationLogWriter: ExecutorService? =
        Executors.newSingleThreadExecutor({ runnable ->
            val thread: Thread = Thread(runnable, "kia-navigation-log-writer")
            thread.setDaemon(true)
            thread
        })

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun line(context: Context?, value: String?) {
        AppLog.addLine(
            context,
            AppLog.stamp().toString() + " " + (if (value == null) "" else value),
        )
    }

    @JvmStatic
    /**
     * Adds a navigation diagnostic to the existing UI ring and to a bounded on-device log.
     *
     * The durable log is stored in app-private storage and rotates at 256 KiB with three backups,
     * so route/TX evidence survives a process restart without unbounded disk growth.
     */
    @kotlin.jvm.Synchronized
    fun navigation(context: Context?, value: String?) {
        val message: String = if (value == null) "" else value
        AppLog.addLine(context, AppLog.stamp().toString() + " " + message)
        if (context == null) return
        var app: Context? = context.getApplicationContext()
        if (app == null) app = context
        val target: Context = app
        val durableText: String = AppLog.stampWithDate().toString() + " " + message
        AppLog.navigationLogWriter!!.execute({ AppLog.appendNavigationFile(target, durableText) })
    }

    private fun addLine(context: Context?, text: String?) {
        while (AppLog.lines.size >= AppLog.MAX_LINES) AppLog.lines.pollFirst()
        AppLog.lines.addLast(text)
        Log.i("Kia", text!!)
        StateStore.setLastLog(context, text)
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun text(): String? {
        val out: StringBuilder = StringBuilder()
        for (line: String? in AppLog.lines) {
            if (out.length > 0) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun lastLines(max: Int): String? {
        val out: StringBuilder = StringBuilder()
        val skip: Int = Math.max(0, AppLog.lines.size - max)
        var i: Int = 0
        for (line: String? in AppLog.lines) {
            if (i++ < skip) continue
            if (out.length > 0) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    @JvmStatic
    fun broadcast(context: Context?) {
        if (context == null) return
        val intent: Intent = Intent(AppIds.ACTION_STATE_CHANGED)
        intent.setPackage(context.getPackageName())
        context.sendBroadcast(intent)
    }

    private fun stamp(): String? {
        return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    }

    private fun stampWithDate(): String? {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    private fun appendNavigationFile(context: Context, line: String?) {
        try {
            var root: File? = context.getExternalFilesDir(null)
            if (root == null) root = context.getFilesDir()
            val directory: File = File(root, AppLog.NAV_LOG_DIR)
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) return
            val bytes: ByteArray? = (line.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            val current: File = File(directory, AppLog.NAV_LOG_FILE)
            if (current.length() + bytes!!.size > AppLog.NAV_LOG_MAX_BYTES) {
                AppLog.rotateNavigationFiles(directory, current)
            }
            FileOutputStream(current, true).use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (e: Exception) {
            Log.w("Kia", "Navigation durable log failed: " + e.javaClass.getSimpleName())
        }
    }

    private fun rotateNavigationFiles(directory: File?, current: File) {
        val oldest: File = AppLog.navigationBackup(directory, AppLog.NAV_LOG_BACKUPS)!!
        if (oldest.exists() && !oldest.delete()) {
            Log.w("Kia", "Navigation durable log: cannot delete oldest backup")
        }
        for (index in AppLog.NAV_LOG_BACKUPS - 1 downTo 1) {
            val source: File = AppLog.navigationBackup(directory, index)!!
            if (!source.exists()) continue
            val target: File = AppLog.navigationBackup(directory, index + 1)!!
            if (!source.renameTo(target)) {
                Log.w("Kia", "Navigation durable log: cannot rotate backup " + index)
            }
        }
        val first: File = AppLog.navigationBackup(directory, 1)!!
        if (current.exists() && !current.renameTo(first)) {
            Log.w("Kia", "Navigation durable log: cannot rotate current file")
            try {
                FileOutputStream(current, false).use { ignored -> }
            } catch (e: Exception) {
                Log.w("Kia", "Navigation durable log: cannot truncate current file")
            }
        }
    }

    private fun navigationBackup(directory: File?, index: Int): File? {
        return File(directory, AppLog.NAV_LOG_FILE + "." + index)
    }
}
