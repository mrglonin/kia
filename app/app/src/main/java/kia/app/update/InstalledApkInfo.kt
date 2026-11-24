package kia.app.update

import android.content.Context
import android.os.Build
import android.text.TextUtils
import java.io.File

internal class InstalledApkInfo
private constructor(
    installed: Boolean,
    versionCode: Long,
    versionName: String?,
    sourcePath: String?,
    sourceSize: Long,
    sourceSha256: String?,
) {
    @JvmField val installed: Boolean
    @JvmField val versionCode: Long
    @JvmField val versionName: String
    @JvmField val sourcePath: String
    @JvmField val sourceSize: Long
    @JvmField val sourceSha256: String

    init {
        this.installed = installed
        this.versionCode = versionCode
        this.versionName = if (versionName == null) "" else versionName
        this.sourcePath = if (sourcePath == null) "" else sourcePath
        this.sourceSize = sourceSize
        this.sourceSha256 = if (sourceSha256 == null) "" else sourceSha256
    }

    fun hasComparableArchive(expectedSha256: String?, expectedSize: Long): Boolean {
        return installed &&
            ((!expectedSha256.isNullOrEmpty() && !TextUtils.isEmpty(sourceSha256)) ||
                (expectedSize > 0 && sourceSize > 0))
    }

    fun matchesArchive(expectedSha256: String?, expectedSize: Long): Boolean {
        var compared: Boolean = false
        if (!expectedSha256.isNullOrEmpty() && !TextUtils.isEmpty(sourceSha256)) {
            compared = true
            if (!expectedSha256.equals(sourceSha256, ignoreCase = true)) return false
        }
        if (expectedSize > 0 && sourceSize > 0) {
            compared = true
            if (expectedSize != sourceSize) return false
        }
        return compared
    }

    fun shortSha(): String? {
        if (sourceSha256.length <= 12) return sourceSha256
        return sourceSha256.substring(0, 12)
    }

    companion object {
        private val SHA_CACHE: FileSha256Cache = FileSha256Cache()

        @JvmStatic
        fun missing(): InstalledApkInfo {
            return InstalledApkInfo(false, 0, "", "", 0, "")
        }

        @JvmStatic
        fun read(context: Context?, packageName: String?): InstalledApkInfo {
            if (context == null || TextUtils.isEmpty(packageName)) return InstalledApkInfo.missing()
            try {
                val info = context.getPackageManager().getPackageInfo(packageName!!, 0)
                val code: Long =
                    if (Build.VERSION.SDK_INT >= 28) info!!.getLongVersionCode()
                    else info!!.versionCode.toLong()
                val appInfo = info.applicationInfo
                val path = if (appInfo == null) "" else appInfo.sourceDir
                val file = if (TextUtils.isEmpty(path)) null else File(path)
                val size: Long = if (file == null || !file.exists()) 0 else file.length()
                val sha: String =
                    if (file == null || !file.exists()) "" else InstalledApkInfo.sha256(file)
                return InstalledApkInfo(true, code, info.versionName, path, size, sha)
            } catch (e: Exception) {
                return InstalledApkInfo.missing()
            }
        }

        private fun sha256(file: File?): String {
            return InstalledApkInfo.SHA_CACHE.sha256(file)
        }
    }
}
