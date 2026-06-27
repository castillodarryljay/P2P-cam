package com.example.p2p

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.IOException

class MuxerVideoSaver(
    private val outputFile: File,
    private val width: Int = 480,
    private val height: Int = 640,
    private val fps: Int = 15
) {
    private val tag = "MuxerVideoSaver"
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false
    private var isRecording = false
    private var isReleased = false

    private var firstFrameTimeNs: Long = -1L
    private var lastPresentationTimeUs: Long = 0L

    private val drainLock = Any()
    private var drainThread: Thread? = null

    fun start() {
        if (isRecording) return
        isRecording = true
        isReleased = false
        firstFrameTimeNs = -1L
        lastPresentationTimeUs = 0L

        try {
            // Setup MediaFormat for H.264
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000) // 1 Mbps is optimal
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // I-frame every 1s
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Start a thread to drain the encoder outputs
            drainThread = Thread {
                drainEncoder()
            }.apply {
                name = "VideoEncoderDrain"
                start()
            }

            Log.i(tag, "Video encoder started successfully inside $outputFile")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start video encoder", e)
            release()
        }
    }

    fun writeBitmap(bitmap: Bitmap) {
        if (!isRecording || inputSurface == null) return
        try {
            var finalBitmap = bitmap
            val videoIsLandscape = width > height
            val bitmapIsLandscape = bitmap.width > bitmap.height

            if (videoIsLandscape != bitmapIsLandscape) {
                val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            val canvas = inputSurface!!.lockCanvas(null)
            val destRect = Rect(0, 0, width, height)
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(finalBitmap, null, destRect, null)
            inputSurface!!.unlockCanvasAndPost(canvas)

            // Push timestamp
            val nowNs = System.nanoTime()
            if (firstFrameTimeNs == -1L) {
                firstFrameTimeNs = nowNs
            }
            // Use time-elapsed for presentation time
            var presentationTimeUs = (nowNs - firstFrameTimeNs) / 1000L
            if (presentationTimeUs <= lastPresentationTimeUs) {
                presentationTimeUs = lastPresentationTimeUs + 1 // Ensure monotonic increase
            }
            lastPresentationTimeUs = presentationTimeUs

            // Let the encoder know frame is available (handled automatically with Surface input)
        } catch (e: Exception) {
            Log.e(tag, "Error writing bitmap to surface", e)
        }
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRecording) {
            val codec = mediaCodec ?: break
            val muxer = mediaMuxer ?: break
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L) // 10ms timeout
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(drainLock) {
                        if (isMuxerStarted) {
                            Log.w(tag, "Codec format changed twice?")
                        } else {
                            val newFormat = codec.outputFormat
                            videoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            isMuxerStarted = true
                            Log.i(tag, "Muxer started with video track index $videoTrackIndex")
                        }
                    }
                } else if (outputBufferIndex >= 0) {
                    val encodedData = codec.getOutputBuffer(outputBufferIndex) ?: continue

                    // Adjust presentation time if needed
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0 // Ignoring config details since muxer handles them
                    }

                    if (bufferInfo.size != 0) {
                        synchronized(drainLock) {
                            if (isMuxerStarted && videoTrackIndex >= 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.i(tag, "End of Stream reached in encoder")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error draining encoder", e)
                break
            }
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false

        // Inform the surface of End of Stream
        try {
            mediaCodec?.signalEndOfInputStream()
        } catch (_: Exception) {}

        drainThread?.join(2000)
        drainThread = null

        release()
    }

    private fun release() {
        if (isReleased) return
        isReleased = true
        isRecording = false

        synchronized(drainLock) {
            try {
                mediaCodec?.stop()
            } catch (_: Exception) {}
            try {
                mediaCodec?.release()
            } catch (_: Exception) {}
            mediaCodec = null

            try {
                if (isMuxerStarted) {
                    mediaMuxer?.stop()
                }
            } catch (_: Exception) {}
            try {
                mediaMuxer?.release()
            } catch (_: Exception) {}
            mediaMuxer = null

            inputSurface = null
            videoTrackIndex = -1
            isMuxerStarted = false
        }
        Log.i(tag, "Encoder release complete")
    }
}
