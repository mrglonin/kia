package kia.app.update;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppUpdateControllerTest {
    @Test
    public void parsesGithubReleaseAssetSha256Digest() {
        assertEquals(
                "07f4979ee2df95b62ce8c1a44f8ecc6a9892b9481951ab780962fe06508f8be7",
                AppUpdateController.githubSha256(
                        "sha256:07F4979EE2DF95B62CE8C1A44F8ECC6A9892B9481951AB780962FE06508F8BE7"));
    }

    @Test
    public void rejectsUnsupportedOrMalformedDigest() {
        assertEquals("", AppUpdateController.githubSha256("sha1:1234"));
        assertEquals("", AppUpdateController.githubSha256("sha256:not-a-digest"));
    }
}
