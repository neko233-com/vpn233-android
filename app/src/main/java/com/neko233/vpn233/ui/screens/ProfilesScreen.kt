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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neko233.vpn233.data.ProfileItem
import com.neko233.vpn233.data.ProfileSourceType
import com.neko233.vpn233.data.ProfileUpdateMode
import com.neko233.vpn233.service.Vpn233Service
import com.neko233.vpn233.ui.components.AppIcons
import com.neko233.vpn233.ui.theme.LocalStrings
import com.neko233.vpn233.ui.theme.LocalVpnPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfilesScreen(
    profiles: List<ProfileItem>,
    activeProfileId: String?,
    prefillUrl: String?,
    onConsumePrefillUrl: () -> Unit,
    onActivateProfile: (String) -> Unit,
    onImportUrl: (String, String) -> Unit,
    onSaveYaml: (String?, String, String, ProfileSourceType) -> Unit,
    onUpdateProfile: (ProfileItem) -> Unit,
    onUpdateAll: () -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onShareProfile: (ProfileItem) -> Unit,
    onPickDocument: () -> Unit,
    onToast: (String) -> Unit,
    readYaml: (String) -> String,
    globalMergeYaml: String = "",
    onSaveGlobalMergeYaml: (String) -> Unit = {},
    onReadMergeYaml: (String) -> String = { "" },
    onSaveMergeYaml: (String, String) -> Unit = { _, _ -> },
    onSetUpdateOptions: (String, Int, ProfileUpdateMode, String) -> Unit = { _, _, _, _ -> }
) {
    val p = LocalVpnPalette.current
    val str = LocalStrings.current
    val clipboard = LocalClipboardManager.current

    var showImportDialog by remember(prefillUrl) { mutableStateOf(!prefillUrl.isNullOrBlank()) }
    var importTab by remember { mutableStateOf(0) } // 0 = URL, 1 = YAML text
    var urlInput by remember(prefillUrl) { mutableStateOf(prefillUrl.orEmpty()) }
    var nameInput by remember { mutableStateOf("") }
    var yamlInput by remember { mutableStateOf("") }

    var editingItem by remember { mutableStateOf<ProfileItem?>(null) }
    var editingName by remember { mutableStateOf("") }
    var editingYaml by remember { mutableStateOf("") }

    var mergeEditingItem by remember { mutableStateOf<ProfileItem?>(null) }
    var editingGlobalMerge by remember { mutableStateOf(false) }
    var mergeText by remember { mutableStateOf("") }

    var optionsEditingItem by remember { mutableStateOf<ProfileItem?>(null) }
    var optIntervalMin by remember { mutableStateOf("0") }
    var optMode by remember { mutableStateOf(ProfileUpdateMode.AUTO) }
    var optUa by remember { mutableStateOf("") }

    var renamingItem by remember { mutableStateOf<ProfileItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingItem by remember { mutableStateOf<ProfileItem?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                str.profilesTitle,
                color = p.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = {
                    importTab = 0
                    showImportDialog = true
                },
                shape = RoundedCornerShape(12.dp),
                color = p.accent
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.Plus, null, tint = Color(0xFF042117), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        str.importSubBtn,
                        color = Color(0xFF042117),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = {
                    mergeText = globalMergeYaml
                    editingGlobalMerge = true
                },
                shape = RoundedCornerShape(11.dp),
                color = if (globalMergeYaml.isNotBlank()) p.accent.copy(alpha = 0.14f) else p.card,
                border = BorderStroke(1.dp, if (globalMergeYaml.isNotBlank()) p.accent.copy(alpha = 0.45f) else p.border)
            ) {
                Text(
                    if (globalMergeYaml.isNotBlank()) "${str.globalMergeBtn} · ON" else str.globalMergeBtn,
                    color = if (globalMergeYaml.isNotBlank()) p.accent else p.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                )
            }
            if (profiles.any { it.url.isNotBlank() }) {
                Surface(
                    onClick = onUpdateAll,
                    shape = RoundedCornerShape(11.dp),
                    color = p.card,
                    border = BorderStroke(1.dp, p.border)
                ) {
                    Text(
                        str.updateAllBtn,
                        color = p.accentSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Standalone Clash Subscription & Import Card
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = p.accentSecondary.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, p.accentSecondary.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "支持标准 Clash / Mihomo 订阅与配置",
                            color = p.accentSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "支持订阅 URL 自动解析流量配额与到期时间、剪贴板一键识别导入及本地 YAML 文件校验。",
                            color = p.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                onClick = {
                                    importTab = 0
                                    showImportDialog = true
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = p.accentSecondary
                            ) {
                                Text(
                                    "URL 订阅",
                                    color = Color(0xFF041C24),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                            Surface(
                                onClick = {
                                    val clip = clipboard.getText()?.text.orEmpty().trim()
                                    when {
                                        clip.startsWith("http://", true) || clip.startsWith("https://", true) -> {
                                            onImportUrl(clip, "")
                                        }
                                        clip.isNotBlank() -> {
                                            onSaveYaml(null, "剪贴板配置", clip, ProfileSourceType.CLIPBOARD)
                                        }
                                        else -> onToast("剪贴板为空")
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = p.card,
                                border = BorderStroke(1.dp, p.border)
                            ) {
                                Text(
                                    "剪贴板一键导入",
                                    color = p.textPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                            Surface(
                                onClick = onPickDocument,
                                shape = RoundedCornerShape(10.dp),
                                color = p.card,
                                border = BorderStroke(1.dp, p.border)
                            ) {
                                Text(
                                    "文件",
                                    color = p.textPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (profiles.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = p.card,
                        border = BorderStroke(1.dp, p.border)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(AppIcons.ConfigDoc, null, tint = p.textSecondary, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("暂无配置文件", color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("支持 Clash / Mihomo 订阅 URL、剪贴板粘贴或本地 YAML 文件", color = p.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            items(profiles, key = { it.id }) { item ->
                val isActive = item.id == activeProfileId
                Surface(
                    onClick = { onActivateProfile(item.id) },
                    shape = RoundedCornerShape(18.dp),
                    color = if (isActive) p.accent.copy(alpha = 0.12f) else p.card,
                    border = BorderStroke(
                        if (isActive) 1.5.dp else 1.dp,
                        if (isActive) p.accent else p.border
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isActive) p.accent else p.border)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = item.name,
                                    color = p.textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = p.cardElevated
                            ) {
                                Text(
                                    text = when (item.sourceType) {
                                        ProfileSourceType.URL -> "订阅"
                                        ProfileSourceType.FILE_IMPORT -> "文件"
                                        ProfileSourceType.CLIPBOARD -> "剪贴板"
                                        ProfileSourceType.LOCAL -> "本地"
                                    },
                                    color = p.textSecondary,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        val upd = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt))
                        Text(
                            text = buildString {
                                append("更新: ").append(upd)
                                if (item.url.isNotBlank()) append(" · ").append(item.url)
                            },
                            color = p.textSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (item.totalBytes > 0) {
                            Spacer(Modifier.height(8.dp))
                            val used = item.usedBytes.coerceAtLeast(0L)
                            val prog = (used.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { prog },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)),
                                color = p.accent,
                                trackColor = p.cardElevated
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${Vpn233Service.formatBytes(used)} / ${Vpn233Service.formatBytes(item.totalBytes)}",
                                color = p.textSecondary,
                                fontSize = 10.sp,
                                style = TextStyle(fontFeatureSettings = "tnum")
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.url.isNotBlank()) {
                                ActionPill(icon = AppIcons.Refresh, label = "更新", tint = p.accent) {
                                    onUpdateProfile(item)
                                }
                                ActionPill(
                                    icon = AppIcons.SettingsGear,
                                    label = if (item.autoUpdateMinutes > 0) "策略(${item.autoUpdateMinutes / 60}h)" else "策略",
                                    tint = p.accentSecondary
                                ) {
                                    optionsEditingItem = item
                                    optIntervalMin = item.autoUpdateMinutes.toString()
                                    optMode = item.updateMode
                                    optUa = item.customUserAgent
                                }
                            }
                            ActionPill(icon = AppIcons.Edit, label = "编辑", tint = p.textPrimary) {
                                editingItem = item
                                editingName = item.name
                                editingYaml = readYaml(item.id)
                            }
                            ActionPill(
                                icon = AppIcons.ShieldBolt,
                                label = if (item.hasMergeYaml) "Merge*" else "Merge",
                                tint = if (item.hasMergeYaml) p.accent else p.textPrimary
                            ) {
                                mergeEditingItem = item
                                mergeText = onReadMergeYaml(item.id)
                            }
                            ActionPill(icon = AppIcons.ExportShare, label = "导出", tint = p.textPrimary) {
                                onShareProfile(item)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "重命名",
                                color = p.textSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    renamingItem = item
                                    renameText = item.name
                                }.padding(4.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                AppIcons.Trash,
                                contentDescription = "删除",
                                tint = p.danger,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { deletingItem = item }
                            )
                        }
                    }
                }
            }
        }
    }

    // Import URL / Paste YAML Dialog
    if (showImportDialog) {
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.border,
            focusedTextColor = p.textPrimary,
            unfocusedTextColor = p.textPrimary
        )
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                onConsumePrefillUrl()
            },
            containerColor = p.card,
            title = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("URL 订阅", "粘贴 YAML").forEachIndexed { idx, lbl ->
                        Surface(
                            onClick = { importTab = idx },
                            shape = RoundedCornerShape(10.dp),
                            color = if (importTab == idx) p.accent else p.cardElevated
                        ) {
                            Text(
                                lbl,
                                color = if (importTab == idx) Color(0xFF042117) else p.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("配置名称 (可选)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (importTab == 0) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Clash 订阅链接 (http/https)") },
                            maxLines = 3,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = {
                            val clip = clipboard.getText()?.text.orEmpty().trim()
                            if (clip.startsWith("http")) urlInput = clip
                        }) {
                            Text("从剪贴板粘贴链接", color = p.accentSecondary)
                        }
                    } else {
                        OutlinedTextField(
                            value = yamlInput,
                            onValueChange = { yamlInput = it },
                            label = { Text("YAML 配置全文") },
                            minLines = 6,
                            maxLines = 10,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = {
                            yamlInput = clipboard.getText()?.text.orEmpty()
                        }) {
                            Text("粘贴剪贴板 YAML", color = p.accentSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (importTab == 0 && urlInput.isNotBlank()) {
                        onImportUrl(urlInput.trim(), nameInput.trim())
                        showImportDialog = false
                        urlInput = ""
                        nameInput = ""
                        onConsumePrefillUrl()
                    } else if (importTab == 1 && yamlInput.isNotBlank()) {
                        onSaveYaml(null, nameInput.trim().ifBlank { "剪贴板配置" }, yamlInput, ProfileSourceType.CLIPBOARD)
                        showImportDialog = false
                        yamlInput = ""
                        nameInput = ""
                    }
                }) {
                    Text("导入并校验", color = p.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    onConsumePrefillUrl()
                }) {
                    Text("取消", color = p.textSecondary)
                }
            }
        )
    }

    // Full-screen YAML Editor Dialog
    editingItem?.let { item ->
        Dialog(
            onDismissRequest = { editingItem = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                shape = RoundedCornerShape(20.dp),
                color = p.bg,
                border = BorderStroke(1.dp, p.border)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("编辑 · ${item.name}", color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                onClick = { editingItem = null },
                                shape = RoundedCornerShape(10.dp),
                                color = p.card
                            ) {
                                Text("取消", color = p.textSecondary, modifier = Modifier.padding(12.dp, 7.dp))
                            }
                            Surface(
                                onClick = {
                                    onSaveYaml(item.id, editingName, editingYaml, item.sourceType)
                                    editingItem = null
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = p.accent
                            ) {
                                Text(
                                    "校验并保存",
                                    color = Color(0xFF042117),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(12.dp, 7.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editingYaml,
                        onValueChange = { editingYaml = it },
                        textStyle = TextStyle(
                            color = p.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // Rename Dialog
    renamingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { renamingItem = null },
            containerColor = p.card,
            title = { Text("重命名配置", color = p.textPrimary) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameProfile(item.id, renameText)
                    renamingItem = null
                }) { Text("确定", color = p.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renamingItem = null }) { Text("取消", color = p.textSecondary) }
            }
        )
    }

    // Delete Confirmation
    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            containerColor = p.card,
            title = { Text("删除配置", color = p.textPrimary) },
            text = { Text("确定删除「${item.name}」吗？此操作无法撤销。", color = p.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteProfile(item.id)
                    deletingItem = null
                }) { Text("删除", color = p.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deletingItem = null }) { Text("取消", color = p.textSecondary) }
            }
        )
    }

    // Shared Per-Profile or Global Merge YAML Editor (Clash Verge Style)
    if (mergeEditingItem != null || editingGlobalMerge) {
        val targetProfile = mergeEditingItem
        val dialogTitle = if (targetProfile != null) "Merge 覆写 · ${targetProfile.name}" else "全局 Merge 覆写（全订阅生效）"
        MergeEditorDialog(
            title = dialogTitle,
            mergeText = mergeText,
            onMergeTextChange = { mergeText = it },
            onSave = {
                if (targetProfile != null) {
                    onSaveMergeYaml(targetProfile.id, mergeText)
                } else {
                    onSaveGlobalMergeYaml(mergeText)
                }
                mergeEditingItem = null
                editingGlobalMerge = false
            },
            onDismiss = {
                mergeEditingItem = null
                editingGlobalMerge = false
            }
        )
    }

    // Per-Profile Auto Update & Proxy Options Dialog
    optionsEditingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { optionsEditingItem = null },
            containerColor = p.card,
            title = { Text("更新策略 · ${item.name}", color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("自动更新周期（亮屏/回到前台检测，零后台耗电）", color = p.textSecondary, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(0 to "关闭", 360 to "6h", 720 to "12h", 1440 to "24h").forEach { (mins, lbl) ->
                            val sel = optIntervalMin == mins.toString()
                            Surface(
                                modifier = Modifier.weight(1f),
                                onClick = { optIntervalMin = mins.toString() },
                                shape = RoundedCornerShape(8.dp),
                                color = if (sel) p.accent.copy(alpha = 0.2f) else p.cardElevated
                            ) {
                                Text(lbl, color = if (sel) p.accent else p.textPrimary, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = optIntervalMin,
                        onValueChange = { optIntervalMin = it.filter { ch -> ch.isDigit() } },
                        label = { Text("自定义间隔 (分钟，0=关闭)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("拉取通道模式", color = p.textSecondary, fontSize = 11.sp)
                    ProfileUpdateMode.entries.forEach { m ->
                        val sel = optMode == m
                        Surface(
                            onClick = { optMode = m },
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) p.accentSecondary.copy(alpha = 0.18f) else p.cardElevated,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                m.labelZh,
                                color = if (sel) p.accentSecondary else p.textPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = optUa,
                        onValueChange = { optUa = it },
                        label = { Text("自定义 User-Agent (留空默认 clash-verge)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mins = optIntervalMin.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    onSetUpdateOptions(item.id, mins, optMode, optUa)
                    optionsEditingItem = null
                }) { Text("保存", color = p.accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { optionsEditingItem = null }) { Text("取消", color = p.textSecondary) }
            }
        )
    }
}

@Composable
private fun MergeEditorDialog(
    title: String,
    mergeText: String,
    onMergeTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalVpnPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            shape = RoundedCornerShape(20.dp),
            color = p.bg,
            border = BorderStroke(1.dp, p.border)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        color = p.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            color = p.card
                        ) { Text("取消", color = p.textSecondary, modifier = Modifier.padding(12.dp, 7.dp)) }
                        Surface(
                            onClick = onSave,
                            shape = RoundedCornerShape(10.dp),
                            color = p.accent
                        ) {
                            Text("校验并应用", color = Color(0xFF042117), fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp, 7.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionPill(icon = AppIcons.Plus, label = "插入模板", tint = p.accentSecondary) {
                        if (mergeText.isBlank()) {
                            onMergeTextChange(
                                "prepend-rules:\n  - DOMAIN-SUFFIX,openai.com,PROXY\nappend-rules:\n  - DOMAIN-KEYWORD,internal,DIRECT\nfake-ip-filter:\n  - '*.lan'\n"
                            )
                        }
                    }
                    ActionPill(icon = AppIcons.Trash, label = "清空覆写", tint = p.danger) {
                        onMergeTextChange("")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持 Clash Verge 语法: prepend-rules / append-rules / prepend-proxies / prepend-proxy-groups / nameserver / fake-ip-filter / nameserver-policy，更新订阅后依然保留。",
                    color = p.textSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = mergeText,
                    onValueChange = onMergeTextChange,
                    textStyle = TextStyle(color = p.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    val p = LocalVpnPalette.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = p.cardElevated
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
