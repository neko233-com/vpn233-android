package com.neko233.vpn233.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import com.neko233.vpn233.data.ConnectionItem
import com.neko233.vpn233.data.CoreLogEntry
import com.neko233.vpn233.data.ProviderItem
import com.neko233.vpn233.data.ProxyGroupUi
import com.neko233.vpn233.data.ProxyNodeUi
import com.neko233.vpn233.data.RuleItem
import com.neko233.vpn233.data.TrafficStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object MihomoBridge {
    private const val MAX_LOGS = 400

    init {
        System.loadLibrary("mihomo")
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var socketProtector: ((Int) -> Boolean)? = null

    private val uidPackageCache = ConcurrentHashMap<Int, String>()
    private val logSeq = AtomicLong(0L)
    private val logBuffer = ArrayDeque<CoreLogEntry>(MAX_LOGS)
    private val _logs = MutableStateFlow<List<CoreLogEntry>>(emptyList())
    val logs: StateFlow<List<CoreLogEntry>> = _logs.asStateFlow()

    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun onProtectSocket(fd: Int): Boolean {
        val protector = socketProtector ?: return true
        return runCatching { protector(fd) }.getOrDefault(false)
    }

    @JvmStatic
    fun onQuerySocketPackage(protocol: Int, src: String, dst: String): ByteArray {
        val ctx = appContext ?: return ByteArray(0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ByteArray(0)
        val srcAddr = parseInetSocketAddress(src) ?: return ByteArray(0)
        val dstAddr = parseInetSocketAddress(dst) ?: return ByteArray(0)
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return ByteArray(0)
        val uid = runCatching {
            cm.getConnectionOwnerUid(protocol, srcAddr, dstAddr)
        }.getOrDefault(-1)
        if (uid <= 0) return ByteArray(0)
        val pkg = uidPackageCache.getOrPut(uid) {
            runCatching {
                val pkgs = ctx.packageManager.getPackagesForUid(uid)
                pkgs?.firstOrNull() ?: ctx.packageManager.getNameForUid(uid) ?: ""
            }.getOrDefault("")
        }
        return pkg.toByteArray(Charsets.UTF_8)
    }

    @JvmStatic
    fun onCoreLog(level: String, msgBytes: ByteArray) {
        val msg = msgBytes.toString(Charsets.UTF_8)
        val entry = CoreLogEntry(
            id = logSeq.incrementAndGet(),
            timeText = timeFmt.get()?.format(Date()) ?: "",
            level = level.lowercase(Locale.US),
            message = msg
        )
        synchronized(logBuffer) {
            if (logBuffer.size >= MAX_LOGS) {
                logBuffer.removeFirst()
            }
            logBuffer.addLast(entry)
            _logs.value = logBuffer.toList()
        }
    }

    fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.clear()
            _logs.value = emptyList()
        }
    }

    private fun parseInetSocketAddress(raw: String): InetSocketAddress? {
        if (raw.isBlank()) return null
        return runCatching {
            if (raw.startsWith("[")) {
                val idx = raw.lastIndexOf("]:")
                if (idx <= 0) return null
                val host = raw.substring(1, idx)
                val port = raw.substring(idx + 2).toInt()
                InetSocketAddress(InetAddress.getByName(host), port)
            } else {
                val idx = raw.lastIndexOf(':')
                if (idx <= 0) return null
                val host = raw.substring(0, idx)
                val port = raw.substring(idx + 1).toInt()
                InetSocketAddress(InetAddress.getByName(host), port)
            }
        }.getOrNull()
    }

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
    private fun ByteArray.str(): String = toString(Charsets.UTF_8)

    fun init(homeDir: String, versionName: String): String =
        nativeInit(homeDir.utf8(), versionName.utf8(), Build.VERSION.SDK_INT).str()

    fun validateConfig(profileDir: String): String =
        nativeValidateConfig(profileDir.utf8()).str()

    fun loadConfig(profileDir: String, overrideJson: String): String =
        nativeLoadConfig(profileDir.utf8(), overrideJson.utf8()).str()

    fun startTun(fd: Int, stack: String, gateway: String, dns: String, mtu: Int = 9000): String =
        nativeStartTun(fd, stack.utf8(), gateway.utf8(), dns.utf8(), mtu).str()

    fun stopTun() = nativeStopTun()

    fun startMixed(listenAddr: String): String =
        nativeStartMixed(listenAddr.utf8()).str()

    fun stopMixed() = nativeStopMixed()

    fun reset() = nativeReset()

    fun setMode(mode: String) = nativeSetMode(mode.utf8())

    fun getMode(): String = nativeGetMode().str()

    fun queryGroups(testUrl: String): Pair<String, List<ProxyGroupUi>> {
        val raw = nativeQueryGroupsJson(testUrl.utf8()).str()
        if (raw.isBlank()) return "rule" to emptyList()
        return runCatching {
            val obj = JSONObject(raw)
            val mode = obj.optString("mode", "rule")
            val arr = obj.optJSONArray("groups") ?: JSONArray()
            val groups = ArrayList<ProxyGroupUi>(arr.length())
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val pArr = g.optJSONArray("proxies") ?: JSONArray()
                val proxies = ArrayList<ProxyNodeUi>(pArr.length())
                for (j in 0 until pArr.length()) {
                    val p = pArr.getJSONObject(j)
                    proxies.add(
                        ProxyNodeUi(
                            name = p.optString("name"),
                            type = p.optString("type"),
                            delay = p.optInt("delay", -1),
                            isGroup = p.optBoolean("isGroup", false),
                            alive = p.optBoolean("alive", false)
                        )
                    )
                }
                groups.add(
                    ProxyGroupUi(
                        name = g.optString("name"),
                        type = g.optString("type"),
                        now = g.optString("now"),
                        icon = g.optString("icon"),
                        selectable = g.optBoolean("selectable", false),
                        proxies = proxies
                    )
                )
            }
            mode to groups
        }.getOrElse { "rule" to emptyList() }
    }

    fun queryRules(): List<RuleItem> {
        val raw = nativeQueryRulesJson().str()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<RuleItem>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                list.add(
                    RuleItem(
                        index = r.optInt("index", i),
                        type = r.optString("type"),
                        payload = r.optString("payload"),
                        adapter = r.optString("adapter"),
                        raw = r.optString("raw")
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun selectProxy(group: String, proxy: String): Boolean =
        nativeSelectProxy(group.utf8(), proxy.utf8())

    fun testGroupDelay(group: String, testUrl: String, timeoutMs: Int = 4000): Map<String, Int> {
        val raw = nativeTestGroupDelay(group.utf8(), testUrl.utf8(), timeoutMs).str()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val map = HashMap<String, Int>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optInt(k, -1)
            }
            map
        }.getOrDefault(emptyMap())
    }

    fun testProxyDelay(proxy: String, testUrl: String, timeoutMs: Int = 4000): Int =
        nativeTestProxyDelay(proxy.utf8(), testUrl.utf8(), timeoutMs)

    fun queryTraffic(): TrafficStats {
        val raw = nativeQueryTrafficJson().str()
        if (raw.isBlank()) return TrafficStats()
        return runCatching {
            val obj = JSONObject(raw)
            TrafficStats(
                upNow = obj.optLong("upNow", 0L),
                downNow = obj.optLong("downNow", 0L),
                upTotal = obj.optLong("upTotal", 0L),
                downTotal = obj.optLong("downTotal", 0L),
                memoryBytes = obj.optLong("memory", 0L)
            )
        }.getOrDefault(TrafficStats())
    }

    fun queryConnections(): List<ConnectionItem> {
        val raw = nativeQueryConnectionsJson().str()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("connections") ?: return emptyList()
            val list = ArrayList<ConnectionItem>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val meta = item.optJSONObject("metadata") ?: JSONObject()
                val chainsArr = item.optJSONArray("chains") ?: JSONArray()
                val chains = ArrayList<String>(chainsArr.length())
                for (c in 0 until chainsArr.length()) {
                    chains.add(chainsArr.optString(c))
                }
                val host = meta.optString("host").ifBlank { meta.optString("remoteDestination") }
                list.add(
                    ConnectionItem(
                        id = item.optString("id"),
                        host = host,
                        dstIp = meta.optString("destinationIP"),
                        dstPort = meta.optString("destinationPort"),
                        network = meta.optString("network"),
                        type = meta.optString("type"),
                        rule = item.optString("rule"),
                        rulePayload = item.optString("rulePayload"),
                        chains = chains,
                        upload = item.optLong("upload", 0L),
                        download = item.optLong("download", 0L),
                        process = meta.optString("process"),
                        start = item.optString("start")
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun closeConnection(uuid: String): Boolean = nativeCloseConnection(uuid.utf8())

    fun closeAllConnections() = nativeCloseAllConnections()

    fun queryProviders(): List<ProviderItem> {
        val raw = nativeQueryProvidersJson().str()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<ProviderItem>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                list.add(
                    ProviderItem(
                        name = p.optString("name"),
                        kind = p.optString("kind"),
                        vehicleType = p.optString("vehicleType"),
                        count = p.optInt("count", 0),
                        updatedAt = p.optLong("updatedAt", 0L)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun updateProvider(kind: String, name: String): String =
        nativeUpdateProvider(kind.utf8(), name.utf8()).str()

    fun notifyDnsChanged(dnsList: List<String>) =
        nativeNotifyDnsChanged(dnsList.joinToString(",").utf8())

    fun notifyTimeZoneChanged(tzName: String, offsetSec: Int) =
        nativeNotifyTimeZoneChanged(tzName.utf8(), offsetSec)

    fun setLogLevel(level: String) = nativeSetLogLevel(level.utf8())

    fun setLogStreaming(enabled: Boolean) = nativeSetLogStreaming(enabled)

    fun forceGc() = nativeForceGc()

    fun setIdleMode(idle: Boolean) = nativeSetIdleMode(idle)

    fun coreVersion(): String = nativeCoreVersion().str()

    @JvmStatic private external fun nativeInit(homeDir: ByteArray, version: ByteArray, sdkInt: Int): ByteArray
    @JvmStatic private external fun nativeValidateConfig(profileDir: ByteArray): ByteArray
    @JvmStatic private external fun nativeLoadConfig(profileDir: ByteArray, overrideJson: ByteArray): ByteArray
    @JvmStatic private external fun nativeStartTun(fd: Int, stack: ByteArray, gateway: ByteArray, dns: ByteArray, mtu: Int): ByteArray
    @JvmStatic private external fun nativeStopTun()
    @JvmStatic private external fun nativeStartMixed(listenAddr: ByteArray): ByteArray
    @JvmStatic private external fun nativeStopMixed()
    @JvmStatic private external fun nativeReset()
    @JvmStatic private external fun nativeSetMode(mode: ByteArray)
    @JvmStatic private external fun nativeGetMode(): ByteArray
    @JvmStatic private external fun nativeQueryGroupsJson(testUrl: ByteArray): ByteArray
    @JvmStatic private external fun nativeQueryRulesJson(): ByteArray
    @JvmStatic private external fun nativeSelectProxy(group: ByteArray, proxy: ByteArray): Boolean
    @JvmStatic private external fun nativeTestGroupDelay(group: ByteArray, testUrl: ByteArray, timeoutMs: Int): ByteArray
    @JvmStatic private external fun nativeTestProxyDelay(proxy: ByteArray, testUrl: ByteArray, timeoutMs: Int): Int
    @JvmStatic private external fun nativeQueryTrafficJson(): ByteArray
    @JvmStatic private external fun nativeQueryConnectionsJson(): ByteArray
    @JvmStatic private external fun nativeCloseConnection(uuid: ByteArray): Boolean
    @JvmStatic private external fun nativeCloseAllConnections()
    @JvmStatic private external fun nativeQueryProvidersJson(): ByteArray
    @JvmStatic private external fun nativeUpdateProvider(kind: ByteArray, name: ByteArray): ByteArray
    @JvmStatic private external fun nativeNotifyDnsChanged(dnsCsv: ByteArray)
    @JvmStatic private external fun nativeNotifyTimeZoneChanged(tzName: ByteArray, offsetSec: Int)
    @JvmStatic private external fun nativeSetLogLevel(level: ByteArray)
    @JvmStatic private external fun nativeSetLogStreaming(enabled: Boolean)
    @JvmStatic private external fun nativeForceGc()
    @JvmStatic private external fun nativeSetIdleMode(idle: Boolean)
    @JvmStatic private external fun nativeCoreVersion(): ByteArray
}
