package com.neko233.vpn233.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.net.VpnService
import android.os.Binder
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import com.neko233.vpn233.Vpn233Application
import com.neko233.vpn233.core.MihomoBridge
import com.neko233.vpn233.data.AccessControlMode
import com.neko233.vpn233.data.AppLanguage
import com.neko233.vpn233.data.CustomRuleEntry
import com.neko233.vpn233.data.NetworkHelpers
import com.neko233.vpn233.data.ProfileSourceType
import com.neko233.vpn233.data.ProfileUpdateMode
import com.neko233.vpn233.data.RoutingMode
import com.neko233.vpn233.data.RulePosition
import com.neko233.vpn233.data.ThemeMode
import com.neko233.vpn233.data.TunStackMode
import com.neko233.vpn233.data.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Synchronous ADB/Agent control endpoint invoked via:
 * `adb shell content call --uri content://com.neko233.vpn233.agent --method <cmd> [--arg <arg>] [--extra k:type:v]`
 *
 * Strictly enforces caller UID == SHELL_UID (adb), ROOT_UID, or self UID so third-party apps cannot access it.
 */
class AgentControlProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SHELL_UID && callingUid != Process.ROOT_UID && callingUid != Process.myUid()) {
            throw SecurityException("Caller UID $callingUid is not permitted to control vpn233 agent endpoint")
        }
        val ctx = context ?: return errBundle("Context unavailable")
        val app = ctx.applicationContext as? Vpn233Application ?: return errBundle("Application not ready")

        return runCatching {
            when (method.lowercase()) {
                "ping" -> okBundle(JSONObject().put("pong", true).put("package", ctx.packageName))
                "status" -> handleStatus(app)
                "start" -> handleStart(app)
                "stop" -> {
                    Vpn233Service.stop(ctx)
                    waitUntilSettled(timeoutMs = 3000L)
                    okBundle(JSONObject().put("status", Vpn233Service.status.value.name))
                }
                "reload" -> {
                    if (Vpn233Service.status.value != VpnStatus.RUNNING) {
                        errBundle("VPN_NOT_RUNNING")
                    } else {
                        MihomoBridge.closeAllConnections()
                        Vpn233Service.reload(ctx)
                        waitUntilSettled(timeoutMs = 4000L)
                        okBundle(JSONObject().put("reloaded", true).put("vpnStatus", Vpn233Service.status.value.name))
                    }
                }
                "set_mode" -> {
                    val m = RoutingMode.fromCore(arg.orEmpty())
                    app.settingsStore.update { it.copy(routingMode = m) }
                    if (Vpn233Service.status.value == VpnStatus.RUNNING) {
                        MihomoBridge.setMode(m.coreValue)
                        Vpn233Service.refreshCurrentNodeName()
                        Vpn233Service.refreshNotification(ctx)
                    }
                    okBundle(JSONObject().put("mode", m.coreValue))
                }
                "list_profiles" -> handleListProfiles(app)
                "import_url" -> handleImportUrl(app, arg.orEmpty(), extras?.getString("name").orEmpty())
                "import_yaml" -> handleImportYaml(app, arg.orEmpty(), extras)
                "activate_profile" -> handleActivateProfile(app, arg.orEmpty(), extras?.getBoolean("force", false) ?: false)
                "rename_profile" -> handleRenameProfile(app, arg.orEmpty(), extras?.getString("name").orEmpty())
                "update_profile" -> handleUpdateProfile(app, arg.orEmpty())
                "set_profile_options" -> handleSetProfileOptions(app, arg.orEmpty(), extras)
                "get_merge_yaml" -> {
                    val item = app.profileRepository.findByIdOrName(arg.orEmpty())
                        ?: return errBundle("Profile not found: $arg")
                    okBundle(JSONObject().put("id", item.id).put("name", item.name).put("yaml", app.profileRepository.readMergeYaml(item.id)))
                }
                "set_merge_yaml" -> handleSetMergeYaml(app, arg.orEmpty(), extras?.getString("yaml").orEmpty())
                "get_global_merge_yaml" -> okBundle(JSONObject().put("yaml", app.settingsStore.settings.value.globalMergeYaml))
                "set_global_merge_yaml" -> handleSetGlobalMergeYaml(app, extras?.getString("yaml") ?: arg.orEmpty())
                "delete_profile" -> handleDeleteProfile(app, arg.orEmpty())
                "export_backup" -> {
                    val zip = app.profileRepository.exportAllBackupZip()
                    okBundle(JSONObject().put("path", zip.absolutePath).put("bytes", zip.length()))
                }
                "list_groups" -> handleListGroups(app)
                "select_proxy" -> handleSelectProxy(app, arg.orEmpty(), extras)
                "test_group" -> handleTestGroup(app, arg.orEmpty())
                "test_proxy" -> {
                    val node = arg.orEmpty().trim()
                    if (node.isEmpty()) errBundle("Missing proxy name")
                    else {
                        val ms = MihomoBridge.testProxyDelay(node, app.settingsStore.settings.value.delayTestUrl, 4500)
                        okBundle(JSONObject().put("proxy", node).put("delay", ms))
                    }
                }
                "list_providers" -> handleListProviders()
                "update_provider" -> handleUpdateProvider(arg.orEmpty(), extras?.getString("kind").orEmpty())
                "list_rules" -> handleListRules(app)
                "add_rule" -> handleAddRule(app, arg.orEmpty(), extras?.getString("position").orEmpty())
                "update_rule" -> handleUpdateRule(app, arg.orEmpty(), extras?.getString("rule").orEmpty(), extras?.getString("position").orEmpty())
                "toggle_rule" -> handleToggleRule(app, arg.orEmpty(), extras?.getBoolean("enabled", true) ?: true)
                "move_rule" -> handleMoveRule(app, arg.orEmpty(), extras?.getInt("to", 0) ?: 0)
                "remove_rule" -> handleRemoveRule(app, arg.orEmpty())
                "clear_rules" -> {
                    app.settingsStore.update { it.copy(customRules = emptyList()) }
                    if (Vpn233Service.status.value == VpnStatus.RUNNING) {
                        MihomoBridge.closeAllConnections()
                        Vpn233Service.reload(app)
                        waitUntilSettled(timeoutMs = 3500L)
                    }
                    okBundle(JSONObject().put("cleared", true))
                }
                "test_match" -> handleTestMatch(arg.orEmpty(), extras)
                "flush_dns" -> {
                    val err = MihomoBridge.flushDnsCache()
                    if (err.isBlank()) okBundle(JSONObject().put("flushed", true)) else errBundle(err)
                }
                "lan_addresses" -> {
                    val port = app.settingsStore.settings.value.mixedPort
                    val ips = NetworkHelpers.getLanIpv4Addresses()
                    val arr = JSONArray(ips.map { "$it:$port" })
                    okBundle(JSONObject().put("allowLan", app.settingsStore.settings.value.allowLan).put("port", port).put("addresses", arr))
                }
                "connections" -> handleConnections()
                "close_connection" -> okBundle(JSONObject().put("closed", MihomoBridge.closeConnection(arg.orEmpty())))
                "close_all_connections" -> {
                    MihomoBridge.closeAllConnections()
                    okBundle(JSONObject().put("closedAll", true))
                }
                "logs" -> handleLogs()
                "force_gc" -> {
                    MihomoBridge.forceGc()
                    val t = MihomoBridge.queryTraffic()
                    okBundle(JSONObject().put("gc", true).put("memoryBytes", t.memoryBytes).put("heapBytes", t.heapBytes))
                }
                "get_settings" -> okBundle(JSONObject(app.settingsStore.toOverrideJson()).apply {
                    val s = app.settingsStore.settings.value
                    put("tunStack", s.tunStack.coreValue)
                    put("allowLan", s.allowLan)
                    put("systemProxy", s.systemProxy)
                    put("mixedPort", s.mixedPort)
                    put("bypassPrivate", s.bypassPrivate)
                    put("dnsHijacking", s.dnsHijacking)
                    put("allowBypass", s.allowBypass)
                    put("autoStartOnBoot", s.autoStartOnBoot)
                    put("showTrafficInNotification", s.showTrafficInNotification)
                    put("disconnectOnNetworkChange", s.disconnectOnNetworkChange)
                    put("language", s.language.code)
                    put("hasGlobalMerge", s.globalMergeYaml.isNotBlank())
                    put("delayTestUrl", s.delayTestUrl)
                    put("themeMode", s.themeMode.name)
                    put("accessControlMode", s.accessControlMode.name)
                    put("accessControlPackages", JSONArray(s.accessControlPackages.toList()))
                })
                "set_setting" -> handleSetSetting(app, arg.orEmpty(), extras?.getString("value").orEmpty())
                "geo_status" -> {
                    val g = app.geoDatabaseManager.getStatus()
                    okBundle(JSONObject().apply {
                        put("geoIpBytes", g.geoIpBytes)
                        put("geoSiteBytes", g.geoSiteBytes)
                        put("asnBytes", g.asnBytes)
                        put("isGeoIpFull", g.isGeoIpFull)
                        put("isGeoSiteFull", g.isGeoSiteFull)
                        put("hasAsn", g.hasAsn)
                    })
                }
                "upgrade_geo" -> {
                    val g = runBlocking(Dispatchers.IO) {
                        app.geoDatabaseManager.upgradeToFullDatabases(runningProxyPort(app)) {}.getOrThrow()
                    }
                    if (Vpn233Service.status.value == VpnStatus.RUNNING) {
                        MihomoBridge.closeAllConnections()
                        Vpn233Service.reload(app)
                        waitUntilSettled(timeoutMs = 3500L)
                    }
                    okBundle(JSONObject().apply {
                        put("geoIpBytes", g.geoIpBytes)
                        put("geoSiteBytes", g.geoSiteBytes)
                        put("asnBytes", g.asnBytes)
                        put("isGeoIpFull", g.isGeoIpFull)
                        put("isGeoSiteFull", g.isGeoSiteFull)
                        put("hasAsn", g.hasAsn)
                    })
                }
                else -> errBundle("Unknown method: $method")
            }
        }.getOrElse { t ->
            errBundle(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun handleStatus(app: Vpn233Application): Bundle {
        val pm = app.getSystemService(PowerManager::class.java)
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(app.packageName) ?: false
        val t = Vpn233Service.traffic.value
        val active = app.profileRepository.getActiveProfile()
        val s = app.settingsStore.settings.value
        return okBundle(JSONObject().apply {
            put("vpnStatus", Vpn233Service.status.value.name)
            put("activeProfileId", active?.id ?: "")
            put("activeProfileName", active?.name ?: "")
            put("currentNode", Vpn233Service.currentNodeName.value)
            put("mode", s.routingMode.coreValue)
            put("tunStack", s.tunStack.coreValue)
            put("allowLan", s.allowLan)
            put("mixedPort", s.mixedPort)
            put("findProcessMode", s.findProcessMode)
            put("profilesCount", app.profileRepository.profiles.value.size)
            put("customRulesCount", s.customRules.size)
            put("ignoringBatteryOptimizations", ignoringBattery)
            put("coreVersion", MihomoBridge.coreVersion())
            put("lastError", Vpn233Service.lastError.value ?: "")
            put("traffic", JSONObject().apply {
                put("upNow", t.upNow)
                put("downNow", t.downNow)
                put("upTotal", t.upTotal)
                put("downTotal", t.downTotal)
                put("memoryBytes", t.memoryBytes)
                put("heapBytes", t.heapBytes)
                put("connCount", t.connCount)
            })
        })
    }

    private fun waitUntilSettled(timeoutMs: Long = 3500L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        Thread.sleep(60)
        while (System.currentTimeMillis() < deadline) {
            val st = Vpn233Service.status.value
            if (st != VpnStatus.STARTING && st != VpnStatus.STOPPING) return
            Thread.sleep(60)
        }
    }

    private fun handleStart(app: Vpn233Application): Bundle {
        if (Vpn233Service.status.value == VpnStatus.RUNNING) {
            return handleStatus(app)
        }
        if (app.profileRepository.getActiveProfile() == null) {
            return errBundle("NO_ACTIVE_PROFILE")
        }
        val prep = VpnService.prepare(app)
        if (prep != null) {
            return errBundle("VPN_PERMISSION_REQUIRED")
        }
        Vpn233Service.clearError()
        Vpn233Service.start(app)
        waitUntilSettled(timeoutMs = 4500L)
        val err = Vpn233Service.lastError.value
        if (!err.isNullOrBlank() && Vpn233Service.status.value != VpnStatus.RUNNING) {
            return errBundle(err)
        }
        return handleStatus(app)
    }

    private fun handleListProfiles(app: Vpn233Application): Bundle {
        val activeId = app.profileRepository.activeProfileId.value
        val arr = JSONArray()
        app.profileRepository.profiles.value.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("sourceType", p.sourceType.name)
                put("url", p.url)
                put("updatedAt", p.updatedAt)
                put("autoUpdateMinutes", p.autoUpdateMinutes)
                put("updateMode", p.updateMode.name)
                put("customUserAgent", p.customUserAgent)
                put("hasMergeYaml", p.hasMergeYaml)
                put("usedBytes", p.usedBytes)
                put("totalBytes", p.totalBytes)
                put("expireEpochSec", p.expireEpochSec)
                put("active", p.id == activeId)
            })
        }
        return okBundle(JSONObject().put("profiles", arr))
    }

    private fun runningProxyPort(app: Vpn233Application): Int {
        if (Vpn233Service.status.value != VpnStatus.RUNNING) return 0
        val p = app.settingsStore.settings.value.mixedPort
        return if (p in 1024..65535) p else 0
    }

    private fun handleImportUrl(app: Vpn233Application, url: String, name: String): Bundle {
        val prevActive = app.profileRepository.activeProfileId.value
        val port = runningProxyPort(app)
        val res = runBlocking(Dispatchers.IO) {
            app.profileRepository.importFromUrlWithResult(url, name, runningProxyPort = port)
        }
        val saveRes = res.getOrThrow()
        val item = saveRes.item
        app.profileRepository.setActiveProfile(item.id)
        val needReload = prevActive != item.id || saveRes.contentChanged
        if (Vpn233Service.status.value == VpnStatus.RUNNING && needReload) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().apply {
            put("id", item.id)
            put("name", item.name)
            put("created", saveRes.created)
            put("contentChanged", saveRes.contentChanged)
            put("profilesCount", app.profileRepository.profiles.value.size)
        })
    }

    private fun readRawOrFile(rawOrPath: String): String =
        if (rawOrPath.startsWith("/") && File(rawOrPath).exists()) {
            File(rawOrPath).readText(Charsets.UTF_8)
        } else {
            rawOrPath
        }

    private fun handleImportYaml(app: Vpn233Application, rawOrPath: String, extras: Bundle?): Bundle {
        val content = readRawOrFile(rawOrPath)
        val metaRaw = extras?.getString("meta").orEmpty().let(::readRawOrFile).trim()
        val metaObj = if (metaRaw.startsWith("{")) runCatching { JSONObject(metaRaw) }.getOrNull() else null
        val name = metaObj?.optString("name").orEmpty().ifBlank { extras?.getString("name").orEmpty() }
        val url = metaObj?.optString("url").orEmpty().ifBlank { extras?.getString("url").orEmpty() }
        val autoMins = metaObj?.optInt("autoUpdateMinutes", -1) ?: (extras?.getInt("autoUpdateMinutes", -1) ?: -1)
        val upBytes = metaObj?.optLong("uploadBytes", -1L) ?: -1L
        val dnBytes = metaObj?.optLong("downloadBytes", -1L) ?: -1L
        val totBytes = metaObj?.optLong("totalBytes", -1L) ?: -1L
        val expSec = metaObj?.optLong("expireEpochSec", -1L) ?: -1L
        val prevActive = app.profileRepository.activeProfileId.value
        val res = runBlocking(Dispatchers.IO) {
            app.profileRepository.saveYamlContentWithResult(
                id = null,
                name = name.ifBlank { "ADB导入配置" },
                yamlContent = content,
                sourceType = if (url.isNotBlank()) ProfileSourceType.URL else ProfileSourceType.LOCAL,
                url = url,
                autoUpdateMinutes = autoMins,
                uploadBytes = upBytes,
                downloadBytes = dnBytes,
                totalBytes = totBytes,
                expireEpochSec = expSec
            )
        }
        val saveRes = res.getOrThrow()
        val item = saveRes.item
        metaObj?.optJSONObject("selected")?.let { selObj ->
            val keys = selObj.keys()
            while (keys.hasNext()) {
                val grp = keys.next()
                val node = selObj.optString(grp)
                if (grp.isNotBlank() && node.isNotBlank()) {
                    app.settingsStore.setSelectedNode(item.id, grp, node)
                }
            }
        }
        app.profileRepository.setActiveProfile(item.id)
        val needReload = prevActive != item.id || saveRes.contentChanged
        if (Vpn233Service.status.value == VpnStatus.RUNNING && needReload) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().apply {
            put("id", item.id)
            put("name", item.name)
            put("created", saveRes.created)
            put("contentChanged", saveRes.contentChanged)
            put("profilesCount", app.profileRepository.profiles.value.size)
        })
    }

    private fun handleActivateProfile(app: Vpn233Application, idOrName: String, force: Boolean): Bundle {
        val item = app.profileRepository.findByIdOrName(idOrName)
            ?: return errBundle("Profile not found: $idOrName")
        val changed = app.profileRepository.setActiveProfile(item.id)
        var closedConnections = false
        if (Vpn233Service.status.value == VpnStatus.RUNNING && (changed || force)) {
            MihomoBridge.closeAllConnections()
            closedConnections = true
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 4000L)
        }
        return okBundle(JSONObject().apply {
            put("id", item.id)
            put("name", item.name)
            put("changed", changed)
            put("closedConnections", closedConnections)
            put("vpnStatus", Vpn233Service.status.value.name)
        })
    }

    private fun handleRenameProfile(app: Vpn233Application, idOrName: String, newName: String): Bundle {
        val item = app.profileRepository.findByIdOrName(idOrName)
            ?: return errBundle("Profile not found: $idOrName")
        if (newName.isBlank()) return errBundle("Empty name")
        app.profileRepository.renameProfile(item.id, newName)
        return okBundle(JSONObject().put("id", item.id).put("name", newName.trim()))
    }

    private fun handleUpdateProfile(app: Vpn233Application, idOrName: String): Bundle {
        val targets = if (idOrName.isBlank() || idOrName.equals("all", true)) {
            app.profileRepository.profiles.value.filter { it.url.isNotBlank() }
        } else {
            val found = app.profileRepository.findByIdOrName(idOrName)
                ?: return errBundle("Profile not found: $idOrName")
            if (found.url.isBlank()) return errBundle("Profile has no URL: ${found.name}")
            listOf(found)
        }
        val updatedArr = JSONArray()
        var activeChanged = false
        val activeId = app.profileRepository.activeProfileId.value
        val port = runningProxyPort(app)
        runBlocking(Dispatchers.IO) {
            targets.forEach { p ->
                val saveRes = app.profileRepository.importFromUrlWithResult(p.url, p.name, p.id, port).getOrThrow()
                if (p.id == activeId && saveRes.contentChanged) activeChanged = true
                updatedArr.put(JSONObject().apply {
                    put("id", saveRes.item.id)
                    put("name", saveRes.item.name)
                    put("contentChanged", saveRes.contentChanged)
                })
            }
        }
        if (activeChanged && Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().put("updated", updatedArr))
    }

    private fun handleSetProfileOptions(app: Vpn233Application, idOrName: String, extras: Bundle?): Bundle {
        val item = app.profileRepository.findByIdOrName(idOrName)
            ?: return errBundle("Profile not found: $idOrName")
        val mins = extras?.getInt("autoUpdateMinutes", item.autoUpdateMinutes) ?: item.autoUpdateMinutes
        val mode = extras?.getString("updateMode")?.let { ProfileUpdateMode.fromName(it) } ?: item.updateMode
        val ua = extras?.getString("ua") ?: item.customUserAgent
        val updated = app.profileRepository.setProfileUpdateOptions(item.id, mins, mode, ua) ?: item
        return okBundle(JSONObject().apply {
            put("id", updated.id)
            put("name", updated.name)
            put("autoUpdateMinutes", updated.autoUpdateMinutes)
            put("updateMode", updated.updateMode.name)
            put("customUserAgent", updated.customUserAgent)
        })
    }

    private fun handleSetMergeYaml(app: Vpn233Application, idOrName: String, rawOrPath: String): Bundle {
        val item = app.profileRepository.findByIdOrName(idOrName)
            ?: return errBundle("Profile not found: $idOrName")
        val content = readRawOrFile(rawOrPath)
        val saved = app.profileRepository.saveMergeYaml(item.id, content) { m ->
            app.settingsStore.toOverrideJson(app.settingsStore.settings.value, m)
        }.getOrThrow()
        if (app.profileRepository.activeProfileId.value == saved.id && Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().put("id", saved.id).put("name", saved.name).put("hasMergeYaml", saved.hasMergeYaml))
    }

    private fun handleSetGlobalMergeYaml(app: Vpn233Application, rawOrPath: String): Bundle {
        val trimmed = readRawOrFile(rawOrPath).trim()
        app.profileRepository.validateGlobalMergeYaml(trimmed) { gm ->
            val s = app.settingsStore.settings.value.copy(globalMergeYaml = gm)
            val activeMerge = app.profileRepository.getActiveProfile()?.let { app.profileRepository.readMergeYaml(it.id) }.orEmpty()
            app.settingsStore.toOverrideJson(s, activeMerge)
        }.getOrThrow()
        app.settingsStore.update { it.copy(globalMergeYaml = trimmed) }
        if (Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().put("hasGlobalMerge", trimmed.isNotEmpty()).put("length", trimmed.length))
    }

    private fun handleTestMatch(host: String, extras: Bundle?): Bundle {
        if (Vpn233Service.status.value != VpnStatus.RUNNING) {
            return errBundle("VPN_NOT_RUNNING")
        }
        val dstIp = extras?.getString("dstIp").orEmpty()
        val port = extras?.getInt("port", 443)?.toString() ?: "443"
        val network = extras?.getString("network").orEmpty().ifBlank { "tcp" }
        val proc = extras?.getString("process").orEmpty()
        val res = MihomoBridge.matchRule(host = host, dstIp = dstIp, dstPort = port, network = network, process = proc)
        return okBundle(JSONObject().apply {
            put("matched", res.matched)
            put("index", res.index)
            put("mode", res.mode)
            put("ruleType", res.ruleType)
            put("payload", res.payload)
            put("adapter", res.adapter)
            put("raw", res.raw)
            put("resolvedIp", res.resolvedIp)
            put("chain", JSONArray(res.chain))
        })
    }

    private fun handleDeleteProfile(app: Vpn233Application, idOrName: String): Bundle {
        val item = app.profileRepository.findByIdOrName(idOrName)
            ?: return errBundle("Profile not found: $idOrName")
        app.profileRepository.deleteProfile(item.id)
        if (Vpn233Service.status.value == VpnStatus.RUNNING && app.profileRepository.activeProfileId.value == null) {
            Vpn233Service.stop(app)
        }
        return okBundle(JSONObject().put("deleted", item.id))
    }

    private fun handleListGroups(app: Vpn233Application): Bundle {
        val (mode, groups) = MihomoBridge.queryGroups(app.settingsStore.settings.value.delayTestUrl)
        val gArr = JSONArray()
        groups.forEach { g ->
            val pArr = JSONArray()
            g.proxies.forEach { n ->
                pArr.put(JSONObject().apply {
                    put("name", n.name)
                    put("type", n.type)
                    put("delay", n.delay)
                    put("isGroup", n.isGroup)
                    put("alive", n.alive)
                })
            }
            gArr.put(JSONObject().apply {
                put("name", g.name)
                put("type", g.type)
                put("now", g.now)
                put("leafNow", g.leafNow)
                put("selectable", g.selectable)
                put("proxies", pArr)
            })
        }
        return okBundle(JSONObject().put("mode", mode).put("groups", gArr))
    }

    private fun handleSelectProxy(app: Vpn233Application, arg: String, extras: Bundle?): Bundle {
        val extraGroup = extras?.getString("group").orEmpty().trim()
        val extraProxy = extras?.getString("proxy").orEmpty().trim()
        val (group, proxy) = when {
            extraProxy.isNotEmpty() -> (extraGroup.ifEmpty { arg.trim() }) to extraProxy
            arg.contains(',') -> arg.substringBefore(',').trim() to arg.substringAfter(',').trim()
            else -> arg.trim() to ""
        }
        if (group.isEmpty() || proxy.isEmpty()) {
            return errBundle("Provide group and proxy via --arg GROUP --extra proxy:s:NODE or --arg GROUP,NODE")
        }
        val prev = MihomoBridge.queryGroups(app.settingsStore.settings.value.delayTestUrl).second
            .firstOrNull { it.name == group }?.now.orEmpty()
        val ok = MihomoBridge.selectProxy(group, proxy)
        if (!ok) return errBundle("Failed to select $proxy in $group")
        app.profileRepository.activeProfileId.value?.let { pid ->
            app.settingsStore.setSelectedNode(pid, group, proxy)
        }
        Vpn233Service.refreshCurrentNodeName()
        Vpn233Service.refreshNotification(app)
        return okBundle(JSONObject().apply {
            put("group", group)
            put("previous", prev)
            put("selected", proxy)
            put("closedConnections", prev != proxy)
        })
    }

    private fun handleListProviders(): Bundle {
        val arr = JSONArray()
        MihomoBridge.queryProviders().forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("kind", p.kind)
                put("vehicleType", p.vehicleType)
                put("count", p.count)
                put("updatedAt", p.updatedAt)
            })
        }
        return okBundle(JSONObject().put("providers", arr))
    }

    private fun handleUpdateProvider(name: String, kindArg: String): Bundle {
        val list = MihomoBridge.queryProviders()
        val resolvedKind = kindArg.ifBlank { list.firstOrNull { it.name == name }?.kind ?: "Proxy" }
        val err = MihomoBridge.updateProvider(resolvedKind, name)
        return if (err.isBlank()) {
            okBundle(JSONObject().put("name", name).put("kind", resolvedKind).put("updated", true))
        } else {
            errBundle(err)
        }
    }

    private fun handleTestGroup(app: Vpn233Application, group: String): Bundle {
        val delays = MihomoBridge.testGroupDelay(group, app.settingsStore.settings.value.delayTestUrl, 4500)
        val mapObj = JSONObject()
        delays.forEach { (k, v) -> mapObj.put(k, v) }
        return okBundle(JSONObject().put("group", group).put("delays", mapObj))
    }

    private fun handleListRules(app: Vpn233Application): Bundle {
        val customArr = JSONArray()
        app.settingsStore.settings.value.customRules.forEach { r ->
            customArr.put(JSONObject().apply {
                put("id", r.id)
                put("ruleLine", r.ruleLine)
                put("enabled", r.enabled)
                put("position", r.position.name)
            })
        }
        val activeArr = JSONArray()
        if (Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.queryRules().forEach { r ->
                activeArr.put(JSONObject().apply {
                    put("index", r.index)
                    put("type", r.type)
                    put("payload", r.payload)
                    put("adapter", r.adapter)
                    put("raw", r.raw)
                })
            }
        }
        return okBundle(JSONObject().put("customRules", customArr).put("activeRules", activeArr))
    }

    private fun handleAddRule(app: Vpn233Application, ruleLine: String, posStr: String): Bundle {
        val trimmed = ruleLine.trim()
        if (trimmed.isEmpty()) return errBundle("Empty rule")
        val pos = runCatching { RulePosition.valueOf(posStr.uppercase()) }.getOrDefault(RulePosition.PREPEND)
        val entry = CustomRuleEntry(UUID.randomUUID().toString(), trimmed, true, pos)
        app.settingsStore.update { it.copy(customRules = listOf(entry) + it.customRules) }
        if (Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().put("id", entry.id).put("ruleLine", entry.ruleLine).put("position", pos.name))
    }

    private fun resolveRuleTargetId(app: Vpn233Application, idOrIndex: String): String? {
        val idx = idOrIndex.toIntOrNull()
        val list = app.settingsStore.settings.value.customRules
        return when {
            idx != null && idx in list.indices -> list[idx].id
            list.any { it.id == idOrIndex } -> idOrIndex
            else -> list.firstOrNull { it.ruleLine == idOrIndex }?.id
        }
    }

    private fun handleUpdateRule(app: Vpn233Application, idOrIndex: String, newRule: String, posStr: String): Bundle {
        val targetId = resolveRuleTargetId(app, idOrIndex) ?: return errBundle("Rule not found: $idOrIndex")
        val trimmed = newRule.trim()
        if (trimmed.isEmpty()) return errBundle("Empty rule")
        app.settingsStore.update { s ->
            s.copy(customRules = s.customRules.map { r ->
                if (r.id == targetId) {
                    val pos = if (posStr.isBlank()) r.position else runCatching {
                        RulePosition.valueOf(posStr.uppercase())
                    }.getOrDefault(r.position)
                    r.copy(ruleLine = trimmed, position = pos)
                } else r
            })
        }
        if (Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().put("id", targetId).put("ruleLine", trimmed))
    }

    private fun handleToggleRule(app: Vpn233Application, idOrIndex: String, enabled: Boolean): Bundle {
        val targetId = resolveRuleTargetId(app, idOrIndex) ?: idOrIndex
        app.settingsStore.update { s ->
            s.copy(customRules = s.customRules.map { if (it.id == targetId) it.copy(enabled = enabled) else it })
        }
        if (Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().put("id", targetId).put("enabled", enabled))
    }

    private fun handleMoveRule(app: Vpn233Application, idOrIndex: String, toIdxRaw: Int): Bundle {
        val targetId = resolveRuleTargetId(app, idOrIndex) ?: return errBundle("Rule not found: $idOrIndex")
        app.settingsStore.update { s ->
            val list = s.customRules.toMutableList()
            val fromIdx = list.indexOfFirst { it.id == targetId }
            if (fromIdx >= 0 && list.isNotEmpty()) {
                val toIdx = toIdxRaw.coerceIn(0, list.lastIndex)
                val item = list.removeAt(fromIdx)
                list.add(toIdx, item)
            }
            s.copy(customRules = list)
        }
        if (Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().put("id", targetId).put("to", toIdxRaw))
    }

    private fun handleRemoveRule(app: Vpn233Application, key: String): Bundle {
        val idx = key.toIntOrNull()
        app.settingsStore.update { s ->
            val filtered = if (idx != null && idx in s.customRules.indices) {
                s.customRules.filterIndexed { i, _ -> i != idx }
            } else {
                s.customRules.filterNot { it.id == key || it.ruleLine == key }
            }
            s.copy(customRules = filtered)
        }
        if (Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().put("removed", key))
    }

    private fun handleConnections(): Bundle {
        val arr = JSONArray()
        MihomoBridge.queryConnections().forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("host", c.host)
                put("dstIp", c.dstIp)
                put("dstPort", c.dstPort)
                put("network", c.network)
                put("rule", c.rule)
                put("chains", JSONArray(c.chains))
                put("upload", c.upload)
                put("download", c.download)
                put("process", c.process)
            })
        }
        return okBundle(JSONObject().put("connections", arr))
    }

    private fun handleLogs(): Bundle {
        val arr = JSONArray()
        MihomoBridge.logs.value.forEach { l ->
            arr.put(JSONObject().apply {
                put("id", l.id)
                put("time", l.timeText)
                put("level", l.level)
                put("message", l.message)
            })
        }
        return okBundle(JSONObject().put("logs", arr))
    }

    private fun handleSetSetting(app: Vpn233Application, key: String, value: String): Bundle {
        val b = value.equals("true", true) || value == "1"
        val reloadNeeded = key !in setOf("themeMode", "autoStartOnBoot", "showTrafficInNotification", "delayTestUrl", "language", "disconnectOnNetworkChange")
        app.settingsStore.update { s ->
            when (key) {
                "tunStack" -> s.copy(tunStack = TunStackMode.fromCore(value))
                "allowLan" -> s.copy(allowLan = b)
                "systemProxy" -> s.copy(systemProxy = b)
                "mixedPort" -> s.copy(mixedPort = value.toIntOrNull()?.coerceIn(0, 65535) ?: s.mixedPort)
                "allowIpv6" -> s.copy(allowIpv6 = b)
                "bypassPrivate" -> s.copy(bypassPrivate = b)
                "dnsHijacking" -> s.copy(dnsHijacking = b)
                "allowBypass" -> s.copy(allowBypass = b)
                "appendSystemDns" -> s.copy(appendSystemDns = b)
                "forceDnsMapping" -> s.copy(forceDnsMapping = b)
                "sniffing" -> s.copy(sniffing = b)
                "overrideDestination" -> s.copy(overrideDestination = b)
                "unifiedDelay" -> s.copy(unifiedDelay = b)
                "tcpConcurrent" -> s.copy(tcpConcurrent = b)
                "geodataMode" -> s.copy(geodataMode = b)
                "findProcessMode" -> s.copy(findProcessMode = value)
                "logLevel" -> s.copy(logLevel = value)
                "accessControlMode" -> s.copy(accessControlMode = AccessControlMode.valueOf(value.uppercase()))
                "accessControlPackages" -> s.copy(
                    accessControlPackages = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                )
                "customDnsNameservers" -> s.copy(
                    customDnsNameservers = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                )
                "fakeIpFilterExtra" -> s.copy(
                    fakeIpFilterExtra = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                )
                "themeMode" -> s.copy(themeMode = ThemeMode.valueOf(value.uppercase()))
                "language" -> s.copy(language = AppLanguage.fromCode(value))
                "disconnectOnNetworkChange" -> s.copy(disconnectOnNetworkChange = b)
                "autoStartOnBoot" -> s.copy(autoStartOnBoot = b)
                "showTrafficInNotification" -> s.copy(showTrafficInNotification = b)
                "delayTestUrl" -> s.copy(delayTestUrl = value)
                else -> throw IllegalArgumentException("Unknown setting key: $key")
            }
        }
        if (key == "logLevel") MihomoBridge.setLogLevel(value)
        if (key == "showTrafficInNotification") Vpn233Service.refreshNotification(app)
        if (reloadNeeded && Vpn233Service.status.value == VpnStatus.RUNNING) {
            MihomoBridge.closeAllConnections()
            Vpn233Service.reload(app)
            waitUntilSettled(timeoutMs = 3500L)
        }
        return okBundle(JSONObject().put("key", key).put("value", value).put("reloaded", reloadNeeded))
    }

    private fun okBundle(json: JSONObject): Bundle = Bundle().apply {
        putBoolean("ok", true)
        putString("json", json.toString())
    }

    private fun errBundle(message: String): Bundle = Bundle().apply {
        putBoolean("ok", false)
        putString("error", message)
        putString("json", JSONObject().put("ok", false).put("error", message).toString())
    }

    override fun query(uri: Uri, p1: Array<out String>?, p2: String?, p3: Array<out String>?, p4: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
