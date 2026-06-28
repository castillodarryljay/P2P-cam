package com.example.ui

import android.graphics.Bitmap
import android.widget.MediaController
import android.util.Log
import android.widget.VideoView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyRow
import androidx.camera.camera2.interop.Camera2CameraInfo
import com.example.data.Recording
import com.example.p2p.P2PReceiver
import com.example.p2p.P2PTransmitter
import com.example.p2p.TransmitterService
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@Composable
fun StreamAppUi(viewModel: StreamViewModel) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0C0F14) // Deep Space Dark Slate
    ) {
        MainAppLayout(viewModel)
    }
}

@Composable
fun PermissionOnboarding(
    onRequestPermissions: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF161B22))
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back to Home",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Color(0xFF1E2633), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = "Camera Permission",
                tint = Color(0xFF00FFCC), // Neo Mint Green
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Camera & Mic Required",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "LocalEye requires Camera permission to stream video from your spare phone, and Microphone permission for two-way audio talkback.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = Color(0xFFA0AAB8),
                lineHeight = 20.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(36.dp))
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3344)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("request_permissions_button")
        ) {
            Text(
                text = "Grant Permissions",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }
    }
}

@Composable
fun MainAppLayout(viewModel: StreamViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
        },
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            StreamViewModel.Screen.Home -> HomeScreen(viewModel)
            StreamViewModel.Screen.TransmitterMode -> TransmitterScreen(viewModel)
            StreamViewModel.Screen.ReceiverMode -> ReceiverScreen(viewModel)
            StreamViewModel.Screen.Dashboard -> DashboardScreen(viewModel)
        }
    }
}

