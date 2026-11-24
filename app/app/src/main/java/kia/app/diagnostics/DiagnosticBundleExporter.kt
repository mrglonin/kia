package kia.app.diagnostics

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kia.app.adas.AdasController
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.settings.AppSettings
import kia.app.sunroof.SunroofOverlayController

/** Creates a user-requested, bounded support archive without route or media text fields. */
object DiagnosticBundleExporter {
    const val FILE_NAME_PREFIX: String = "kia-diagnostics-"
    private const val MAX_LOG_BYTES: Long = 1024L * 1024L

    @JvmStatic
    @Throws(Exception::class)
    fun write(context: Context?, target: android.net.Uri?) {
        require(!(context == null || target == null)) { "target" }
        context.getContentResolver().openOutputStream(target, "wt").use { output ->
            if (output == null) null
            else
                ZipOutputStream(output).use { zip ->
                    checkNotNull(zip) { "Cannot open output" }
                    DiagnosticBundleExporter.addText(
                        zip,
                        "summary.txt",
                        DiagnosticBundleExporter.summary(context),
                    )
                    DiagnosticBundleExporter.addText(zip, "general.log", AppLog.text())
                    DiagnosticBundleExporter.addText(
                        zip,
                        "settings.json",
                        SettingsTransfer.exportJson(context),
                    )
                    DiagnosticBundleExporter.addNavigationLogs(context, zip)
                    zip.finish()
                }
        }
    }

    @JvmStatic
    fun suggestedFileName(): String? {
        return ((DiagnosticBundleExporter.FILE_NAME_PREFIX +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())) + ".zip")
    }

    @JvmStatic
    fun summary(context: Context): String? {
        val adapter = StateStore.adapter()
        val navigation = StateStore.navigation()
        val media = StateStore.media()
        val tpms = StateStore.tpms()
        val rcta = StateStore.rcta()
        val now: Long = System.currentTimeMillis()
        val out: StringBuilder = StringBuilder()
        out.append("KIA diagnostic snapshot\n")
        out.append("created=").append(DiagnosticBundleExporter.date(now)).append('\n')
        out.append("app=").append(DiagnosticBundleExporter.appVersion(context)).append('\n')
        out.append("android=")
            .append(Build.VERSION.RELEASE)
            .append(" api=")
            .append(Build.VERSION.SDK_INT)
            .append(" device=")
            .append(Build.MANUFACTURER)
            .append(' ')
            .append(Build.MODEL)
            .append('\n')
        out.append("adapter_connected=")
            .append(adapter.usbConnected)
            .append(" uid=")
            .append(DiagnosticBundleExporter.clean(adapter.uid))
            .append(" firmware=")
            .append(DiagnosticBundleExporter.clean(adapter.firmware))
            .append(" last_frame_age=")
            .append(DiagnosticBundleExporter.age(now, adapter.lastFrameAt))
            .append(" health=")
            .append(DiagnosticBundleExporter.clean(adapter.health))
            .append('\n')
        out.append("navigation_enabled=")
            .append(AppSettings.navigationEnabled(context))
            .append(" source=")
            .append(AppSettings.navSourceLabel(context))
            .append(" active=")
            .append(navigation.active)
            .append(" snapshot_age=")
            .append(DiagnosticBundleExporter.age(now, navigation.updatedAt))
            .append(" maneuver_present=")
            .append(!navigation.maneuver.isEmpty())
            .append(" micro_present=")
            .append(!navigation.microManeuverId.isEmpty())
            .append(" cluster_tx_present=")
            .append(!navigation.clusterTx.isEmpty())
            .append('\n')
        out.append("media_profile=")
            .append(AppSettings.mediaProfileLabel(context))
            .append(" playing=")
            .append(media.playing)
            .append(" package=")
            .append(DiagnosticBundleExporter.clean(media.packageName))
            .append(" state_age=")
            .append(DiagnosticBundleExporter.age(now, media.updatedAt))
            .append(" cluster_tx_present=")
            .append(!media.clusterTx!!.isEmpty())
            .append('\n')
        out.append("tpms=")
            .append(DiagnosticBundleExporter.sanitize(tpms.summary()))
            .append(" state_age=")
            .append(DiagnosticBundleExporter.age(now, tpms.updatedAt))
            .append('\n')
        out.append("rcta=")
            .append(rcta.summary())
            .append(" state_age=")
            .append(DiagnosticBundleExporter.age(now, rcta.updatedAt))
            .append('\n')
        out.append("sunroof=")
            .append(SunroofOverlayController.get(context).statusSummary())
            .append('\n')
        out.append(AdasController.get(context).report()).append('\n')
        out.append("overlay_permission=")
            .append(Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context))
            .append('\n')
        out.append("auto_start=")
            .append(AppSettings.autoStart(context))
            .append(" expert_mode=")
            .append(AppSettings.expertMode(context))
            .append('\n')
        return out.toString()
    }

    @Throws(Exception::class)
    private fun addNavigationLogs(context: Context, zip: ZipOutputStream) {
        var root: File? = context.getExternalFilesDir(null)
        if (root == null) root = context.getFilesDir()
        val directory: File = File(root, "navigation-logs")
        val files: kotlin.Array<File?>? =
            directory.listFiles({ dir, name -> name.startsWith("navigation.log") })
        if (files == null) return
        var remaining: Long = DiagnosticBundleExporter.MAX_LOG_BYTES
        val buffer: ByteArray = ByteArray(8192)
        for (file: File? in files) {
            if (file == null || !file.isFile() || remaining <= 0L) continue
            val allowed: Long = Math.min(remaining, file.length())
            zip.putNextEntry(
                ZipEntry("navigation/" + DiagnosticBundleExporter.safeName(file.getName()))
            )
            FileInputStream(file).use { input ->
                var written: Long = 0L
                var read = 0
                while (
                    written < allowed &&
                        (input
                            .read(
                                buffer,
                                0,
                                Math.min(buffer.size.toLong(), allowed - written).toInt(),
                            )
                            .also { read = it }) != -1
                ) {
                    zip.write(buffer, 0, read)
                    written += read.toLong()
                }
                remaining -= written
            }
            zip.closeEntry()
        }
    }

    @Throws(Exception::class)
    private fun addText(zip: ZipOutputStream, name: String?, value: String?) {
        zip.putNextEntry(ZipEntry(name))
        val bytes: ByteArray? =
            (if (value == null) "" else value).toByteArray(StandardCharsets.UTF_8)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun appVersion(context: Context): String? {
        try {
            val info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
            val code: Long =
                if (Build.VERSION.SDK_INT >= 28) info!!.getLongVersionCode()
                else info!!.versionCode.toLong()
            return info.versionName + " (" + code + ")"
        } catch (ignored: Exception) {
            return "unknown"
        }
    }

    private fun age(now: Long, then: Long): String? {
        if (then <= 0L) return "never"
        val seconds: Long = Math.max(0L, now - then) / 1000L
        if (seconds < 60L) return seconds.toString() + "s"
        if (seconds < 3600L) return (seconds / 60L).toString() + "m"
        return (seconds / 3600L).toString() + "h"
    }

    private fun date(value: Long): String? {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(value))
    }

    private fun clean(value: String?): String {
        if (value == null) return ""
        return DiagnosticBundleExporter.sanitize(value.trim())
    }

    private fun sanitize(value: String?): String {
        if (value == null) return ""
        return value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\+?\\d[\\d ()-]{7,}\\d"), "[phone]")
    }

    private fun safeName(value: String?): String? {
        return if (value == null) "navigation.log" else value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
