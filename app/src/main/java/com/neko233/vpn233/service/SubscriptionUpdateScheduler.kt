package com.neko233.vpn233.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.neko233.vpn233.data.ProfileItem
import com.neko233.vpn233.data.SubscriptionRetryStore
import com.neko233.vpn233.data.SubscriptionUpdatePolicy

/** One network-constrained job for the earliest due subscription; Android may defer it in Doze. */
internal object SubscriptionUpdateScheduler {
    const val JOB_A = 23301
    const val JOB_B = 23302
    private const val PREFS = "subscription_job_schedule"
    private val lock = Any()
    private var activeJobId: Int? = null

    fun reconcile(context: Context, profiles: List<ProfileItem>) {
        synchronized(lock) {
            if (activeJobId != null) return
            val scheduled = runCatching { scheduleLocked(context, profiles, null) }.getOrDefault(false)
            if (!scheduled) Log.w("SubscriptionUpdate", "Unable to schedule subscription refresh")
        }
    }

    fun started(jobId: Int) {
        synchronized(lock) { activeJobId = jobId }
    }

    /** Schedule the next run with the other ID before completing this job. */
    fun scheduleAfterRun(context: Context, profiles: List<ProfileItem>, jobId: Int): Boolean =
        synchronized(lock) {
            runCatching { scheduleLocked(context, profiles, jobId) }
                .getOrElse {
                    Log.w("SubscriptionUpdate", "Unable to schedule the next subscription refresh")
                    false
                }
        }

    fun finished(jobId: Int) {
        synchronized(lock) { if (activeJobId == jobId) activeJobId = null }
    }

    private fun scheduleLocked(context: Context, profiles: List<ProfileItem>, runningJobId: Int?): Boolean {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastId = prefs.getInt("job_id", 0)
        val retryStore = SubscriptionRetryStore(app)
        retryStore.prune(profiles.filter { it.url.isNotBlank() && it.autoUpdateMinutes > 0 }.map { it.id }.toSet())
        val dueAt = SubscriptionUpdatePolicy.nextDueAt(profiles, retryStore::retryAfter)
        if (dueAt == null) {
            listOf(JOB_A, JOB_B).filter { it != runningJobId }.forEach(scheduler::cancel)
            prefs.edit().clear().commit()
            return true
        }
        if (runningJobId == null && lastId in listOf(JOB_A, JOB_B) &&
            prefs.getLong("due_at", Long.MIN_VALUE) == dueAt && scheduler.getPendingJob(lastId) != null
        ) return true

        val nextId = when (runningJobId ?: lastId) {
            JOB_A -> JOB_B
            else -> JOB_A
        }
        val now = System.currentTimeMillis()
        val minDelay = if (runningJobId == null) 1_000L else 60_000L
        val delay = if (dueAt <= now) minDelay else (dueAt - now).coerceAtLeast(minDelay)
        val info = JobInfo.Builder(nextId, ComponentName(app, SubscriptionUpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delay)
            .setPersisted(true)
            .setBackoffCriteria(SubscriptionUpdatePolicy.FIRST_RETRY_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        if (scheduler.schedule(info) != JobScheduler.RESULT_SUCCESS) return false
        if (lastId in listOf(JOB_A, JOB_B) && lastId != runningJobId && lastId != nextId) {
            scheduler.cancel(lastId)
        }
        prefs.edit().putInt("job_id", nextId).putLong("due_at", dueAt).commit()
        return true
    }
}