@Composable
fun HomeScreen(viewModel: StreamViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        // App Header
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0xFF161B22), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = "Logo",
                    tint = Color(0xFFFF3344),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "LocalEye P2P",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = "Remote Camera & Two-Way Audio",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8))
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Large Option Cards
        Text(
            text = "Select Device Mode",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White
            ),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // CARD Transmitter
        DeviceModeCard(
            title = "Camera / Transmitter",
            description = "Install on your spare phone. Turns this device into a wireless remote camera and microphone.",
            icon = Icons.Default.Videocam,
            accentColor = Color(0xFF00FFCC),
            onClick = { viewModel.navigateTo(StreamViewModel.Screen.TransmitterMode) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // CARD Receiver
        DeviceModeCard(
            title = "Viewer / Recorder",
            description = "Install on your main phone. Watch real-time stream, record video on-device, and interact with two-way audio.",
            icon = Icons.Default.Monitor,
            accentColor = Color(0xFFFF3344),
            onClick = { viewModel.navigateTo(StreamViewModel.Screen.ReceiverMode) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // CARD Dashboard
        DeviceModeCard(
            title = "Recordings Gallery",
            description = "View, play, and manage saved video records captured directly from remote cameras.",
            icon = Icons.Default.FolderZip,
            accentColor = Color(0xFFFFB703),
            onClick = { viewModel.navigateTo(StreamViewModel.Screen.Dashboard) }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun DeviceModeCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, Color(0xFF222B36), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(Color(0xFF1D2633), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFFA0AAB8),
                        lineHeight = 16.sp
                    )
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF53657D),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun TransmitterScreen(viewModel: StreamViewModel) {
    val permissionState = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    )

    if (permissionState.allPermissionsGranted) {
        TransmitterScreenContent(viewModel)
    } else {
        PermissionOnboarding(
            onRequestPermissions = { permissionState.launchMultiplePermissionRequest() },
            onBack = { viewModel.navigateTo(StreamViewModel.Screen.Home) }
        )
    }
}

@Composable
fun TransmitterScreenContent(viewModel: StreamViewModel) {
    val context = LocalContext.current
    val isServiceRunning by TransmitterService.isServiceRunning.collectAsStateWithLifecycle()
    val activeCameraLabel by TransmitterService.activeCameraLabel.collectAsStateWithLifecycle()
    val availableCameras by viewModel.availableCameras.collectAsStateWithLifecycle()
    val selectedCamera by viewModel.selectedCamera.collectAsStateWithLifecycle()
    val streamResolution by viewModel.streamResolution.collectAsStateWithLifecycle()
    val localIps by viewModel.localNetworkInterfaces.collectAsStateWithLifecycle()
    val clientsCount by viewModel.transmitter.clientsConnectedCount.collectAsStateWithLifecycle()
    val connectionState by viewModel.transmitter.connectionState.collectAsStateWithLifecycle()

    // Breathing neon state animation for active background stream
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_animation")
    val alphaPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // App bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.navigateTo(StreamViewModel.Screen.Home) },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF161B22)),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Go back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "P2P Stream Server",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }

        if (isServiceRunning) {
            // ============================================
            // ACTIVE SERVICE STATE (Running in Background)
            // ============================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Large Pulsing Status Indicator Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .border(
                            1.dp,
                            Color(0xFF00FFCC).copy(alpha = alphaPulse * 0.7f),
                            RoundedCornerShape(24.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101622)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(64.dp)
                                .background(
                                    Color(0xFF00FFCC).copy(alpha = 0.1f * alphaPulse),
                                    CircleShape
                                ),
                            shape = CircleShape,
                            color = Color.Transparent
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Canvas(modifier = Modifier.size(16.dp)) {
                                    drawCircle(color = Color(0xFF00FFCC))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "SCREEN-OFF STREAMING ACTIVE",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF00FFCC),
                                letterSpacing = 1.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Camera server is running securely as a Foreground Service. You can safely turn off the screen or leave this app. CPU will remain awake to complete the P2P feed.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFA0AAB8),
                                lineHeight = 18.sp
                            )
                        )
                    }
                }

                // Interactive connection details info panel
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFFFF3344), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Streaming Lens: ",
                                style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFA0AAB8))
                            )
                            Text(
                                activeCameraLabel.ifEmpty { "Default Lens" },
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF222B36))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.People, contentDescription = null, tint = Color(0xFFFF3344), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Connected Clients: ",
                                style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFA0AAB8))
                            )
                            Text(
                                if (clientsCount > 0) "1 Connected" else "0 (Awaiting connection...)",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (clientsCount > 0) Color(0xFF00FFCC) else Color(0xFFFFB703)
                                )
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF222B36))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.HighQuality, contentDescription = null, tint = Color(0xFFFF3344), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Streaming Resolution: ",
                                style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFA0AAB8))
                            )
                            Text(
                                streamResolution.uppercase(),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF222B36))

                        Text(
                            text = "Server IP Address (Enter this on Main phone):",
                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF53657D)),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        val qrIp = when (val state = connectionState) {
                            is P2PTransmitter.ConnectionState.Listening -> state.ipAddress
                            is P2PTransmitter.ConnectionState.Connected -> localIps.firstOrNull()?.second ?: ""
                            else -> localIps.firstOrNull()?.second ?: ""
                        }

                        when (val state = connectionState) {
                            is P2PTransmitter.ConnectionState.Listening -> {
                                Text(
                                    text = "${state.ipAddress}:${state.port}",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00FFCC),
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                            is P2PTransmitter.ConnectionState.Connected -> {
                                Text(
                                    text = "Connected Client: ${state.clientIp}",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF3344),
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                            else -> {
                                Text(
                                    text = localIps.firstOrNull()?.second?.let { "$it:9002" } ?: "Detecting Network IP...",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFB703),
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                        }

                        if (qrIp.isNotEmpty()) {
                            val qrBitmap = remember(qrIp) { com.example.p2p.QRUtils.generateQrCode(qrIp, 300) }
                            if (qrBitmap != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .background(Color.White, RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = "Connection QR Code",
                                        modifier = Modifier.size(160.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Scan QR with Main phone to connect",
                                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFA0AAB8)),
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // STOP SERVICE BUTTON
                Button(
                    onClick = { viewModel.stopForegroundStreaming() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE63946)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop Background Stream",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        } else {
            // ============================================
            // SETUP STATE (Service Inactive/Preview)
            // ============================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Configuration Heading instruction
                Text(
                    text = "Configure Lens and Stream Quality below, then activate the server to stream even with your screen turned off.",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8)),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                // Layout Preview Zone (16:9 Aspect Ratio)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black)
                        .border(1.dp, Color(0xFF222B36), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        selectedCamera = selectedCamera,
                        onFrameAnalyzed = {} // Pre-stream doesn't broadcast frames
                    )

                    // Overlay indicator label
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentAlignment = Alignment.TopStart
                    ) {
                        Row(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = null, tint = Color(0xFF00FFCC), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "LENS PREVIEW ACTIVE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                        }
                    }
                }

                // LENS SELECTION ROW
                Text(
                    text = "SELECT CAMERA LENS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF53657D),
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp)
                )

                if (availableCameras.isEmpty()) {
                    Text(
                        text = "Scanning camera sensors... If this takes persistent time, check app camera permissions.",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8)),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableCameras.forEach { item ->
                                val isSelected = selectedCamera?.hash == item.hash
                                val isWideAngle = item.isUltraWide
                                val chipBg = if (isSelected) Color(0xFFFF3344) else Color(0xFF161B22)
                                val chipBorder = if (isSelected) Color(0xFFFF3344) else Color(0xFF222B36)
                                val chipContentColor = if (isSelected) Color.White else Color(0xFFA0AAB8)

                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.selectCamera(item) },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, chipBorder),
                                    color = chipBg
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = if (isWideAngle) Icons.Default.ZoomOutMap
                                                         else if (item.isBack) Icons.Default.CameraRear
                                                         else Icons.Default.CameraFront,
                                            contentDescription = null,
                                            tint = chipContentColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = item.name,
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                                color = chipContentColor,
                                                fontSize = 9.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Wide Angle lens toggle button for quick access
                        if (availableCameras.any { it.isUltraWide }) {
                            val isWideSelected = selectedCamera?.isUltraWide == true
                            Button(
                                onClick = { viewModel.toggleWideAngle() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isWideSelected) Color(0xFF00FFCC) else Color(0xFF161B22)
                                ),
                                border = BorderStroke(1.dp, if (isWideSelected) Color(0xFF00FFCC) else Color(0xFF222B36)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ZoomOutMap,
                                        contentDescription = "Wide Angle Lens",
                                        tint = if (isWideSelected) Color.Black else Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isWideSelected) "Wide Angle Lens (0.5x) Enabled" else "Switch to Wide Angle Lens (0.5x)",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (isWideSelected) Color.Black else Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // SELECT RESOLUTION (16:9)
                Text(
                    text = "SELECT STREAM RESOLUTION (16:9)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF53657D),
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp)
                )

                val resolutions = listOf("1080p", "720p", "480p", "360p")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    resolutions.forEach { res ->
                        val isSelected = streamResolution == res
                        val chipBg = if (isSelected) Color(0xFFFF3344) else Color(0xFF161B22)
                        val chipBorder = if (isSelected) Color(0xFFFF3344) else Color(0xFF222B36)
                        val chipContentColor = if (isSelected) Color.White else Color(0xFFA0AAB8)

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { viewModel.setStreamResolution(res) },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, chipBorder),
                            color = chipBg
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = res,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = chipContentColor
                                    )
                                )
                                Text(
                                    text = when (res) {
                                        "1080p" -> "FHD (16:9)"
                                        "720p" -> "HD (16:9)"
                                        "480p" -> "SD (16:9)"
                                        "360p" -> "Low (16:9)"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = chipContentColor.copy(alpha = 0.7f),
                                        fontSize = 8.sp
                                    )
                                )
                            }
                        }
                    }
                }

                // IP ADDRESS INSTRUCTION DETAILS
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Connection IP Addresses",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = Color.White)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (localIps.isEmpty()) {
                            Text(
                                text = "Awaiting connection, checking WiFi interfaces...",
                                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8))
                            )
                        } else {
                            localIps.forEach { ipPair ->
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(ipPair.first, style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8)))
                                    Text(
                                        "${ipPair.second}:9002",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF00FFCC),
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // ACTION BUTTON TO START
                Button(
                    onClick = { viewModel.startForegroundStreaming() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(bottom = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3344)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Start Background Stream",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ReceiverScreen(viewModel: StreamViewModel) {
    val context = LocalContext.current
    val connectionState by viewModel.receiver.connectionState.collectAsStateWithLifecycle()
    val videoStream by viewModel.receiver.videoStream.collectAsStateWithLifecycle()
    val isBackTalkEnabled by viewModel.receiver.isBackTalkEnabled.collectAsStateWithLifecycle()
    val recordingState by viewModel.receiver.recordingState.collectAsStateWithLifecycle()
    val targetIp by viewModel.targetIp.collectAsStateWithLifecycle()
    var showQrScanner by remember { mutableStateOf(false) }
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    DisposableEffect(Unit) {
        onDispose {
            viewModel.receiver.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.navigateTo(StreamViewModel.Screen.Home) },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF161B22))
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Go back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Remote Stream Monitor",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }

        if (connectionState is P2PReceiver.ConnectionState.Connected) {
            // Screen display for active stream (16:9 Aspect Ratio)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black)
                    .border(2.dp, Color(0xFF222B36), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (videoStream != null) {
                    Image(
                        bitmap = videoStream!!.asImageBitmap(),
                        contentDescription = "Stream Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFFFF3344))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Buffering stream...",
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFA0AAB8))
                        )
                    }
                }

                // Overlay Status (Recording etc.)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        // FPS / Connection Badge
                        Box(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "LIVE: low-latency",
                                style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold)
                            )
                        }

                        // Recording Badge
                        if (recordingState is P2PReceiver.RecordingState.Recording) {
                            val duration = (recordingState as P2PReceiver.RecordingState.Recording).durationSeconds
                            val formattedTime = String.format("%02d:%02d", duration / 60, duration % 60)
                            Row(
                                modifier = Modifier
                                    .background(Color.Red.copy(alpha = 0.85f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Canvas(modifier = Modifier.size(6.dp)) {
                                    drawCircle(color = Color.White)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "REC $formattedTime",
                                    style = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }

            // Realtime interaction overlay bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val selectedOrientation by viewModel.receiver.selectedRecordingOrientation.collectAsStateWithLifecycle()
                        val isRecording = recordingState is P2PReceiver.RecordingState.Recording

                        if (!isRecording) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Record Orientation:",
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8), fontWeight = FontWeight.Bold)
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.receiver.setRecordingOrientation(P2PReceiver.RecordingOrientation.PORTRAIT) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selectedOrientation == P2PReceiver.RecordingOrientation.PORTRAIT) Color(0xFFFF3344) else Color(0xFF222B36)
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Portrait (9:16)", style = MaterialTheme.typography.labelSmall.copy(color = Color.White))
                                    }
                                    Button(
                                        onClick = { viewModel.receiver.setRecordingOrientation(P2PReceiver.RecordingOrientation.LANDSCAPE) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selectedOrientation == P2PReceiver.RecordingOrientation.LANDSCAPE) Color(0xFFFF3344) else Color(0xFF222B36)
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Landscape (16:9)", style = MaterialTheme.typography.labelSmall.copy(color = Color.White))
                                    }
                                }
                            }
                            HorizontalDivider(color = Color(0xFF222B36), thickness = 1.dp, modifier = Modifier.padding(bottom = 12.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Two-way sound (Speak to spare phone)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = { viewModel.receiver.toggleBackTalk(!isBackTalkEnabled) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = if (isBackTalkEnabled) Color(0xFF00FFCC) else Color(0xFF222B36)
                                    ),
                                    modifier = Modifier.size(52.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isBackTalkEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                        contentDescription = "Toggle backtalk mic",
                                        tint = if (isBackTalkEnabled) Color.Black else Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Talk back",
                                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFA0AAB8))
                                )
                            }

                            // Local recording on parent
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = {
                                        if (isRecording) {
                                            viewModel.receiver.stopRecording()
                                        } else {
                                            viewModel.receiver.startRecording()
                                        }
                                    },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = if (isRecording) Color.Red else Color(0xFF222B36)
                                    ),
                                    modifier = Modifier.size(52.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                        contentDescription = "Toggle record on-device",
                                        tint = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isRecording) "Stop Rec" else "Record",
                                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFA0AAB8))
                                )
                            }

                            // Disconnect Stream button
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = { viewModel.receiver.stop() },
                                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFFE63946)),
                                    modifier = Modifier.size(52.dp)
                                ) {
                                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "Disconnect Stream", tint = Color.White)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Disconnect",
                                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFA0AAB8))
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Connect selector screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .background(Color(0xFF1E2633), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Connect setup",
                        tint = Color(0xFFFF3344),
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Connect to Camera",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Please enter the IP Address listed on your spare phone screen below to connect.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFA0AAB8)),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                OutlinedTextField(
                    value = targetIp,
                    onValueChange = { viewModel.updateTargetIp(it) },
                    label = { Text("Camera IP Address", color = Color(0xFFA0AAB8)) },
                    placeholder = { Text("e.g. 192.168.43.1") },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (cameraPermissionState.status.isGranted) {
                                showQrScanner = true
                            } else {
                                cameraPermissionState.launchPermissionRequest()
                            }
                        }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code", tint = Color(0xFFFF3344))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF3344),
                        unfocusedBorderColor = Color(0xFF222B36),
                        focusedContainerColor = Color(0xFF121824),
                        unfocusedContainerColor = Color(0xFF121824)
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ip_address_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                // Discovered Cameras / Spare Phone Network Scanner
                val discoveredCameras by viewModel.discoveredCameras.collectAsStateWithLifecycle()
                val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    viewModel.startScanningForCameras()
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    border = BorderStroke(1.dp, Color(0xFF222B36)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Discovered Cameras",
                                style = MaterialTheme.typography.titleSmall.copy(color = Color.White, fontWeight = FontWeight.Bold)
                            )
                            if (isScanning) {
                                CircularProgressIndicator(color = Color(0xFFFF3344), modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(
                                    onClick = { viewModel.startScanningForCameras() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Scan network",
                                        tint = Color(0xFFFF3344),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (discoveredCameras.isEmpty()) {
                            Text(
                                text = if (isScanning) "Searching subnet for spare cameras..." else "No cameras found automatically. Scan again or type IP.",
                                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8))
                            )
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(discoveredCameras) { ip ->
                                    Button(
                                        onClick = {
                                            viewModel.updateTargetIp(ip)
                                            viewModel.receiver.connect(ip)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3344).copy(alpha = 0.15f)),
                                        border = BorderStroke(1.dp, Color(0xFFFF3344)),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Videocam,
                                                contentDescription = "Camera",
                                                tint = Color(0xFFFF3344),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = ip,
                                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                val isConnecting = connectionState is P2PReceiver.ConnectionState.Connecting

                Button(
                    onClick = { viewModel.receiver.connect(targetIp) },
                    enabled = !isConnecting && targetIp.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3344)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("connect_to_camera_button")
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = "Connect Stream",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }

                if (connectionState is P2PReceiver.ConnectionState.Error) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = (connectionState as P2PReceiver.ConnectionState.Error).message,
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFE63946)),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onQrScanned = { scannedIp ->
                viewModel.updateTargetIp(scannedIp)
                viewModel.receiver.connect(scannedIp)
                showQrScanner = false
            }
        )
    }
}

