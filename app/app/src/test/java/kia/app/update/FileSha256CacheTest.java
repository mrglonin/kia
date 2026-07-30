package kia.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class FileSha256CacheTest {
    @Test
    public void computesSha256WithProductionDigester() throws Exception {
        File file = File.createTempFile("kia-apk-cache-real", ".apk");
        file.deleteOnExit();
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223"
                        + "b00361a396177a9cb410ff61f20015ad",
                new FileSha256Cache().sha256(file));
    }

    @Test
    public void unchangedFileIsHashedOnlyOnce() throws Exception {
        File file = File.createTempFile("kia-apk-cache", ".apk");
        file.deleteOnExit();
        Files.write(file.toPath(), "first".getBytes(StandardCharsets.UTF_8));
        AtomicInteger calls = new AtomicInteger();
        FileSha256Cache cache = new FileSha256Cache(2, input -> {
            calls.incrementAndGet();
            return "digest-" + input.length();
        });

        assertEquals("digest-5", cache.sha256(file));
        assertEquals("digest-5", cache.sha256(file));
        assertEquals(1, calls.get());
    }

    @Test
    public void replacementWithSamePathInvalidatesDigest() throws Exception {
        File file = File.createTempFile("kia-apk-cache-replace", ".apk");
        file.deleteOnExit();
        Files.write(file.toPath(), "aaaa".getBytes(StandardCharsets.UTF_8));
        long originalModifiedAt = file.lastModified();
        AtomicInteger calls = new AtomicInteger();
        FileSha256Cache cache = new FileSha256Cache(2, input ->
                "digest-" + calls.incrementAndGet());

        String first = cache.sha256(file);
        Files.write(file.toPath(), "bbbb".getBytes(StandardCharsets.UTF_8));
        file.setLastModified(Math.max(System.currentTimeMillis(), originalModifiedAt + 2_000L));
        String second = cache.sha256(file);

        assertNotEquals(first, second);
        assertEquals(2, calls.get());
    }

    @Test
    public void changingFileDuringHashIsNeverCachedUnderOldIdentity() throws Exception {
        File file = File.createTempFile("kia-apk-cache-changing", ".apk");
        file.deleteOnExit();
        Files.write(file.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        AtomicInteger calls = new AtomicInteger();
        FileSha256Cache cache = new FileSha256Cache(2, input -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                try {
                    Files.write(input.toPath(), "new-value".getBytes(StandardCharsets.UTF_8));
                    input.setLastModified(System.currentTimeMillis() + 2_000L);
                } catch (Exception e) {
                    return "";
                }
            }
            return "digest-" + call;
        });

        assertEquals("digest-2", cache.sha256(file));
        assertEquals("digest-2", cache.sha256(file));
        assertEquals(2, calls.get());
    }
}
