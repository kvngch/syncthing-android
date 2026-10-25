package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.defaultPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Runs the syncthing binary from command line, and writes its output to syncthing.log.
 *
 * @see <a href="https://docs.syncthing.net/users/syncthing.html">Command Line Docs</a>
 */
class SyncthingRunnable(private val context: Context, command: Command) : Runnable {

    enum class Command {
        deviceid, // Output the device ID to the command line.
        generate, // Generate keys, a config file and immediately exit.
        main, // Run the main Syncthing application.
        resetdatabase, // Reset Syncthing's database
        resetdeltas, // Reset Syncthing's delta indexes
    }

    class ExecutableNotFoundException(message: String) : Exception(message)

    private val preferences = context.defaultPreferences
    private val notificationHandler = (context.applicationContext as SyncthingApp).notificationHandler
    private val verboseLog = AppPrefs.getPrefVerboseLog(preferences)

    // Example: "/data/app/${applicationId}-8HsN-IsVtZXc8GrE5-Hepw==/lib/x86/libsyncthingnative.so"
    private val syncthingBinary = Constants.getSyncthingBinary(context)

    /**
     * Tells this run from the others, e.g. the one of a previous instance of the service.
     */
    val runId = nextRunId.incrementAndGet()
    private val syncthingLogFile = Constants.getSyncthingLogFile(context)

    private val commandLine = listOf(syncthingBinary.path) + when (command) {
        Command.deviceid -> listOf("device-id")
        Command.generate -> listOf("generate")
        Command.main -> listOf("serve", "--no-browser")
        Command.resetdatabase -> listOf("debug", "reset-database")
        Command.resetdeltas -> listOf("serve", "--debug-reset-delta-idxs")
    }

    /**
     * True for the long-running "serve" process that SyncthingService starts in its own thread
     * and watches. One-shot commands (generate, device-id, reset-database) run synchronously.
     */
    private val served = command == Command.main || command == Command.resetdeltas

    /**
     * Set by SyncthingService before it stops the binary itself, so that the exit is not
     * reported back to it as an unexpected one.
     */
    @Volatile
    private var stopRequested = false

    // syncthing.log, written by both reader threads. Guarded by logFileLock.
    private val logFileLock = Any()
    private var logFileOutput: FileOutputStream? = null
    private var logBytesSinceTrim = 0L

    fun setStopRequested() {
        stopRequested = true
    }

    override fun run() {
        try {
            run(false)
        } catch (e: ExecutableNotFoundException) {
            throw RuntimeException(e.message)
        }
    }

