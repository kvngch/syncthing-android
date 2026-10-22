package com.nutomic.syncthingandroid.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Started by the JobScheduler at the start or the end of a time window, see
 * JobUtils.scheduleSyncTriggerServiceJob: tells RunConditionMonitor to start or stop Syncthing.
 */
class SyncTriggerJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val intent = Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
        // An int, as JobUtils writes it.
        if (params.extras.getInt(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, 0) == 1) {
            intent.putExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        // The work is done once the broadcast is sent: returning true without ever calling
        // jobFinished() kept the wakelock until the system timeout.
        return false
    }

    // Nothing left running to reschedule: the next window is scheduled by RunConditionMonitor.
    override fun onStopJob(params: JobParameters): Boolean = false
}
