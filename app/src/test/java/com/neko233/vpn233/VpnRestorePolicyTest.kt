package com.neko233.vpn233

import com.neko233.vpn233.service.VpnRestorePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRestorePolicyTest {
    @Test fun updateRestoresOnlyAnActiveVpn() {
        assertTrue(VpnRestorePolicy.shouldStart(isBoot = false, autoStartOnBoot = false, lastRunning = true))
        assertFalse(VpnRestorePolicy.shouldStart(isBoot = false, autoStartOnBoot = true, lastRunning = false))
    }

    @Test fun bootAndLegacyUpdatesRespectExistingPreference() {
        assertFalse(VpnRestorePolicy.shouldStart(isBoot = true, autoStartOnBoot = false, lastRunning = true))
        assertTrue(VpnRestorePolicy.shouldStart(isBoot = true, autoStartOnBoot = true, lastRunning = false))
        assertTrue(VpnRestorePolicy.shouldStart(isBoot = false, autoStartOnBoot = true, lastRunning = null))
        assertFalse(VpnRestorePolicy.shouldStart(isBoot = false, autoStartOnBoot = false, lastRunning = null))
    }
}
