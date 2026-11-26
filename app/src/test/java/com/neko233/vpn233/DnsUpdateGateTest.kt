package com.neko233.vpn233

import com.neko233.vpn233.service.DnsUpdateGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsUpdateGateTest {
    @Test fun duplicateDnsCallbacksAreSuppressedButNetworkChangesRefreshDns() {
        val gate = DnsUpdateGate()
        val servers = listOf("203.0.113.1", "203.0.113.2")
        assertFalse(gate.shouldUpdate(1, emptyList()))
        assertTrue(gate.shouldUpdate(1, servers))
        assertFalse(gate.shouldUpdate(1, servers))
        assertTrue(gate.shouldUpdate(2, servers))
        assertTrue(gate.shouldUpdate(2, servers.reversed()))
        gate.reset()
        assertTrue(gate.shouldUpdate(2, servers.reversed()))
    }
}
