package com.nutomic.syncthingandroid.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.http.PollWebGuiAvailableTask
import com.nutomic.syncthingandroid.util.CertificateValidator
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.PermissionUtil
import com.nutomic.syncthingandroid.util.Util
import com.nutomic.syncthingandroid.util.defaultPreferences
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

/**
 * Holds the native syncthing instance and provides an API to access it.
 */
class SyncthingService : Service() {

    fun interface OnServiceStateChangeListener {
        fun onServiceStateChange(currentState: State)
    }

    /**
     * Outcome of [replaceHttpsCertificate] / [resetHttpsCertificate].
     */
    enum class HttpsCertReplaceResult {
        /** The new certificate was applied and Syncthing came back online serving it. */
        SUCCESS,

        /** The files were written but Syncthing is not currently meant to run; applies on next start. */
        SUCCESS_PENDING_START,

        /** Writing the files failed; the previous certificate was restored. Carries a detail message. */
        FAILED,

        /**
         * Syncthing came online but was not serving the new certificate: it could not load the
         * cert/key pair and generated a replacement self-signed certificate instead of failing.
         * The previous certificate has been restored.
         */
        FAILED_CERT_REJECTED,

        /** Syncthing did not come back online at all; the previous certificate was restored. */
        FAILED_NOT_ONLINE,
    }

    fun interface OnHttpsCertReplaceResultListener {
        fun onResult(result: HttpsCertReplaceResult, errorDetail: String?)
    }

    /**
     * Indicates the current state of SyncthingService and of Syncthing itself.
     */
    enum class State {
        /**
         * Service is initializing, Syncthing was not started yet.
         */
        INIT,

        /**
         * Syncthing binary is starting.
         */
        STARTING,

        /**
         * Syncthing binary is running,
         * Rest API is available,
         * RestApi class read the config and is fully initialized.
         */
        ACTIVE,

        /**
         * Syncthing binary is shutting down.
         */
        DISABLED,

        /**
         * There is some problem that prevents Syncthing from running.
         */
        ERROR,
    }

    /**
     * Initialized with State.DISABLED: RunConditionMonitor sends an update if we should run the
     * binary after it got instantiated in onStartCommand. Guarded by stateLock.
     */
    var currentState = State.DISABLED
        private set
    private val stateLock = Any()

    /**
     * The REST API, while the binary runs.
     */
    var api: RestApi? = null
        private set

    val notificationHandler: NotificationHandler by lazy { (application as SyncthingApp).notificationHandler }

    private val preferences: SharedPreferences by lazy { defaultPreferences }
    private val configRouter by lazy { ConfigRouter(this) }
    private var verboseLog = false

    /**
     * Runs work on the main thread, where the state machine lives. Not cancelled in onDestroy: a
     * shutdown deferred while the binary starts must still run.
     */
    private val scope = MainScope()
    private val binder = SyncthingServiceBinder(this)
    private val onServiceStateChangeListeners = HashSet<OnServiceStateChangeListener>()

    private var syncthingRunnable: SyncthingRunnable? = null
    private var syncthingRunnableThread: Thread? = null
    private var pollWebGuiAvailableTask: PollWebGuiAvailableTask? = null
    private var eventProcessor: EventProcessor? = null
    private var runConditionMonitor: RunConditionMonitor? = null

    /**
     * The result of the last should run decision received from RunConditionMonitor.
     */
    private var lastDeterminedShouldRun = false

    /**
     * True if the user granted the storage permission.
     */
    private var storagePermissionGranted = false

    /**
     * Set while import or export works on the files of the stopped binary,
     * so that a run condition change cannot start it meanwhile.
     */
    @Volatile
    private var configTransferInProgress = false

    override fun onCreate() {
        super.onCreate()
        verboseLog = AppPrefs.getPrefVerboseLog(preferences)
        logV("onCreate")
        // If runtime permissions are revoked, android kills and restarts the service.
        // We need to recheck if we still have the storage permission.
        storagePermissionGranted = PermissionUtil.haveStoragePermission(this)
        notificationHandler.appShutdownInProgress = false
    }

