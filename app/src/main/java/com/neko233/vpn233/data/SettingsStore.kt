package com.neko233.vpn233.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SettingsStore(context: Context) {
    private val baseDir = File(context.filesDir, "vpn233").apply { mkdirs() }
    private val settingsFile = File(baseDir, "settings.json")
    private val selectionsFile = File(baseDir, "selections.json")

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val lock = Any()

    private fun loadSettings(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        return runCatching {
            val obj = JSONObject(settingsFile.readText(Charsets.UTF_8))
            val pkgs = HashSet<String>()
            val pkgArr = obj.optJSONArray("accessControlPackages") ?: JSONArray()
            for (i in 0 until pkgArr.length()) {
                val p = pkgArr.optString(i)
                if (p.isNotBlank()) pkgs.add(p)
            }
            val rules = ArrayList<String>()
            val ruleArr = obj.optJSONArray("customOverrideRules") ?: JSONArray()
            for (i in 0 until ruleArr.length()) {
                val r = ruleArr.optString(i)
                if (r.isNotBlank()) rules.add(r)
            }
            val dnsList = ArrayList<String>()
            val dnsArr = obj.optJSONArray("customDnsNameservers") ?: JSONArray()
            for (i in 0 until dnsArr.length()) {
                val d = dnsArr.optString(i)
                if (d.isNotBlank()) dnsList.add(d)
            }
            AppSettings(
                routingMode = RoutingMode.fromCore(obj.optString("routingMode", "rule")),
                tunStack = TunStackMode.fromCore(obj.optString("tunStack", "mixed")),
                allowIpv6 = obj.optBoolean("allowIpv6", false),
                bypassPrivate = obj.optBoolean("bypassPrivate", true),
                dnsHijacking = obj.optBoolean("dnsHijacking", true),
                appendSystemDns = obj.optBoolean("appendSystemDns", true),
                forceDnsMapping = obj.optBoolean("forceDnsMapping", false),
                systemProxy = obj.optBoolean("systemProxy", true),
                mixedPort = obj.optInt("mixedPort", 7890),
                sniffing = obj.optBoolean("sniffing", true),
                overrideDestination = obj.optBoolean("overrideDestination", true),
                unifiedDelay = obj.optBoolean("unifiedDelay", true),
                tcpConcurrent = obj.optBoolean("tcpConcurrent", true),
                geodataMode = obj.optBoolean("geodataMode", false),
                findProcessMode = obj.optString("findProcessMode", "off"),
                logLevel = obj.optString("logLevel", "warning"),
                accessControlMode = runCatching {
                    AccessControlMode.valueOf(obj.optString("accessControlMode", AccessControlMode.ACCEPT_ALL.name))
                }.getOrDefault(AccessControlMode.ACCEPT_ALL),
                accessControlPackages = pkgs,
                allowBypass = obj.optBoolean("allowBypass", true),
                themeMode = runCatching {
                    ThemeMode.valueOf(obj.optString("themeMode", ThemeMode.DARK.name))
                }.getOrDefault(ThemeMode.DARK),
                autoStartOnBoot = obj.optBoolean("autoStartOnBoot", false),
                delayTestUrl = obj.optString("delayTestUrl", "https://www.gstatic.com/generate_204"),
                customOverrideRules = rules,
                customDnsNameservers = dnsList,
                showTrafficInNotification = obj.optBoolean("showTrafficInNotification", true)
            )
        }.getOrDefault(AppSettings())
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        synchronized(lock) {
            val updated = transform(_settings.value)
            _settings.value = updated
            saveSettings(updated)
            return updated
        }
    }

    private fun saveSettings(s: AppSettings) {
        val obj = JSONObject().apply {
            put("routingMode", s.routingMode.coreValue)
            put("tunStack", s.tunStack.coreValue)
            put("allowIpv6", s.allowIpv6)
            put("bypassPrivate", s.bypassPrivate)
            put("dnsHijacking", s.dnsHijacking)
            put("appendSystemDns", s.appendSystemDns)
            put("forceDnsMapping", s.forceDnsMapping)
            put("systemProxy", s.systemProxy)
            put("mixedPort", s.mixedPort)
            put("sniffing", s.sniffing)
            put("overrideDestination", s.overrideDestination)
            put("unifiedDelay", s.unifiedDelay)
            put("tcpConcurrent", s.tcpConcurrent)
            put("geodataMode", s.geodataMode)
            put("findProcessMode", s.findProcessMode)
            put("logLevel", s.logLevel)
            put("accessControlMode", s.accessControlMode.name)
            put("accessControlPackages", JSONArray(s.accessControlPackages.toList()))
            put("allowBypass", s.allowBypass)
            put("themeMode", s.themeMode.name)
            put("autoStartOnBoot", s.autoStartOnBoot)
            put("delayTestUrl", s.delayTestUrl)
            put("customOverrideRules", JSONArray(s.customOverrideRules))
            put("customDnsNameservers", JSONArray(s.customDnsNameservers))
            put("showTrafficInNotification", s.showTrafficInNotification)
        }
        val tmp = File(baseDir, "settings.json.tmp")
        tmp.writeText(obj.toString(2), Charsets.UTF_8)
        tmp.renameTo(settingsFile)
    }

    fun toOverrideJson(s: AppSettings = _settings.value): String {
        val obj = JSONObject().apply {
            put("mode", s.routingMode.coreValue)
            put("ipv6", s.allowIpv6)
            put("unifiedDelay", s.unifiedDelay)
            put("tcpConcurrent", s.tcpConcurrent)
            put("geodataMode", s.geodataMode)
            put("findProcessMode", s.findProcessMode)
            put("appendSystemDns", s.appendSystemDns)
            put("forceDnsMapping", s.forceDnsMapping)
            put("sniffing", s.sniffing)
            put("overrideDestination", s.overrideDestination)
            put("logLevel", s.logLevel)
            if (s.customOverrideRules.isNotEmpty()) {
                put("prependRules", JSONArray(s.customOverrideRules))
            }
            if (s.customDnsNameservers.isNotEmpty()) {
                put("nameServers", JSONArray(s.customDnsNameservers))
            }
        }
        return obj.toString()
    }

    fun getSelectedNodes(profileId: String): Map<String, String> {
        synchronized(lock) {
            if (!selectionsFile.exists()) return emptyMap()
            return runCatching {
                val root = JSONObject(selectionsFile.readText(Charsets.UTF_8))
                val pObj = root.optJSONObject(profileId) ?: return emptyMap()
                val res = LinkedHashMap<String, String>()
                val keys = pObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    res[k] = pObj.optString(k)
                }
                res
            }.getOrDefault(emptyMap())
        }
    }

    fun setSelectedNode(profileId: String, groupName: String, nodeName: String) {
        synchronized(lock) {
            val root = if (selectionsFile.exists()) {
                runCatching { JSONObject(selectionsFile.readText(Charsets.UTF_8)) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            val pObj = root.optJSONObject(profileId) ?: JSONObject()
            pObj.put(groupName, nodeName)
            root.put(profileId, pObj)
            val tmp = File(baseDir, "selections.json.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            tmp.renameTo(selectionsFile)
        }
    }
}
