package dev.applewatchandroid.companion.apple_watch_companion

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import java.util.Locale

/** Local text processing, on a serial background queue; no Bridge/Binder access. */
internal class NativeTextAdapter(engine: FlutterEngine) {
    private val messenger = engine.dartExecutor.binaryMessenger
    private val channel = MethodChannel(messenger, "dev.applewatchandroid.companion/text",
        StandardMethodCodec.INSTANCE, messenger.makeBackgroundTaskQueue())

    init {
        channel.setMethodCallHandler { call, result ->
            if (call.method != "normalizeMonogram") {
                result.notImplemented()
                return@setMethodCallHandler
            }
            val input = (call.arguments as? Map<*, *>)?.get("text") as? String
            if (input == null) {
                result.error("INVALID", "Invalid monogram input", null)
                return@setMethodCallHandler
            }
            try {
                result.success(NativeMonogramTextNormalizer.normalize(input, Locale.getDefault()))
            } catch (_: IllegalArgumentException) {
                result.error("INVALID", "Invalid monogram input", null)
            }
        }
    }
    fun close() { channel.setMethodCallHandler(null) }
}
