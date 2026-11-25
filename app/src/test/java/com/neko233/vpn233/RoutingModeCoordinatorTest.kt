package com.neko233.vpn233

import com.neko233.vpn233.data.ProxyNodeUi
import com.neko233.vpn233.service.RoutingModeCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingModeCoordinatorTest {
    private fun node(name: String, group: Boolean = false) = ProxyNodeUi(name, "", -1, group, true)

    @Test
    fun globalModePrefersTheProxyGroupInsteadOfDirect() {
        val nodes = listOf(node("DIRECT"), node("REJECT"), node("Node-A"), node("PROXY", true))
        assertEquals("PROXY", RoutingModeCoordinator.defaultGlobalTarget(nodes))
        assertEquals("Group", RoutingModeCoordinator.defaultGlobalTarget(listOf(node("DIRECT"), node("Node-A"), node("Group", true))))
        assertEquals("Node-A", RoutingModeCoordinator.defaultGlobalTarget(listOf(node("DIRECT"), node("Node-A"))))
        assertNull(RoutingModeCoordinator.defaultGlobalTarget(listOf(node("DIRECT"), node("REJECT"))))
        assertEquals("DIRECT", RoutingModeCoordinator.globalTarget(nodes, "DIRECT"))
        assertEquals("PROXY", RoutingModeCoordinator.globalTarget(nodes, "removed-node"))
    }
}
