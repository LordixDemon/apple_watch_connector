package dev.applewatchandroid.companion.apple_watch_companion

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android's document picker saves to the user's chosen provider, including cloud storage. */
internal class WatchFaceFilesAdapter(private val activity: Activity, engine: FlutterEngine) {
    private val channel = MethodChannel(engine.dartExecutor.binaryMessenger, "dev.applewatchandroid.companion/face_files")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var pending: MethodChannel.Result? = null
    private var bytes: ByteArray? = null
    private var activeWrite: ActiveWrite? = null

    private class ActiveWrite(val result: MethodChannel.Result) {
        val completed = AtomicBoolean(false)
    }

    init {
        channel.setMethodCallHandler { call, result ->
            if (call.method != "save") { result.notImplemented(); return@setMethodCallHandler }
            if (pending != null || activeWrite != null) {
                result.error("BUSY", "A file dialog is already open", null); return@setMethodCallHandler
            }
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
        val payload = bytes
        pending = null
        bytes = null
        val uri = data?.data
        if (status != Activity.RESULT_OK || uri == null || payload == null) {
            payload?.fill(0)
            result.success(false)
            return true
        }
        val write = ActiveWrite(result)
        activeWrite = write
        try {
            io.execute {
                var saved = false
                try {
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload) }
                        ?: error("Cannot write selected document")
                    saved = true
                } catch (_: Exception) {
                    saved = false
                } finally {
                    payload.fill(0)
                }
                main.post {
                    if (activeWrite === write) activeWrite = null
                    if (write.completed.compareAndSet(false, true)) {
                        if (saved) result.success(true)
                        else result.error("SAVE_FAILED", "Could not save the Watch face file", null)
                    }
                }
            }
        } catch (_: Exception) {
            payload.fill(0)
            if (activeWrite === write) activeWrite = null
            if (write.completed.compareAndSet(false, true)) {
                result.error("SAVE_FAILED", "Could not save the Watch face file", null)
            }
        }
        return true
    }

    private fun finishError(message: String) {
        pending?.error("UNAVAILABLE", message, null)
        bytes?.fill(0)
        bytes = null
        pending = null
        val write = activeWrite
        activeWrite = null
        if (write != null && write.completed.compareAndSet(false, true)) {
            write.result.error("UNAVAILABLE", message, null)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        finishError("File dialog closed")
        channel.setMethodCallHandler(null)
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    companion object { private const val REQUEST = 4817 }
}
