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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neko233.vpn233.data.ProviderItem
import com.neko233.vpn233.data.ProxyGroupUi
import com.neko233.vpn233.data.ProxyNodeUi
import com.neko233.vpn233.data.VpnStatus
import com.neko233.vpn233.ui.components.AppIcons
import com.neko233.vpn233.ui.theme.LocalVpnPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class NodeSortMode(val label: String) {
    DEFAULT("默认"),
    DELAY("延迟"),
    NAME("名称")
}

@Composable
fun ProxiesScreen(
    vpnStatus: VpnStatus,
    groups: List<ProxyGroupUi>,
    providers: List<ProviderItem>,
    testingGroup: String?,
    onRefresh: () -> Unit,
    onSelectProxy: (String, String) -> Unit,
    onTestGroup: (String) -> Unit,
    onTestSingle: (String) -> Unit,
    onUpdateProvider: (String, String) -> Unit,
    onStartVpn: () -> Unit
) {
    val p = LocalVpnPalette.current
    var selectedGroupIndex by remember { mutableStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(NodeSortMode.DEFAULT) }
    var showProviders by remember { mutableStateOf(false) }

    LaunchedEffect(vpnStatus) {
        if (vpnStatus == VpnStatus.RUNNING) onRefresh()
    }

    if (vpnStatus != VpnStatus.RUNNING) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = p.card,
                border = BorderStroke(1.dp, p.border)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(AppIcons.Nodes, null, tint = p.accent, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("隧道尚未运行", color = p.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "开启 vpn233 后即可在此查看策略组、批量测速并切换节点。",
                        color = p.textSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = onStartVpn,
                        shape = RoundedCornerShape(14.dp),
                        color = p.accent
                    ) {
                        Text(
                            "立即连接",
                            color = Color(0xFF042117),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 11.dp)
                        )
                    }
                }
            }
        }
        return
    }

    val safeIndex = selectedGroupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    val currentGroup = groups.getOrNull(safeIndex)

    Column(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
        // Header + Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("节点代理", color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (providers.isNotEmpty()) {
                    Surface(
                        onClick = { showProviders = !showProviders },
                        shape = RoundedCornerShape(12.dp),
                        color = if (showProviders) p.accentSecondary.copy(alpha = 0.2f) else p.card,
                        border = BorderStroke(1.dp, if (showProviders) p.accentSecondary else p.border)
                    ) {
                        Text(
                            text = "外部资源(${providers.size})",
                            color = if (showProviders) p.accentSecondary else p.textPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
                if (currentGroup != null) {
                    Surface(
                        onClick = { onTestGroup(currentGroup.name) },
                        enabled = testingGroup == null,
                        shape = RoundedCornerShape(12.dp),
                        color = p.accent.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, p.accent.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (testingGroup == currentGroup.name) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = p.accent
                                )
                            } else {
                                Icon(AppIcons.SpeedBolt, null, tint = p.accent, modifier = Modifier.size(15.dp))
                            }
                            Spacer(Modifier.width(5.dp))
                            Text("测速", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (showProviders && providers.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(providers, key = { "${it.kind}:${it.name}" }) { pv ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = p.card,
                        border = BorderStroke(1.dp, p.border)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pv.name, color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                val timeStr = if (pv.updatedAt > 0) {
                                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(pv.updatedAt))
                                } else {
                                    "未记录"
                                }
                                Text(
                                    "${pv.kind} · ${pv.vehicleType} · ${pv.count} 条 · 更新于 $timeStr",
                                    color = p.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Surface(
                                onClick = { onUpdateProvider(pv.kind, pv.name) },
                                shape = RoundedCornerShape(10.dp),
                                color = p.cardElevated
                            ) {
                                Icon(
                                    AppIcons.Refresh,
                                    contentDescription = "更新",
                                    tint = p.accent,
                                    modifier = Modifier.padding(8.dp).size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            return
        }

        // Group Horizontal Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groups.forEachIndexed { idx, grp ->
                val selected = idx == safeIndex
                Surface(
                    onClick = { selectedGroupIndex = idx },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) p.accent else p.card,
                    border = if (selected) null else BorderStroke(1.dp, p.border)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(
                            text = grp.name,
                            color = if (selected) Color(0xFF042117) else p.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (grp.now.isNotBlank()) {
                            Text(
                                text = grp.now,
                                color = if (selected) Color(0xFF063827) else p.textSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Search + Sort Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = p.card,
                border = BorderStroke(1.dp, p.border)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.Search, null, tint = p.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchText.isEmpty()) {
                            Text("搜索节点名称或地区...", color = p.textSecondary, fontSize = 12.sp)
                        }
                        BasicTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            singleLine = true,
                            textStyle = TextStyle(color = p.textPrimary, fontSize = 13.sp),
                            cursorBrush = SolidColor(p.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchText.isNotEmpty()) {
                        Icon(
                            AppIcons.Close,
                            null,
                            tint = p.textSecondary,
                            modifier = Modifier.size(15.dp).clickable { searchText = "" }
                        )
                    }
                }
            }

            Surface(
                onClick = {
                    val next = (sortMode.ordinal + 1) % NodeSortMode.entries.size
                    sortMode = NodeSortMode.entries[next]
                },
                shape = RoundedCornerShape(12.dp),
                color = p.card,
                border = BorderStroke(1.dp, p.border)
            ) {
                Text(
                    text = "排序:${sortMode.label}",
                    color = p.textPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (currentGroup != null) {
            val filteredNodes = remember(currentGroup, searchText, sortMode) {
                val base = if (searchText.isBlank()) {
                    currentGroup.proxies
                } else {
                    val q = searchText.trim()
                    currentGroup.proxies.filter { it.name.contains(q, ignoreCase = true) || it.type.contains(q, true) }
                }
                when (sortMode) {
                    NodeSortMode.DEFAULT -> base
                    NodeSortMode.NAME -> base.sortedBy { it.name.lowercase() }
                    NodeSortMode.DELAY -> base.sortedWith(
                        compareBy<ProxyNodeUi> { if (it.delay > 0) 0 else 1 }
                            .thenBy { if (it.delay > 0) it.delay else Int.MAX_VALUE }
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredNodes, key = { it.name }) { node ->
                    val isSelected = currentGroup.now == node.name
                    val delayColor = when {
                        node.delay <= 0 -> p.textSecondary
                        node.delay < 140 -> p.success
                        node.delay < 320 -> p.warning
                        else -> p.danger
                    }
                    Surface(
                        onClick = {
                            if (currentGroup.selectable) {
                                onSelectProxy(currentGroup.name, node.name)
                            } else {
                                onTestSingle(node.name)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) p.accent.copy(alpha = 0.14f) else p.card,
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 1.dp,
                            if (isSelected) p.accent else p.border
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = p.cardElevated
                                ) {
                                    Text(
                                        text = node.type,
                                        color = p.textSecondary,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        maxLines = 1
                                    )
                                }
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(p.accent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            AppIcons.Check,
                                            null,
                                            tint = Color(0xFF042117),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = node.name,
                                color = if (isSelected) p.accent else p.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 17.sp,
                                modifier = Modifier.height(34.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when {
                                        node.delay > 0 -> "${node.delay} ms"
                                        else -> "点击测速"
                                    },
                                    color = delayColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                    modifier = Modifier.clickable { onTestSingle(node.name) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
