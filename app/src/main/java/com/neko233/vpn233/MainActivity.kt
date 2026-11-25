package com.neko233.vpn233

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.neko233.vpn233.data.VpnStatus
import com.neko233.vpn233.service.Vpn233Service
import com.neko233.vpn233.ui.MainViewModel
import com.neko233.vpn233.ui.components.AppIcons
import com.neko233.vpn233.ui.screens.DashboardScreen
import com.neko233.vpn233.ui.screens.MonitorDialog
import com.neko233.vpn233.ui.screens.ProfilesScreen
import com.neko233.vpn233.ui.screens.ProxiesScreen
import com.neko233.vpn233.ui.screens.RulesDialog
import com.neko233.vpn233.ui.screens.SettingsScreen
import com.neko233.vpn233.ui.theme.LocalVpnPalette
import com.neko233.vpn233.ui.theme.Vpn233Theme

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("连接", AppIcons.ShieldBolt),
    PROXIES("节点", AppIcons.Nodes),
    PROFILES("配置", AppIcons.ConfigDoc),
    SETTINGS("设置", AppIcons.SettingsGear)
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private val deepLinkUrl = mutableStateOf<String?>(null)
    private val requestedTab = mutableStateOf<MainTab?>(null)
    private val requestedOpenRules = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        viewModel = MainViewModel(application)
        handleIncomingIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsState()
            val isDark = when (settings.themeMode) {
                com.neko233.vpn233.data.ThemeMode.DARK -> true
                com.neko233.vpn233.data.ThemeMode.LIGHT -> false
                com.neko233.vpn233.data.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    val ctrl = WindowCompat.getInsetsController(window, view)
                    ctrl.isAppearanceLightStatusBars = !isDark
                    ctrl.isAppearanceLightNavigationBars = !isDark
                }
            }
            Vpn233Theme(themeMode = settings.themeMode) {
                Vpn233AppRoot(
                    viewModel = viewModel,
                    prefillUrl = deepLinkUrl.value,
                    requestedTab = requestedTab.value,
                    requestedOpenRules = requestedOpenRules.value,
                    onConsumePrefillUrl = { deepLinkUrl.value = null },
                    onConsumeNavRequest = {
                        requestedTab.value = null
                        requestedOpenRules.value = false
                    },
                    onRequestStartVpn = { startWithPermission -> startWithPermission() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Vpn233Service.setUiVisible(this, true)
    }

    override fun onResume() {
        super.onResume()
        viewModel.scanDropInImports()
        viewModel.refreshGroups()
    }

    override fun onStop() {
        Vpn233Service.setUiVisible(this, false)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            com.neko233.vpn233.core.MihomoBridge.setIdleMode(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent?.getStringExtra("tab")?.let { tabName ->
            requestedTab.value = when (tabName.lowercase()) {
                "home" -> MainTab.HOME
                "proxies" -> MainTab.PROXIES
                "profiles" -> MainTab.PROFILES
                "settings" -> MainTab.SETTINGS
                else -> null
            }
        }
        if (intent?.getBooleanExtra("open_rules", false) == true) {
            requestedOpenRules.value = true
        }
        val data: Uri = intent?.data ?: return
        if (data.host == "install-config") {
            val url = data.getQueryParameter("url")
            if (!url.isNullOrBlank()) {
                deepLinkUrl.value = url
            }
        }
    }
}

@Composable
private fun Vpn233AppRoot(
    viewModel: MainViewModel,
    prefillUrl: String?,
    requestedTab: MainTab?,
    requestedOpenRules: Boolean,
    onConsumePrefillUrl: () -> Unit,
    onConsumeNavRequest: () -> Unit,
    onRequestStartVpn: (() -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = LocalVpnPalette.current

    val settings by viewModel.settings.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val vpnStatus by viewModel.vpnStatus.collectAsState()
    val traffic by viewModel.traffic.collectAsState()
    val speedHistory by viewModel.speedHistory.collectAsState()
    val currentNodeName by viewModel.currentNodeName.collectAsState()
    val lastError by viewModel.lastVpnError.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val connections by viewModel.connections.collectAsState()
    val activeRules by viewModel.activeRules.collectAsState()
    val logs by viewModel.coreLogs.collectAsState()
    val geoDbStatus by viewModel.geoDbStatus.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val busyMessage by viewModel.busyMessage.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val testingGroup by viewModel.testingGroup.collectAsState()

    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var showMonitor by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            Vpn233Service.start(context)
        } else {
            viewModel.showToast("未授予 VPN 权限")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val docPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFromUri(uri)
    }

    LaunchedEffect(prefillUrl) {
        if (!prefillUrl.isNullOrBlank()) {
            currentTab = MainTab.PROFILES
        }
    }

    LaunchedEffect(requestedTab, requestedOpenRules) {
        if (requestedTab != null) {
            currentTab = requestedTab
        }
        if (requestedOpenRules) {
            showRules = true
        }
        if (requestedTab != null || requestedOpenRules) {
            onConsumeNavRequest()
        }
    }

    LaunchedEffect(toastMessage) {
        val msg = toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearToast()
    }

    LaunchedEffect(vpnStatus) {
        if (vpnStatus == VpnStatus.RUNNING) {
            viewModel.refreshGroups()
        }
    }

    fun startVpnSafely() {
        Vpn233Service.clearError()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val prep = VpnService.prepare(context)
        if (prep != null) {
            vpnPermissionLauncher.launch(prep)
        } else {
            Vpn233Service.start(context)
        }
    }

    fun toggleVpn() {
        when (vpnStatus) {
            VpnStatus.RUNNING, VpnStatus.STARTING -> Vpn233Service.stop(context)
            VpnStatus.STOPPED -> onRequestStartVpn { startVpnSafely() }
            VpnStatus.STOPPING -> Unit
        }
    }

    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }

    Scaffold(
        containerColor = p.bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                color = p.card,
                border = BorderStroke(1.dp, p.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    MainTab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) p.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { currentTab = tab }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = if (selected) p.accent else p.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = tab.label,
                                color = if (selected) p.accent else p.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(innerPadding)
        ) {
            when (currentTab) {
                MainTab.HOME -> DashboardScreen(
                    vpnStatus = vpnStatus,
                    settings = settings,
                    activeProfile = activeProfile,
                    currentNodeName = currentNodeName,
                    groups = groups,
                    traffic = traffic,
                    speedHistory = speedHistory,
                    coreVersion = viewModel.coreVersion,
                    lastError = lastError,
                    onToggleVpn = ::toggleVpn,
                    onChangeMode = viewModel::setRoutingMode,
                    onOpenMonitor = { showMonitor = true },
                    onOpenRules = { showRules = true },
                    onGoProxies = { currentTab = MainTab.PROXIES },
                    onGoProfiles = { currentTab = MainTab.PROFILES },
                    onQuickTest = {
                        groups.firstOrNull()?.let { viewModel.testGroupDelay(it.name) }
                    }
                )

                MainTab.PROXIES -> ProxiesScreen(
                    vpnStatus = vpnStatus,
                    groups = groups,
                    providers = providers,
                    testingGroup = testingGroup,
                    onRefresh = viewModel::refreshGroups,
                    onSelectProxy = viewModel::selectProxy,
                    onTestGroup = viewModel::testGroupDelay,
                    onTestSingle = viewModel::testSingleProxy,
                    onUpdateProvider = viewModel::updateProvider,
                    onStartVpn = ::startVpnSafely
                )

                MainTab.PROFILES -> ProfilesScreen(
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    prefillUrl = prefillUrl,
                    onConsumePrefillUrl = onConsumePrefillUrl,
                    onActivateProfile = viewModel::activateProfile,
                    onImportUrl = viewModel::importFromUrl,
                    onSaveYaml = viewModel::saveYaml,
                    onUpdateProfile = viewModel::updateProfile,
                    onUpdateAll = viewModel::updateAllUrlProfiles,
                    onRenameProfile = viewModel::renameProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onShareProfile = viewModel::shareProfileYaml,
                    onPickDocument = {
                        docPickerLauncher.launch(arrayOf("*/*"))
                    },
                    onToast = viewModel::showToast,
                    readYaml = viewModel::readProfileYaml
                )

                MainTab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    geoDbStatus = geoDbStatus,
                    installedApps = installedApps,
                    coreVersion = viewModel.coreVersion,
                    onUpdateSettings = viewModel::updateSettings,
                    onLoadApps = viewModel::loadInstalledAppsIfNeeded,
                    onOpenRules = { showRules = true },
                    onUpgradeGeoDb = viewModel::upgradeGeoDatabases,
                    onResetGeoDb = viewModel::resetGeoDatabasesToLite,
                    onExportAllBackup = viewModel::exportAllBackup,
                    onToast = viewModel::showToast
                )
            }

            if (busyMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(p.bg.copy(alpha = 0.72f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = p.card,
                        border = BorderStroke(1.dp, p.border)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = p.accent
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(busyMessage.orEmpty(), color = p.textPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    if (showMonitor) {
        MonitorDialog(
            connections = connections,
            logs = logs,
            onRefreshConnections = viewModel::refreshConnections,
            onCloseConnection = viewModel::closeConnection,
            onCloseAllConnections = viewModel::closeAllConnections,
            onQuickAddRule = { rule -> viewModel.addCustomRule(rule) },
            onSetLogStreaming = viewModel::setLogStreaming,
            onClearLogs = viewModel::clearLogs,
            onToast = viewModel::showToast,
            onDismiss = { showMonitor = false }
        )
    }

    if (showRules) {
        RulesDialog(
            customRules = settings.customRules,
            activeRules = activeRules,
            groups = groups,
            onRefreshActiveRules = viewModel::refreshActiveRules,
            onAddRule = viewModel::addCustomRule,
            onUpdateRule = viewModel::updateCustomRule,
            onToggleRule = viewModel::toggleCustomRule,
            onMoveRule = viewModel::moveCustomRule,
            onDeleteRule = viewModel::deleteCustomRule,
            onDismiss = { showRules = false }
        )
    }
}
