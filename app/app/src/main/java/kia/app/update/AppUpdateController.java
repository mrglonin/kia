package kia.app.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.UpdateState;

public final class AppUpdateController {
    private static final String LATEST_MANIFEST_URL =
            "https://api.github.com/repos/mrglonin/kia/contents/updates/latest.json?ref=main";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/mrglonin/kia/releases/latest";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private final Context app;
    private volatile boolean busy;
    private ReleaseInfo latest = new ReleaseInfo();

    public AppUpdateController(Context context) {
        this.app = context.getApplicationContext();
    }

    public void checkAsync() {
        checkAsync(null);
    }

    public void checkAsync(Activity activity) {
        if (busy) {
            if (activity != null) Toast.makeText(activity, "Проверка уже идёт", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        setApp("App update: проверка GitHub", "", "", "", 0, 0, false, true, false, false);
        EXEC.execute(() -> {
            try {
                ReleaseInfo info = loadLatestRelease();
                latest = info;
                int currentCode = currentVersionCode();
                boolean available = info.versionCode > currentCode;
                File file = downloadedFile(info.assetName);
                String status;
                if (TextUtils.isEmpty(info.downloadUrl)) {
                    status = "App update: APK не найден";
                } else if (available) {
                    status = "App update: доступно " + info.assetName;
                } else {
                    status = "App update: актуально";
                }
                setApp(status, info.assetName, info.downloadUrl, info.sha256, 0,
                        info.assetSize, available, false, false, file.exists() && file.length() > 0);
                AppLog.line(app, "App update: currentCode=" + currentCode
                        + " latestCode=" + info.versionCode
                        + " asset=" + info.assetName);
            } catch (Exception e) {
                setApp("App update: ошибка " + e.getClass().getSimpleName(), "", "", "",
                        0, 0, false, false, false, false);
                AppLog.line(app, "App update: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    public void downloadAndInstall(Activity activity) {
        if (activity == null) return;
        ReleaseInfo source = latest;
        if (TextUtils.isEmpty(source.downloadUrl)) {
            checkAsync(activity);
            Toast.makeText(activity, "Сначала проверяю обновление", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!canInstallPackages(activity)) {
            setApp("App update: нужно разрешение установки APK", source.assetName, source.downloadUrl,
                    source.sha256, 0, source.assetSize, true, false, false, false);
            openInstallPermission(activity);
            return;
        }
        File existing = downloadedFile(source.assetName);
        if (existing.exists() && existing.length() > 0 && verifySha(existing, source.sha256)) {
            install(activity, existing);
            return;
        }
        download(activity, source);
    }

    private void download(Activity activity, ReleaseInfo source) {
        if (busy) {
            Toast.makeText(activity, "Загрузка уже идёт", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        setApp("App update: загрузка " + source.assetName, source.assetName, source.downloadUrl,
                source.sha256, 0, source.assetSize, true, false, true, false);
        EXEC.execute(() -> {
            File out = downloadedFile(source.assetName);
            File tmp = new File(out.getParentFile(), out.getName() + ".part");
            try {
                if (!out.getParentFile().exists()) out.getParentFile().mkdirs();
                HttpURLConnection connection = (HttpURLConnection) new URL(source.downloadUrl).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "KiaUpdater");
                long total = connection.getContentLengthLong();
                if (total <= 0) total = source.assetSize;
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream file = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[32768];
                    long done = 0;
                    long lastReport = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        file.write(buffer, 0, read);
                        done += read;
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 500L) {
                            lastReport = now;
                            setApp("App update: загрузка " + percent(done, total), source.assetName,
                                    source.downloadUrl, source.sha256, done, total, true,
                                    false, true, false);
                        }
                    }
                }
                if (!verifySha(tmp, source.sha256)) throw new IllegalStateException("sha256 mismatch");
                if (out.exists() && !out.delete()) throw new IllegalStateException("old apk delete failed");
                if (!tmp.renameTo(out)) throw new IllegalStateException("rename failed");
                setApp("App update: APK скачан", source.assetName, source.downloadUrl, source.sha256,
                        out.length(), total, true, false, false, true);
                activity.runOnUiThread(() -> install(activity, out));
            } catch (Exception e) {
                tmp.delete();
                setApp("App update: ошибка загрузки " + e.getClass().getSimpleName(),
                        source.assetName, source.downloadUrl, source.sha256, 0, source.assetSize,
                        true, false, false, false);
                AppLog.line(app, "App update download: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    private void install(Activity activity, File file) {
        try {
            Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updateprovider/" + file.getName());
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            activity.startActivity(intent);
            setApp("App update: открыт установщик Android", latest.assetName, latest.downloadUrl,
                    latest.sha256, file.length(), latest.assetSize, true, false, false, true);
        } catch (Exception e) {
            setApp("App update: установщик не открылся", latest.assetName, latest.downloadUrl,
                    latest.sha256, file.length(), latest.assetSize, true, false, false, true);
            Toast.makeText(activity, "Не удалось открыть установщик: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private ReleaseInfo loadLatestRelease() throws Exception {
        try {
            ReleaseInfo manifest = loadManifestRelease();
            if (!TextUtils.isEmpty(manifest.downloadUrl)) return manifest;
        } catch (Exception e) {
            AppLog.line(app, "App update manifest fallback: " + e.getClass().getSimpleName());
        }
        return loadGithubRelease();
    }

    private ReleaseInfo loadManifestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_MANIFEST_URL).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github.raw");
        connection.setRequestProperty("User-Agent", "KiaUpdater");
        int code = connection.getResponseCode();
        String json = readText(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("manifest HTTP " + code);
        JSONObject appJson = new JSONObject(json).optJSONObject("app");
        if (appJson == null) {
            JSONObject contents = new JSONObject(json);
            String encoded = contents.optString("content", "");
            if (!TextUtils.isEmpty(encoded)) {
                String decoded = new String(Base64.decode(encoded, Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
                appJson = new JSONObject(decoded).optJSONObject("app");
            }
        }
        ReleaseInfo info = new ReleaseInfo();
        if (appJson == null) return info;
        info.versionName = appJson.optString("version_name", "");
        info.versionCode = appJson.optInt("version_code", 0);
        info.assetName = appJson.optString("asset_name", "");
        info.downloadUrl = appJson.optString("download_url", "");
        info.sha256 = appJson.optString("sha256", "");
        info.assetSize = appJson.optLong("size", 0);
        return info;
    }

    private ReleaseInfo loadGithubRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "KiaUpdater");
        int code = connection.getResponseCode();
        String json = readText(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("release HTTP " + code);
        JSONObject root = new JSONObject(json);
        JSONArray assets = root.optJSONArray("assets");
        ReleaseInfo info = new ReleaseInfo();
        info.versionName = root.optString("tag_name", "");
        if (assets == null) return info;
        int bestCode = -1;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String lower = name.toLowerCase(Locale.US);
            if (!lower.endsWith(".apk")) continue;
            if (lower.contains("yandex") || lower.contains("nav") || lower.contains("mod")) continue;
            if (!lower.startsWith("kia_")) continue;
            int assetCode = versionCodeFromName(name);
            if (assetCode < bestCode) continue;
            bestCode = assetCode;
            info.assetName = name;
            info.downloadUrl = asset.optString("browser_download_url", "");
            info.assetSize = asset.optLong("size", 0);
            info.versionCode = assetCode;
        }
        return info;
    }

    private File downloadedFile(String assetName) {
        String safe = TextUtils.isEmpty(assetName) ? "kia_update.apk" : assetName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safe.endsWith(".apk")) safe += ".apk";
        File root = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (root == null) root = app.getFilesDir();
        return new File(new File(root, UpdateApkProvider.DIR), safe);
    }

    private int currentVersionCode() {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) info.getLongVersionCode();
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int versionCodeFromName(String name) {
        String digits = name == null ? "" : name.replaceAll("[^0-9]", "");
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean canInstallPackages(Activity activity) {
        return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
    }

    private static void openInstallPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    private static boolean verifySha(File file, String expected) {
        if (file == null || !file.exists()) return false;
        if (TextUtils.isEmpty(expected)) return true;
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            String actual = hex(digest.digest());
            return expected.equalsIgnoreCase(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static String readText(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder out = new StringBuilder();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static String percent(long done, long total) {
        if (total <= 0) return done / 1024 + " KB";
        return Math.min(100, Math.round(done * 100f / total)) + "%";
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) {
            int v = b & 0xff;
            if (v < 16) out.append('0');
            out.append(Integer.toHexString(v));
        }
        return out.toString();
    }

    private void setApp(String status, String asset, String url, String sha256, long done,
                        long total, boolean available, boolean checking, boolean downloading,
                        boolean downloaded) {
        UpdateState current = StateStore.updates();
        StateStore.setUpdates(app, current.withApp(status, asset, url, sha256, done, total,
                available, checking, downloading, downloaded));
    }

    private static final class ReleaseInfo {
        String versionName = "";
        int versionCode;
        String assetName = "";
        String downloadUrl = "";
        String sha256 = "";
        long assetSize;
    }
}
