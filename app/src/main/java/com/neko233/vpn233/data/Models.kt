package com.neko233.vpn233.data

enum class VpnStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING
}

enum class RoutingMode(val coreValue: String, val labelZh: String, val labelEn: String) {
    RULE("rule", "规则", "Rule"),
    GLOBAL("global", "全局", "Global"),
    DIRECT("direct", "直连", "Direct");

    companion object {
        fun fromCore(value: String): RoutingMode =
            entries.firstOrNull { it.coreValue.equals(value, ignoreCase = true) } ?: RULE
    }
}

enum class TunStackMode(val coreValue: String, val label: String, val desc: String) {
    MIXED("mixed", "Mixed", "TCP 用 System、UDP 用 gVisor，兼顾性能与兼容性"),
    GVISOR("gvisor", "gVisor", "用户态完整协议栈，兼容性最佳"),
    SYSTEM("system", "System", "内核网络栈转发，延迟与功耗最低");

    companion object {
        fun fromCore(value: String): TunStackMode =
            entries.firstOrNull { it.coreValue.equals(value, ignoreCase = true) } ?: MIXED
    }
}

enum class AccessControlMode(val label: String) {
    ACCEPT_ALL("所有应用走代理"),
    ALLOW_SELECTED("仅已选应用走代理 (白名单)"),
    DENY_SELECTED("绕过已选应用 (黑名单)")
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    DARK("深邃暗黑"),
    LIGHT("清透浅色")
}

enum class ProfileSourceType {
    URL,
    LOCAL,
    CLIPBOARD,
    FILE_IMPORT
}

data class ProfileItem(
    val id: String,
    val name: String,
    val sourceType: ProfileSourceType,
    val url: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val autoUpdateMinutes: Int = 0,
    val uploadBytes: Long = -1L,
    val downloadBytes: Long = -1L,
    val totalBytes: Long = -1L,
    val expireEpochSec: Long = -1L
) {
    val usedBytes: Long
        get() = if (uploadBytes >= 0 && downloadBytes >= 0) uploadBytes + downloadBytes else -1L
}

data class AppSettings(
    val routingMode: RoutingMode = RoutingMode.RULE,
    val tunStack: TunStackMode = TunStackMode.MIXED,
    val allowIpv6: Boolean = false,
    val bypassPrivate: Boolean = true,
    val dnsHijacking: Boolean = true,
    val appendSystemDns: Boolean = true,
    val forceDnsMapping: Boolean = false,
    val systemProxy: Boolean = true,
    val mixedPort: Int = 7890,
    val sniffing: Boolean = true,
    val overrideDestination: Boolean = true,
    val unifiedDelay: Boolean = true,
    val tcpConcurrent: Boolean = true,
    val geodataMode: Boolean = false,
    val findProcessMode: String = "off",
    val logLevel: String = "warning",
    val accessControlMode: AccessControlMode = AccessControlMode.ACCEPT_ALL,
    val accessControlPackages: Set<String> = emptySet(),
    val allowBypass: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val autoStartOnBoot: Boolean = false,
    val delayTestUrl: String = "https://www.gstatic.com/generate_204",
    val customOverrideRules: List<String> = emptyList(),
    val customDnsNameservers: List<String> = emptyList(),
    val showTrafficInNotification: Boolean = true
)

data class ProxyNodeUi(
    val name: String,
    val type: String,
    val delay: Int,
    val isGroup: Boolean,
    val alive: Boolean
)

data class ProxyGroupUi(
    val name: String,
    val type: String,
    val now: String,
    val icon: String,
    val selectable: Boolean,
    val proxies: List<ProxyNodeUi>
)

data class TrafficStats(
    val upNow: Long = 0L,
    val downNow: Long = 0L,
    val upTotal: Long = 0L,
    val downTotal: Long = 0L,
    val memoryBytes: Long = 0L
)

data class ConnectionItem(
    val id: String,
    val host: String,
    val dstIp: String,
    val dstPort: String,
    val network: String,
    val type: String,
    val rule: String,
    val rulePayload: String,
    val chains: List<String>,
    val upload: Long,
    val download: Long,
    val process: String,
    val start: String
)

data class ProviderItem(
    val name: String,
    val kind: String,
    val vehicleType: String,
    val count: Int,
    val updatedAt: Long
)

data class CoreLogEntry(
    val id: Long,
    val timeText: String,
    val level: String,
    val message: String
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

data class GeoDbStatus(
    val geoIpBytes: Long,
    val geoSiteBytes: Long,
    val asnBytes: Long
) {
    val isGeoIpFull: Boolean get() = geoIpBytes > 1_500_000L
    val isGeoSiteFull: Boolean get() = geoSiteBytes > 800_000L
    val hasAsn: Boolean get() = asnBytes > 100_000L
}
