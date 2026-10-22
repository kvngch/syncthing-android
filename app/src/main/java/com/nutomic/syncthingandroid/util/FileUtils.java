package com.nutomic.syncthingandroid.util;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import com.nutomic.syncthingandroid.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Utils for dealing with Storage Access Framework URIs.
 */
public class FileUtils {

    private static final String TAG = "FileUtils";

    private FileUtils() {
        // Private constructor to enforce Singleton pattern.
    }

    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    private static final String DOWNLOADS_PROVIDER_AUTHORITY = "com.android.providers.downloads.documents";
    private static final String RAW_DOCUMENT_ID_PREFIX = "raw:";
    private static final String DOWNLOADS_VOLUME_NAME = "downloads";
    private static final String PRIMARY_VOLUME_NAME = "primary";
    private static final String HOME_VOLUME_NAME = "home";

    public enum ExternalStorageDirType {
        DATA,
        EXT_MEDIA,
        INT_MEDIA
    }

    public static android.net.Uri convertFromDocumentUriToTreeUri(Uri documentUri) {
        // IN: content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2F${applicationId}
        // OUT: content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia%2F${applicationId}
        String authority = documentUri.getAuthority();
        String documentId = DocumentsContract.getDocumentId(documentUri);
        return DocumentsContract.buildTreeDocumentUri(authority, documentId);
    }
    
    public static boolean directoryUriExists(final Context context, Uri documentUri) {
        Uri treeUri = convertFromDocumentUriToTreeUri(documentUri);
        String absPath = getAbsolutePathFromSAFUri(context, treeUri);
        return absPath != null && new File(absPath).exists();
    }
    
    @Nullable
    public static String getAbsolutePathFromSAFUri(final Context context, @Nullable final Uri safResultUri) {
        Uri treeUri = DocumentsContract.buildDocumentUriUsingTree(safResultUri,
            DocumentsContract.getTreeDocumentId(safResultUri));
        return getAbsolutePathFromTreeUri(context, treeUri);
    }

    @Nullable
    public static String getAbsolutePathFromTreeUri(final Context context, @Nullable final Uri treeUri) {
        if (treeUri == null) {
            Log.w(TAG, "getAbsolutePathFromTreeUri: called with treeUri == null");
            return null;
        }

        /**
         * Only the external storage provider has document ids of the form "[volume]:[path]".
         * The Downloads provider gives "raw:[absolute path]" for a folder, or opaque ids such
         * as "msd:123"; cloud providers have no local path at all.
         */
        String authority = treeUri.getAuthority();
        if (DOWNLOADS_PROVIDER_AUTHORITY.equals(authority)) {
            String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (treeDocumentId.startsWith(RAW_DOCUMENT_ID_PREFIX)) {
                return cutTrailingSlash(treeDocumentId.substring(RAW_DOCUMENT_ID_PREFIX.length()));
            }
        }
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(authority)) {
            Log.w(TAG, "getAbsolutePathFromTreeUri: No local path for provider " + authority);
            return null;
        }

        // Determine volumeId, e.g. "home", "documents"
        String volumeId = getVolumeIdFromTreeUri(treeUri);
        if (volumeId == null) {
            return null;
        }

