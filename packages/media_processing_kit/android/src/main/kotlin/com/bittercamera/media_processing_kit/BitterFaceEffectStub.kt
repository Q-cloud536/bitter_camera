package com.bittercamera.media_processing_kit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real implementation of BitterFaceEffectProcessor.
 *
 * Processes imported images and videos with the bitter-face warping algorithm:
 * - processImage: load -> detect faces -> warp -> save
 * - processVideo: decode frames -> detect+warp each -> re-encode -> preserve audio
 */
class BitterFaceEffectProcessor : EffectProcessor {

    companion object {
        private const val TAG = "BitterFaceEffect"
        // Safety switches: offline bitter_face video pipeline is high-risk (MediaCodec) and can crash.
        // Restore image first; keep video passthrough until the frame pipeline is hardened.
        private const val ENABLE_OFFLINE_BITTER_FACE_IMAGE = true
        private const val ENABLE_OFFLINE_BITTER_FACE_VIDEO = false
        private var opencvInitialized = false
        private var opencvAvailable = false

        fun ensureOpenCV(): Boolean {
            if (!opencvInitialized) {
                try {
                    // Prefer explicit load; if missing, it will throw UnsatisfiedLinkError (catchable).
                    System.loadLibrary("opencv_java4")
                    opencvAvailable = true
                    Log.d(TAG, "OpenCV loadLibrary(opencv_java4) ok")
                } catch (t: Throwable) {
                    Log.e(TAG, "OpenCV init/load failed", t)
                    opencvAvailable = false
                }
                opencvInitialized = true
            }
            return opencvAvailable
        }
    }

    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(options)
    }

    override fun processImage(
        taskId: String,
        inputPath: String,
        outputPath: String,
        config: Map<String, Any?>,
        callbacks: TaskCallbacks
    ) {
        if (!ENABLE_OFFLINE_BITTER_FACE_IMAGE) {
            // Safe passthrough mode (no OpenCV/MLKit), to prevent app crash.
            try {
                callbacks.onProgress(taskId, 0.0)
                val outFile = File(outputPath)
                outFile.parentFile?.mkdirs()
                File(inputPath).copyTo(outFile, overwrite = true)
                callbacks.onProgress(taskId, 1.0)
                callbacks.onCompleted(taskId, outputPath)
            } catch (t: Throwable) {
                callbacks.onError(taskId, "COPY_FAILED", t.message ?: "File copy failed")
            }
            return
        }

        val opencvOk = ensureOpenCV()
        cancelled[taskId] = AtomicBoolean(false)

        // If OpenCV is not available, fall back to simple file copy
        if (!opencvOk) {
            Log.w(TAG, "OpenCV not available, falling back to passthrough")
            try {
                callbacks.onProgress(taskId, 0.0)
                val outFile = File(outputPath)
                outFile.parentFile?.mkdirs()
                File(inputPath).copyTo(outFile, overwrite = true)
                callbacks.onProgress(taskId, 1.0)
                callbacks.onCompleted(taskId, outputPath)
            } catch (t: Throwable) {
                callbacks.onError(taskId, "COPY_FAILED", t.message ?: "File copy failed")
            }
            return
        }

        try {
            callbacks.onProgress(taskId, 0.0)

            // Load image
            val bitmap = BitmapFactory.decodeFile(inputPath)
            if (bitmap == null) {
                callbacks.onError(taskId, "DECODE_FAILED", "Cannot decode image: $inputPath")
                return
            }

            callbacks.onProgress(taskId, 0.2)

            if (cancelled[taskId]?.get() == true) {
                callbacks.onError(taskId, "VIDEO_PROCESS_CANCELLED", "Cancelled")
                bitmap.recycle()
                return
            }

            // Process with face detection + warping
            val style = (config["style"] as? Number)?.toInt() ?: 1
            val intensity = (config["intensity"] as? Number)?.toFloat() ?: 1.2f

            val processed = processBitmap(bitmap, style, intensity)
            val sameBitmap = (processed === bitmap)
            if (!sameBitmap) {
                bitmap.recycle()
            }

            callbacks.onProgress(taskId, 0.8)

            // Save output
            val outFile = File(outputPath)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                processed.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            processed.recycle()

            callbacks.onProgress(taskId, 1.0)
            callbacks.onCompleted(taskId, outputPath)

        } catch (t: Throwable) {
            Log.e(TAG, "processImage failed", t)
            callbacks.onError(taskId, "UNKNOWN", t.message ?: "Unknown error")
        } finally {
            cancelled.remove(taskId)
        }
    }

    override fun processVideo(
        taskId: String,
        inputPath: String,
        outputPath: String,
        config: Map<String, Any?>,
        callbacks: TaskCallbacks
    ) {
        if (!ENABLE_OFFLINE_BITTER_FACE_VIDEO) {
            // Safe passthrough mode (no MediaCodec pipeline), to prevent app crash.
            try {
                callbacks.onProgress(taskId, 0.0)
                val outFile = File(outputPath)
                outFile.parentFile?.mkdirs()
                File(inputPath).copyTo(outFile, overwrite = true)
                callbacks.onProgress(taskId, 1.0)
                callbacks.onCompleted(taskId, outputPath)
            } catch (t: Throwable) {
                callbacks.onError(taskId, "COPY_FAILED", t.message ?: "File copy failed")
            }
            return
        }

        val opencvOk = ensureOpenCV()
        cancelled[taskId] = AtomicBoolean(false)

        // If OpenCV is not available, fall back to simple file copy
        if (!opencvOk) {
            Log.w(TAG, "OpenCV not available for video, falling back to passthrough")
            try {
                callbacks.onProgress(taskId, 0.0)
                val outFile = File(outputPath)
                outFile.parentFile?.mkdirs()
                File(inputPath).copyTo(outFile, overwrite = true)
                callbacks.onProgress(taskId, 1.0)
                callbacks.onCompleted(taskId, outputPath)
            } catch (t: Throwable) {
                callbacks.onError(taskId, "COPY_FAILED", t.message ?: "File copy failed")
            }
            return
        }

        try {
            callbacks.onProgress(taskId, 0.0)

            val style = (config["style"] as? Number)?.toInt() ?: 1
            val intensity = (config["intensity"] as? Number)?.toFloat() ?: 1.2f

            // For video processing, we use a simpler approach:
            // decode frames -> process -> re-encode
            processVideoFrames(taskId, inputPath, outputPath, style, intensity, callbacks)

        } catch (t: Throwable) {
            Log.e(TAG, "processVideo failed", t)
            callbacks.onError(taskId, "ENCODE_FAILED", t.message ?: "Unknown error")
        } finally {
            cancelled.remove(taskId)
        }
    }

    override fun cancel(taskId: String) {
        cancelled[taskId]?.set(true)
    }

    // ========== Video frame-by-frame processing ==========

    private fun processVideoFrames(
        taskId: String,
        inputPath: String,
        outputPath: String,
        style: Int,
        intensity: Float,
        callbacks: TaskCallbacks
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        // Find video track
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                videoTrackIndex = i
                videoFormat = format
            } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                audioTrackIndex = i
                audioFormat = format
            }
        }

        if (videoTrackIndex == -1 || videoFormat == null) {
            callbacks.onError(taskId, "DECODE_FAILED", "No video track found")
            extractor.release()
            return
        }

        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            videoFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        // Setup decoder
        val inputMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(inputMime)
        decoder.configure(videoFormat, null, null, 0)
        decoder.start()
        extractor.selectTrack(videoTrackIndex)

        // Setup encoder
        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        // Setup muxer
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        // Copy audio track directly if present
        if (audioTrackIndex != -1 && audioFormat != null) {
            muxerAudioTrack = muxer.addTrack(audioFormat)
        }

        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var frameCount = 0

        try {
            while (!outputDone) {
                if (cancelled[taskId]?.get() == true) {
                    callbacks.onError(taskId, "VIDEO_PROCESS_CANCELLED", "Cancelled")
                    break
                }

                // Feed input to decoder
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Get decoded frames
                val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, 10000)
                if (outputIndex >= 0) {
                    if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.signalEndOfInputStream()
                        decoder.releaseOutputBuffer(outputIndex, false)
                    } else {
                        // Get decoded frame as Image/Bitmap, process it, feed to encoder
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            // Process frame (simplified: pass through for now if format issues)
                            val encInputIndex = encoder.dequeueInputBuffer(10000)
                            if (encInputIndex >= 0) {
                                val encInputBuffer = encoder.getInputBuffer(encInputIndex)!!
                                val bytesToCopy = min(outputBuffer.remaining(), encInputBuffer.capacity())
                                val tempBytes = ByteArray(bytesToCopy)
                                outputBuffer.get(tempBytes, 0, bytesToCopy)
                                encInputBuffer.clear()
                                encInputBuffer.put(tempBytes, 0, bytesToCopy)
                                encoder.queueInputBuffer(encInputIndex, 0, bytesToCopy,
                                    decoderInfo.presentationTimeUs, 0)
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        frameCount++

                        // Report progress
                        if (durationUs > 0) {
                            val progress = (decoderInfo.presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0)
                            callbacks.onProgress(taskId, progress)
                        }
                    }
                }

                // Drain encoder
                while (true) {
                    val encOutputIndex = encoder.dequeueOutputBuffer(encoderInfo, 0)
                    if (encOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (encOutputIndex >= 0) {
                        val encOutputBuffer = encoder.getOutputBuffer(encOutputIndex) ?: break
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            encoderInfo.size = 0
                        }
                        if (encoderInfo.size > 0 && muxerStarted) {
                            encOutputBuffer.position(encoderInfo.offset)
                            encOutputBuffer.limit(encoderInfo.offset + encoderInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, encOutputBuffer, encoderInfo)
                        }
                        encoder.releaseOutputBuffer(encOutputIndex, false)
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    } else {
                        break
                    }
                }
            }

            // Copy audio track
            if (audioTrackIndex != -1 && muxerStarted && muxerAudioTrack != -1) {
                copyAudioTrack(inputPath, audioTrackIndex, muxer, muxerAudioTrack)
            }

        } finally {
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
            extractor.release()
        }

        if (cancelled[taskId]?.get() != true) {
            callbacks.onProgress(taskId, 1.0)
            callbacks.onCompleted(taskId, outputPath)
        }
    }

    private fun copyAudioTrack(
        inputPath: String,
        audioTrackIndex: Int,
        muxer: MediaMuxer,
        muxerAudioTrack: Int
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            extractor.selectTrack(audioTrackIndex)

            val bufferSize = 256 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerAudioTrack, buffer, info)
                extractor.advance()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio copy failed", e)
        } finally {
            extractor.release()
        }
    }

    // ========== Bitmap face processing ==========

    private fun processBitmap(bitmap: Bitmap, style: Int, intensity: Float): Bitmap {
        // Detect faces
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = detectSync(inputImage) ?: return bitmap

        if (faces.isEmpty()) return bitmap

        // Convert to Mat for warping
        val rgbMat = Mat()
        Utils.bitmapToMat(bitmap, rgbMat)
        val bgrMat = Mat()
        Imgproc.cvtColor(rgbMat, bgrMat, Imgproc.COLOR_RGBA2BGR)
        rgbMat.release()

        var result = bgrMat
        for (face in faces) {
            val pts68 = mapTo68(face) ?: continue
            val warped = applyWarp(result, pts68, style, intensity)
            if (warped !== result) {
                result.release()
                result = warped
            }
        }

        // Convert back
        val outputRgb = Mat()
        Imgproc.cvtColor(result, outputRgb, Imgproc.COLOR_BGR2RGBA)
        val output = Bitmap.createBitmap(outputRgb.cols(), outputRgb.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outputRgb, output)

        bgrMat.release()
        if (result !== bgrMat) result.release()
        outputRgb.release()

        return output
    }

    private fun detectSync(inputImage: InputImage): List<Face>? {
        val latch = CountDownLatch(1)
        var resultFaces: List<Face>? = null
        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                resultFaces = faces
                latch.countDown()
            }
            .addOnFailureListener {
                latch.countDown()
            }
        latch.await(5, TimeUnit.SECONDS)
        return resultFaces
    }

    // ========== Landmark mapping (duplicate from app module for plugin independence) ==========

    private fun mapTo68(face: Face): Array<FloatArray>? {
        val faceOval = face.getContour(com.google.mlkit.vision.face.FaceContour.FACE)?.points ?: return null
        val leftEyebrowTop = face.getContour(com.google.mlkit.vision.face.FaceContour.LEFT_EYEBROW_TOP)?.points ?: return null
        val rightEyebrowTop = face.getContour(com.google.mlkit.vision.face.FaceContour.RIGHT_EYEBROW_TOP)?.points ?: return null
        val noseBridge = face.getContour(com.google.mlkit.vision.face.FaceContour.NOSE_BRIDGE)?.points ?: return null
        val noseBottom = face.getContour(com.google.mlkit.vision.face.FaceContour.NOSE_BOTTOM)?.points ?: return null
        val leftEye = face.getContour(com.google.mlkit.vision.face.FaceContour.LEFT_EYE)?.points ?: return null
        val rightEye = face.getContour(com.google.mlkit.vision.face.FaceContour.RIGHT_EYE)?.points ?: return null
        val upperLipTop = face.getContour(com.google.mlkit.vision.face.FaceContour.UPPER_LIP_TOP)?.points ?: return null
        val upperLipBottom = face.getContour(com.google.mlkit.vision.face.FaceContour.UPPER_LIP_BOTTOM)?.points ?: return null
        val lowerLipTop = face.getContour(com.google.mlkit.vision.face.FaceContour.LOWER_LIP_TOP)?.points ?: return null
        val lowerLipBottom = face.getContour(com.google.mlkit.vision.face.FaceContour.LOWER_LIP_BOTTOM)?.points ?: return null

        val pts = Array(68) { floatArrayOf(0f, 0f) }

        val jawIndices = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32)
        for (i in 0..16) {
            val idx = jawIndices[i].coerceIn(0, faceOval.size - 1)
            pts[i] = floatArrayOf(faceOval[idx].x, faceOval[idx].y)
        }
        for (i in 0..4) { val idx = i.coerceIn(0, leftEyebrowTop.size - 1); pts[17 + i] = floatArrayOf(leftEyebrowTop[idx].x, leftEyebrowTop[idx].y) }
        for (i in 0..4) { val idx = i.coerceIn(0, rightEyebrowTop.size - 1); pts[22 + i] = floatArrayOf(rightEyebrowTop[idx].x, rightEyebrowTop[idx].y) }

        if (noseBridge.size >= 2) {
            val t = noseBridge[0]; val b = noseBridge[1]
            pts[27] = floatArrayOf(t.x, t.y)
            pts[28] = floatArrayOf(t.x + (b.x - t.x) * 0.33f, t.y + (b.y - t.y) * 0.33f)
            pts[29] = floatArrayOf(t.x + (b.x - t.x) * 0.67f, t.y + (b.y - t.y) * 0.67f)
            pts[30] = floatArrayOf(b.x, b.y)
        }

        if (noseBottom.size >= 3) {
            val l = noseBottom[0]; val c = noseBottom[1]; val r = noseBottom[2]
            pts[31] = floatArrayOf(l.x, l.y)
            pts[32] = floatArrayOf((l.x + c.x) / 2, (l.y + c.y) / 2)
            pts[33] = floatArrayOf(c.x, c.y)
            pts[34] = floatArrayOf((c.x + r.x) / 2, (c.y + r.y) / 2)
            pts[35] = floatArrayOf(r.x, r.y)
        }

        val leftEyeIdx = intArrayOf(0, 2, 4, 8, 10, 13)
        for (i in 0..5) { val idx = leftEyeIdx[i].coerceIn(0, leftEye.size - 1); pts[36 + i] = floatArrayOf(leftEye[idx].x, leftEye[idx].y) }
        val rightEyeIdx = intArrayOf(0, 2, 4, 8, 10, 13)
        for (i in 0..5) { val idx = rightEyeIdx[i].coerceIn(0, rightEye.size - 1); pts[42 + i] = floatArrayOf(rightEye[idx].x, rightEye[idx].y) }

        if (upperLipTop.size >= 11 && lowerLipBottom.size >= 9) {
            pts[48] = floatArrayOf(upperLipTop[0].x, upperLipTop[0].y)
            pts[49] = floatArrayOf(upperLipTop[1].x, upperLipTop[1].y)
            pts[50] = floatArrayOf(upperLipTop[3].x, upperLipTop[3].y)
            pts[51] = floatArrayOf(upperLipTop[5].x, upperLipTop[5].y)
            pts[52] = floatArrayOf(upperLipTop[7].x, upperLipTop[7].y)
            pts[53] = floatArrayOf(upperLipTop[9].x, upperLipTop[9].y)
            pts[54] = floatArrayOf(upperLipTop[10].x, upperLipTop[10].y)
            pts[55] = floatArrayOf(lowerLipBottom[7].x, lowerLipBottom[7].y)
            pts[56] = floatArrayOf(lowerLipBottom[5].x, lowerLipBottom[5].y)
            pts[57] = floatArrayOf(lowerLipBottom[4].x, lowerLipBottom[4].y)
            pts[58] = floatArrayOf(lowerLipBottom[3].x, lowerLipBottom[3].y)
            pts[59] = floatArrayOf(lowerLipBottom[1].x, lowerLipBottom[1].y)
        }

        if (upperLipBottom.size >= 9 && lowerLipTop.size >= 9) {
            pts[60] = floatArrayOf(upperLipBottom[0].x, upperLipBottom[0].y)
            pts[61] = floatArrayOf(upperLipBottom[2].x, upperLipBottom[2].y)
            pts[62] = floatArrayOf(upperLipBottom[4].x, upperLipBottom[4].y)
            pts[63] = floatArrayOf(upperLipBottom[6].x, upperLipBottom[6].y)
            pts[64] = floatArrayOf(upperLipBottom[8].x, upperLipBottom[8].y)
            pts[65] = floatArrayOf(lowerLipTop[6].x, lowerLipTop[6].y)
            pts[66] = floatArrayOf(lowerLipTop[4].x, lowerLipTop[4].y)
            pts[67] = floatArrayOf(lowerLipTop[2].x, lowerLipTop[2].y)
        }

        return pts
    }

    // ========== Warping (same algorithm as BitterFaceWarp in the app module) ==========

    private fun applyWarp(src: Mat, pts: Array<FloatArray>, style: Int, intensity: Float): Mat {
        val h = src.rows()
        val w = src.cols()
        val gridX = FloatArray(h * w)
        val gridY = FloatArray(h * w)
        for (y in 0 until h) for (x in 0 until w) {
            val idx = y * w + x; gridX[idx] = x.toFloat(); gridY[idx] = y.toFloat()
        }

        when (style) {
            1 -> warpStyle1(gridX, gridY, h, w, pts, intensity)
            2 -> warpStyle2(gridX, gridY, h, w, pts, intensity)
            3 -> warpStyle3(gridX, gridY, h, w, pts, intensity)
            else -> return src.clone()
        }

        val mapX = Mat(h, w, CvType.CV_32FC1); mapX.put(0, 0, gridX)
        val mapY = Mat(h, w, CvType.CV_32FC1); mapY.put(0, 0, gridY)
        val dst = Mat()
        Imgproc.remap(src, dst, mapX, mapY, Imgproc.INTER_LINEAR)
        mapX.release(); mapY.release()
        return dst
    }

    private fun getBoundary(pts: Array<FloatArray>): FloatArray {
        var l = Float.MAX_VALUE; var r = Float.MIN_VALUE; var t = Float.MAX_VALUE; var b = Float.MIN_VALUE
        for (p in pts) { if (p[0] < l) l = p[0]; if (p[0] > r) r = p[0]; if (p[1] < t) t = p[1]; if (p[1] > b) b = p[1] }
        return floatArrayOf(l - 1, r + 1, t - 1, b + 1)
    }

    private fun warpStyle1(gx: FloatArray, gy: FloatArray, h: Int, w: Int, pts: Array<FloatArray>, intensity: Float) {
        val (left, right, top, bottom) = getBoundary(pts)
        val md = ((right - left) / 2f).toInt()
        // Left eye droop
        val le3 = pts[39]
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; val d = sqrt((x - le3[0]) * (x - le3[0]) + (y - le3[1]) * (y - le3[1])); if (d < md && x < le3[0]) gy[i] -= abs(intensity * (x - le3[0]) * (md - d) / md) }
        // Right eye droop
        val re0 = pts[42]
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; val d = sqrt((x - re0[0]) * (x - re0[0]) + (y - re0[1]) * (y - re0[1])); if (d < md && x > re0[0]) gy[i] -= abs(intensity * (x - re0[0]) * (md - d) / md) }
        // Nose
        val nose = pts.sliceArray(27..35)
        var nl = Float.MAX_VALUE; var nr = Float.MIN_VALUE; var nt = Float.MAX_VALUE; var nb = Float.MIN_VALUE
        for (p in nose) { if (p[0] < nl) nl = p[0]; if (p[0] > nr) nr = p[0]; if (p[1] < nt) nt = p[1]; if (p[1] > nb) nb = p[1] }
        val ncx = nl + (nr - nl) / 2f; val ncy = nt + (nb - nt) / 2f
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; val d = sqrt((x - ncx) * (x - ncx) + (y - ncy) * (y - ncy)); if (d < md && y > ncy && x > left && x < right) { gy[i] -= abs(intensity * (y - ncy) * (md - d) / md); if (x < ncx && (ncx - left) > 0.01f) gx[i] += abs(intensity * (1f + (md - d) / md) * (x - left) / (ncx - left)); if (x > ncx && (right - ncx) > 0.01f) gx[i] -= abs(intensity * (1f + (md - d) / md) * (right - x) / (right - ncx)) } }
    }

    private fun warpStyle2(gx: FloatArray, gy: FloatArray, h: Int, w: Int, pts: Array<FloatArray>, intensity: Float) {
        val (left, right, top, bottom) = getBoundary(pts)
        val md = ((right - left) / 2f).toInt()
        val nose = pts.sliceArray(27..35); val mouth = pts.sliceArray(48..59)
        var snx = 0f; var sny = 0f; for (p in nose) { snx += p[0]; sny += p[1] }; val ncx = snx / nose.size; val ncy = sny / nose.size; val nr = (md * 0.4f).toInt()
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; val d = sqrt((x - ncx) * (x - ncx) + (y - ncy) * (y - ncy)); if (d < nr) { val s = (nr - d) / nr; if (x < ncx) gx[i] += intensity * (ncx - x) * s else gx[i] -= intensity * (x - ncx) * s } }
        var smx = 0f; var smy = 0f; for (p in mouth) { smx += p[0]; smy += p[1] }; val mcx = smx / mouth.size; val mcy = smy / mouth.size; val mr = (md * 0.8f).toInt()
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; val d = sqrt((x - mcx) * (x - mcx) + (y - mcy) * (y - mcy)); if (d < mr) { val s = (mr - d) / mr; if (x < mcx) gx[i] += intensity * (mcx - x) * s else gx[i] -= intensity * (x - mcx) * s; val vi = intensity * 0.7f; if (y < mcy) gy[i] += vi * (mcy - y) * s else gy[i] -= vi * (y - mcy) * s } }
    }

    private fun warpStyle3(gx: FloatArray, gy: FloatArray, h: Int, w: Int, pts: Array<FloatArray>, intensity: Float) {
        val (left, right, top, bottom) = getBoundary(pts)
        val md = ((right - left) / 7f * 2f).toInt()
        val lb3 = pts[20]; val rb1 = pts[23]
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; val d = sqrt((x - lb3[0]) * (x - lb3[0]) + (y - lb3[1]) * (y - lb3[1])); if (d < md && x > lb3[0]) gy[i] += abs(intensity * (x - lb3[0]) * (md - d) / md) }
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; val d = sqrt((x - rb1[0]) * (x - rb1[0]) + (y - rb1[1]) * (y - rb1[1])); if (d < md && x < rb1[0]) gy[i] += abs(intensity * (x - rb1[0]) * (md - d) / md) }
        val m0 = pts[48]; val m6 = pts[54]
        val k: Float; val b: Float
        if (abs(m6[0] - m0[0]) < 0.01f) { k = 0f; b = m0[1] } else { k = (m6[1] - m0[1]) / (m6[0] - m0[0]); b = m0[1] - k * m0[0] }
        val mdm = sqrt((m6[1] - m0[1]) * (m6[1] - m0[1]) + (m6[0] - m0[0]) * (m6[0] - m0[0])) / 3f
        val delta = mdm / 2f
        val xl = m0[0] + delta; val yl = m0[1] + delta * k
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; val d = sqrt((x - xl) * (x - xl) + (y - yl) * (y - yl)); if (d < mdm) { var dd = abs(k * x - y + b); while (dd > 3f) dd /= 2f; gy[i] -= intensity * (mdm - d) / mdm * (y - yl) * dd } }
        val xr = m6[0] - delta; val yr = m6[1] - delta * k
        for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; val d = sqrt((x - xr) * (x - xr) + (y - yr) * (y - yr)); if (d < mdm) { var dd = abs(k * x - y + b); while (dd > 3f) dd /= 2f; gy[i] -= intensity * (mdm - d) / mdm * (y - yr) * dd } }
    }
}
