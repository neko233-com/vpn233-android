package com.neko233.vpn233

import com.neko233.vpn233.data.GeoBootstrapInstaller
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class GeoBootstrapInstallerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test
    fun replacesOnlyExactLegacyFileAndPreservesCustomData() {
        val file = File(temp.root, "GeoSite.dat")
        val old = ByteArray(2048) { 1 }
        val full = ByteArray(4096) { 2 }
        file.writeBytes(old)
        GeoBootstrapInstaller.install(file, hash(old), old.size.toLong()) { ByteArrayInputStream(full) }
        assertArrayEquals(full, file.readBytes())

        val custom = ByteArray(3000) { 3 }
        file.writeBytes(custom)
        GeoBootstrapInstaller.install(file, hash(old), old.size.toLong()) { ByteArrayInputStream(full) }
        assertArrayEquals(custom, file.readBytes())
    }

    @Test
    fun failedUpgradeLeavesExistingFileIntact() {
        val file = File(temp.root, "geoip.metadb")
        val old = ByteArray(2048) { 4 }
        file.writeBytes(old)
        assertThrows(IOException::class.java) {
            GeoBootstrapInstaller.install(file, hash(old), old.size.toLong()) { throw IOException("test failure") }
        }
        assertArrayEquals(old, file.readBytes())
    }
}
