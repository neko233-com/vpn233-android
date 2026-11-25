package com.neko233.vpn233.data

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkHelpers {
    fun getLanIpv4Addresses(): List<String> = runCatching {
        val list = ArrayList<String>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
        while (ifaces.hasMoreElements()) {
            val iface = ifaces.nextElement()
            val name = iface.name.orEmpty().lowercase()
            if (!iface.isUp || iface.isLoopback || name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy")) {
                continue
            }
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    addr.hostAddress?.takeIf { it.isNotBlank() }?.let { list.add(it) }
                }
            }
        }
        list.distinct()
    }.getOrDefault(emptyList())
}