    /**
     * Runs the command and waits for its end. Returns what the binary printed if [returnStdOut],
     * else writes it to syncthing.log and returns "".
     */
    @Throws(ExecutableNotFoundException::class)
    fun run(returnStdOut: Boolean): String {
        var sendStopToService = false
        var restartSyncthingNative = false
        var capturedStdOut = ""

        trimSyncthingLogFile()

        var multicastLock: WifiManager.MulticastLock? = null
        var process: Process? = null
        try {
            // Android 11 blocks local discovery if we did not acquire MulticastLock. No Wi-Fi
            // manager on devices without Wi-Fi.
            multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?)
                ?.createMulticastLock("multicastLock")
                ?.apply {
                    setReferenceCounted(true)
                    acquire()
                }

            process = setupAndLaunch(buildEnvironment())
            val readers = if (returnStdOut) {
                capturedStdOut = readStdOut(process)
                emptyList<Thread>()
            } else {
                listOf(logToFile(process.inputStream), logToFile(process.errorStream))
            }

            val exitCode = process.waitFor()
            logV("Syncthing exited with code $exitCode")
            readers.forEach { it.join() }

            when (exitCode) {
                0, 137 -> if (stopRequested) {
                    Log.i(TAG, "Syncthing was shut down normally via API or SIGKILL. Exit code = $exitCode")
                } else {
                    // Shut down from the web UI, or killed by Android (phantom process killer).
                    Log.w(TAG, "Syncthing stopped without a request from the service. Exit code = $exitCode")
                    sendStopToService = true
                }
                3 -> {
                    // Restart was requested via Rest API call.
                    Log.i(TAG, "exit reason = exitRestarting. Restarting syncthing.")
                    restartSyncthingNative = true
                }
                else -> {
                    Log.w(TAG, "Syncthing exited with code $exitCode: " + exitReason(exitCode))
                    notificationHandler.showCrashedNotification(R.string.notification_crash_title, exitCode.toString())
                    sendStopToService = true
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
            notificationHandler.showCrashedNotification(R.string.notification_crash_title, "IOException")
            sendStopToService = true
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for the syncthing binary", e)
            Thread.currentThread().interrupt()
        } finally {
            multicastLock?.release()
            process?.destroy()
            closeLogFile()
        }

        // Restart syncthing if it exited unexpectedly while running on a separate thread.
        if (served && restartSyncthingNative && !stopRequested) {
            context.startService(
                Intent(context, SyncthingService::class.java)
                    .setAction(SyncthingService.ACTION_RESTART)
                    .putExtra(SyncthingService.EXTRA_RUN_ID, runId)
            )
        }

        // Notify SyncthingService that State.STARTING or State.ACTIVE is no longer valid,
        // unless it stopped the binary itself. One-shot commands are not watched by the service.
        if (served && sendStopToService && !stopRequested) {
            context.startService(
                Intent(context, SyncthingService::class.java)
                    .setAction(SyncthingService.ACTION_STOP)
                    .putExtra(SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE, true)
                    .putExtra(SyncthingService.EXTRA_RUN_ID, runId)
            )
        }
        return capturedStdOut
    }

    private fun readStdOut(process: Process): String {
        val output = StringBuilder()
        try {
            process.inputStream.bufferedReader().forEachLine {
                Log.i(TAG_NATIVE, it)
                output.append(it).append('\n')
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read Syncthing's command line output", e)
        }
        return output.toString()
    }

    /**
     * Copies a stream of the binary to syncthing.log, on a thread of its own.
     */
    private fun logToFile(stream: InputStream) = thread {
        try {
            stream.bufferedReader().forEachLine { appendToLogFile(it) }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read Syncthing's command line output", e)
        }
    }

    /**
     * Appends one line to syncthing.log, from either reader thread, on a stream kept open. The file
     * is trimmed at each start and again every LOG_FILE_TRIM_BYTES, e.g. with STTRACE.
     */
    private fun appendToLogFile(line: String) {
        synchronized(logFileLock) {
            val output = logFileOutput ?: FileOutputStream(syncthingLogFile, true).also { logFileOutput = it }
            val bytes = (line + "\n").toByteArray()
            output.write(bytes)
            logBytesSinceTrim += bytes.size
            if (logBytesSinceTrim > LOG_FILE_TRIM_BYTES) {
                output.close()
                logFileOutput = null
                trimSyncthingLogFile()
                logBytesSinceTrim = 0
            }
        }
    }

    private fun closeLogFile() {
        synchronized(logFileLock) {
            try {
                logFileOutput?.close()
            } catch (e: IOException) {
                Log.w(TAG, "closeLogFile: Failed to close syncthing.log", e)
            }
            logFileOutput = null
        }
    }

    /**
     * Only keeps the last LOG_FILE_MAX_LINES lines of the log file, to avoid bloat.
     */
    private fun trimSyncthingLogFile() {
        if (!syncthingLogFile.exists()) {
            return
        }
        try {
            RandomAccessFile(syncthingLogFile, "r").use { input ->
                // Find the offset of the (n + 1)th newline with constant memory. The last n lines is
                // everything after that point. This will read in block-aligned chunks if
                // LOG_FILE_BUFFER_SIZE is a multiple of the filesystem block size.
                val buf = ByteArray(LOG_FILE_BUFFER_SIZE)
                val length = input.length()
                val chunks = (length + buf.size - 1) / buf.size
                var newlinesRemaining = LOG_FILE_MAX_LINES + 1
                var truncationOffset = -1L
                for (chunk in chunks - 1 downTo 0) {
                    val offset = buf.size * chunk
                    input.seek(offset)
                    // Last chunk can be smaller than the whole buffer.
                    val n = minOf(length - offset, buf.size.toLong()).toInt()
                    input.readFully(buf, 0, n)
                    val ret = findNthLastNewline(buf, n, newlinesRemaining)
                    if (ret >= 0) {
                        truncationOffset = offset + ret + 1
                        break
                    }
                    newlinesRemaining = -ret
                }
                if (truncationOffset < 0) {
                    // The file already contains fewer than maximum lines.
                    return
                }

                input.seek(truncationOffset)
                val tempFile = File(context.filesDir, "syncthing.log.tmp")
                var remain = length - truncationOffset
                FileOutputStream(tempFile).use { output ->
                    while (remain > 0) {
                        val n = minOf(remain, buf.size.toLong()).toInt()
                        input.readFully(buf, 0, n)
                        output.write(buf, 0, n)
                        remain -= n
                    }
                }
                tempFile.renameTo(syncthingLogFile)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to trim log file", e)
        }
    }

    private fun buildEnvironment(): Map<String, String> {
        val env = HashMap<String, String>()

        // Set home directory to data folder for web GUI folder picker.
        env["HOME"] = FileUtils.getSyncthingTildeAbsolutePath()

        // Set config, key and database directory.
        env["STHOMEDIR"] = context.filesDir.toString()
        env["STTRACE"] = preferences.getStringSet(Constants.PREF_DEBUG_FACILITIES_ENABLED, null).orEmpty().joinToString(",")
        env["STMONITORED"] = "1"
        env["STNOUPGRADE"] = "1"
        env["STVERSIONEXTRA"] = context.getString(R.string.app_name)

        // Database tuning against slowness.
        env["SQLITE_TMPDIR"] = context.cacheDir.absolutePath

        // Workaround SyncthingNativeCode denied to read gatewayIP by Android 14+ restriction.
        getGatewayIpV4(context)?.let { env["FALLBACK_NET_GATEWAY_IPV4"] = it }

        if (preferences.getBoolean(Constants.PREF_USE_TOR, false)) {
            env["all_proxy"] = "socks5://localhost:9050"
            env["ALL_PROXY_NO_FALLBACK"] = "1"
        } else {
            val socksProxyAddress = preferences.getString(Constants.PREF_SOCKS_PROXY_ADDRESS, null).orEmpty()
            if (socksProxyAddress.isNotEmpty()) {
                env["all_proxy"] = socksProxyAddress
            }
            val httpProxyAddress = preferences.getString(Constants.PREF_HTTP_PROXY_ADDRESS, null).orEmpty()
            if (httpProxyAddress.isNotEmpty()) {
                env["http_proxy"] = httpProxyAddress
                env["https_proxy"] = httpProxyAddress
            }
        }

        putCustomEnvironmentVariables(env)
        return env
    }

    /**
     * The "NAME=value" variables of the settings, separated by spaces.
     */
    private fun putCustomEnvironmentVariables(environment: MutableMap<String, String>) {
        val customEnvironment = preferences.getString(Constants.PREF_ENVIRONMENT_VARIABLES, null).orEmpty()
        for (variable in customEnvironment.split(" ").filter { it.isNotEmpty() }) {
            val parts = variable.split("=", limit = 2)
            if (parts.size != 2) {
                // Preferences restored from a backup did not go through the settings screen validation.
                Log.w(TAG, "Ignoring invalid environment variable [$variable]")
                continue
            }
            logV("Setting env var: [${parts[0]}]=[${parts[1]}]")
            environment[parts[0]] = parts[1]
        }
    }

    private fun setupAndLaunch(env: Map<String, String>): Process {
        // Check if "libsyncthingnative.so" exists.
        if (!syncthingBinary.exists()) {
            Log.e(TAG, "CRITICAL - Syncthing core binary is missing in APK package location $syncthingBinary")
            throw ExecutableNotFoundException(syncthingBinary.path)
        }
        return ProcessBuilder(commandLine).apply { environment().putAll(env) }.start()
    }

    private fun logV(message: String) {
        if (verboseLog) {
            Log.v(TAG, message)
        }
    }

    private companion object {
        const val TAG = "SyncthingRunnable"
        val nextRunId = AtomicInteger()
        const val TAG_NATIVE = "SyncthingNativeCode"

        const val LOG_FILE_MAX_LINES = 200000
        const val LOG_FILE_BUFFER_SIZE = 1024 * 1024
        const val LOG_FILE_TRIM_BYTES = 16L * 1024 * 1024

        fun exitReason(exitCode: Int) = when (exitCode) {
            1 -> "exitError. Another Syncthing instance may be already running."
            // This should not happen as STNOUPGRADE is set.
            2 -> "exitNoUpgradeAvailable. Another Syncthing instance may be already running."
            9 -> "exitForceKill."
            64 -> "exitInvalidCommandLine."
            else -> "unexpected."
        }

        /**
         * The offset of the nth last newline among the first [size] bytes of [data] if found there,
         * else the negative of the newlines still to find, for the search in the previous chunk.
         */
        fun findNthLastNewline(data: ByteArray, size: Int, nth: Int): Int {
            require(nth > 0) { "nth must be positive: $nth" }
            var remaining = nth
            for (i in size - 1 downTo 0) {
                if (data[i] == '\n'.code.toByte()) {
                    remaining--
                    if (remaining == 0) {
                        return i
                    }
                }
            }
            return -remaining
        }

        /**
         * The IPv4 gateway of the active network, which Android 14+ hides from the binary.
         */
        fun getGatewayIpV4(context: Context): String? {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProperties = connectivityManager.activeNetwork?.let { connectivityManager.getLinkProperties(it) }
                ?: return null
            return linkProperties.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        }
    }
}
