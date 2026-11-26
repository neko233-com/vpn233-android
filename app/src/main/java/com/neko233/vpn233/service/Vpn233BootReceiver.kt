package com.neko233.vpn233.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.neko233.vpn233.Vpn233Application

class Vpn233BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val app = context.applicationContext as? Vpn233Application ?: return
            val isBoot = action == Intent.ACTION_BOOT_COMPLETED
            val shouldStart = VpnRestorePolicy.shouldStart(
                isBoot,
                app.settingsStore.settings.value.autoStartOnBoot,
                VpnRunState.lastRunning(context)
            )
            Log.i("Vpn233Restore", "event=$action shouldStart=$shouldStart")
            if (isBoot && !shouldStart) VpnRunState.setRunning(context, false)
            if (shouldStart &&
                app.profileRepository.getActiveProfile() != null &&
                VpnService.prepare(context) == null
            ) {
                Vpn233Service.start(context)
            }
        }
    }
}
