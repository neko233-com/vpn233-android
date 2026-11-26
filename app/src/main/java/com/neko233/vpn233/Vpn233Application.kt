package com.neko233.vpn233

import android.app.Application
import com.neko233.vpn233.core.MihomoBridge
import com.neko233.vpn233.data.GeoDatabaseManager
import com.neko233.vpn233.data.ProfileRepository
import com.neko233.vpn233.data.SettingsStore
import com.neko233.vpn233.service.SubscriptionUpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class Vpn233Application : Application() {
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var geoDatabaseManager: GeoDatabaseManager
        private set
    lateinit var profileRepository: ProfileRepository
        private set

    @Volatile
    private var initialized = false
    private val initLock = Any()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ensureInitialized()
    }

    fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            instance = this
            MihomoBridge.attachContext(this)
            settingsStore = SettingsStore(this)
            geoDatabaseManager = GeoDatabaseManager(this).apply {
                ensureBootstrapAssets()
            }
            MihomoBridge.init(
                geoDatabaseManager.homeDir.absolutePath,
                "v${BuildConfig.MIHOMO_VERSION} (vpn233 ${BuildConfig.VERSION_NAME})"
            )
            profileRepository = ProfileRepository(this)
            initialized = true
            appScope.launch {
                profileRepository.profiles.collect { SubscriptionUpdateScheduler.reconcile(this@Vpn233Application, it) }
            }
        }
    }

    companion object {
        lateinit var instance: Vpn233Application
            private set
    }
}