    /**
     * Handles intent actions, e.g. [ACTION_RESTART]
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // A service started by startForegroundService() must call startForeground() before it
        // stops, or Android kills the app. updatePersistentNotification() does it on Android 8+,
        // so it comes before any early return below.
        notificationHandler.updatePersistentNotification(this)

        // The permission can be revoked or granted while the service lives.
        storagePermissionGranted = PermissionUtil.haveStoragePermission(this)
        if (!storagePermissionGranted) {
            Log.e(TAG, "User revoked storage permission. Stopping service.")
            notificationHandler.showStoragePermissionRevokedNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        if (runConditionMonitor == null) {
            // Instantiate the run condition monitor on first onStartCommand and enable callback
            // on run condition change affecting the final decision to run/terminate syncthing.
            // After initial run conditions are collected the first decision is sent to
            // onShouldRunDecisionChanged.
            runConditionMonitor = RunConditionMonitor(
                this,
                { onShouldRunDecisionChanged(it) },
                { applyCustomRunConditions(it) },
            )
        }
        notificationHandler.updatePersistentNotification(this)

        if (intent == null) {
            return START_STICKY
        }
        val action = intent.action
        if (action == ACTION_RESTART && currentState == State.ACTIVE && isFromCurrentBinary(intent)) {
            shutdown(State.INIT)
            launchStartupTask(SyncthingRunnable.Command.main)
        } else if (action == ACTION_STOP) {
            // SyncthingRunnable, the only sender of ACTION_STOP, always sets this extra.
            if (intent.getBooleanExtra(EXTRA_STOP_AFTER_CRASHED_NATIVE, false) && isFromCurrentBinary(intent)) {
                // The syncthing native binary crashed. Changing currentState prevents the "defer
                // until syncthing is started" routine we normally use for clean shutdown to take
                // place: the crashed instance is shut down forcefully at once.
                currentState = State.ERROR
                shutdown(State.DISABLED)
            }
        } else if (action == ACTION_RESET_DATABASE) {
            // Stop syncthing native if it's running, reset the database (syncthing native exits
            // after performing the reset), then relaunch it if it was running.
            Log.i(TAG, "Invoking reset of database")
            runWhenNotStarting {
                if (currentState != State.DISABLED) {
                    // Shutdown synchronously.
                    shutdown(State.DISABLED)
                }
                SyncthingRunnable(this, SyncthingRunnable.Command.resetdatabase).run()
                Toast.makeText(this, R.string.st_reset_database_done, Toast.LENGTH_LONG).show()
                if (lastDeterminedShouldRun) {
                    launchStartupTask(SyncthingRunnable.Command.main)
                }
            }
        } else if (action == ACTION_RESET_DELTAS) {
            // Stop syncthing native if it's running, then reset the delta indexes: syncthing native
            // does NOT exit after the reset. If it was not running before, it is shut down again
            // once it left State.STARTING, when the reset was completed and the web UI came up.
            Log.i(TAG, "Invoking reset of delta indexes")
            runWhenNotStarting {
                if (currentState != State.DISABLED) {
                    // Shutdown synchronously.
                    shutdown(State.DISABLED)
                }
                launchStartupTask(SyncthingRunnable.Command.resetdeltas)
                stopUnlessShouldRun()
            }
        } else if (action == ACTION_REFRESH_NETWORK_INFO) {
            runConditionMonitor?.updateShouldRunDecision()
        } else if (action == ACTION_IGNORE_DEVICE) {
            configRouter.ignoreDevice(
                api,
                intent.getStringExtra(EXTRA_DEVICE_ID),
                intent.getStringExtra(EXTRA_DEVICE_NAME),
                intent.getStringExtra(EXTRA_DEVICE_ADDRESS),
            )
            notificationHandler.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        } else if (action == ACTION_IGNORE_FOLDER) {
            configRouter.ignoreFolder(
                api,
                intent.getStringExtra(EXTRA_DEVICE_ID),
                intent.getStringExtra(EXTRA_FOLDER_ID),
                intent.getStringExtra(EXTRA_FOLDER_LABEL),
            )
            notificationHandler.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        } else if (action == ACTION_OVERRIDE_CHANGES && currentState == State.ACTIVE) {
            api?.overrideChanges(intent.getStringExtra(EXTRA_FOLDER_ID))
        } else if (action == ACTION_REVERT_LOCAL_CHANGES && currentState == State.ACTIVE) {
            api?.revertLocalChanges(intent.getStringExtra(EXTRA_FOLDER_ID))
        } else {
            afterFreshServiceInstanceStart()
        }
        return START_STICKY
    }

    /**
     * False for a stop or restart sent by the binary of a previous instance of the service: the
     * new instance kills it by name at its start, and must not take that exit for its own. True
     * for the senders that do not name a binary.
     */
    private fun isFromCurrentBinary(intent: Intent): Boolean {
        if (!intent.hasExtra(EXTRA_RUN_ID)) {
            return true
        }
        val fromCurrent = intent.getIntExtra(EXTRA_RUN_ID, 0) == syncthingRunnable?.runId
        if (!fromCurrent) {
            Log.w(TAG, "Ignored ${intent.action} sent by a binary this instance did not start")
        }
        return fromCurrent
    }

    /**
     * Catches a fresh service startup right after the run condition evaluation took place, while
     * SyncthingNative may be starting in the background or not be running.
     */
    private fun afterFreshServiceInstanceStart() {
        logV("afterFreshServiceInstanceStart: Service started from scratch, SyncthingNative is going to STATE_$currentState meanwhilst ...")
        if (currentState == State.DISABLED) {
            // Read and parse the config from disk.
            loadConfigOrStop("afterFreshServiceInstanceStart")
        }
    }

    /**
     * Reads config.xml. Null after a failure, which is notified and stops the service.
     */
    private fun loadConfigOrStop(caller: String): ConfigXml? =
        try {
            ConfigXml(this).apply { loadConfig() }
        } catch (e: ConfigXml.OpenConfigException) {
            notificationHandler.showCrashedNotification(R.string.config_read_failed, "$caller:OpenConfigException")
            synchronized(stateLock) {
                onServiceStateChange(State.ERROR)
            }
            stopSelf()
            null
        }

    /**
     * After run conditions monitored by RunConditionMonitor changed and it had an influence on the
     * decision to run/terminate syncthing, this function is called to run/terminate the syncthing
     * binary. onServiceStateChange is called while applying the decision change.
     */
    private fun onShouldRunDecisionChanged(newShouldRunDecision: Boolean) {
        if (newShouldRunDecision == lastDeterminedShouldRun) {
            return
        }
        Log.i(TAG, "shouldRun decision changed to $newShouldRunDecision according to configured run conditions.")
        lastDeterminedShouldRun = newShouldRunDecision
        if (newShouldRunDecision) {
            // Start syncthing, unless it is already starting, running or failing.
            if (currentState == State.DISABLED || currentState == State.INIT) {
                launchStartupTask(SyncthingRunnable.Command.main)
            }
        } else {
            stopUnlessShouldRun()
        }
    }

    /**
     * Stops syncthing once it left State.STARTING, unless the run conditions want it again by
     * then: in State.STARTING, a start request does nothing, and the stop must not undo it.
     */
    private fun stopUnlessShouldRun() {
        runWhenNotStarting {
            if (!lastDeterminedShouldRun && currentState != State.DISABLED) {
                shutdown(State.DISABLED)
            }
        }
    }

