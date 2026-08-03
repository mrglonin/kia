package kia.app.core.model;

public final class UpdateState {
    public final String appStatus;
    public final String appAsset;
    public final String appUrl;
    public final String appSha256;
    public final long appDownloadedBytes;
    public final long appTotalBytes;
    public final boolean appAvailable;
    public final boolean appChecking;
    public final boolean appDownloading;
    public final boolean appDownloaded;
    public final String navigatorStatus;
    public final String navigatorVersion;
    public final String navigatorPackageName;
    public final String navigatorAsset;
    public final String navigatorFingerprint;
    public final long navigatorDownloadedBytes;
    public final long navigatorTotalBytes;
    public final boolean navigatorAvailable;
    public final boolean navigatorChecking;
    public final boolean navigatorDownloading;
    public final boolean navigatorDownloaded;
    public final boolean navigatorInstalling;
    public final String firmwareStatus;
    public final String firmwareAsset;
    public final String firmwareUrl;
    public final String firmwareSha256;
    public final long firmwareDownloadedBytes;
    public final long firmwareTotalBytes;
    public final boolean firmwareChecking;
    public final boolean firmwareDownloading;
    public final boolean firmwareDownloaded;
    public final boolean firmwareFlashing;
    public final boolean exclusiveUsbMode;

    public UpdateState(String appStatus, String appAsset, String appUrl, String appSha256,
                       long appDownloadedBytes, long appTotalBytes, boolean appAvailable,
                       boolean appChecking, boolean appDownloading, boolean appDownloaded,
                       String navigatorStatus, String navigatorVersion, String navigatorPackageName,
                       String navigatorAsset, String navigatorFingerprint,
                       long navigatorDownloadedBytes, long navigatorTotalBytes,
                       boolean navigatorAvailable, boolean navigatorChecking,
                       boolean navigatorDownloading, boolean navigatorDownloaded,
                       boolean navigatorInstalling,
                       String firmwareStatus, String firmwareAsset, String firmwareUrl,
                       String firmwareSha256, long firmwareDownloadedBytes, long firmwareTotalBytes,
                       boolean firmwareChecking, boolean firmwareDownloading,
                       boolean firmwareDownloaded, boolean firmwareFlashing,
                       boolean exclusiveUsbMode) {
        this.appStatus = safe(appStatus);
        this.appAsset = safe(appAsset);
        this.appUrl = safe(appUrl);
        this.appSha256 = safe(appSha256);
        this.appDownloadedBytes = appDownloadedBytes;
        this.appTotalBytes = appTotalBytes;
        this.appAvailable = appAvailable;
        this.appChecking = appChecking;
        this.appDownloading = appDownloading;
        this.appDownloaded = appDownloaded;
        this.navigatorStatus = safe(navigatorStatus);
        this.navigatorVersion = safe(navigatorVersion);
        this.navigatorPackageName = safe(navigatorPackageName);
        this.navigatorAsset = safe(navigatorAsset);
        this.navigatorFingerprint = safe(navigatorFingerprint);
        this.navigatorDownloadedBytes = navigatorDownloadedBytes;
        this.navigatorTotalBytes = navigatorTotalBytes;
        this.navigatorAvailable = navigatorAvailable;
        this.navigatorChecking = navigatorChecking;
        this.navigatorDownloading = navigatorDownloading;
        this.navigatorDownloaded = navigatorDownloaded;
        this.navigatorInstalling = navigatorInstalling;
        this.firmwareStatus = safe(firmwareStatus);
        this.firmwareAsset = safe(firmwareAsset);
        this.firmwareUrl = safe(firmwareUrl);
        this.firmwareSha256 = safe(firmwareSha256);
        this.firmwareDownloadedBytes = firmwareDownloadedBytes;
        this.firmwareTotalBytes = firmwareTotalBytes;
        this.firmwareChecking = firmwareChecking;
        this.firmwareDownloading = firmwareDownloading;
        this.firmwareDownloaded = firmwareDownloaded;
        this.firmwareFlashing = firmwareFlashing;
        this.exclusiveUsbMode = exclusiveUsbMode;
    }

    public static UpdateState empty() {
        return new UpdateState("App update: ожидание", "", "", "", 0, 0,
                false, false, false, false,
                "Navigator update: ожидание", "", "", "", "", 0, 0,
                false, false, false, false, false,
                "Firmware update: ожидание", "", "", "", 0, 0,
                false, false, false, false, false);
    }

    public UpdateState withApp(String status, String asset, String url, String sha256,
                               long done, long total, boolean available,
                               boolean checking, boolean downloading, boolean downloaded) {
        return new UpdateState(status, asset, url, sha256, done, total, available,
                checking, downloading, downloaded,
                navigatorStatus, navigatorVersion, navigatorPackageName, navigatorAsset,
                navigatorFingerprint,
                navigatorDownloadedBytes, navigatorTotalBytes, navigatorAvailable,
                navigatorChecking, navigatorDownloading, navigatorDownloaded,
                navigatorInstalling,
                firmwareStatus, firmwareAsset, firmwareUrl, firmwareSha256,
                firmwareDownloadedBytes, firmwareTotalBytes, firmwareChecking,
                firmwareDownloading, firmwareDownloaded, firmwareFlashing,
                exclusiveUsbMode);
    }

    public UpdateState withNavigator(String status, String version, String packageName,
                                     String asset, String fingerprint, long done, long total,
                                     boolean available, boolean checking,
                                     boolean downloading, boolean downloaded,
                                     boolean installing) {
        return new UpdateState(appStatus, appAsset, appUrl, appSha256,
                appDownloadedBytes, appTotalBytes, appAvailable, appChecking,
                appDownloading, appDownloaded,
                status, version, packageName, asset, fingerprint, done, total, available,
                checking, downloading, downloaded, installing,
                firmwareStatus, firmwareAsset, firmwareUrl, firmwareSha256,
                firmwareDownloadedBytes, firmwareTotalBytes, firmwareChecking,
                firmwareDownloading, firmwareDownloaded, firmwareFlashing,
                exclusiveUsbMode);
    }

    public UpdateState withFirmware(String status, String asset, String url, String sha256,
                                    long done, long total, boolean checking,
                                    boolean downloading, boolean downloaded, boolean flashing) {
        return new UpdateState(appStatus, appAsset, appUrl, appSha256, appDownloadedBytes,
                appTotalBytes, appAvailable, appChecking, appDownloading, appDownloaded,
                navigatorStatus, navigatorVersion, navigatorPackageName, navigatorAsset,
                navigatorFingerprint,
                navigatorDownloadedBytes, navigatorTotalBytes, navigatorAvailable,
                navigatorChecking, navigatorDownloading, navigatorDownloaded,
                navigatorInstalling,
                status, asset, url, sha256, done, total, checking, downloading,
                downloaded, flashing, exclusiveUsbMode);
    }

    public UpdateState withExclusiveUsbMode(boolean value) {
        return new UpdateState(appStatus, appAsset, appUrl, appSha256, appDownloadedBytes,
                appTotalBytes, appAvailable, appChecking, appDownloading, appDownloaded,
                navigatorStatus, navigatorVersion, navigatorPackageName, navigatorAsset,
                navigatorFingerprint,
                navigatorDownloadedBytes, navigatorTotalBytes, navigatorAvailable,
                navigatorChecking, navigatorDownloading, navigatorDownloaded,
                navigatorInstalling,
                firmwareStatus, firmwareAsset, firmwareUrl, firmwareSha256,
                firmwareDownloadedBytes, firmwareTotalBytes, firmwareChecking,
                firmwareDownloading, firmwareDownloaded, firmwareFlashing, value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
