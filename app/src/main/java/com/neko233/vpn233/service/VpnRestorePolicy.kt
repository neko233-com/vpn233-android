package com.neko233.vpn233.service

/** Preserve an explicit disconnect across updates while keeping older boot settings compatible. */
internal object VpnRestorePolicy {
    fun shouldStart(isBoot: Boolean, autoStartOnBoot: Boolean, lastRunning: Boolean?): Boolean =
        if (isBoot) autoStartOnBoot else lastRunning ?: autoStartOnBoot
}
