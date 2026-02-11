package com.example.bitter_camera.faceeffect

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour

/**
 * Maps Google ML Kit FaceContour points to dlib's 68-landmark format.
 *
 * dlib 68-landmark layout:
 *   0-16  : jaw line (17 points, right ear -> chin -> left ear)
 *   17-21 : left eyebrow (5 points)
 *   22-26 : right eyebrow (5 points)
 *   27-30 : nose bridge (4 points, top to bottom)
 *   31-35 : nose bottom (5 points, left to right)
 *   36-41 : left eye (6 points, clockwise from left corner)
 *   42-47 : right eye (6 points, clockwise from left corner)
 *   48-59 : outer lip (12 points)
 *   60-67 : inner lip (8 points)
 *
 * ML Kit FaceContour provides:
 *   FACE            : 36 points (face oval)
 *   LEFT_EYEBROW_TOP/BOTTOM : 5 points each
 *   RIGHT_EYEBROW_TOP/BOTTOM: 5 points each
 *   LEFT_EYE        : 16 points
 *   RIGHT_EYE       : 16 points
 *   UPPER_LIP_TOP   : 11 points
 *   UPPER_LIP_BOTTOM: 9 points
 *   LOWER_LIP_TOP   : 9 points
 *   LOWER_LIP_BOTTOM: 9 points
 *   NOSE_BRIDGE      : 2 points
 *   NOSE_BOTTOM      : 3 points
 */
object FaceLandmarkMapper {

    /**
     * Convert a ML Kit [Face] to a 68-element array matching dlib landmark indices.
     * Returns null if required contours are missing.
     */
    fun mapTo68(face: Face): Array<FloatArray>? {
        val faceOval = face.getContour(FaceContour.FACE)?.points ?: return null
        val leftEyebrowTop = face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points ?: return null
        val rightEyebrowTop = face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points ?: return null
        val noseBridge = face.getContour(FaceContour.NOSE_BRIDGE)?.points ?: return null
        val noseBottom = face.getContour(FaceContour.NOSE_BOTTOM)?.points ?: return null
        val leftEye = face.getContour(FaceContour.LEFT_EYE)?.points ?: return null
        val rightEye = face.getContour(FaceContour.RIGHT_EYE)?.points ?: return null
        val upperLipTop = face.getContour(FaceContour.UPPER_LIP_TOP)?.points ?: return null
        val upperLipBottom = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: return null
        val lowerLipTop = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: return null
        val lowerLipBottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points ?: return null

        val pts = Array(68) { floatArrayOf(0f, 0f) }

        // --- 0-16: jaw line (17 points from face oval's 36 points) ---
        // Face oval goes clockwise: top-center -> right -> bottom -> left -> back to top
        // We need: right ear (roughly index ~0-2) -> chin -> left ear
        // ML Kit FACE contour: 36 points clockwise from top-center of face
        // We sample every ~2 points starting from index ~1 to get the jaw portion
        val jawIndices = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32)
        for (i in 0..16) {
            val idx = jawIndices[i].coerceIn(0, faceOval.size - 1)
            pts[i] = faceOval[idx].toArray()
        }

        // --- 17-21: left eyebrow (5 points) ---
        for (i in 0..4) {
            val idx = i.coerceIn(0, leftEyebrowTop.size - 1)
            pts[17 + i] = leftEyebrowTop[idx].toArray()
        }

        // --- 22-26: right eyebrow (5 points) ---
        for (i in 0..4) {
            val idx = i.coerceIn(0, rightEyebrowTop.size - 1)
            pts[22 + i] = rightEyebrowTop[idx].toArray()
        }

        // --- 27-30: nose bridge (4 points from ML Kit's 2 points + interpolation) ---
        if (noseBridge.size >= 2) {
            val top = noseBridge[0]
            val bottom = noseBridge[1]
            pts[27] = top.toArray()
            pts[28] = lerp(top, bottom, 0.33f)
            pts[29] = lerp(top, bottom, 0.67f)
            pts[30] = bottom.toArray()
        } else if (noseBridge.isNotEmpty()) {
            for (i in 27..30) pts[i] = noseBridge[0].toArray()
        }

