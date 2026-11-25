package com.neko233.vpn233.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.neko233.vpn233.service.Vpn233Service
import com.neko233.vpn233.ui.components.AppIcons
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
    readYaml: (String) -> String
) {
    val p = LocalVpnPalette.current
    val clipboard = LocalClipboardManager.current

    var showImportDialog by remember(prefillUrl) { mutableStateOf(!prefillUrl.isNullOrBlank()) }
    var importTab by remember { mutableStateOf(0) } // 0 = URL, 1 = YAML text
    var urlInput by remember(prefillUrl) { mutableStateOf(prefillUrl.orEmpty()) }
    var nameInput by remember { mutableStateOf("") }
    var yamlInput by remember { mutableStateOf("") }

    var editingItem by remember { mutableStateOf<ProfileItem?>(null) }
    var editingName by remember { mutableStateOf("") }
    var editingYaml by remember { mutableStateOf("") }

    var renamingItem by remember { mutableStateOf<ProfileItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingItem by remember { mutableStateOf<ProfileItem?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("订阅与配置", color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profiles.any { it.url.isNotBlank() }) {
                    Surface(
                        onClick = onUpdateAll,
                        shape = RoundedCornerShape(12.dp),
                        color = p.card,
                        border = BorderStroke(1.dp, p.border)
                    ) {
                        Text(
                            "全部更新",
                            color = p.accentSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                        )
                    }
                }
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
                        Text("导入订阅", color = Color(0xFF042117), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
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
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.url.isNotBlank()) {
                                ActionPill(icon = AppIcons.Refresh, label = "更新", tint = p.accent) {
                                    onUpdateProfile(item)
                                }
                            }
                            ActionPill(icon = AppIcons.Edit, label = "编辑", tint = p.textPrimary) {
                                editingItem = item
                                editingName = item.name
                                editingYaml = readYaml(item.id)
                            }
                            ActionPill(icon = AppIcons.ExportShare, label = "导出", tint = p.textPrimary) {
                                onShareProfile(item)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "重命名",
                                color = p.textSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    renamingItem = item
                                    renameText = item.name
                                }.padding(4.dp)
                            )
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
