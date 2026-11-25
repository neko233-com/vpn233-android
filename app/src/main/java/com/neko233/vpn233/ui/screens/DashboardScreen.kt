package com.neko233.vpn233.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neko233.vpn233.data.AppSettings
import com.neko233.vpn233.data.ProfileItem
import com.neko233.vpn233.data.ProxyGroupUi
import com.neko233.vpn233.data.RoutingMode
import com.neko233.vpn233.data.TrafficStats
import com.neko233.vpn233.data.VpnStatus
import com.neko233.vpn233.service.Vpn233Service
import com.neko233.vpn233.ui.components.AppIcons
import com.neko233.vpn233.ui.components.TrafficSparkline
import com.neko233.vpn233.ui.theme.LocalVpnPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    vpnStatus: VpnStatus,
    settings: AppSettings,
    activeProfile: ProfileItem?,
    currentNodeName: String,
    groups: List<ProxyGroupUi>,
    traffic: TrafficStats,
    speedHistory: List<Pair<Long, Long>>,
    coreVersion: String,
    lastError: String?,
    onToggleVpn: () -> Unit,
    onChangeMode: (RoutingMode) -> Unit,
    onOpenMonitor: () -> Unit,
    onOpenRules: () -> Unit,
    onGoProxies: () -> Unit,
    onGoProfiles: () -> Unit,
    onQuickTest: () -> Unit
) {
    val p = LocalVpnPalette.current
    val ctx = LocalContext.current
    val pm = androidx.compose.runtime.remember { ctx.getSystemService(PowerManager::class.java) }
    val ignoringBattery = androidx.compose.runtime.remember(pm, vpnStatus) {
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
    }
    val running = vpnStatus == VpnStatus.RUNNING
    val busy = vpnStatus == VpnStatus.STARTING || vpnStatus == VpnStatus.STOPPING

    val statusColor by animateColorAsState(
        targetValue = when (vpnStatus) {
            VpnStatus.RUNNING -> p.accent
            VpnStatus.STARTING -> p.accentSecondary
            VpnStatus.STOPPING -> p.warning
            VpnStatus.STOPPED -> p.textSecondary
        },
        label = "statusColor"
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val ringScale by pulse.animateFloat(
        initialValue = 1.0f,
        targetValue = if (running || busy) 1.24f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringScale"
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = if (running || busy) 0.42f else 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Brand Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.linearGradient(listOf(p.accent, p.accentSecondary))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.ShieldBolt,
                        contentDescription = null,
                        tint = Color(0xFF051314),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "vpn233",
                        color = p.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = coreVersion.substringBefore(" ("),
                        color = p.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Surface(
                onClick = onOpenMonitor,
                shape = RoundedCornerShape(14.dp),
                color = p.card,
                border = BorderStroke(1.dp, p.border)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = AppIcons.Monitor,
                        contentDescription = "实时监控",
                        tint = if (running) p.accent else p.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "连接/日志",
                        color = p.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (!ignoringBattery) {
            Surface(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    }
                    runCatching { ctx.startActivity(intent) }
                },
                shape = RoundedCornerShape(14.dp),
                color = p.warning.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, p.warning.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("关闭电池优化，确保息屏及后台稳定不断连", color = p.warning, fontSize = 11.sp)
                    Text("去设置 →", color = p.warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (!lastError.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = p.danger.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, p.danger.copy(alpha = 0.4f))
            ) {
                Text(
                    text = lastError,
                    color = p.danger,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }
        }

        // Hero Connection Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = p.card,
            border = BorderStroke(1.dp, if (running) p.accent.copy(alpha = 0.55f) else p.border)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = if (running) {
                                listOf(p.accent.copy(alpha = 0.14f), Color.Transparent)
                            } else {
                                listOf(p.cardElevated.copy(alpha = 0.45f), Color.Transparent)
                            }
                        )
                    )
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = statusColor.copy(alpha = 0.14f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = when (vpnStatus) {
                                        VpnStatus.RUNNING -> "隧道已连接 · ${settings.tunStack.label}"
                                        VpnStatus.STARTING -> "正在建立隧道..."
                                        VpnStatus.STOPPING -> "正在断开..."
                                        VpnStatus.STOPPED -> "未连接"
                                    },
                                    color = statusColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = activeProfile?.name ?: "未配置节点文件",
                            color = p.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        val nodeSubtitle = when {
                            activeProfile == null -> "点击右侧或「配置」导入订阅"
                            running && currentNodeName.isNotBlank() -> "当前出站: $currentNodeName"
                            running -> "运行模式: ${settings.routingMode.labelZh}"
                            else -> "点击右侧按钮一键连接"
                        }
                        Text(
                            text = nodeSubtitle,
                            color = p.textSecondary,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Box(contentAlignment = Alignment.Center) {
                        if (running || busy) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .scale(ringScale)
                                    .clip(CircleShape)
                                    .background(statusColor.copy(alpha = ringAlpha))
                            )
                        }
                        Surface(
                            onClick = {
                                if (activeProfile == null) onGoProfiles() else onToggleVpn()
                            },
                            enabled = !busy,
                            shape = CircleShape,
                            color = if (running) p.accent else p.cardElevated,
                            border = BorderStroke(
                                1.5.dp,
                                if (running) p.accent else p.accent.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .size(68.dp)
                                .semantics {
                                    contentDescription = if (running) "vpn233_disconnect" else "vpn233_connect"
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (activeProfile == null) AppIcons.Plus else AppIcons.Power,
                                    contentDescription = if (running) "断开" else "连接",
                                    tint = if (running) Color(0xFF042117) else p.accent,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Routing Mode Segmented Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = p.card,
            border = BorderStroke(1.dp, p.border)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RoutingMode.entries.forEach { mode ->
                    val selected = settings.routingMode == mode
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(13.dp))
                            .clickable { onChangeMode(mode) },
                        shape = RoundedCornerShape(13.dp),
                        color = if (selected) p.accent.copy(alpha = 0.18f) else Color.Transparent,
                        border = if (selected) BorderStroke(1.dp, p.accent.copy(alpha = 0.55f)) else null
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${mode.labelZh}模式",
                                color = if (selected) p.accent else p.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = mode.labelEn,
                                color = p.textSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        // Real-time Speed & Sparkline Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = p.card,
            border = BorderStroke(1.dp, p.border)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricColumn(
                        label = "↓ 下载速率",
                        value = Vpn233Service.formatRate(traffic.downNow),
                        sub = "总下载 ${Vpn233Service.formatBytes(traffic.downTotal)}",
                        accent = p.accentSecondary
                    )
                    MetricColumn(
                        label = "↑ 上传速率",
                        value = Vpn233Service.formatRate(traffic.upNow),
                        sub = "总上传 ${Vpn233Service.formatBytes(traffic.upTotal)}",
                        accent = p.warning
                    )
                    MetricColumn(
                        label = "核心内存",
                        value = if (running) Vpn233Service.formatBytes(traffic.memoryBytes) else "--",
                        sub = if (running) "已启用 ${settings.tunStack.label}" else "待机",
                        accent = p.accent
                    )
                }
                Spacer(Modifier.height(12.dp))
                TrafficSparkline(
                    points = speedHistory,
                    downColor = p.accentSecondary,
                    upColor = p.warning,
                    gridColor = p.border.copy(alpha = 0.6f)
                )
            }
        }

        // Active Profile Quota Card (if Subscription-Userinfo present)
        if (activeProfile != null && activeProfile.totalBytes > 0) {
            val used = activeProfile.usedBytes.coerceAtLeast(0L)
            val progress = (used.toFloat() / activeProfile.totalBytes.toFloat()).coerceIn(0f, 1f)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = p.card,
                border = BorderStroke(1.dp, p.border)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("订阅流量配额", color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${Vpn233Service.formatBytes(used)} / ${Vpn233Service.formatBytes(activeProfile.totalBytes)}",
                            color = p.textSecondary,
                            fontSize = 12.sp,
                            style = TextStyle(fontFeatureSettings = "tnum")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(99.dp)),
                        color = if (progress > 0.88f) p.danger else p.accent,
                        trackColor = p.cardElevated
                    )
                    if (activeProfile.expireEpochSec > 0) {
                        Spacer(Modifier.height(6.dp))
                        val expStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(Date(activeProfile.expireEpochSec * 1000L))
                        Text("到期时间: $expStr", color = p.textSecondary, fontSize = 11.sp)
                    }
                }
            }
        }

        // Quick Actions 2x2 Grid
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    icon = AppIcons.Nodes,
                    title = "切换节点",
                    subtitle = if (groups.isNotEmpty()) "${groups.size} 个策略组" else "浏览节点",
                    tint = p.accent,
                    onClick = onGoProxies
                )
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    icon = AppIcons.SpeedBolt,
                    title = "一键测速",
                    subtitle = if (running) "当前主分组" else "启动后可用",
                    tint = p.accentSecondary,
                    onClick = { if (running) onQuickTest() else onGoProxies() }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    icon = AppIcons.Edit,
                    title = "分流规则",
                    subtitle = "自定义 ${settings.customRules.size} 条",
                    tint = p.success,
                    onClick = onOpenRules
                )
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    icon = AppIcons.ConfigDoc,
                    title = "订阅配置",
                    subtitle = activeProfile?.name ?: "导入订阅",
                    tint = p.warning,
                    onClick = onGoProfiles
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    sub: String,
    accent: Color
) {
    val p = LocalVpnPalette.current
    Column {
        Text(text = label, color = p.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
        Text(
            text = sub,
            color = p.textSecondary,
            fontSize = 10.sp,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

@Composable
private fun QuickActionCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit
) {
    val p = LocalVpnPalette.current
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = p.card,
        border = BorderStroke(1.dp, p.border)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                color = p.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = subtitle,
                color = p.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
