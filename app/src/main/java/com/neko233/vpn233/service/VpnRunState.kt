package com.neko233.vpn233.service

import android.content.Context
import android.util.Log

/** Remembers the last settled VPN state across process death and package replacement. */
internal object VpnRunState {
    private const val PREFS = "vpn_run_state"
    private const val RUNNING = "running"

    fun lastRunning(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(RUNNING)) prefs.getBoolean(RUNNING, false) else null
    }

    fun setRunning(context: Context, running: Boolean) {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(RUNNING, running).commit()) {
            Log.e("VpnRunState", "Unable to save VPN run state")
        }
    }
}