        // Handle Uri referring to internal or external storage.
        String volumePath = getVolumePath(volumeId, context);
        if (volumePath == null) {
            return File.separator;
        }
        if (volumePath.endsWith(File.separator)) {
            volumePath = volumePath.substring(0, volumePath.length() - 1);
        }
        String documentPath = getDocumentPathFromTreeUri(treeUri);
        if (documentPath.endsWith(File.separator)) {
            documentPath = documentPath.substring(0, documentPath.length() - 1);
        }
        if (documentPath.length() > 0) {
            if (documentPath.startsWith(File.separator)) {
                return volumePath + documentPath;
            } else {
                return volumePath + File.separator + documentPath;
            }
        } else {
            return volumePath;
        }
    }

    private static List<String> getMountedStoragePaths() {
        List<String> mountPaths = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("/storage/") || line.contains("/mnt/media_rw/")) {
                    String[] parts = line.split(" ");
                    String mountPoint = parts[1];

                    // Filter
                    if ((mountPoint.startsWith("/storage/") || mountPoint.startsWith("/mnt/media_rw/"))
                            && !mountPoint.contains("emulated")
                            && new File(mountPoint).isDirectory()) {
                        mountPaths.add(mountPoint);
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "getMountedStoragePaths: Error reading /proc/mounts", e);
        }
        return mountPaths;
    }

    public static File[] getMountedStoragePathsAsFileArray() {
        List<String> paths = getMountedStoragePaths();
        List<File> files = new ArrayList<>();
        for (String path : paths) {
            File f = new File(path);
            if (f.canRead()) {
                files.add(f);
            }
        }
        return files.toArray(new File[0]);
    }

    @SuppressLint("ObsoleteSdkInt")
    private static String getVolumePath(final String volumeId, final Context context) {
        try {
            if (HOME_VOLUME_NAME.equals(volumeId)) {
                Log.v(TAG, "getVolumePath: isHomeVolume");
                // Reading the environment var avoids hard coding the case of the "documents" folder.
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath();
            }
            if (DOWNLOADS_VOLUME_NAME.equals(volumeId)) {
                Log.v(TAG, "getVolumePath: isDownloadsVolume");
                return getExternalStorageDownloadsDirectory();
            }

            StorageManager mStorageManager =
                    (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            Class<?> storageVolumeClazz = Class.forName("android.os.storage.StorageVolume");
            Method getVolumeList = mStorageManager.getClass().getMethod("getVolumeList");
            Method getUuid = storageVolumeClazz.getMethod("getUuid");
            Method getPath = storageVolumeClazz.getMethod("getPath");
            Method isPrimary = storageVolumeClazz.getMethod("isPrimary");
            Object result = getVolumeList.invoke(mStorageManager);

            final int length = Array.getLength(result);
            for (int i = 0; i < length; i++) {
                Object storageVolumeElement = Array.get(result, i);
                String uuid = (String) getUuid.invoke(storageVolumeElement);
                Boolean primary = (Boolean) isPrimary.invoke(storageVolumeElement);
                Boolean isPrimaryVolume = (primary && PRIMARY_VOLUME_NAME.equals(volumeId));
                Boolean isExternalVolume = ((uuid != null) && uuid.equals(volumeId));
                Log.d(TAG, "Found volume with uuid='" + uuid +
                    "', volumeId='" + volumeId +
                    "', primary=" + primary +
                    ", isPrimaryVolume=" + isPrimaryVolume +
                    ", isExternalVolume=" + isExternalVolume
                );
                if (isPrimaryVolume || isExternalVolume) {
                    Log.v(TAG, "getVolumePath: isPrimaryVolume || isExternalVolume");
                    // Return path if the correct volume corresponding to volumeId was found.
                    return (String) getPath.invoke(storageVolumeElement);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getVolumePath exception", e);
        }
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.w(TAG, "getVolumePath failed for volumeId='" + volumeId + "'");
            if (volumeId.equals("primary")) {
                Log.d(TAG, "volumeId == primary");
                return getInternalStorageRootAbsolutePath();
            }
            return "/storage/" + volumeId;
        // }
        // Log.e(TAG, "getVolumePath failed for volumeId='" + volumeId + "'");
        // return null;
    }

    public static File getExternalFilesDir(final Context context, String type) {
        return getExternalFilesDir(context, ExternalStorageDirType.DATA, type);
    }

    public static File getExternalFilesDir(final Context context, ExternalStorageDirType extDirType, String type) {
        /**
         * Determine the app's private data folder on external storage if present.
         * e.g. "/storage/abcd-efgh/Android/data/[PACKAGE_NAME]/files"
         * e.g. "/storage/abcd-efgh/Android/media/[PACKAGE_NAME]"
         */
        ArrayList<File> externalFilesDir = new ArrayList<>();
        switch(extDirType){
            case DATA:
                externalFilesDir.addAll(Arrays.asList(ContextCompat.getExternalFilesDirs(context, null)));
                if (externalFilesDir.size() > 1) {
                    // There is a bug on Huawei devices running Android 7, which returns the wrong external path.
                    // That's why we use ContextCompat here instead of context.
                    // See: https://stackoverflow.com/questions/39895579/fileprovider-error-onhuawei-devices
                    externalFilesDir.remove(context.getExternalFilesDir(null));
                }
                break;
            case INT_MEDIA:
                externalFilesDir.add(new File(Environment.getExternalStorageDirectory() + "/Android/media/" + context.getPackageName()));
                break;
            case EXT_MEDIA:
                externalFilesDir.addAll(Arrays.asList(context.getExternalMediaDirs()));
                if (!externalFilesDir.isEmpty()) {
                    externalFilesDir.remove(externalFilesDir.get(0));
                }
                break;
        }
        externalFilesDir.remove(null);      // getExternalFilesDirs may return null for an ejected SDcard.
        if (externalFilesDir.isEmpty()) {
            Log.w(TAG, "Could not determine app's private files directory on external storage.");
            return null;
        }
        if (type != null) {
            switch(extDirType) {
                case EXT_MEDIA:
                case INT_MEDIA:
                    if (type.equals(Environment.DIRECTORY_PICTURES)) {
                        return new File(externalFilesDir.get(0), Environment.DIRECTORY_PICTURES);
                    }
            }
        }
        return externalFilesDir.get(0);
    }

    /**
     * FileProvider does not support converting the absolute path from
     * getExternalFilesDir() to a "content://" Uri. As "file://" Uri
     * has been blocked since Android 7+, we need to build the Uri
     * manually after discovering the first external storage.
     * This is crucial to assist the user finding a writeable folder
     * to use Syncthing's two way sync feature.
     * API for getExternalFilesDirs(): 19+ (KITKAT+)
     * API for getExternalMediaDirs(): 21+ (LOLLIPOP+)
     */
    public static android.net.Uri getExternalFilesDirUri(final Context context, ExternalStorageDirType extDirType) {
        try {
            File externalFilesDir = getExternalFilesDir(context, extDirType, null);
            if (externalFilesDir == null) {
                Log.w(TAG, "Could not determine app's private files directory on external storage.");
                return null;
            }
            String absPath = externalFilesDir.getAbsolutePath();

            // Log.v(TAG, "getExternalFilesDirUri: absPath=" + absPath);
            String[] segments = absPath.split("/");
            if (segments.length < 2) {
                Log.w(TAG, "Could not extract volumeId from app's private files path '" + absPath + "'");
                return null;
            }
            // Extract the volumeId, e.g. "abcd-efgh"
            String volumeId = segments[2];
            switch(extDirType) {
                case DATA:
                    // Build the content Uri for our private ".../data/[PKG_NAME]/files" folder.
                    return android.net.Uri.parse(
                        "content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/" +
                        volumeId + "%3AAndroid%2Fdata%2F" +
                        context.getPackageName() + "%2Ffiles");
                case EXT_MEDIA:
                    // Build the content Uri for our private ".../media/[PKG_NAME]" folder.
                    return android.net.Uri.parse(
                        "content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/" +
                        volumeId + "%3AAndroid%2Fmedia%2F" +
                        context.getPackageName());
                case INT_MEDIA:
                    // Build the content Uri for our private ".../media/[PKG_NAME]" folder.
                    return android.net.Uri.parse(
                        "content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/" +
                        "primary" + "%3AAndroid%2Fmedia%2F" +
                        context.getPackageName());
            }
        } catch (Exception e) {
            Log.w(TAG, "getExternalFilesDirUri exception", e);
        }
        return null;
    }

    /**
     * FileProvider does not support converting absolute paths
     * to a "content://" Uri. As "file://" Uri has been blocked
     * since Android 7+, we need to build the Uri manually.
     */
    public static android.net.Uri getInternalStorageRootUri() {
        return android.net.Uri.parse("content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/primary%3A");
    }

    private static String getVolumeIdFromTreeUri(final Uri treeUri) {
        final String docId = DocumentsContract.getTreeDocumentId(treeUri);
        // Limit 2: a folder name may hold ":" too.
        final String[] split = docId.split(":", 2);
        if (split.length > 0) {
            return split[0];
        } else {
            return null;
        }
    }

    private static String getDocumentPathFromTreeUri(final Uri treeUri) {
        final String docId = DocumentsContract.getTreeDocumentId(treeUri);
        final String[] split = docId.split(":", 2);
        if ((split.length >= 2) && (split[1] != null)) return split[1];
        else return File.separator;
    }

    /**
     * Reading the environment var avoids hard coding the absolute path of the "/Download" folder.
     */
    public static String getExternalStorageDownloadsDirectory() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
    }

    @Nullable
    public static String cutTrailingSlash(final String path) {
        if (path.endsWith(File.separator)) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Deletes a directory recursively.
     */
    public static boolean deleteDirectoryRecursively(File dir) throws IOException {
        if (dir == null || !dir.exists()) return false;
        if (dir.isFile()) return dir.delete();

        File[] entries = dir.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                deleteDirectoryRecursively(entry);
            }
        }
        return dir.delete();
    }

    /**
     * Writes data to "[target].tmp", syncs it to disk, then renames it over target.
     * On any failure the target is left untouched and an IOException is thrown.
     */
    public static void writeBytesAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
            fos.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Failed to rename " + tmp.getName() + " to " + target.getName());
        }
    }

    /**
     * Expands the "~" path.
     * Equals SyncthingRunnable env "HOME"
     * Result: e.g. /storage/emulated/0/syncthing
     */
    public static String getSyncthingTildeAbsolutePath() {
        return FileUtils.getInternalStorageRootAbsolutePath() + "/syncthing";
    }
    
    private static String getInternalStorageRootAbsolutePath() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    /**
     * Derives the mime type from file extension.
     */
    /**
     * MIME type of a file extension, "" if unknown. The platform table replaces a 600 entry map
     * that was rebuilt on every call.
     */
    public static String getMimeTypeFromFileExtension(String fileExtension) {
        String fileMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.toLowerCase(Locale.ROOT));
        return (fileMimeType == null) ? "" : fileMimeType;
    }

    public static final DocumentFile safCreateDirectory(final DocumentFile parentFolder,
                                                                final String folderName) {
        if (parentFolder == null) {
            Log.w(TAG, "safCreateDirectory: parentFolder == null");
            return null;
        }
        DocumentFile dfNewFolder = null;
        for (DocumentFile file : parentFolder.listFiles()) {
            if (file.isDirectory() && file.getName().equals(folderName)) {
                Log.v(TAG, "safCreateDirectory: Directory already exists '" + folderName + "'");
                return file;
            }
        }
        dfNewFolder = parentFolder.createDirectory(folderName);
        if (dfNewFolder == null) {
            Log.w(TAG, "safCreateDirectory: Failed to create directory '" + folderName + "'");
            return null;
        }
        Log.v(TAG, "safCreateDirectory: Created directory '" + folderName + "'");
        return dfNewFolder;
    }

    public static final boolean safCreateFile(final Context context,
                                                    final DocumentFile parentFolder,
                                                    final String fileNameAndExtension,
                                                    final String content) {
        for (DocumentFile file : parentFolder.listFiles()) {
            if (file.isFile() && file.getName().equals(fileNameAndExtension)) {
                Log.v(TAG, "safCreateFile: File already exists '" + fileNameAndExtension + "'");
                return true;
            }
        }

        String fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileNameAndExtension);
        final String fileMimeType = FileUtils.getMimeTypeFromFileExtension(fileExtension);

        String fileName = fileNameAndExtension;
        int dotIndex = fileNameAndExtension.lastIndexOf('.');
        if (dotIndex > 0) {
            fileName = fileNameAndExtension.substring(0, dotIndex);
        }

        boolean failSuccess = false;
        OutputStream outputStream = null;
        try {
            Uri fileUri = DocumentsContract.createDocument(
                    context.getContentResolver(),
                    parentFolder.getUri(),
                    fileMimeType,
                    fileName
            );
            if (fileUri == null) {
                Log.e(TAG, "safCreateFile: Failed to create file '" + fileNameAndExtension + "' #1");
                return false;
            }
            outputStream = context.getContentResolver().openOutputStream(fileUri);
            if (!content.isEmpty()) {
                outputStream.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            outputStream.flush();
            Log.v(TAG, "safCreateFile: Created file '" + fileNameAndExtension + "', type '" + fileMimeType + "'");
            failSuccess = true;
        } catch (Exception e) {
            Log.e(TAG, "safCreateFile: Failed to create file '" + fileNameAndExtension + "' #2", e);
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "safCreateFile: Failed to create file '" + fileNameAndExtension + "' #3", e);
            }
        }
        return failSuccess;
    }

    /**
     * Open file in compatible app.
     */
    public static void openFile(final Context context, String fullPathAndFilename) {
        File file = new File(fullPathAndFilename);
        if (!file.isFile()) {
            // E.g. a recent change to a file deleted since.
            Toast.makeText(context, R.string.open_file_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        // MimeTypeMap.getFileExtensionFromUrl returns "" as soon as the name holds a space or an accent.
        String fileName = file.getName();
        int dotIndex = fileName.lastIndexOf('.');
        String fileExtension = (dotIndex >= 0) ? fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT) : "";
        String mimeType = FileUtils.getMimeTypeFromFileExtension(fileExtension);
        if (mimeType.isEmpty()) {
            mimeType = "*/*";
        }
        Log.v(TAG, "openFile: Detected mime type \'" + mimeType + "\' for file \'" + fullPathAndFilename + "\'");
        Intent intent;
        switch(fileExtension) {
            case "apk":
                // Requires permission in AndroidManifest.xml
                // We've dropped this as this is rarely used, mainly during development.
                // <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
                intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                break;
            default:
                intent = new Intent(Intent.ACTION_VIEW);
        }
        Uri fileUri;
        try {
            fileUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
        } catch (IllegalArgumentException e) {
            // The file is outside the roots declared in res/xml/file_paths.xml.
            Log.w(TAG, "openFile: No content URI for '" + fullPathAndFilename + "'", e);
            Toast.makeText(context, R.string.open_file_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        intent.setDataAndType(fileUri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException anfe) {
            Log.w(TAG, "openFile: ActivityNotFoundException. Falling back to app chooser...");
            // Keep the content URI and its read grant, only widen the type.
            intent.setDataAndType(fileUri, "*/*");
            Intent chooserIntent = Intent.createChooser(intent, context.getString(R.string.open_file_with));
            try {
                context.startActivity(chooserIntent);
            } catch (Exception ex) {
                Log.e(TAG, "openFile:", ex);
                Toast.makeText(context, R.string.open_file_no_compatible_app, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Open folder in compatible file manager app.
     */
    public static void openFolder(final Context context, String folderPath) {
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            Toast.makeText(
                    context,
                    context.getString(R.string.state_error_message, "Invalid folder path"),
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        Uri folderUri = Uri.fromFile(folder);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(folderUri, "resource/folder");
        intent.putExtra("org.openintents.extra.ABSOLUTE_PATH", folderPath);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        try {
            // Launch file manager.
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "openFolder: No compatible file manager app not found or has insufficient permissions (stage #1)", e);
            // fallback to older mime type
            try {
                String documentId = getDocumentIdFromPath(folderPath);
                Uri documentUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId);
                intent.setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR);
                context.startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException e2) {
                Log.e(TAG, "openFolder: No compatible file manager app not found or has insufficient permissions (stage #2)", e2);
                // No compatible file manager app found.
                suggestFileManagerApp(context);
            }
        }
    }

    private static void suggestFileManagerApp(final Context context) {
        AlertDialog mSuggestFileManagerAppDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.suggest_file_manager_app_dialog_title)
                .setMessage(R.string.suggest_file_manager_app_dialog_text)
                .setPositiveButton(R.string.yes, (d, i) -> {
                    final String appPackageName = "me.zhanghai.android.files";
                    try {
                        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + appPackageName)));
                    } catch (android.content.ActivityNotFoundException anfe) {
                        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + appPackageName)));
                    }
                })
                .setNegativeButton(R.string.no, (d, i) -> {})
                .show();
    }

    /**
     * Converts a raw file path into a Storage Access Framework Document ID,
     * including support for removable SD cards and USB OTG drives.
     */
    private static String getDocumentIdFromPath(String fullPath) {
        if (fullPath == null || fullPath.isEmpty()) {
            return null;
        }

        // Clean up trailing slashes for consistent parsing
        if (fullPath.endsWith("/") && fullPath.length() > 1) {
            fullPath = fullPath.substring(0, fullPath.length() - 1);
        }

        final String INTERNAL_STORAGE_PATH = "/storage/emulated/0";
        @SuppressLint("SdCardPath")
        final String SDCARD_PATH = "/sdcard";
        final String STORAGE_PREFIX = "/storage/";
        final String PRIMARY_AUTHORITY_PREFIX = "primary:";

        // 1. Handle Internal Storage
        if (fullPath.equals(INTERNAL_STORAGE_PATH) || fullPath.equals(SDCARD_PATH)) {
            return PRIMARY_AUTHORITY_PREFIX; // Root of internal storage
        } else if (fullPath.startsWith(INTERNAL_STORAGE_PATH + "/")) {
            String relativePath = fullPath.substring(INTERNAL_STORAGE_PATH.length() + 1);
            return PRIMARY_AUTHORITY_PREFIX + relativePath;
        } else if (fullPath.startsWith(SDCARD_PATH + "/")) {
            String relativePath = fullPath.substring(SDCARD_PATH.length() + 1);
            return PRIMARY_AUTHORITY_PREFIX + relativePath;
        }
        // 2. Handle Removable SD Cards and USB Drives
        else if (fullPath.startsWith(STORAGE_PREFIX)) {
            // Path looks something like: /storage/1A2B-3C4D/books/sync
            String withoutStorage = fullPath.substring(STORAGE_PREFIX.length()); // "1A2B-3C4D/books/sync"
            int firstSlashIndex = withoutStorage.indexOf('/');

            if (firstSlashIndex != -1) {
                // Extract the UUID and the relative path
                String uuid = withoutStorage.substring(0, firstSlashIndex); // "1A2B-3C4D"
                String relativePath = withoutStorage.substring(firstSlashIndex + 1); // "books/sync"
                return uuid + ":" + relativePath;
            } else {
                // It's the absolute root of the SD card: /storage/1A2B-3C4D
                return withoutStorage + ":";
            }
        }

        // Path does not match known external storage patterns
        return null;
    }
}
