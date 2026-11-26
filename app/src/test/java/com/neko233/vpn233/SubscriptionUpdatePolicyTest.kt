package com.neko233.vpn233

import com.neko233.vpn233.data.ProfileItem
import com.neko233.vpn233.data.ProfileSourceType
import com.neko233.vpn233.data.SubscriptionUpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionUpdatePolicyTest {
    private fun profile(id: String, updated: Long, minutes: Int, url: String = "https://example.invalid/sub") =
        ProfileItem(id = id, name = id, sourceType = ProfileSourceType.URL, url = url,
            updatedAt = updated, autoUpdateMinutes = minutes)

    @Test fun schedulesOnlyTheEarliestEnabledSubscription() {
        val items = listOf(profile("disabled", 0, 0), profile("local", 0, 1, ""),
            profile("later", 100_000, 60), profile("earlier", 100_000, 10))
        assertEquals(700_000L, SubscriptionUpdatePolicy.nextDueAt(items) { 0L })
        assertNull(SubscriptionUpdatePolicy.nextDueAt(items.take(2)) { 0L })
    }

    @Test fun aFailedSubscriptionBacksOffWithoutDelayingAnother() {
        val items = listOf(profile("retry", 0, 1), profile("other", 0, 30))
        assertEquals(1_800_000L, SubscriptionUpdatePolicy.nextDueAt(items) { id ->
            if (id == "retry") 3_600_000L else 0L
        })
        assertEquals(15 * 60_000L, SubscriptionUpdatePolicy.retryDelayMs(1))
        assertEquals(30 * 60_000L, SubscriptionUpdatePolicy.retryDelayMs(2))
        assertEquals(4 * 60 * 60_000L, SubscriptionUpdatePolicy.retryDelayMs(30))
    }

    @Test fun futureDatesDoNotOverflowIntoImmediateRuns() {
        assertEquals(Long.MAX_VALUE, SubscriptionUpdatePolicy.dueAt(profile("future", Long.MAX_VALUE - 10, 1), 0L))
    }
}
