package kia.app.update;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Small in-memory cache for immutable APK digests.
 *
 * <p>Package APK paths change on install/update. Size and modification time guard the less common
 * in-place replacement case. The identity is checked again after hashing so a changing file is
 * never cached under stale metadata.
 */
final class FileSha256Cache {
    interface DigestComputer {
        String compute(File file);
    }

    private static final int DEFAULT_MAX_ENTRIES = 8;

    private final int maxEntries;
    private final DigestComputer computer;
    private final LinkedHashMap<FileIdentity, String> entries =
            new LinkedHashMap<>(DEFAULT_MAX_ENTRIES, 0.75f, true);

    FileSha256Cache() {
        this(DEFAULT_MAX_ENTRIES, FileSha256Cache::computeSha256);
    }

    FileSha256Cache(int maxEntries, DigestComputer computer) {
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
        this.computer = Objects.requireNonNull(computer, "computer");
    }

    String sha256(File file) {
        if (file == null || !file.isFile()) return "";
        for (int attempt = 0; attempt < 2; attempt++) {
            FileIdentity before = FileIdentity.from(file);
            String cached = cached(before);
            if (cached != null) return cached;

            String computed = safe(computer.compute(file));
            if (computed.isEmpty()) return "";
            FileIdentity after = FileIdentity.from(file);
            if (!before.equals(after)) continue;

            put(after, computed);
            return computed;
        }
        return "";
    }

    private synchronized String cached(FileIdentity identity) {
        return entries.get(identity);
    }

    private synchronized void put(FileIdentity identity, String digest) {
        entries.put(identity, digest);
        while (entries.size() > maxEntries) {
            Map.Entry<FileIdentity, String> eldest = entries.entrySet().iterator().next();
            entries.remove(eldest.getKey());
        }
    }

    private static String computeSha256(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65_536];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return hex(digest.digest());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) {
            int value = b & 0xff;
            if (value < 16) out.append('0');
            out.append(Integer.toHexString(value));
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class FileIdentity {
        final String path;
        final long size;
        final long modifiedAt;

        private FileIdentity(String path, long size, long modifiedAt) {
            this.path = path;
            this.size = size;
            this.modifiedAt = modifiedAt;
        }

        static FileIdentity from(File file) {
            return new FileIdentity(file.getAbsolutePath(), file.length(), file.lastModified());
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof FileIdentity)) return false;
            FileIdentity other = (FileIdentity) value;
            return size == other.size
                    && modifiedAt == other.modifiedAt
                    && path.equals(other.path);
        }

        @Override
        public int hashCode() {
            int result = path.hashCode();
            result = 31 * result + Long.hashCode(size);
            result = 31 * result + Long.hashCode(modifiedAt);
            return result;
        }
    }
}
