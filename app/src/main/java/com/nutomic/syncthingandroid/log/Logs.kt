package com.nutomic.syncthingandroid.log

import java.io.File
import java.io.RandomAccessFile

private const val ANDROID_LOG_MAX_LINES = 2000

/**
 * The Syncthing log grows until the next start of the binary: only its end is shown.
 */
private const val SYNCTHING_LOG_MAX_LINES = 2000
private const val SYNCTHING_LOG_MAX_BYTES = 512 * 1024

/**
 * Log tags and messages of the system and of other libraries, of no use to read Syncthing's state.
 */
private val androidLogNoise = listOf(
    "--- beginning of ", "/AbsListViewStubImpl", "W/ActionBarDrawerToggle", "/ActivityThread", "/Adreno",
    "/AdrenoUtils", "/AiRecognition", "/androidtc", "/AssistStructure", "/AudioCapabilities",
    "/BLASTBufferQueue_Java", "/BinderMonitor", "I/chatty", "/CameraExtImplXiaoMi", "/CameraManagerGlobal",
    "/Choreographer", "W/chmod", "/chromium", "/ContentCaptureHelper", "/ContentCatcher", "/cr_AwContents",
    "/cr_base", "/cr_BrowserStartup", "/cr_CachingUmaRecorder", "/cr_ChildProcessConn", "/cr_ChildProcLH",
    "/cr_CombinedPProvider", "/cr_CrashFileManager", "/cr_LibraryLoader", "/cr_media", "/cr_MediaCodecUtil",
    "/cr_PolicyProvider", "/cr_VAUtil", "/cr_WVCFactoryProvider", "I/ConfigStore", "/dalvikvm", "/DecorView",
    "/DpmTcmClient", "/EditorStubImpl", "/EGL", "/EpFrameworkFactory", "/eglCodecCommon", "/FeatureParser",
    "/FenceTime", "E/FileUtils err", "/FloatingActionMode", "/ForceDarkHelperStubImpl", "/FrameEvents",
    "/FramePredict", "/FrameTracker", "/Gralloc", "/HandWritingStubImpl", "/HWUI", "/IInputConnectionWrapper",
    "/ImeTracker", "/ImeBackDispatcher", "/ImeFocusController", "/InputEventReceiver", "/InputMethodManager",
    "/InsetsController", "/InsetsSourceConsumer", "/JavaheapMonitor", "E/LB", "W/Looper", "/libEGL",
    "/libMiGL", "E/libc", "W/libc", "/MessageMonitor", "/MIUI", "/MiInputConsumer", "/Miui",
    "MiuiBoosterUtils", "/NativeTurboSchedManager", "W/netstat", "/ngandroid.debu", "/OpenGLRenderer",
    "/PacProxySelector", "/Perf", "/re-initialized", "/RemoteInputConnectionImpl", "/RenderInspector",
    "/RenderThread", "/ResourceType", "W/sh", "W/Settings", "/SplineOverScroller", "/StrictMode",
    "/studio.deploy", "/SurfaceComposerClient", "/SurfaceSyncGroup", "I/System.out", "W/TextView",
    "I/Timeline", "I/VRI", "/VideoCapabilities", "/ViewRootImpl", "I/WebViewFactory", "WindowOnBackDispatcher",
    "I/X509Util", "/ziparchive", "/zygote", "/zygote64",
)

private val pid = Regex("\\(\\s?[0-9]+\\):")
private val date = Regex("^[0-9]{2}-[0-9]{2}\\s")
private val milliseconds = Regex("^([0-9]{2}:[0-9]{2}:[0-9]{2})\\.[0-9]{3}\\s")

/**
 * The last lines of the Android log, from logcat run directly, without a shell.
 */
internal fun readAndroidLog(): String {
    val process = ProcessBuilder(
        "/system/bin/logcat", "-t", ANDROID_LOG_MAX_LINES.toString(), "-v", "time", "*:i", "ps:s", "art:s"
    ).redirectErrorStream(true).start()
    return process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
}

/**
 * Drops the noise of the system from logcat output, the Syncthing native tag and the PIDs, and
 * shortens each timestamp to the time of day.
 */
internal fun cleanAndroidLog(output: String): String =
    output
        .replace("I/SyncthingNativeCode", "")
        .replace(pid, "")
        .split("\n")
        .filterNot { line -> androidLogNoise.any { it in line } }
        .joinToString("\n") { line -> line.replaceFirst(date, "").replaceFirst(milliseconds, "$1") }

/**
 * The last lines of a log file, read from at most its last bytes without loading the whole file.
 * Empty if the file does not exist.
 */
internal fun readLogFileTail(
    file: File,
    maxBytes: Int = SYNCTHING_LOG_MAX_BYTES,
    maxLines: Int = SYNCTHING_LOG_MAX_LINES,
): String {
    if (!file.exists()) {
        return ""
    }
    RandomAccessFile(file, "r").use { raf ->
        val start = maxOf(0, raf.length() - maxBytes)
        val data = ByteArray((raf.length() - start).toInt())
        raf.seek(start)
        raf.readFully(data)
        val lines = String(data, Charsets.UTF_8).split("\n")
        // The first line was cut by the seek, unless the file was read from its start.
        val from = maxOf(if (start > 0) 1 else 0, lines.size - maxLines)
        return lines.subList(from, lines.size).joinToString("\n")
    }
}
