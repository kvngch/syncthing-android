package com.nutomic.syncthingandroid.util

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.text.DecimalFormat
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.log10
import kotlin.math.pow

object Util {
    private const val TAG = "Util"

    /**
     * Time a process gets to end after SIGINT before it gets SIGKILL. killProcess runs on the main
     * thread from SyncthingService: the whole wait must stay under the 5 s after which Android
     * raises an ANR on an unresponsive UI.
     */
    private const val KILL_PROCESS_GRACE_MS = 3000L

    private val syncConflictFileName = Regex(".*\\.sync-conflict-[0-9]{8}-[0-9]{6}-[a-zA-Z0-9]{7}.*")

    /**
     * Converts a number of bytes to a human readable file size (eg 3.5 GiB).
     *
     * Based on http://stackoverflow.com/a/5599842
     */
    @JvmStatic
    fun readableFileSize(context: Context, bytes: Double): String =
        readable(context.resources.getStringArray(R.array.file_size_units), bytes)

    /**
     * Converts a number of bits to a human readable transfer rate in bytes per second
     * (eg 100 KiB/s).
     */
    @JvmStatic
    fun readableTransferRate(context: Context, bits: Long): String =
        readable(context.resources.getStringArray(R.array.transfer_rate_units), (bits / 8).toDouble())

    private fun readable(units: Array<String>, value: Double): String {
        if (value <= 0) {
            return "0 " + units[0]
        }
        val digitGroups = (log10(value) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(value / 1024.0.pow(digitGroups)) + " " + units[digitGroups]
    }

    /**
     * Whether the syncthing binary can write into the folder. It runs under our uid, so a plain
     * write from the app tests the same access.
     */
    @JvmStatic
    fun nativeBinaryCanWriteToPath(context: Context, absoluteFolderPath: String): Boolean {
        val touchFile = File(absoluteFolderPath, ".stwritetest")
        try {
            FileOutputStream(touchFile).close()
        } catch (e: IOException) {
            Log.i(TAG, "Failed to write test file '$touchFile', ${e.message}")
            return false
        }
        Log.i(TAG, "Successfully wrote test file '$touchFile'")
        if (!touchFile.delete()) {
            // This is very unlikely to happen, so we have less error handling.
            Log.i(TAG, "Failed to remove test file")
        }
        return true
    }

    /**
     * Ends the processes running [processName] gracefully, or by force after
     * KILL_PROCESS_GRACE_MS. Returns after about KILL_PROCESS_GRACE_MS + 1 s at most.
     */
    @JvmStatic
    fun killProcess(processName: String) {
        val pids = processPids(processName)
        if (pids.isEmpty()) {
            Log.v(TAG, "killProcess: Found no running instances of [$processName]")
            return
        }
        pids.forEach { signal(it, OsConstants.SIGINT) }
        if (waitForProcessesToEnd(processName, KILL_PROCESS_GRACE_MS)) {
            Log.d(TAG, "killProcess: No more instances of [$processName] running")
            return
        }
        // Stuck on exit, e.g. on a storage that no longer answers. Syncthing's SQLite database
        // stays consistent through a SIGKILL.
        processPids(processName).forEach {
            Log.w(TAG, "killProcess: [$processName] still running, sending SIGKILL to $it")
            signal(it, OsConstants.SIGKILL)
        }
        if (!waitForProcessesToEnd(processName, 1000)) {
            Log.e(TAG, "killProcess: [$processName] still running after SIGKILL")
        }
    }

    private fun signal(pid: Int, signal: Int) {
        try {
            Os.kill(pid, signal)
        } catch (e: ErrnoException) {
            Log.w(TAG, "killProcess: Failed to send signal $signal to process [$pid]", e)
        }
    }

    /**
     * The processes whose command line holds [processName]. Android only shows an app its own
     * processes in /proc, the syncthing binary among them.
     */
    private fun processPids(processName: String): List<Int> =
        File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            // Gone meanwhile, or not ours.
            val commandLine = try {
                File(dir, "cmdline").readText()
            } catch (e: IOException) {
                return@mapNotNull null
            }
            pid.takeIf { processName in commandLine && it != Process.myPid() }
        }

