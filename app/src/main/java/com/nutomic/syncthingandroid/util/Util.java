package com.nutomic.syncthingandroid.util;

import android.app.Dialog;
import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.service.Constants;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.KeyStore;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class Util {

    private static final String TAG = "Util";

    private Util() {
    }

    /**
     * Converts a number of bytes to a human readable file size (eg 3.5 GiB).
     * <p>
     * Based on http://stackoverflow.com/a/5599842
     */
    public static String readableFileSize(Context context, double bytes) {
        final String[] units = context.getResources().getStringArray(R.array.file_size_units);
        if (bytes <= 0) return "0 " + units[0];
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return new DecimalFormat("#,##0.#")
                .format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    /**
     * Converts a number of bytes to a human readable transfer rate in bytes per second
     * (eg 100 KiB/s).
     * <p>
     * Based on http://stackoverflow.com/a/5599842
     */
    public static String readableTransferRate(Context context, long bits) {
        final String[] units = context.getResources().getStringArray(R.array.transfer_rate_units);
        long bytes = bits / 8;
        if (bytes <= 0) return "0 " + units[0];
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return new DecimalFormat("#,##0.#")
                .format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    /**
     * Returns if the syncthing binary would be able to write a file into
     * the given folder given the configured access level.
     */
    public static boolean nativeBinaryCanWriteToPath(Context context, String absoluteFolderPath) {
        final String TOUCH_FILE_NAME = ".stwritetest";

        // Write permission test file. The native binary runs under our uid, so a plain
        // Java write tests the same access without passing the path through a shell.
        File touchFile = new File(absoluteFolderPath, TOUCH_FILE_NAME);
        try {
            new FileOutputStream(touchFile).close();
        } catch (IOException e) {
            Log.i(TAG, "Failed to write test file '" + touchFile + "', " + e.getMessage());
            return false;
        }

        // Detected we have write permission.
        Log.i(TAG, "Successfully wrote test file '" + touchFile + "'");

        // Remove test file.
        if (!touchFile.delete()) {
            // This is very unlikely to happen, so we have less error handling.
            Log.i(TAG, "Failed to remove test file");
        }
        return true;
    }

    /**
     * Look for running processes and return an array
     * containing the PIDs of found instances.
     */
    public static List<String> getProcessPIDs(final String processName) {
        List<String> processPIDs = new ArrayList<String>();
        String output = runShellCommandGetOutput("ps\n");
        if (TextUtils.isEmpty(output)) {
            Log.w(TAG, "getProcessPIDs: Failed to list processes. ps command returned empty.");
            return processPIDs;
        }

        String lines[] = output.split("\n");
        if (lines.length == 0) {
            Log.w(TAG, "getProcessPIDs: Failed to list processes. ps command returned no rows.");
            return processPIDs;
        }

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains(processName)) {
                String processPID = line.trim().split("\\s+")[1];
                // Log.v(TAG, "getProcessPIDs: Found PID [" + processPID + "] for ["+ processName + "]");
                processPIDs.add(processPID);
            }
        }
        return processPIDs;
    }

    /**
     * Time a process gets to end after SIGINT before it gets SIGKILL. killProcess runs on the main
     * thread from SyncthingService: the whole wait must stay under the 5 s after which Android
     * raises an ANR on an unresponsive UI.
     */
    private static final long KILL_PROCESS_GRACE_MS = 3000;

    /**
     * Look for running processes and end them gracefully, or by force after KILL_PROCESS_GRACE_MS.
     * Returns after about KILL_PROCESS_GRACE_MS + 1 s at most; it waited without limit before.
     */
    public static void killProcess(final String processName) {
        int exitCode;
        List<String> processPIDs = getProcessPIDs(processName);
        if (processPIDs.isEmpty()) {
            Log.v(TAG, "killProcess: Found no running instances of [" + processName + "]");
            return;
        }
        for (String processPID : processPIDs) {
            exitCode = runShellCommand("kill -SIGINT " + processPID + "\n");
            if (exitCode != 0) {
                Log.w(TAG, "killProcess: Failed to send kill SIGINT to process [" + processPID +
                        "] exit code " + Integer.toString(exitCode));
            }
        }

        if (waitForProcessesToEnd(processName, KILL_PROCESS_GRACE_MS)) {
            Log.d(TAG, "killProcess: No more instances of [" + processName + "] running");
            return;
        }
        // Stuck on exit, e.g. on a storage that no longer answers. Syncthing's SQLite database
        // stays consistent through a SIGKILL.
        for (String processPID : getProcessPIDs(processName)) {
            Log.w(TAG, "killProcess: [" + processName + "] still running, sending SIGKILL to " + processPID);
            runShellCommand("kill -9 " + processPID + "\n");
        }
        if (!waitForProcessesToEnd(processName, 1000)) {
            Log.e(TAG, "killProcess: [" + processName + "] still running after SIGKILL");
        }
    }

    /**
     * Returns true once no process named processName runs, false if timeoutMs elapsed first.
     */
    private static boolean waitForProcessesToEnd(String processName, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (!getProcessPIDs(processName).isEmpty()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                return false;
            }
            SystemClock.sleep(50);
        }
        return true;
    }

    /**
     * Run command in a shell and return the exit code.
     */
    public static int runShellCommand(String cmd) {
        // Assume "failure" exit code if an error is caught.
        // Note: redirectErrorStream(true); System.getProperty("line.separator");
        int exitCode = 255;
        Process shellProc = null;
        DataOutputStream shellOut = null;
        try {
            shellProc = Runtime.getRuntime().exec("sh");
            shellOut = new DataOutputStream(shellProc.getOutputStream());
            BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(shellOut));
            Log.d(TAG, "runShellCommand: " + cmd);
            bufferedWriter.write(cmd);
            bufferedWriter.flush();
            shellOut.close();
            shellOut = null;
            BufferedReader bufferedReader = null;
            try {
                bufferedReader = new BufferedReader(new InputStreamReader(shellProc.getInputStream(), Charsets.UTF_8));
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    Log.v(TAG, "runShellCommand: " + line);
                }
            } catch (IOException e) {
                Log.w(TAG, "runShellCommand: Failed to read output", e);
            } finally {
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
            }
            exitCode = shellProc.waitFor();
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "runShellCommand: Exception", e);
        } finally {
            try {
                if (shellOut != null) {
                    shellOut.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "runShellCommand: Failed to close stream", e);
            }
            if (shellProc != null) {
                shellProc.destroy();
            }
        }
        return exitCode;
    }

    public static String runShellCommandGetOutput(String cmd) {
        // Note: redirectErrorStream(true); System.getProperty("line.separator");
        int exitCode = 255;
        String capturedStdOut = "";
        Process shellProc = null;
        DataOutputStream shellOut = null;
        try {
            shellProc = Runtime.getRuntime().exec("sh");
            shellOut = new DataOutputStream(shellProc.getOutputStream());
            BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(shellOut));
            Log.d(TAG, "runShellCommandGetOutput: " + cmd);
            bufferedWriter.write(cmd);
            bufferedWriter.flush();
            shellOut.close();
            shellOut = null;
            BufferedReader bufferedReader = null;
            try {
                bufferedReader = new BufferedReader(new InputStreamReader(shellProc.getInputStream(), Charsets.UTF_8));
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    // Log.i(TAG, "runShellCommandGetOutput: " + line);
                    capturedStdOut = capturedStdOut + line + "\n";
                }
            } catch (IOException e) {
                Log.w(TAG, "runShellCommandGetOutput: Failed to read output", e);
            } finally {
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
            }
            exitCode = shellProc.waitFor();
            if (exitCode != 0) {
                Log.i(TAG, "runShellCommandGetOutput: Exited with code " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "runShellCommandGetOutput: Exception", e);
        } finally {
            try {
                if (shellOut != null) {
                    shellOut.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "runShellCommandGetOutput: Failed to close shell stream", e);
            }
            if (shellProc != null) {
                shellProc.destroy();
            }
        }

        // Return captured command line output.
        return capturedStdOut;
    }

    /**
     * Check if a TCP is listening on the local device on a specific port.
     * netstat reads /proc/net, closed to apps since Android 10: try to bind the port instead,
     * on all interfaces, which fails if anything already listens on it.
     */
    public static Boolean isTcpPortListening(Integer port) {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Make sure that dialog is showing and activity is valid before dismissing dialog, to prevent
     * various crashes.
     */
    public static void dismissDialogSafe(Dialog dialog, AppCompatActivity activity) {
        if (dialog == null || !dialog.isShowing())
            return;

        if (activity.isFinishing())
            return;

        if (activity.isDestroyed())
            return;

        dialog.dismiss();
    }

    /**
     * Format a path properly.
     *
     * @param path String containing the path that needs formatting.
     * @return formatted file path as a string.
     */
    public static String formatPath(String path) {
        return new File(path).toURI().normalize().getPath();
    }

    /**
     * Shorten a path using ellipsis to display it on UI
     * where we have little space to display it.
     */
    public static final String getPathEllipsis(final String fullFN) {
        final boolean FUNC_LOG_D = false;
        final boolean FUNC_LOG_V = false;
        final int MAX_CHARS_SUBDIR = 15;
        final int MAX_CHARS_FILENAME = MAX_CHARS_SUBDIR * 2;

        int index;
        String part;
        String workIn = fullFN;
        String workOut = "";
        while(true) {
            index = workIn.indexOf('/');
            if (index < 0) {
                // Last part is the filename.
                if (FUNC_LOG_V) {
                    Log.v(TAG, "getPathEllipsis: workIn [" + workIn + "] @ index <= 0");
                }
                if (workIn.length() > MAX_CHARS_FILENAME) {
                    int indexFileExt = workIn.lastIndexOf(".");
                    if (indexFileExt > 0) {
                        // Filename with extension.
                        String fileName = workIn.substring(0, indexFileExt);
                        if (fileName.length() > MAX_CHARS_FILENAME) {
                            fileName = fileName.substring(0, MAX_CHARS_FILENAME) + "\u22ef";
                        }
                        workIn = fileName + workIn.substring(indexFileExt);
                    } else {
                        // Filename without extension
                        workIn = workIn.substring(0, MAX_CHARS_FILENAME) + "\u22ef";
                    }
                }
                workOut += workIn;
                break;
            }
            // Handle one directory from the path.
            part = workIn.substring(0, index);
            if (FUNC_LOG_V) {
                Log.v(TAG, "getPathEllipsis: part [" + part + "]");
            }
            if (part.length() > MAX_CHARS_SUBDIR) {
                part = part.substring(0, MAX_CHARS_SUBDIR) + "\u22ef";
            }
            workOut += part + "/";
            workIn = workIn.substring(index + 1);
            if (FUNC_LOG_V) {
                Log.v(TAG, "getPathEllipsis: workIn [" + workIn + "], workOut [" + workOut + "]");
            }
        }
        if (FUNC_LOG_D) {
            Log.v(TAG, "getPathEllipsis: INP [" + fullFN + "]");
            Log.v(TAG, "getPathEllipsis: OUT [" + workOut + "]");
        }
        return workOut;
    }


    public static boolean containsIgnoreCase(String src, String what) {
        final int length = what.length();
        if (length == 0) {
            return true;
        }
        for (int i = src.length() - length; i >= 0; i--) {
            if (src.regionMatches(true, i, what, 0, length)) {
                return true;
            }
        }
        return false;
    }

    public static Boolean isRunningOnTV(Context context) {
        // Same test as the Kotlin extension used by the Compose screens.
        return ExtensionsKt.isTelevision(context.getResources().getConfiguration());
    }

    /**
     * Asks whether to drop the unsaved changes of an edit screen; OK closes it without a result.
     * Shared by FolderActivity and DeviceActivity.
     */
    public static Dialog showDiscardChangesDialog(AppCompatActivity activity) {
        Dialog dialog = new AlertDialog.Builder(activity)
                .setMessage(R.string.dialog_discard_changes)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    activity.setResult(AppCompatActivity.RESULT_CANCELED);
                    activity.finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        return dialog;
    }

    /**
     * Converts dateTime to readable localized string.
     */
    public static String formatDateTime(String dateTime) {
        // Convert dateTime to readable localized string.
        ZonedDateTime parsedDateTime = ZonedDateTime.parse(dateTime);
        ZonedDateTime zonedDateTime = parsedDateTime.withZoneSameInstant(ZoneId.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault());
        return formatter.format(zonedDateTime);
    }

    public static String formatTime(String dateTime) {
        // Convert dateTime to readable localized string.
        ZonedDateTime parsedDateTime = ZonedDateTime.parse(dateTime);
        ZonedDateTime zonedDateTime = parsedDateTime.withZoneSameInstant(ZoneId.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault());
        return formatter.format(zonedDateTime);
    }

    /**
     * Returns the current time in UTC, e.g. "2021-02-11T22:11:29.356Z".
     * Despite the name, the local clock time is not used: Syncthing expects UTC.
     */
    public static String getLocalZonedDateTime() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
    
    /**
     * Called by RestApi/setRemoteCompletionInfo after folder completed.
     */
    public static void runScriptSet(final String absPath, final String[] scriptArgs) {
        File scriptFolder = new File(absPath);
        if (!scriptFolder.exists() || !scriptFolder.isDirectory()) {
            Log.w(TAG, "runScriptSet: Folder does not exist or is not of type folder: " + absPath);
            return;
        }

        // Find all script files within given folder path.
        File[] scriptFiles = scriptFolder.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase(Locale.ROOT).endsWith(".sh");
            }
        });
        if (scriptFiles == null || scriptFiles.length == 0) {
            Log.v(TAG, "runScriptSet: No script files found within folder: " + absPath);
            return;
        }
        for (File scriptFile : scriptFiles) {
            // Paths and arguments go to the process as separate argv entries, never through a shell.
            List<String> command = new ArrayList<>();
            command.add("sh");
            command.add(scriptFile.getAbsolutePath());
            if (scriptArgs != null) {
                command.addAll(Arrays.asList(scriptArgs));
            }

            // Execute script from the folder root.
            try {
                Process process = new ProcessBuilder(command)
                        .directory(scriptFolder.getParentFile())
                        .redirectErrorStream(true)
                        .start();
                String output = CharStreams.toString(new InputStreamReader(process.getInputStream(), Charsets.UTF_8));
                process.waitFor();
                Log.v(TAG, "runScriptSet: Exec result [" + output + "]");
            } catch (IOException e) {
                Log.w(TAG, "runScriptSet: Failed to run " + scriptFile.getName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
    
    private static final Pattern SYNC_CONFLICT_FILE_NAME =
            Pattern.compile(".*\\.sync-conflict-[0-9]{8}-[0-9]{6}-[a-zA-Z0-9]{7}.*");

    /**
     * Called by RestApi/setRemoteCompletionInfo after folder completed.
     * Returns the paths, relative to absPath, of the ".sync-conflict-YYYYMMDD-HHMMSS-DEVICEI*"
     * files found outside the top level ".stversions" directory. Symlinks are not followed.
     * Stops early if the calling thread is interrupted.
     */
    public static String[] getSyncConflictFiles(final String absPath) {
        List<String> conflictFiles = new ArrayList<>();
        collectSyncConflictFiles(new File(absPath), "", conflictFiles);
        return conflictFiles.toArray(new String[0]);
    }

    private static void collectSyncConflictFiles(File dir, String relativeDir, List<String> result) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            String relativePath = relativeDir + entry.getName();
            if (entry.isDirectory()) {
                if (!relativePath.equals(Constants.FOLDER_NAME_STVERSIONS) && !isSymlink(entry)) {
                    collectSyncConflictFiles(entry, relativePath + "/", result);
                }
            } else if (SYNC_CONFLICT_FILE_NAME.matcher(entry.getName()).matches()
                    && entry.isFile() && !isSymlink(entry)) {
                result.add(relativePath);
            }
        }
    }

    /**
     * java.nio.file.Files.isSymbolicLink needs API 26, so compare the canonical path
     * of the file with its path inside the canonical parent.
     */
    private static boolean isSymlink(File file) {
        try {
            File inCanonicalParent = new File(file.getParentFile().getCanonicalFile(), file.getName());
            return !inCanonicalParent.getCanonicalFile().equals(inCanonicalParent.getAbsoluteFile());
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Cached {@link X509TrustManager} backed by the Android OS trust store ("AndroidCAStore"),
     * which aggregates both the system CAs and the CAs the user manually installed. Built lazily.
     */
    private static volatile X509TrustManager sOsTrustManager;
    private static volatile boolean sOsTrustManagerInitialized = false;

    /**
     * Returns an {@link X509TrustManager} that validates against the Android OS trust store,
     * including user-installed CAs. Unlike a {@code TrustManagerFactory.init((KeyStore) null)},
     * the "AndroidCAStore" keystore exposes user-added certificates on API 24+.
     *
     * Used as a fallback so the app can talk to a local Syncthing instance whose HTTPS certificate
     * was replaced with one signed by a CA the user trusts at the OS level (see
     * https://github.com/researchxxl/syncthing-android/issues/222).
     *
     * @return the OS-backed trust manager, or {@code null} if it could not be built.
     */
    public static X509TrustManager getOsTrustManager() {
        if (!sOsTrustManagerInitialized) {
            synchronized (Util.class) {
                if (!sOsTrustManagerInitialized) {
                    try {
                        KeyStore caStore = KeyStore.getInstance("AndroidCAStore");
                        caStore.load(null);
                        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                                TrustManagerFactory.getDefaultAlgorithm());
                        tmf.init(caStore);
                        for (TrustManager tm : tmf.getTrustManagers()) {
                            if (tm instanceof X509TrustManager) {
                                sOsTrustManager = (X509TrustManager) tm;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "getOsTrustManager: Failed to build OS trust manager", e);
                    }
                    sOsTrustManagerInitialized = true;
                }
            }
        }
        return sOsTrustManager;
    }
}
