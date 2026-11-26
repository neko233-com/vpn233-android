package com.neko233.vpn233.data

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Replace only missing/corrupt files or an exact legacy bundled asset, never user data. */
internal object GeoBootstrapInstaller {
    fun install(destination: File, legacySha256: String? = null, legacySize: Long? = null, openAsset: () -> InputStream) {
        if (destination.isFile && destination.length() > 1024L) {
            if (legacySha256 == null || (legacySize != null && destination.length() != legacySize) ||
                sha256(destination) != legacySha256) return
        }
        val parent = destination.parentFile ?: throw IllegalArgumentException("Geo database has no parent directory")
        check(parent.mkdirs() || parent.isDirectory) { "Cannot create Geo database directory" }
        val temp = File.createTempFile(".geo-", ".tmp", parent)
        try {
            openAsset().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            require(temp.length() > 1024L) { "Bundled Geo database is empty or incomplete" }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
