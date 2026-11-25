package com.neko233.vpn233.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.service.quicksettings.TileService
import com.neko233.vpn233.MainActivity
import com.neko233.vpn233.R
import com.neko233.vpn233.Vpn233Application
import com.neko233.vpn233.core.MihomoBridge
import com.neko233.vpn233.data.AccessControlMode
import com.neko233.vpn233.data.RoutingMode
import com.neko233.vpn233.data.TrafficStats
import com.neko233.vpn233.data.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TimeZone

class Vpn233Service : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunPfd: ParcelFileDescriptor? = null
    private var monitorJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var receiversRegistered = false

    @Volatile
    private var screenInteractive = true
    private var lastNotifiedText = ""

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_TIMEZONE_CHANGED -> syncTimezone()
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    MihomoBridge.setIdleMode(true)
                    stopMonitorLoop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    val logLevel = Vpn233Application.instance.settingsStore.settings.value.logLevel
                    MihomoBridge.setLogLevel(logLevel)
                    if (_status.value == VpnStatus.RUNNING) {
                        ensureMonitorLoop()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        MihomoBridge.socketProtector = { fd -> protect(fd) }
        val pm = getSystemService(PowerManager::class.java)
        screenInteractive = pm?.isInteractive ?: true
        registerSystemObservers()
        syncTimezone()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnInternal()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                serviceScope.launch { startOrReloadVpn() }
                return START_STICKY
            }
            ACTION_REFRESH_MONITOR -> {
                if (_status.value == VpnStatus.RUNNING) {
                    ensureMonitorLoop()
                }
                return START_STICKY
            }
            else -> {
                postForegroundNotification("vpn233 正在启动...", "准备核心配置")
                serviceScope.launch { startOrReloadVpn() }
                return START_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep VPN alive when user swipes app from Recents, but release UI memory
        if (_status.value == VpnStatus.RUNNING) {
            MihomoBridge.setIdleMode(true)
        }
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun startOrReloadVpn() {
        if (_status.value == VpnStatus.STARTING || _status.value == VpnStatus.STOPPING) return
        _status.value = VpnStatus.STARTING
        _lastError.value = null
        requestTileUpdate()

        val app = Vpn233Application.instance
        val settings = app.settingsStore.settings.value
        val activeProfile = app.profileRepository.getActiveProfile()
        val profileDir = app.profileRepository.getActiveProfileDir()
        if (activeProfile == null || profileDir == null) {
            _lastError.value = "请先导入并选择一个配置"
            _status.value = VpnStatus.STOPPED
            stopForegroundCompat()
            stopSelf()
            return
        }

        try {
            app.geoDatabaseManager.ensureBootstrapAssets()
            MihomoBridge.setLogLevel(settings.logLevel)

            closeTunOnly()
            MihomoBridge.stopMixed()

            val overrideJson = app.settingsStore.toOverrideJson(settings)
            val loadErr = MihomoBridge.loadConfig(profileDir.absolutePath, overrideJson)
            if (loadErr.isNotBlank()) {
                throw IllegalStateException(loadErr)
            }

            val savedSelections = app.settingsStore.getSelectedNodes(activeProfile.id)
            savedSelections.forEach { (group, node) ->
                MihomoBridge.selectProxy(group, node)
            }
            MihomoBridge.setMode(settings.routingMode.coreValue)

            var mixedBound = ""
            if (settings.systemProxy || settings.allowLan || settings.mixedPort > 0) {
                val targetPort = if (settings.mixedPort in 1024..65535) settings.mixedPort else 0
                val bindHost = if (settings.allowLan) "0.0.0.0" else "127.0.0.1"
                val res = MihomoBridge.startMixed("$bindHost:$targetPort")
                if (!res.startsWith("ERR:") && res.isNotBlank()) {
                    val boundPort = res.substringAfterLast(':').toIntOrNull() ?: 0
                    mixedBound = if (boundPort > 0) "127.0.0.1:$boundPort" else res
                }
            }

            val builder = Builder()
                .setSession("vpn233")
                .setMtu(TUN_MTU)
                .setBlocking(false)
                .addAddress(TUN_GATEWAY, 30)
                .addDnsServer(TUN_DNS)

            if (settings.allowIpv6) {
                builder.addAddress(TUN_GATEWAY6, 126)
                builder.addDnsServer(TUN_DNS6)
            }

            if (settings.bypassPrivate) {
                BYPASS_PRIVATE_ROUTES_V4.forEach { cidr ->
                    val (ip, pfx) = parseCidr(cidr)
                    builder.addRoute(ip, pfx)
                }
                builder.addRoute(TUN_DNS, 32)
                if (settings.allowIpv6) {
                    BYPASS_PRIVATE_ROUTES_V6.forEach { cidr ->
                        val (ip, pfx) = parseCidr(cidr)
                        builder.addRoute(ip, pfx)
                    }
                    builder.addRoute(TUN_DNS6, 128)
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
                if (settings.allowIpv6) {
                    builder.addRoute("::", 0)
                }
            }

            when (settings.accessControlMode) {
                AccessControlMode.ACCEPT_ALL -> Unit
                AccessControlMode.ALLOW_SELECTED -> {
                    (settings.accessControlPackages + packageName).forEach { pkg ->
                        runCatching { builder.addAllowedApplication(pkg) }
                    }
                }
                AccessControlMode.DENY_SELECTED -> {
                    (settings.accessControlPackages - packageName).forEach { pkg ->
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
                if (settings.systemProxy && mixedBound.contains(':')) {
                    val host = mixedBound.substringBeforeLast(':')
                    val port = mixedBound.substringAfterLast(':').toIntOrNull() ?: 0
                    if (port > 0) {
                        val excl = HTTP_PROXY_BLACKLIST + if (settings.bypassPrivate) HTTP_PROXY_LOCAL else emptyList()
                        builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port, excl))
                    }
                }
            }

            if (settings.allowBypass) {
                builder.allowBypass()
            }

            builder.setConfigureIntent(mainActivityPendingIntent())

            val pfd = builder.establish() ?: throw IllegalStateException("系统未授予 VPN 权限或创建隧道失败")
            tunPfd = pfd
            val detachedFd = pfd.detachFd()
            tunPfd = null

            val gwArg = "$TUN_GATEWAY/30" + if (settings.allowIpv6) ",$TUN_GATEWAY6/126" else ""
            val dnsArg = when {
                settings.dnsHijacking -> "0.0.0.0"
                settings.allowIpv6 -> "$TUN_DNS,$TUN_DNS6"
                else -> TUN_DNS
            }
            val tunErr = MihomoBridge.startTun(detachedFd, settings.tunStack.coreValue, gwArg, dnsArg, TUN_MTU)
            if (tunErr.isNotBlank()) {
                throw IllegalStateException("启动 TUN 失败: $tunErr")
            }

            registerPhysicalNetworkDnsCallback()
            _activeProfileName.value = activeProfile.name
            refreshCurrentNodeName()
            _status.value = VpnStatus.RUNNING
            pushStaticOrRateNotification(TrafficStats(), force = true)
            requestTileUpdate()
            ensureMonitorLoop()
        } catch (t: Throwable) {
            _lastError.value = t.message ?: "启动失败"
            stopVpnInternal()
        }
    }

    private fun stopMonitorLoop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun ensureMonitorLoop() {
        if (!screenInteractive || _status.value != VpnStatus.RUNNING) return
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive && _status.value == VpnStatus.RUNNING && screenInteractive) {
                val s = Vpn233Application.instance.settingsStore.settings.value
                val uiActive = isUiVisible
                if (!uiActive && !s.showTrafficInNotification) {
                    // Zero background polling when UI hidden and notification rate display disabled
                    pushStaticOrRateNotification(TrafficStats(), force = false)
                    break
                }
                val stats = MihomoBridge.queryTraffic()
                if (uiActive) {
                    _traffic.value = stats
                    _speedHistory.value = (_speedHistory.value + (stats.upNow to stats.downNow)).takeLast(45)
                }
                if (s.showTrafficInNotification) {
                    pushStaticOrRateNotification(stats, force = false)
                }
                delay(if (uiActive) 1000L else 2500L)
            }
        }
    }

    private fun pushStaticOrRateNotification(stats: TrafficStats, force: Boolean) {
        val s = Vpn233Application.instance.settingsStore.settings.value
        val node = _currentNodeName.value
        val title = buildString {
            append(_activeProfileName.value.ifBlank { "vpn233" })
            if (node.isNotBlank()) append(" · ").append(node)
        }
        val content = if (s.showTrafficInNotification) {
            "↑ ${formatRate(stats.upNow)}   ↓ ${formatRate(stats.downNow)}"
        } else {
            "已连接 · ${s.routingMode.labelZh}模式"
        }
        val signature = "$title|$content"
        if (force || signature != lastNotifiedText) {
            lastNotifiedText = signature
            updateNotification(title, content)
        }
    }

    private fun stopVpnInternal() {
        _status.value = VpnStatus.STOPPING
        stopMonitorLoop()
        unregisterPhysicalNetworkDnsCallback()
        closeTunOnly()
        runCatching { MihomoBridge.reset() }
        _traffic.value = TrafficStats()
        _speedHistory.value = emptyList()
        _currentNodeName.value = ""
        lastNotifiedText = ""
        _status.value = VpnStatus.STOPPED
        requestTileUpdate()
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeTunOnly() {
        runCatching { MihomoBridge.stopTun() }
        runCatching { tunPfd?.close() }
        tunPfd = null
    }

    private fun registerPhysicalNetworkDnsCallback() {
        unregisterPhysicalNetworkDnsCallback()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                pushDns(cm.getLinkProperties(network))
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                pushDns(linkProperties)
            }

            override fun onLost(network: Network) {
                val active = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(active)
                if (active != null && caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false) {
                    pushDns(cm.getLinkProperties(active))
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    private fun pushDns(lp: LinkProperties?) {
        val list = lp?.dnsServers?.mapNotNull { it.hostAddress }?.distinct().orEmpty()
        if (list.isNotEmpty()) {
            MihomoBridge.notifyDnsChanged(list)
        }
    }

    private fun unregisterPhysicalNetworkDnsCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun registerSystemObservers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemEventReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(systemEventReceiver, filter)
        }
        receiversRegistered = true
    }

    private fun syncTimezone() {
        val tz = TimeZone.getDefault()
        MihomoBridge.notifyTimeZoneChanged(tz.id, tz.rawOffset / 1000)
    }

    override fun onRevoke() {
        stopVpnInternal()
        super.onRevoke()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            MihomoBridge.forceGc()
        }
    }

    override fun onDestroy() {
        MihomoBridge.socketProtector = null
        if (receiversRegistered) {
            runCatching { unregisterReceiver(systemEventReceiver) }
            receiversRegistered = false
        }
        stopMonitorLoop()
        unregisterPhysicalNetworkDnsCallback()
        closeTunOnly()
        runCatching { MihomoBridge.reset() }
        _status.value = VpnStatus.STOPPED
        requestTileUpdate()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = Intent(this, Vpn233Service::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainActivityPendingIntent())
            .addAction(Notification.Action.Builder(null, "断开", stopPi).build())
            .build()
    }

    private fun postForegroundNotification(title: String, text: String) {
        val n = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun mainActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun requestTileUpdate() {
        runCatching {
            TileService.requestListeningState(this, ComponentName(this, Vpn233TileService::class.java))
        }
    }

    private fun parseCidr(cidr: String): Pair<String, Int> {
        val parts = cidr.split('/')
        return parts[0] to parts[1].toInt()
    }

    companion object {
        const val ACTION_START = "com.neko233.vpn233.action.START"
        const val ACTION_STOP = "com.neko233.vpn233.action.STOP"
        const val ACTION_RELOAD = "com.neko233.vpn233.action.RELOAD"
        const val ACTION_REFRESH_MONITOR = "com.neko233.vpn233.action.REFRESH_MONITOR"

        private const val CHANNEL_ID = "vpn233_status"
        private const val NOTIFICATION_ID = 2331
        private const val TUN_MTU = 9000
        private const val TUN_GATEWAY = "172.19.0.1"
        private const val TUN_DNS = "172.19.0.2"
        private const val TUN_GATEWAY6 = "fdfe:dcba:9876::1"
        private const val TUN_DNS6 = "fdfe:dcba:9876::2"

        @Volatile
        var isUiVisible: Boolean = false
            private set

        private val _status = MutableStateFlow(VpnStatus.STOPPED)
        val status: StateFlow<VpnStatus> = _status.asStateFlow()

        private val _traffic = MutableStateFlow(TrafficStats())
        val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

        private val _speedHistory = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
        val speedHistory: StateFlow<List<Pair<Long, Long>>> = _speedHistory.asStateFlow()

        private val _currentNodeName = MutableStateFlow("")
        val currentNodeName: StateFlow<String> = _currentNodeName.asStateFlow()

        private val _activeProfileName = MutableStateFlow("")
        val activeProfileName: StateFlow<String> = _activeProfileName.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        fun clearError() {
            _lastError.value = null
        }

        fun setUiVisible(context: Context, visible: Boolean) {
            isUiVisible = visible
            if (visible && _status.value == VpnStatus.RUNNING) {
                val intent = Intent(context, Vpn233Service::class.java).setAction(ACTION_REFRESH_MONITOR)
                runCatching { context.startService(intent) }
            }
        }

        fun refreshCurrentNodeName() {
            val s = Vpn233Application.instance.settingsStore.settings.value
            val (_, groups) = MihomoBridge.queryGroups(s.delayTestUrl)
            _currentNodeName.value = groups.firstOrNull()?.now.orEmpty()
        }

        fun start(context: Context) {
            val intent = Intent(context, Vpn233Service::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, Vpn233Service::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun reload(context: Context) {
            if (_status.value == VpnStatus.RUNNING) {
                val intent = Intent(context, Vpn233Service::class.java).setAction(ACTION_RELOAD)
                context.startService(intent)
            }
        }

        fun formatRate(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format(java.util.Locale.US, "%.2f GB", gb)
        }

        private val BYPASS_PRIVATE_ROUTES_V4 = listOf(
            "0.0.0.0/5",
            "8.0.0.0/7",
            "11.0.0.0/8",
            "12.0.0.0/6",
            "16.0.0.0/4",
            "32.0.0.0/3",
            "64.0.0.0/2",
            "128.0.0.0/3",
            "160.0.0.0/5",
            "168.0.0.0/6",
            "172.0.0.0/12",
            "172.32.0.0/11",
            "172.64.0.0/10",
            "172.128.0.0/9",
            "173.0.0.0/8",
            "174.0.0.0/7",
            "176.0.0.0/4",
            "192.0.0.0/9",
            "192.128.0.0/11",
            "192.160.0.0/13",
            "192.169.0.0/16",
            "192.170.0.0/15",
            "192.172.0.0/14",
            "192.176.0.0/12",
            "192.192.0.0/10",
            "193.0.0.0/8",
            "194.0.0.0/7",
            "196.0.0.0/6",
            "200.0.0.0/5",
            "208.0.0.0/4"
        )

        private val BYPASS_PRIVATE_ROUTES_V6 = listOf(
            "::/1",
            "8000::/2",
            "c000::/3",
            "e000::/4",
            "f000::/5",
            "f800::/6",
            "fe00::/9",
            "fec0::/10",
            "ff00::/8"
        )

        private val HTTP_PROXY_LOCAL = listOf(
            "localhost", "*.local", "127.*", "10.*",
            "172.16.*", "172.17.*", "172.18.*", "172.19.*",
            "172.2*", "172.30.*", "172.31.*", "192.168.*"
        )

        private val HTTP_PROXY_BLACKLIST = listOf(
            "*zhihu.com", "*zhimg.com", "*jd.com", "*360buyimg.com"
        )
    }
}
