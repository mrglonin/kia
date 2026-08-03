package kia.app.update;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.settings.AppSettings;

public final class NavigatorUpdateController {
    private static final String LATEST_MANIFEST_URL =
            "https://api.github.com/repos/mrglonin/kia/contents/updates/latest.json?ref=main";
    private static final String RAW_ROOT =
            "https://raw.githubusercontent.com/mrglonin/kia/main/";
    private static final String DEFAULT_PACKAGE = "ru.yandex.yandexnavi";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean CHECK_BUSY = new AtomicBoolean();
    private static final int INSTALL_REQUEST = 29032;

    private final Context app;
    private volatile boolean busy;
    private static volatile NavigatorInfo latest = new NavigatorInfo();
    private static volatile WeakReference<Activity> installAfterCheckActivity =
            new WeakReference<>(null);

    public NavigatorUpdateController(Context context) {
        this.app = context.getApplicationContext();
    }

    public void checkAsync() {
        checkAsync(null);
    }

    public void checkAsync(Activity activity) {
        if (busy || !CHECK_BUSY.compareAndSet(false, true)) {
            if (activity != null) Toast.makeText(activity, "Проверка Navigator уже идёт", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        setNavigator("Navigator update: проверка GitHub", "", DEFAULT_PACKAGE, "", 0, 0,
                false, true, false, false, false);
        EXEC.execute(() -> {
            try {
                NavigatorInfo info = loadManifestNavigator();
                latest = info;
                InstalledApkInfo installed = installedNavigator(info.packageName);
                boolean manifestReady = info.installable();
                boolean canInstall = manifestReady && shouldInstall(info, installed);
                boolean downloaded = canInstall && allDownloaded(info);
                String status;
                if (!manifestReady) status = "Navigator update: APK не найден";
                else if (!installed.installed) status = "Navigator update: можно установить " + info.version;
                else if (sameVersionDifferentArchive(info, installed)) {
                    status = "Navigator update: доступна пересборка " + info.version;
                } else if (canInstall) status = "Navigator update: доступен " + info.version;
                else if (info.versionCode == installed.versionCode) status = "Navigator update: актуально " + info.version;
                else status = "Navigator update: установлена новее";
                setNavigator(status, info.version, info.packageName, info.displayAsset(), 0,
                        info.totalSize(), canInstall, false, false, downloaded, false);
                if (manifestReady && info.versionCode > 0) {
                    AppSettings.setLastNavigatorUpdateCheckAt(app, System.currentTimeMillis());
                }
                UpdateNotificationController.refresh(app);
                AppLog.line(app, "Navigator update: installedCode=" + installed.versionCode
                        + " latestCode=" + info.versionCode
                        + " installedSize=" + installed.sourceSize
                        + " latestSize=" + info.totalSize()
                        + " installedSha=" + installed.shortSha()
                        + " files=" + info.files.size());
                Activity pendingActivity = installAfterCheckActivity.get();
                installAfterCheckActivity = new WeakReference<>(null);
                if (pendingActivity != null && canInstall
                        && !pendingActivity.isFinishing() && !pendingActivity.isDestroyed()) {
                        busy = false;
                        pendingActivity.runOnUiThread(
                                () -> downloadAndInstall(pendingActivity));
                        return;
                }
            } catch (Exception e) {
                installAfterCheckActivity = new WeakReference<>(null);
                setNavigator("Navigator update: ошибка " + e.getClass().getSimpleName(), "",
                        DEFAULT_PACKAGE, "", 0, 0, false, false, false, false, false);
                UpdateNotificationController.refresh(app);
                AppLog.line(app, "Navigator update check: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
                CHECK_BUSY.set(false);
            }
        });
    }

    public void downloadAndInstall(Activity activity) {
        if (activity == null) return;
        NavigatorInfo source = latest;
        if (!source.installable()) {
            installAfterCheckActivity = new WeakReference<>(activity);
            checkAsync(activity);
            Toast.makeText(activity, "Проверяю Yandex Navigator и начну установку", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) {
            Toast.makeText(activity, "Проверка Navigator уже идёт", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        setNavigator("Navigator update: проверка APK", source.version, source.packageName,
                source.displayAsset(), 0, source.totalSize(), true,
                true, false, false, false);
        EXEC.execute(() -> {
            try {
                InstalledApkInfo installed = installedNavigator(source.packageName);
                boolean installNeeded = shouldInstall(source, installed);
                boolean downloaded = installNeeded && allDownloaded(source);
                activity.runOnUiThread(() -> {
                    busy = false;
                    continueDownloadAndInstall(activity, source, installNeeded, downloaded);
                });
            } catch (Exception e) {
                busy = false;
                setNavigator("Navigator update: ошибка проверки "
                                + e.getClass().getSimpleName(),
                        source.version, source.packageName, source.displayAsset(),
                        0, source.totalSize(), true,
                        false, false, false, false);
                AppLog.line(app, "Navigator update preflight: "
                        + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        });
    }

    private void continueDownloadAndInstall(Activity activity, NavigatorInfo source,
                                            boolean installNeeded, boolean downloaded) {
        if (!installNeeded) {
            setNavigator("Navigator update: актуально " + source.version, source.version,
                    source.packageName, source.displayAsset(), 0, source.totalSize(),
                    false, false, false, false, false);
            Toast.makeText(activity, "Yandex Navigator уже актуален", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!canInstallPackages(activity)) {
            setNavigator("Navigator update: нужно разрешение установки APK", source.version,
                    source.packageName, source.displayAsset(), 0, source.totalSize(), true,
                    false, false, false, false);
            openInstallPermission(activity);
            return;
        }
        if (downloaded) {
            install(source);
            return;
        }
        download(activity, source);
    }

    private void download(Activity activity, NavigatorInfo source) {
        if (busy) {
            Toast.makeText(activity, "Загрузка Navigator уже идёт", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        setNavigator("Navigator update: загрузка", source.version, source.packageName,
                source.displayAsset(), 0, source.totalSize(), true, false, true, false, false);
        EXEC.execute(() -> {
            long done = 0;
            try {
                File root = navigatorDir();
                if (!root.exists()) root.mkdirs();
                for (NavFile file : source.files) {
                    File out = new File(root, file.name);
                    if (file.hasParts()) {
                        File partsDir = new File(root, file.name + ".parts");
                        if (!partsDir.exists()) partsDir.mkdirs();
                        for (NavPart part : file.parts) {
                            File partFile = new File(partsDir, part.name);
                            if (!verifySha(partFile, part.sha256)) {
                                downloadOne(part.url, partFile, part.sha256);
                            }
                            done += Math.max(0, partFile.length());
                            setNavigator("Navigator update: загрузка " + percent(done, source.totalSize()),
                                    source.version, source.packageName, part.name, done, source.totalSize(),
                                    true, false, true, false, false);
                        }
                        if (!verifySha(out, file.sha256)) {
                            concatenate(file, partsDir, out);
                        }
                    } else {
                        if (!verifySha(out, file.sha256)) {
                            downloadOne(file.url, out, file.sha256);
                        }
                        done += Math.max(0, out.length());
                        setNavigator("Navigator update: загрузка " + percent(done, source.totalSize()),
                                source.version, source.packageName, file.name, done, source.totalSize(),
                                true, false, true, false, false);
                    }
                    if (!verifySha(out, file.sha256)) throw new IllegalStateException("sha256 mismatch " + file.name);
                }
                setNavigator("Navigator update: APK скачан", source.version, source.packageName,
                        source.displayAsset(), source.totalSize(), source.totalSize(), true,
                        false, false, true, false);
                install(source);
            } catch (Exception e) {
                setNavigator("Navigator update: ошибка загрузки " + e.getClass().getSimpleName(),
                        source.version, source.packageName, source.displayAsset(), done, source.totalSize(),
                        true, false, false, false, false);
                AppLog.line(app, "Navigator update download: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    private void install(NavigatorInfo source) {
        EXEC.execute(() -> {
            try {
                File root = navigatorDir();
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(source.packageName);
                params.setSize(source.totalSize());
                int sessionId = installer.createSession(params);
                PackageInstaller.Session session = installer.openSession(sessionId);
                try {
                    for (NavFile file : source.files) {
                        File apk = new File(root, file.name);
                        writeSessionFile(session, apk);
                    }
                    Intent intent = new Intent(app, NavigatorInstallResultReceiver.class);
                    intent.setPackage(app.getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pending = PendingIntent.getBroadcast(app, INSTALL_REQUEST, intent, flags);
                    session.commit(pending.getIntentSender());
                    setNavigator("Navigator update: открыт установщик Android", source.version,
                            source.packageName, source.displayAsset(), source.totalSize(), source.totalSize(),
                            true, false, false, true, true);
                } finally {
                    session.close();
                }
            } catch (Exception e) {
                setNavigator("Navigator update: установщик не открылся", source.version,
                        source.packageName, source.displayAsset(), source.totalSize(), source.totalSize(),
                        true, false, false, true, false);
                AppLog.line(app, "Navigator update install: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        });
    }

    private void writeSessionFile(PackageInstaller.Session session, File file) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             OutputStream out = session.openWrite(file.getName(), 0, file.length())) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            session.fsync(out);
        }
    }

    private NavigatorInfo loadManifestNavigator() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_MANIFEST_URL).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github.raw");
        connection.setRequestProperty("User-Agent", "KiaUpdater");
        int code = connection.getResponseCode();
        String json = readText(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("manifest HTTP " + code);
        JSONObject navJson = new JSONObject(json).optJSONObject("navigator");
        if (navJson == null) {
            JSONObject contents = new JSONObject(json);
            String encoded = contents.optString("content", "");
            if (!TextUtils.isEmpty(encoded)) {
                String decoded = new String(Base64.decode(encoded, Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
                navJson = new JSONObject(decoded).optJSONObject("navigator");
            }
        }
        NavigatorInfo info = new NavigatorInfo();
        if (navJson == null) return info;
        info.name = navJson.optString("name", "Yandex Navigator Kia hook");
        info.version = navJson.optString("version", "");
        info.versionCode = navJson.optInt("version_code", 0);
        info.packageName = navJson.optString("package", DEFAULT_PACKAGE);
        info.apkDir = navJson.optString("apk_dir", "");
        JSONArray files = navJson.optJSONArray("files");
        if (files == null) return info;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) {
                info.filesMetadataValid = false;
                continue;
            }
            NavFile file = new NavFile();
            file.name = item.optString("name", "");
            file.size = item.optLong("size", 0);
            file.sha256 = item.optString("sha256", "");
            file.url = downloadUrl(item.optString("download_url", ""), info.apkDir, file.name);
            JSONArray parts = item.optJSONArray("parts");
            if (parts != null) {
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject p = parts.optJSONObject(j);
                    if (p == null) {
                        file.partsMetadataValid = false;
                        continue;
                    }
                    NavPart part = new NavPart();
                    part.name = p.optString("name", "");
                    part.size = p.optLong("size", 0);
                    part.sha256 = p.optString("sha256", "");
                    part.url = downloadUrl(p.optString("download_url", ""), info.apkDir, part.name);
                    file.parts.add(part);
                }
            }
            info.files.add(file);
        }
        return info;
    }

    private static String downloadUrl(String explicit, String dir, String name) {
        if (!TextUtils.isEmpty(explicit)) return explicit;
        String path = (TextUtils.isEmpty(dir) ? "" : dir + "/") + name;
        return RAW_ROOT + path.replace(" ", "%20");
    }

    private boolean allDownloaded(NavigatorInfo info) {
        if (info == null || info.files.isEmpty()) return false;
        File root = navigatorDir();
        for (NavFile file : info.files) {
            if (!verifySha(new File(root, file.name), file.sha256)) return false;
        }
        return true;
    }

    private void downloadOne(String url, File out, String sha256) throws Exception {
        if (TextUtils.isEmpty(url)) throw new IllegalStateException("empty url");
        if (out.getParentFile() != null && !out.getParentFile().exists()) out.getParentFile().mkdirs();
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "KiaUpdater");
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             FileOutputStream file = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) file.write(buffer, 0, read);
        }
        if (!verifySha(tmp, sha256)) throw new IllegalStateException("sha256 mismatch " + out.getName());
        if (out.exists() && !out.delete()) throw new IllegalStateException("delete failed " + out.getName());
        if (!tmp.renameTo(out)) throw new IllegalStateException("rename failed " + out.getName());
    }

    private void concatenate(NavFile file, File partsDir, File out) throws Exception {
        File tmp = new File(out.getParentFile(), out.getName() + ".join");
        try (FileOutputStream joined = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[65536];
            for (NavPart part : file.parts) {
                File partFile = new File(partsDir, part.name);
                try (InputStream in = new BufferedInputStream(new FileInputStream(partFile))) {
                    int read;
                    while ((read = in.read(buffer)) != -1) joined.write(buffer, 0, read);
                }
            }
        }
        if (!verifySha(tmp, file.sha256)) throw new IllegalStateException("join sha256 mismatch " + file.name);
        if (out.exists() && !out.delete()) throw new IllegalStateException("delete failed " + out.getName());
        if (!tmp.renameTo(out)) throw new IllegalStateException("join rename failed " + out.getName());
    }

    private File navigatorDir() {
        File root = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (root == null) root = app.getFilesDir();
        return new File(new File(root, UpdateApkProvider.DIR), "navigator");
    }

    private InstalledApkInfo installedNavigator(String packageName) {
        return InstalledApkInfo.read(app, TextUtils.isEmpty(packageName) ? DEFAULT_PACKAGE : packageName);
    }

    private static boolean shouldInstall(NavigatorInfo source, InstalledApkInfo installed) {
        if (source == null || source.files.isEmpty()) return false;
        if (installed == null || !installed.installed) return true;
        if (source.versionCode > installed.versionCode) return true;
        if (source.versionCode < installed.versionCode) return false;
        return sameVersionDifferentArchive(source, installed);
    }

    private static boolean sameVersionDifferentArchive(NavigatorInfo source, InstalledApkInfo installed) {
        NavFile file = source == null ? null : source.singleFile();
        return file != null
                && installed != null
                && installed.installed
                && source.versionCode > 0
                && source.versionCode == installed.versionCode
                && installed.hasComparableArchive(file.sha256, file.size)
                && !installed.matchesArchive(file.sha256, file.size);
    }

    static boolean validFileMetadata(String name, String url, String sha256, long size) {
        return name != null && !name.trim().isEmpty()
                && name.toLowerCase(Locale.US).endsWith(".apk")
                && validArchiveMetadata(url, sha256, size);
    }

    static boolean validPartMetadata(String name, String url, String sha256, long size) {
        return name != null && !name.trim().isEmpty()
                && validArchiveMetadata(url, sha256, size);
    }

    private static boolean validArchiveMetadata(String url, String sha256, long size) {
        return url != null && !url.trim().isEmpty()
                && sha256 != null && sha256.matches("(?i)[0-9a-f]{64}")
                && size > 0L;
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
        if (file == null || !file.exists() || TextUtils.isEmpty(expected)) return false;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return expected.equalsIgnoreCase(hex(digest.digest()));
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

    private void setNavigator(String status, String version, String packageName, String asset,
                              long done, long total, boolean available, boolean checking,
                              boolean downloading, boolean downloaded, boolean installing) {
        StateStore.updateUpdates(app, current -> current.withNavigator(
                status, version, packageName, asset, latest.manifestFingerprint(),
                done, total, available, checking, downloading, downloaded, installing));
    }

    static final class NavigatorInfo {
        String name = "Yandex Navigator Kia hook";
        String version = "";
        int versionCode;
        String packageName = DEFAULT_PACKAGE;
        String apkDir = "";
        boolean filesMetadataValid = true;
        final List<NavFile> files = new ArrayList<>();

        long totalSize() {
            long total = 0;
            for (NavFile file : files) total += Math.max(0, file.size);
            return total;
        }

        String displayAsset() {
            if (files.isEmpty()) return "";
            return files.size() + " APK / " + version;
        }

        NavFile singleFile() {
            return files.size() == 1 ? files.get(0) : null;
        }

        boolean installable() {
            if (versionCode <= 0 || files.isEmpty() || !filesMetadataValid) return false;
            for (NavFile file : files) {
                if (!validFileMetadata(file.name, file.url, file.sha256, file.size)) {
                    return false;
                }
                if (!file.partsMetadataValid) return false;
                for (NavPart part : file.parts) {
                    if (!validPartMetadata(part.name, part.url, part.sha256, part.size)) {
                        return false;
                    }
                }
            }
            return true;
        }

        String manifestFingerprint() {
            StringBuilder out = new StringBuilder();
            for (NavFile file : files) {
                out.append(file.name).append(':').append(file.sha256)
                        .append(':').append(file.size).append(';');
                for (NavPart part : file.parts) {
                    out.append(part.name).append(':').append(part.sha256)
                            .append(':').append(part.size).append(';');
                }
            }
            return out.toString();
        }
    }

    static final class NavFile {
        String name = "";
        String url = "";
        String sha256 = "";
        long size;
        boolean partsMetadataValid = true;
        final List<NavPart> parts = new ArrayList<>();

        boolean hasParts() {
            return !parts.isEmpty();
        }
    }

    static final class NavPart {
        String name = "";
        String url = "";
        String sha256 = "";
        long size;
    }
}
