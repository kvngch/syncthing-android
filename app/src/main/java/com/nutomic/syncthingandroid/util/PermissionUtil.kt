package com.nutomic.syncthingandroid.util

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.nutomic.syncthingandroid.R

object PermissionUtil {
    private const val TAG = "PermissionUtil"

    /**
     * Write access to the shared storage: the storage permission up to Android 10, all files
     * access from Android 11.
     */
    @JvmStatic
    fun haveStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        return Environment.isExternalStorageManager()
    }

    /**
     * Asks for the storage permission, or opens the system screen granting all files access.
     */
    @JvmStatic
    fun requestStoragePermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), requestCode)
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData("package:${activity.packageName}".toUri())
        try {
            if (intent.resolveActivity(activity.packageManager) != null) {
                // Launch "Allow all files access?" dialog.
                activity.startActivity(intent)
                return
            }
            Log.w(TAG, "Request all files access not supported")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Request all files access not supported", e)
        }
        // Some devices don't support this request.
        Toast.makeText(activity, R.string.dialog_all_files_access_not_supported, Toast.LENGTH_LONG).show()
    }
}
