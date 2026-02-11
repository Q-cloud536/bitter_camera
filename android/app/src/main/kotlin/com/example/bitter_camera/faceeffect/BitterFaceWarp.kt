package com.example.bitter_camera.faceeffect

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Port of the 3 bitter-face warping styles from main_algorithm.py.
 *
 * Each style takes an OpenCV Mat (BGR image), a 68-point landmark array
 * (float[68][2] in pixel coordinates), and an intensity parameter.
 *
 * The warping uses per-pixel remap grids (same approach as the Python code):
 *   1. Create meshgrid (gridX, gridY) spanning the full image
 *   2. Modify grid coordinates near face features
 *   3. Call Imgproc.remap() with bilinear interpolation
 */
object BitterFaceWarp {

    /**
     * Apply one of the 3 styles.
     * @param src   input image (BGR Mat, will NOT be modified)
     * @param pts   68-point landmarks, pts[i] = [x, y]
     * @param style 1, 2, or 3
     * @param intensity warping strength (default 1.2)
     * @return new Mat with the warped image
     */
    fun apply(src: Mat, pts: Array<FloatArray>, style: Int, intensity: Float = 1.2f): Mat {
        return when (style) {
            1 -> applyStyle1(src, pts, intensity)
            2 -> applyStyle2(src, pts, intensity)
            3 -> applyStyle3(src, pts, intensity)
            else -> src.clone()
        }
    }

    // ========== Helper: get boundary of points ==========
    private fun getBoundary(pts: Array<FloatArray>): FloatArray {
        var left = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        var top = Float.MAX_VALUE
        var bottom = Float.MIN_VALUE
        for (p in pts) {
            if (p[0] < left) left = p[0]
            if (p[0] > right) right = p[0]
            if (p[1] < top) top = p[1]
            if (p[1] > bottom) bottom = p[1]
        }
        return floatArrayOf(left - 1f, right + 1f, top - 1f, bottom + 1f)
    }

    // ========== Helper: distance function from style3 ==========
    private fun dFunc(x: Float, y: Float, k: Float, b: Float): Float {
        var dist = abs(k * x - y + b)
        while (dist > 3f) dist /= 2f
        return dist
    }

