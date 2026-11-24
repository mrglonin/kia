package kia.app.core.model

class UpdateState(
    appStatus: String?,
    appAsset: String?,
    appUrl: String?,
    appSha256: String?,
    appDownloadedBytes: Long,
    appTotalBytes: Long,
    appAvailable: Boolean,
    appChecking: Boolean,
    appDownloading: Boolean,
    appDownloaded: Boolean,
    navigatorStatus: String?,
    navigatorVersion: String?,
    navigatorPackageName: String?,
    navigatorAsset: String?,
    navigatorFingerprint: String?,
    navigatorDownloadedBytes: Long,
    navigatorTotalBytes: Long,
    navigatorAvailable: Boolean,
    navigatorChecking: Boolean,
    navigatorDownloading: Boolean,
    navigatorDownloaded: Boolean,
    navigatorInstalling: Boolean,
    firmwareStatus: String?,
    firmwareAsset: String?,
    firmwareUrl: String?,
    firmwareSha256: String?,
    firmwareDownloadedBytes: Long,
    firmwareTotalBytes: Long,
    firmwareChecking: Boolean,
    firmwareDownloading: Boolean,
    firmwareDownloaded: Boolean,
    firmwareFlashing: Boolean,
    exclusiveUsbMode: Boolean,
) {
    @JvmField val appStatus: String
    @JvmField val appAsset: String
    @JvmField val appUrl: String
    @JvmField val appSha256: String
    @JvmField val appDownloadedBytes: Long
    @JvmField val appTotalBytes: Long
    @JvmField val appAvailable: Boolean
    @JvmField val appChecking: Boolean
    @JvmField val appDownloading: Boolean
    @JvmField val appDownloaded: Boolean
    @JvmField val navigatorStatus: String
    @JvmField val navigatorVersion: String
    @JvmField val navigatorPackageName: String
    @JvmField val navigatorAsset: String
    @JvmField val navigatorFingerprint: String
    @JvmField val navigatorDownloadedBytes: Long
    @JvmField val navigatorTotalBytes: Long
    @JvmField val navigatorAvailable: Boolean
    @JvmField val navigatorChecking: Boolean
    @JvmField val navigatorDownloading: Boolean
    @JvmField val navigatorDownloaded: Boolean
    @JvmField val navigatorInstalling: Boolean
    @JvmField val firmwareStatus: String
    @JvmField val firmwareAsset: String
    @JvmField val firmwareUrl: String
    @JvmField val firmwareSha256: String
    @JvmField val firmwareDownloadedBytes: Long
    @JvmField val firmwareTotalBytes: Long
    @JvmField val firmwareChecking: Boolean
    @JvmField val firmwareDownloading: Boolean
    @JvmField val firmwareDownloaded: Boolean
    @JvmField val firmwareFlashing: Boolean
    @JvmField val exclusiveUsbMode: Boolean

    init {
        this.appStatus = UpdateState.safe(appStatus)
        this.appAsset = UpdateState.safe(appAsset)
        this.appUrl = UpdateState.safe(appUrl)
        this.appSha256 = UpdateState.safe(appSha256)
        this.appDownloadedBytes = appDownloadedBytes
        this.appTotalBytes = appTotalBytes
        this.appAvailable = appAvailable
        this.appChecking = appChecking
        this.appDownloading = appDownloading
        this.appDownloaded = appDownloaded
        this.navigatorStatus = UpdateState.safe(navigatorStatus)
        this.navigatorVersion = UpdateState.safe(navigatorVersion)
        this.navigatorPackageName = UpdateState.safe(navigatorPackageName)
        this.navigatorAsset = UpdateState.safe(navigatorAsset)
        this.navigatorFingerprint = UpdateState.safe(navigatorFingerprint)
        this.navigatorDownloadedBytes = navigatorDownloadedBytes
        this.navigatorTotalBytes = navigatorTotalBytes
        this.navigatorAvailable = navigatorAvailable
        this.navigatorChecking = navigatorChecking
        this.navigatorDownloading = navigatorDownloading
        this.navigatorDownloaded = navigatorDownloaded
        this.navigatorInstalling = navigatorInstalling
        this.firmwareStatus = UpdateState.safe(firmwareStatus)
        this.firmwareAsset = UpdateState.safe(firmwareAsset)
        this.firmwareUrl = UpdateState.safe(firmwareUrl)
        this.firmwareSha256 = UpdateState.safe(firmwareSha256)
        this.firmwareDownloadedBytes = firmwareDownloadedBytes
        this.firmwareTotalBytes = firmwareTotalBytes
        this.firmwareChecking = firmwareChecking
        this.firmwareDownloading = firmwareDownloading
        this.firmwareDownloaded = firmwareDownloaded
        this.firmwareFlashing = firmwareFlashing
        this.exclusiveUsbMode = exclusiveUsbMode
    }

    fun withApp(
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
    ): UpdateState {
        return UpdateState(
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
            navigatorStatus,
            navigatorVersion,
            navigatorPackageName,
            navigatorAsset,
            navigatorFingerprint,
            navigatorDownloadedBytes,
            navigatorTotalBytes,
            navigatorAvailable,
            navigatorChecking,
            navigatorDownloading,
            navigatorDownloaded,
            navigatorInstalling,
            firmwareStatus,
            firmwareAsset,
            firmwareUrl,
            firmwareSha256,
            firmwareDownloadedBytes,
            firmwareTotalBytes,
            firmwareChecking,
            firmwareDownloading,
            firmwareDownloaded,
            firmwareFlashing,
            exclusiveUsbMode,
        )
    }

    fun withNavigator(
        status: String?,
        version: String?,
        packageName: String?,
        asset: String?,
        fingerprint: String?,
        done: Long,
        total: Long,
        available: Boolean,
        checking: Boolean,
        downloading: Boolean,
        downloaded: Boolean,
        installing: Boolean,
    ): UpdateState {
        return UpdateState(
            appStatus,
            appAsset,
            appUrl,
            appSha256,
            appDownloadedBytes,
            appTotalBytes,
            appAvailable,
            appChecking,
            appDownloading,
            appDownloaded,
            status,
            version,
            packageName,
            asset,
            fingerprint,
            done,
            total,
            available,
            checking,
            downloading,
            downloaded,
            installing,
            firmwareStatus,
            firmwareAsset,
            firmwareUrl,
            firmwareSha256,
            firmwareDownloadedBytes,
            firmwareTotalBytes,
            firmwareChecking,
            firmwareDownloading,
            firmwareDownloaded,
            firmwareFlashing,
            exclusiveUsbMode,
        )
    }

    fun withFirmware(
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
    ): UpdateState {
        return UpdateState(
            appStatus,
            appAsset,
            appUrl,
            appSha256,
            appDownloadedBytes,
            appTotalBytes,
            appAvailable,
            appChecking,
            appDownloading,
            appDownloaded,
            navigatorStatus,
            navigatorVersion,
            navigatorPackageName,
            navigatorAsset,
            navigatorFingerprint,
            navigatorDownloadedBytes,
            navigatorTotalBytes,
            navigatorAvailable,
            navigatorChecking,
            navigatorDownloading,
            navigatorDownloaded,
            navigatorInstalling,
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
            exclusiveUsbMode,
        )
    }

    fun withExclusiveUsbMode(value: Boolean): UpdateState {
        return UpdateState(
            appStatus,
            appAsset,
            appUrl,
            appSha256,
            appDownloadedBytes,
            appTotalBytes,
            appAvailable,
            appChecking,
            appDownloading,
            appDownloaded,
            navigatorStatus,
            navigatorVersion,
            navigatorPackageName,
            navigatorAsset,
            navigatorFingerprint,
            navigatorDownloadedBytes,
            navigatorTotalBytes,
            navigatorAvailable,
            navigatorChecking,
            navigatorDownloading,
            navigatorDownloaded,
            navigatorInstalling,
            firmwareStatus,
            firmwareAsset,
            firmwareUrl,
            firmwareSha256,
            firmwareDownloadedBytes,
            firmwareTotalBytes,
            firmwareChecking,
            firmwareDownloading,
            firmwareDownloaded,
            firmwareFlashing,
            value,
        )
    }

    companion object {
        @JvmStatic
        fun empty(): UpdateState {
            return UpdateState(
                "App update: ожидание",
                "",
                "",
                "",
                0,
                0,
                false,
                false,
                false,
                false,
                "Navigator update: ожидание",
                "",
                "",
                "",
                "",
                0,
                0,
                false,
                false,
                false,
                false,
                false,
                "Firmware update: ожидание",
                "",
                "",
                "",
                0,
                0,
                false,
                false,
                false,
                false,
                false,
            )
        }

        private fun safe(value: String?): String {
            return if (value == null) "" else value
        }
    }
}
