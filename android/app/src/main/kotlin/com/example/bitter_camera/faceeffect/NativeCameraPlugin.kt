package com.example.bitter_camera.faceeffect

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Flutter MethodChannel plugin bridging the native camera pipeline to Dart.
 *
 * Channel name: "bitter_camera/camera"
 *
 * Methods:
 *   startPreview(cameraFacing: String) -> textureId: Long
 *   stopPreview() -> void
 *   switchCamera() -> textureId: Long
 *   capturePhoto() -> filePath: String
 *   startRecording() -> void
 *   stopRecording() -> filePath: String
 *   setEffectStyle(style: Int) -> void
 *   setEffectIntensity(intensity: Double) -> void
 */
class NativeCameraPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var cameraHandler: NativeCameraHandler? = null
    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var permissionBinding: ActivityPluginBinding? = null
    private var pendingPermissionResult: MethodChannel.Result? = null
    private val permissionRequestCode = 7117

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = binding
        channel = MethodChannel(binding.binaryMessenger, "bitter_camera/camera")
        channel!!.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        cameraHandler?.release()
        cameraHandler = null
        flutterPluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        permissionBinding = binding
        binding.addRequestPermissionsResultListener { requestCode, _, grantResults ->
            if (requestCode != permissionRequestCode) return@addRequestPermissionsResultListener false
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            pendingPermissionResult?.success(allGranted)
            pendingPermissionResult = null
            true
        }
    }

    override fun onDetachedFromActivity() {
        cameraHandler?.release()
        cameraHandler = null
        activity = null
        permissionBinding = null
        pendingPermissionResult = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        permissionBinding = binding
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
        permissionBinding = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "requestPermissions" -> {
                val act = activity
                if (act == null) {
                    result.success(false)
                    return
                }

                val perms = arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                )
                val missing = perms.filter {
                    ContextCompat.checkSelfPermission(act, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    result.success(true)
                    return
                }

                // Only allow one in-flight request.
                pendingPermissionResult?.success(false)
                pendingPermissionResult = result
                ActivityCompat.requestPermissions(act, missing.toTypedArray(), permissionRequestCode)
            }
            "startPreview" -> {
                val act = activity
                val binding = flutterPluginBinding
                if (act == null || binding == null) {
                    result.error("NO_ACTIVITY", "Activity not available", null)
                    return
                }

                // Ensure camera permission is granted.
                if (ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    result.error("PERMISSION_DENIED", "Camera permission not granted", null)
                    return
                }

                cameraHandler?.release()
                cameraHandler = NativeCameraHandler(act, binding.textureRegistry)

                val facing = call.argument<String>("cameraFacing") ?: "front"
                val isFront = facing == "front"

                try {
                    val textureId = cameraHandler!!.startPreview(isFront)
                    result.success(textureId)
                } catch (t: Throwable) {
                    cameraHandler?.release()
                    cameraHandler = null
                    result.error("START_PREVIEW_FAILED", t.message ?: "startPreview failed", null)
                }
            }

            "stopPreview" -> {
                cameraHandler?.stopPreview()
                result.success(null)
            }

            "switchCamera" -> {
                val handler = cameraHandler
                if (handler == null) {
                    result.error("NOT_STARTED", "Camera not started", null)
                    return
                }
                try {
                    val textureId = handler.switchCamera()
                    result.success(textureId)
                } catch (t: Throwable) {
                    result.error("SWITCH_CAMERA_FAILED", t.message ?: "switchCamera failed", null)
                }
            }

            "capturePhoto" -> {
                val handler = cameraHandler
                val act = activity
                if (handler == null || act == null) {
                    result.error("NOT_STARTED", "Camera not started", null)
                    return
                }
                val outputDir = act.cacheDir
                val path = handler.capturePhoto(outputDir)
                if (path != null) {
                    result.success(path)
                } else {
                    result.error("CAPTURE_FAILED", "No frame available", null)
                }
            }

            "startRecording" -> {
                val handler = cameraHandler
                val act = activity
                if (handler == null || act == null) {
                    result.error("NOT_STARTED", "Camera not started", null)
                    return
                }
                val outputPath = File(act.cacheDir,
                    "bitter_video_${System.currentTimeMillis()}.mp4").absolutePath
                handler.startRecording(outputPath)
                result.success(outputPath)
            }

            "stopRecording" -> {
                val handler = cameraHandler
                if (handler == null) {
                    result.error("NOT_STARTED", "Camera not started", null)
                    return
                }
                val path = handler.stopRecording()
                result.success(path)
            }

            "setEffectStyle" -> {
                val style = call.argument<Int>("style") ?: 1
                cameraHandler?.faceProcessor?.currentStyle = style
                result.success(null)
            }

            "setEffectIntensity" -> {
                val intensity = call.argument<Double>("intensity") ?: 1.2
                cameraHandler?.faceProcessor?.currentIntensity = intensity.toFloat()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }
}
