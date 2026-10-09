package dev.applewatchandroid.companion.apple_watch_companion

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var bridge: CompanionFlutterBridge? = null
    private var faceFiles: WatchFaceFilesAdapter? = null
    private var nativeText: NativeTextAdapter? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // The separate profiling APK must never initialize Binder or optical IO.
        if (BuildConfig.NATIVE_FACE_PROFILE_ISOLATED) return
        nativeText?.close()
        nativeText = NativeTextAdapter(flutterEngine)
        bridge?.close()
        bridge = CompanionFlutterBridge(this, flutterEngine)
        faceFiles?.close()
        faceFiles = WatchFaceFilesAdapter(this, flutterEngine)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        bridge?.close()
        bridge = null
        faceFiles?.close()
        faceFiles = null
        nativeText?.close()
        nativeText = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onResume() {
        super.onResume()
        bridge?.resume()
    }

    @Deprecated("Delegates document picker results to the Flutter adapter")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (faceFiles?.activityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        nativeText?.close()
        nativeText = null
        faceFiles?.close()
        faceFiles = null
        bridge?.close()
        bridge = null
        super.onDestroy()
    }
}