    /**
     * After sync preconditions changed, pauses or resumes the devices and folders with their own
     * run conditions: through the REST API while syncthing runs, in config.xml otherwise.
     */
    private fun applyCustomRunConditions(runConditionMonitor: RunConditionMonitor) {
        synchronized(stateLock) {
            val restApi = api
            if (restApi != null) {
                // Forward event because syncthing is running or starting. While it starts, it
                // has read config.xml already: wait for its config to be loaded through the API.
                restApi.runWhenConfigLoaded { restApi.applyCustomRunConditions(runConditionMonitor) }
                return
            }
        }

        val configXml = loadConfigOrStop("applyCustomRunConditions") ?: return
        var configChanged = false
        for (folder in configXml.getFolders()) {
            val prefix = Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
            if (!preferences.getBoolean(Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(prefix), false)) {
                continue
            }
            val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(prefix)
            logV("applyCustomRunConditions: f(${folder.label})=${if (syncConditionsMet) "1" else "0"}")
            if (folder.paused == syncConditionsMet) {
                configXml.setFolderPause(folder.id, !syncConditionsMet)
                Log.d(TAG, "applyCustomRunConditions: f(${folder.label})=${if (syncConditionsMet) ">1" else ">0"}")
                configChanged = true
            }
        }
        for (device in configXml.getDevices(false)) {
            val prefix = Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
            if (!preferences.getBoolean(Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(prefix), false)) {
                continue
            }
            val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(prefix)
            logV("applyCustomRunConditions: d(${device.name})=${if (syncConditionsMet) "1" else "0"}")
            if (device.paused == syncConditionsMet) {
                configXml.setDevicePause(device.deviceID, !syncConditionsMet)
                Log.d(TAG, "applyCustomRunConditions: d(${device.name})=${if (syncConditionsMet) ">1" else ">0"}")
                configChanged = true
            }
        }
        if (configChanged) {
            logV("applyCustomRunConditions: Saving changed config ...")
            configXml.saveChanges()
        } else {
            logV("applyCustomRunConditions: No action was necessary.")
        }
    }

    /**
     * Prepares to launch the syncthing binary.
     */
    private fun launchStartupTask(srCommand: SyncthingRunnable.Command) {
        synchronized(stateLock) {
            if (currentState != State.DISABLED && currentState != State.INIT) {
                Log.e(TAG, "launchStartupTask: Wrong state $currentState detected. Cancelling.")
                return
            }
        }
        if (configTransferInProgress) {
            Log.i(TAG, "launchStartupTask: Import or export in progress. The binary starts when it ends.")
            return
        }
        val config = loadConfigOrStop("launchStartupTask") ?: return

        // Check if an old syncthing instance is still running. This happens after an in-place app
        // upgrade. If so, end it, before checking the port: it would hold the webgui port.
        Util.killProcess(Constants.FILENAME_SYNCTHING_BINARY)

        // Check if the SyncthingNative's configured webgui port is allocated by another app or process.
        val webGuiTcpPort = config.webGuiBindPort
        if (Util.isTcpPortListening(webGuiTcpPort)) {
            // We shouldn't start SyncthingNative as we would wait forever for life signs on the configured port. (ANR)
            Log.e(TAG, "launchStartupTask: WebUI tcp port $webGuiTcpPort unavailable. Second instance?")
            notificationHandler.showCrashedNotification(R.string.webui_tcp_port_unavailable, webGuiTcpPort.toString())
            return
        }

        onServiceStateChange(State.STARTING)

        if (api == null) {
            api = RestApi(this, config.webGuiUrl, config.apiKey, { onApiAvailable() }, { onServiceStateChange(currentState) })
            Log.i(TAG, "Web GUI will be available at " + config.webGuiUrl)
        }

        // Check the lifecycle of syncthingRunnable and create a singleton.
        if (syncthingRunnable != null || syncthingRunnableThread != null) {
            Log.e(TAG, "onStartupTaskCompleteListener: Syncthing binary lifecycle violated")
            return
        }
        val runnable = SyncthingRunnable(this, srCommand)
        syncthingRunnable = runnable

        // Start the syncthing binary in a separate thread.
        val thread = Thread(runnable)
        thread.setUncaughtExceptionHandler { failedThread, ex ->
            Log.e(TAG, "mSyncthingRunnableThread: Uncaught exception", ex)
            notificationHandler.showCrashedNotification(R.string.executable_not_found, Constants.FILENAME_SYNCTHING_BINARY)
            // The binary is not running: leave State.STARTING, in which shutdown() would defer forever.
            scope.launch {
                if (syncthingRunnableThread == failedThread) {
                    currentState = State.ERROR
                    shutdown(State.DISABLED)
                }
            }
        }
        syncthingRunnableThread = thread
        thread.start()

        // Wait for the web-gui of the native syncthing binary to come online. Another thread could
        // request to stop the binary meanwhile: see shutdown, which cancels this wait.
        if (pollWebGuiAvailableTask == null) {
            pollWebGuiAvailableTask = PollWebGuiAvailableTask(this, config.webGuiUrl, config.apiKey) {
                Log.i(TAG, "Web GUI has come online at " + config.webGuiUrl)
                api?.readConfigFromRestApi()
            }
        }
    }