    // ========== Create meshgrid ==========
    private fun createMeshGrid(h: Int, w: Int): Pair<FloatArray, FloatArray> {
        val gridX = FloatArray(h * w)
        val gridY = FloatArray(h * w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                gridX[idx] = x.toFloat()
                gridY[idx] = y.toFloat()
            }
        }
        return Pair(gridX, gridY)
    }

    // ========== Remap helper ==========
    private fun doRemap(src: Mat, gridX: FloatArray, gridY: FloatArray, h: Int, w: Int): Mat {
        val mapX = Mat(h, w, CvType.CV_32FC1)
        val mapY = Mat(h, w, CvType.CV_32FC1)
        mapX.put(0, 0, gridX)
        mapY.put(0, 0, gridY)

        val dst = Mat()
        Imgproc.remap(src, dst, mapX, mapY, Imgproc.INTER_LINEAR)

        mapX.release()
        mapY.release()
        return dst
    }

    // ================================================================
    // Style 1: squeeze cheeks + eye/nose droop
    // Port of apply_style1() from main_algorithm.py lines 37-94
    // ================================================================
    private fun applyStyle1(img: Mat, points: Array<FloatArray>, intensity: Float): Mat {
        val leftEye = points.sliceArray(36..41)
        val rightEye = points.sliceArray(42..47)
        val nose = points.sliceArray(27..35)

        val (left, right, top, bottom) = getBoundary(points)
        val maxDist = ((right - left) / 2f).toInt()

        val h = img.rows()
        val w = img.cols()
        val (gridX, gridY) = createMeshGrid(h, w)

        // Left eye droop: push pixels down near left_eye[3] (index 39)
        run {
            val cx = leftEye[3][0]
            val cy = leftEye[3][1]
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                    if (dist < maxDist && x < cx) {
                        gridY[idx] = gridY[idx] - abs(
                            intensity * (x - cx) * (maxDist - dist) / maxDist
                        )
                    }
                }
            }
        }

        // Right eye droop: push pixels down near right_eye[0] (index 42)
        run {
            val cx = rightEye[0][0]
            val cy = rightEye[0][1]
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                    if (dist < maxDist && x > cx) {
                        gridY[idx] = gridY[idx] - abs(
                            intensity * (x - cx) * (maxDist - dist) / maxDist
                        )
                    }
                }
            }
        }

        // Nose region stretch
        run {
            // Bounding rect of nose points
            var nLeft = Float.MAX_VALUE; var nRight = Float.MIN_VALUE
            var nTop = Float.MAX_VALUE; var nBottom = Float.MIN_VALUE
            for (p in nose) {
                if (p[0] < nLeft) nLeft = p[0]
                if (p[0] > nRight) nRight = p[0]
                if (p[1] < nTop) nTop = p[1]
                if (p[1] > nBottom) nBottom = p[1]
            }
            val nw = nRight - nLeft
            val nh = nBottom - nTop
            val ncx = nLeft + nw / 2f
            val ncy = nTop + nh / 2f

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val dist = sqrt((x - ncx) * (x - ncx) + (y - ncy) * (y - ncy))
                    if (dist < maxDist && y > ncy && x > left && x < right) {
                        gridY[idx] = gridY[idx] - abs(
                            intensity * (y - ncy) * (maxDist - dist) / maxDist
                        )
                        // Left side of nose
                        if (x < ncx && (ncx - left) > 0.01f) {
                            gridX[idx] = gridX[idx] + abs(
                                intensity * (1f + (maxDist - dist) / maxDist) * (x - left) / (ncx - left)
                            )
                        }
                        // Right side of nose
                        if (x > ncx && (right - ncx) > 0.01f) {
                            gridX[idx] = gridX[idx] - abs(
                                intensity * (1f + (maxDist - dist) / maxDist) * (right - x) / (right - ncx)
                            )
                        }
                    }
                }
            }
        }

        return doRemap(img, gridX, gridY, h, w)
    }

    // ================================================================
    // Style 2: elongate chin + mouth/nose stretch
    // Port of apply_style2() from main_algorithm.py lines 96-161
    // ================================================================
    private fun applyStyle2(img: Mat, points: Array<FloatArray>, intensity: Float): Mat {
        val nose = points.sliceArray(27..35)
        val mouth = points.sliceArray(48..59)

        val (left, right, top, bottom) = getBoundary(points)
        val maxDist = ((right - left) / 2f).toInt()

        val h = img.rows()
        val w = img.cols()
        val (gridX, gridY) = createMeshGrid(h, w)

        // Nose horizontal stretch
        run {
            var sumX = 0f; var sumY = 0f
            for (p in nose) { sumX += p[0]; sumY += p[1] }
            val ncx = sumX / nose.size
            val ncy = sumY / nose.size
            val noseRadius = (maxDist * 0.4f).toInt()

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val dist = sqrt((x - ncx) * (x - ncx) + (y - ncy) * (y - ncy))
                    if (dist < noseRadius) {
                        val stretch = (noseRadius - dist) / noseRadius
                        if (x < ncx) {
                            gridX[idx] += intensity * (ncx - x) * stretch
                        } else {
                            gridX[idx] -= intensity * (x - ncx) * stretch
                        }
                    }
                }
            }
        }

        // Mouth horizontal + vertical stretch
        run {
            var sumX = 0f; var sumY = 0f
            for (p in mouth) { sumX += p[0]; sumY += p[1] }
            val mcx = sumX / mouth.size
            val mcy = sumY / mouth.size
            val mouthRadius = (maxDist * 0.8f).toInt()

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val dist = sqrt((x - mcx) * (x - mcx) + (y - mcy) * (y - mcy))
                    if (dist < mouthRadius) {
                        val stretch = (mouthRadius - dist) / mouthRadius

                        // Horizontal stretch
                        if (x < mcx) {
                            gridX[idx] += intensity * 1.0f * (mcx - x) * stretch
                        } else {
                            gridX[idx] -= intensity * 1.0f * (x - mcx) * stretch
                        }

                        // Vertical stretch
                        val vi = intensity * 0.7f
                        if (y < mcy) {
                            gridY[idx] += vi * (mcy - y) * stretch
                        } else {
                            gridY[idx] -= vi * (y - mcy) * stretch
                        }
                    }
                }
            }
        }

        return doRemap(img, gridX, gridY, h, w)
    }

    // ================================================================
    // Style 3: flatten + eyebrow raise + mouth ends stretch
    // Port of apply_style3() from main_algorithm.py lines 163-236
    // ================================================================
    private fun applyStyle3(img: Mat, points: Array<FloatArray>, intensity: Float): Mat {
        val leftBrow = points.sliceArray(17..21)
        val rightBrow = points.sliceArray(22..26)
        val mouth = points.sliceArray(48..59)

        val (left, right, top, bottom) = getBoundary(points)
        val maxDist = ((right - left) / 7f * 2f).toInt()

        val h = img.rows()
        val w = img.cols()
        val (gridX, gridY) = createMeshGrid(h, w)

        // Left eyebrow raise
        run {
            val cx = leftBrow[3][0]
            val cy = leftBrow[3][1]
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                    if (dist < maxDist && x > cx) {
                        gridY[idx] += abs(
                            intensity * (x - cx) * (maxDist - dist) / maxDist
                        )
                    }
                }
            }
        }

        // Right eyebrow raise
        run {
            val cx = rightBrow[1][0]
            val cy = rightBrow[1][1]
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                    if (dist < maxDist && x < cx) {
                        gridY[idx] += abs(
                            intensity * (x - cx) * (maxDist - dist) / maxDist
                        )
                    }
                }
            }
        }

        // Mouth ends vertical stretch
        run {
            val xl0 = mouth[0][0]
            val yl0 = mouth[0][1]
            val xr0 = mouth[6][0]
            val yr0 = mouth[6][1]

            val k: Float
            val b: Float
            if (abs(xr0 - xl0) < 0.01f) {
                k = 0f
                b = yl0
            } else {
                k = (yr0 - yl0) / (xr0 - xl0)
                b = yl0 - k * xl0
            }

            val maxDistMouth = (sqrt((yr0 - yl0) * (yr0 - yl0) + (xr0 - xl0) * (xr0 - xl0)) / 3f)
            val delta = maxDistMouth / 2f

            // Left mouth end
            val xl = xl0 + delta
            val yl = yl0 + delta * k
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val dist = sqrt((x - xl) * (x - xl) + (y - yl) * (y - yl))
                    if (dist < maxDistMouth) {
                        gridY[idx] -= (
                            intensity *
                            (maxDistMouth - dist) / maxDistMouth *
                            (y - yl) *
                            dFunc(x.toFloat(), y.toFloat(), k, b)
                        )
                    }
                }
            }

            // Right mouth end
            val xr = xr0 - delta
            val yr = yr0 - delta * k
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val dist = sqrt((x - xr) * (x - xr) + (y - yr) * (y - yr))
                    if (dist < maxDistMouth) {
                        gridY[idx] -= (
                            intensity *
                            (maxDistMouth - dist) / maxDistMouth *
                            (y - yr) *
                            dFunc(x.toFloat(), y.toFloat(), k, b)
                        )
                    }
                }
            }
        }

        return doRemap(img, gridX, gridY, h, w)
    }
}
