package com.neko233.vpn233.service

/** Suppress duplicate callbacks, but refresh DNS when the underlying network changes. */
internal class DnsUpdateGate {
    private var network: Long? = null
    private var servers: List<String> = emptyList()

    @Synchronized
    fun shouldUpdate(networkHandle: Long, newServers: List<String>): Boolean {
        if (newServers.isEmpty() || (network == networkHandle && servers == newServers)) return false
        network = networkHandle
        servers = newServers.toList()
        return true
    }

    @Synchronized
    fun reset() {
        network = null
        servers = emptyList()
    }
}
