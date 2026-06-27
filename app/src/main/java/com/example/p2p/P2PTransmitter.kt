package com.example.p2p

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class P2PTransmitter {
    private val tag = "P2PTransmitter"

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null
    private var dataInputStream: DataInputStream? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState = _connectionState.asStateFlow()

    private val _clientsConnectedCount = MutableStateFlow(0)
    val clientsConnectedCount = _clientsConnectedCount.asStateFlow()

    private var isRunning = false
    private val writeLock = Any()

    // Audio Configurations
    private val sampleRate = 16000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioSource = MediaRecorder.AudioSource.MIC

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var audioRecordThread: Thread? = null
    private var clientReadThread: Thread? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    sealed class ConnectionState {
        object Idle : ConnectionState()
        data class Listening(val ipAddress: String, val port: Int) : ConnectionState()
        data class Connected(val clientIp: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    fun startServer(port: Int = 9002) {
        if (isRunning) return
        isRunning = true
        _connectionState.value = ConnectionState.Idle

        coroutineScope.launch {
            try {
                val myIp = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                _connectionState.value = ConnectionState.Listening(myIp, port)
                Log.i(tag, "Transmitter server started on $myIp:$port")

                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Log.i(tag, "Client connected: ${socket.inetAddress.hostAddress}")
                        handleNewClient(socket)
                    } catch (se: SocketException) {
                        Log.i(tag, "ServerSocket closed or accepted break")
                        break
                    } catch (e: Exception) {
                        Log.e(tag, "Error accepting client", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start ServerSocket", e)
                _connectionState.value = ConnectionState.Error("Port $port binding failed: ${e.message}")
            }
        }
    }

    private fun handleNewClient(socket: Socket) {
        // Only allow one connected client for performance and latency
        synchronized(writeLock) {
            cleanupActiveResources()
            clientSocket = socket
            try {
                dataOutputStream = DataOutputStream(socket.getOutputStream())
                dataInputStream = DataInputStream(socket.getInputStream())
                _connectionState.value = ConnectionState.Connected(socket.inetAddress.hostAddress ?: "Client")
                _clientsConnectedCount.value = 1

                // Start bidirectional audio
                startAudioRecord()
                startAudioPlayback()
                startClientReadLoop()
            } catch (e: Exception) {
                Log.e(tag, "Failed setting up streams for client", e)
                resetClientConnection()
            }
        }
    }

    fun sendVideoFrame(bitmap: Bitmap, quality: Int = 50) {
        if (!isRunning || clientSocket == null || dataOutputStream == null) return
        coroutineScope.launch(Dispatchers.Default) {
            try {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val jpegBytes = out.toByteArray()

                writePacket(0x01, jpegBytes)
            } catch (e: Exception) {
                Log.e(tag, "Failed sending video frame", e)
                resetClientConnection()
            }
        }
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
                Log.e(tag, "Socket write exception", e)
                resetClientConnection()
            }
        }
    }

    // Capture microphone audio and stream it
    @SuppressLint("MissingPermission")
    private fun startAudioRecord() {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, audioFormat)
            if (bufferSize <= 0) return

            audioRecord = AudioRecord(audioSource, sampleRate, channelIn, audioFormat, bufferSize)
            audioRecord?.startRecording()

            audioRecordThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRunning && clientSocket != null) {
                    val record = audioRecord ?: break
                    val read = record.read(buffer, 0, bufferSize)
                    if (read > 0) {
                        val payload = buffer.copyOf(read)
                        writePacket(0x02, payload) // 0x02 = OUTGOING AUDIO
                    } else if (read < 0) {
                        Log.e(tag, "Audio record error: $read")
                        break
                    }
                }
            }.apply {
                name = "TransmitterAudioRecord"
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.i(tag, "Audio Record thread started client side")
        } catch (e: Exception) {
            Log.e(tag, "Failed starting audio capture", e)
        }
    }

    // Start playback for backtalk audio coming from Client
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
            Log.i(tag, "Audio Playback started on Transmitter")
        } catch (e: Exception) {
            Log.e(tag, "Failed starting backtalk audio playback", e)
        }
    }

    // Core read loop to receive items from client (specifically back-talk microphone audio packages)
    private fun startClientReadLoop() {
        val dis = dataInputStream ?: return
        val socket = clientSocket ?: return
        clientReadThread = Thread {
            val headerBytes = ByteArray(3)
            try {
                while (isRunning && !socket.isClosed) {
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
                    if (length <= 0 || length > 1024 * 1024) continue // Safety skip

                    // Read Payload
                    val payload = ByteArray(length)
                    var payloadRead = 0
                    while (payloadRead < length) {
                        val count = dis.read(payload, payloadRead, length - payloadRead)
                        if (count == -1) throw IOException("Incomplete transmission")
                        payloadRead += count
                    }

                    // Process Backtalk Audio
                    if (type == 0x03.toByte()) { // BACKTALK AUDIO from Receiver
                        audioTrack?.write(payload, 0, length)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Client reader loop terminated: ${e.message}")
                resetClientConnection()
            }
        }.apply {
            name = "ReceiverReadLoop"
            start()
        }
    }

    private fun resetClientConnection() {
        synchronized(writeLock) {
            cleanupActiveResources()
            _clientsConnectedCount.value = 0
            val myIp = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
            if (isRunning) {
                _connectionState.value = ConnectionState.Listening(myIp, 9002)
            } else {
                _connectionState.value = ConnectionState.Idle
            }
        }
    }

    private fun cleanupActiveResources() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        audioRecordThread?.interrupt()
        audioRecordThread = null

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
            clientSocket?.close()
        } catch (_: Exception) {}
        clientSocket = null
    }

    fun stopServer() {
        isRunning = false
        cleanupActiveResources()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        _connectionState.value = ConnectionState.Idle
        _clientsConnectedCount.value = 0
        Log.i(tag, "Transmitter server stopped entirely")
    }
}
