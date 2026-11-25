package com.neko233.vpn233.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.core.content.FileProvider
import com.neko233.vpn233.Vpn233Application
import com.neko233.vpn233.core.MihomoBridge
import com.neko233.vpn233.data.AppSettings
import com.neko233.vpn233.data.ConnectionItem
import com.neko233.vpn233.data.CustomRuleEntry
import com.neko233.vpn233.data.GeoDbStatus
import com.neko233.vpn233.data.InstalledAppInfo
import com.neko233.vpn233.data.ProfileItem
import com.neko233.vpn233.data.ProfileSourceType
import com.neko233.vpn233.data.ProviderItem
import com.neko233.vpn233.data.ProxyGroupUi
import com.neko233.vpn233.data.RoutingMode
import com.neko233.vpn233.data.RuleItem
import com.neko233.vpn233.data.RulePosition
import com.neko233.vpn233.data.VpnStatus
import com.neko233.vpn233.service.Vpn233Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val vpnApp = app as Vpn233Application

    val settings: StateFlow<AppSettings> = vpnApp.settingsStore.settings
    val profiles: StateFlow<List<ProfileItem>> = vpnApp.profileRepository.profiles
    val activeProfileId: StateFlow<String?> = vpnApp.profileRepository.activeProfileId
    val vpnStatus: StateFlow<VpnStatus> = Vpn233Service.status
    val traffic = Vpn233Service.traffic
    val speedHistory = Vpn233Service.speedHistory
    val currentNodeName = Vpn233Service.currentNodeName
    val lastVpnError = Vpn233Service.lastError
    val coreLogs = MihomoBridge.logs

    private val _groups = MutableStateFlow<List<ProxyGroupUi>>(emptyList())
    val groups: StateFlow<List<ProxyGroupUi>> = _groups.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderItem>>(emptyList())
    val providers: StateFlow<List<ProviderItem>> = _providers.asStateFlow()

    private val _connections = MutableStateFlow<List<ConnectionItem>>(emptyList())
    val connections: StateFlow<List<ConnectionItem>> = _connections.asStateFlow()

    private val _activeRules = MutableStateFlow<List<RuleItem>>(emptyList())
    val activeRules: StateFlow<List<RuleItem>> = _activeRules.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _geoDbStatus = MutableStateFlow(vpnApp.geoDatabaseManager.getStatus())
    val geoDbStatus: StateFlow<GeoDbStatus> = _geoDbStatus.asStateFlow()

    private val _busyMessage = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busyMessage.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _testingGroup = MutableStateFlow<String?>(null)
    val testingGroup: StateFlow<String?> = _testingGroup.asStateFlow()

    val coreVersion: String by lazy { MihomoBridge.coreVersion() }

    init {
        scanDropInImports()
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun showToast(msg: String) {
        _toastMessage.value = msg
    }

    fun scanDropInImports() {
        scope.launch {
            val imported = vpnApp.profileRepository.scanDropInImports()
            if (imported.isNotEmpty()) {
                _toastMessage.value = "已自动导入 ${imported.size} 个配置文件"
            }
        }
    }

    fun setRoutingMode(mode: RoutingMode) {
        vpnApp.settingsStore.update { it.copy(routingMode = mode) }
        if (vpnStatus.value == VpnStatus.RUNNING) {
            scope.launch(Dispatchers.IO) {
                MihomoBridge.setMode(mode.coreValue)
                Vpn233Service.refreshCurrentNodeName()
                refreshGroups()
            }
        }
    }

    fun updateSettings(reloadIfRunning: Boolean = false, transform: (AppSettings) -> AppSettings) {
        val updated = vpnApp.settingsStore.update(transform)
        MihomoBridge.setLogLevel(updated.logLevel)
        if (reloadIfRunning && vpnStatus.value == VpnStatus.RUNNING) {
            Vpn233Service.reload(app)
        }
    }

    fun refreshGroups() {
        if (vpnStatus.value != VpnStatus.RUNNING) {
            _groups.value = emptyList()
            _providers.value = emptyList()
            return
        }
        scope.launch(Dispatchers.IO) {
            val (_, list) = MihomoBridge.queryGroups(settings.value.delayTestUrl)
            _groups.value = list
            _providers.value = MihomoBridge.queryProviders()
        }
    }

    fun selectProxy(groupName: String, nodeName: String) {
        scope.launch(Dispatchers.IO) {
            val ok = MihomoBridge.selectProxy(groupName, nodeName)
            if (ok) {
                activeProfileId.value?.let { pid ->
                    vpnApp.settingsStore.setSelectedNode(pid, groupName, nodeName)
                }
                Vpn233Service.refreshCurrentNodeName()
                refreshGroups()
            }
        }
    }

    fun testGroupDelay(groupName: String) {
        if (_testingGroup.value != null || vpnStatus.value != VpnStatus.RUNNING) return
        _testingGroup.value = groupName
        scope.launch(Dispatchers.IO) {
            try {
                MihomoBridge.testGroupDelay(groupName, settings.value.delayTestUrl, 4500)
                refreshGroups()
            } finally {
                _testingGroup.value = null
            }
        }
    }

    fun testSingleProxy(proxyName: String) {
        if (vpnStatus.value != VpnStatus.RUNNING) return
        scope.launch(Dispatchers.IO) {
            val d = MihomoBridge.testProxyDelay(proxyName, settings.value.delayTestUrl, 4500)
            refreshGroups()
            _toastMessage.value = if (d > 0) "$proxyName: ${d}ms" else "$proxyName: 测试超时"
        }
    }

    fun updateProvider(kind: String, name: String) {
        scope.launch(Dispatchers.IO) {
            _busyMessage.value = "正在更新 $name..."
            try {
                val err = MihomoBridge.updateProvider(kind, name)
                refreshGroups()
                _toastMessage.value = if (err.isBlank()) "已更新 $name" else "更新失败: $err"
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun refreshConnections() {
        if (vpnStatus.value != VpnStatus.RUNNING) {
            _connections.value = emptyList()
            return
        }
        scope.launch(Dispatchers.IO) {
            _connections.value = MihomoBridge.queryConnections()
        }
    }

    fun closeConnection(id: String) {
        scope.launch(Dispatchers.IO) {
            MihomoBridge.closeConnection(id)
            refreshConnections()
        }
    }

    fun closeAllConnections() {
        scope.launch(Dispatchers.IO) {
            MihomoBridge.closeAllConnections()
            refreshConnections()
        }
    }

    fun refreshActiveRules() {
        if (vpnStatus.value != VpnStatus.RUNNING) {
            _activeRules.value = emptyList()
            return
        }
        scope.launch(Dispatchers.IO) {
            _activeRules.value = MihomoBridge.queryRules()
        }
    }

    fun addCustomRule(ruleLine: String, position: RulePosition = RulePosition.PREPEND) {
        val cleaned = ruleLine.trim()
        if (cleaned.isEmpty()) return
        updateSettings(reloadIfRunning = true) { s ->
            val entry = CustomRuleEntry(
                id = java.util.UUID.randomUUID().toString(),
                ruleLine = cleaned,
                enabled = true,
                position = position
            )
            s.copy(customRules = listOf(entry) + s.customRules)
        }
        _toastMessage.value = "已添加规则: $cleaned"
    }

    fun updateCustomRule(id: String, ruleLine: String, position: RulePosition) {
        val cleaned = ruleLine.trim()
        if (cleaned.isEmpty()) return
        updateSettings(reloadIfRunning = true) { s ->
            s.copy(customRules = s.customRules.map {
                if (it.id == id) it.copy(ruleLine = cleaned, position = position) else it
            })
        }
    }

    fun toggleCustomRule(id: String, enabled: Boolean) {
        updateSettings(reloadIfRunning = true) { s ->
            s.copy(customRules = s.customRules.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            })
        }
    }

    fun moveCustomRule(fromIdx: Int, toIdx: Int) {
        updateSettings(reloadIfRunning = true) { s ->
            val list = s.customRules.toMutableList()
            if (fromIdx in list.indices && toIdx in list.indices) {
                val item = list.removeAt(fromIdx)
                list.add(toIdx, item)
            }
            s.copy(customRules = list)
        }
    }

    fun deleteCustomRule(id: String) {
        updateSettings(reloadIfRunning = true) { s ->
            s.copy(customRules = s.customRules.filterNot { it.id == id })
        }
    }

    fun setLogStreaming(enabled: Boolean) {
        if (enabled) {
            if (settings.value.logLevel == "warning" || settings.value.logLevel == "error") {
                MihomoBridge.setLogLevel("info")
            }
        } else {
            MihomoBridge.setLogLevel(settings.value.logLevel)
        }
        MihomoBridge.setLogStreaming(enabled)
    }

    fun clearLogs() {
        MihomoBridge.clearLogs()
    }

    fun activateProfile(id: String) {
        vpnApp.profileRepository.setActiveProfile(id)
        if (vpnStatus.value == VpnStatus.RUNNING) {
            Vpn233Service.reload(app)
        }
    }

    fun importFromUrl(url: String, name: String) {
        scope.launch {
            _busyMessage.value = "正在拉取并校验配置..."
            try {
                val res = vpnApp.profileRepository.importFromUrl(url, name)
                res.onSuccess { item ->
                    vpnApp.profileRepository.setActiveProfile(item.id)
                    if (vpnStatus.value == VpnStatus.RUNNING) Vpn233Service.reload(app)
                    _toastMessage.value = "已导入配置「${item.name}」"
                }.onFailure { err ->
                    _toastMessage.value = err.message ?: "导入失败"
                }
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun updateProfile(item: ProfileItem) {
        if (item.url.isBlank()) return
        scope.launch {
            _busyMessage.value = "正在更新 ${item.name}..."
            try {
                val res = vpnApp.profileRepository.importFromUrl(item.url, item.name, item.id)
                res.onSuccess {
                    if (activeProfileId.value == item.id && vpnStatus.value == VpnStatus.RUNNING) {
                        Vpn233Service.reload(app)
                    }
                    _toastMessage.value = "配置「${item.name}」已更新"
                }.onFailure { err ->
                    _toastMessage.value = "更新失败: ${err.message}"
                }
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun updateAllUrlProfiles() {
        val urlProfiles = profiles.value.filter { it.url.isNotBlank() }
        if (urlProfiles.isEmpty()) return
        scope.launch {
            _busyMessage.value = "正在更新全部 ${urlProfiles.size} 个订阅..."
            var okCount = 0
            try {
                for (item in urlProfiles) {
                    val res = vpnApp.profileRepository.importFromUrl(item.url, item.name, item.id)
                    if (res.isSuccess) okCount++
                }
                if (vpnStatus.value == VpnStatus.RUNNING) {
                    Vpn233Service.reload(app)
                }
                _toastMessage.value = "已更新 $okCount/${urlProfiles.size} 个订阅"
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun saveYaml(id: String?, name: String, yaml: String, sourceType: ProfileSourceType = ProfileSourceType.LOCAL) {
        scope.launch {
            _busyMessage.value = "正在校验 YAML..."
            try {
                val res = vpnApp.profileRepository.saveYamlContent(id, name, yaml, sourceType)
                res.onSuccess { item ->
                    if (id == null) {
                        vpnApp.profileRepository.setActiveProfile(item.id)
                    }
                    if (activeProfileId.value == item.id && vpnStatus.value == VpnStatus.RUNNING) {
                        Vpn233Service.reload(app)
                    }
                    _toastMessage.value = "已保存「${item.name}」"
                }.onFailure { err ->
                    _toastMessage.value = err.message ?: "保存失败"
                }
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun importFromUri(uri: Uri) {
        scope.launch {
            _busyMessage.value = "正在解析文件..."
            try {
                val res = vpnApp.profileRepository.importFromUri(uri)
                res.onSuccess { items ->
                    items.firstOrNull()?.let { vpnApp.profileRepository.setActiveProfile(it.id) }
                    if (vpnStatus.value == VpnStatus.RUNNING) Vpn233Service.reload(app)
                    _toastMessage.value = "成功导入 ${items.size} 个配置"
                }.onFailure { err ->
                    _toastMessage.value = err.message ?: "导入失败"
                }
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun renameProfile(id: String, name: String) {
        vpnApp.profileRepository.renameProfile(id, name)
    }

    fun deleteProfile(id: String) {
        vpnApp.profileRepository.deleteProfile(id)
        if (vpnStatus.value == VpnStatus.RUNNING && activeProfileId.value == null) {
            Vpn233Service.stop(app)
        }
    }

    fun readProfileYaml(id: String): String = vpnApp.profileRepository.readProfileYaml(id)

    fun shareProfileYaml(item: ProfileItem) {
        scope.launch(Dispatchers.IO) {
            val outDir = File(app.cacheDir, "exports").apply { mkdirs() }
            val safeName = item.name.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
            val file = File(outDir, "$safeName.yaml")
            file.writeText(vpnApp.profileRepository.readProfileYaml(item.id), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            withContext(Dispatchers.Main) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(Intent.createChooser(send, "导出配置 ${item.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun exportAllBackup() {
        scope.launch(Dispatchers.IO) {
            val file = vpnApp.profileRepository.exportAllBackupZip()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            withContext(Dispatchers.Main) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(Intent.createChooser(send, "导出 vpn233 备份").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun loadInstalledAppsIfNeeded() {
        if (_installedApps.value.isNotEmpty()) return
        scope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { it.packageName != app.packageName }
                .map { info ->
                    val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
                    InstalledAppInfo(info.packageName, label, isSys)
                }
                .sortedWith(compareBy<InstalledAppInfo> { it.isSystem }.thenBy { it.label.lowercase() })
            _installedApps.value = apps
        }
    }

    fun upgradeGeoDatabases() {
        scope.launch {
            _busyMessage.value = "正在更新 Geo 数据库..."
            try {
                val res = vpnApp.geoDatabaseManager.upgradeToFullDatabases { step ->
                    _busyMessage.value = step
                }
                res.onSuccess { st ->
                    _geoDbStatus.value = st
                    _toastMessage.value = "已升级为完整版 GeoIP / GeoSite / ASN 数据库"
                }.onFailure { err ->
                    _toastMessage.value = "升级失败: ${err.message}"
                }
            } finally {
                _busyMessage.value = null
            }
        }
    }

    fun resetGeoDatabasesToLite() {
        _geoDbStatus.value = vpnApp.geoDatabaseManager.resetToLiteDatabases()
        _toastMessage.value = "已恢复内置精简版 Geo 数据库"
    }
}
