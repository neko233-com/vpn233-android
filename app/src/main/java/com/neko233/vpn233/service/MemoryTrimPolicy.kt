package com.neko233.vpn233.service

import android.content.ComponentCallbacks2

internal object MemoryTrimPolicy {
    const val MIN_INTERVAL_MS = 60_000L

    fun shouldReclaim(level: Int, now: Long, last: Long): Boolean {
        val pressure = level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        return pressure && (last < 0L || now < last || now - last >= MIN_INTERVAL_MS)
    }
}
