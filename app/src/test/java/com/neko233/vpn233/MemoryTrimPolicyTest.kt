package com.neko233.vpn233

import android.content.ComponentCallbacks2
import com.neko233.vpn233.service.MemoryTrimPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrimPolicyTest {
    @Test fun uiHiddenDoesNotForceCollectionButRealPressureDoes() {
        assertFalse(MemoryTrimPolicy.shouldReclaim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, 1_000, -1))
        assertTrue(MemoryTrimPolicy.shouldReclaim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 1_000, -1))
        assertTrue(MemoryTrimPolicy.shouldReclaim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, 1_000, -1))
    }

    @Test fun repeatedPressureIsRateLimited() {
        assertFalse(MemoryTrimPolicy.shouldReclaim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, 20_000, 10_000))
        assertTrue(MemoryTrimPolicy.shouldReclaim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, 70_000, 10_000))
    }
}
