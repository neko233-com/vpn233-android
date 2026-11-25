package com.neko233.vpn233.service

import com.neko233.vpn233.Vpn233Application
import com.neko233.vpn233.core.MihomoBridge
import com.neko233.vpn233.data.ProxyNodeUi
import com.neko233.vpn233.data.RoutingMode

internal object RoutingModeCoordinator {
    private val builtins = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL")

    internal fun defaultGlobalTarget(nodes: List<ProxyNodeUi>): String? =
        nodes.firstOrNull { it.name == "PROXY" }?.name
            ?: nodes.firstOrNull { it.isGroup && it.name !in builtins }?.name
            ?: nodes.firstOrNull { it.name !in builtins }?.name

    internal fun globalTarget(nodes: List<ProxyNodeUi>, saved: String?): String? =
        saved?.takeIf { name -> nodes.any { it.name == name } } ?: defaultGlobalTarget(nodes)

    /** Must run under CoreSwitchLock. Preserve an explicitly saved GLOBAL selection. */
    fun apply(app: Vpn233Application, profileId: String, mode: RoutingMode) {
        MihomoBridge.setMode(mode.coreValue)
        if (mode != RoutingMode.GLOBAL) return
        val groups = MihomoBridge.queryGroups(app.settingsStore.settings.value.delayTestUrl).second
        val saved = app.settingsStore.getSelectedNodes(profileId)["GLOBAL"]
        val target = groups.firstOrNull { it.name == "GLOBAL" }?.let { globalTarget(it.proxies, saved) } ?: return
        if (MihomoBridge.selectProxy("GLOBAL", target)) {
            if (saved != target) app.settingsStore.setSelectedNode(profileId, "GLOBAL", target)
        }
    }
}
