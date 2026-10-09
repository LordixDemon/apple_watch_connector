package dev.applewatchandroid.companion.apple_watch_companion

import android.app.Activity
import android.content.Intent
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/** Android's document picker saves to the user's chosen provider, including cloud storage. */
internal class WatchFaceFilesAdapter(private val activity: Activity, engine: FlutterEngine) {
    private val channel = MethodChannel(engine.dartExecutor.binaryMessenger, "dev.applewatchandroid.companion/face_files")
    private var pending: MethodChannel.Result? = null
    private var bytes: ByteArray? = null
    init {
        channel.setMethodCallHandler { call, result ->
            if (call.method != "save") { result.notImplemented(); return@setMethodCallHandler }
            if (pending != null) { result.error("BUSY", "A file dialog is already open", null); return@setMethodCallHandler }
            val payload = call.argument<ByteArray>("bytes")
            val name = call.argument<String>("name")
            if (payload == null || payload.size > 16 * 1024 * 1024 || name == null || name.contains('/') || name.contains('\\')) {
                result.error("INVALID", "Invalid Watch face file", null); return@setMethodCallHandler
            }
            pending = result; bytes = payload.copyOf()
            try {
                activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                    // A ZIP MIME type makes DocumentsUI append .zip to .watchface/.watchlayout.
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, name), REQUEST)
            } catch (_: Exception) { finishError("Could not open the save dialog") }
        }
    }
    fun activityResult(request: Int, status: Int, data: Intent?): Boolean {
        if (request != REQUEST) return false
        val result = pending ?: return true
        try {
            val uri = data?.data
            if (status != Activity.RESULT_OK || uri == null) result.success(false)
            else {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes ?: error("Missing file")) }
                    ?: error("Cannot write selected document")
                result.success(true)
            }
        } catch (_: Exception) { result.error("SAVE_FAILED", "Could not save the Watch face file", null) }
        finally { bytes?.fill(0); bytes = null; pending = null }
        return true
    }
    private fun finishError(message: String) {
        pending?.error("UNAVAILABLE", message, null); bytes?.fill(0); bytes = null; pending = null
    }
    fun close() { finishError("File dialog closed"); channel.setMethodCallHandler(null) }
    companion object { private const val REQUEST = 4817 }
}
