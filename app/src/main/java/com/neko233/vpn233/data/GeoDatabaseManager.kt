package com.neko233.vpn233.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class GeoDatabaseManager(private val context: Context) {
    val homeDir = File(context.filesDir, "mihomo").apply { mkdirs() }

    private val geoIpFile get() = File(homeDir, "geoip.metadb")
    private val geoIpDatFile get() = File(homeDir, "GeoIP.dat")
    private val geoSiteFile get() = File(homeDir, "GeoSite.dat")
    private val asnFile get() = File(homeDir, "ASN.mmdb")

    fun ensureBootstrapAssets() {
        copyBundledAsset("geoip.metadb", geoIpFile, "c17f1d162bf3da8aa881dd6c0665b2179ea9a83e6a4af0257190dfcabd037233", 407_997L)
        copyBundledAsset("geosite.dat", geoSiteFile, "b8c5706889a8ad4a48662e84b14a4d35abf0b10de47c85c94febfad841150af5", 176_649L)
        copyBundledAsset("GeoIP.dat", geoIpDatFile)
        copyBundledAsset("ASN.mmdb", asnFile)
    }

    private fun copyBundledAsset(assetName: String, dest: File, legacySha256: String? = null, legacySize: Long? = null) =
        GeoBootstrapInstaller.install(dest, legacySha256, legacySize) { context.assets.open(assetName) }

    fun getStatus(): GeoDbStatus = GeoDbStatus(
        geoIpBytes = if (geoIpFile.exists()) geoIpFile.length() else 0L,
        geoSiteBytes = if (geoSiteFile.exists()) geoSiteFile.length() else 0L,
        asnBytes = if (asnFile.exists()) asnFile.length() else 0L,
        geoIpDatBytes = if (geoIpDatFile.exists()) geoIpDatFile.length() else 0L
    )

    suspend fun upgradeToFullDatabases(proxyPort: Int = 0, onStep: (String) -> Unit): Result<GeoDbStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                onStep("正在下载完整版 geoip.metadb...")
                downloadWithFallback(
                    listOf(
                        "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.metadb",
                        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb"
                    ),
                    geoIpFile,
                    proxyPort
                )
                onStep("正在下载 GeoIP.dat...")
                downloadWithFallback(
                    listOf(
                        "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.dat",
                        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat"
                    ),
                    geoIpDatFile,
                    proxyPort
                )
                onStep("正在下载完整版 GeoSite.dat...")
                downloadWithFallback(
                    listOf(
                        "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat",
                        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat"
                    ),
                    geoSiteFile,
                    proxyPort
                )
                onStep("正在下载 ASN.mmdb...")
                downloadWithFallback(
                    listOf(
                        "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/GeoLite2-ASN.mmdb",
                        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb"
                    ),
                    asnFile,
                    proxyPort
                )
                getStatus()
            }
        }

    fun resetToBundledDatabases(): GeoDbStatus {
        geoIpFile.delete()
        geoIpDatFile.delete()
        geoSiteFile.delete()
        asnFile.delete()
        ensureBootstrapAssets()
        return getStatus()
    }

    private fun downloadWithFallback(urls: List<String>, target: File, proxyPort: Int) {
        var lastError: Throwable? = null
        val proxies = buildList {
            if (proxyPort in 1024..65535) {
                add(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
            }
            add(Proxy.NO_PROXY)
        }
        for (u in urls) {
            for (prx in proxies) {
                val tmp = File(target.parentFile, "${target.name}.dl")
                try {
                    val conn = (URL(u).openConnection(prx) as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", "vpn233/1.2 mihomo/1.19")
                        instanceFollowRedirects = true
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code from $u")
                    }
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (tmp.length() < 10_000L) {
                        throw IllegalStateException("Downloaded file too small from $u")
                    }
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    return
                } catch (t: Throwable) {
                    tmp.delete()
                    lastError = t
                }
            }
        }
        throw lastError ?: IllegalStateException("Failed to download ${target.name}")
    }
}
