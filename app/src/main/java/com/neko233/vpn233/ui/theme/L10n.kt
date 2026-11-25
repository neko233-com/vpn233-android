package com.neko233.vpn233.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.neko233.vpn233.data.AppLanguage
import java.util.Locale

data class UiStrings(
    val isEn: Boolean,
    val tabHome: String = if (isEn) "Connect" else "连接",
    val tabProxies: String = if (isEn) "Proxies" else "节点",
    val tabProfiles: String = if (isEn) "Profiles" else "配置",
    val tabSettings: String = if (isEn) "Settings" else "设置",
    val monitorBtn: String = if (isEn) "Traffic/Diag" else "连接/诊断",
    val batteryBanner: String = if (isEn) "Disable battery optimization for reliable background uptime" else "关闭电池优化，确保息屏及后台稳定不断连",
    val batteryGoSetting: String = if (isEn) "Grant →" else "去设置 →",
    val statusConnected: String = if (isEn) "Connected" else "隧道已连接",
    val statusStarting: String = if (isEn) "Connecting..." else "正在建立隧道...",
    val statusStopping: String = if (isEn) "Stopping..." else "正在断开...",
    val statusStopped: String = if (isEn) "Disconnected" else "未连接",
    val modeRule: String = if (isEn) "Rule Mode" else "规则模式",
    val modeGlobal: String = if (isEn) "Global Mode" else "全局模式",
    val modeDirect: String = if (isEn) "Direct Mode" else "直连模式",
    val downSpeed: String = if (isEn) "↓ Download" else "↓ 下载速率",
    val upSpeed: String = if (isEn) "↑ Upload" else "↑ 上传速率",
    val coreMem: String = if (isEn) "Core Mem" else "核心内存",
    val qaProxies: String = if (isEn) "Switch Node" else "切换节点",
    val qaSpeedTest: String = if (isEn) "Latency Test" else "一键测速",
    val qaRules: String = if (isEn) "Routing Rules" else "分流规则",
    val qaProfiles: String = if (isEn) "Subscriptions" else "订阅配置",
    val proxiesTitle: String = if (isEn) "Proxy Nodes" else "节点代理",
    val profilesTitle: String = if (isEn) "Profiles & Subs" else "订阅与配置",
    val settingsTitle: String = if (isEn) "Settings & Engine" else "设置与增强",
    val noProfileTitle: String = if (isEn) "No Profile Selected" else "未配置节点文件",
    val noProfileSub: String = if (isEn) "Tap right button or Profiles to import" else "点击右侧或「配置」导入订阅",
    val outboundPrefix: String = if (isEn) "Outbound" else "当前出站",
    val modePrefix: String = if (isEn) "Mode" else "运行模式",
    val tapConnectHint: String = if (isEn) "Tap power button to connect" else "点击右侧按钮一键连接",
    val totalDownPrefix: String = if (isEn) "Total Down" else "总下载",
    val totalUpPrefix: String = if (isEn) "Total Up" else "总上传",
    val standbyText: String = if (isEn) "Idle" else "待机",
    val subQuotaTitle: String = if (isEn) "Subscription Quota" else "订阅流量配额",
    val expirePrefix: String = if (isEn) "Expires" else "到期时间",
    val browseNodes: String = if (isEn) "Browse nodes" else "浏览节点",
    val activeGroupLabel: String = if (isEn) "Active group" else "当前主分组",
    val availAfterStart: String = if (isEn) "Connect first" else "启动后可用",
    val importSubHint: String = if (isEn) "Import subscription" else "导入订阅",
    val globalMergeBtn: String = if (isEn) "Global Merge" else "全局覆写",
    val updateAllBtn: String = if (isEn) "Update All" else "全部更新",
    val importSubBtn: String = if (isEn) "Import" else "导入订阅"
) {
    fun groupsCountText(n: Int): String = if (isEn) "$n proxy groups" else "$n 个策略组"
    fun customRulesCountText(n: Int): String = if (isEn) "$n custom rules" else "自定义 $n 条"
}

fun stringsFor(language: AppLanguage): UiStrings {
    val isEn = when (language) {
        AppLanguage.ZH -> false
        AppLanguage.EN -> true
        AppLanguage.SYSTEM -> Locale.getDefault().language.equals("en", ignoreCase = true)
    }
    return UiStrings(isEn = isEn)
}

val LocalStrings = staticCompositionLocalOf { UiStrings(isEn = false) }
