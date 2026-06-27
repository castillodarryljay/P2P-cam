package com.example.p2p

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.example.data.Recording
import com.example.data.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class P2PReceiver(private val recordingRepository: RecordingRepository, private val filesDir: File) {
    private val tag = "P2PReceiver"

    private var socket: Socket? = null
    private var dataInputStream: DataInputStream? = null
    private var dataOutputStream: DataOutputStream? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState = _connectionState.asStateFlow()

    private val _videoStream = MutableStateFlow<Bitmap?>(null)
    val videoStream = _videoStream.asStateFlow()

    private val _isBackTalkEnabled = MutableStateFlow(false)
    val isBackTalkEnabled = _isBackTalkEnabled.asStateFlow()

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState = _recordingState.asStateFlow()

    enum class RecordingOrientation {
        PORTRAIT, LANDSCAPE
    }
    private val _selectedRecordingOrientation = MutableStateFlow(RecordingOrientation.PORTRAIT)
    val selectedRecordingOrientation = _selectedRecordingOrientation.asStateFlow()

    fun setRecordingOrientation(orientation: RecordingOrientation) {
        _selectedRecordingOrientation.value = orientation
    }

    private var isRunning = false
    private val writeLock = Any()

    // Audio configs
    private val sampleRate = 16000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioSource = MediaRecorder.AudioSource.MIC

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    private var clientReadThread: Thread? = null
    private var backTalkRecordThread: Thread? = null

    private var videoSaver: MuxerVideoSaver? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingStartTime: Long = 0L
    private var recordingTickerJob: Job? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    sealed class ConnectionState {
        object Idle : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    sealed class RecordingState {
        object Idle : RecordingState()
        data class Recording(val durationSeconds: Long, val filePath: String) : RecordingState()
    }

    fun connect(ip: String, port: Int = 9002) {
        if (isRunning) return
        isRunning = true
        _connectionState.value = ConnectionState.Connecting

        coroutineScope.launch {
            try {
                socket = Socket().apply {
                    connect(InetSocketAddress(ip, port), 5000) // 5s timeout
                }
                dataInputStream = DataInputStream(socket!!.getInputStream())
                dataOutputStream = DataOutputStream(socket!!.getOutputStream())

                _connectionState.value = ConnectionState.Connected
                Log.i(tag, "Connected to transmitter: $ip:$port")

                // Setup local AudioTrack for playing transmitter microphone
                startAudioPlayback()

                // Start Main socket reader
                startReadLoop()

                // Start microphone capture if backtalk was toggled on pre-connection
                if (_isBackTalkEnabled.value) {
                    startBackTalkRecording()
                }

            } catch (e: Exception) {
                Log.e(tag, "Connection failed to $ip:$port", e)
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
                stop()
            }
        }
    }

    private fun startAudioPlayback() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelOut, audioFormat)
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelOut,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
            Log.i(tag, "Sound receiver playback started")
        } catch (e: Exception) {
            Log.e(tag, "AudioTrack init failed on receiver", e)
        }
    }

    private fun startReadLoop() {
        val dis = dataInputStream ?: return
        val sock = socket ?: return

        clientReadThread = Thread {
            val headerBytes = ByteArray(3)
            try {
                while (isRunning && !sock.isClosed) {
                    // Read 3 byte header "P2P"
                    var read = 0
                    while (read < 3) {
                        val count = dis.read(headerBytes, read, 3 - read)
                        if (count == -1) throw IOException("EOF reached")
                        read += count
                    }
                    val header = String(headerBytes, Charsets.US_ASCII)
                    if (header != "P2P") {
                        continue
                    }

                    // Read Type
                    val type = dis.readByte()

                    // Read Length
                    val length = dis.readInt()
                    if (length <= 0 || length > 10 * 1024 * 1024) continue // Safety skip if abnormal (10MB limit)

                    // Read Payload
                    val payload = ByteArray(length)
                    var payloadRead = 0
                    while (payloadRead < length) {
                        val count = dis.read(payload, payloadRead, length - payloadRead)
                        if (count == -1) throw IOException("Payload cut off")
                        payloadRead += count
                    }

                    // Handle Payload based on Type
                    when (type) {
                        0x01.toByte() -> { // JPEG Frame
                            val bmp = BitmapFactory.decodeByteArray(payload, 0, length)
                            if (bmp != null) {
                                _videoStream.value = bmp
                                // If recording is active, feed frame
                                val saver = videoSaver
                                if (saver != null) {
                                    saver.writeBitmap(bmp)
                                }
                            }
                        }
                        0x02.toByte() -> { // AUDIO from transmitter microphone
                            audioTrack?.write(payload, 0, length)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Read loop terminated: ${e.message}")
                _connectionState.value = ConnectionState.Error("Disconnected from spare camera: ${e.message}")
                stop()
            }
        }.apply {
            name = "ReceiverSocketReader"
            start()
        }
    }

    fun startRecording() {
        if (_connectionState.value !is ConnectionState.Connected) return
        if (_recordingState.value is RecordingState.Recording) return

        try {
            val recordingsDir = File(filesDir, "recordings").apply { mkdirs() }
            val fileName = "p2p_cam_${System.currentTimeMillis()}.mp4"
            val file = File(recordingsDir, fileName)
            currentRecordingFile = file

            val orientation = _selectedRecordingOrientation.value
            val width = if (orientation == RecordingOrientation.LANDSCAPE) 640 else 480
            val height = if (orientation == RecordingOrientation.LANDSCAPE) 480 else 640

            videoSaver = MuxerVideoSaver(file, width = width, height = height).apply {
                start()
            }

            currentRecordingStartTime = System.currentTimeMillis()
            _recordingState.value = RecordingState.Recording(0, file.absolutePath)

            // Start simple 1s progress reporter
            recordingTickerJob = coroutineScope.launch {
                while (true) {
                    delay(1000)
                    val currentDuration = (System.currentTimeMillis() - currentRecordingStartTime) / 1000L
                    val state = _recordingState.value
                    if (state is RecordingState.Recording) {
                        _recordingState.value = RecordingState.Recording(currentDuration, file.absolutePath)
                    }
                }
            }
            Log.i(tag, "Started video recording to $file")
        } catch (e: Exception) {
            Log.e(tag, "Failed starting on-device recording", e)
        }
    }

    fun stopRecording() {
        val state = _recordingState.value
        if (state !is RecordingState.Recording) return

        recordingTickerJob?.cancel()
        recordingTickerJob = null

        videoSaver?.stop()
        videoSaver = null

        val duration = (System.currentTimeMillis() - currentRecordingStartTime) / 1000L
        val file = currentRecordingFile
        currentRecordingFile = null
        _recordingState.value = RecordingState.Idle

        if (file != null && file.exists()) {
            val size = file.length()
            if (size <= 0) {
                // Delete if empty
                file.delete()
                Log.w(tag, "Deleted zero bytes record file")
                return
            }

            coroutineScope.launch {
                val name = file.name
                val rec = Recording(
                    filePath = file.absolutePath,
                    fileName = name,
                    durationSeconds = duration,
                    sizeBytes = size
                )
                recordingRepository.insert(rec)
                Log.i(tag, "Saved recording record to Room: $rec")
            }
        }
    }

    fun toggleBackTalk(enabled: Boolean) {
        _isBackTalkEnabled.value = enabled
        if (_connectionState.value is ConnectionState.Connected) {
            if (enabled) {
                startBackTalkRecording()
            } else {
                stopBackTalkRecording()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBackTalkRecording() {
        if (backTalkRecordThread != null) return
        try {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, audioFormat)
            if (bufferSize <= 0) return

            audioRecord = AudioRecord(audioSource, sampleRate, channelIn, audioFormat, bufferSize)
            audioRecord?.startRecording()

            backTalkRecordThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRunning && _isBackTalkEnabled.value) {
                    val record = audioRecord ?: break
                    val read = record.read(buffer, 0, bufferSize)
                    if (read > 0) {
                        val payload = buffer.copyOf(read)
                        writePacket(0x03, payload) // 0x03 = BACKTALK AUDIO from client
                    } else if (read < 0) {
                        break
                    }
                }
            }.apply {
                name = "BackTalkRecord"
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.i(tag, "BackTalk sound recording started on receiver")
        } catch (e: Exception) {
            Log.e(tag, "Failed starting mic capture for TalkBack", e)
        }
    }

    private fun stopBackTalkRecording() {
        audioRecordThreadRelease()
    }

    private fun audioRecordThreadRelease() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        backTalkRecordThread?.interrupt()
        backTalkRecordThread = null
    }

    private fun writePacket(type: Byte, payload: ByteArray) {
        synchronized(writeLock) {
            val dos = dataOutputStream ?: return
            try {
                dos.write("P2P".toByteArray(Charsets.US_ASCII))
                dos.writeByte(type.toInt())
                dos.writeInt(payload.size)
                dos.write(payload)
                dos.flush()
            } catch (e: Exception) {
                Log.e(tag, "Failed writing packet from receiver", e)
            }
        }
    }

    fun stop() {
        isRunning = false

        if (_recordingState.value is RecordingState.Recording) {
            stopRecording()
        }

        cleanupResources()

        if (_connectionState.value != ConnectionState.Idle && _connectionState.value !is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Idle
        }
        _videoStream.value = null
        Log.i(tag, "Client receiver disconnected and released")
    }

    private fun cleanupResources() {
        audioRecordThreadRelease()

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        clientReadThread?.interrupt()
        clientReadThread = null

        try {
            dataInputStream?.close()
        } catch (_: Exception) {}
        dataInputStream = null

        try {
            dataOutputStream?.close()
        } catch (_: Exception) {}
        dataOutputStream = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }
}