        // --- 31-35: nose bottom (5 points from ML Kit's 3 points + interpolation) ---
        if (noseBottom.size >= 3) {
            val left = noseBottom[0]
            val center = noseBottom[1]
            val right = noseBottom[2]
            pts[31] = left.toArray()
            pts[32] = lerp(left, center, 0.5f)
            pts[33] = center.toArray()
            pts[34] = lerp(center, right, 0.5f)
            pts[35] = right.toArray()
        } else if (noseBottom.isNotEmpty()) {
            for (i in 31..35) pts[i] = noseBottom[0].toArray()
        }

        // --- 36-41: left eye (6 points from ML Kit's 16 points) ---
        // ML Kit LEFT_EYE: 16 points clockwise from left corner
        // dlib: 6 points clockwise from left corner
        // Sample: indices 0, 2, 4, 8, 10, 13 (approximate equal spacing around the eye)
        val leftEyeIndices = intArrayOf(0, 2, 4, 8, 10, 13)
        for (i in 0..5) {
            val idx = leftEyeIndices[i].coerceIn(0, leftEye.size - 1)
            pts[36 + i] = leftEye[idx].toArray()
        }

        // --- 42-47: right eye (6 points from ML Kit's 16 points) ---
        val rightEyeIndices = intArrayOf(0, 2, 4, 8, 10, 13)
        for (i in 0..5) {
            val idx = rightEyeIndices[i].coerceIn(0, rightEye.size - 1)
            pts[42 + i] = rightEye[idx].toArray()
        }

        // --- 48-59: outer lip (12 points) ---
        // ML Kit UPPER_LIP_TOP has 11 points, LOWER_LIP_BOTTOM has 9 points
        // dlib outer lip: 12 points clockwise from right corner
        // Upper lip top: points 0..10 (left to right)
        // Lower lip bottom: points 0..8 (left to right, need reverse for clockwise)
        if (upperLipTop.size >= 11 && lowerLipBottom.size >= 9) {
            // Right corner -> upper lip (left to right indices: 0,2,4,5,6,8,10)
            pts[48] = upperLipTop[0].toArray()       // right corner
            pts[49] = upperLipTop[1].toArray()
            pts[50] = upperLipTop[3].toArray()
            pts[51] = upperLipTop[5].toArray()        // top center
            pts[52] = upperLipTop[7].toArray()
            pts[53] = upperLipTop[9].toArray()
            pts[54] = upperLipTop[10].toArray()       // left corner
            // Lower lip (reversed for clockwise direction)
            pts[55] = lowerLipBottom[7].toArray()
            pts[56] = lowerLipBottom[5].toArray()
            pts[57] = lowerLipBottom[4].toArray()     // bottom center
            pts[58] = lowerLipBottom[3].toArray()
            pts[59] = lowerLipBottom[1].toArray()
        }

        // --- 60-67: inner lip (8 points) ---
        if (upperLipBottom.size >= 9 && lowerLipTop.size >= 9) {
            pts[60] = upperLipBottom[0].toArray()     // right corner
            pts[61] = upperLipBottom[2].toArray()
            pts[62] = upperLipBottom[4].toArray()     // top center
            pts[63] = upperLipBottom[6].toArray()
            pts[64] = upperLipBottom[8].toArray()     // left corner
            pts[65] = lowerLipTop[6].toArray()
            pts[66] = lowerLipTop[4].toArray()        // bottom center
            pts[67] = lowerLipTop[2].toArray()
        }

        return pts
    }

    private fun PointF.toArray() = floatArrayOf(x, y)

    private fun lerp(a: PointF, b: PointF, t: Float): FloatArray {
        return floatArrayOf(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t
        )
    }
}
