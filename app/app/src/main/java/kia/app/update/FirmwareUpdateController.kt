package kia.app.update

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.UnaryOperator
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.UpdateState
import kia.app.navigation.domain.NavigationFeature
import kia.app.protocol.adapter.AdapterCommand
import kia.app.protocol.adapter.AdapterGateway
import kia.app.protocol.adapter.AdapterProtocol
import org.json.JSONObject

class FirmwareUpdateController private constructor(context: Context) {
    private val app: Context
    private val ackLock = java.lang.Object()
    @kotlin.concurrent.Volatile private var busy: Boolean = false
    @kotlin.concurrent.Volatile private var lastAck: Int = -1
    private var latest: kia.app.update.FirmwareUpdateController.ReleaseInfo =
        kia.app.update.FirmwareUpdateController.ReleaseInfo()

    init {
        this.app = context.applicationContext ?: context
    }

    fun busy(): Boolean {
        return busy
    }

    fun checkAsync() {
        if (busy) {
            AppLog.line(app, "Firmware update: busy")
            return
        }
        busy = true
        setFirmware("Firmware update: проверка GitHub", "", "", "", 0, 0, true, false, false, false)
        FirmwareUpdateController.EXEC!!.execute({
            try {
                latest = loadLatestRelease()
                val status: String?
                if (TextUtils.isEmpty(latest.downloadUrl)) {
                    status = "Firmware update: BIN не найден"
                } else if (latest.assetSize > FirmwareUpdateController.MAX_FIRMWARE_SIZE) {
                    status = "Firmware update: BIN больше 112 KB"
                } else {
                    status = "Firmware update: найден " + latest.assetName
                }
                setFirmware(
                    status,
                    latest.assetName,
                    latest.downloadUrl,
                    latest.sha256,
                    0,
                    latest.assetSize,
                    false,
                    false,
                    false,
                    false,
                )
                AppLog.line(
                    app,
                    "Firmware update: latest=" + latest.assetName + " size=" + latest.assetSize,
                )
            } catch (e: Exception) {
                setFirmware(
                    "Firmware update: ошибка " + e.javaClass.getSimpleName(),
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
                AppLog.line(
                    app,
                    "Firmware update check: " + e.javaClass.getSimpleName() + " " + e.message,
                )
            } finally {
                busy = false
            }
        })
    }

    fun downloadAndFlashLatest() {
        if (busy) {
            AppLog.line(app, "Firmware update: busy")
            return
        }
        if (TextUtils.isEmpty(latest.downloadUrl)) {
            checkThenFlash()
            return
        }
        downloadAndFlash(latest)
    }

    fun flashBundled(id: String) {
        if (busy) {
            AppLog.line(app, "Firmware update: busy")
            return
        }
        val bundled = FirmwareUpdateController.bundled(id)
        if (bundled == null) {
            setFirmware(
                "Firmware update: APK BIN " + id + " не найден",
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
            return
        }
        busy = true
        val source: kia.app.update.FirmwareUpdateController.ReleaseInfo = bundled.toReleaseInfo()
        setFirmware(
            "Firmware update: чтение из APK " + bundled.label,
            source.assetName,
            source.downloadUrl,
            source.sha256,
            0,
            source.assetSize,
            false,
            false,
            true,
            false,
        )
        FirmwareUpdateController.EXEC!!.execute({
            try {
                val data = readBundled(bundled)
                check(data!!.size <= FirmwareUpdateController.MAX_FIRMWARE_SIZE) {
                    "firmware too large"
                }
                check(!(bundled.assetSize > 0 && data.size.toLong() != bundled.assetSize)) {
                    "asset size mismatch"
                }
                check(FirmwareUpdateController.verifySha(data, bundled.sha256!!)) {
                    "sha256 mismatch"
                }
                setFirmware(
                    "Firmware update: APK BIN готов " + bundled.label,
                    source.assetName,
                    source.downloadUrl,
                    source.sha256,
                    data.size.toLong(),
                    source.assetSize,
                    false,
                    false,
                    true,
                    false,
                )
                flashBlocking(data, source)
            } catch (e: Exception) {
                setFirmware(
                    "Firmware update: ошибка APK BIN " + e.javaClass.getSimpleName(),
                    source.assetName,
                    source.downloadUrl,
                    source.sha256,
                    0,
                    source.assetSize,
                    false,
                    false,
                    false,
                    false,
                )
                AppLog.line(
                    app,
                    "Firmware bundled update: " + e.javaClass.getSimpleName() + " " + e.message,
                )
            } finally {
                busy = false
                endExclusiveUpdateAndResync()
            }
        })
    }

    fun flashFile(uri: Uri?, label: String?) {
        if (busy) {
            AppLog.line(app, "Firmware update: busy")
            return
        }
        if (uri == null) {
            setFirmware(
                "Firmware update: файл не выбран",
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
            return
        }
        busy = true
        val assetName = if (TextUtils.isEmpty(label)) "manual.bin" else label
        val source: kia.app.update.FirmwareUpdateController.ReleaseInfo =
            kia.app.update.FirmwareUpdateController.ReleaseInfo()
        source.assetName = assetName
        source.downloadUrl = uri.toString()
        setFirmware(
            "Firmware update: чтение файла " + assetName,
            assetName,
            source.downloadUrl,
            "",
            0,
            0,
            false,
            false,
            true,
            false,
        )
        FirmwareUpdateController.EXEC!!.execute({
            try {
                val data = readUri(uri)
                source.assetSize = data!!.size.toLong()
                setFirmware(
                    "Firmware update: файл готов " + assetName,
                    source.assetName,
                    source.downloadUrl,
                    "",
                    data.size.toLong(),
                    data.size.toLong(),
                    false,
                    false,
                    true,
                    false,
                )
                flashBlocking(data, source)
            } catch (e: Exception) {
                setFirmware(
                    "Firmware update: ошибка файла " + e.javaClass.getSimpleName(),
                    source.assetName,
                    source.downloadUrl,
                    "",
                    0,
                    source.assetSize,
                    false,
                    false,
                    false,
                    false,
                )
                AppLog.line(
                    app,
                    "Firmware file update: " + e.javaClass.getSimpleName() + " " + e.message,
                )
            } finally {
                busy = false
                endExclusiveUpdateAndResync()
            }
        })
    }

    fun handleAck(frame: ByteArray?) {
        if (frame == null || frame.size < 6) return
        synchronized(ackLock) {
            lastAck = frame.get(5).toInt() and 0xff
            ackLock.notifyAll()
        }
    }

    private fun checkThenFlash() {
        if (busy) return
        busy = true
        setFirmware("Firmware update: проверка GitHub", "", "", "", 0, 0, true, false, false, false)
        FirmwareUpdateController.EXEC!!.execute({
            try {
                latest = loadLatestRelease()
                busy = false
                if (TextUtils.isEmpty(latest.downloadUrl)) {
                    setFirmware(
                        "Firmware update: BIN не найден",
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
                    return@execute
                }
                downloadAndFlash(latest)
            } catch (e: Exception) {
                busy = false
                setFirmware(
                    "Firmware update: ошибка " + e.javaClass.getSimpleName(),
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
                AppLog.line(
                    app,
                    "Firmware update check: " + e.javaClass.getSimpleName() + " " + e.message,
                )
            }
        })
    }

    private fun downloadAndFlash(source: kia.app.update.FirmwareUpdateController.ReleaseInfo) {
        if (source.assetSize > FirmwareUpdateController.MAX_FIRMWARE_SIZE) {
            setFirmware(
                "Firmware update: BIN больше 112 KB",
                source.assetName,
                source.downloadUrl,
                source.sha256,
                0,
                source.assetSize,
                false,
                false,
                false,
                false,
            )
            return
        }
        busy = true
        setFirmware(
            "Firmware update: загрузка " + source.assetName,
            source.assetName,
            source.downloadUrl,
            source.sha256,
            0,
            source.assetSize,
            false,
            true,
            false,
            false,
        )
        FirmwareUpdateController.EXEC!!.execute({
            try {
                val data = download(source)
                check(FirmwareUpdateController.verifySha(data, source.sha256!!)) {
                    "sha256 mismatch"
                }
                setFirmware(
                    "Firmware update: BIN скачан",
                    source.assetName,
                    source.downloadUrl,
                    source.sha256,
                    data!!.size.toLong(),
                    source.assetSize,
                    false,
                    false,
                    true,
                    false,
                )
                flashBlocking(data, source)
            } catch (e: Exception) {
                setFirmware(
                    "Firmware update: ошибка " + e.javaClass.getSimpleName(),
                    source.assetName,
                    source.downloadUrl,
                    source.sha256,
                    0,
                    source.assetSize,
                    false,
                    false,
                    false,
                    false,
                )
                AppLog.line(
                    app,
                    "Firmware update: " + e.javaClass.getSimpleName() + " " + e.message,
                )
            } finally {
                busy = false
                endExclusiveUpdateAndResync()
            }
        })
    }

    private fun endExclusiveUpdateAndResync() {
        val gateway = AdapterGateway.get(app)
        val transportWasExclusive: Boolean = gateway!!.exclusiveUpdateMode()
        gateway.endExclusiveUpdate()
        if (!transportWasExclusive) return
        Handler(Looper.getMainLooper())
            .postDelayed(
                {
                    if (gateway.exclusiveUpdateMode()) return@postDelayed
                    gateway.start()
                    NavigationFeature.get(app)
                        .resendAfterTransportRecovery("adapter firmware update")
                },
                FirmwareUpdateController.TRANSPORT_RECOVERY_DELAY_MS,
            )
    }

    @Throws(Exception::class)
    private fun download(source: kia.app.update.FirmwareUpdateController.ReleaseInfo): ByteArray? {
        val connection = URL(source.downloadUrl).openConnection() as HttpURLConnection?
        connection!!.setConnectTimeout(12000)
        connection.setReadTimeout(30000)
        connection.setRequestProperty("User-Agent", "KiaFirmwareUpdater")
        var total: Long = connection.getContentLengthLong()
        if (total <= 0) total = source.assetSize
        val out: ByteArrayOutputStream =
            ByteArrayOutputStream(
                if (total > 0 && total < Integer.MAX_VALUE) total.toInt() else 8192
            )
        BufferedInputStream(connection.getInputStream()).use { `in` ->
            val buffer: ByteArray = ByteArray(8192)
            var done: Long = 0
            var lastReport: Long = 0
            var read: Int
            while ((`in`.read(buffer).also { read = it }) != -1) {
                out.write(buffer, 0, read)
                done += read.toLong()
                check(done <= FirmwareUpdateController.MAX_FIRMWARE_SIZE) { "firmware too large" }
                val now: Long = System.currentTimeMillis()
                if (now - lastReport > 500L) {
                    lastReport = now
                    setFirmware(
                        "Firmware update: загрузка " +
                            FirmwareUpdateController.percent(done, total),
                        source.assetName,
                        source.downloadUrl,
                        source.sha256,
                        done,
                        total,
                        false,
                        true,
                        false,
                        false,
                    )
                }
            }
        }
        return out.toByteArray()
    }

    @Throws(Exception::class)
    private fun readBundled(source: BundledFirmware): ByteArray? {
        val out: ByteArrayOutputStream =
            ByteArrayOutputStream(Math.max(8192L, source.assetSize).toInt())
        BufferedInputStream(app.getAssets().open(source.assetPath!!)).use { `in` ->
            val buffer: ByteArray = ByteArray(8192)
            var read: Int
            while ((`in`.read(buffer).also { read = it }) != -1) {
                out.write(buffer, 0, read)
                check(!(out.size() > FirmwareUpdateController.MAX_FIRMWARE_SIZE)) {
                    "firmware too large"
                }
            }
        }
        return out.toByteArray()
    }

    @Throws(Exception::class)
    private fun readUri(uri: Uri?): ByteArray? {
        val out: ByteArrayOutputStream = ByteArrayOutputStream(8192)
        val raw = app.getContentResolver().openInputStream(uri!!)
        checkNotNull(raw) { "file open failed" }
        BufferedInputStream(raw).use { `in` ->
            val buffer: ByteArray = ByteArray(8192)
            var read: Int
            while ((`in`.read(buffer).also { read = it }) != -1) {
                out.write(buffer, 0, read)
                check(!(out.size() > FirmwareUpdateController.MAX_FIRMWARE_SIZE)) {
                    "firmware too large"
                }
            }
        }
        return out.toByteArray()
    }

    @Throws(Exception::class)
    private fun flashBlocking(
        firmware: ByteArray?,
        source: kia.app.update.FirmwareUpdateController.ReleaseInfo,
    ) {
        require(!(firmware == null || firmware.size == 0)) { "empty firmware" }
        require(firmware.size <= FirmwareUpdateController.MAX_FIRMWARE_SIZE) {
            "firmware too large"
        }
        val gateway = AdapterGateway.get(app)
        gateway!!.beginExclusiveUpdate()
        setFirmware(
            "Firmware update: вход в bootloader",
            source.assetName,
            source.downloadUrl,
            source.sha256,
            0,
            firmware.size.toLong(),
            false,
            false,
            true,
            true,
        )
        var started: Boolean = false
        var attempt: Int = 1
        while (attempt <= 10 && !started) {
            resetAck()
            gateway.send(
                AdapterCommand.firmware("firmware start", AdapterProtocol.firmwareStart(), false)
            )
            started = waitForAck(1, 1200)
            if (!started) AppLog.line(app, "Firmware update: start retry " + attempt)
            attempt++
        }
        check(started) { "adapter did not enter firmware mode" }

        val blocks: Int = (firmware.size + 15) / 16
        for (i in 0..<blocks) {
            val offset: Int = i * 16
            val block = Arrays.copyOfRange(firmware, offset, Math.min(offset + 16, firmware.size))
            var accepted: Boolean = false
            var attempt: Int = 1
            while (attempt <= 3 && !accepted) {
                resetAck()
                gateway.send(
                    AdapterCommand.firmware(
                        "firmware block",
                        AdapterProtocol.firmwareBlock(offset, block),
                        true,
                    )
                )
                accepted = waitForAck(2, 1200)
                check(lastAck != 0) { "adapter cancelled at block " + i }
                attempt++
            }
            check(accepted) { "no ack for block " + i }
            if (i == 0 || i == blocks - 1 || i % 16 == 0) {
                val percent: Int = Math.max(0, Math.min(100, Math.round((i + 1) * 100f / blocks)))
                setFirmware(
                    "Firmware update: блоки " + (i + 1) + "/" + blocks,
                    source.assetName,
                    source.downloadUrl,
                    source.sha256,
                    (i + 1).toLong(),
                    blocks.toLong(),
                    false,
                    false,
                    true,
                    true,
                )
                AppLog.line(app, "Firmware update: " + percent + "%")
            }
        }
        resetAck()
        gateway.send(
            AdapterCommand.firmware("firmware finish", AdapterProtocol.firmwareFinish(), false)
        )
        setFirmware(
            "Firmware update: отправлено, адаптер перезапускается",
            source.assetName,
            source.downloadUrl,
            source.sha256,
            firmware.size.toLong(),
            firmware.size.toLong(),
            false,
            false,
            true,
            false,
        )
    }

    private fun resetAck() {
        synchronized(ackLock) { lastAck = -1 }
    }

    private fun waitForAck(expected: Int, timeoutMs: Long): Boolean {
        val end: Long = System.currentTimeMillis() + timeoutMs
        synchronized(ackLock) {
            while (System.currentTimeMillis() < end) {
                if (lastAck == expected) return true
                if (lastAck == 0) return false
                val remaining: Long = end - System.currentTimeMillis()
                if (remaining <= 0) break
                try {
                    ackLock.wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return lastAck == expected
        }
    }

    @Throws(Exception::class)
    private fun loadLatestRelease(): kia.app.update.FirmwareUpdateController.ReleaseInfo {
        try {
            val manifest: kia.app.update.FirmwareUpdateController.ReleaseInfo =
                loadManifestRelease()
            if (!TextUtils.isEmpty(manifest.downloadUrl)) return manifest
        } catch (e: Exception) {
            AppLog.line(app, "Firmware manifest fallback: " + e.javaClass.getSimpleName())
        }
        return loadGithubRelease()
    }

    @Throws(Exception::class)
    private fun loadManifestRelease(): kia.app.update.FirmwareUpdateController.ReleaseInfo {
        val connection =
            URL(FirmwareUpdateController.LATEST_MANIFEST_URL).openConnection() as HttpURLConnection?
        connection!!.setConnectTimeout(12000)
        connection.setReadTimeout(20000)
        connection.setRequestProperty("Accept", "application/vnd.github.raw")
        connection.setRequestProperty("User-Agent", "KiaFirmwareUpdater")
        val code: Int = connection.getResponseCode()
        val json =
            FirmwareUpdateController.readText(
                if (code >= 200 && code < 300) connection.getInputStream()
                else connection.getErrorStream()
            )
        check(!(code < 200 || code >= 300)) { "manifest HTTP " + code }
        val root: JSONObject = JSONObject(json)
        var firmware: JSONObject? = root.optJSONObject("firmware")
        if (firmware == null) {
            val encoded = root.optString("content", "")
            if (!TextUtils.isEmpty(encoded)) {
                val decoded: String =
                    String(
                        Base64.decode(encoded, Base64.DEFAULT),
                        java.nio.charset.StandardCharsets.UTF_8,
                    )
                firmware = JSONObject(decoded).optJSONObject("firmware")
            }
        }
        val info: kia.app.update.FirmwareUpdateController.ReleaseInfo =
            kia.app.update.FirmwareUpdateController.ReleaseInfo()
        if (firmware == null) return info
        info.version = firmware.optString("version", "")
        info.assetName = firmware.optString("asset_name", "")
        info.downloadUrl = firmware.optString("download_url", "")
        info.sha256 = firmware.optString("sha256", "")
        info.assetSize = firmware.optLong("size", 0)
        return info
    }

    @Throws(Exception::class)
    private fun loadGithubRelease(): kia.app.update.FirmwareUpdateController.ReleaseInfo {
        val connection =
            URL(FirmwareUpdateController.LATEST_RELEASE_URL).openConnection() as HttpURLConnection?
        connection!!.setConnectTimeout(12000)
        connection.setReadTimeout(20000)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "KiaFirmwareUpdater")
        val code: Int = connection.getResponseCode()
        val json =
            FirmwareUpdateController.readText(
                if (code >= 200 && code < 300) connection.getInputStream()
                else connection.getErrorStream()
            )
        check(!(code < 200 || code >= 300)) { "release HTTP " + code }
        val root: JSONObject = JSONObject(json)
        val assets = root.optJSONArray("assets")
        val best: kia.app.update.FirmwareUpdateController.ReleaseInfo =
            kia.app.update.FirmwareUpdateController.ReleaseInfo()
        best.version = root.optString("tag_name", "")
        if (assets == null) return best
        var bestScore: Int = -1
        for (i in 0..<assets.length()) {
            val asset = assets.optJSONObject(i)
            if (asset == null) continue
            val name = asset.optString("name", "")
            val lower = name.lowercase(Locale.US)
            if (!lower.endsWith(".bin")) continue
            val score: Int = FirmwareUpdateController.firmwareScore(lower)
            if (score < bestScore) continue
            bestScore = score
            best.assetName = name
            best.downloadUrl = asset.optString("browser_download_url", "")
            best.assetSize = asset.optLong("size", 0)
        }
        return best
    }

    private fun setFirmware(
        status: String?,
        asset: String?,
        url: String?,
        sha256: String?,
        done: Long,
        total: Long,
        checking: Boolean,
        downloading: Boolean,
        downloaded: Boolean,
        flashing: Boolean,
    ) {
        StateStore.updateUpdates(
            app,
            UnaryOperator<UpdateState?> { current ->
                current!!.withFirmware(
                    status,
                    asset,
                    url,
                    sha256,
                    done,
                    total,
                    checking,
                    downloading,
                    downloaded,
                    flashing,
                )
            },
        )
    }

    private class ReleaseInfo {
        @JvmField var version: String? = ""
        @JvmField var assetName: String? = ""
        @JvmField var downloadUrl: String? = ""
        @JvmField var sha256: String? = ""
        @JvmField var assetSize: Long = 0
    }

    private class BundledFirmware(
        id: String?,
        label: String?,
        assetPath: String?,
        assetName: String?,
        sha256: String?,
        assetSize: Long,
    ) {
        @JvmField val id: String?
        @JvmField val label: String?
        @JvmField val assetPath: String?
        @JvmField val assetName: String?
        @JvmField val sha256: String?
        @JvmField val assetSize: Long

        init {
            this.id = id
            this.label = label
            this.assetPath = assetPath
            this.assetName = assetName
            this.sha256 = sha256
            this.assetSize = assetSize
        }

        fun toReleaseInfo(): kia.app.update.FirmwareUpdateController.ReleaseInfo {
            val info: kia.app.update.FirmwareUpdateController.ReleaseInfo =
                kia.app.update.FirmwareUpdateController.ReleaseInfo()
            info.version = label
            info.assetName = assetName
            info.downloadUrl = "asset://" + assetPath
            info.sha256 = sha256
            info.assetSize = assetSize
            return info
        }
    }

    companion object {
        private const val LATEST_MANIFEST_URL: String =
            "https://api.github.com/repos/mrglonin/kia/contents/updates/latest.json?ref=main"
        private const val LATEST_RELEASE_URL: String =
            "https://api.github.com/repos/mrglonin/kia/releases/latest"
        private val BUNDLED_FIRMWARES: kotlin.Array<BundledFirmware?> =
            arrayOfNulls<BundledFirmware>(0)
        private const val MAX_FIRMWARE_SIZE: Int = 114688
        private const val TRANSPORT_RECOVERY_DELAY_MS: Long = 2500L
        private val EXEC: ExecutorService? = Executors.newSingleThreadExecutor()
        private var instance: FirmwareUpdateController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): FirmwareUpdateController {
            if (FirmwareUpdateController.instance == null)
                FirmwareUpdateController.instance = FirmwareUpdateController(context)
            return FirmwareUpdateController.instance!!
        }

        private fun firmwareScore(lower: String): Int {
            var score: Int = 0
            if (lower.contains("v24")) score += 100
            if (lower.contains("v21")) score += 80
            if (lower.contains("2can35")) score += 40
            if (lower.contains("kia")) score += 20
            if (lower.contains("usb")) score += 10
            if (lower.contains("stlink")) score -= 30
            return score
        }

        private fun bundled(id: String?): BundledFirmware? {
            if (id.isNullOrEmpty()) return null
            for (item: BundledFirmware? in FirmwareUpdateController.BUNDLED_FIRMWARES) {
                if (id.equals(item!!.id)) return item
            }
            return null
        }

        @Throws(Exception::class)
        private fun verifySha(data: ByteArray?, expected: String?): Boolean {
            if (expected.isNullOrEmpty()) return true
            val digest = MessageDigest.getInstance("SHA-256")
            val actual =
                FirmwareUpdateController.hex(
                    digest.digest(if (data == null) ByteArray(0) else data)
                )
            return expected.equals(actual, ignoreCase = true)
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
