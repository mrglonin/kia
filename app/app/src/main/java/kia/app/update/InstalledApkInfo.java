package kia.app.update;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.text.TextUtils;

import java.io.File;

final class InstalledApkInfo {
    private static final FileSha256Cache SHA_CACHE = new FileSha256Cache();

    final boolean installed;
    final long versionCode;
    final String versionName;
    final String sourcePath;
    final long sourceSize;
    final String sourceSha256;

    private InstalledApkInfo(boolean installed, long versionCode, String versionName,
                             String sourcePath, long sourceSize, String sourceSha256) {
        this.installed = installed;
        this.versionCode = versionCode;
        this.versionName = versionName == null ? "" : versionName;
        this.sourcePath = sourcePath == null ? "" : sourcePath;
        this.sourceSize = sourceSize;
        this.sourceSha256 = sourceSha256 == null ? "" : sourceSha256;
    }

    static InstalledApkInfo missing() {
        return new InstalledApkInfo(false, 0, "", "", 0, "");
    }

    static InstalledApkInfo read(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName)) return missing();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            ApplicationInfo appInfo = info.applicationInfo;
            String path = appInfo == null ? "" : appInfo.sourceDir;
            File file = TextUtils.isEmpty(path) ? null : new File(path);
            long size = file == null || !file.exists() ? 0 : file.length();
            String sha = file == null || !file.exists() ? "" : sha256(file);
            return new InstalledApkInfo(true, code, info.versionName, path, size, sha);
        } catch (Exception e) {
            return missing();
        }
    }

    boolean hasComparableArchive(String expectedSha256, long expectedSize) {
        return installed
                && ((!TextUtils.isEmpty(expectedSha256) && !TextUtils.isEmpty(sourceSha256))
                || (expectedSize > 0 && sourceSize > 0));
    }

    boolean matchesArchive(String expectedSha256, long expectedSize) {
        boolean compared = false;
        if (!TextUtils.isEmpty(expectedSha256) && !TextUtils.isEmpty(sourceSha256)) {
            compared = true;
            if (!expectedSha256.equalsIgnoreCase(sourceSha256)) return false;
        }
        if (expectedSize > 0 && sourceSize > 0) {
            compared = true;
            if (expectedSize != sourceSize) return false;
        }
        return compared;
    }

    String shortSha() {
        if (sourceSha256.length() <= 12) return sourceSha256;
        return sourceSha256.substring(0, 12);
    }

    private static String sha256(File file) {
        return SHA_CACHE.sha256(file);
    }
}
