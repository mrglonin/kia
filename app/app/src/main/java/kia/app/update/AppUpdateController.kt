package kia.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.UnaryOperator
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.UpdateState
import kia.app.core.settings.AppSettings
import org.json.JSONObject

class AppUpdateController(context: Context) {
    private val app: Context
    @kotlin.concurrent.Volatile private var busy: Boolean = false

    init {
        this.app = context.applicationContext ?: context
    }

    @kotlin.jvm.JvmOverloads
    fun checkAsync(activity: Activity? = null) {
        if (busy || !AppUpdateController.CHECK_BUSY.compareAndSet(false, true)) {
            if (activity != null)
                Toast.makeText(activity, "Проверка уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        setApp("App update: проверка GitHub", "", "", "", 0, 0, false, true, false, false)
        AppUpdateController.EXEC!!.execute({
            try {
                val info = loadLatestRelease()
                AppUpdateController.latest = info
                val installed = currentApk()
                val available: Boolean = AppUpdateController.shouldInstall(info, installed)
                val file = downloadedFile((info.assetName)!!)
                val downloaded: Boolean =
                    available &&
                        file.exists() &&
                        file.length() > 0 &&
                        AppUpdateController.verifySha(file, (info.sha256)!!)
                val status: String?
                if (TextUtils.isEmpty(info.downloadUrl)) {
                    status = "App update: APK не найден"
                } else if (AppUpdateController.sameVersionDifferentArchive(info, installed)) {
                    status = "App update: доступна пересборка " + info.assetName
                } else if (available) {
                    status = "App update: доступно " + info.assetName
                } else {
                    status = "App update: актуально"
                }
                setApp(
                    status,
                    info.assetName,
                    info.downloadUrl,
                    info.sha256,
                    0,
                    info.assetSize,
                    available,
                    false,
                    false,
                    downloaded,
                )
                if (AppUpdateController.releaseReady(info)) {
                    AppSettings.setLastAppUpdateCheckAt(app, System.currentTimeMillis())
                }
                UpdateNotificationController.refresh(app)
                AppLog.line(
                    app,
                    ("App update: currentCode=" +
                        installed.versionCode +
                        " latestCode=" +
                        info.versionCode +
                        " currentSize=" +
                        installed.sourceSize +
                        " latestSize=" +
                        info.assetSize +
                        " currentSha=" +
                        installed.shortSha() +
                        " asset=" +
                        info.assetName),
                )
            } catch (e: Exception) {
                setApp(
                    "App update: ошибка " + e.javaClass.getSimpleName(),
                    "",
                    "",
                    "",
                    0,
                    0,
                    false,
                    false,
                    false,
                    false,
                )
                UpdateNotificationController.refresh(app)
                AppLog.line(app, "App update: " + e.javaClass.getSimpleName() + " " + e.message)
            } finally {
                busy = false
                AppUpdateController.CHECK_BUSY.set(false)
            }
        })
    }

    fun downloadAndInstall(activity: Activity?) {
        if (activity == null) return
        val source = AppUpdateController.latest
        if (!AppUpdateController.releaseReady(source)) {
            checkAsync(activity)
            Toast.makeText(activity, "Сначала проверяю обновление", Toast.LENGTH_SHORT).show()
            return
        }
        val installed: InstalledApkInfo = currentApk()
        if (!AppUpdateController.shouldInstall(source, installed)) {
            setApp(
                "App update: актуально",
                source!!.assetName,
                source.downloadUrl,
                source.sha256,
                0,
                source.assetSize,
                false,
                false,
                false,
                false,
            )
            Toast.makeText(activity, "Kia уже актуальна", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AppUpdateController.canInstallPackages(activity)) {
            setApp(
                "App update: нужно разрешение установки APK",
                source!!.assetName,
                source.downloadUrl,
                source.sha256,
                0,
                source.assetSize,
                true,
                false,
                false,
                false,
            )
            AppUpdateController.openInstallPermission(activity)
            return
        }
        val existing: File = downloadedFile((source!!.assetName)!!)
        if (
            existing.exists() &&
                existing.length() > 0 &&
                AppUpdateController.verifySha(existing, (source.sha256)!!)
        ) {
            install(activity, existing)
            return
        }
        download(activity, source)
    }

    private fun download(
        activity: Activity,
        source: kia.app.update.AppUpdateController.ReleaseInfo,
    ) {
        if (busy) {
            Toast.makeText(activity, "Загрузка уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        setApp(
            "App update: загрузка " + source.assetName,
            source.assetName,
            source.downloadUrl,
            source.sha256,
            0,
            source.assetSize,
            true,
            false,
            true,
            false,
        )
        AppUpdateController.EXEC!!.execute({
            val out = downloadedFile(source.assetName!!)
            val tmp: File = File(out.getParentFile(), out.getName() + ".part")
            try {
                if (!out.getParentFile().exists()) out.getParentFile().mkdirs()
                val connection = URL(source.downloadUrl).openConnection() as HttpURLConnection?
                connection!!.setConnectTimeout(12000)
                connection.setReadTimeout(30000)
                connection.setRequestProperty("User-Agent", "KiaUpdater")
                var total: Long = connection.getContentLengthLong()
                if (total <= 0) total = source.assetSize
                BufferedInputStream(connection.getInputStream()).use { `in` ->
                    FileOutputStream(tmp).use { file ->
                        val buffer: ByteArray = ByteArray(32768)
                        var done: Long = 0
                        var lastReport: Long = 0
                        var read: Int
                        while ((`in`.read(buffer).also { read = it }) != -1) {
                            file.write(buffer, 0, read)
                            done += read.toLong()
                            val now: Long = System.currentTimeMillis()
                            if (now - lastReport > 500L) {
                                lastReport = now
                                setApp(
                                    "App update: загрузка " +
                                        AppUpdateController.percent(done, total),
                                    source.assetName,
                                    source.downloadUrl,
                                    source.sha256,
                                    done,
                                    total,
                                    true,
                                    false,
                                    true,
                                    false,
                                )
                            }
                        }
                    }
                }
                check(AppUpdateController.verifySha(tmp, source.sha256!!)) { "sha256 mismatch" }
                check(!(out.exists() && !out.delete())) { "old apk delete failed" }
                check(tmp.renameTo(out)) { "rename failed" }
                setApp(
                    "App update: APK скачан",
                    source.assetName,
                    source.downloadUrl,
                    source.sha256,
                    out.length(),
                    total,
                    true,
                    false,
                    false,
                    true,
                )
                activity.runOnUiThread({ install(activity, out) })
            } catch (e: Exception) {
                tmp.delete()
                setApp(
                    "App update: ошибка загрузки " + e.javaClass.getSimpleName(),
                    source.assetName,
                    source.downloadUrl,
                    source.sha256,
                    0,
                    source.assetSize,
                    true,
                    false,
                    false,
                    false,
                )
                AppLog.line(
                    app,
                    "App update download: " + e.javaClass.getSimpleName() + " " + e.message,
                )
            } finally {
                busy = false
            }
        })
    }

    private fun install(activity: Activity, file: File) {
        try {
            val uri =
                Uri.parse(
                    "content://" + activity.getPackageName() + ".updateprovider/" + file.getName()
                )
            val intent: Intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            intent.setDataAndType(uri, AppUpdateController.APK_MIME)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            activity.startActivity(intent)
            setApp(
                "App update: открыт установщик Android",
                AppUpdateController.latest!!.assetName,
                AppUpdateController.latest!!.downloadUrl,
                AppUpdateController.latest!!.sha256,
                file.length(),
                AppUpdateController.latest!!.assetSize,
                true,
                false,
                false,
                true,
            )
        } catch (e: Exception) {
            setApp(
                "App update: установщик не открылся",
                AppUpdateController.latest!!.assetName,
                AppUpdateController.latest!!.downloadUrl,
                AppUpdateController.latest!!.sha256,
                file.length(),
                AppUpdateController.latest!!.assetSize,
                true,
                false,
                false,
                true,
            )
            Toast.makeText(
                    activity,
                    "Не удалось открыть установщик: " + e.message,
                    Toast.LENGTH_LONG,
                )
                .show()
        }
    }

    @Throws(Exception::class)
    private fun loadLatestRelease(): kia.app.update.AppUpdateController.ReleaseInfo {
        var manifest: kia.app.update.AppUpdateController.ReleaseInfo? = null
        var github: kia.app.update.AppUpdateController.ReleaseInfo? = null
        var manifestFailure: Exception? = null
        var githubFailure: Exception? = null
        try {
            manifest = loadManifestRelease()
        } catch (e: Exception) {
            manifestFailure = e
            AppLog.line(app, "App update manifest fallback: " + e.javaClass.getSimpleName())
        }
        try {
            github = loadGithubRelease()
        } catch (e: Exception) {
            githubFailure = e
            AppLog.line(app, "App update release fallback: " + e.javaClass.getSimpleName())
        }
        if (
            AppUpdateController.preferGithubRelease(
                if (manifest == null) 0 else manifest.versionCode,
                AppUpdateController.releaseReady(manifest),
                if (github == null) 0 else github.versionCode,
                AppUpdateController.releaseReady(github),
            )
        ) {
            return github!!
        }
        if (AppUpdateController.releaseReady(manifest)) return manifest!!
        if (AppUpdateController.releaseReady(github)) return github!!
        if (manifestFailure != null) throw manifestFailure
        if (githubFailure != null) throw githubFailure
        return if (manifest == null) kia.app.update.AppUpdateController.ReleaseInfo() else manifest
    }

    @Throws(Exception::class)
    private fun loadManifestRelease(): kia.app.update.AppUpdateController.ReleaseInfo {
        val connection =
            URL(AppUpdateController.LATEST_MANIFEST_URL).openConnection() as HttpURLConnection?
        connection!!.setConnectTimeout(12000)
        connection.setReadTimeout(20000)
        connection.setRequestProperty("Accept", "application/vnd.github.raw")
        connection.setRequestProperty("User-Agent", "KiaUpdater")
        val code: Int = connection.getResponseCode()
        val json =
            AppUpdateController.readText(
                if (code >= 200 && code < 300) connection.getInputStream()
                else connection.getErrorStream()
            )
        check(!(code < 200 || code >= 300)) { "manifest HTTP " + code }
        var appJson: JSONObject? = JSONObject(json).optJSONObject("app")
        if (appJson == null) {
            val contents: JSONObject = JSONObject(json)
            val encoded = contents.optString("content", "")
            if (!TextUtils.isEmpty(encoded)) {
                val decoded: String =
                    String(
                        Base64.decode(encoded, Base64.DEFAULT),
                        java.nio.charset.StandardCharsets.UTF_8,
                    )
                appJson = JSONObject(decoded).optJSONObject("app")
            }
        }
        val info: kia.app.update.AppUpdateController.ReleaseInfo =
            kia.app.update.AppUpdateController.ReleaseInfo()
        if (appJson == null) return info
        info.versionName = appJson.optString("version_name", "")
        info.versionCode = appJson.optInt("version_code", 0)
        info.assetName = appJson.optString("asset_name", "")
        info.downloadUrl = appJson.optString("download_url", "")
        info.sha256 = appJson.optString("sha256", "")
        info.assetSize = appJson.optLong("size", 0)
        return info
    }

    @Throws(Exception::class)
    private fun loadGithubRelease(): kia.app.update.AppUpdateController.ReleaseInfo {
        val connection =
            URL(AppUpdateController.LATEST_RELEASE_URL).openConnection() as HttpURLConnection?
        connection!!.setConnectTimeout(12000)
        connection.setReadTimeout(20000)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "KiaUpdater")
        val code: Int = connection.getResponseCode()
        val json =
            AppUpdateController.readText(
                if (code >= 200 && code < 300) connection.getInputStream()
                else connection.getErrorStream()
            )
        check(!(code < 200 || code >= 300)) { "release HTTP " + code }
        val root: JSONObject = JSONObject(json)
        val assets = root.optJSONArray("assets")
        val info: kia.app.update.AppUpdateController.ReleaseInfo =
            kia.app.update.AppUpdateController.ReleaseInfo()
        info.versionName = root.optString("tag_name", "")
        if (assets == null) return info
        var bestCode: Int = -1
        for (i in 0..<assets.length()) {
            val asset = assets.optJSONObject(i)
            if (asset == null) continue
            val name = asset.optString("name", "")
            val lower = name.lowercase(Locale.US)
            if (!lower.endsWith(".apk")) continue
            if (lower.contains("yandex") || lower.contains("nav") || lower.contains("mod")) continue
            if (!lower.startsWith("kia_")) continue
            val assetCode: Int = AppUpdateController.versionCodeFromName(name)
            if (assetCode < bestCode) continue
            bestCode = assetCode
            info.assetName = name
            info.downloadUrl = asset.optString("browser_download_url", "")
            info.assetSize = asset.optLong("size", 0)
            info.versionCode = assetCode
            info.sha256 = AppUpdateController.githubSha256(asset.optString("digest", ""))
        }
        return info
    }

    private fun downloadedFile(assetName: String?): File {
        var safe: String? =
            if (assetName.isNullOrEmpty()) "kia_update.apk"
            else assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!safe!!.endsWith(".apk")) safe += ".apk"
        var root: File? = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (root == null) root = app.getFilesDir()
        return File(File(root, UpdateApkProvider.DIR), safe)
    }

    private fun currentApk(): InstalledApkInfo {
        return InstalledApkInfo.read(app, app.getPackageName())
    }

    private fun setApp(
        status: String?,
        asset: String?,
        url: String?,
        sha256: String?,
        done: Long,
        total: Long,
        available: Boolean,
        checking: Boolean,
        downloading: Boolean,
        downloaded: Boolean,
    ) {
        StateStore.updateUpdates(
            app,
            UnaryOperator<UpdateState?> { current ->
                current!!.withApp(
                    status,
                    asset,
                    url,
                    sha256,
                    done,
                    total,
                    available,
                    checking,
                    downloading,
                    downloaded,
                )
            },
        )
    }

    private class ReleaseInfo {
        @JvmField var versionName: String? = ""
        @JvmField var versionCode: Int = 0
        @JvmField var assetName: String? = ""
        @JvmField var downloadUrl: String? = ""
        @JvmField var sha256: String? = ""
        @JvmField var assetSize: Long = 0
    }

    companion object {
        private const val LATEST_MANIFEST_URL: String =
            "https://api.github.com/repos/mrglonin/kia/contents/updates/latest.json?ref=main"
        private const val LATEST_RELEASE_URL: String =
            "https://api.github.com/repos/mrglonin/kia/releases/latest"
        private const val APK_MIME: String = "application/vnd.android.package-archive"
        private val EXEC: ExecutorService? = Executors.newSingleThreadExecutor()
        private val CHECK_BUSY: AtomicBoolean = AtomicBoolean()

        @kotlin.concurrent.Volatile
        private var latest: kia.app.update.AppUpdateController.ReleaseInfo? =
            kia.app.update.AppUpdateController.ReleaseInfo()

        @JvmStatic
        fun preferGithubRelease(
            manifestCode: Int,
            manifestReady: Boolean,
            githubCode: Int,
            githubReady: Boolean,
        ): Boolean {
            return githubReady && (!manifestReady || githubCode > manifestCode)
        }

        private fun releaseReady(info: kia.app.update.AppUpdateController.ReleaseInfo?): Boolean {
            return info != null &&
                AppUpdateController.validReleaseMetadata(
                    info.versionCode,
                    info.assetName,
                    info.downloadUrl,
                    info.sha256,
                    info.assetSize,
                )
        }

        @JvmStatic
        fun validReleaseMetadata(
            versionCode: Int,
            assetName: String?,
            downloadUrl: String?,
            sha256: String?,
            assetSize: Long,
        ): Boolean {
            return versionCode > 0 &&
                assetName != null &&
                !assetName.trim().isEmpty() &&
                assetName.lowercase(Locale.US).endsWith(".apk") &&
                downloadUrl != null &&
                !downloadUrl.trim().isEmpty() &&
                sha256 != null &&
                sha256.matches(Regex("(?i)[0-9a-f]{64}")) &&
                assetSize > 0L
        }

        private fun shouldInstall(
            source: kia.app.update.AppUpdateController.ReleaseInfo?,
            installed: InstalledApkInfo?,
        ): Boolean {
            if (source == null || TextUtils.isEmpty(source.downloadUrl)) return false
            if (installed == null || !installed.installed) return true
            if (source.versionCode > installed.versionCode) return true
            if (source.versionCode < installed.versionCode) return false
            return AppUpdateController.sameVersionDifferentArchive(source, installed)
        }

        private fun sameVersionDifferentArchive(
            source: kia.app.update.AppUpdateController.ReleaseInfo?,
            installed: InstalledApkInfo?,
        ): Boolean {
            return source != null &&
                installed != null &&
                installed.installed &&
                source.versionCode > 0 &&
                source.versionCode.toLong() == installed.versionCode &&
                installed.hasComparableArchive(source.sha256, source.assetSize) &&
                !installed.matchesArchive(source.sha256!!, source.assetSize)
        }

        private fun versionCodeFromName(name: String?): Int {
            val digits = if (name == null) "" else name.replace(Regex("[^0-9]"), "")
            if (digits.length == 0) return 0
            try {
                return Integer.parseInt(digits)
            } catch (ignored: Exception) {
                return 0
            }
        }

        @JvmStatic
        fun githubSha256(digest: String?): String? {
            var value: String? = if (digest == null) "" else digest.trim()
            val prefix: String = "sha256:"
            if (value!!.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
                value = value.substring(prefix.length).trim()
            }
            return if (value.matches(Regex("(?i)[0-9a-f]{64}"))) value.lowercase(Locale.US) else ""
        }

        private fun canInstallPackages(activity: Activity): Boolean {
            return Build.VERSION.SDK_INT < 26 ||
                activity.getPackageManager().canRequestPackageInstalls()
        }

        private fun openInstallPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT < 26) return
            try {
                val intent: Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.setData(Uri.parse("package:" + activity.getPackageName()))
                activity.startActivity(intent)
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        }

        private fun verifySha(file: File?, expected: String?): Boolean {
            if (file == null || !file.exists() || expected.isNullOrEmpty()) return false
            try {
                BufferedInputStream(FileInputStream(file)).use { `in` ->
                    val digest: MessageDigest? = MessageDigest.getInstance("SHA-256")
                    val buffer: ByteArray = ByteArray(32768)
                    var read: Int
                    while ((`in`.read(buffer).also { read = it }) != -1) digest!!.update(
                        buffer,
                        0,
                        read,
                    )
                    val actual = AppUpdateController.hex(digest!!.digest())
                    return expected.equals(actual, ignoreCase = true)
                }
            } catch (e: Exception) {
                return false
            }
        }

        @Throws(Exception::class)
        private fun readText(`in`: InputStream?): String? {
            if (`in` == null) return ""
            val out: StringBuilder = StringBuilder()
            val buffer: ByteArray = ByteArray(8192)
            var read: Int
            while ((`in`.read(buffer).also { read = it }) != -1) {
                out.append(String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8))
            }
            return out.toString()
        }

        private fun percent(done: Long, total: Long): String? {
            if (total <= 0) return (done / 1024).toString() + " KB"
            return Math.min(100, Math.round(done * 100f / total)).toString() + "%"
        }

        private fun hex(data: ByteArray): String? {
            val out: StringBuilder = StringBuilder()
            for (b: Byte in data) {
                val v: Int = b.toInt() and 0xff
                if (v < 16) out.append('0')
                out.append(Integer.toHexString(v))
            }
            return out.toString()
        }
    }
}