    /**
     * Called when RestApi has read the config, the version and the system status. UI stressing
     * results in api getting null on simultaneous shutdown, so we check it for safety.
     */
    private fun onApiAvailable() {
        val restApi = api
        if (restApi == null) {
            Log.e(TAG, "onApiAvailable: Did we stop the binary during startup? mRestApi == null")
            return
        }
        synchronized(stateLock) {
            if (currentState != State.STARTING) {
                Log.e(TAG, "onApiAvailable: Wrong state $currentState detected. Cancelling callback.")
                return
            }
            onServiceStateChange(State.ACTIVE)
        }
        if (eventProcessor == null) {
            eventProcessor = EventProcessor(this, restApi).also { it.start() }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * Stops the native binary.
     * Shuts down RunConditionMonitor instance.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // Shut down the run condition monitor so we won't get interrupted by run condition events
        // that occur during shutdown.
        runConditionMonitor?.shutdown()
        notificationHandler.appShutdownInProgress = true
        if (!storagePermissionGranted) {
            // If the storage permission got revoked, we did not start the binary and
            // are in State.INIT requiring an immediate shutdown of this service class.
            Log.i(TAG, "Shutting down syncthing binary due to missing storage permission.")
        }
        shutdown(State.DISABLED)
        super.onDestroy()
    }

    /**
     * Stop SyncthingNative and all helpers like event processor and api handler.
     * Sets [currentState] to newState.
     * Performs a synchronous shutdown of the native binary.
     */
    private fun shutdown(newState: State) {
        if (currentState == State.STARTING) {
            Log.w(TAG, "Deferring shutdown until State.STARTING was left")
            scope.launch {
                delay(1000)
                shutdown(newState)
            }
            return
        }

        // The exit that follows is expected, the runnable must not report it back.
        syncthingRunnable?.setStopRequested()

        synchronized(stateLock) {
            onServiceStateChange(newState)
        }

        pollWebGuiAvailableTask?.cancelRequestsAndCallback()
        pollWebGuiAvailableTask = null

        eventProcessor?.stop()
        eventProcessor = null

        notificationHandler.cancelRestartNotification()

        if (syncthingRunnable != null) {
            api?.shutdown()
        }
        // Changes made while Syncthing started, which it will not get: config.xml takes them,
        // once the binary is down.
        val writesNeverSent = api?.takeWritesNeverSent().orEmpty()
        api = null

        if (syncthingRunnable == null) {
            writesNeverSent.forEach { it.run() }
            return
        }
        Util.killProcess(Constants.FILENAME_SYNCTHING_BINARY)
        syncthingRunnableThread?.let { thread ->
            logV("Waiting for mSyncthingRunnableThread to finish after killProcess(Syncthing) ...")
            try {
                // Bounded: the process is gone after killProcess, its reader thread ends at once.
                thread.join(1000)
                if (thread.isAlive) {
                    Log.w(TAG, "mSyncthingRunnableThread still running after killProcess(Syncthing)")
                }
            } catch (e: InterruptedException) {
                Log.w(TAG, "mSyncthingRunnableThread InterruptedException")
            }
            Log.d(TAG, "Finished mSyncthingRunnableThread.")
            syncthingRunnableThread = null
        }
        syncthingRunnable = null
        writesNeverSent.forEach { it.run() }
    }

    /**
     * Runs task now, or once State.STARTING was left: [shutdown] only defers in that
     * state, so a task that needs the binary stopped must not start before. Main thread only.
     */
    private fun runWhenNotStarting(task: () -> Unit) {
        if (currentState == State.STARTING) {
            scope.launch {
                delay(1000)
                runWhenNotStarting(task)
            }
            return
        }
        task()
    }

    /**
     * Called on a worker thread by import and export, never on the main thread (it would
     * deadlock). Stops the binary on the main thread, where the state machine lives, and blocks
     * until it is down.
     * Returns false if interrupted, or if another import or export is in progress: both would
     * write the same files. The binary is then left as it was.
     */
    private fun stopBinaryForConfigTransfer(): Boolean {
        val stopped = CountDownLatch(1)
        var started = false
        scope.launch {
            runWhenNotStarting {
                if (!configTransferInProgress) {
                    if (currentState != State.DISABLED) {
                        // Shutdown synchronously.
                        shutdown(State.DISABLED)
                    }
                    configTransferInProgress = true
                    started = true
                } else {
                    Log.w(TAG, "Another import or export is in progress")
                }
                stopped.countDown()
            }
        }
        return try {
            stopped.await()
            started
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun endConfigTransfer() {
        scope.launch {
            configTransferInProgress = false
            if (lastDeterminedShouldRun) {
                launchStartupTask(SyncthingRunnable.Command.main)
            }
        }
    }

    /**
     * Force re-evaluating run conditions immediately e.g. after
     * preferences were modified by SettingsActivity.onStop.
     */
    fun evaluateRunConditions() {
        val monitor = runConditionMonitor ?: return
        Log.d(TAG, "Forced re-evaluating run conditions ...")
        monitor.updateShouldRunDecision()
    }

    /**
     * Register a listener for the syncthing API state changing.
     * The listener is called immediately with the current state, and again whenever the state
     * changes. The call is always from the GUI thread.
     *
     * @see unregisterOnServiceStateChangeListener
     */
    fun registerOnServiceStateChangeListener(listener: OnServiceStateChangeListener) {
        // Initially send the current state to the new subscriber to make sure it doesn't stay
        // in undefined state forever until the state next change occurs.
        listener.onServiceStateChange(currentState)
        onServiceStateChangeListeners.add(listener)
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @see registerOnServiceStateChangeListener
     */
    fun unregisterOnServiceStateChangeListener(listener: OnServiceStateChangeListener) {
        onServiceStateChangeListeners.remove(listener)
    }

    /**
     * Called to notify listeners of an API change.
     */
    private fun onServiceStateChange(newState: State) {
        if (newState == currentState) {
            Log.d(TAG, "onServiceStateChange: Called with unchanged state $newState")
            return
        }
        Log.i(TAG, "onServiceStateChange: from $currentState to $newState")
        currentState = newState
        scope.launch {
            notificationHandler.updatePersistentNotification(this@SyncthingService)
            // A copy: a listener may register or unregister one meanwhile.
            for (listener in onServiceStateChangeListeners.toList()) {
                listener.onServiceStateChange(currentState)
            }
        }
    }

    val runDecisionExplanation: String
        get() = runConditionMonitor?.runDecisionExplanation
            ?: getString(R.string.reason_run_condition_monitor_not_instantiated)

    /**
     * The backup zip file, by default /storage/emulated/0/backups/syncthing/config.zip.
     */
    @Suppress("DEPRECATION")
    private fun getBackupZipFile(): File {
        // An empty path in the preferences falls back to the default too.
        val relPathToZip = preferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, null).orEmpty()
            .ifEmpty { "backups/syncthing/config.zip" }
        return File(Environment.getExternalStorageDirectory(), relPathToZip)
    }

    /**
     * Exports the local config, keys, database and preferences to the backup zip file.
     *
     * Test with Android Virtual Device using emulator.
     * cls & adb shell su 0 "ls -a -l -R /data/data/${applicationId}/files; echo === SDCARD ===; ls -a -l -R /storage/emulated/0/backups/syncthing"
     */
    fun exportConfig(): Boolean {
        Log.d(TAG, "exportConfig BEGIN")
        if (!stopBinaryForConfigTransfer()) {
            return false
        }
        var success = true

        // Create export dir if non-existent.
        val targetZip = getBackupZipFile()
        val tmpZip = File(targetZip.path + ".tmp")
        targetZip.parentFile?.mkdirs()

        // Export SharedPreferences.
        val sharedPreferencesFile = Constants.getSharedPrefsFile(this)
        try {
            ObjectOutputStream(FileOutputStream(sharedPreferencesFile)).use { it.writeObject(preferences.all) }
        } catch (e: IOException) {
            Log.e(TAG, "exportConfig: Failed to export SharedPreferences", e)
            success = false
        }

        // Files to back up.
        val includePaths = listOf(
            Constants.getConfigFile(this),
            Constants.getPrivateKeyFile(this),
            Constants.getPublicKeyFile(this),
            Constants.getHttpsCertFile(this),
            Constants.getHttpsKeyFile(this),
            Constants.getSharedPrefsFile(this),
            Constants.getIndexDbFolder(this),
        )

        // If user set one, apply a password and encrypt the zip file.
        val zipEncryptionPassword = preferences.getString(Constants.PREF_BACKUP_PASSWORD, null).orEmpty()

        try {
            // Build the archive next to the target, then rename it: on failure (storage full),
            // the previous backup stays in place instead of a partial one.
            tmpZip.delete()
            val parameters = ZipParameters()
            parameters.setCompressionMethod(CompressionMethod.DEFLATE)
            parameters.setCompressionLevel(CompressionLevel.NORMAL)
            val zipFile = if (zipEncryptionPassword.isEmpty()) {
                parameters.setEncryptFiles(false)
                ZipFile(tmpZip)
            } else {
                parameters.setEncryptFiles(true)
                parameters.setEncryptionMethod(EncryptionMethod.AES)
                parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
                ZipFile(tmpZip, zipEncryptionPassword.toCharArray())
            }
            for (includePath in includePaths) {
                when {
                    includePath.isFile -> zipFile.addFile(includePath, parameters)
                    includePath.isDirectory -> zipFile.addFolder(includePath, parameters)
                }
            }
            if (!tmpZip.renameTo(targetZip)) {
                throw IOException("Failed to rename ${tmpZip.name} to ${targetZip.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "exportConfig: Failed to export config, " + e.message)
            tmpZip.delete()
            success = false
        } finally {
            sharedPreferencesFile.delete()
        }
        Log.d(TAG, "exportConfig END")

        // Start syncthing after export if run conditions apply.
        endConfigTransfer()
        return success
    }

    /**
     * Imports config and keys from the backup zip file.
     *
     * Test with Android Virtual Device using emulator.
     * cls & adb shell su 0 "ls -a -l -R /data/data/${applicationId}/files; echo === SDCARD ===; ls -a -l -R /storage/emulated/0/backups/syncthing"
     *
     * @return True if the import was successful, false otherwise (eg if files aren't found).
     */
    fun importConfig(): Boolean {
        Log.d(TAG, "importConfig PRECHECK")
        val zipFilePath = getBackupZipFile()
        if (!zipFilePath.exists()) {
            Log.e(TAG, "importConfig: ZIP file is missing. Please check if it is present at '${zipFilePath.absolutePath}' as specified in the settings screen.")
            return false
        }
        val zipFile = openBackupZip(zipFilePath) ?: return false

        // Shutdown SyncthingNative.
        Log.d(TAG, "importConfig BEGIN")
        if (!stopBinaryForConfigTransfer()) {
            return false
        }

        // Extract into a staging directory first. The current config, keys and database are only
        // replaced once the whole archive was extracted and holds the required files: a failure
        // on the way (storage full, corrupt archive) leaves the current setup untouched.
        val stagingDir = File(cacheDir, "import-staging")
        try {
            FileUtils.deleteDirectoryRecursively(stagingDir)
            zipFile.extractAll(stagingDir.absolutePath)
        } catch (e: IOException) {
            Log.e(TAG, "importConfig: Failed to extract zip, " + e.message)
            return abortImport(stagingDir)
        }
        REQUIRED_FILES.firstOrNull { !File(stagingDir, it).exists() }?.let {
            Log.e(TAG, "importConfig: Missing file after extraction [$it]")
            return abortImport(stagingDir)
        }
        for (optionalFile in listOf(Constants.getHttpsCertFile(this), Constants.getHttpsKeyFile(this), Constants.getSharedPrefsFile(this))) {
            if (!File(stagingDir, optionalFile.name).exists()) {
                // Archives from older versions may lack them; Syncthing regenerates the HTTPS pair.
                Log.w(TAG, "importConfig: Archive without [${optionalFile.name}]")
            }
        }
        // The database of the previous setup does not match the imported config.
        val archiveHasDatabase = File(stagingDir, Constants.getIndexDbFolder(this).name).exists()
        if (!moveIntoFilesDir(stagingDir)) {
            Log.e(TAG, "importConfig: Failed to move the imported files into place, previous files restored")
            return abortImport(stagingDir)
        }
        deleteRecursivelyQuietly(stagingDir)
        if (!archiveHasDatabase) {
            Log.d(TAG, "importConfig: Clearing index database")
            deleteRecursivelyQuietly(Constants.getIndexDbFolder(this))
        }

        // Import shared preferences.
        var success = true
        val sharedPreferencesFile = Constants.getSharedPrefsFile(this)
        if (sharedPreferencesFile.exists()) {
            Log.d(TAG, "importConfig: Importing shared preferences")
            success = importConfigSharedPrefs(sharedPreferencesFile)
            sharedPreferencesFile.delete()
        }

        try {
            cleanupImportedFolderDatabases()
        } catch (e: Exception) {
            Log.e(TAG, "importConfig: Failed to cleanup invalid folder databases", e)
        }

        // Start syncthing after import if run conditions apply.
        endConfigTransfer()
        return success
    }

    /**
     * The backup zip, checked to hold the required files and to open with the password of the
     * settings. Null after logging why not.
     */
    private fun openBackupZip(zipFilePath: File): ZipFile? =
        try {
            // If user set one, get password to decrypt the zip file.
            val password = preferences.getString(Constants.PREF_BACKUP_PASSWORD, null).orEmpty()
            val zipFile = if (password.isEmpty()) ZipFile(zipFilePath) else ZipFile(zipFilePath, password.toCharArray())
            val missingFile = REQUIRED_FILES.firstOrNull { zipFile.getFileHeader(it) == null }
            when {
                password.isNotEmpty() && !zipFile.isEncrypted -> {
                    Log.e(TAG, "importConfig: ZIP file is not encrypted, but password was specified in settings screen. Try to specify an empty password temporarily.")
                    null
                }
                missingFile != null -> {
                    Log.e(TAG, "importConfig: Required file not found inside zip [$missingFile]")
                    null
                }
                else -> {
                    // Test if supplied encryption password is correct.
                    zipFile.extractFile(Constants.PUBLIC_KEY_FILE, cacheDir.absolutePath)
                    File(cacheDir, Constants.PUBLIC_KEY_FILE).delete()
                    zipFile
                }
            }
        } catch (e: ZipException) {
            Log.e(TAG, "importConfig: Failed to open zip, " + e.message)
            null
        }

    private fun abortImport(stagingDir: File): Boolean {
        deleteRecursivelyQuietly(stagingDir)
        endConfigTransfer()
        return false
    }

    /**
     * Replaces the Web GUI HTTPS certificate and key with the supplied PEM bytes, then restarts
     * Syncthing so the new files take effect. If the restart fails to bring the Web GUI back online,
     * the previous certificate and key are restored automatically.
     *
     * The bytes are expected to already be validated (see CertificateValidator).
     * The whole start/stop lifecycle is marshalled onto the main thread because the binary
     * orchestration fields are only safe to touch there.
     */
    fun replaceHttpsCertificate(certPem: ByteArray, keyPem: ByteArray, listener: OnHttpsCertReplaceResultListener) {
        scope.launch { doReplaceHttpsCertificate(certPem, keyPem, listener) }
    }

    /**
     * Deletes the user-supplied HTTPS certificate/key so Syncthing regenerates a fresh self-signed
     * certificate at the next start, then restarts (with rollback on failure).
     */
    fun resetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        scope.launch { doResetHttpsCertificate(listener) }
    }

    private fun doReplaceHttpsCertificate(certPem: ByteArray, keyPem: ByteArray, listener: OnHttpsCertReplaceResultListener) {
        // shutdown() defers while STARTING; wait it out so our file writes don't race the binary.
        if (currentState == State.STARTING) {
            scope.launch {
                delay(1000)
                doReplaceHttpsCertificate(certPem, keyPem, listener)
            }
            return
        }
        val certFile = Constants.getHttpsCertFile(this)
        val keyFile = Constants.getHttpsKeyFile(this)

        // Stop the binary so it releases the cert/key before we overwrite them.
        if (currentState != State.DISABLED) {
            shutdown(State.DISABLED)
        }
        val certBak = backupFile(certFile)
        val keyBak = backupFile(keyFile)
        try {
            FileUtils.writeBytesAtomic(certFile, certPem)
            FileUtils.writeBytesAtomic(keyFile, keyPem)
            restrictToOwner(keyFile)
        } catch (e: IOException) {
            Log.e(TAG, "doReplaceHttpsCertificate: Failed to write new cert/key", e)
            restoreFile(certBak, certFile)
            restoreFile(keyBak, keyFile)
            if (lastDeterminedShouldRun) {
                launchStartupTask(SyncthingRunnable.Command.main)
            }
            listener.onResult(HttpsCertReplaceResult.FAILED, e.message)
            return
        }

        // Captured before the restart so we can tell afterwards whether syncthing is actually serving
        // what we wrote, or replaced it with a self-signed certificate of its own.
        val expectedFingerprint = CertificateValidator.leafFingerprint(certPem)
        applyCertChangeWithVerify(certFile, keyFile, certBak, keyBak, expectedFingerprint, listener)
    }

    private fun doResetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        if (currentState == State.STARTING) {
            scope.launch {
                delay(1000)
                doResetHttpsCertificate(listener)
            }
            return
        }
        val certFile = Constants.getHttpsCertFile(this)
        val keyFile = Constants.getHttpsKeyFile(this)
        if (currentState != State.DISABLED) {
            shutdown(State.DISABLED)
        }
        val certBak = backupFile(certFile)
        val keyBak = backupFile(keyFile)
        // Removing the files makes syncthing generate a fresh self-signed certificate at startup.
        deleteQuietly(certFile)
        deleteQuietly(keyFile)

        // No expected fingerprint: regenerating is the whole point here, so any certificate syncthing
        // comes up with is the right one.
        applyCertChangeWithVerify(certFile, keyFile, certBak, keyBak, null, listener)
    }

    private fun applyCertChangeWithVerify(
        certFile: File,
        keyFile: File,
        certBak: File?,
        keyBak: File?,
        expectedFingerprint: String?,
        listener: OnHttpsCertReplaceResultListener,
    ) {
        if (lastDeterminedShouldRun) {
            verifyRestartAndRollback(certFile, keyFile, certBak, keyBak, expectedFingerprint, listener)
        } else {
            // Not currently meant to run; the new files will take effect on next start.
            deleteQuietly(certBak)
            deleteQuietly(keyBak)
            listener.onResult(HttpsCertReplaceResult.SUCCESS_PENDING_START, null)
        }
    }

    /**
     * Restarts the binary and watches the service state: failure on ERROR / an abnormal
     * STARTING to DISABLED transition (crashed binary) / a watchdog timeout.
     *
     * Reaching ACTIVE is necessary but **not sufficient**. When syncthing cannot load the cert/key
     * pair it does not fail: it generates a fresh self-signed certificate, overwrites the files, and
     * starts normally (see lib/api/api.go getListener). So success additionally requires the
     * certificate on disk to still be the one we wrote, identified by expectedFingerprint.
     *
     * On any failure the backed-up cert/key are restored and a known-good instance is brought back up.
     */
    private fun verifyRestartAndRollback(
        certFile: File,
        keyFile: File,
        certBak: File?,
        keyBak: File?,
        expectedFingerprint: String?,
        listener: OnHttpsCertReplaceResultListener,
    ) {
        var resolved = false
        var sawStarting = false
        var failureReason = HttpsCertReplaceResult.FAILED_NOT_ONLINE

        val finishFailure = {
            runWhenNotStarting {
                // Stop first, then restore. In the FAILED_CERT_REJECTED case syncthing is running and
                // serving a certificate that differs from the file on disk; restoring the file underneath
                // a live instance would leave ApiRequest pinning against a certificate that is not the one
                // being served, so any request in that window fails.
                if (currentState != State.DISABLED && currentState != State.INIT) {
                    shutdown(State.INIT)
                }
                restoreFile(certBak, certFile)
                restoreFile(keyBak, keyFile)
                // Bring the previous, known-good certificate back online.
                launchStartupTask(SyncthingRunnable.Command.main)
                listener.onResult(failureReason, null)
            }
        }
        val finishSuccess = {
            if (expectedFingerprint != null && !certFileHasFingerprint(certFile, expectedFingerprint)) {
                Log.w(TAG, "verifyRestartAndRollback: Syncthing replaced the supplied certificate with a generated one; rolling back.")
                failureReason = HttpsCertReplaceResult.FAILED_CERT_REJECTED
                finishFailure()
            } else {
                deleteQuietly(certBak)
                deleteQuietly(keyBak)
                listener.onResult(HttpsCertReplaceResult.SUCCESS, null)
            }
        }

        lateinit var verifyListener: OnServiceStateChangeListener
        val watchdog = scope.launch {
            delay(HTTPS_CERT_VERIFY_TIMEOUT_MS)
            if (!resolved) {
                resolved = true
                unregisterOnServiceStateChangeListener(verifyListener)
                if (currentState == State.ACTIVE) finishSuccess() else finishFailure()
            }
        }
        verifyListener = OnServiceStateChangeListener { state ->
            if (resolved) {
                return@OnServiceStateChangeListener
            }
            if (state == State.STARTING) {
                sawStarting = true
                return@OnServiceStateChangeListener
            }
            val success = state == State.ACTIVE
            val failure = state == State.ERROR || (sawStarting && state == State.DISABLED)
            if (!success && !failure) {
                return@OnServiceStateChangeListener
            }
            resolved = true
            watchdog.cancel()
            // Defer unregister + lifecycle work out of onServiceStateChange's listener iteration.
            scope.launch {
                unregisterOnServiceStateChangeListener(verifyListener)
                if (success) finishSuccess() else finishFailure()
            }
        }

        // registerOnServiceStateChangeListener replays the current state (DISABLED) synchronously;
        // that is ignored because sawStarting is still false.
        registerOnServiceStateChangeListener(verifyListener)
        launchStartupTask(SyncthingRunnable.Command.main)
    }

    /**
     * Whether the certificate currently on disk is the one identified by [expected]. A missing
     * or unreadable file counts as "not it", which routes to the rollback path.
     */
    private fun certFileHasFingerprint(certFile: File, expected: String): Boolean =
        try {
            expected == CertificateValidator.leafFingerprint(certFile.readBytes())
        } catch (e: IOException) {
            Log.w(TAG, "certFileHasFingerprint: Failed to read ${certFile.name}", e)
            false
        }

    private fun backupFile(file: File): File? {
        if (!file.exists()) {
            return null
        }
        val bak = File(file.parentFile, file.name + ".bak")
        deleteQuietly(bak)
        if (file.renameTo(bak)) {
            return bak
        }
        Log.w(TAG, "backupFile: Failed to back up ${file.name}")
        return null
    }

    private fun restoreFile(bak: File?, target: File) {
        if (bak == null || !bak.exists()) {
            return
        }
        deleteQuietly(target)
        if (!bak.renameTo(target)) {
            Log.w(TAG, "restoreFile: Failed to restore ${target.name}")
        }
    }

    private fun deleteRecursivelyQuietly(fileOrDir: File) {
        try {
            FileUtils.deleteDirectoryRecursively(fileOrDir)
        } catch (e: IOException) {
            Log.w(TAG, "deleteRecursivelyQuietly: Failed to delete ${fileOrDir.name}", e)
        }
    }

    /**
     * Moves every entry of stagingDir into the files directory. Each entry replaced is kept as
     * "[name].bak" until all moves succeeded; on a failure, the moved entries are removed and the
     * backups renamed back. Renames stay atomic, the cache and files directories sharing a volume.
     */
    private fun moveIntoFilesDir(stagingDir: File): Boolean {
        val entries = stagingDir.listFiles() ?: return false
        val moved = mutableListOf<File>()
        val backups = mutableListOf<Pair<File, File>>()
        var success = true
        for (entry in entries) {
            val target = File(filesDir, entry.name)
            val backup = File(filesDir, entry.name + ".bak")
            deleteRecursivelyQuietly(backup)
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    success = false
                    break
                }
                backups += target to backup
            }
            if (!entry.renameTo(target)) {
                success = false
                break
            }
            moved += target
        }
        if (success) {
            backups.forEach { (_, backup) -> deleteRecursivelyQuietly(backup) }
            return true
        }
        moved.forEach { deleteRecursivelyQuietly(it) }
        for ((target, backup) in backups) {
            if (!backup.renameTo(target)) {
                Log.e(TAG, "moveIntoFilesDir: Failed to restore ${target.name}")
            }
        }
        return false
    }

    private fun deleteQuietly(file: File?) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "deleteQuietly: Failed to delete ${file.name}")
        }
    }

    private fun restrictToOwner(file: File) {
        // Mirror syncthing core, which writes the HTTPS key with 0600 permissions.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    /**
     * Resets Syncthing's database if a folder of the imported config has no folder or no marker
     * on this device.
     */
    private fun cleanupImportedFolderDatabases() {
        val configXml = ConfigXml(this)
        try {
            configXml.loadConfig()
        } catch (e: ConfigXml.OpenConfigException) {
            Log.w(TAG, "importConfig: Unable to parse imported config for DB cleanup")
            return
        }
        for (folder in configXml.getFolders()) {
            if (folder.id.isNullOrEmpty()) {
                continue
            }
            val folderPath = folder.path?.takeIf { it.isNotEmpty() }?.let { File(it) }
            val markerName = folder.markerName.orEmpty().ifEmpty { Constants.FILENAME_STFOLDER }
            if (folderPath == null || !folderPath.isDirectory || !File(folderPath, markerName).exists()) {
                Log.i(TAG, "importConfig: Folder path or marker missing for folder id \"${folder.id}\". Resetting Syncthing database.")
                SyncthingRunnable(this, SyncthingRunnable.Command.resetdatabase).run()
                break
            }
        }
    }

    // The result of commit() reports a failed write, which the KTX edit {} drops.
    @SuppressLint("UseKtx")
    private fun importConfigSharedPrefs(file: File): Boolean {
        try {
            // Read, deserialize shared preferences.
            val sharedPrefsMap = ObjectInputStream(FileInputStream(file)).use { it.readObject() } as? Map<*, *>
            if (sharedPrefsMap == null) {
                Log.e(TAG, "importConfig: Invalid object stream")
                return true
            }

            // Kept from the current preferences, to restore them after the import.
            val relPathToZip = preferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, "")
            val backupPassword = preferences.getString(Constants.PREF_BACKUP_PASSWORD, "")

            val editor = preferences.edit().clear()
            for ((key, value) in sharedPrefsMap) {
                val prefKey = key as String
                when (prefKey) {
                    in DEPRECATED_PREFS -> logV("importConfig: Ignoring deprecated pref \"$prefKey\".")
                    in CACHE_PREFS -> logV("importConfig: Ignoring cache pref \"$prefKey\".")
                    else -> {
                        Log.i(TAG, "importConfig: Adding pref \"$prefKey\" to commit ...")
                        // The editor only provides typed setters.
                        when (value) {
                            is Boolean -> editor.putBoolean(prefKey, value)
                            is String -> editor.putString(prefKey, value)
                            is Int -> editor.putInt(prefKey, value)
                            is Float -> editor.putFloat(prefKey, value)
                            is Long -> editor.putLong(prefKey, value)
                            is Set<*> -> editor.putStringSet(prefKey, value.mapTo(HashSet()) { it as String })
                            else -> Log.w(TAG, "importConfig: SharedPref type ${value?.javaClass?.name} is unknown")
                        }
                    }
                }
            }
            editor.putString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, relPathToZip)
            editor.putString(Constants.PREF_BACKUP_PASSWORD, backupPassword)

            // All shared preferences have been added: apply them at once.
            return editor.commit()
        } catch (e: IOException) {
            Log.e(TAG, "importConfig: Failed to import SharedPreferences", e)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "importConfig: Failed to import SharedPreferences", e)
        }
        return false
    }

    private fun logV(message: String) {
        if (verboseLog) {
            Log.v(TAG, message)
        }
    }

    companion object {
        private const val TAG = "SyncthingService"

        /**
         * Intent action to perform a Syncthing restart.
         */
        const val ACTION_RESTART = ".SyncthingService.RESTART"

        /**
         * Intent action to perform a Syncthing stop.
         */
        const val ACTION_STOP = ".SyncthingService.STOP"

        /**
         * Intent action to reset Syncthing's database.
         */
        const val ACTION_RESET_DATABASE = ".SyncthingService.RESET_DATABASE"

        /**
         * Intent action to reset Syncthing's delta indexes.
         */
        const val ACTION_RESET_DELTAS = ".SyncthingService.RESET_DELTAS"

        const val ACTION_REFRESH_NETWORK_INFO = ".SyncthingService.REFRESH_NETWORK_INFO"

        /**
         * Intent action to permanently ignore a device connection request.
         */
        const val ACTION_IGNORE_DEVICE = ".SyncthingService.IGNORE_DEVICE"

        /**
         * Intent action to permanently ignore a folder share request.
         */
        const val ACTION_IGNORE_FOLDER = ".SyncthingService.IGNORE_FOLDER"

        /**
         * Intent action to override folder changes.
         */
        const val ACTION_OVERRIDE_CHANGES = ".SyncthingService.OVERRIDE_CHANGES"

        /**
         * Intent action to revert local folder changes.
         */
        const val ACTION_REVERT_LOCAL_CHANGES = ".SyncthingService.REVERT_LOCAL_CHANGES"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE, ACTION_IGNORE_FOLDER.
         */
        const val EXTRA_NOTIFICATION_ID = ".SyncthingService.EXTRA_NOTIFICATION_ID"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE
         */
        const val EXTRA_DEVICE_ID = ".SyncthingService.EXTRA_DEVICE_ID"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE
         */
        const val EXTRA_DEVICE_ADDRESS = ".SyncthingService.EXTRA_DEVICE_ADDRESS"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE
         */
        const val EXTRA_DEVICE_NAME = ".SyncthingService.EXTRA_DEVICE_NAME"

        /**
         * Extra used together with ACTION_IGNORE_FOLDER
         */
        const val EXTRA_FOLDER_ID = ".SyncthingService.EXTRA_FOLDER_ID"

        /**
         * Extra used together with ACTION_IGNORE_FOLDER
         */
        const val EXTRA_FOLDER_LABEL = ".SyncthingService.EXTRA_FOLDER_LABEL"

        /**
         * Extra used together with ACTION_STOP.
         */
        const val EXTRA_STOP_AFTER_CRASHED_NATIVE = ".SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE"

        /**
         * [SyncthingRunnable.runId] of the binary that sends ACTION_STOP or ACTION_RESTART.
         */
        const val EXTRA_RUN_ID = ".SyncthingService.EXTRA_RUN_ID"

        /**
         * Backstop timeout for verifyRestartAndRollback in case the state machine never reaches
         * a terminal state (e.g. the binary crashed via a path that doesn't transition to ERROR).
         */
        private const val HTTPS_CERT_VERIFY_TIMEOUT_MS = 30_000L

        /**
         * Files an archive must hold to be imported.
         */
        private val REQUIRED_FILES = listOf(Constants.CONFIG_FILE, Constants.PRIVATE_KEY_FILE, Constants.PUBLIC_KEY_FILE)

        /**
         * Preferences that are no longer used and left-overs from previous versions of the app.
         */
        private val DEPRECATED_PREFS = setOf(
            "first_start", "advanced_folder_picker", "backup_folder_name", "bind_network", "log_to_file",
            "notification_type", "notify_crashes", "suggest_new_folder_root", "use_legacy_hashing",
            "pref_current_language", "restartOnWakeup", "wakelock_while_binary_running", "use_root",
            "important_news_shown_version",
        )

        /**
         * Cached information which is not available on SettingsActivity.
         */
        private val CACHE_PREFS = setOf(
            Constants.PREF_APP_START_COUNTER,
            Constants.PREF_BTNSTATE_FORCE_START_STOP,
            Constants.PREF_DEBUG_FACILITIES_AVAILABLE,
            Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID,
            Constants.PREF_LAST_BINARY_VERSION,
            Constants.PREF_LOCAL_DEVICE_ID,
            Constants.PREF_LAST_RUN_TIME,
        )
    }
}
