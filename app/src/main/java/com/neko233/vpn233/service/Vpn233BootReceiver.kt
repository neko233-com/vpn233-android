package com.neko233.vpn233.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.neko233.vpn233.Vpn233Application

class Vpn233BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val app = context.applicationContext as? Vpn233Application ?: return
            if (app.settingsStore.settings.value.autoStartOnBoot &&
                app.profileRepository.getActiveProfile() != null &&
                VpnService.prepare(context) == null
            ) {
                Vpn233Service.start(context)
            }
        }
    }
}
