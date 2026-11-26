package com.neko233.vpn233

import com.neko233.vpn233.data.AppSettings
import com.neko233.vpn233.data.CoreProfileOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CoreProfileOptionsTest {
    @Test
    fun profileModeLeavesCoreOptionsToTheSubscription() {
        val obj = JSONObject().put("mode", "rule").put("ipv6", false)
        CoreProfileOptions.putOverrides(obj, AppSettings(followProfileOptions = true))
        assertFalse(obj.has("geodataMode"))
        assertFalse(obj.has("findProcessMode"))
        assertFalse(obj.has("sniffing"))
        assertEquals("rule", obj.getString("mode"))
    }

    @Test
    fun explicitAppSettingsStillOverrideCoreOptions() {
        val obj = JSONObject()
        CoreProfileOptions.putOverrides(obj, AppSettings(followProfileOptions = false, geodataMode = true, findProcessMode = "strict"))
        assertEquals(true, obj.getBoolean("geodataMode"))
        assertEquals("strict", obj.getString("findProcessMode"))
    }
}
