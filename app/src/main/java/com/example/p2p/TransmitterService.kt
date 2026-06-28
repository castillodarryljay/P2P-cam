package com.example.p2p

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TransmitterService : Service(), LifecycleOwner {
    private val tag = "TransmitterService"

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Coroutine scope for service operations
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.i(tag, "TransmitterService Created")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val cameraHash = intent?.getIntExtra(EXTRA_CAMERA_HASH, -1) ?: -1
        val cameraId = intent?.getStringExtra(EXTRA_CAMERA_ID)
        val physicalCameraId = intent?.getStringExtra(EXTRA_PHYSICAL_CAMERA_ID)
        val resolution = intent?.getStringExtra(EXTRA_RESOLUTION) ?: "720p"

        // Map resolution preset to JPEG compression quality dynamically if not specified
        val mappedQuality = when (resolution) {
            "1080p" -> 85
            "720p" -> 70
            "480p" -> 55
            "360p" -> 40
            else -> 70
        }

        // Create Channel & Start Foreground immediately
        createNotificationChannel()
        val notification = createNotification()
        
        var serviceType = 0
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                serviceType
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Acquire WakeLock to keep CPU alive when screen is off
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalEye::TransmitterBGWake").apply {
                acquire()
            }
            Log.i(tag, "Partial WakeLock Acquired")
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire wake lock", e)
        }

        // Launch transmitter server and bind selected CameraX lens
        startStreamingServerAndCamera(cameraHash, cameraId, physicalCameraId, mappedQuality, resolution)

        _isServiceRunning.value = true
        return START_STICKY
    }

    private fun startStreamingServerAndCamera(cameraHash: Int, cameraId: String?, physicalCameraId: String?, quality: Int, resolution: String) {
        serviceScope.launch {
            // Start transmitter socket server
            transmitterInstance.startServer()

            try {
                // Initialize CameraX background capture
                val provider = ProcessCameraProvider.getInstance(this@TransmitterService).get()
                cameraProvider = provider
                provider.unbindAll()

                // Find matching CameraInfo from ID or hash or default to back camera
                val availableInfos = provider.availableCameraInfos
                val selectedCameraInfo = if (cameraId != null) {
                    availableInfos.find { Camera2CameraInfo.from(it).cameraId == cameraId }
                } else {
                    availableInfos.find { it.hashCode() == cameraHash }
                } ?: availableInfos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }

                val cameraSelector = if (selectedCameraInfo != null) {
                    CameraSelector.Builder()
                        .addCameraFilter { list -> list.filter { it == selectedCameraInfo } }
                        .build()
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                // Expose current camera name and detail to the state flow
                selectedCameraInfo?.let { info ->
                    var label = "Back Camera"
                    try {
                        val focalLengths: FloatArray? = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focalLength = focalLengths?.firstOrNull() ?: 4.0f
                        val isBack = info.lensFacing == CameraSelector.LENS_FACING_BACK
                        if (isBack) {
                            label = if (focalLength < 3.0f) "Back Ultra-Wide Camera" else "Back Standard Camera"
                        } else {
                            label = "Front Camera"
                        }
                    } catch (_: Exception) {}
                    _activeCameraLabel.value = label
                }

                // Determine 16:9 aspect ratio resolution target
                val targetSize = when (resolution) {
                    "1080p" -> android.util.Size(1920, 1080)
                    "720p" -> android.util.Size(1280, 720)
                    "480p" -> android.util.Size(854, 480)
                    "360p" -> android.util.Size(640, 360)
                    else -> android.util.Size(1280, 720)
                }

                // Configure ImageAnalysis for streaming frames with physical lens interop if available
                val imageAnalysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(targetSize)

                if (physicalCameraId != null) {
                    androidx.camera.camera2.interop.Camera2Interop.Extender(imageAnalysisBuilder)
                        .setPhysicalCameraId(physicalCameraId)
                }

                val imageAnalysis = imageAnalysisBuilder.build()

                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    try {
                        // Deliver frame to connected viewer
                        val clientsConnected = transmitterInstance.clientsConnectedCount.value
                        if (clientsConnected > 0) {
                            var bitmap = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            if (rotation != 0) {
                                val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            }
                            transmitterInstance.sendVideoFrame(bitmap, quality)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error analyzing image frame", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                provider.bindToLifecycle(this@TransmitterService, cameraSelector, imageAnalysis)
                Log.i(tag, "CameraX background image analysis bound to TransmitterService")
            } catch (e: Exception) {
                Log.e(tag, "Failed to build CameraX background binder", e)
            }
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, TransmitterService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Active P2P Camera Stream")
            .setContentText("LocalEye is streaming camera feed & audio in background. Screen can be turned off safely.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Stream", pendingStopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Streaming Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification status when running active P2P camera server in background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.i(tag, "TransmitterService Stopping")
        _isServiceRunning.value = false
        _activeCameraLabel.value = ""

        // Release WakeLock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        // Stop transmitter socket server
        transmitterInstance.stopServer()

        // Unbind and release CameraX
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        cameraProvider = null

        cameraExecutor?.shutdown()
        cameraExecutor = null

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 817102
        const val CHANNEL_ID = "P2P_TRANSMITTER_CHANNEL"

        const val ACTION_STOP = "com.example.p2p.STOP_SERVICE"
        const val EXTRA_CAMERA_HASH = "extra_camera_hash"
        const val EXTRA_CAMERA_ID = "extra_camera_id"
        const val EXTRA_PHYSICAL_CAMERA_ID = "extra_physical_camera_id"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_RESOLUTION = "extra_resolution"

        // Static singleton reference for easier state observation from the compose UI
        val transmitterInstance = P2PTransmitter()

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private val _activeCameraLabel = MutableStateFlow("")
        val activeCameraLabel = _activeCameraLabel.asStateFlow()
    }
}