@Composable
fun DashboardScreen(viewModel: StreamViewModel) {
    val savedRecordings by viewModel.savedRecordings.collectAsStateWithLifecycle()
    var selectedVideoForPlayback by remember { mutableStateOf<Recording?>(null) }
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(exportStatus) {
        exportStatus?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearExportStatus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.navigateTo(StreamViewModel.Screen.Home) },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF161B22))
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Go back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Recordings Gallery",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }

        if (savedRecordings.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color(0xFF1E2633), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Empty",
                        tint = Color(0xFF53657D),
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No recordings found",
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "When connected as a Viewer, hit the record button to capture high-quality remote video on this device.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFA0AAB8)),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        } else {
            // Visual list of recordings
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(savedRecordings, key = { it.id }) { rec ->
                    RecordingCardItem(
                        recording = rec,
                        onPlay = { selectedVideoForPlayback = rec },
                        onDownload = { viewModel.exportRecordingToGallery(rec) },
                        onShare = { viewModel.shareRecording(rec) },
                        onDelete = { viewModel.deleteRecording(rec) }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }
    }

    // Modal dialog playback
    if (selectedVideoForPlayback != null) {
        VideoPlayerDialog(
            videoFile = File(selectedVideoForPlayback!!.filePath),
            onDismiss = { selectedVideoForPlayback = null }
        )
    }
}

