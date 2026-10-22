package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.os.BundleCompat
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.PermissionUtil
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Syncthing Camera": takes a picture with the system camera app and keeps it in the folder
 * Syncthing shares for it. The first time, an introduction asks for consent and permissions.
 */
class PhotoShootActivity : ThemedAppCompatActivity() {

    private val sharedPreferences: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    /**
     * The file the camera app writes to, until its result comes back.
     */
    private var photoUri: Uri? = null

    /**
     * Null while the introduction is not shown: the camera opened straight away.
     */
    private var permissions by mutableStateOf<Permissions?>(null)

    private data class Permissions(val storage: Boolean, val camera: Boolean)

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { onPictureTaken() }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ApplicationTheme {
                permissions?.let { IntroScreen(it) }
            }
        }

        photoUri = savedInstanceState?.let { BundleCompat.getParcelable(it, STATE_PHOTO_URI, Uri::class.java) }
        if (photoUri != null) {
            // Recreated while the camera app was open: its result is on the way.
            if (savedInstanceState?.getBoolean(STATE_INTRO) == true) {
                refreshPermissions()
            }
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, R.string.photo_shoot_intro_no_camera, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Consent given before and permissions still there: straight to the camera.
        val consented = sharedPreferences.getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)
        if (consented && hasStoragePermission() && hasCameraPermission()) {
            openCamera()
            return
        }
        refreshPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Storage access is granted in the system settings: read it again on return.
        if (permissions != null) {
            refreshPermissions()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Survives the process being killed while the camera app is open.
        outState.putParcelable(STATE_PHOTO_URI, photoUri)
        outState.putBoolean(STATE_INTRO, permissions != null)
    }

    @Composable
    private fun IntroScreen(granted: Permissions) {
        Scaffold { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.photo_shoot_intro_welcome_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.photo_shoot_intro_permission_title), style = MaterialTheme.typography.titleMedium)
                if (!granted.storage) {
                    OutlinedButton(onClick = { PermissionUtil.requestStoragePermission(this@PhotoShootActivity, REQUEST_STORAGE) }) {
                        Text(stringResource(R.string.grant_storage_permission))
                    }
                }
                if (!granted.camera) {
                    OutlinedButton(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.grant_camera_permission))
                    }
                }
                Text(stringResource(R.string.photo_shoot_intro_permission_desc))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = ::finish) { Text(stringResource(R.string.back)) }
                    Button(onClick = ::onContinue) { Text(stringResource(R.string.cont)) }
                }
            }
        }
    }

    private fun onContinue() {
        if (!hasStoragePermission() || !hasCameraPermission()) {
            Toast.makeText(this, R.string.photo_shoot_intro_missing_permissions, Toast.LENGTH_LONG).show()
            return
        }
        // The consent that lets the next launches go straight to the camera.
        sharedPreferences.edit { putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true) }
        openCamera()
    }

    private fun openCamera() {
        val photoFile = try {
            createImageFile()
        } catch (e: IOException) {
            Log.e(TAG, "Error occurred while creating the temp image file", e)
            null
        } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
        photoUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No app handles ACTION_IMAGE_CAPTURE.", e)
            photoUri = null
            photoFile.delete()
            Toast.makeText(this, R.string.photo_shoot_no_camera_app, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * Judges by the file rather than by the result: some camera apps report it wrong.
     */
    private fun onPictureTaken() {
        val uri = photoUri ?: return
        photoUri = null
        if (isReadableImage(uri)) {
            Toast.makeText(this, R.string.photo_shoot_take_picture_success, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Log.d(TAG, "User cancelled or failed to take a picture.")
        try {
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Delete temporary file FAILED", e)
        }
        Toast.makeText(this, R.string.photo_shoot_take_picture_failure, Toast.LENGTH_SHORT).show()
        if (permissions == null) {
            // Came straight to the camera, without the introduction: nothing to show here.
            finish()
        }
    }

    /**
     * Reads the image header only: decoding a full-size photo costs memory and time, just to know
     * the camera app wrote a valid picture.
     */
    private fun isReadableImage(uri: Uri): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: IOException) {
            return false
        } catch (e: RuntimeException) {
            return false
        }
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun createImageFile(): File? {
        val storageDir = FileUtils.getExternalFilesDir(
            this, FileUtils.ExternalStorageDirType.INT_MEDIA, Environment.DIRECTORY_PICTURES
        ) ?: run {
            Log.e(TAG, "createImageFile: storageDir == null")
            return null
        }
        storageDir.mkdirs()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    private fun refreshPermissions() {
        permissions = Permissions(storage = hasStoragePermission(), camera = hasCameraPermission())
    }

    private fun hasStoragePermission() = PermissionUtil.haveStoragePermission(this)

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "PhotoShootActivity"
        private const val STATE_PHOTO_URI = "lastPhotoURI"
        private const val STATE_INTRO = "intro"
        private const val REQUEST_STORAGE = 142
    }
}
