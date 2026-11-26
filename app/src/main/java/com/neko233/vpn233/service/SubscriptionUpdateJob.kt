package com.neko233.vpn233.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.neko233.vpn233.Vpn233Application
import com.neko233.vpn233.core.MihomoBridge
import com.neko233.vpn233.data.VpnStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SubscriptionUpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        SubscriptionUpdateScheduler.started(params.jobId)
        task = scope.launch {
            var needsRetry = false
            try {
                val app = application as Vpn233Application
                val port = app.settingsStore.settings.value.mixedPort
                    .takeIf { Vpn233Service.status.value == VpnStatus.RUNNING && it in 1024..65535 } ?: 0
                val (updatedCount, activeChanged) = app.profileRepository.checkDueAutoUpdates(port)
                Log.i("SubscriptionUpdate", "Background refresh completed: updated=$updatedCount")
                if (activeChanged && Vpn233Service.isActiveOrStarting()) {
                    MihomoBridge.closeAllConnections()
                    Vpn233Service.reload(app)
                }
                needsRetry = !SubscriptionUpdateScheduler.scheduleAfterRun(app, app.profileRepository.profiles.value, params.jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w("SubscriptionUpdate", "Background subscription refresh failed")
                needsRetry = true
            } finally {
                if (isActive) jobFinished(params, needsRetry)
                SubscriptionUpdateScheduler.finished(params.jobId)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel()
        SubscriptionUpdateScheduler.finished(params.jobId)
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