@Composable
fun RecordingCardItem(
    recording: Recording,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val durationMinSec = String.format("%02d:%02d", recording.durationSeconds / 60, recording.durationSeconds % 60)
    val fileSizeFormatted = DecimalFormat("#,##0.0").format(recording.sizeBytes / (1024f * 1024f)) + " MB"
    val formattedDate = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(recording.timestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF222B36), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Video placeholder icon indicator box
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E2633))
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play recording",
                    tint = Color(0xFF00FFCC),
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.fileName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Length: $durationMinSec",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8))
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF53657D))
                    )
                    Text(
                        text = fileSizeFormatted,
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8))
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF53657D), fontSize = 10.sp)
                )
            }

            IconButton(
                onClick = onDownload,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF00FFCC))
            ) {
                Icon(Icons.Default.Download, contentDescription = "Download to Gallery")
            }

            IconButton(
                onClick = onShare,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFFFF3344))
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share video")
            }

            IconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFFE63946))
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete recording")
            }
        }
    }
}

@Composable
fun VideoPlayerDialog(
    videoFile: File,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(450.dp)
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121824))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = videoFile.name,
                        style = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close player", tint = Color.White)
                    }
                }

                // Video player View
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { context ->
                            VideoView(context).apply {
                                setVideoPath(videoFile.absolutePath)
                                val mediaController = MediaController(context)
                                mediaController.setAnchorView(this)
                                setMediaController(mediaController)
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    selectedCamera: CameraOption?,
    onFrameAnalyzed: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(selectedCamera) {
        try {
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            val preview = Preview.Builder().build()
            val cameraSelector = if (selectedCamera != null) {
                val matchingInfo = cameraProvider.availableCameraInfos.find { info ->
                    Camera2CameraInfo.from(info).cameraId == selectedCamera.cameraId
                } ?: cameraProvider.availableCameraInfos.find { it.hashCode() == selectedCamera.hash }

                if (matchingInfo != null) {
                    CameraSelector.Builder()
                        .addCameraFilter { list -> list.filter { it == matchingInfo } }
                        .build()
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // Keep analysis light & responsive
            val imageAnalysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

            if (selectedCamera?.physicalCameraId != null) {
                androidx.camera.camera2.interop.Camera2Interop.Extender(imageAnalysisBuilder)
                    .setPhysicalCameraId(selectedCamera.physicalCameraId)
            }

            val imageAnalysis = imageAnalysisBuilder.build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    val bitmap = imageProxy.toBitmap()
                    onFrameAnalyzed(bitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    imageProxy.close()
                }
            }

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            preview.surfaceProvider = previewView.surfaceProvider
        } catch (e: Exception) {
            Log.e("CameraPreview", "Camera connection failed", e)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                Log.e("CameraPreview", "Failed to unbind camera on dispose", e)
            }
            analysisExecutor.shutdown()
        }
    }
}

@Composable
fun QrScannerDialog(
    onDismiss: () -> Unit,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

    val statusText by remember { mutableStateOf("Position QR Code within the frame to scan") }

    LaunchedEffect(Unit) {
        try {
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            val preview = Preview.Builder().build()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    val bitmap = imageProxy.toBitmap()
                    val qrText = com.example.p2p.QRUtils.decodeQrFromBitmap(bitmap)
                    if (qrText != null && qrText.isNotBlank()) {
                        if (qrText.contains(".") && qrText.split(".").size >= 3) {
                            imageProxy.close()
                            previewView.post {
                                onQrScanned(qrText.trim())
                            }
                            return@setAnalyzer
                        }
                    }
                } catch (e: Exception) {
                    Log.e("QrScannerDialog", "Error scanning QR", e)
                } finally {
                    imageProxy.close()
                }
            }

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
            preview.surfaceProvider = previewView.surfaceProvider
        } catch (e: Exception) {
            Log.e("QrScannerDialog", "Failed to start camera for QR scanner", e)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(450.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
            border = BorderStroke(1.dp, Color(0xFF222B36))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scan Connection QR",
                        style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close scanner", tint = Color.White)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .border(1.dp, Color(0xFF222B36), RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .align(Alignment.Center)
                            .border(2.dp, Color(0xFFFF3344), RoundedCornerShape(12.dp))
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA0AAB8)),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                Log.e("QrScannerDialog", "Failed to unbind camera on dispose", e)
            }
            analysisExecutor.shutdown()
        }
    }
}
