package com.example.bitter_camera.faceeffect

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.flutter.view.TextureRegistry
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the native camera pipeline:
 *   CameraX capture -> YUV->BGR conversion -> face detection + warp -> render to Flutter Texture
 *
 * Also handles photo capture and video recording from the processed stream.
 */
class NativeCameraHandler(
    private val activity: Activity,
    private val textureRegistry: TextureRegistry
) {
    companion object {
        private const val TAG = "NativeCameraHandler"
        private var opencvInitialized = false
        private var opencvAvailable = false

        fun ensureOpenCV(): Boolean {
            if (!opencvInitialized) {
                try {
                    opencvAvailable = OpenCVLoader.initLocal()
                    Log.d(TAG, "OpenCV init: $opencvAvailable")
                } catch (t: Throwable) {
                    Log.e(TAG, "OpenCV init failed", t)
                    opencvAvailable = false
                }
                opencvInitialized = true
            }
            return opencvAvailable
        }
    }

    private var textureEntry: TextureRegistry.SurfaceTextureEntry? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    // A separate output surface for CameraX Preview, to avoid sharing the Flutter render surface.
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private val renderLock = Any()

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    val faceProcessor = FaceProcessor()
    private var isFrontCamera = true

    // Preview lifecycle guards (avoid rendering after stop/switch)
    private val generationCounter = AtomicLong(0)
    @Volatile private var activeGeneration: Long = 0
    @Volatile private var previewActive: Boolean = false
    @Volatile private var frameCount: Long = 0

    // Processed frame dimensions (set when first frame arrives)
    private var frameWidth = 0
    private var frameHeight = 0

    // Latest processed bitmap (for photo capture)
    @Volatile
    private var latestProcessedBitmap: Bitmap? = null

    // Video recording state
    private val isRecording = AtomicBoolean(false)
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var recordingOutputPath: String? = null
    private var muxerStarted = false
    private var presentationTimeUs = 0L

    /**
     * Start the camera preview pipeline.
     * @return the Flutter texture ID for display
     */
    fun startPreview(useFrontCamera: Boolean = true): Long {
        if (!ensureOpenCV()) {
            throw IllegalStateException("OpenCV initialization failed on this device")
        }

        isFrontCamera = useFrontCamera
        previewActive = true
        activeGeneration = generationCounter.incrementAndGet()
        val myGen = activeGeneration

        // Reset dimensions so we recreate the Surface after stop/switch
        frameWidth = 0
        frameHeight = 0

        // Create Flutter texture
        textureEntry = textureRegistry.createSurfaceTexture()
        val entry = textureEntry!!
        surfaceTexture = entry.surfaceTexture()
        // Set a default buffer size; will be updated when frames arrive
        surfaceTexture!!.setDefaultBufferSize(640, 480)
        // Ensure we have a valid Surface immediately; otherwise rendering can stay white forever.
        surface?.release()
        surface = Surface(surfaceTexture)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val cameraSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis!!.setAnalyzer(analysisExecutor) { imageProxy ->
                processImageProxy(imageProxy, myGen)
            }

            // Some devices/emulators do not produce frames with ImageAnalysis alone.
            // Bind a Preview use case to ensure camera stream starts, but we won't display it directly.
            preview = Preview.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .build()
                .also { p ->
                    p.setSurfaceProvider { request ->
                        // Use a dedicated Surface for CameraX Preview output.
                        previewSurfaceTexture?.release()
                        previewSurfaceTexture = SurfaceTexture(0).apply {
                            setDefaultBufferSize(request.resolution.width, request.resolution.height)
                        }
                        previewSurface?.release()
                        previewSurface = Surface(previewSurfaceTexture)
                        val s = previewSurface
                        if (s == null) {
                            request.willNotProvideSurface()
                            return@setSurfaceProvider
                        }
                        request.provideSurface(s, analysisExecutor) { _ -> }
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    activity as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(activity))

        return entry.id()
    }

    /**
     * Stop the camera preview and release resources.
     */
    fun stopPreview() {
        previewActive = false
        // invalidate any in-flight analyzer work
        activeGeneration = generationCounter.incrementAndGet()
        frameCount = 0

        try {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera", e)
        }
        imageAnalysis = null
        preview = null
        cameraProvider = null

        try {
            analysisExecutor.shutdownNow()
        } catch (_: Exception) {}
        analysisExecutor = Executors.newSingleThreadExecutor()

        synchronized(renderLock) {
            surface?.release()
            surface = null

            textureEntry?.release()
            textureEntry = null
            surfaceTexture = null

            previewSurface?.release()
            previewSurface = null
            previewSurfaceTexture?.release()
            previewSurfaceTexture = null
        }

        frameWidth = 0
        frameHeight = 0

        latestProcessedBitmap?.recycle()
        latestProcessedBitmap = null
    }

    /**
     * Switch between front and back camera.
     * @return new texture ID
     */
    fun switchCamera(): Long {
        stopPreview()
        return startPreview(!isFrontCamera)
    }

    /**
     * Capture the latest processed frame as a photo.
     * @return file path of the saved JPEG, or null on failure
     */
    fun capturePhoto(outputDir: File): String? {
        val bitmap = latestProcessedBitmap ?: return null
        val file = File(outputDir, "bitter_photo_${System.currentTimeMillis()}.jpg")
        return try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Photo save failed", e)
            null
        }
    }

    /**
     * Start recording video from the processed frames.
     */
    fun startRecording(outputPath: String) {
        if (isRecording.get()) return
        recordingOutputPath = outputPath

        try {
            val w = if (frameWidth > 0) frameWidth else 640
            val h = if (frameHeight > 0) frameHeight else 480

            // Setup video encoder
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 25)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec!!.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec!!.start()

            mediaMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = -1
            muxerStarted = false
            presentationTimeUs = 0L

            isRecording.set(true)
            Log.d(TAG, "Recording started: $outputPath")
        } catch (e: Exception) {
            Log.e(TAG, "Start recording failed", e)
            cleanupRecording()
        }
    }

    /**
     * Stop recording and finalize the video file.
     * @return the output file path
     */
    fun stopRecording(): String? {
        if (!isRecording.get()) return null
        isRecording.set(false)

        try {
            // For ByteBuffer input mode, we must queue an explicit EOS input buffer.
            val codec = mediaCodec
            if (codec != null) {
                val eosIndex = codec.dequeueInputBuffer(10_000)
                if (eosIndex >= 0) {
                    codec.queueInputBuffer(
                        eosIndex,
                        0,
                        0,
                        presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } else {
                    Log.w(TAG, "Failed to dequeue input buffer for EOS: $eosIndex")
                }
            }

            // Drain remaining encoded data until EOS is observed.
            drainEncoder(true)

            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
            mediaMuxer = null

            Log.d(TAG, "Recording stopped: $recordingOutputPath")
            return recordingOutputPath
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording failed", e)
            cleanupRecording()
            return recordingOutputPath
        }
    }

    private fun cleanupRecording() {
        try { mediaCodec?.release() } catch (_: Exception) {}
        try { mediaMuxer?.release() } catch (_: Exception) {}
        mediaCodec = null
        mediaMuxer = null
        isRecording.set(false)
    }

    // ========== Frame processing pipeline ==========

    private fun processImageProxy(imageProxy: ImageProxy, gen: Long) {
        if (!previewActive || gen != activeGeneration) {
            imageProxy.close()
            return
        }
        try {
            val c = ++frameCount
            val bgrMat = yuvToMat(imageProxy)
            if (bgrMat == null) {
                return
            }

            // Apply rotation based on image rotation info
            val rotatedMat = rotateMat(bgrMat, imageProxy.imageInfo.rotationDegrees)
            bgrMat.release()

            // Mirror for front camera
            val finalInput = if (isFrontCamera) {
                val mirrored = Mat()
                Core.flip(rotatedMat, mirrored, 1) // horizontal flip
                rotatedMat.release()
                mirrored
            } else {
                rotatedMat
            }

            // Re-check guard after heavy work (switch/stop may have happened)
            if (!previewActive || gen != activeGeneration) {
                finalInput.release()
                return
            }

            // Update frame dimensions
            if (frameWidth != finalInput.cols() || frameHeight != finalInput.rows()) {
                frameWidth = finalInput.cols()
                frameHeight = finalInput.rows()
                surfaceTexture?.setDefaultBufferSize(frameWidth, frameHeight)
                // Avoid releasing/recreating Surface during active preview; it can race with render thread.
                if (surface == null) {
                    val st = surfaceTexture
                    if (st != null) surface = Surface(st)
                }
            }

            // Run face detection + warping
            val processed = faceProcessor.processFrame(finalInput)

            // Convert to Bitmap for rendering
            val rgbMat = Mat()
            Imgproc.cvtColor(processed, rgbMat, Imgproc.COLOR_BGR2RGBA)
            val bitmap = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, bitmap)

            // Store latest frame for photo capture
            latestProcessedBitmap?.recycle()
            latestProcessedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            // Draw to Flutter Texture's Surface
            // Guard again right before render (stop/switch can happen between checks).
            if (!previewActive || gen != activeGeneration) {
                if (processed !== finalInput) processed.release()
                finalInput.release()
                rgbMat.release()
                bitmap.recycle()
                return
            }
            renderToSurface(bitmap)

            // If recording, feed frame to encoder
            if (isRecording.get()) {
                feedFrameToEncoder(processed)
            }

            // Cleanup
            if (processed !== finalInput) processed.release()
            finalInput.release()
            rgbMat.release()
            bitmap.recycle()
        } catch (t: Throwable) {
            Log.e(TAG, "Frame processing error", t)
        } finally {
            imageProxy.close()
        }
    }

    private fun yuvToMat(imageProxy: ImageProxy): Mat? {
        try {
            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val width = imageProxy.width
            val height = imageProxy.height
            val nv21 = ByteArray(width * height * 3 / 2)

            // Copy Y plane respecting row/pixel strides.
            var outIndex = 0
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            for (row in 0 until height) {
                val rowStart = row * yRowStride
                for (col in 0 until width) {
                    nv21[outIndex++] = yBuffer.get(rowStart + col * yPixelStride)
                }
            }

            // Copy VU planes in NV21 order, also respecting strides.
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride
            for (row in 0 until chromaHeight) {
                val uRowStart = row * uRowStride
                val vRowStart = row * vRowStride
                for (col in 0 until chromaWidth) {
                    val uIndex = uRowStart + col * uPixelStride
                    val vIndex = vRowStart + col * vPixelStride
                    nv21[outIndex++] = vBuffer.get(vIndex)
                    nv21[outIndex++] = uBuffer.get(uIndex)
                }
            }

            val yuvMat = Mat(imageProxy.height + imageProxy.height / 2, imageProxy.width, org.opencv.core.CvType.CV_8UC1)
            yuvMat.put(0, 0, nv21)

            val bgrMat = Mat()
            Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_NV21)
            yuvMat.release()

            return bgrMat
        } catch (t: Throwable) {
            Log.e(TAG, "YUV conversion failed", t)
            return null
        }
    }

    private fun rotateMat(mat: Mat, degrees: Int): Mat {
        return when (degrees) {
            90 -> {
                val rotated = Mat()
                Core.transpose(mat, rotated)
                Core.flip(rotated, rotated, 1)
                rotated
            }
            180 -> {
                val rotated = Mat()
                Core.flip(mat, rotated, -1)
                rotated
            }
            270 -> {
                val rotated = Mat()
                Core.transpose(mat, rotated)
                Core.flip(rotated, rotated, 0)
                rotated
            }
            else -> mat.clone()
        }
    }

    private fun renderToSurface(bitmap: Bitmap) {
        synchronized(renderLock) {
            val s = surface ?: return
            if (!s.isValid) return
            try {
                val canvas = s.lockCanvas(null) ?: return
                // Scale bitmap to fill the surface
                val scaleX = canvas.width.toFloat() / bitmap.width
                val scaleY = canvas.height.toFloat() / bitmap.height
                val matrix = Matrix()
                matrix.setScale(scaleX, scaleY)
                canvas.drawBitmap(bitmap, matrix, null)
                s.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                Log.e(TAG, "Surface render error", t)
            }
        }
    }

    private fun feedFrameToEncoder(bgrMat: Mat) {
        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return

                // Convert BGR to YUV420 for encoder
                val yuvMat = Mat()
                Imgproc.cvtColor(bgrMat, yuvMat, Imgproc.COLOR_BGR2YUV_I420)
                val yuvBytes = ByteArray((yuvMat.total() * yuvMat.elemSize()).toInt())
                yuvMat.get(0, 0, yuvBytes)
                yuvMat.release()

                inputBuffer.clear()
                val bytesToWrite = minOf(yuvBytes.size, inputBuffer.capacity())
                inputBuffer.put(yuvBytes, 0, bytesToWrite)

                presentationTimeUs += 40_000 // ~25 fps
                codec.queueInputBuffer(inputIndex, 0, bytesToWrite, presentationTimeUs, 0)
            }

            drainEncoder(false)
        } catch (e: Exception) {
            Log.e(TAG, "Encoder feed error", e)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val startWaitNs = if (endOfStream) System.nanoTime() else 0L
        val maxWaitNs = 5_000_000_000L // 5s

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10000 else 0)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    videoTrackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            } else if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size > 0 && muxerStarted) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                }

                codec.releaseOutputBuffer(outputIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            } else {
                // INFO_TRY_AGAIN_LATER (-1)
                if (endOfStream) {
                    val waited = System.nanoTime() - startWaitNs
                    if (waited < maxWaitNs) {
                        continue
                    }
                }
                break
            }
        }
    }

    fun release() {
        stopRecording()
        stopPreview()
        faceProcessor.close()
        analysisExecutor.shutdown()
    }
}
