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
import androidx.compose.material3.Icon
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
import com.neko233.vpn233.service.Vpn233Service
import com.neko233.vpn233.ui.components.AppIcons
import com.neko233.vpn233.ui.theme.LocalVpnPalette
import kotlinx.coroutines.delay

@Composable
fun MonitorDialog(
    connections: List<ConnectionItem>,
    logs: List<CoreLogEntry>,
    onRefreshConnections: () -> Unit,
    onCloseConnection: (String) -> Unit,
    onCloseAllConnections: () -> Unit,
    onSetLogStreaming: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalVpnPalette.current
    val clipboard = LocalClipboardManager.current
    var tab by remember { mutableStateOf(0) } // 0 = Connections, 1 = Logs
    var logFilter by remember { mutableStateOf("ALL") }

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
                        listOf("活跃连接 (${connections.size})", "实时日志 (${logs.size})").forEachIndexed { idx, label ->
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
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
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
                        Text("按住或点右侧可单独断开连接", color = p.textSecondary, fontSize = 11.sp)
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
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(connections, key = { it.id }) { c ->
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
                } else {
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
                }
            }
        }
    }
}
