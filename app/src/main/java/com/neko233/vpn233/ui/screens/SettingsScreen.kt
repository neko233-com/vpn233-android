package com.neko233.vpn233.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neko233.vpn233.BuildConfig
import com.neko233.vpn233.core.MihomoBridge
import com.neko233.vpn233.data.AccessControlMode
import com.neko233.vpn233.data.AppLanguage
import com.neko233.vpn233.data.AppSettings
import com.neko233.vpn233.data.GeoDbStatus
import com.neko233.vpn233.data.InstalledAppInfo
import com.neko233.vpn233.data.NetworkHelpers
import com.neko233.vpn233.data.ThemeMode
import com.neko233.vpn233.data.TunStackMode
import com.neko233.vpn233.service.Vpn233Service
import com.neko233.vpn233.ui.components.AppIcons
import com.neko233.vpn233.ui.theme.LocalStrings
import com.neko233.vpn233.ui.theme.LocalVpnPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    settings: AppSettings,
    geoDbStatus: GeoDbStatus,
    installedApps: List<InstalledAppInfo>,
    coreVersion: String,
    onUpdateSettings: (Boolean, (AppSettings) -> AppSettings) -> Unit,
    onLoadApps: () -> Unit,
    onOpenRules: () -> Unit,
    onUpgradeGeoDb: () -> Unit,
    onResetGeoDb: () -> Unit,
    onExportAllBackup: () -> Unit,
    onToast: (String) -> Unit
) {
    val p = LocalVpnPalette.current
    val str = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    var showAppPicker by remember { mutableStateOf(false) }
    var showDnsEditor by remember { mutableStateOf(false) }
    var showDelayUrlEditor by remember { mutableStateOf(false) }
    var showPortEditor by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(str.settingsTitle, color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // 1. Network & TUN
        SectionCard("网络与 TUN 隧道") {
            Text("TUN 网络栈", color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TunStackMode.entries.forEach { st ->
                    val sel = settings.tunStack == st
                    Surface(
                        modifier = Modifier.weight(1f),
                        onClick = { onUpdateSettings(true) { it.copy(tunStack = st) } },
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) p.accent.copy(alpha = 0.18f) else p.cardElevated,
                        border = if (sel) BorderStroke(1.dp, p.accent) else null
                    ) {
                        Text(
                            st.label,
                            color = if (sel) p.accent else p.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            Text(settings.tunStack.desc, color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            ToggleRow("绕过私有网络", "192.168 / 10.x 等局域网直连不进隧道", settings.bypassPrivate) { v ->
                onUpdateSettings(true) { it.copy(bypassPrivate = v) }
            }
            ToggleRow("允许 IPv6 流量", "通过 VpnService 路由 IPv6 地址", settings.allowIpv6) { v ->
                onUpdateSettings(true) { it.copy(allowIpv6 = v) }
            }
            ToggleRow("DNS 劫持 (0.0.0.0:53)", "捕获全部 53 端口 DNS 查询到核心", settings.dnsHijacking) { v ->
                onUpdateSettings(true) { it.copy(dnsHijacking = v) }
            }
            ToggleRow("附加系统 HTTP 代理", "VpnService 通知应用本地 Mixed 代理端口", settings.systemProxy) { v ->
                onUpdateSettings(true) { it.copy(systemProxy = v) }
            }
            ToggleRow("允许局域网共享 (Allow LAN)", "绑定 0.0.0.0 供同一 Wi-Fi 下其他设备连接", settings.allowLan) { v ->
                onUpdateSettings(true) { it.copy(allowLan = v) }
            }
            if (settings.allowLan && settings.mixedPort > 0) {
                val lanIps = remember(settings.allowLan, settings.mixedPort) {
                    NetworkHelpers.getLanIpv4Addresses()
                }
                if (lanIps.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        lanIps.forEach { ip ->
                            val ep = "$ip:${settings.mixedPort}"
                            Surface(
                                onClick = {
                                    clipboard.setText(AnnotatedString(ep))
                                    onToast("已复制局域网代理地址: $ep")
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = p.accent.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, p.accent.copy(alpha = 0.45f))
                            ) {
                                Text("LAN: $ep (复制)", color = p.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp, 5.dp))
                            }
                        }
                    }
                }
            }
            ClickRow("本地 Mixed 端口", "当前: ${settings.mixedPort}") { showPortEditor = true }
            ToggleRow("允许其他应用绕过", "允许系统声明 allowBypass 的网络请求", settings.allowBypass) { v ->
                onUpdateSettings(true) { it.copy(allowBypass = v) }
            }
            ToggleRow("切网自动断开旧连接", "Wi-Fi 与 5G 切换时清理半开会话防卡顿", settings.disconnectOnNetworkChange) { v ->
                onUpdateSettings(false) { it.copy(disconnectOnNetworkChange = v) }
            }
        }

        // 2. Per-App Proxy
        SectionCard("分应用代理 (Access Control)") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AccessControlMode.entries.forEach { mode ->
                    val sel = settings.accessControlMode == mode
                    Surface(
                        onClick = { onUpdateSettings(true) { it.copy(accessControlMode = mode) } },
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) p.accent.copy(alpha = 0.16f) else p.cardElevated,
                        border = if (sel) BorderStroke(1.dp, p.accent.copy(alpha = 0.5f)) else null
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(mode.label, color = if (sel) p.accent else p.textPrimary, fontSize = 12.sp)
                            if (sel) Icon(AppIcons.Check, null, tint = p.accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (settings.accessControlMode != AccessControlMode.ACCEPT_ALL) {
                Spacer(Modifier.height(6.dp))
                ClickRow(
                    title = "选择受控应用",
                    subtitle = "已选择 ${settings.accessControlPackages.size} 个应用"
                ) {
                    onLoadApps()
                    showAppPicker = true
                }
            }
        }

        // 3. Meta & Override Features
        SectionCard("DNS 与 Meta 特性覆写") {
            ToggleRow("遵循配置的内核选项", "开启后嗅探、延迟、并发、GeoData 和进程匹配使用配置文件中的值", settings.followProfileOptions) { v ->
                onUpdateSettings(true) { it.copy(followProfileOptions = v) }
            }
            ToggleRow("追加系统 DNS (system://)", "自动注入物理网络 DNS 处理兜底解析", settings.appendSystemDns) { v ->
                onUpdateSettings(true) { it.copy(appendSystemDns = v) }
            }
            ToggleRow("强制 FakeIP 映射", "即使原配置关闭 DNS 也强制启用 FakeIP", settings.forceDnsMapping) { v ->
                onUpdateSettings(true) { it.copy(forceDnsMapping = v) }
            }
            ToggleRow("域名嗅探 (Sniffer)", "识别 TLS/HTTP/QUIC 真实域名用于规则匹配", settings.sniffing) { v ->
                onUpdateSettings(true) { it.copy(sniffing = v, followProfileOptions = false) }
            }
            ToggleRow("覆写目标地址", "将嗅探到的域名写入路由目标", settings.overrideDestination) { v ->
                onUpdateSettings(true) { it.copy(overrideDestination = v, followProfileOptions = false) }
            }
            ToggleRow("统一延迟 (Unified Delay)", "消除不同协议握手差异，更客观反映延迟", settings.unifiedDelay) { v ->
                onUpdateSettings(true) { it.copy(unifiedDelay = v, followProfileOptions = false) }
            }
            ToggleRow("TCP 并发拨号", "同时尝试解析出的多个 IP 取最快连接", settings.tcpConcurrent) { v ->
                onUpdateSettings(true) { it.copy(tcpConcurrent = v, followProfileOptions = false) }
            }
            Text("进程匹配模式 (关闭最省电)", color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("off" to "关闭 (省电)", "strict" to "严格", "always" to "总是").forEach { (modeVal, lbl) ->
                    val sel = settings.findProcessMode == modeVal
                    Surface(
                        modifier = Modifier.weight(1f),
                        onClick = { onUpdateSettings(true) { it.copy(findProcessMode = modeVal, followProfileOptions = false) } },
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) p.accent.copy(alpha = 0.18f) else p.cardElevated,
                        border = if (sel) BorderStroke(1.dp, p.accent) else null
                    ) {
                        Text(
                            lbl,
                            color = if (sel) p.accent else p.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 7.dp).fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            ClickRow(
                title = "分流规则编辑器 (支持全部类型与热重载)",
                subtitle = "已配置 ${settings.customRules.count { it.enabled }} 条启用规则 · 共 ${settings.customRules.size} 条"
            ) { onOpenRules() }
            ClickRow(
                title = "自定义 DNS 与 FakeIP 过滤覆盖",
                subtitle = "DNS ${settings.customDnsNameservers.size} 个 · 额外 FakeIP 过滤 ${settings.fakeIpFilterExtra.size} 条"
            ) { showDnsEditor = true }
            ClickRow("延迟测试 URL", settings.delayTestUrl) { showDelayUrlEditor = true }
        }

        // 4. Geo Databases
        SectionCard("Geo 数据库") {
            Text(
                "内置完整 GeoIP、GeoSite 和 ASN 数据库，支持在线更新。",
                color = p.textSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "GeoIP: ${if (geoDbStatus.isGeoIpFull) "完整版" else "精简版"} (${Vpn233Service.formatBytes(geoDbStatus.geoIpBytes)}) · " +
                    "GeoSite: ${if (geoDbStatus.isGeoSiteFull) "完整版" else "精简版"} (${Vpn233Service.formatBytes(geoDbStatus.geoSiteBytes)}) · " +
                    "ASN: ${if (geoDbStatus.hasAsn) Vpn233Service.formatBytes(geoDbStatus.asnBytes) else "未安装"} · " +
                    "GeoIP.dat: ${Vpn233Service.formatBytes(geoDbStatus.geoIpDatBytes)}",
                color = p.textPrimary,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = onUpgradeGeoDb,
                    shape = RoundedCornerShape(10.dp),
                    color = p.accent
                ) {
                    Text(
                        "在线更新",
                        color = Color(0xFF042117),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                Surface(
                    onClick = onResetGeoDb,
                    shape = RoundedCornerShape(10.dp),
                    color = p.cardElevated
                ) {
                    Text(
                        "恢复内置版本",
                        color = p.textPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // 5. Background Keep-Alive, Theme & Backup
        SectionCard("后台保活、外观与备份") {
            val ctx = LocalContext.current
            val pm = remember { ctx.getSystemService(PowerManager::class.java) }
            val ignoringBattery = remember(pm) {
                pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
            }
            Surface(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    }
                    runCatching { ctx.startActivity(intent) }
                },
                shape = RoundedCornerShape(12.dp),
                color = if (ignoringBattery) p.accent.copy(alpha = 0.14f) else p.warning.copy(alpha = 0.15f),
                border = BorderStroke(
                    1.dp,
                    if (ignoringBattery) p.accent.copy(alpha = 0.45f) else p.warning.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (ignoringBattery) "已关闭电池优化 · 后台常驻稳定" else "建议关闭电池优化 (后台防杀)",
                            color = if (ignoringBattery) p.accent else p.warning,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "息屏自动休眠轮询，零 WakeLock 唤醒，仅在数据包到达时响应",
                            color = p.textSecondary,
                            fontSize = 10.sp
                        )
                    }
                    if (!ignoringBattery) {
                        Text("去授权", color = p.warning, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("界面主题", color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEach { tm ->
                    val sel = settings.themeMode == tm
                    Surface(
                        modifier = Modifier.weight(1f),
                        onClick = { onUpdateSettings(false) { it.copy(themeMode = tm) } },
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) p.accentSecondary.copy(alpha = 0.18f) else p.cardElevated,
                        border = if (sel) BorderStroke(1.dp, p.accentSecondary) else null
                    ) {
                        Text(
                            tm.label,
                            color = if (sel) p.accentSecondary else p.textPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("界面语言", color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AppLanguage.entries.forEach { lang ->
                    val sel = settings.language == lang
                    Surface(
                        modifier = Modifier.weight(1f),
                        onClick = { onUpdateSettings(false) { it.copy(language = lang) } },
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) p.accent.copy(alpha = 0.18f) else p.cardElevated,
                        border = if (sel) BorderStroke(1.dp, p.accent) else null
                    ) {
                        Text(
                            lang.label,
                            color = if (sel) p.accent else p.textPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow("通知栏显示实时网速", "每秒刷新上传/下载速率", settings.showTrafficInNotification) { v ->
                onUpdateSettings(false) { it.copy(showTrafficInNotification = v) }
            }
            ToggleRow("开机自动连接", "设备重启后自动恢复上次配置", settings.autoStartOnBoot) { v ->
                onUpdateSettings(false) { it.copy(autoStartOnBoot = v) }
            }
            ClickRow("导出全部配置备份 (.zip)", "打包当前全部配置文件与元数据") { onExportAllBackup() }
            ClickRow("释放核心内存 (GC)", "触发 Go 核心垃圾回收并归还空闲内存") {
                MihomoBridge.forceGc()
                onToast("已完成核心内存回收")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "vpn233 v${BuildConfig.VERSION_NAME} (arm64-v8a)\n$coreVersion\ngithub.com/neko233-com/vpn233-android",
                color = p.textSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            selectedPackages = settings.accessControlPackages,
            onUpdateSelection = { updated ->
                onUpdateSettings(true) { it.copy(accessControlPackages = updated) }
            },
            onDismiss = { showAppPicker = false }
        )
    }

    if (showDnsEditor) {
        var fakeText by remember { mutableStateOf(settings.fakeIpFilterExtra.joinToString("\n")) }
        var dnsText by remember { mutableStateOf(settings.customDnsNameservers.joinToString("\n")) }
        AlertDialog(
            onDismissRequest = { showDnsEditor = false },
            containerColor = p.card,
            title = { Text("自定义 DNS 与 FakeIP 过滤", color = p.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "推荐混合 DoH" to listOf("https://dns.alidns.com/dns-query", "https://doh.pub/dns-query", "https://1.1.1.1/dns-query"),
                            "阿里+腾讯 DoH" to listOf("https://dns.alidns.com/dns-query", "https://doh.pub/dns-query"),
                            "Cloudflare/Google" to listOf("https://1.1.1.1/dns-query", "https://dns.google/dns-query"),
                            "恢复配置默认" to emptyList()
                        ).forEach { (lbl, list) ->
                            Surface(
                                onClick = { dnsText = list.joinToString("\n") },
                                shape = RoundedCornerShape(8.dp),
                                color = p.cardElevated
                            ) {
                                Text(lbl, color = p.accentSecondary, fontSize = 11.sp, modifier = Modifier.padding(9.dp, 6.dp))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = dnsText,
                        onValueChange = { dnsText = it },
                        label = { Text("覆盖 NameServer (留空沿用配置，每行一个)") },
                        minLines = 3,
                        maxLines = 6,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = fakeText,
                        onValueChange = { fakeText = it },
                        label = { Text("额外 FakeIP Filter 域名 (每行一个)") },
                        minLines = 2,
                        maxLines = 5,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val fake = fakeText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val dns = dnsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    onUpdateSettings(true) { it.copy(fakeIpFilterExtra = fake, customDnsNameservers = dns) }
                    showDnsEditor = false
                }) { Text("应用", color = p.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showDnsEditor = false }) { Text("取消", color = p.textSecondary) }
            }
        )
    }

    if (showDelayUrlEditor) {
        var urlTxt by remember { mutableStateOf(settings.delayTestUrl) }
        AlertDialog(
            onDismissRequest = { showDelayUrlEditor = false },
            containerColor = p.card,
            title = { Text("延迟测试 URL", color = p.textPrimary) },
            text = {
                OutlinedTextField(
                    value = urlTxt,
                    onValueChange = { urlTxt = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (urlTxt.isNotBlank()) {
                        onUpdateSettings(false) { it.copy(delayTestUrl = urlTxt.trim()) }
                    }
                    showDelayUrlEditor = false
                }) { Text("保存", color = p.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showDelayUrlEditor = false }) { Text("取消", color = p.textSecondary) }
            }
        )
    }

    if (showPortEditor) {
        var portTxt by remember { mutableStateOf(settings.mixedPort.toString()) }
        AlertDialog(
            onDismissRequest = { showPortEditor = false },
            containerColor = p.card,
            title = { Text("本地 Mixed 端口", color = p.textPrimary) },
            text = {
                OutlinedTextField(
                    value = portTxt,
                    onValueChange = { portTxt = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val port = portTxt.toIntOrNull()?.coerceIn(0, 65535) ?: 7890
                    onUpdateSettings(true) { it.copy(mixedPort = port) }
                    showPortEditor = false
                }) { Text("保存", color = p.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showPortEditor = false }) { Text("取消", color = p.textSecondary) }
            }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val p = LocalVpnPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = p.card,
        border = BorderStroke(1.dp, p.border)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val p = LocalVpnPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = p.textSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF042117), checkedTrackColor = p.accent)
        )
    }
}

@Composable
private fun ClickRow(title: String, subtitle: String, onClick: () -> Unit) {
    val p = LocalVpnPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(title, color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = p.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<InstalledAppInfo>,
    selectedPackages: Set<String>,
    onUpdateSelection: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalVpnPalette.current
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val filtered = remember(apps, query, showSystem, selectedPackages) {
        apps.filter { app ->
            (showSystem || !app.isSystem || selectedPackages.contains(app.packageName)) &&
                (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
        }.sortedByDescending { selectedPackages.contains(it.packageName) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(22.dp),
            color = p.bg,
            border = BorderStroke(1.dp, p.border)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("选择应用 (${selectedPackages.size})", color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = { showSystem = !showSystem },
                            shape = RoundedCornerShape(10.dp),
                            color = if (showSystem) p.accentSecondary.copy(alpha = 0.2f) else p.card
                        ) {
                            Text(
                                if (showSystem) "隐藏系统" else "显示系统",
                                color = if (showSystem) p.accentSecondary else p.textSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp, 6.dp)
                            )
                        }
                        Surface(
                            onClick = {
                                val next = selectedPackages + filtered.map { it.packageName }
                                onUpdateSelection(next)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = p.card
                        ) {
                            Text("全选当前", color = p.accentSecondary, fontSize = 11.sp, modifier = Modifier.padding(10.dp, 6.dp))
                        }
                        Surface(
                            onClick = { onUpdateSelection(emptySet()) },
                            shape = RoundedCornerShape(10.dp),
                            color = p.card
                        ) {
                            Text("清空", color = p.danger, fontSize = 11.sp, modifier = Modifier.padding(10.dp, 6.dp))
                        }
                        Surface(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            color = p.accent
                        ) {
                            Text("完成", color = Color(0xFF042117), fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(12.dp, 6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = p.card,
                    border = BorderStroke(1.dp, p.border)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp, 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(AppIcons.Search, null, tint = p.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("搜索应用名称或包名...", color = p.textSecondary, fontSize = 12.sp)
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(color = p.textPrimary, fontSize = 13.sp),
                                cursorBrush = SolidColor(p.accent),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = selectedPackages.contains(app.packageName)
                        Surface(
                            onClick = {
                                val next = selectedPackages.toMutableSet()
                                if (checked) next.remove(app.packageName) else next.add(app.packageName)
                                onUpdateSelection(next)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = p.card
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIconImage(app.packageName, modifier = Modifier.size(34.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, color = p.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(app.packageName, color = p.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Checkbox(checked = checked, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconImage(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    drawable.bitmap.asImageBitmap()
                } else {
                    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    if (img != null) {
        Image(bitmap = img!!, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier = modifier)
    }
}
