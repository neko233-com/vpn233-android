package com.neko233.vpn233

import com.neko233.vpn233.data.ProfileSourceType
import com.neko233.vpn233.data.ProfileStorageEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProfileStorageEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleYaml(server: String, name: String = "Node1"): String = """
        proxies:
          - name: "$name"
            type: socks5
            server: $server
            port: 1080
        proxy-groups:
          - name: "PROXY"
            type: select
            proxies:
              - $name
              - DIRECT
        rules:
          - MATCH,PROXY
    """.trimIndent()

    @Test
    fun idempotentNamedYamlAndUrlUpsertsDoNotDuplicate() {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val engine = ProfileStorageEngine(filesDir, cacheDir) { "" }

        // 1. Named local/ADB import twice -> same ID, count stays 1
        val first = engine.saveYamlContentWithResult(
            id = null,
            name = "WorkProfile",
            yamlContent = sampleYaml("1.1.1.1", "KR-01"),
            sourceType = ProfileSourceType.LOCAL
        ).getOrThrow()
        assertTrue(first.created)
        assertTrue(first.contentChanged)

        val secondSame = engine.saveYamlContentWithResult(
            id = null,
            name = "WorkProfile",
            yamlContent = sampleYaml("1.1.1.1", "KR-01"),
            sourceType = ProfileSourceType.LOCAL
        ).getOrThrow()
        assertFalse(secondSame.created)
        assertFalse(secondSame.contentChanged)
        assertEquals(first.item.id, secondSame.item.id)
        assertEquals(1, engine.profiles.value.size)

        // 2. Updated YAML with same explicit name -> updates in-place, sets contentChanged = true
        val thirdUpdated = engine.saveYamlContentWithResult(
            id = null,
            name = "WorkProfile",
            yamlContent = sampleYaml("2.2.2.2", "US-01"),
            sourceType = ProfileSourceType.LOCAL
        ).getOrThrow()
        assertFalse(thirdUpdated.created)
        assertTrue(thirdUpdated.contentChanged)
        assertEquals(first.item.id, thirdUpdated.item.id)
        assertEquals(1, engine.profiles.value.size)
        assertTrue(engine.readProfileYaml(first.item.id).contains("US-01"))

        // 3. URL import idempotency (by normalized URL)
        val url = "https://sub.example.com/clash.yaml"
        val urlFirst = engine.upsertUrlPayload(
            trimmedUrl = url,
            resolvedName = "MySub",
            body = sampleYaml("3.3.3.3", "JP-01"),
            existing = engine.findByUrl(url),
            updateHours = 12,
            uploadBytes = 100L,
            downloadBytes = 200L,
            totalBytes = 1000L,
            expireEpochSec = 1900000000L
        )
        assertTrue(urlFirst.created)
        val urlSecondSame = engine.upsertUrlPayload(
            trimmedUrl = url,
            resolvedName = "MySub",
            body = sampleYaml("3.3.3.3", "JP-01"),
            existing = engine.findByUrl(url),
            updateHours = 12,
            uploadBytes = 100L,
            downloadBytes = 200L,
            totalBytes = 1000L,
            expireEpochSec = 1900000000L
        )
        assertFalse(urlSecondSame.created)
        assertFalse(urlSecondSame.contentChanged)
        assertEquals(urlFirst.item.id, urlSecondSame.item.id)
        assertEquals(2, engine.profiles.value.size)
    }

    @Test
    fun genericClipboardDeduplicatesIdenticalContentWithoutClobberingDifferentContent() {
        val filesDir = tempFolder.newFolder("files2")
        val cacheDir = tempFolder.newFolder("cache2")
        val engine = ProfileStorageEngine(filesDir, cacheDir) { "" }

        val clip1 = engine.saveYamlContentWithResult(
            id = null,
            name = "剪贴板配置",
            yamlContent = sampleYaml("10.0.0.1", "Clip-A"),
            sourceType = ProfileSourceType.CLIPBOARD
        ).getOrThrow()
        assertEquals("剪贴板配置", clip1.item.name)

        // Same clipboard content pasted again -> reuses existing profile
        val clip1Repeat = engine.saveYamlContentWithResult(
            id = null,
            name = "剪贴板配置",
            yamlContent = sampleYaml("10.0.0.1", "Clip-A"),
            sourceType = ProfileSourceType.CLIPBOARD
        ).getOrThrow()
        assertFalse(clip1Repeat.created)
        assertEquals(clip1.item.id, clip1Repeat.item.id)
        assertEquals(1, engine.profiles.value.size)

        // Different clipboard content -> creates distinct disambiguated profile
        val clip2 = engine.saveYamlContentWithResult(
            id = null,
            name = "剪贴板配置",
            yamlContent = sampleYaml("10.0.0.2", "Clip-B"),
            sourceType = ProfileSourceType.CLIPBOARD
        ).getOrThrow()
        assertTrue(clip2.created)
        assertEquals("剪贴板配置 (2)", clip2.item.name)
        assertEquals(2, engine.profiles.value.size)

        // ZIP archive re-import idempotency + reload from disk check
        val zipBytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("alpha.yaml"))
                zos.write(sampleYaml("8.8.8.8", "Alpha").toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            bos.toByteArray()
        }
        val z1 = engine.importBytesWithResult(zipBytes, "bundle.zip")
        val z2 = engine.importBytesWithResult(zipBytes, "bundle.zip")
        assertTrue(z1.first().created)
        assertFalse(z2.first().created)
        assertEquals(3, engine.profiles.value.size)

        val reloaded = ProfileStorageEngine(filesDir, cacheDir) { "" }
        assertEquals(3, reloaded.profiles.value.size)
        assertEquals(clip1.item.id, reloaded.activeProfileId.value)
    }
}
