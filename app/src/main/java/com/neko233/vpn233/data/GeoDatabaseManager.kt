package com.neko233.vpn233.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class GeoDatabaseManager(private val context: Context) {
    val homeDir = File(context.filesDir, "mihomo").apply { mkdirs() }

    private val geoIpFile get() = File(homeDir, "geoip.metadb")
    private val geoSiteFile get() = File(homeDir, "GeoSite.dat")
    private val asnFile get() = File(homeDir, "ASN.mmdb")

    fun ensureBootstrapAssets() {
        copyAssetIfMissing("geoip.metadb", geoIpFile)
        copyAssetIfMissing("geosite.dat", geoSiteFile)
    }

    private fun copyAssetIfMissing(assetName: String, dest: File) {
        if (dest.exists() && dest.length() > 1024L) return
        val tmp = File(homeDir, "$assetName.tmp")
        runCatching {
            context.assets.open(assetName).use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tmp.length() > 1024L) {
                tmp.renameTo(dest)
            } else {
                tmp.delete()
            }
        }
    }

    fun getStatus(): GeoDbStatus = GeoDbStatus(
        geoIpBytes = if (geoIpFile.exists()) geoIpFile.length() else 0L,
        geoSiteBytes = if (geoSiteFile.exists()) geoSiteFile.length() else 0L,
        asnBytes = if (asnFile.exists()) asnFile.length() else 0L
    )

    suspend fun upgradeToFullDatabases(onStep: (String) -> Unit): Result<GeoDbStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                onStep("正在下载完整版 geoip.metadb...")
                downloadWithFallback(
                    listOf(
                        "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.metadb",
                        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb"
                    ),
                    geoIpFile
                )
                onStep("正在下载完整版 GeoSite.dat...")
                downloadWithFallback(
                    listOf(
                        "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat",
                        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat"
                    ),
                    geoSiteFile
                )
                onStep("正在下载 ASN.mmdb...")
                downloadWithFallback(
                    listOf(
                        "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/GeoLite2-ASN.mmdb",
                        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb"
                    ),
                    asnFile
                )
                getStatus()
            }
        }

    fun resetToLiteDatabases(): GeoDbStatus {
        geoIpFile.delete()
        geoSiteFile.delete()
        asnFile.delete()
        ensureBootstrapAssets()
        return getStatus()
    }

    private fun downloadWithFallback(urls: List<String>, target: File) {
        var lastError: Throwable? = null
        for (u in urls) {
            val tmp = File(target.parentFile, "${target.name}.dl")
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "vpn233/1.0 mihomo/1.19")
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
                if (target.exists()) target.delete()
                tmp.renameTo(target)
                return
            } catch (t: Throwable) {
                tmp.delete()
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Failed to download ${target.name}")
    }
}
