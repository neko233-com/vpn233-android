package com.neko233.vpn233.data

import android.content.Context

/** Only profile IDs and retry times are stored; subscription URLs never enter job extras. */
internal class SubscriptionRetryStore(context: Context) {
    private val prefs = context.getSharedPreferences("subscription_retry", Context.MODE_PRIVATE)

    fun retryAfter(id: String): Long = prefs.getLong("until:$id", 0L)

    fun failed(id: String, now: Long) {
        val count = prefs.getInt("count:$id", 0).coerceIn(0, 4) + 1
        val delay = SubscriptionUpdatePolicy.retryDelayMs(count)
        val until = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
        prefs.edit().putInt("count:$id", count).putLong("until:$id", until).commit()
    }

    fun succeeded(id: String) {
        prefs.edit().remove("count:$id").remove("until:$id").commit()
    }

    fun prune(activeIds: Set<String>) {
        val stale = prefs.all.keys.filter { key ->
            (key.startsWith("count:") || key.startsWith("until:")) && key.substringAfter(':') !in activeIds
        }
        if (stale.isNotEmpty()) {
            val editor = prefs.edit()
            stale.forEach(editor::remove)
            editor.commit()
        }
    }
}
