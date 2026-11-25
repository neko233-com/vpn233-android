package com.neko233.vpn233

import com.neko233.vpn233.data.ProfileSourceType
import com.neko233.vpn233.data.ProfileStorageEngine
import com.neko233.vpn233.data.ProfileUpdateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProfileBackupTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun engine(name: String, validator: (String) -> String = { "" }) =
        ProfileStorageEngine(temp.newFolder("$name-files"), temp.newFolder("$name-cache"), validator)

    private fun yaml(name: String) = """
        proxies:
          - {name: $name, type: socks5, server: example.invalid, port: 1080}
        rules:
          - MATCH,DIRECT
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }

    @Test
    fun backupRestoresProfilesMergeProvidersAndUpdateOptions() {
        val source = engine("source")
        source.saveYamlContentWithResult(null, "Local", yaml("Local")).getOrThrow()
        val sub = source.saveYamlContentWithResult(
            null, "Subscription", yaml("Sub"), ProfileSourceType.URL,
            url = "https://example.invalid/subscription", autoUpdateMinutes = 720,
            uploadBytes = 10, downloadBytes = 20, totalBytes = 100, expireEpochSec = 1900000000
        ).getOrThrow().item
        source.setProfileUpdateOptions(sub.id, 720, ProfileUpdateMode.PROXY, "test-client")
        source.saveMergeYaml(sub.id, "prepend-rules:\n  - DOMAIN,example.invalid,DIRECT").getOrThrow()
        File(source.getProfileDir(sub.id), "providers/list.yaml").apply {
            parentFile!!.mkdirs()
            writeText("payload: []")
        }
        source.setActiveProfile(sub.id)

        val restored = engine("restored") { dir ->
            if (File(dir, "config.yaml").readText().contains("name: Sub") &&
                !File(dir, "providers/list.yaml").exists()) "missing provider" else ""
        }
        val results = restored.importBytesWithResult(source.exportAllBackupZip().readBytes(), "backup.zip")
        assertEquals(2, results.size)
        val item = restored.findByIdOrName("Subscription")!!
        assertEquals(item.id, restored.activeProfileId.value)
        assertEquals(item.id, results.first().item.id)
        assertEquals(ProfileSourceType.URL, item.sourceType)
        assertEquals("https://example.invalid/subscription", item.url)
        assertEquals(720, item.autoUpdateMinutes)
        assertEquals(ProfileUpdateMode.PROXY, item.updateMode)
        assertEquals("test-client", item.customUserAgent)
        assertEquals(30L, item.usedBytes)
        assertEquals(100L, item.totalBytes)
        assertEquals(1900000000L, item.expireEpochSec)
        assertTrue(item.hasMergeYaml)
        assertTrue(restored.readMergeYaml(item.id).contains("example.invalid"))
        assertEquals("payload: []", File(restored.getProfileDir(item.id), "providers/list.yaml").readText())
        assertEquals(2, restored.importBytesWithResult(source.exportAllBackupZip().readBytes(), "backup.zip").size)
        assertEquals(2, restored.profiles.value.size)
    }

    @Test
    fun traversalEntryIsRejectedBeforeAnyProfileIsWritten() {
        val store = engine("traversal")
        val archive = zip(
            "profiles/a/config.yaml" to yaml("A").toByteArray(),
            "profiles/a/providers/../../escaped.yaml" to "bad".toByteArray()
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.importBytesWithResult(archive, "backup.zip")
        }
        assertTrue(store.profiles.value.isEmpty())
    }

    @Test
    fun oversizedExpandedEntryIsRejectedBeforeAnyProfileIsWritten() {
        val store = engine("oversized")
        val archive = zip(
            "profile.yaml" to yaml("A").toByteArray(),
            "payload.bin" to ByteArray(16 * 1024 * 1024 + 1)
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.importBytesWithResult(archive, "backup.zip")
        }
        assertTrue(store.profiles.value.isEmpty())
    }

    @Test
    fun invalidLaterConfigDoesNotPartiallyImportArchive() {
        val store = engine("invalid") { dir ->
            if (File(dir, "config.yaml").readText().contains("name: Invalid")) "invalid" else ""
        }
        val archive = zip(
            "valid.yaml" to yaml("Valid").toByteArray(),
            "invalid.yaml" to yaml("Invalid").toByteArray()
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.importBytesWithResult(archive, "backup.zip")
        }
        assertFalse(store.profiles.value.any())
    }

    @Test
    fun concurrentSubscriptionImportsDeduplicateAndKeepUserUpdateInterval() {
        val store = engine("concurrent")
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(6)
        try {
            val results = (1..6).map {
                workers.submit<com.neko233.vpn233.data.ProfileSaveResult> {
                    start.await()
                    store.upsertUrlPayload("https://example.invalid/sub", "Sub", yaml("Sub"), null, 12, -1, -1, -1, -1)
                }
            }
            start.countDown()
            val ids = results.map { it.get(10, TimeUnit.SECONDS).item.id }.toSet()
            assertEquals(1, ids.size)
            assertEquals(1, store.profiles.value.size)
            val id = ids.single()
            store.setProfileUpdateOptions(id, 90, ProfileUpdateMode.PROXY, "custom")
            val updated = store.upsertUrlPayload(
                "https://example.invalid/sub", "Sub", yaml("Sub"),
                store.findByIdOrName(id), 24, -1, -1, -1, -1
            )
            assertEquals(90, updated.item.autoUpdateMinutes)
            assertEquals(ProfileUpdateMode.PROXY, updated.item.updateMode)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun identicalContentFromDifferentSubscriptionUrlsRemainsSeparateAfterRestore() {
        val source = engine("distinct-urls-source")
        listOf("one", "two").forEach { path ->
            source.saveYamlContentWithResult(
                null, "Subscription $path", yaml("Shared"), ProfileSourceType.URL,
                url = "https://example.invalid/$path"
            ).getOrThrow()
        }
        assertEquals(2, source.profiles.value.size)
        val restored = engine("distinct-urls-restored")
        restored.importBytesWithResult(source.exportAllBackupZip().readBytes(), "backup.zip")
        assertEquals(setOf("https://example.invalid/one", "https://example.invalid/two"),
            restored.profiles.value.map { it.url }.toSet())
    }
}
