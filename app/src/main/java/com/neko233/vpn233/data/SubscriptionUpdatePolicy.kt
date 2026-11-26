package com.neko233.vpn233.data

/** Absolute due times let Android batch work without a repeating in-process timer. */
internal object SubscriptionUpdatePolicy {
    const val FIRST_RETRY_MS = 15 * 60_000L
    const val MAX_RETRY_MS = 4 * 60 * 60_000L

    fun dueAt(profile: ProfileItem, retryAfter: Long): Long? {
        if (profile.url.isBlank() || profile.autoUpdateMinutes <= 0) return null
        val interval = profile.autoUpdateMinutes.toLong() * 60_000L
        val lastUpdate = profile.updatedAt.coerceAtLeast(0L)
        val due = if (lastUpdate > Long.MAX_VALUE - interval) Long.MAX_VALUE else lastUpdate + interval
        return maxOf(due, retryAfter)
    }

    fun nextDueAt(profiles: List<ProfileItem>, retryAfter: (String) -> Long): Long? =
        profiles.asSequence().mapNotNull { dueAt(it, retryAfter(it.id)) }.minOrNull()

    fun retryDelayMs(failureCount: Int): Long {
        val shift = (failureCount.coerceAtLeast(1) - 1).coerceAtMost(4)
        return (FIRST_RETRY_MS * (1L shl shift)).coerceAtMost(MAX_RETRY_MS)
    }
}
