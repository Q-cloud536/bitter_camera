package com.example.bitter_camera

import com.example.bitter_camera.faceeffect.NativeCameraPlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Register the native camera plugin for real-time face effects
        flutterEngine.plugins.add(NativeCameraPlugin())
    }
}
