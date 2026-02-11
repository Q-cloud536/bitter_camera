package com.example.bitter_camera.faceeffect

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Orchestrates ML Kit face detection and the bitter-face warping pipeline.
 *
 * Usage:
 *   val processor = FaceProcessor()
 *   val result = processor.processFrame(bgrMat, style = 1, intensity = 1.2f)
 *   // result is the warped Mat (or original if no face found)
 */
class FaceProcessor {

    private val detector: FaceDetector

    @Volatile
    var currentStyle: Int = 1
    @Volatile
    var currentIntensity: Float = 1.2f

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        detector = FaceDetection.getClient(options)
    }

    /**
     * Process a single BGR Mat: detect faces, apply warping, return result.
     * This is synchronous (blocks until ML Kit returns).
     */
    fun processFrame(bgrMat: Mat, style: Int = currentStyle, intensity: Float = currentIntensity): Mat {
        if (style == 0) return bgrMat // no effect

        try {
            // Convert BGR Mat to Bitmap for ML Kit
            val rgbMat = Mat()
            Imgproc.cvtColor(bgrMat, rgbMat, Imgproc.COLOR_BGR2RGB)
            val bitmap = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, bitmap)
            rgbMat.release()

            // Run ML Kit face detection synchronously
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faces = detectSync(inputImage)
            
            if (faces == null || faces.isEmpty()) {
                bitmap.recycle()
                return bgrMat
            }

            // Apply warping for each detected face
            var result = bgrMat.clone()
            for (face in faces) {
                val pts68 = FaceLandmarkMapper.mapTo68(face) ?: continue
                val warped = BitterFaceWarp.apply(result, pts68, style, intensity)
                if (warped !== result) {
                    result.release()
                    result = warped
                }
            }

            bitmap.recycle()
            return result
        } catch (t: Throwable) {
            android.util.Log.e("FaceProcessor", "processFrame failed", t)
            return bgrMat // Return original on error
        }
    }

    /**
     * Process a Bitmap directly: detect faces, apply warping, return result Bitmap.
     * Convenient for image import processing.
     */
    fun processBitmap(bitmap: Bitmap, style: Int = currentStyle, intensity: Float = currentIntensity): Bitmap {
        if (style == 0) return bitmap

        try {
            // Convert Bitmap to BGR Mat
            val rgbMat = Mat()
            Utils.bitmapToMat(bitmap, rgbMat)
            val bgrMat = Mat()
            Imgproc.cvtColor(rgbMat, bgrMat, Imgproc.COLOR_RGBA2BGR)
            rgbMat.release()

            val result = processFrame(bgrMat, style, intensity)

            // Convert result back to Bitmap
            val outputRgb = Mat()
            Imgproc.cvtColor(result, outputRgb, Imgproc.COLOR_BGR2RGB)
            val outputBitmap = Bitmap.createBitmap(outputRgb.cols(), outputRgb.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outputRgb, outputBitmap)

            bgrMat.release()
            if (result !== bgrMat) result.release()
            outputRgb.release()

            return outputBitmap
        } catch (t: Throwable) {
            android.util.Log.e("FaceProcessor", "processBitmap failed", t)
            return bitmap // Return original on error
        }
    }

    /**
     * Run ML Kit face detection synchronously (with timeout).
     */
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

        // Wait up to 2 seconds for detection
        latch.await(2, TimeUnit.SECONDS)
        return resultFaces
    }

    fun close() {
        detector.close()
    }
}
