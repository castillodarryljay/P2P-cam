package com.example.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Recording
import com.example.data.RecordingRepository
import com.example.p2p.NetworkUtils
import com.example.p2p.P2PReceiver
import com.example.p2p.P2PTransmitter
import com.example.p2p.TransmitterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

data class CameraOption(
    val hash: Int,
    val name: String,
    val isBack: Boolean,
    val isUltraWide: Boolean = false,
    val cameraId: String = "0",
    val physicalCameraId: String? = null
)

class StreamViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "StreamViewModel"

    private val db = AppDatabase.getDatabase(application)
    private val repository = RecordingRepository(db.recordingDao())

    // Transmitter & Receiver Instances (Streamlining transmitter to the Foreground service instance)
    val transmitter = TransmitterService.transmitterInstance
    val receiver = P2PReceiver(repository, application.filesDir)

    // Navigation State
    enum class Screen {
        Home,
        TransmitterMode,
        ReceiverMode,
        Dashboard
    }

    private val _currentScreen = MutableStateFlow(Screen.Home)
    val currentScreen = _currentScreen.asStateFlow()

    // Transmitter configurations (Quality rate: 30 - 90)
    private val _streamQuality = MutableStateFlow(60)
    val streamQuality = _streamQuality.asStateFlow()

    // Resolution: "1080p", "720p", "480p", "360p"
    private val _streamResolution = MutableStateFlow("720p")
    val streamResolution = _streamResolution.asStateFlow()

    // Camera Selector options
    private val _availableCameras = MutableStateFlow<List<CameraOption>>(emptyList())
    val availableCameras = _availableCameras.asStateFlow()

    private val _selectedCamera = MutableStateFlow<CameraOption?>(null)
    val selectedCamera = _selectedCamera.asStateFlow()

    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera = _useFrontCamera.asStateFlow()

    // Receiver connections configurations
    private val _targetIp = MutableStateFlow("")
    val targetIp = _targetIp.asStateFlow()

    // List of active local network ips
    private val _localNetworkInterfaces = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val localNetworkInterfaces = _localNetworkInterfaces.asStateFlow()

    // Saved recordings flow
    val savedRecordings: StateFlow<List<Recording>> = repository.allRecordings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _discoveredCameras = MutableStateFlow<List<String>>(emptyList())
    val discoveredCameras = _discoveredCameras.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus = _exportStatus.asStateFlow()

    fun clearExportStatus() {
        _exportStatus.value = null
    }

    init {
        // Pre-populate target IP address with the default subnet if available
        refreshLocalIps()
        loadAvailableCameras()
        val defaultIp = NetworkUtils.getLocalIpAddress()
        if (defaultIp != null) {
            // Hotspot clients usually have IPs in the range 192.168.43.1 to 192.168.43.254,
            // or 192.168.49.x, or similar. Standard hotspot base is 192.168.43.1.
            if (defaultIp == "192.168.43.1") {
                // If this phone is NOT the hotspot, it might be connected.
                _targetIp.value = "192.168.43.1"
            } else {
                val ipPrefix = defaultIp.substringBeforeLast(".")
                _targetIp.value = "$ipPrefix."
            }
        } else {
            _targetIp.value = "192.168.43.1"
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun loadAvailableCameras() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val context = getApplication<Application>()
                val provider = ProcessCameraProvider.getInstance(context).get()
                val cameraManager = context.getSystemService(Application.CAMERA_SERVICE) as CameraManager
                val list = mutableListOf<CameraOption>()
                var backCount = 0
                var frontCount = 0

                for (info in provider.availableCameraInfos) {
                    val lensFacing = info.lensFacing
                    val isBack = lensFacing == CameraSelector.LENS_FACING_BACK
                    val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
                    val logicalCameraId = Camera2CameraInfo.from(info).cameraId
                    val characteristics = cameraManager.getCameraCharacteristics(logicalCameraId)

                    var name = ""
                    var isUltraWide = false

                    try {
                        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focalLength = focalLengths?.firstOrNull() ?: 4.0f

                        if (isBack) {
                            backCount++
                            if (focalLength < 3.0f) {
                                name = "Back Ultra-Wide Lens (0.5x)"
                                isUltraWide = true
                            } else if (focalLength > 6.0f) {
                                name = "Back Telephoto Lens"
                            } else {
                                name = "Back Standard Lens (1.0x)"
                            }
                            if (backCount > 1 && !name.contains("Ultra-Wide") && !name.contains("Telephoto")) {
                                name += " #$backCount"
                            }
                        } else if (isFront) {
                            frontCount++
                            name = "Front Lens"
                            if (frontCount > 1) {
                                name += " #$frontCount"
                            }
                        } else {
                            name = "External Lens"
                        }
                    } catch (e: Exception) {
                        if (isBack) {
                            backCount++
                            name = "Back Lens #$backCount"
                        } else if (isFront) {
                            frontCount++
                            name = "Front Lens #$frontCount"
                        } else {
                            name = "External Lens"
                        }
                    }

                    list.add(
                        CameraOption(
                            hash = info.hashCode(),
                            name = name,
                            isBack = isBack,
                            isUltraWide = isUltraWide,
                            cameraId = logicalCameraId,
                            physicalCameraId = null
                        )
                    )

                    // Also check for physical camera IDs inside this logical multi-camera (Android 9+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            val physicalIds = characteristics.physicalCameraIds
                            for (pId in physicalIds) {
                                val pChars = cameraManager.getCameraCharacteristics(pId)
                                val pFocalLengths = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                val pFocalLength = pFocalLengths?.firstOrNull() ?: 4.0f
                                var pName = ""
                                var pIsUltraWide = false

                                if (isBack) {
                                    if (pFocalLength < 3.0f) {
                                        pName = "Back Ultra-Wide Lens (0.5x)"
                                        pIsUltraWide = true
                                    } else if (pFocalLength > 6.0f) {
                                        pName = "Back Telephoto Lens"
                                    } else {
                                        continue // Skip standard physical lens since we have logical standard lens
                                    }
                                } else {
                                    continue
                                }

                                val physicalHash = (logicalCameraId + "_" + pId).hashCode()
                                list.add(
                                    CameraOption(
                                        hash = physicalHash,
                                        name = pName,
                                        isBack = isBack,
                                        isUltraWide = pIsUltraWide,
                                        cameraId = logicalCameraId,
                                        physicalCameraId = pId
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed parsing physical camera IDs for $logicalCameraId", e)
                        }
                    }
                }

                // Fallback to checking cameraIdList directly if ProcessCameraProvider was empty
                if (list.isEmpty()) {
                    for (id in cameraManager.cameraIdList) {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        val isBack = facing == CameraCharacteristics.LENS_FACING_BACK
                        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focalLength = focalLengths?.firstOrNull() ?: 4.0f
                        var isUltraWide = false
                        val name = if (isBack) {
                            if (focalLength < 3.0f) {
                                isUltraWide = true
                                "Back Ultra-Wide Lens (0.5x)"
                            } else "Back Lens ($id)"
                        } else "Front Lens ($id)"

                        list.add(
                            CameraOption(
                                hash = id.hashCode(),
                                name = name,
                                isBack = isBack,
                                isUltraWide = isUltraWide,
                                cameraId = id,
                                physicalCameraId = null
                            )
                        )
                    }
                }

                _availableCameras.value = list.distinctBy { it.name }
                // Select default back standard lens if available, else first back lens, else first available
                val defaultSelection = list.find { it.name.contains("Standard") } 
                    ?: list.firstOrNull { it.isBack } 
                    ?: list.firstOrNull()
                _selectedCamera.value = defaultSelection
            } catch (e: Exception) {
                Log.e(tag, "Failed loading available camera lenses", e)
            }
        }
    }

    fun selectCamera(camera: CameraOption) {
        _selectedCamera.value = camera
    }

    fun setStreamResolution(resolution: String) {
        _streamResolution.value = resolution
    }

    fun toggleWideAngle() {
        val cameras = _availableCameras.value
        val current = _selectedCamera.value ?: return
        if (current.isUltraWide) {
            // Switch back to standard back lens
            val standard = cameras.find { it.isBack && !it.isUltraWide }
            if (standard != null) {
                _selectedCamera.value = standard
            }
        } else {
            // Switch to ultra-wide lens
            val wide = cameras.find { it.isUltraWide }
            if (wide != null) {
                _selectedCamera.value = wide
            }
        }
    }

    fun startForegroundStreaming() {
        val context = getApplication<Application>()
        val camera = _selectedCamera.value
        val cameraHash = camera?.hash ?: -1
        val cameraId = camera?.cameraId
        val physicalCameraId = camera?.physicalCameraId
        val quality = _streamQuality.value
        val resolution = _streamResolution.value
        
        val intent = Intent(context, TransmitterService::class.java).apply {
            putExtra(TransmitterService.EXTRA_CAMERA_HASH, cameraHash)
            putExtra(TransmitterService.EXTRA_CAMERA_ID, cameraId)
            putExtra(TransmitterService.EXTRA_PHYSICAL_CAMERA_ID, physicalCameraId)
            putExtra(TransmitterService.EXTRA_QUALITY, quality)
            putExtra(TransmitterService.EXTRA_RESOLUTION, resolution)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopForegroundStreaming() {
        val context = getApplication<Application>()
        val intent = Intent(context, TransmitterService::class.java)
        context.stopService(intent)
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
        if (screen == Screen.TransmitterMode) {
            refreshLocalIps()
            loadAvailableCameras()
        }
    }

    fun setStreamQuality(quality: Int) {
        _streamQuality.value = quality.coerceIn(20, 95)
    }

    fun toggleCameraLens() {
        _useFrontCamera.value = !_useFrontCamera.value
    }

    fun updateTargetIp(ip: String) {
        _targetIp.value = ip
    }

    fun refreshLocalIps() {
        viewModelScope.launch {
            _localNetworkInterfaces.value = NetworkUtils.getAllLocalIpAddresses()
        }
    }

    fun startScanningForCameras() {
        if (_isScanning.value) return
        _isScanning.value = true
        _discoveredCameras.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            val found = mutableListOf<String>()
            val localIps = NetworkUtils.getAllLocalIpAddresses().map { it.second }
            for (localIp in localIps) {
                val ipPrefix = localIp.substringBeforeLast(".") + "."
                val jobs = (1..254).map { lastOctet ->
                    async {
                        val targetIp = "$ipPrefix$lastOctet"
                        try {
                            val socket = Socket()
                            socket.connect(InetSocketAddress(targetIp, 9002), 250) // 250ms timeout
                            socket.close()
                            synchronized(found) {
                                if (!found.contains(targetIp)) {
                                    found.add(targetIp)
                                }
                            }
                        } catch (_: Exception) {
                            // Offline or filtered
                        }
                    }
                }
                jobs.awaitAll()
            }
            _discoveredCameras.value = found
            _isScanning.value = false
        }
    }

    fun exportRecordingToGallery(recording: Recording) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(recording.filePath)
            if (!file.exists()) {
                _exportStatus.value = "Source file does not exist."
                return@launch
            }

            try {
                val resolver = context.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, recording.fileName)
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/LocalEye")
                        put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }

                val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val uri = resolver.insert(collectionUri, contentValues)
                if (uri == null) {
                    _exportStatus.value = "Failed to create gallery media entry."
                    return@launch
                }

                resolver.openOutputStream(uri).use { outputStream ->
                    if (outputStream == null) {
                        _exportStatus.value = "Failed to write output stream."
                        return@launch
                    }
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }

                _exportStatus.value = "Saved successfully to Movies/LocalEye!"
            } catch (e: Exception) {
                Log.e(tag, "Failed exporting video to gallery", e)
                _exportStatus.value = "Export failed: ${e.message}"
            }
        }
    }

    fun shareRecording(recording: Recording) {
        val context = getApplication<Application>()
        val file = File(recording.filePath)
        if (!file.exists()) return

        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Transfer / Share Video").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(tag, "Failed to share recording file", e)
        }
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                val file = File(recording.filePath)
                if (file.exists()) {
                    file.delete()
                }
                repository.delete(recording)
            } catch (e: Exception) {
                Log.e(tag, "Failed deleting recording files", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Do not stop transmitter server here as it resides securely in the foreground service background worker
        receiver.stop()
    }
}
