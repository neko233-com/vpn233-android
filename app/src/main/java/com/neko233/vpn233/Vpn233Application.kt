package com.neko233.vpn233

import android.app.Application
import com.neko233.vpn233.core.MihomoBridge
import com.neko233.vpn233.data.GeoDatabaseManager
import com.neko233.vpn233.data.ProfileRepository
import com.neko233.vpn233.data.SettingsStore

class Vpn233Application : Application() {
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var geoDatabaseManager: GeoDatabaseManager
        private set
    lateinit var profileRepository: ProfileRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        MihomoBridge.attachContext(this)
        settingsStore = SettingsStore(this)
        geoDatabaseManager = GeoDatabaseManager(this).apply {
            ensureBootstrapAssets()
        }
        MihomoBridge.init(
            geoDatabaseManager.homeDir.absolutePath,
            "v1.19.34 (vpn233 ${BuildConfig.VERSION_NAME})"
        )
        profileRepository = ProfileRepository(this)
    }

    companion object {
        lateinit var instance: Vpn233Application
            private set
    }
}
