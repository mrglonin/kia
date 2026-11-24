package kia.app.update

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Objects

/**
 * Small in-memory cache for immutable APK digests.
 *
 * Package APK paths change on install/update. Size and modification time guard the less common
 * in-place replacement case. The identity is checked again after hashing so a changing file is
 * never cached under stale metadata.
 */
internal class FileSha256Cache
@kotlin.jvm.JvmOverloads
constructor(
    maxEntries: Int = FileSha256Cache.DEFAULT_MAX_ENTRIES,
    computer: DigestComputer? = DigestComputer { file: File? ->
        FileSha256Cache.computeSha256(file)
    },
) {
    internal fun interface DigestComputer {
        fun compute(file: File?): String?
    }

    private val maxEntries: Int
    private val computer: DigestComputer?
    private val entries: LinkedHashMap<FileIdentity?, String?> =
        LinkedHashMap(FileSha256Cache.DEFAULT_MAX_ENTRIES, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        this.maxEntries = maxEntries
        this.computer = Objects.requireNonNull(computer, "computer")
    }

    fun sha256(file: File?): String {
        if (file == null || !file.isFile()) return ""
        for (attempt in 0..1) {
            val before: FileIdentity = FileIdentity.from(file)
            val cached = cached(before)
            if (cached != null) return cached

            val computed: String = FileSha256Cache.safe(computer!!.compute(file))
            if (computed.isEmpty()) return ""
            val after: FileIdentity = FileIdentity.from(file)
            if (!before.equals(after)) continue

            put(after, computed)
            return computed
        }
        return ""
    }

    @kotlin.jvm.Synchronized
    private fun cached(identity: FileIdentity?): String? {
        return entries.get(identity)
    }

    @kotlin.jvm.Synchronized
    private fun put(identity: FileIdentity?, digest: String?) {
        entries.put(identity, digest)
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
        }
    }

    private class FileIdentity(path: String?, size: Long, modifiedAt: Long) {
        @JvmField val path: String?
        @JvmField val size: Long
        @JvmField val modifiedAt: Long

        init {
            this.path = path
            this.size = size
            this.modifiedAt = modifiedAt
        }

        override fun equals(value: Any?): Boolean {
            if (this === value) return true
            if (value !is FileIdentity) return false
            val other: FileIdentity = value
            return size == other.size && modifiedAt == other.modifiedAt && path.equals(other.path)
        }

        override fun hashCode(): Int {
            var result: Int = path.hashCode()
            result = 31 * result + java.lang.Long.hashCode(size)
            result = 31 * result + java.lang.Long.hashCode(modifiedAt)
            return result
        }

        companion object {
            @JvmStatic
            fun from(file: File): FileIdentity {
                return FileIdentity(file.getAbsolutePath(), file.length(), file.lastModified())
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES: Int = 8

        private fun computeSha256(file: File?): String? {
            try {
                BufferedInputStream(FileInputStream(file)).use { `in` ->
                    val digest: MessageDigest? = MessageDigest.getInstance("SHA-256")
                    val buffer: ByteArray = ByteArray(65536)
                    var read: Int
                    while ((`in`.read(buffer).also { read = it }) != -1) digest!!.update(
                        buffer,
                        0,
                        read,
                    )
                    return FileSha256Cache.hex(digest!!.digest())
                }
            } catch (ignored: Exception) {
                return ""
            }
        }

        private fun hex(data: ByteArray): String? {
            val out: StringBuilder = StringBuilder()
            for (b: Byte in data) {
                val value: Int = b.toInt() and 0xff
                if (value < 16) out.append('0')
                out.append(Integer.toHexString(value))
            }
            return out.toString()
        }

        private fun safe(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
