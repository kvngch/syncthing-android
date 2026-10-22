package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import java.util.concurrent.Executors
import kotlinx.coroutines.awaitCancellation

/**
 * Reads a device ID from a QR code with the back camera.
 */
class QRScannerActivity : ThemedAppCompatActivity() {

    /**
     * The analyzer keeps reading frames until the activity is gone: one result only.
     */
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ApplicationTheme {
                ScannerScreen(onCode = ::deliver, onCancel = ::finish)
            }
        }
    }

    private fun deliver(code: String) {
        if (delivered) {
            return
        }
        delivered = true
        setResult(RESULT_OK, Intent().putExtra(QR_RESULT_ARG, code))
        finish()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ScannerScreen(onCode: (String) -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current
        var granted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            )
        }
        // Saved: a second request while the system dialog is up, after a rotation, comes back at
        // once as a refusal. The first request delivers its result to the recreated activity.
        var requested by rememberSaveable { mutableStateOf(false) }
        val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) granted = true else onCancel()
        }
        LaunchedEffect(Unit) {
            if (!granted && !requested) {
                requested = true
                requestPermission.launch(Manifest.permission.CAMERA)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.scan_qr_code_description)) },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (granted) {
                    CameraPreview(onCode, onCancel)
                }
            }
        }
    }

    /**
     * Back camera preview, each frame read for a QR code off the main thread.
     */
    @Composable
    private fun CameraPreview(onCode: (String) -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
        val executor = remember { Executors.newSingleThreadExecutor() }
        DisposableEffect(executor) {
            onDispose { executor.shutdown() }
        }

        LaunchedEffect(lifecycleOwner) {
            val provider = ProcessCameraProvider.awaitInstance(context)
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider { surfaceRequest = it }
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(executor, QrCodeAnalyzer { code ->
                        ContextCompat.getMainExecutor(context).execute { onCode(code) }
                    })
                }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // No back camera, or it is in use.
                Log.e(TAG, "Failed to open the camera", e)
                Toast.makeText(context, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
                onCancel()
                return@LaunchedEffect
            }
            try {
                awaitCancellation()
            } finally {
                provider.unbindAll()
            }
        }

        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
    }

    /**
     * Reads QR codes from the luminance plane of the camera frames.
     */
    private class QrCodeAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }

        override fun analyze(image: ImageProxy) {
            image.use {
                val plane = it.planes[0]
                val data = ByteArray(plane.buffer.remaining()).also { bytes -> plane.buffer.get(bytes) }
                // The row stride can exceed the width: rows are read through it.
                val source = PlanarYUVLuminanceSource(
                    data, plane.rowStride, it.height, 0, 0, it.width, it.height, false
                )
                try {
                    onCode(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text)
                } catch (e: ReaderException) {
                    // No QR code in this frame.
                } finally {
                    reader.reset()
                }
            }
        }
    }

    companion object {
        private const val TAG = "QRScannerActivity"

        const val QR_RESULT_ARG = "QR_CODE"

        @JvmStatic
        fun intent(context: Context): Intent = Intent(context, QRScannerActivity::class.java)
    }
}
