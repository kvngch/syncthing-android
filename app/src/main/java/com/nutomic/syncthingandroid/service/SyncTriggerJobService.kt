package com.nutomic.syncthingandroid.service

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Started by the JobScheduler at the start or the end of a time window, see
 * JobUtils.scheduleSyncTriggerServiceJob: tells RunConditionMonitor to start or stop Syncthing.
 */
class SyncTriggerJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        // An int, as JobUtils writes it.
        RunConditionMonitor.fireSyncTrigger(params.extras.getInt(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, 0) == 1)
        // The work is done once the trigger is posted: returning true without ever calling
        // jobFinished() kept the wakelock until the system timeout.
        return false
    }

    // Nothing left running to reschedule: the next window is scheduled by RunConditionMonitor.
    override fun onStopJob(params: JobParameters): Boolean = false
}
