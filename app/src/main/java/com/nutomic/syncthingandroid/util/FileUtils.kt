package com.nutomic.syncthingandroid.util

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.nutomic.syncthingandroid.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/**
 * Files, folders and Storage Access Framework URIs.
 */
object FileUtils {
    private const val TAG = "FileUtils"

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_PROVIDER_AUTHORITY = "com.android.providers.downloads.documents"
    private const val RAW_DOCUMENT_ID_PREFIX = "raw:"
    private const val DOWNLOADS_VOLUME_NAME = "downloads"
    private const val PRIMARY_VOLUME_NAME = "primary"
    private const val HOME_VOLUME_NAME = "home"

    enum class ExternalStorageDirType {
        DATA,
        EXT_MEDIA,
        INT_MEDIA,
    }

    /**
     * Whether the folder of a document URI exists on a local storage.
     */
    @JvmStatic
    fun directoryUriExists(context: Context, documentUri: Uri): Boolean {
        // IN: content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2F${applicationId}
        // OUT: content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia%2F${applicationId}
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            documentUri.authority,
            DocumentsContract.getDocumentId(documentUri),
        )
        val absPath = getAbsolutePathFromSAFUri(context, treeUri)
        return absPath != null && File(absPath).exists()
    }

    /**
     * The absolute path of a folder picked through the Storage Access Framework, or null if it is
     * not on a local storage.
     */
    @JvmStatic
    fun getAbsolutePathFromSAFUri(context: Context, safResultUri: Uri?): String? {
        if (safResultUri == null) {
            Log.w(TAG, "getAbsolutePathFromSAFUri: called with safResultUri == null")
            return null
        }
        val treeUri = DocumentsContract.buildDocumentUriUsingTree(
            safResultUri,
            DocumentsContract.getTreeDocumentId(safResultUri),
        )
        return getAbsolutePathFromTreeUri(context, treeUri)
    }

    private fun getAbsolutePathFromTreeUri(context: Context, treeUri: Uri): String? {
        // Only the external storage provider has document ids of the form "[volume]:[path]".
        // The Downloads provider gives "raw:[absolute path]" for a folder, or opaque ids such
        // as "msd:123"; cloud providers have no local path at all.
        val authority = treeUri.authority
        if (authority == DOWNLOADS_PROVIDER_AUTHORITY) {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            if (treeDocumentId.startsWith(RAW_DOCUMENT_ID_PREFIX)) {
                return cutTrailingSlash(treeDocumentId.removePrefix(RAW_DOCUMENT_ID_PREFIX))
            }
        }
        if (authority != EXTERNAL_STORAGE_AUTHORITY) {
            Log.w(TAG, "getAbsolutePathFromTreeUri: No local path for provider $authority")
            return null
        }
        // The document id is "[volume]:[path]"; a folder name may hold ":" too.
        val documentId = DocumentsContract.getTreeDocumentId(treeUri).split(':', limit = 2)
        val volumePath = getVolumePath(documentId[0], context).removeSuffix(File.separator)
        val documentPath = documentId.getOrElse(1) { File.separator }.removeSuffix(File.separator)
        return when {
            documentPath.isEmpty() -> volumePath
            documentPath.startsWith(File.separator) -> volumePath + documentPath
            else -> volumePath + File.separator + documentPath
        }
    }

    /**
     * Mount points of the removable storages, from /proc/mounts: those the app may read without
     * the Storage Access Framework.
     */
    @JvmStatic
    fun getMountedStoragePathsAsFileArray(): Array<File> =
        getMountedStoragePaths().map(::File).filter { it.canRead() }.toTypedArray()

    private fun getMountedStoragePaths(): List<String> =
        try {
            File("/proc/mounts").readLines()
                .mapNotNull { it.split(' ').getOrNull(1) }
                .filter {
                    (it.startsWith("/storage/") || it.startsWith("/mnt/media_rw/")) &&
                        "emulated" !in it &&
                        File(it).isDirectory
                }
        } catch (e: IOException) {
            Log.w(TAG, "getMountedStoragePaths: Error reading /proc/mounts", e)
            emptyList()
        }

    /**
     * The mount point of a storage volume, by its id in a document URI.
     */
    @Suppress("DEPRECATION")
    private fun getVolumePath(volumeId: String, context: Context): String {
        when (volumeId) {
            // Reading the environment var avoids hard coding the case of the "documents" folder.
            HOME_VOLUME_NAME ->
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
            DOWNLOADS_VOLUME_NAME -> return getExternalStorageDownloadsDirectory()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(StorageManager::class.java)
                ?.storageVolumes
                ?.firstOrNull { (it.isPrimary && volumeId == PRIMARY_VOLUME_NAME) || it.uuid == volumeId }
                ?.directory
                ?.let { return it.absolutePath }
        }
        // Where Android mounts the volumes: the primary one at the external storage directory,
        // the others at /storage/[uuid].
        return if (volumeId == PRIMARY_VOLUME_NAME) getInternalStorageRootAbsolutePath() else "/storage/$volumeId"
    }

    @JvmStatic
    fun getExternalFilesDir(context: Context, type: String?): File? =
        getExternalFilesDir(context, ExternalStorageDirType.DATA, type)

    /**
     * The app's private folder on the external storage, if present: e.g.
     * "/storage/abcd-efgh/Android/data/[PACKAGE_NAME]/files" or
     * "/storage/abcd-efgh/Android/media/[PACKAGE_NAME]". [type] "Pictures" points to the
     * subfolder of the media folders.
     */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun getExternalFilesDir(context: Context, extDirType: ExternalStorageDirType, type: String?): File? {
        val dirs = when (extDirType) {
            ExternalStorageDirType.DATA -> {
                // ContextCompat: some Huawei devices running Android 7 returned the wrong path.
                // See https://stackoverflow.com/questions/39895579/fileprovider-error-onhuawei-devices
                val all = ContextCompat.getExternalFilesDirs(context, null).toList()
                if (all.size > 1) all - context.getExternalFilesDir(null) else all
            }
            ExternalStorageDirType.INT_MEDIA ->
                listOf(File(Environment.getExternalStorageDirectory(), "Android/media/" + context.packageName))
            ExternalStorageDirType.EXT_MEDIA -> context.externalMediaDirs.toList().drop(1)
        }.filterNotNull() // null for an ejected SD card.
        val dir = dirs.firstOrNull()
        if (dir == null) {
            Log.w(TAG, "Could not determine app's private files directory on external storage.")
            return null
        }
        val isMedia = extDirType == ExternalStorageDirType.EXT_MEDIA || extDirType == ExternalStorageDirType.INT_MEDIA
        return if (isMedia && type == Environment.DIRECTORY_PICTURES) File(dir, Environment.DIRECTORY_PICTURES) else dir
    }

    /**
     * The document URI of the app's private folder on the external storage, to open the system
     * folder picker there. FileProvider cannot build it and "file://" URIs are blocked, so it is
     * built by hand.
     */
    @JvmStatic
    fun getExternalFilesDirUri(context: Context, extDirType: ExternalStorageDirType): Uri? {
        val externalFilesDir = getExternalFilesDir(context, extDirType, null) ?: return null
        // The volume id, e.g. "abcd-efgh" in "/storage/abcd-efgh/Android/data/...".
        val volumeId = externalFilesDir.absolutePath.split('/').getOrNull(2)
        if (volumeId == null) {
            Log.w(TAG, "Could not extract volumeId from app's private files path '$externalFilesDir'")
            return null
        }
        val packageName = context.packageName
        val documentId = when (extDirType) {
            ExternalStorageDirType.DATA -> "$volumeId%3AAndroid%2Fdata%2F$packageName%2Ffiles"
            ExternalStorageDirType.EXT_MEDIA -> "$volumeId%3AAndroid%2Fmedia%2F$packageName"
            ExternalStorageDirType.INT_MEDIA -> "primary%3AAndroid%2Fmedia%2F$packageName"
        }
        return "content://$EXTERNAL_STORAGE_AUTHORITY/document/$documentId".toUri()
    }

    /**
     * The document URI of the internal storage root, built by hand as FileProvider cannot.
     */
    @JvmStatic
    fun getInternalStorageRootUri(): Uri = "content://$EXTERNAL_STORAGE_AUTHORITY/document/primary%3A".toUri()

    /**
     * Reading the environment var avoids hard coding the absolute path of the "/Download" folder.
     */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun getExternalStorageDownloadsDirectory(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    @JvmStatic
    fun cutTrailingSlash(path: String): String = path.removeSuffix(File.separator)

    /**
     * Deletes a file, or a directory and everything in it. A symbolic link is deleted, not
     * followed: an extracted archive may hold one to a directory outside.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun deleteDirectoryRecursively(dir: File?): Boolean {
        if (dir == null) {
            return false
        }
        if (Files.isSymbolicLink(dir.toPath())) {
            return dir.delete()
        }
        if (!dir.exists()) {
            return false
        }
        if (dir.isFile) {
            return dir.delete()
        }
        dir.listFiles()?.forEach { deleteDirectoryRecursively(it) }
        return dir.delete()
    }

    /**
     * Writes data to "[target].tmp", syncs it to disk, then renames it over target.
     * On any failure the target is left untouched and an IOException is thrown.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun writeBytesAtomic(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(data)
                it.flush()
                it.fd.sync()
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Failed to rename ${tmp.name} to ${target.name}")
        }
    }

    /**
     * Expands the "~" path, as SyncthingRunnable sets "HOME". E.g. /storage/emulated/0/syncthing
     */
    @JvmStatic
    fun getSyncthingTildeAbsolutePath(): String = getInternalStorageRootAbsolutePath() + "/syncthing"

    @Suppress("DEPRECATION")
    private fun getInternalStorageRootAbsolutePath(): String = Environment.getExternalStorageDirectory().absolutePath

    /**
     * MIME type of a file extension, "" if unknown.
     */
    private fun getMimeTypeFromFileExtension(fileExtension: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.lowercase(Locale.ROOT)).orEmpty()

    /**
     * The subfolder [folderName] of [parentFolder], created if missing; null if it cannot be.
     */
    @JvmStatic
    fun safCreateDirectory(parentFolder: DocumentFile?, folderName: String): DocumentFile? {
        if (parentFolder == null) {
            Log.w(TAG, "safCreateDirectory: parentFolder == null")
            return null
        }
        parentFolder.listFiles().firstOrNull { it.isDirectory && it.name == folderName }?.let {
            Log.v(TAG, "safCreateDirectory: Directory already exists '$folderName'")
            return it
        }
        val created = parentFolder.createDirectory(folderName)
        if (created == null) {
            Log.w(TAG, "safCreateDirectory: Failed to create directory '$folderName'")
            return null
        }
        Log.v(TAG, "safCreateDirectory: Created directory '$folderName'")
        return created
    }

    /**
     * Creates [fileNameAndExtension] in [parentFolder] with [content], unless it exists. True if
     * the file is there afterwards.
     */
    @JvmStatic
    fun safCreateFile(context: Context, parentFolder: DocumentFile, fileNameAndExtension: String, content: String): Boolean {
        if (parentFolder.listFiles().any { it.isFile && it.name == fileNameAndExtension }) {
            Log.v(TAG, "safCreateFile: File already exists '$fileNameAndExtension'")
            return true
        }
        val fileMimeType = getMimeTypeFromFileExtension(MimeTypeMap.getFileExtensionFromUrl(fileNameAndExtension))
        val dotIndex = fileNameAndExtension.lastIndexOf('.')
        val fileName = if (dotIndex > 0) fileNameAndExtension.substring(0, dotIndex) else fileNameAndExtension
        return try {
            val fileUri = DocumentsContract.createDocument(context.contentResolver, parentFolder.uri, fileMimeType, fileName)
            if (fileUri == null) {
                Log.e(TAG, "safCreateFile: Failed to create file '$fileNameAndExtension' #1")
                return false
            }
            val output = context.contentResolver.openOutputStream(fileUri) ?: throw IOException("No output stream for $fileUri")
            output.use {
                if (content.isNotEmpty()) {
                    it.write(content.toByteArray(Charsets.ISO_8859_1))
                }
                it.flush()
            }
            Log.v(TAG, "safCreateFile: Created file '$fileNameAndExtension', type '$fileMimeType'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "safCreateFile: Failed to create file '$fileNameAndExtension' #2", e)
            false
        }
    }

    /**
     * Opens a file in a compatible app.
     */
    @JvmStatic
    fun openFile(context: Context, fullPathAndFilename: String) {
        val file = File(fullPathAndFilename)
        if (!file.isFile) {
            // E.g. a recent change to a file deleted since.
            Toast.makeText(context, R.string.open_file_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        // MimeTypeMap.getFileExtensionFromUrl returns "" as soon as the name holds a space or an accent.
        val fileExtension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mimeType = getMimeTypeFromFileExtension(fileExtension).ifEmpty { "*/*" }
        Log.v(TAG, "openFile: Detected mime type '$mimeType' for file '$fullPathAndFilename'")
        // Installing an APK requires REQUEST_INSTALL_PACKAGES, which the app does not ask for.
        @Suppress("DEPRECATION")
        val intent = Intent(if (fileExtension == "apk") Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW)
        val fileUri = try {
            FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        } catch (e: IllegalArgumentException) {
            // The file is outside the roots declared in res/xml/file_paths.xml.
            Log.w(TAG, "openFile: No content URI for '$fullPathAndFilename'", e)
            Toast.makeText(context, R.string.open_file_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        intent.setDataAndType(fileUri, mimeType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "openFile: ActivityNotFoundException. Falling back to app chooser...")
            // Keep the content URI and its read grant, only widen the type.
            intent.setDataAndType(fileUri, "*/*")
            try {
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_file_with)))
            } catch (ex: Exception) {
                Log.e(TAG, "openFile:", ex)
                Toast.makeText(context, R.string.open_file_no_compatible_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Opens a folder in a compatible file manager app. The "file://" URI is allowed by the VM
     * policy SyncthingApp sets. False if no app can open folders: see [SuggestFileManagerDialog].
     */
    @JvmStatic
    fun openFolder(context: Context, folderPath: String?): Boolean {
        if (folderPath.isNullOrEmpty() || !File(folderPath).isDirectory) {
            Toast.makeText(
                context,
                context.getString(R.string.state_error_message, "Invalid folder path"),
                Toast.LENGTH_LONG,
            ).show()
            return true
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(File(folderPath)), "resource/folder")
            .putExtra("org.openintents.extra.ABSOLUTE_PATH", folderPath)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        try {
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "openFolder: No compatible file manager app found (stage #1)", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "openFolder: File manager app has insufficient permissions (stage #1)", e)
        }
        // Fall back to the directory MIME type of the documents provider.
        val documentId = getDocumentIdFromPath(folderPath)
        if (documentId != null) {
            intent.setDataAndType(
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId),
                DocumentsContract.Document.MIME_TYPE_DIR,
            )
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "openFolder: No compatible file manager app found (stage #2)", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "openFolder: File manager app has insufficient permissions (stage #2)", e)
            }
        }
        return false
    }

    /**
     * Converts a raw file path into a Storage Access Framework document id, including support
     * for removable SD cards and USB OTG drives. Null if the path is on no known storage.
     */
    @SuppressLint("SdCardPath")
    private fun getDocumentIdFromPath(path: String): String? {
        // Clean up a trailing slash for consistent parsing.
        val fullPath = if (path.length > 1) path.removeSuffix("/") else path
        val internalStoragePath = "/storage/emulated/0"
        val sdcardPath = "/sdcard"
        val storagePrefix = "/storage/"
        val primaryPrefix = "primary:"
        return when {
            // Internal storage.
            fullPath == internalStoragePath || fullPath == sdcardPath -> primaryPrefix
            fullPath.startsWith("$internalStoragePath/") -> primaryPrefix + fullPath.removePrefix("$internalStoragePath/")
            fullPath.startsWith("$sdcardPath/") -> primaryPrefix + fullPath.removePrefix("$sdcardPath/")
            // Removable SD cards and USB drives, e.g. /storage/1A2B-3C4D/books/sync.
            fullPath.startsWith(storagePrefix) -> {
                val withoutStorage = fullPath.removePrefix(storagePrefix)
                if ('/' in withoutStorage) {
                    withoutStorage.substringBefore('/') + ":" + withoutStorage.substringAfter('/')
                } else {
                    // The root of the SD card: /storage/1A2B-3C4D
                    "$withoutStorage:"
                }
            }
            else -> null
        }
    }
}

/**
 * Offers to install a file manager, after [FileUtils.openFolder] found no app to open a folder.
 */
@Composable
fun SuggestFileManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.suggest_file_manager_app_dialog_title)) },
        text = { Text(stringResource(R.string.suggest_file_manager_app_dialog_text)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    val appPackageName = "me.zhanghai.android.files"
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$appPackageName".toUri()))
                    } catch (e: ActivityNotFoundException) {
                        context.openUrl("https://play.google.com/store/apps/details?id=$appPackageName")
                    }
                },
            ) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.no))
            }
        },
    )
}
