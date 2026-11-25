package com.neko233.vpn233.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neko233.vpn233.data.CustomRuleEntry
import com.neko233.vpn233.data.ProxyGroupUi
import com.neko233.vpn233.data.RuleItem
import com.neko233.vpn233.data.RulePosition
import com.neko233.vpn233.ui.components.AppIcons
import com.neko233.vpn233.ui.theme.LocalVpnPalette

private val RULE_TYPES = listOf(
    "DOMAIN-SUFFIX",
    "DOMAIN",
    "DOMAIN-KEYWORD",
    "DOMAIN-REGEX",
    "IP-CIDR",
    "IP-CIDR6",
    "GEOIP",
    "GEOSITE",
    "IP-ASN",
    "DST-PORT",
    "PROCESS-NAME",
    "RAW"
)

@Composable
fun RulesDialog(
    customRules: List<CustomRuleEntry>,
    activeRules: List<RuleItem>,
    groups: List<ProxyGroupUi>,
    onRefreshActiveRules: () -> Unit,
    onAddRule: (String, RulePosition) -> Unit,
    onUpdateRule: (String, String, RulePosition) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onMoveRule: (Int, Int) -> Unit,
    onDeleteRule: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalVpnPalette.current
    var tab by remember { mutableStateOf(0) } // 0 = Custom Editable Rules, 1 = Active Core Rules
    var search by remember { mutableStateOf("") }
    var editingEntry by remember { mutableStateOf<CustomRuleEntry?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tab) {
        if (tab == 1) onRefreshActiveRules()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("自定义规则 (${customRules.size})", "生效规则 (${activeRules.size})").forEachIndexed { idx, lbl ->
                            val sel = tab == idx
                            Surface(
                                onClick = { tab = idx },
                                shape = RoundedCornerShape(12.dp),
                                color = if (sel) p.accent else p.card,
                                border = if (sel) null else BorderStroke(1.dp, p.border)
                            ) {
                                Text(
                                    lbl,
                                    color = if (sel) Color(0xFF042117) else p.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(12.dp, 8.dp)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tab == 0) {
                            Surface(
                                onClick = { showCreateDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                color = p.accent
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp, 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(AppIcons.Plus, null, tint = Color(0xFF042117), modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("新增", color = Color(0xFF042117), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Surface(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            color = p.card
                        ) {
                            Icon(AppIcons.Close, "关闭", tint = p.textPrimary, modifier = Modifier.padding(8.dp).size(18.dp))
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
                            if (search.isEmpty()) Text("搜索域名 / IP / 规则类型 / 出站...", color = p.textSecondary, fontSize = 12.sp)
                            BasicTextField(
                                value = search,
                                onValueChange = { search = it },
                                singleLine = true,
                                textStyle = TextStyle(color = p.textPrimary, fontSize = 13.sp),
                                cursorBrush = SolidColor(p.accent),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (tab == 0) {
                    if (customRules.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("暂无自定义规则", color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text("点击右上角「新增」添加规则，对所有配置生效且实时热重载", color = p.textSecondary, fontSize = 12.sp)
                            }
                        }
                    } else {
                        val filtered = remember(customRules, search) {
                            if (search.isBlank()) customRules
                            else customRules.filter { it.ruleLine.contains(search, true) }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(filtered, key = { _, it -> it.id }) { idx, entry ->
                                val parts = entry.ruleLine.split(',')
                                val typeLabel = parts.firstOrNull().orEmpty()
                                val targetLabel = parts.lastOrNull().orEmpty()
                                val targetColor = when (targetLabel.uppercase()) {
                                    "DIRECT" -> p.success
                                    "REJECT", "REJECT-DROP" -> p.danger
                                    else -> p.accentSecondary
                                }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = p.card,
                                    border = BorderStroke(1.dp, if (entry.enabled) p.border else p.border.copy(alpha = 0.4f))
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(shape = RoundedCornerShape(6.dp), color = p.cardElevated) {
                                                    Text(
                                                        entry.position.label,
                                                        color = p.textSecondary,
                                                        fontSize = 10.sp,
                                                        modifier = Modifier.padding(6.dp, 2.dp)
                                                    )
                                                }
                                                Surface(shape = RoundedCornerShape(6.dp), color = targetColor.copy(alpha = 0.18f)) {
                                                    Text(
                                                        targetLabel,
                                                        color = targetColor,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(6.dp, 2.dp)
                                                    )
                                                }
                                            }
                                            Switch(
                                                checked = entry.enabled,
                                                onCheckedChange = { onToggleRule(entry.id, it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color(0xFF042117),
                                                    checkedTrackColor = p.accent
                                                )
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = entry.ruleLine,
                                            color = if (entry.enabled) p.textPrimary else p.textSecondary,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("上移", color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.clickable {
                                                val realIdx = customRules.indexOfFirst { it.id == entry.id }
                                                if (realIdx > 0) onMoveRule(realIdx, realIdx - 1)
                                            })
                                            Text("下移", color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.clickable {
                                                val realIdx = customRules.indexOfFirst { it.id == entry.id }
                                                if (realIdx in 0 until customRules.lastIndex) onMoveRule(realIdx, realIdx + 1)
                                            })
                                            Text("编辑 ($typeLabel)", color = p.accent, fontSize = 11.sp, modifier = Modifier.clickable {
                                                editingEntry = entry
                                            })
                                            Spacer(Modifier.weight(1f))
                                            Icon(
                                                AppIcons.Trash,
                                                "删除",
                                                tint = p.danger,
                                                modifier = Modifier.size(16.dp).clickable { onDeleteRule(entry.id) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val filteredActive = remember(activeRules, search) {
                        if (search.isBlank()) activeRules
                        else activeRules.filter { it.raw.contains(search, true) }
                    }
                    if (filteredActive.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("连接后可查看当前全部生效规则", color = p.textSecondary, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredActive, key = { it.index }) { r ->
                                val targetColor = when (r.adapter.uppercase()) {
                                    "DIRECT" -> p.success
                                    "REJECT", "REJECT-DROP" -> p.danger
                                    else -> p.accentSecondary
                                }
                                Surface(
                                    onClick = {
                                        editingEntry = CustomRuleEntry(
                                            id = "",
                                            ruleLine = r.raw,
                                            enabled = true,
                                            position = RulePosition.PREPEND
                                        )
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = p.card
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "#${r.index + 1}",
                                            color = p.textSecondary,
                                            fontSize = 10.sp,
                                            modifier = Modifier.width(34.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                r.payload.ifBlank { r.type },
                                                color = p.textPrimary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(r.type, color = p.textSecondary, fontSize = 10.sp)
                                        }
                                        Surface(shape = RoundedCornerShape(6.dp), color = targetColor.copy(alpha = 0.16f)) {
                                            Text(
                                                r.adapter,
                                                color = targetColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(7.dp, 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog || editingEntry != null) {
        val existing = editingEntry
        RuleEditorDialog(
            initialRuleLine = existing?.ruleLine ?: "DOMAIN-SUFFIX,,PROXY",
            initialPosition = existing?.position ?: RulePosition.PREPEND,
            groupNames = groups.map { it.name },
            onSave = { line, pos ->
                if (existing != null && existing.id.isNotBlank()) {
                    onUpdateRule(existing.id, line, pos)
                } else {
                    onAddRule(line, pos)
                }
                showCreateDialog = false
                editingEntry = null
            },
            onDismiss = {
                showCreateDialog = false
                editingEntry = null
            }
        )
    }
}

@Composable
private fun RuleEditorDialog(
    initialRuleLine: String,
    initialPosition: RulePosition,
    groupNames: List<String>,
    onSave: (String, RulePosition) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalVpnPalette.current
    val initialParts = initialRuleLine.split(',')
    val initType = initialParts.firstOrNull()?.uppercase()?.takeIf { it in RULE_TYPES } ?: if (initialRuleLine.startsWith("MATCH", true)) "RAW" else "DOMAIN-SUFFIX"
    val initPayload = if (initType != "RAW" && initialParts.size >= 2) initialParts[1] else ""
    val initTarget = if (initType != "RAW" && initialParts.size >= 3) initialParts[2] else "PROXY"
    val initNoResolve = initialParts.any { it.equals("no-resolve", true) }

    var selectedType by remember { mutableStateOf(initType) }
    var payload by remember { mutableStateOf(initPayload) }
    var target by remember { mutableStateOf(initTarget) }
    var rawLine by remember { mutableStateOf(initialRuleLine) }
    var noResolve by remember { mutableStateOf(initNoResolve) }
    var position by remember { mutableStateOf(initialPosition) }

    val targetOptions = remember(groupNames) {
        (listOf("PROXY", "DIRECT", "REJECT") + groupNames).distinct()
    }

    val composedRule = if (selectedType == "RAW") {
        rawLine.trim()
    } else {
        buildString {
            append(selectedType).append(',').append(payload.trim()).append(',').append(target.trim())
            if (noResolve && selectedType in listOf("IP-CIDR", "IP-CIDR6", "GEOIP", "IP-ASN")) {
                append(",no-resolve")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        title = { Text("编辑分流规则", color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("规则类型", color = p.textSecondary, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RULE_TYPES.forEach { t ->
                        val sel = selectedType == t
                        Surface(
                            onClick = { selectedType = t },
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) p.accent else p.cardElevated
                        ) {
                            Text(
                                t,
                                color = if (sel) Color(0xFF042117) else p.textPrimary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(9.dp, 6.dp)
                            )
                        }
                    }
                }

                if (selectedType == "RAW") {
                    OutlinedTextField(
                        value = rawLine,
                        onValueChange = { rawLine = it },
                        label = { Text("完整规则表达式 (支持 AND/OR/SUB-RULE)") },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it },
                        label = { Text("匹配值 (域名 / CIDR / CN / 端口 / 包名)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("出站策略", color = p.textSecondary, fontSize = 11.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        targetOptions.forEach { opt ->
                            val sel = target == opt
                            Surface(
                                onClick = { target = opt },
                                shape = RoundedCornerShape(8.dp),
                                color = if (sel) p.accentSecondary else p.cardElevated
                            ) {
                                Text(
                                    opt,
                                    color = if (sel) Color(0xFF041C24) else p.textPrimary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(9.dp, 6.dp)
                                )
                            }
                        }
                    }
                    if (selectedType in listOf("IP-CIDR", "IP-CIDR6", "GEOIP", "IP-ASN")) {
                        Row(
                            modifier = Modifier.clickable { noResolve = !noResolve },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = noResolve, onCheckedChange = { noResolve = it })
                            Text("跳过 DNS 解析 (no-resolve)", color = p.textPrimary, fontSize = 12.sp)
                        }
                    }
                }

                Text("优先级位置", color = p.textSecondary, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    RulePosition.entries.forEach { pos ->
                        val sel = position == pos
                        Surface(
                            modifier = Modifier.weight(1f),
                            onClick = { position = pos },
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) p.accent.copy(alpha = 0.18f) else p.cardElevated,
                            border = if (sel) BorderStroke(1.dp, p.accent) else null
                        ) {
                            Text(
                                pos.label,
                                color = if (sel) p.accent else p.textPrimary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Surface(shape = RoundedCornerShape(8.dp), color = p.cardElevated) {
                    Text(
                        "预览: $composedRule",
                        color = p.accent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val valid = if (selectedType == "RAW") rawLine.isNotBlank() else payload.isNotBlank() && target.isNotBlank()
                    if (valid) onSave(composedRule, position)
                }
            ) {
                Text("保存并应用", color = p.accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = p.textSecondary) }
        }
    )
}
