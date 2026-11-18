package com.example.marsphotos.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun CameraScreen(
    locationId: Int,
    locationName: String,
    onPhotoCaptured: (filePath: String, latitude: Double, longitude: Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCapture = remember { ImageCapture.Builder().build() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    var locationGranted by remember { mutableStateOf(context.hasLocationPermission()) }

    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        cameraGranted = permissions[Manifest.permission.CAMERA] ?: context.hasPermission(Manifest.permission.CAMERA)
        locationGranted = (permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) ||
                (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false) ||
                context.hasLocationPermission()
    }

    val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    LaunchedEffect(Unit) {
        cameraGranted = context.hasPermission(Manifest.permission.CAMERA)
        locationGranted = context.hasLocationPermission()
        if (!cameraGranted || !locationGranted) {
            permissionRequest.launch(requiredPermissions)
        }
    }

    LaunchedEffect(cameraGranted) {
        if (cameraGranted) {
            try {
                val provider = context.awaitCameraProvider()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                cameraProvider = provider
            } catch (e: Exception) {
                Log.e("CameraScreen", "Failed to bind camera use cases", e)
            }
        }
    }

    DisposableEffect(cameraProvider) {
        onDispose { cameraProvider?.unbindAll() }
    }

    fun takePhoto() {
        if (isCapturing) return
        isCapturing = true

        val directory = File(context.filesDir, "location_photos").apply { mkdirs() }
        val file = File(directory, "location_${locationId}_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (context.hasLocationPermission()) {
                        fetchLocation(fusedLocationClient) { location ->
                            isCapturing = false
                            val lat = location?.latitude ?: 0.0
                            val lon = location?.longitude ?: 0.0
                            if (location == null) {
                                Toast.makeText(context, "Location unavailable, saved photo without GPS", Toast.LENGTH_SHORT).show()
                            }
                            onPhotoCaptured(file.absolutePath, lat, lon)
                        }
                    } else {
                        isCapturing = false
                        Toast.makeText(context, "Saved photo without GPS coordinates", Toast.LENGTH_SHORT).show()
                        onPhotoCaptured(file.absolutePath, 0.0, 0.0)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    file.delete()
                    Log.e("CameraScreen", "Error taking photo", exception)
                    Toast.makeText(context, "Could not take photo: ${exception.message ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        if (cameraGranted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!cameraGranted) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission is needed to take a photo of this location.",
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionRequest.launch(requiredPermissions) }) {
                    Text("Grant permissions")
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = locationName,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = onClose) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { takePhoto() },
                        enabled = cameraGranted && !isCapturing
                    ) {
                        Text(if (isCapturing) "Saving..." else "Take Photo")
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun fetchLocation(client: FusedLocationProviderClient, onResult: (Location?) -> Unit) {
    val cts = CancellationTokenSource()
    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
        .addOnSuccessListener { current ->
            if (current != null) {
                onResult(current)
            } else {
                client.lastLocation
                    .addOnSuccessListener { last -> onResult(last) }
                    .addOnFailureListener { onResult(null) }
            }
        }
        .addOnFailureListener {
            client.lastLocation
                .addOnSuccessListener { last -> onResult(last) }
                .addOnFailureListener { onResult(null) }
        }
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { continuation.resume(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasLocationPermission(): Boolean =
    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
