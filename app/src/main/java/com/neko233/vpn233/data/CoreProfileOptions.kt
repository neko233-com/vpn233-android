package com.neko233.vpn233.data

import org.json.JSONObject

/** Android-owned routing and TUN options remain separate from profile-owned core options. */
internal object CoreProfileOptions {
    fun putOverrides(json: JSONObject, settings: AppSettings) {
        if (settings.followProfileOptions) return
        json.put("unifiedDelay", settings.unifiedDelay)
        json.put("tcpConcurrent", settings.tcpConcurrent)
        json.put("geodataMode", settings.geodataMode)
        json.put("findProcessMode", settings.findProcessMode)
        json.put("sniffing", settings.sniffing)
        json.put("overrideDestination", settings.overrideDestination)
    }
}
