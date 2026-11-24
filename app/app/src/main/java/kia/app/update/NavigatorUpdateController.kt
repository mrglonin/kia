package kia.app.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayList
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

class NavigatorUpdateController(context: Context) {
    private val app: Context
    @kotlin.concurrent.Volatile private var busy: Boolean = false

    init {
        this.app = context.applicationContext ?: context
    }

    @kotlin.jvm.JvmOverloads
    fun checkAsync(activity: Activity? = null) {
        if (busy || !NavigatorUpdateController.CHECK_BUSY.compareAndSet(false, true)) {
            if (activity != null)
                Toast.makeText(activity, "Проверка Navigator уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        setNavigator(
            "Navigator update: проверка GitHub",
            "",
            NavigatorUpdateController.DEFAULT_PACKAGE,
            "",
            0,
            0,
            false,
            true,
            false,
            false,
            false,
        )
        NavigatorUpdateController.EXEC!!.execute({
            try {
                val info = loadManifestNavigator()
                NavigatorUpdateController.latest = info
                val installed = installedNavigator(info.packageName)
                val manifestReady: Boolean = info.installable()
                val canInstall: Boolean =
                    manifestReady && NavigatorUpdateController.shouldInstall(info, installed)
                val downloaded: Boolean = canInstall && allDownloaded(info)
                val status: String?
                if (!manifestReady) status = "Navigator update: APK не найден"
                else if (!installed.installed)
                    status = "Navigator update: можно установить " + info.version
                else if (NavigatorUpdateController.sameVersionDifferentArchive(info, installed)) {
                    status = "Navigator update: доступна пересборка " + info.version
                } else if (canInstall) status = "Navigator update: доступен " + info.version
                else if (info.versionCode.toLong() == installed.versionCode)
                    status = "Navigator update: актуально " + info.version
                else status = "Navigator update: установлена новее"
                setNavigator(
                    status,
                    info.version,
                    info.packageName,
                    info.displayAsset(),
                    0,
                    info.totalSize(),
                    canInstall,
                    false,
                    false,
                    downloaded,
                    false,
                )
                if (manifestReady && info.versionCode > 0) {
                    AppSettings.setLastNavigatorUpdateCheckAt(app, System.currentTimeMillis())
                }
                UpdateNotificationController.refresh(app)
                AppLog.line(
                    app,
                    ("Navigator update: installedCode=" +
                        installed.versionCode +
                        " latestCode=" +
                        info.versionCode +
                        " installedSize=" +
                        installed.sourceSize +
                        " latestSize=" +
                        info.totalSize() +
                        " installedSha=" +
                        installed.shortSha() +
                        " files=" +
                        info.files.size),
                )
                val pendingActivity = NavigatorUpdateController.installAfterCheckActivity.get()
                NavigatorUpdateController.installAfterCheckActivity = WeakReference(null)
                if (
                    pendingActivity != null &&
                        canInstall &&
                        !pendingActivity.isFinishing() &&
                        !pendingActivity.isDestroyed()
                ) {
                    busy = false
                    pendingActivity.runOnUiThread({ downloadAndInstall(pendingActivity) })
                    return@execute
                }
            } catch (e: Exception) {
                NavigatorUpdateController.installAfterCheckActivity = WeakReference(null)
                setNavigator(
                    "Navigator update: ошибка " + e.javaClass.getSimpleName(),
                    "",
                    NavigatorUpdateController.DEFAULT_PACKAGE,
                    "",
                    0,
                    0,
                    false,
                    false,
                    false,
                    false,
                    false,
                )
                UpdateNotificationController.refresh(app)
                AppLog.line(
                    app,
                    "Navigator update check: " + e.javaClass.getSimpleName() + " " + e.message,
                )
            } finally {
                busy = false
                NavigatorUpdateController.CHECK_BUSY.set(false)
            }
        })
    }

    fun downloadAndInstall(activity: Activity?) {
        if (activity == null) return
        val source = NavigatorUpdateController.latest
        if (!source.installable()) {
            NavigatorUpdateController.installAfterCheckActivity = WeakReference(activity)
            checkAsync(activity)
            Toast.makeText(
                    activity,
                    "Проверяю Yandex Navigator и начну установку",
                    Toast.LENGTH_SHORT,
                )
                .show()
            return
        }
        if (busy) {
            Toast.makeText(activity, "Проверка Navigator уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        setNavigator(
            "Navigator update: проверка APK",
            source.version,
            source.packageName,
            source.displayAsset(),
            0,
            source.totalSize(),
            true,
            true,
            false,
            false,
            false,
        )
        NavigatorUpdateController.EXEC!!.execute({
            try {
                val installed = installedNavigator(source.packageName)
                val installNeeded: Boolean =
                    NavigatorUpdateController.shouldInstall(source, installed)
                val downloaded: Boolean = installNeeded && allDownloaded(source)
                activity.runOnUiThread({
                    busy = false
                    continueDownloadAndInstall(activity, source, installNeeded, downloaded)
                })
            } catch (e: Exception) {
                busy = false
                setNavigator(
                    "Navigator update: ошибка проверки " + e.javaClass.getSimpleName(),
                    source.version,
                    source.packageName,
                    source.displayAsset(),
                    0,
                    source.totalSize(),
                    true,
                    false,
                    false,
                    false,
                    false,
                )
                AppLog.line(
                    app,
                    ("Navigator update preflight: " + e.javaClass.getSimpleName() + " " + e.message),
                )
            }
        })
    }

    private fun continueDownloadAndInstall(
        activity: Activity,
        source: NavigatorInfo,
        installNeeded: Boolean,
        downloaded: Boolean,
    ) {
        if (!installNeeded) {
            setNavigator(
                "Navigator update: актуально " + source.version,
                source.version,
                source.packageName,
                source.displayAsset(),
                0,
                source.totalSize(),
                false,
                false,
                false,
                false,
                false,
            )
            Toast.makeText(activity, "Yandex Navigator уже актуален", Toast.LENGTH_SHORT).show()
            return
        }
        if (!NavigatorUpdateController.canInstallPackages(activity)) {
            setNavigator(
                "Navigator update: нужно разрешение установки APK",
                source.version,
                source.packageName,
                source.displayAsset(),
                0,
                source.totalSize(),
                true,
                false,
                false,
                false,
                false,
            )
            NavigatorUpdateController.openInstallPermission(activity)
            return
        }
        if (downloaded) {
            install(source)
            return
        }
        download(activity, source)
    }

    private fun download(activity: Activity?, source: NavigatorInfo) {
        if (busy) {
            Toast.makeText(activity, "Загрузка Navigator уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        setNavigator(
            "Navigator update: загрузка",
            source.version,
            source.packageName,
            source.displayAsset(),
            0,
            source.totalSize(),
            true,
            false,
            true,
            false,
            false,
        )
        NavigatorUpdateController.EXEC!!.execute({
            var done: Long = 0
            try {
                val root = navigatorDir()
                if (!root!!.exists()) root.mkdirs()
                for (file: NavFile? in source.files) {
                    val out: File = File(root, file!!.name)
                    if (file.hasParts()) {
                        val partsDir: File = File(root, file.name.toString() + ".parts")
                        if (!partsDir.exists()) partsDir.mkdirs()
                        for (part: NavPart? in file.parts) {
                            val partFile: File = File(partsDir, part!!.name)
                            if (!NavigatorUpdateController.verifySha(partFile, (part.sha256)!!)) {
                                downloadOne(part.url, partFile, (part.sha256)!!)
                            }
                            done += Math.max(0, partFile.length())
                            setNavigator(
                                "Navigator update: загрузка " +
                                    NavigatorUpdateController.percent(done, source.totalSize()),
                                source.version,
                                source.packageName,
                                part.name,
                                done,
                                source.totalSize(),
                                true,
                                false,
                                true,
                                false,
                                false,
                            )
                        }
                        if (!NavigatorUpdateController.verifySha(out, (file.sha256)!!)) {
                            concatenate(file, partsDir, out)
                        }
                    } else {
                        if (!NavigatorUpdateController.verifySha(out, (file.sha256)!!)) {
                            downloadOne(file.url, out, (file.sha256)!!)
                        }
                        done += Math.max(0, out.length())
                        setNavigator(
                            "Navigator update: загрузка " +
                                NavigatorUpdateController.percent(done, source.totalSize()),
                            source.version,
                            source.packageName,
                            file.name,
                            done,
                            source.totalSize(),
                            true,
                            false,
                            true,
                            false,
                            false,
                        )
                    }
                    check(NavigatorUpdateController.verifySha(out, (file.sha256)!!)) {
                        "sha256 mismatch " + file.name
                    }
                }
                setNavigator(
                    "Navigator update: APK скачан",
                    source.version,
                    source.packageName,
                    source.displayAsset(),
                    source.totalSize(),
                    source.totalSize(),
                    true,
                    false,
                    false,
                    true,
                    false,
                )
                install(source)
            } catch (e: Exception) {
                setNavigator(
                    "Navigator update: ошибка загрузки " + e.javaClass.getSimpleName(),
                    source.version,
                    source.packageName,
                    source.displayAsset(),
                    done,
                    source.totalSize(),
                    true,
                    false,
                    false,
                    false,
                    false,
                )
                AppLog.line(
                    app,
                    "Navigator update download: " + e.javaClass.getSimpleName() + " " + e.message,
                )
            } finally {
                busy = false
            }
        })
    }

    private fun install(source: NavigatorInfo) {
        NavigatorUpdateController.EXEC!!.execute({
            try {
                val root = navigatorDir()
                val installer = app.getPackageManager().getPackageInstaller()
                val params: PackageInstaller.SessionParams =
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(source.packageName)
                params.setSize(source.totalSize())
                val sessionId: Int = installer.createSession(params)
                val session = installer.openSession(sessionId)
                try {
                    for (file: NavFile? in source.files) {
                        val apk: File = File(root, file!!.name)
                        writeSessionFile(session, apk)
                    }
                    val intent: Intent = Intent(app, NavigatorInstallResultReceiver::class.java)
                    intent.setPackage(app.getPackageName())
                    var flags: Int = PendingIntent.FLAG_UPDATE_CURRENT
                    if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                    val pending =
                        PendingIntent.getBroadcast(
                            app,
                            NavigatorUpdateController.INSTALL_REQUEST,
                            intent,
                            flags,
                        )
                    session.commit(pending!!.getIntentSender())
                    setNavigator(
                        "Navigator update: открыт установщик Android",
                        source.version,
                        source.packageName,
                        source.displayAsset(),
                        source.totalSize(),
                        source.totalSize(),
                        true,
                        false,
                        false,
                        true,
                        true,
                    )
                } finally {
                    session.close()
                }
            } catch (e: Exception) {
                setNavigator(
                    "Navigator update: установщик не открылся",
                    source.version,
                    source.packageName,
                    source.displayAsset(),
                    source.totalSize(),
                    source.totalSize(),
                    true,
                    false,
                    false,
                    true,
                    false,
                )
                AppLog.line(
                    app,
                    "Navigator update install: " + e.javaClass.getSimpleName() + " " + e.message,
                )
            }
        })
    }

    @Throws(Exception::class)
    private fun writeSessionFile(session: PackageInstaller.Session, file: File) {
        BufferedInputStream(FileInputStream(file)).use { `in` ->
            session.openWrite(file.getName(), 0, file.length()).use { out ->
                val buffer: ByteArray = ByteArray(65536)
                var read: Int
                while ((`in`.read(buffer).also { read = it }) != -1) out.write(buffer, 0, read)
                session.fsync(out)
            }
        }
    }

    @Throws(Exception::class)
    private fun loadManifestNavigator(): NavigatorInfo {
        val connection =
            URL(NavigatorUpdateController.LATEST_MANIFEST_URL).openConnection()
                as HttpURLConnection?
        connection!!.setConnectTimeout(12000)
        connection.setReadTimeout(20000)
        connection.setRequestProperty("Accept", "application/vnd.github.raw")
        connection.setRequestProperty("User-Agent", "KiaUpdater")
        val code: Int = connection.getResponseCode()
        val json =
            NavigatorUpdateController.readText(
                if (code >= 200 && code < 300) connection.getInputStream()
                else connection.getErrorStream()
            )
        check(!(code < 200 || code >= 300)) { "manifest HTTP " + code }
        var navJson: JSONObject? = JSONObject(json).optJSONObject("navigator")
        if (navJson == null) {
            val contents: JSONObject = JSONObject(json)
            val encoded = contents.optString("content", "")
            if (!TextUtils.isEmpty(encoded)) {
                val decoded: String =
                    String(
                        Base64.decode(encoded, Base64.DEFAULT),
                        java.nio.charset.StandardCharsets.UTF_8,
                    )
                navJson = JSONObject(decoded).optJSONObject("navigator")
            }
        }
        val info: NavigatorInfo = NavigatorInfo()
        if (navJson == null) return info
        info.name = navJson.optString("name", "Yandex Navigator Kia hook")
        info.version = navJson.optString("version", "")
        info.versionCode = navJson.optInt("version_code", 0)
        info.packageName = navJson.optString("package", NavigatorUpdateController.DEFAULT_PACKAGE)
        info.apkDir = navJson.optString("apk_dir", "")
        val files = navJson.optJSONArray("files")
        if (files == null) return info
        for (i in 0..<files.length()) {
            val item = files.optJSONObject(i)
            if (item == null) {
                info.filesMetadataValid = false
                continue
            }
            val file: NavFile = NavFile()
            file.name = item.optString("name", "")
            file.size = item.optLong("size", 0)
            file.sha256 = item.optString("sha256", "")
            file.url =
                NavigatorUpdateController.downloadUrl(
                    item.optString("download_url", ""),
                    info.apkDir,
                    file.name,
                )
            val parts = item.optJSONArray("parts")
            if (parts != null) {
                for (j in 0..<parts.length()) {
                    val p = parts.optJSONObject(j)
                    if (p == null) {
                        file.partsMetadataValid = false
                        continue
                    }
                    val part: NavPart = NavPart()
                    part.name = p.optString("name", "")
                    part.size = p.optLong("size", 0)
                    part.sha256 = p.optString("sha256", "")
                    part.url =
                        NavigatorUpdateController.downloadUrl(
                            p.optString("download_url", ""),
                            info.apkDir,
                            part.name,
                        )
                    file.parts.add(part)
                }
            }
            info.files.add(file)
        }
        return info
    }

    private fun allDownloaded(info: NavigatorInfo?): Boolean {
        if (info == null || info.files.isEmpty()) return false
        val root: File = navigatorDir()!!
        for (file: NavFile? in info.files) {
            if (!NavigatorUpdateController.verifySha(File(root, file!!.name), file.sha256!!))
                return false
        }
        return true
    }

    @Throws(Exception::class)
    private fun downloadOne(url: String?, out: File, sha256: String) {
        check(!TextUtils.isEmpty(url)) { "empty url" }
        if (out.getParentFile() != null && !out.getParentFile().exists())
            out.getParentFile().mkdirs()
        val tmp: File = File(out.getParentFile(), out.getName() + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection?
        connection!!.setConnectTimeout(12000)
        connection.setReadTimeout(60000)
        connection.setRequestProperty("User-Agent", "KiaUpdater")
        BufferedInputStream(connection.getInputStream()).use { `in` ->
            FileOutputStream(tmp).use { file ->
                val buffer: ByteArray = ByteArray(65536)
                var read: Int
                while ((`in`.read(buffer).also { read = it }) != -1) file.write(buffer, 0, read)
            }
        }
        check(NavigatorUpdateController.verifySha(tmp, sha256)) {
            "sha256 mismatch " + out.getName()
        }
        check(!(out.exists() && !out.delete())) { "delete failed " + out.getName() }
        check(tmp.renameTo(out)) { "rename failed " + out.getName() }
    }

    @Throws(Exception::class)
    private fun concatenate(file: NavFile, partsDir: File?, out: File) {
        val tmp: File = File(out.getParentFile(), out.getName() + ".join")
        FileOutputStream(tmp).use { joined ->
            val buffer: ByteArray = ByteArray(65536)
            for (part: NavPart? in file.parts) {
                val partFile: File = File(partsDir, part!!.name)
                BufferedInputStream(FileInputStream(partFile)).use { `in` ->
                    var read: Int
                    while ((`in`.read(buffer).also { read = it }) != -1) joined.write(
                        buffer,
                        0,
                        read,
                    )
                }
            }
        }
        check(NavigatorUpdateController.verifySha(tmp, file.sha256!!)) {
            "join sha256 mismatch " + file.name
        }
        check(!(out.exists() && !out.delete())) { "delete failed " + out.getName() }
        check(tmp.renameTo(out)) { "join rename failed " + out.getName() }
    }

    private fun navigatorDir(): File? {
        var root: File? = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (root == null) root = app.getFilesDir()
        return File(File(root, UpdateApkProvider.DIR), "navigator")
    }

    private fun installedNavigator(packageName: String?): InstalledApkInfo {
        return InstalledApkInfo.read(
            app,
            if (TextUtils.isEmpty(packageName)) NavigatorUpdateController.DEFAULT_PACKAGE
            else packageName,
        )
    }

    private fun setNavigator(
        status: String?,
        version: String?,
        packageName: String?,
        asset: String?,
        done: Long,
        total: Long,
        available: Boolean,
        checking: Boolean,
        downloading: Boolean,
        downloaded: Boolean,
        installing: Boolean,
    ) {
        StateStore.updateUpdates(
            app,
            UnaryOperator<UpdateState?> { current ->
                current!!.withNavigator(
                    status,
                    version,
                    packageName,
                    asset,
                    NavigatorUpdateController.latest.manifestFingerprint(),
                    done,
                    total,
                    available,
                    checking,
                    downloading,
                    downloaded,
                    installing,
                )
            },
        )
    }

    internal class NavigatorInfo {
        @JvmField var name: String? = "Yandex Navigator Kia hook"
        @JvmField var version: String? = ""
        @JvmField var versionCode: Int = 0
        @JvmField var packageName: String? = NavigatorUpdateController.DEFAULT_PACKAGE
        @JvmField var apkDir: String? = ""
        @JvmField var filesMetadataValid: Boolean = true
        @JvmField val files: MutableList<NavFile?> = ArrayList()

        fun totalSize(): Long {
            var total: Long = 0
            for (file: NavFile? in files) total += Math.max(0, file!!.size)
            return total
        }

        fun displayAsset(): String? {
            if (files.isEmpty()) return ""
            return files.size.toString() + " APK / " + version
        }

        fun singleFile(): NavFile? {
            return if (files.size == 1) files.get(0) else null
        }

        fun installable(): Boolean {
            if (versionCode <= 0 || files.isEmpty() || !filesMetadataValid) return false
            for (file: NavFile? in files) {
                if (
                    !NavigatorUpdateController.validFileMetadata(
                        file!!.name,
                        file.url,
                        file.sha256,
                        file.size,
                    )
                ) {
                    return false
                }
                if (!file.partsMetadataValid) return false
                for (part: NavPart? in file.parts) {
                    if (
                        !NavigatorUpdateController.validPartMetadata(
                            part!!.name,
                            part.url,
                            part.sha256,
                            part.size,
                        )
                    ) {
                        return false
                    }
                }
            }
            return true
        }

        fun manifestFingerprint(): String? {
            val out: StringBuilder = StringBuilder()
            for (file: NavFile? in files) {
                out.append(file!!.name)
                    .append(':')
                    .append(file.sha256)
                    .append(':')
                    .append(file.size)
                    .append(';')
                for (part: NavPart? in file.parts) {
                    out.append(part!!.name)
                        .append(':')
                        .append(part.sha256)
                        .append(':')
                        .append(part.size)
                        .append(';')
                }
            }
            return out.toString()
        }
    }

    internal class NavFile {
        @JvmField var name: String? = ""
        @JvmField var url: String? = ""
        @JvmField var sha256: String? = ""
        @JvmField var size: Long = 0
        @JvmField var partsMetadataValid: Boolean = true
        @JvmField val parts: MutableList<NavPart?> = ArrayList()

        fun hasParts(): Boolean {
            return !parts.isEmpty()
        }
    }

    internal class NavPart {
        @JvmField var name: String? = ""
        @JvmField var url: String? = ""
        @JvmField var sha256: String? = ""
        @JvmField var size: Long = 0
    }

    companion object {
        private const val LATEST_MANIFEST_URL: String =
            "https://api.github.com/repos/mrglonin/kia/contents/updates/latest.json?ref=main"
        private const val RAW_ROOT: String = "https://raw.githubusercontent.com/mrglonin/kia/main/"
        private const val DEFAULT_PACKAGE: String = "ru.yandex.yandexnavi"
        private val EXEC: ExecutorService? = Executors.newSingleThreadExecutor()
        private val CHECK_BUSY: AtomicBoolean = AtomicBoolean()
        private const val INSTALL_REQUEST: Int = 29032

        @kotlin.concurrent.Volatile private var latest: NavigatorInfo = NavigatorInfo()
        @kotlin.concurrent.Volatile
        private var installAfterCheckActivity: WeakReference<Activity?> = WeakReference(null)

        private fun downloadUrl(explicit: String?, dir: String?, name: String?): String? {
            if (!TextUtils.isEmpty(explicit)) return explicit
            val path: String = (if (TextUtils.isEmpty(dir)) "" else dir.toString() + "/") + name
            return NavigatorUpdateController.RAW_ROOT + path.replace(" ", "%20")
        }

        private fun shouldInstall(source: NavigatorInfo?, installed: InstalledApkInfo?): Boolean {
            if (source == null || source.files.isEmpty()) return false
            if (installed == null || !installed.installed) return true
            if (source.versionCode > installed.versionCode) return true
            if (source.versionCode < installed.versionCode) return false
            return NavigatorUpdateController.sameVersionDifferentArchive(source, installed)
        }

        private fun sameVersionDifferentArchive(
            source: NavigatorInfo?,
            installed: InstalledApkInfo?,
        ): Boolean {
            val file = if (source == null) null else source.singleFile()
            return file != null &&
                installed != null &&
                installed.installed &&
                source!!.versionCode > 0 &&
                source.versionCode.toLong() == installed.versionCode &&
                installed.hasComparableArchive(file.sha256, file.size) &&
                !installed.matchesArchive(file.sha256!!, file.size)
        }

        @JvmStatic
        fun validFileMetadata(name: String?, url: String?, sha256: String?, size: Long): Boolean {
            return name != null &&
                !name.trim().isEmpty() &&
                name.lowercase(Locale.US).endsWith(".apk") &&
                NavigatorUpdateController.validArchiveMetadata(url, sha256, size)
        }

        @JvmStatic
        fun validPartMetadata(name: String?, url: String?, sha256: String?, size: Long): Boolean {
            return name != null &&
                !name.trim().isEmpty() &&
                NavigatorUpdateController.validArchiveMetadata(url, sha256, size)
        }

        private fun validArchiveMetadata(url: String?, sha256: String?, size: Long): Boolean {
            return url != null &&
                !url.trim().isEmpty() &&
                sha256 != null &&
                sha256.matches(Regex("(?i)[0-9a-f]{64}")) &&
                size > 0L
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
                    val buffer: ByteArray = ByteArray(65536)
                    var read: Int
                    while ((`in`.read(buffer).also { read = it }) != -1) digest!!.update(
                        buffer,
                        0,
                        read,
                    )
                    return expected.equals(
                        NavigatorUpdateController.hex(digest!!.digest()),
                        ignoreCase = true,
                    )
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
