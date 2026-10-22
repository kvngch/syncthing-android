package com.nutomic.syncthingandroid.service

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.JobUtils
import java.lang.ref.WeakReference

/**
 * Decides whether Syncthing should run, from the run conditions set by the user and the state of
 * the device: network, power source, power saving, auto sync, flight mode, time schedule. The
 * state is read on creation, then on each change.
 */
class RunConditionMonitor(
    private val context: Context,
    private val onShouldRunChangedListener: OnShouldRunChangedListener?,
    private val onSyncPreconditionChangedListener: OnSyncPreconditionChangedListener?,
) {
    fun interface OnShouldRunChangedListener {
        fun onShouldRunDecisionChanged(shouldRun: Boolean)
    }

    fun interface OnSyncPreconditionChangedListener {
        fun onSyncPreconditionChanged(runConditionMonitor: RunConditionMonitor)
    }

    private class Condition(val met: Boolean, val explanation: String = "")

    private class LocationUnavailableException : Exception()

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val res = context.resources
    private val verboseLog = AppPrefs.getPrefVerboseLog(preferences)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Re-evaluates the run conditions on the main thread, where the listeners expect it.
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { updateShouldRunDecision() }

    var runDecisionExplanation = ""
        private set

    private var isShutDown = false

    /**
     * Only relevant if the user has enabled turning Syncthing on by
     * time schedule for a specific amount of time periodically.
     * Holds true if we are within a "SyncthingNative should run" time frame.
     * Initial status false because we check if the last sync was more than one hour ago on app start.
     */
    private var timeConditionMatch = false

    // Avoid re-scheduling start if run conditions change while already running.
    private var runAllowedStopScheduled = false

    private var triggeredSyncDurationS = 10
    private var triggeredSyncSleepIntervalS = 10

    /**
     * The result of the last call to [decideShouldRun].
     */
    private var lastDeterminedShouldRun = false

    /**
     * Network changes of the default network. Signal strength alone changes its capabilities
     * often: the decision is re-evaluated only when a fact the run conditions read changes.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var lastFacts: List<Boolean>? = null

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            onFacts(
                listOf(
                    networkCapabilities.isWifiOrEthernet(),
                    networkCapabilities.isMobile(),
                    !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    networkCapabilities.isRoaming(),
                )
            )

        override fun onLost(network: Network) = onFacts(null)

        private fun onFacts(facts: List<Boolean>?) {
            if (facts != lastFacts) {
                lastFacts = facts
                mainHandler.post(updateRunnable)
            }
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Let the charging state settle. onReceive runs on the main thread: never sleep here.
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                    mainHandler.removeCallbacks(updateRunnable)
                    mainHandler.postDelayed(updateRunnable, 5000)
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> updateShouldRunDecision()
            }
        }
    }

    // Android's "AutoSync" quick toggle.
    private val syncStatusObserverHandle = ContentResolver.addStatusChangeListener(
        ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS
    ) { mainHandler.post(updateRunnable) }

    init {
        logV("Created new instance")
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        current = WeakReference(this)

        if (!Constants.isRunningOnEmulator()) {
            triggeredSyncSleepIntervalS = minutesPref(Constants.PREF_SLEEP_INTERVAL_MINUTES, 60) * 60
        }
        var lastSyncTimeSinceBootMillisecs = preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
        val elapsedRealtime = SystemClock.elapsedRealtime()
        // After a reboot, the last run time may refer to the previous boot. Setting it to
        // -triggeredSyncSleepIntervalS guarantees timeConditionMatch to be true.
        if (lastSyncTimeSinceBootMillisecs > elapsedRealtime) {
            preferences.edit { putLong(Constants.PREF_LAST_RUN_TIME, -triggeredSyncSleepIntervalS * 1000L) }
            lastSyncTimeSinceBootMillisecs = 0
        }

        // Initially determine if syncthing should run under current circumstances.
        updateShouldRunDecision()

        // Initially schedule the SyncTrigger job.
        val elapsedSecondsSinceLastSync = ((elapsedRealtime - lastSyncTimeSinceBootMillisecs) / 1000).toInt()
        Log.d(
            TAG,
            "JobPrepare: mTimeConditionMatch=$timeConditionMatch, elapsedRealtime=$elapsedRealtime, " +
                "lastSyncTimeSinceBootMillisecs=$lastSyncTimeSinceBootMillisecs, " +
                "elapsedSecondsSinceLastSync=$elapsedSecondsSinceLastSync"
        )
        // When the sleep interval has already elapsed, updateShouldRunDecision set
        // timeConditionMatch: the delay below is never negative.
        JobUtils.scheduleSyncTriggerServiceJob(
            context,
            if (timeConditionMatch) triggeredSyncDurationS else triggeredSyncSleepIntervalS - elapsedSecondsSinceLastSync,
            !timeConditionMatch,
        )
    }

    fun shutdown() {
        if (isShutDown) {
            return
        }
        isShutDown = true
        logV("Shutting down")
        JobUtils.cancelAllScheduledJobs(context)
        ContentResolver.removeStatusChangeListener(syncStatusObserverHandle)
        if (current?.get() === this) {
            current = null
        }
        context.unregisterReceiver(systemReceiver)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        mainHandler.removeCallbacks(updateRunnable)
    }

    private fun onSyncTrigger(extraBeginActiveTimeWindow: Boolean) {
        runAllowedStopScheduled = false
        logV("onSyncTrigger: extraBeginActiveTimeWindow=$extraBeginActiveTimeWindow")

        if (!preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)) {
            // The feature is currently disabled.
            // Reschedule the job to see if the user turned on this feature in the meantime.
            timeConditionMatch = false
            JobUtils.cancelAllScheduledJobs(context)
            JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncSleepIntervalS, true)
            return
        }

        // extraBeginActiveTimeWindow determines whether syncthing should start or stop
        if (extraBeginActiveTimeWindow) {
            // We should immediately start SyncthingNative for TRIGGERED_SYNC_DURATION_SECS.
            timeConditionMatch = true
            JobUtils.cancelAllScheduledJobs(context)
            JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncDurationS, false)
            runAllowedStopScheduled = true
        } else {
            // Toggle the "digital input" for this condition as the condition change is
            // triggered by a time schedule.
            timeConditionMatch = false
            // If Syncthing is running and the last run was more than triggeredSyncSleepIntervalS ago,
            // this stop job might actually start Syncthing (resp. leave it running) because
            // timeConditionMatch is switched to true if last run was more than triggeredSyncSleepIntervalS ago.
            // So in this case we put a new (fake) last run time slightly less than triggeredSyncSleepIntervalS ago.
            // If Syncthing really is stopped (which it should) then the wrong time gets
            // corrected immediately
            val lastRunTimeMillis = preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
            val now = SystemClock.elapsedRealtime()
            if (lastDeterminedShouldRun && now - lastRunTimeMillis > triggeredSyncSleepIntervalS * 1000L) {
                preferences.edit {
                    putLong(Constants.PREF_LAST_RUN_TIME, now - triggeredSyncSleepIntervalS * 1000L + 60 * 1000)
                }
            }
        }
        updateShouldRunDecision()

        // Reschedule the job.
        // If we are within a "SyncthingNative shouldn't run" time frame,
        // let the receiver fire and change to "SyncthingNative should run" after
        // triggeredSyncSleepIntervalS seconds elapsed.
        // If we are within a "SyncthingNative should run" time frame,
        // the change to "SyncthingNative shouldn't run" after
        // TRIGGERED_SYNC_DURATION_SECS seconds elapsed should actually
        // be scheduled inside updateShouldRunDecision(), but this might
        // not always be the case.
        // Thus we schedule an additional change to "SyncthingNative shouldn't run"
        // after TRIGGERED_SYNC_DURATION_SECS seconds elapsed, but without
        // cancelling other jobs. This should only serve as a backup job and
        // will not fire if the job inside updateShouldRunDecision() is
        // scheduled correctly.
        if (!runAllowedStopScheduled && !lastDeterminedShouldRun) {
            JobUtils.cancelAllScheduledJobs(context)
            JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncSleepIntervalS, true)
        } else {
            JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncDurationS, false)
        }
    }

    /**
     * Event handler that is fired after preconditions changed.
     * We then need to decide if syncthing should run.
     */
    fun updateShouldRunDecision() {
        if (!Constants.isRunningOnEmulator()) {
            triggeredSyncDurationS = minutesPref(Constants.PREF_SYNC_DURATION_MINUTES, 5) * 60
            triggeredSyncSleepIntervalS = minutesPref(Constants.PREF_SLEEP_INTERVAL_MINUTES, 60) * 60
        }

        val newShouldRun = decideShouldRun()
        if (newShouldRun) {
            // Sync preconditions changed or a re-evaluation was forced, while SyncthingService
            // stays or becomes ACTIVE: it evaluates the custom per-object run conditions and
            // pauses or resumes objects accordingly.
            onSyncPreconditionChangedListener?.onSyncPreconditionChanged(this)
        }

        if (newShouldRun != lastDeterminedShouldRun) {
            // SyncthingService transitions from INIT/DISABLED to STARTING and ACTIVE, or from
            // ACTIVE to DISABLED.
            if (onShouldRunChangedListener != null) {
                onShouldRunChangedListener.onShouldRunDecisionChanged(newShouldRun)
                lastDeterminedShouldRun = newShouldRun
            }
            if (newShouldRun &&
                !runAllowedStopScheduled &&
                preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false) &&
                preferences.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP) ==
                Constants.BTNSTATE_NO_FORCE_START_STOP
            ) {
                JobUtils.cancelAllScheduledJobs(context)
                JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncDurationS, false)
                runAllowedStopScheduled = true
            }
            preferences.edit { putLong(Constants.PREF_LAST_RUN_TIME, SystemClock.elapsedRealtime()) }
        }
    }

    private fun minutesPref(key: String, default: Int) = preferences.getString(key, null)?.toIntOrNull() ?: default

    /*
     * Each sync condition has its own evaluator function which determines if the condition is met.
     */

    /**
     * Constants.PREF_RUN_ON_WIFI
     */
    private fun checkConditionSyncOnWifi(prefNameSyncOnWifi: String): Condition {
        if (!preferences.getBoolean(prefNameSyncOnWifi, true)) {
            return Condition(false, "\n" + res.getString(R.string.reason_wifi_disallowed))
        }
        if (isWifiOrEthernetConnection()) {
            return Condition(true, "\n" + res.getString(R.string.reason_on_wifi))
        }
        // Not being on Wi-Fi does not end the decision: decideShouldRun still checks flight mode,
        // as some phones report no active network in flight mode even when connected to Wi-Fi.
        return Condition(false, "\n" + res.getString(R.string.reason_not_on_wifi))
    }

    private fun checkConditionSyncOnPowerSource(prefNameSyncOnPowerSource: String): Condition =
        when (preferences.getString(prefNameSyncOnPowerSource, Constants.PowerSource.CHARGER_BATTERY)) {
            Constants.PowerSource.CHARGER ->
                if (isCharging()) Condition(true) else Condition(false, res.getString(R.string.reason_not_charging))
            Constants.PowerSource.BATTERY ->
                if (isCharging()) Condition(false, res.getString(R.string.reason_not_on_battery_power)) else Condition(true)
            else -> Condition(true)
        }

    /**
     * Constants.PREF_WIFI_SSID_WHITELIST
     */
    private fun checkConditionSyncOnWhitelistedWifi(
        prefNameUseWifiWhitelist: String,
        prefNameSelectedWhitelistSsid: String,
    ): Condition {
        if (!preferences.getBoolean(prefNameUseWifiWhitelist, false)) {
            logV("handleWifiWhitelist: !prefWifiWhitelistEnabled")
            return Condition(true, "\n" + res.getString(R.string.reason_on_whitelisted_wifi))
        }
        val whitelistedWifiSsids = preferences.getStringSet(prefNameSelectedWhitelistSsid, null).orEmpty()
        return try {
            if (isWifiConnectionWhitelisted(whitelistedWifiSsids)) {
                logV("handleWifiWhitelist: isWifiConnectionWhitelisted")
                Condition(true, "\n" + res.getString(R.string.reason_on_whitelisted_wifi))
            } else {
                Condition(false, "\n" + res.getString(R.string.reason_not_on_whitelisted_wifi))
            }
        } catch (e: LocationUnavailableException) {
            Condition(false, "\n" + res.getString(R.string.reason_location_unavailable))
        }
    }

    /**
     * Constants.PREF_RUN_ON_METERED_WIFI
     */
    private fun checkConditionSyncOnMeteredWifi(prefNameSyncOnMeteredWifi: String): Condition = when {
        // Condition is always met as we allow both types of wifi - metered and non-metered.
        preferences.getBoolean(prefNameSyncOnMeteredWifi, false) ->
            Condition(true, "\n" + res.getString(R.string.reason_on_metered_nonmetered_wifi))
        !isMeteredNetworkConnection() -> Condition(true, "\n" + res.getString(R.string.reason_on_nonmetered_wifi))
        // We disallowed non-metered wifi and are connected to metered wifi.
        else -> Condition(false, "\n" + res.getString(R.string.reason_not_nonmetered_wifi))
    }

    /**
     * Constants.PREF_RUN_ON_MOBILE_DATA
     */
    private fun checkConditionSyncOnMobileData(prefNameSyncOnMobileData: String): Condition = when {
        !preferences.getBoolean(prefNameSyncOnMobileData, false) ->
            Condition(false, res.getString(R.string.reason_mobile_data_disallowed))
        isMobileDataConnection() -> Condition(true, res.getString(R.string.reason_on_mobile_data))
        else -> Condition(false, res.getString(R.string.reason_not_on_mobile_data))
    }

    /**
     * Constants.PREF_RUN_ON_ROAMING
     */
    private fun checkConditionSyncOnRoaming(prefNameSyncOnRoaming: String): Condition = when {
        // Condition is always met as we allow both types of mobile data networks - roaming and non-roaming.
        preferences.getBoolean(prefNameSyncOnRoaming, false) ->
            Condition(true, "\n" + res.getString(R.string.reason_on_roaming_nonroaming_mobile_data))
        !isRoamingNetworkConnection() -> Condition(true, "\n" + res.getString(R.string.reason_on_nonroaming_mobile_data))
        // We disallowed non-roaming mobile data and are connected to a mobile data network in roaming mode.
        else -> Condition(false, "\n" + res.getString(R.string.reason_not_nonroaming_mobile_data))
    }

    /**
     * Determines if Syncthing should currently run.
     * Updates runDecisionExplanation.
     */
    private fun decideShouldRun(): Boolean {
        runDecisionExplanation = ""

        when (preferences.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP)) {
            Constants.BTNSTATE_FORCE_START -> {
                logV("decideShouldRun: PREF_BTNSTATE_FORCE_START")
                runDecisionExplanation = res.getString(R.string.reason_force_start)
                return true
            }
            Constants.BTNSTATE_FORCE_STOP -> {
                logV("decideShouldRun: PREF_BTNSTATE_FORCE_STOP")
                runDecisionExplanation = res.getString(R.string.reason_force_stop)
                return false
            }
        }

        // PREF_RUN_ON_TIME_SCHEDULE
        // set timeConditionMatch to true if the last run was more than the sleep interval ago
        val sinceLastRunMillis = SystemClock.elapsedRealtime() - preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
        if (sinceLastRunMillis > minutesPref(Constants.PREF_SLEEP_INTERVAL_MINUTES, 60) * 60 * 1000L) {
            timeConditionMatch = true
        }
        if (preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false) && !timeConditionMatch) {
            // Currently, we aren't within a "SyncthingNative should run" time frame.
            logV("decideShouldRun: PREF_RUN_ON_TIME_SCHEDULE && !mTimeConditionMatch")
            val minutes = (sinceLastRunMillis / (60 * 1000)).toInt()
            val minutesText = if (minutes == 0) {
                res.getString(R.string.reason_not_within_time_frame_0_min)
            } else {
                res.getQuantityString(R.plurals.reason_not_within_time_frame_minutes, minutes, minutes)
            }
            runDecisionExplanation = res.getString(R.string.reason_not_within_time_frame_2, minutesText)
            return false
        }

        // PREF_POWER_SOURCE
        val powerSource = checkConditionSyncOnPowerSource(Constants.PREF_POWER_SOURCE)
        if (!powerSource.met) {
            logV("checkConditionSyncOnPowerSource: " + powerSource.explanation)
            runDecisionExplanation = powerSource.explanation
            return false
        }

        // Power saving
        if (preferences.getBoolean(Constants.PREF_RESPECT_BATTERY_SAVING, true) && isPowerSaving()) {
            logV("decideShouldRun: prefRespectPowerSaving && isPowerSaving")
            runDecisionExplanation = res.getString(R.string.reason_not_while_power_saving)
            return false
        }

        // Android global AutoSync setting.
        if (preferences.getBoolean(Constants.PREF_RESPECT_MASTER_SYNC, false) && !ContentResolver.getMasterSyncAutomatically()) {
            logV("decideShouldRun: prefRespectMasterSync && !getMasterSyncAutomatically")
            runDecisionExplanation = res.getString(R.string.reason_not_while_auto_sync_data_disabled)
            return false
        }

        // Run on mobile data?
        if (explain(checkConditionSyncOnMobileData(Constants.PREF_RUN_ON_MOBILE_DATA)) &&
            explain(checkConditionSyncOnRoaming(Constants.PREF_RUN_ON_ROAMING))
        ) {
            // Mobile data is connected and its connection type is allowed.
            logV("decideShouldRun: checkConditionSyncOnMobileData && checkConditionSyncOnRoaming")
            return true
        }

        // Run on WiFi?
        if (explain(checkConditionSyncOnWifi(Constants.PREF_RUN_ON_WIFI)) &&
            explain(checkConditionSyncOnMeteredWifi(Constants.PREF_RUN_ON_METERED_WIFI)) &&
            explain(
                checkConditionSyncOnWhitelistedWifi(Constants.PREF_USE_WIFI_SSID_WHITELIST, Constants.PREF_WIFI_SSID_WHITELIST)
            )
        ) {
            // Wifi is connected, of an allowed type and whitelisted.
            logV("decideShouldRun: checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi && checkConditionSyncOnWhitelistedWifi")
            return true
        }

        // Run in flight mode.
        if (preferences.getBoolean(Constants.PREF_RUN_IN_FLIGHT_MODE, false) && isFlightMode()) {
            logV("decideShouldRun: prefRunInFlightMode && isFlightMode")
            runDecisionExplanation += "\n" + res.getString(R.string.reason_on_flight_mode)
            return true
        }

        // If none of the above run conditions matched, don't run.
        logV("decideShouldRun: return false")
        return false
    }

    /**
     * Appends the condition's explanation to runDecisionExplanation and returns if it is met.
     */
    private fun explain(condition: Condition): Boolean {
        runDecisionExplanation += condition.explanation
        return condition.met
    }

    /**
     * Check if an object's individual sync conditions are met.
     * Precondition: Object must own pref "...CustomSyncConditionsEnabled == true".
     */
    fun checkObjectSyncConditions(objectPrefixAndId: String): Boolean {
        if (!checkConditionSyncOnPowerSource(Constants.DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectPrefixAndId)).met) {
            logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnPowerSource")
            return false
        }
        if (checkConditionSyncOnMobileData(Constants.DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectPrefixAndId)).met) {
            logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnMobileData")
            return true
        }
        val onAllowedWifi = checkConditionSyncOnWifi(Constants.DYN_PREF_OBJECT_SYNC_ON_WIFI(objectPrefixAndId)).met &&
            checkConditionSyncOnMeteredWifi(Constants.DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(objectPrefixAndId)).met &&
            checkConditionSyncOnWhitelistedWifi(
                Constants.DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(objectPrefixAndId),
                Constants.DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(objectPrefixAndId),
            ).met
        if (onAllowedWifi) {
            logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi && checkConditionSyncOnWhitelistedWifi")
        }
        return onAllowedWifi
    }

    /*
     * Functions for run condition information retrieval.
     */

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            logV("isCharging: Checking battery status intent returned null")
            return false
        }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        logV("isCharging: Battery status intent extras: ${intent.extras}")
        val chargers = mutableListOf(
            BatteryManager.BATTERY_PLUGGED_AC,
            BatteryManager.BATTERY_PLUGGED_USB,
            BatteryManager.BATTERY_PLUGGED_WIRELESS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            chargers += BatteryManager.BATTERY_PLUGGED_DOCK
        }
        return plugged in chargers
    }

    private fun isPowerSaving(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    private fun activeCapabilities(): NetworkCapabilities? =
        connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

    private fun isFlightMode() = connectivityManager.activeNetwork == null

    private fun isMeteredNetworkConnection(): Boolean {
        val capabilities = activeCapabilities() ?: return false
        // Ethernet counts as Wi-Fi, unmetered, to allow syncing on Android TV or in VirtualBox.
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && connectivityManager.isActiveNetworkMetered
    }

    private fun isMobileDataConnection() = activeCapabilities()?.isMobile() == true

    private fun isRoamingNetworkConnection() = activeCapabilities()?.isRoaming() == true

    private fun isWifiOrEthernetConnection() = activeCapabilities()?.isWifiOrEthernet() == true

    private fun NetworkCapabilities.isWifiOrEthernet() =
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    private fun NetworkCapabilities.isMobile() =
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)

    @Suppress("DEPRECATION")
    private fun NetworkCapabilities.isRoaming() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        } else {
            connectivityManager.activeNetworkInfo?.isRoaming == true
        }

    @Suppress("DEPRECATION")
    private fun isWifiConnectionWhitelisted(whitelistedSsids: Set<String>): Boolean {
        val wifiInfo = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        if (wifiInfo == null) {
            // May be null, if wifi has been turned off in the meantime.
            Log.d(TAG, "isWifiConnectionWhitelisted: SSID unknown due to wifiInfo == null")
            return false
        }
        val wifiSsid = wifiInfo.ssid
        if (wifiSsid == null || wifiSsid == "<unknown ssid>") {
            // The SSID needs the location service.
            throw LocationUnavailableException()
        }
        return wifiSsid in whitelistedSsids
    }

    private fun logV(message: String) {
        if (verboseLog) {
            Log.v(TAG, message)
        }
    }

    companion object {
        private const val TAG = "RunConditionMonitor"

        /**
         * The key of the job extra telling SyncTriggerJobService to open a time window.
         */
        const val EXTRA_BEGIN_ACTIVE_TIME_WINDOW = ".service.RunConditionMonitor.BEGIN_ACTIVE_TIME_WINDOW"

        /**
         * The monitor of the running service, which the screens, tiles, receivers and jobs of the
         * app reach through the functions below. Weak: the monitor holds the service.
         */
        @Volatile
        private var current: WeakReference<RunConditionMonitor>? = null

        /**
         * Re-evaluates the run conditions, e.g. after the run mode changed. Does nothing while the
         * service does not run.
         */
        @JvmStatic
        fun requestUpdate() = post {
            it.logV("requestUpdate")
            it.updateShouldRunDecision()
        }

        /**
         * Opens a time window of the schedule now if [beginActiveTimeWindow], else closes it. Does
         * nothing while the service does not run.
         */
        @JvmStatic
        fun fireSyncTrigger(beginActiveTimeWindow: Boolean) = post { it.onSyncTrigger(beginActiveTimeWindow) }

        /**
         * Runs [action] on the main thread, where the monitor lives, unless it was shut down meanwhile.
         */
        private fun post(action: (RunConditionMonitor) -> Unit) {
            val monitor = current?.get() ?: return
            monitor.mainHandler.post {
                if (current?.get() === monitor) {
                    action(monitor)
                }
            }
        }
    }
}
