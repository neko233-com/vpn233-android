package com.neko233.vpn233

import com.neko233.vpn233.data.SubscriptionUserAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUserAgentTest {
    @Test
    fun defaultIdentifiesMihomoWithoutClaimingToBeAnotherClient() {
        val ua = SubscriptionUserAgent.select(null, "1.19.34", "1.4.0")
        assertTrue(ua.startsWith("Mihomo/1.19.34"))
        assertFalse(ua.contains("clash-verge", ignoreCase = true))
    }

    @Test
    fun explicitUserAgentIsPreserved() {
        assertEquals("custom-client/1", SubscriptionUserAgent.select(" custom-client/1 ", "1.19.34", "1.4.0"))
    }
}
