package com.neko233.vpn233.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neko233.vpn233.data.ConnectionItem
import com.neko233.vpn233.data.CoreLogEntry
import com.neko233.vpn233.data.MatchTestResult
import com.neko233.vpn233.data.VpnStatus
import com.neko233.vpn233.service.Vpn233Service
import com.neko233.vpn233.ui.components.AppIcons
import com.neko233.vpn233.ui.theme.LocalVpnPalette
import kotlinx.coroutines.delay

@Composable
fun MonitorDialog(
    vpnStatus: VpnStatus = VpnStatus.STOPPED,
    connections: List<ConnectionItem>,
    logs: List<CoreLogEntry>,
    matchResult: MatchTestResult? = null,
    exitIpInfo: String? = null,
    onRefreshConnections: () -> Unit,
    onCloseConnection: (String) -> Unit,
    onCloseAllConnections: () -> Unit,
    onQuickAddRule: (String) -> Unit,
    onSetLogStreaming: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onTestMatch: (String, String, String) -> Unit = { _, _, _ -> },
    onFlushDns: () -> Unit = {},
    onCheckExitIp: () -> Unit = {},
    onToast: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalVpnPalette.current
    val clipboard = LocalClipboardManager.current
    var tab by remember { mutableStateOf(0) } // 0 = Connections, 1 = Logs, 2 = Diagnostics
    var connFilter by remember { mutableStateOf("") }
    var logFilter by remember { mutableStateOf("ALL") }
    var ruleTargetConn by remember { mutableStateOf<ConnectionItem?>(null) }
    var testHost by remember { mutableStateOf("openai.com") }
    var testPort by remember { mutableStateOf("443") }
    var testNet by remember { mutableStateOf("tcp") }

    DisposableEffect(Unit) {
        onSetLogStreaming(true)
        onDispose { onSetLogStreaming(false) }
    }

    LaunchedEffect(tab) {
        if (tab == 0) {
            while (true) {
                onRefreshConnections()
                delay(1500L)
            }
        }
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
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("连接 (${connections.size})", "日志 (${logs.size})", "诊断").forEachIndexed { idx, label ->
                            val selected = tab == idx
                            Surface(
                                onClick = { tab = idx },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) p.accent else p.card,
                                border = if (selected) null else BorderStroke(1.dp, p.border)
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) Color(0xFF042117) else p.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        color = p.card
                    ) {
                        Icon(
                            AppIcons.Close,
                            contentDescription = "关闭",
                            tint = p.textPrimary,
                            modifier = Modifier.padding(8.dp).size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (tab == 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("按域名/规则/进程过滤或断开连接", color = p.textSecondary, fontSize = 11.sp)
                        Surface(
                            onClick = onCloseAllConnections,
                            shape = RoundedCornerShape(10.dp),
                            color = p.danger.copy(alpha = 0.14f)
                        ) {
                            Text(
                                "断开全部",
                                color = p.danger,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = p.card,
                        border = BorderStroke(1.dp, p.border)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(AppIcons.Search, null, tint = p.textSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (connFilter.isEmpty()) Text("过滤域名 / IP / 规则 / 链路...", color = p.textSecondary, fontSize = 11.sp)
                                BasicTextField(
                                    value = connFilter,
                                    onValueChange = { connFilter = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = p.textPrimary, fontSize = 12.sp),
                                    cursorBrush = SolidColor(p.accent),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val filteredConns = remember(connections, connFilter) {
                        if (connFilter.isBlank()) connections
                        else {
                            val q = connFilter.trim()
                            connections.filter {
                                it.host.contains(q, true) || it.dstIp.contains(q, true) ||
                                    it.rule.contains(q, true) || it.process.contains(q, true) ||
                                    it.chains.any { c -> c.contains(q, true) }
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredConns, key = { it.id }) { c ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = p.card,
                                border = BorderStroke(1.dp, p.border)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val target = buildString {
                                            append(c.host.ifBlank { c.dstIp })
                                            if (c.dstPort.isNotBlank()) append(":${c.dstPort}")
                                        }
                                        Text(
                                            text = target,
                                            color = p.textPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val metaLine = buildString {
                                            append(c.network.uppercase())
                                            if (c.process.isNotBlank()) append(" · ").append(c.process)
                                            if (c.rule.isNotBlank()) append(" · ").append(c.rule)
                                            if (c.rulePayload.isNotBlank()) append("(${c.rulePayload})")
                                        }
                                        Text(
                                            text = metaLine,
                                            color = p.textSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.padding(top = 3.dp)
                                        ) {
                                            Text(
                                                text = c.chains.reversed().joinToString(" → "),
                                                color = p.accent,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Text(
                                                text = "↑${Vpn233Service.formatBytes(c.upload)} ↓${Vpn233Service.formatBytes(c.download)}",
                                                color = p.textSecondary,
                                                fontSize = 11.sp,
                                                style = TextStyle(fontFeatureSettings = "tnum")
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        onClick = { ruleTargetConn = c },
                                        shape = RoundedCornerShape(8.dp),
                                        color = p.cardElevated
                                    ) {
                                        Text(
                                            "规则+",
                                            color = p.accentSecondary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(7.dp, 4.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        AppIcons.Close,
                                        contentDescription = "关闭连接",
                                        tint = p.danger,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clickable { onCloseConnection(c.id) }
                                    )
                                }
                            }
                        }
                    }
                } else if (tab == 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("ALL", "INFO", "WARNING", "ERROR", "DEBUG").forEach { lvl ->
                                val sel = logFilter == lvl
                                Surface(
                                    onClick = { logFilter = lvl },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) p.accentSecondary.copy(alpha = 0.22f) else p.card
                                ) {
                                    Text(
                                        lvl,
                                        color = if (sel) p.accentSecondary else p.textSecondary,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                onClick = {
                                    val txt = logs.joinToString("\n") { "[${it.timeText}][${it.level}] ${it.message}" }
                                    clipboard.setText(AnnotatedString(txt))
                                    onToast("已复制日志")
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = p.card
                            ) {
                                Text("复制", color = p.textPrimary, fontSize = 11.sp, modifier = Modifier.padding(8.dp, 5.dp))
                            }
                            Surface(
                                onClick = onClearLogs,
                                shape = RoundedCornerShape(8.dp),
                                color = p.card
                            ) {
                                Text("清空", color = p.danger, fontSize = 11.sp, modifier = Modifier.padding(8.dp, 5.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val visibleLogs = remember(logs, logFilter) {
                        val base = if (logFilter == "ALL") logs else logs.filter { it.level.equals(logFilter, true) }
                        base.asReversed()
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(p.card, RoundedCornerShape(14.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(visibleLogs, key = { it.id }) { entry ->
                            val lvlColor = when (entry.level) {
                                "error" -> p.danger
                                "warning" -> p.warning
                                "debug" -> p.accentSecondary
                                else -> p.accent
                            }
                            Text(
                                text = "${entry.timeText} [${entry.level.uppercase()}] ${entry.message}",
                                color = if (entry.level == "error" || entry.level == "warning") lvlColor else p.textPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(shape = RoundedCornerShape(16.dp), color = p.card, border = BorderStroke(1.dp, p.border)) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("规则命中模拟器 (Rule Match Tester)", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = testHost,
                                    onValueChange = { testHost = it },
                                    label = { Text("目标域名或 IP") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = testPort,
                                        onValueChange = { testPort = it.filter { ch -> ch.isDigit() } },
                                        label = { Text("端口") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    listOf("tcp", "udp").forEach { proto ->
                                        val sel = testNet == proto
                                        Surface(
                                            onClick = { testNet = proto },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (sel) p.accentSecondary else p.cardElevated
                                        ) {
                                            Text(
                                                proto.uppercase(),
                                                color = if (sel) Color(0xFF041C24) else p.textPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(12.dp, 10.dp)
                                            )
                                        }
                                    }
                                    Surface(
                                        onClick = { onTestMatch(testHost, testPort.ifBlank { "443" }, testNet) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = p.accent
                                    ) {
                                        Text("测试", color = Color(0xFF042117), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(14.dp, 10.dp))
                                    }
                                }
                                if (vpnStatus != VpnStatus.RUNNING) {
                                    Text("开启隧道后可执行实时规则命中测算", color = p.textSecondary, fontSize = 11.sp)
                                } else if (matchResult != null) {
                                    Surface(shape = RoundedCornerShape(12.dp), color = p.cardElevated) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text("命中规则: ${matchResult.raw.ifBlank { matchResult.ruleType }}", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text("出站链路: ${matchResult.chain.joinToString(" → ").ifBlank { matchResult.adapter }}", color = p.textPrimary, fontSize = 12.sp)
                                            if (matchResult.resolvedIp.isNotBlank()) {
                                                Text("解析 IP: ${matchResult.resolvedIp}", color = p.textSecondary, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Surface(shape = RoundedCornerShape(16.dp), color = p.card, border = BorderStroke(1.dp, p.border)) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("出口 IP 与 DNS 缓存诊断", color = p.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        onClick = onCheckExitIp,
                                        shape = RoundedCornerShape(10.dp),
                                        color = p.accentSecondary
                                    ) {
                                        Text("检测出口 IP", color = Color(0xFF041C24), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(12.dp, 8.dp))
                                    }
                                    Surface(
                                        onClick = onFlushDns,
                                        shape = RoundedCornerShape(10.dp),
                                        color = p.cardElevated,
                                        border = BorderStroke(1.dp, p.border)
                                    ) {
                                        Text("清空 DNS / FakeIP 缓存", color = p.textPrimary, fontSize = 12.sp, modifier = Modifier.padding(12.dp, 8.dp))
                                    }
                                }
                                if (!exitIpInfo.isNullOrBlank()) {
                                    Text(exitIpInfo, color = p.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ruleTargetConn?.let { c ->
        val domain = c.host.trim()
        val ip = c.dstIp.trim()
        val quickOptions = buildList {
            if (domain.isNotBlank() && !domain.all { it.isDigit() || it == '.' || it == ':' }) {
                add("直连该域名" to "DOMAIN,$domain,DIRECT")
                add("代理该域名" to "DOMAIN,$domain,PROXY")
                add("拦截该域名" to "DOMAIN,$domain,REJECT")
                val parts = domain.split('.')
                if (parts.size >= 2) {
                    val rootDom = "${parts[parts.size - 2]}.${parts.last()}"
                    add("主域名直连 ($rootDom)" to "DOMAIN-SUFFIX,$rootDom,DIRECT")
                    add("主域名代理 ($rootDom)" to "DOMAIN-SUFFIX,$rootDom,PROXY")
                }
            } else if (ip.isNotBlank()) {
                val cidr = if (ip.contains(':')) "$ip/128" else "$ip/32"
                add("直连该 IP" to "IP-CIDR,$cidr,DIRECT,no-resolve")
                add("代理该 IP" to "IP-CIDR,$cidr,PROXY,no-resolve")
                add("拦截该 IP" to "IP-CIDR,$cidr,REJECT,no-resolve")
            }
            if (c.process.isNotBlank()) {
                add("进程直连 (${c.process})" to "PROCESS-NAME,${c.process},DIRECT")
                add("进程代理 (${c.process})" to "PROCESS-NAME,${c.process},PROXY")
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ruleTargetConn = null },
            containerColor = p.card,
            title = { Text("快捷添加分流规则", color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickOptions.forEach { (label, rule) ->
                        Surface(
                            onClick = {
                                onQuickAddRule(rule)
                                ruleTargetConn = null
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = p.cardElevated
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(label, color = p.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(rule, color = p.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { ruleTargetConn = null }) {
                    Text("取消", color = p.textSecondary)
                }
            }
        )
    }
}
