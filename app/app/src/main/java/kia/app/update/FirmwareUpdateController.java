package kia.app.update;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.UpdateState;
import kia.app.protocol.adapter.AdapterCommand;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;

public final class FirmwareUpdateController {
    private static final String LATEST_MANIFEST_URL =
            "https://api.github.com/repos/mrglonin/kia/contents/updates/latest.json?ref=main";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/mrglonin/kia/releases/latest";
    private static final BundledFirmware[] BUNDLED_FIRMWARES = new BundledFirmware[0];
    private static final int MAX_FIRMWARE_SIZE = 114688;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static FirmwareUpdateController instance;

    private final Context app;
    private final Object ackLock = new Object();
    private volatile boolean busy;
    private volatile int lastAck = -1;
    private ReleaseInfo latest = new ReleaseInfo();

    private FirmwareUpdateController(Context context) {
        this.app = context.getApplicationContext();
    }

    public static synchronized FirmwareUpdateController get(Context context) {
        if (instance == null) instance = new FirmwareUpdateController(context);
        return instance;
    }

    public boolean busy() {
        return busy;
    }

    public void checkAsync() {
        if (busy) {
            AppLog.line(app, "Firmware update: busy");
            return;
        }
        busy = true;
        setFirmware("Firmware update: проверка GitHub", "", "", "", 0, 0,
                true, false, false, false);
        EXEC.execute(() -> {
            try {
                latest = loadLatestRelease();
                String status;
                if (TextUtils.isEmpty(latest.downloadUrl)) {
                    status = "Firmware update: BIN не найден";
                } else if (latest.assetSize > MAX_FIRMWARE_SIZE) {
                    status = "Firmware update: BIN больше 112 KB";
                } else {
                    status = "Firmware update: найден " + latest.assetName;
                }
                setFirmware(status, latest.assetName, latest.downloadUrl, latest.sha256,
                        0, latest.assetSize, false, false, false, false);
                AppLog.line(app, "Firmware update: latest=" + latest.assetName + " size=" + latest.assetSize);
            } catch (Exception e) {
                setFirmware("Firmware update: ошибка " + e.getClass().getSimpleName(), "", "", "",
                        0, 0, false, false, false, false);
                AppLog.line(app, "Firmware update check: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    public void downloadAndFlashLatest() {
        if (busy) {
            AppLog.line(app, "Firmware update: busy");
            return;
        }
        if (TextUtils.isEmpty(latest.downloadUrl)) {
            checkThenFlash();
            return;
        }
        downloadAndFlash(latest);
    }

    public void flashBundled(String id) {
        if (busy) {
            AppLog.line(app, "Firmware update: busy");
            return;
        }
        BundledFirmware bundled = bundled(id);
        if (bundled == null) {
            setFirmware("Firmware update: APK BIN " + id + " не найден", "", "", "",
                    0, 0, false, false, false, false);
            return;
        }
        busy = true;
        ReleaseInfo source = bundled.toReleaseInfo();
        setFirmware("Firmware update: чтение из APK " + bundled.label, source.assetName,
                source.downloadUrl, source.sha256, 0, source.assetSize,
                false, false, true, false);
        EXEC.execute(() -> {
            try {
                byte[] data = readBundled(bundled);
                if (data.length > MAX_FIRMWARE_SIZE) throw new IllegalStateException("firmware too large");
                if (bundled.assetSize > 0 && data.length != bundled.assetSize) {
                    throw new IllegalStateException("asset size mismatch");
                }
                if (!verifySha(data, bundled.sha256)) throw new IllegalStateException("sha256 mismatch");
                setFirmware("Firmware update: APK BIN готов " + bundled.label, source.assetName,
                        source.downloadUrl, source.sha256, data.length, source.assetSize,
                        false, false, true, false);
                flashBlocking(data, source);
            } catch (Exception e) {
                setFirmware("Firmware update: ошибка APK BIN " + e.getClass().getSimpleName(),
                        source.assetName, source.downloadUrl, source.sha256, 0, source.assetSize,
                        false, false, false, false);
                AppLog.line(app, "Firmware bundled update: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
                AdapterGateway.get(app).endExclusiveUpdate();
            }
        });
    }

    public void flashFile(Uri uri, String label) {
        if (busy) {
            AppLog.line(app, "Firmware update: busy");
            return;
        }
        if (uri == null) {
            setFirmware("Firmware update: файл не выбран", "", "", "",
                    0, 0, false, false, false, false);
            return;
        }
        busy = true;
        String assetName = TextUtils.isEmpty(label) ? "manual.bin" : label;
        ReleaseInfo source = new ReleaseInfo();
        source.assetName = assetName;
        source.downloadUrl = uri.toString();
        setFirmware("Firmware update: чтение файла " + assetName, assetName, source.downloadUrl,
                "", 0, 0, false, false, true, false);
        EXEC.execute(() -> {
            try {
                byte[] data = readUri(uri);
                source.assetSize = data.length;
                setFirmware("Firmware update: файл готов " + assetName, source.assetName,
                        source.downloadUrl, "", data.length, data.length,
                        false, false, true, false);
                flashBlocking(data, source);
            } catch (Exception e) {
                setFirmware("Firmware update: ошибка файла " + e.getClass().getSimpleName(),
                        source.assetName, source.downloadUrl, "", 0, source.assetSize,
                        false, false, false, false);
                AppLog.line(app, "Firmware file update: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
                AdapterGateway.get(app).endExclusiveUpdate();
            }
        });
    }

    public void handleAck(byte[] frame) {
        if (frame == null || frame.length < 6) return;
        synchronized (ackLock) {
            lastAck = frame[5] & 0xff;
            ackLock.notifyAll();
        }
    }

    private void checkThenFlash() {
        if (busy) return;
        busy = true;
        setFirmware("Firmware update: проверка GitHub", "", "", "", 0, 0,
                true, false, false, false);
        EXEC.execute(() -> {
            try {
                latest = loadLatestRelease();
                busy = false;
                if (TextUtils.isEmpty(latest.downloadUrl)) {
                    setFirmware("Firmware update: BIN не найден", "", "", "", 0, 0,
                            false, false, false, false);
                    return;
                }
                downloadAndFlash(latest);
            } catch (Exception e) {
                busy = false;
                setFirmware("Firmware update: ошибка " + e.getClass().getSimpleName(), "", "", "",
                        0, 0, false, false, false, false);
                AppLog.line(app, "Firmware update check: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        });
    }

    private void downloadAndFlash(ReleaseInfo source) {
        if (source.assetSize > MAX_FIRMWARE_SIZE) {
            setFirmware("Firmware update: BIN больше 112 KB", source.assetName, source.downloadUrl,
                    source.sha256, 0, source.assetSize, false, false, false, false);
            return;
        }
        busy = true;
        setFirmware("Firmware update: загрузка " + source.assetName, source.assetName,
                source.downloadUrl, source.sha256, 0, source.assetSize, false, true, false, false);
        EXEC.execute(() -> {
            try {
                byte[] data = download(source);
                if (!verifySha(data, source.sha256)) throw new IllegalStateException("sha256 mismatch");
                setFirmware("Firmware update: BIN скачан", source.assetName, source.downloadUrl,
                        source.sha256, data.length, source.assetSize, false, false, true, false);
                flashBlocking(data, source);
            } catch (Exception e) {
                setFirmware("Firmware update: ошибка " + e.getClass().getSimpleName(), source.assetName,
                        source.downloadUrl, source.sha256, 0, source.assetSize, false, false, false, false);
                AppLog.line(app, "Firmware update: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
                AdapterGateway.get(app).endExclusiveUpdate();
            }
        });
    }

    private byte[] download(ReleaseInfo source) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(source.downloadUrl).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "KiaFirmwareUpdater");
        long total = connection.getContentLengthLong();
        if (total <= 0) total = source.assetSize;
        ByteArrayOutputStream out = new ByteArrayOutputStream(total > 0 && total < Integer.MAX_VALUE ? (int) total : 8192);
        try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
            byte[] buffer = new byte[8192];
            long done = 0;
            long lastReport = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                done += read;
                if (done > MAX_FIRMWARE_SIZE) throw new IllegalStateException("firmware too large");
                long now = System.currentTimeMillis();
                if (now - lastReport > 500L) {
                    lastReport = now;
                    setFirmware("Firmware update: загрузка " + percent(done, total), source.assetName,
                            source.downloadUrl, source.sha256, done, total, false, true, false, false);
                }
            }
        }
        return out.toByteArray();
    }

    private byte[] readBundled(BundledFirmware source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(8192, source.assetSize));
        try (InputStream in = new BufferedInputStream(app.getAssets().open(source.assetPath))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_FIRMWARE_SIZE) throw new IllegalStateException("firmware too large");
            }
        }
        return out.toByteArray();
    }

    private byte[] readUri(Uri uri) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        InputStream raw = app.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IllegalStateException("file open failed");
        try (InputStream in = new BufferedInputStream(raw)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_FIRMWARE_SIZE) throw new IllegalStateException("firmware too large");
            }
        }
        return out.toByteArray();
    }

    private void flashBlocking(byte[] firmware, ReleaseInfo source) throws Exception {
        if (firmware == null || firmware.length == 0) throw new IllegalArgumentException("empty firmware");
        if (firmware.length > MAX_FIRMWARE_SIZE) throw new IllegalArgumentException("firmware too large");
        AdapterGateway gateway = AdapterGateway.get(app);
        gateway.beginExclusiveUpdate();
        setFirmware("Firmware update: вход в bootloader", source.assetName, source.downloadUrl,
                source.sha256, 0, firmware.length, false, false, true, true);
        boolean started = false;
        for (int attempt = 1; attempt <= 10 && !started; attempt++) {
            resetAck();
            gateway.send(AdapterCommand.firmware("firmware start", AdapterProtocol.firmwareStart(), false));
            started = waitForAck(1, 1200);
            if (!started) AppLog.line(app, "Firmware update: start retry " + attempt);
        }
        if (!started) throw new IllegalStateException("adapter did not enter firmware mode");

        int blocks = (firmware.length + 15) / 16;
        for (int i = 0; i < blocks; i++) {
            int offset = i * 16;
            byte[] block = Arrays.copyOfRange(firmware, offset, Math.min(offset + 16, firmware.length));
            boolean accepted = false;
            for (int attempt = 1; attempt <= 3 && !accepted; attempt++) {
                resetAck();
                gateway.send(AdapterCommand.firmware("firmware block", AdapterProtocol.firmwareBlock(offset, block), true));
                accepted = waitForAck(2, 1200);
                if (lastAck == 0) throw new IllegalStateException("adapter cancelled at block " + i);
            }
            if (!accepted) throw new IllegalStateException("no ack for block " + i);
            if (i == 0 || i == blocks - 1 || i % 16 == 0) {
                int percent = Math.max(0, Math.min(100, Math.round((i + 1) * 100f / blocks)));
                setFirmware("Firmware update: блоки " + (i + 1) + "/" + blocks, source.assetName,
                        source.downloadUrl, source.sha256, i + 1, blocks, false, false, true, true);
                AppLog.line(app, "Firmware update: " + percent + "%");
            }
        }
        resetAck();
        gateway.send(AdapterCommand.firmware("firmware finish", AdapterProtocol.firmwareFinish(), false));
        setFirmware("Firmware update: отправлено, адаптер перезапускается", source.assetName,
                source.downloadUrl, source.sha256, firmware.length, firmware.length,
                false, false, true, false);
    }

    private void resetAck() {
        synchronized (ackLock) {
            lastAck = -1;
        }
    }

    private boolean waitForAck(int expected, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (ackLock) {
            while (System.currentTimeMillis() < end) {
                if (lastAck == expected) return true;
                if (lastAck == 0) return false;
                long remaining = end - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    ackLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return lastAck == expected;
        }
    }

    private ReleaseInfo loadLatestRelease() throws Exception {
        try {
            ReleaseInfo manifest = loadManifestRelease();
            if (!TextUtils.isEmpty(manifest.downloadUrl)) return manifest;
        } catch (Exception e) {
            AppLog.line(app, "Firmware manifest fallback: " + e.getClass().getSimpleName());
        }
        return loadGithubRelease();
    }

    private ReleaseInfo loadManifestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_MANIFEST_URL).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github.raw");
        connection.setRequestProperty("User-Agent", "KiaFirmwareUpdater");
        int code = connection.getResponseCode();
        String json = readText(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("manifest HTTP " + code);
        JSONObject root = new JSONObject(json);
        JSONObject firmware = root.optJSONObject("firmware");
        if (firmware == null) {
            String encoded = root.optString("content", "");
            if (!TextUtils.isEmpty(encoded)) {
                String decoded = new String(Base64.decode(encoded, Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
                firmware = new JSONObject(decoded).optJSONObject("firmware");
            }
        }
        ReleaseInfo info = new ReleaseInfo();
        if (firmware == null) return info;
        info.version = firmware.optString("version", "");
        info.assetName = firmware.optString("asset_name", "");
        info.downloadUrl = firmware.optString("download_url", "");
        info.sha256 = firmware.optString("sha256", "");
        info.assetSize = firmware.optLong("size", 0);
        return info;
    }

    private ReleaseInfo loadGithubRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "KiaFirmwareUpdater");
        int code = connection.getResponseCode();
        String json = readText(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("release HTTP " + code);
        JSONObject root = new JSONObject(json);
        JSONArray assets = root.optJSONArray("assets");
        ReleaseInfo best = new ReleaseInfo();
        best.version = root.optString("tag_name", "");
        if (assets == null) return best;
        int bestScore = -1;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String lower = name.toLowerCase(Locale.US);
            if (!lower.endsWith(".bin")) continue;
            int score = firmwareScore(lower);
            if (score < bestScore) continue;
            bestScore = score;
            best.assetName = name;
            best.downloadUrl = asset.optString("browser_download_url", "");
            best.assetSize = asset.optLong("size", 0);
        }
        return best;
    }

    private static int firmwareScore(String lower) {
        int score = 0;
        if (lower.contains("v24")) score += 100;
        if (lower.contains("v21")) score += 80;
        if (lower.contains("2can35")) score += 40;
        if (lower.contains("kia")) score += 20;
        if (lower.contains("usb")) score += 10;
        if (lower.contains("stlink")) score -= 30;
        return score;
    }

    private static BundledFirmware bundled(String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (BundledFirmware item : BUNDLED_FIRMWARES) {
            if (id.equals(item.id)) return item;
        }
        return null;
    }

    private void setFirmware(String status, String asset, String url, String sha256,
                             long done, long total, boolean checking, boolean downloading,
                             boolean downloaded, boolean flashing) {
        UpdateState current = StateStore.updates();
        StateStore.setUpdates(app, current.withFirmware(status, asset, url, sha256, done,
                total, checking, downloading, downloaded, flashing));
    }

    private static boolean verifySha(byte[] data, String expected) throws Exception {
        if (TextUtils.isEmpty(expected)) return true;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = hex(digest.digest(data == null ? new byte[0] : data));
        return expected.equalsIgnoreCase(actual);
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

    private static final class ReleaseInfo {
        String version = "";
        String assetName = "";
        String downloadUrl = "";
        String sha256 = "";
        long assetSize;
    }

    private static final class BundledFirmware {
        final String id;
        final String label;
        final String assetPath;
        final String assetName;
        final String sha256;
        final long assetSize;

        BundledFirmware(String id, String label, String assetPath, String assetName,
                        String sha256, long assetSize) {
            this.id = id;
            this.label = label;
            this.assetPath = assetPath;
            this.assetName = assetName;
            this.sha256 = sha256;
            this.assetSize = assetSize;
        }

        ReleaseInfo toReleaseInfo() {
            ReleaseInfo info = new ReleaseInfo();
            info.version = label;
            info.assetName = assetName;
            info.downloadUrl = "asset://" + assetPath;
            info.sha256 = sha256;
            info.assetSize = assetSize;
            return info;
        }
    }
}