    /**
     * True once no process runs [processName], false if [timeoutMs] elapsed first.
     */
    private fun waitForProcessesToEnd(processName: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (processPids(processName).isNotEmpty()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                return false
            }
            SystemClock.sleep(50)
        }
        return true
    }

    /**
     * Whether a TCP port is in use on the device. netstat reads /proc/net, closed to apps since
     * Android 10: try to bind the port instead, on all interfaces, which fails if anything already
     * listens on it.
     */
    @JvmStatic
    fun isTcpPortListening(port: Int): Boolean =
        try {
            ServerSocket().use { it.bind(InetSocketAddress(port)) }
            false
        } catch (e: IOException) {
            true
        }

    /**
     * The path in a normalized form: no "." or ".." segment, no double slash.
     */
    @JvmStatic
    fun formatPath(path: String): String = File(path).toURI().normalize().path

    /**
     * Shortens each part of a path, to display it where space is short.
     */
    @JvmStatic
    fun getPathEllipsis(fullFN: String): String {
        val maxCharsSubdir = 15
        val maxCharsFilename = maxCharsSubdir * 2
        val parts = fullFN.split('/')
        val directories = parts.dropLast(1).map {
            if (it.length > maxCharsSubdir) it.take(maxCharsSubdir) + "⋯" else it
        }
        var fileName = parts.last()
        if (fileName.length > maxCharsFilename) {
            val indexFileExt = fileName.lastIndexOf('.')
            fileName = if (indexFileExt > 0) {
                // Filename with extension.
                var name = fileName.substring(0, indexFileExt)
                if (name.length > maxCharsFilename) {
                    name = name.take(maxCharsFilename) + "⋯"
                }
                name + fileName.substring(indexFileExt)
            } else {
                fileName.take(maxCharsFilename) + "⋯"
            }
        }
        return (directories + fileName).joinToString("/")
    }

    @JvmStatic
    fun isRunningOnTV(context: Context): Boolean = context.resources.configuration.isTelevision

    /**
     * The date and time of a Syncthing timestamp, localized, in the device's time zone.
     */
    @JvmStatic
    fun formatDateTime(dateTime: String): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .format(ZonedDateTime.parse(dateTime).withZoneSameInstant(ZoneId.systemDefault()))

    /**
     * The time of a Syncthing timestamp, localized, in the device's time zone.
     */
    @JvmStatic
    fun formatTime(dateTime: String): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .format(ZonedDateTime.parse(dateTime).withZoneSameInstant(ZoneId.systemDefault()))

    /**
     * Returns the current time in UTC, e.g. "2021-02-11T22:11:29.356Z".
     * Despite the name, the local clock time is not used: Syncthing expects UTC.
     */
    @JvmStatic
    fun getLocalZonedDateTime(): String =
        ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /**
     * Runs each ".sh" script of [absPath] from the folder root. Called by RestApi after a folder
     * completed.
     */
    @JvmStatic
    fun runScriptSet(absPath: String, scriptArgs: Array<String>?) {
        val scriptFolder = File(absPath)
        if (!scriptFolder.isDirectory) {
            Log.w(TAG, "runScriptSet: Folder does not exist or is not of type folder: $absPath")
            return
        }
        val scriptFiles = scriptFolder.listFiles { _, name -> name.lowercase(Locale.ROOT).endsWith(".sh") }
        if (scriptFiles.isNullOrEmpty()) {
            Log.v(TAG, "runScriptSet: No script files found within folder: $absPath")
            return
        }
        for (scriptFile in scriptFiles) {
            // Paths and arguments go to the process as separate argv entries, never through a shell.
            val command = listOf("sh", scriptFile.absolutePath) + scriptArgs.orEmpty()
            try {
                val process = ProcessBuilder(command)
                    .directory(scriptFolder.parentFile)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                Log.v(TAG, "runScriptSet: Exec result [$output]")
            } catch (e: IOException) {
                Log.w(TAG, "runScriptSet: Failed to run ${scriptFile.name}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * The paths, relative to [absPath], of the ".sync-conflict-YYYYMMDD-HHMMSS-DEVICEI*" files
     * found outside the top level ".stversions" directory. Symlinks are not followed. Stops early
     * if the calling thread is interrupted. Called by RestApi after a folder completed.
     */
    @JvmStatic
    fun getSyncConflictFiles(absPath: String): Array<String> {
        val result = mutableListOf<String>()
        collectSyncConflictFiles(File(absPath), "", result)
        return result.toTypedArray()
    }

    private fun collectSyncConflictFiles(dir: File, relativeDir: String, result: MutableList<String>) {
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (Thread.currentThread().isInterrupted) {
                return
            }
            val relativePath = relativeDir + entry.name
            val symlink = Files.isSymbolicLink(entry.toPath())
            if (entry.isDirectory) {
                if (relativePath != Constants.FOLDER_NAME_STVERSIONS && !symlink) {
                    collectSyncConflictFiles(entry, "$relativePath/", result)
                }
            } else if (syncConflictFileName.matches(entry.name) && entry.isFile && !symlink) {
                result.add(relativePath)
            }
        }
    }

    /**
     * Backed by the Android OS trust store ("AndroidCAStore"), which aggregates both the system
     * CAs and the CAs the user installed. Built once, on first use.
     */
    private val cachedOsTrustManager: X509TrustManager? by lazy {
        try {
            val caStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(caStore) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "getOsTrustManager: Failed to build OS trust manager", e)
            null
        }
    }

    /**
     * An [X509TrustManager] that validates against the Android OS trust store, including
     * user-installed CAs. Unlike a `TrustManagerFactory.init((KeyStore) null)`, the
     * "AndroidCAStore" keystore exposes user-added certificates.
     *
     * Used as a fallback so the app can talk to a local Syncthing instance whose HTTPS certificate
     * was replaced with one signed by a CA the user trusts at the OS level (see
     * https://github.com/researchxxl/syncthing-android/issues/222).
     *
     * @return the OS-backed trust manager, or null if it could not be built.
     */
    @JvmStatic
    fun getOsTrustManager(): X509TrustManager? = cachedOsTrustManager
}
