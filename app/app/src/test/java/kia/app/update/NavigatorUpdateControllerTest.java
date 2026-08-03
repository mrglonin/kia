package kia.app.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NavigatorUpdateControllerTest {
    @Test
    public void navigatorArchiveRequiresInstallableMetadata() {
        String sha = "0b6e336aaeb0211cd011de28ba66567e9c1e879b5aa6fb449ebf11fef14a71fd";
        assertTrue(NavigatorUpdateController.validFileMetadata(
                "yandex.apk", "https://example.test/yandex.apk", sha, 100L));
        assertFalse(NavigatorUpdateController.validFileMetadata(
                "yandex.apk", "https://example.test/yandex.apk", "", 100L));
        assertFalse(NavigatorUpdateController.validFileMetadata(
                "yandex.zip", "https://example.test/yandex.zip", sha, 100L));
    }

    @Test
    public void multipartArchiveRequiresCompletePartMetadata() {
        String sha = "0b6e336aaeb0211cd011de28ba66567e9c1e879b5aa6fb449ebf11fef14a71fd";
        assertTrue(NavigatorUpdateController.validPartMetadata(
                "yandex.apk.001", "https://example.test/yandex.apk.001", sha, 100L));
        assertFalse(NavigatorUpdateController.validPartMetadata(
                "yandex.apk.001", "", sha, 100L));
        assertFalse(NavigatorUpdateController.validPartMetadata(
                "yandex.apk.001", "https://example.test/yandex.apk.001", "", 100L));
        assertFalse(NavigatorUpdateController.validPartMetadata(
                "yandex.apk.001", "https://example.test/yandex.apk.001", sha, 0L));
    }

    @Test
    public void mixedValidAndInvalidNavigatorFilesFailClosed() {
        String sha = "0b6e336aaeb0211cd011de28ba66567e9c1e879b5aa6fb449ebf11fef14a71fd";
        NavigatorUpdateController.NavigatorInfo info =
                new NavigatorUpdateController.NavigatorInfo();
        info.versionCode = 71011062;

        NavigatorUpdateController.NavFile valid = new NavigatorUpdateController.NavFile();
        valid.name = "base.apk";
        valid.url = "https://example.test/base.apk";
        valid.sha256 = sha;
        valid.size = 100L;
        info.files.add(valid);

        NavigatorUpdateController.NavFile invalid = new NavigatorUpdateController.NavFile();
        invalid.name = "";
        invalid.url = "https://example.test/split.apk";
        invalid.sha256 = sha;
        invalid.size = 100L;
        info.files.add(invalid);
        assertFalse(info.installable());

        info.files.remove(invalid);
        info.filesMetadataValid = false;
        assertFalse(info.installable());
    }
}
