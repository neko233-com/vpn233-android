package com.neko233.vpn233.data

/** Subscription services choose their output format based on this header. */
internal object SubscriptionUserAgent {
    fun select(custom: String?, coreVersion: String, appVersion: String): String =
        custom?.trim()?.takeIf { it.isNotEmpty() } ?: "Mihomo/$coreVersion vpn233/$appVersion"
}
