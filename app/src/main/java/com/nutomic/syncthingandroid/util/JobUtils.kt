package com.nutomic.syncthingandroid.util

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncTriggerJobService

object JobUtils {
    private const val TAG = "JobUtils"

    /**
     * Schedules SyncTriggerJobService, which starts Syncthing after the delay if [startRun] is
     * true and stops it otherwise.
     */
    @JvmStatic
    fun scheduleSyncTriggerServiceJob(context: Context, delayInSeconds: Int, startRun: Boolean) {
        val delay = delayInSeconds.coerceAtLeast(0)
        val builder = JobInfo.Builder(0, ComponentName(context, SyncTriggerJobService::class.java))
            .setMinimumLatency(delay * 1000L)
        if (startRun) {
            // An int, as SyncTriggerJobService reads it.
            builder.setExtras(PersistableBundle().apply { putInt(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, 1) })
        }
        jobScheduler(context).schedule(builder.build())
        Log.i(TAG, "Scheduled SyncTriggerJobService to run in $delay seconds.")
    }

    @JvmStatic
    fun cancelAllScheduledJobs(context: Context) {
        jobScheduler(context).cancelAll()
    }

    private fun jobScheduler(context: Context) =
        context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
}
