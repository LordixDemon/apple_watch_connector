package dev.applewatchandroid.companion.apple_watch_companion

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Flutter engine adapter; Binder state and pending requests belong to its client. */
internal class CompanionFlutterBridge(private val activity: Activity, engine: FlutterEngine) {
    private var sink: EventChannel.EventSink? = null
    private var closed = false
    private val client = BridgeIpcClient(activity) { event -> sink?.success(event) }
    private val optical = OpticalDecoderClient(activity)
    private val methods = MethodChannel(engine.dartExecutor.binaryMessenger, "dev.applewatchandroid.companion/bridge")
    private val events = EventChannel(engine.dartExecutor.binaryMessenger, "dev.applewatchandroid.companion/events")

    init {
        methods.setMethodCallHandler(::handle)
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) {
                sink = eventSink
                client.emitState()
                client.start()
            }
            override fun onCancel(arguments: Any?) { sink = null }
        })
        client.start()
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        val opticalResult = object : BridgeResult {
            override fun success(value: Any?) = result.success(value)
            override fun error(code: String, message: String, details: Any?) = result.error(code, message, details)
        }
        when (call.method) {
            "startOpticalDecoder" -> { optical.start(call.argument<Int>("width") ?: 0, call.argument<Int>("height") ?: 0, opticalResult); return }
            "processOpticalFrame" -> { optical.frame(call.argument<ByteArray>("uv") ?: byteArrayOf(), opticalResult); return }
            "stopOpticalDecoder" -> { optical.stop(opticalResult); return }
            "discardOpticalCandidate" -> { optical.discard(call.argument<String>("opticalToken")); result.success(null); return }
        }
        if (call.method == "getState") { result.success(client.observedState); return }
        if (call.method in setOf("requestPhoneFlashPermission", "requestConnectionPermissions")) {
            try {
                activity.startActivity(Intent().setComponent(ComponentName(client.bridgePackage,
                    "${client.bridgePackage}.BridgePermissionActivity"))
                    .putExtra("scope", if (call.method == "requestPhoneFlashPermission") "flash" else "connection"))
                result.success(mapOf("status" to "OPENED"))
            } catch (_: SecurityException) { result.error("UNAVAILABLE", "Bridge permission screen denied", null) }
            catch (_: android.content.ActivityNotFoundException) { result.error("UNAVAILABLE", "Update Bridge first", null) }
            return
        }
        if (call.method !in setOf("pingWatch", "setActiveFace", "duplicateNativeFace", "removeNativeFace",
                "reorderNativeFaces", "updateNativeFace", "addNativeFace", "exportNativeFace",
                "beginNativeFaceImport", "appendNativeFaceImport", "finishNativeFaceImport", "cancelNativeFaceImport", "sendNotification",
                "refreshWatchState", "refreshFaceCollection", "stopPhonePing", "setWatchSetting", "setNativePigmentVisibility", "setNativeMonogram", "syncWifi",
                "connectWatch", "disconnectWatch", "beginPairing", "beginOpticalPairing", "replacePairOptically", "resumeSetup", "submitPin", "selectDiscoveredWatch", "activationResponse",
                "confirmSetup", "auditStockBond", "importStockBond", "alignStockIdentity", "probeStockReconnect")) {
            result.error("UNIMPLEMENTED", "This Watch operation has no verified implementation yet", null)
            return
        }
        if (call.method == "setNativeMonogram") {
            val args = call.arguments as? Map<*, *>
            val text = args?.get("text") as? String
            val revision = args?.get("revision") as? String
            if (text == null || text.length !in 1..5
                || revision == null || !revision.matches(Regex("[0-9a-f]{64}"))) {
                result.error("REJECTED", "Invalid monogram change", null)
                return
            }
        }
        if (call.method == "setNativePigmentVisibility") {
            val names = call.argument<Any>("expectedNames")
            val changes = call.argument<Any>("changes")
            val timestamp = call.argument<Any>("sourceTimestamp")
            val automaticNames = call.argument<Any>("expectedAutomaticNames")
            val automaticTimestamp = call.argument<Any>("automaticTimestamp")
            if (names !is List<*> || names.size > 1023 || names.any { it !is String }
                || changes !is Map<*, *> || changes.isEmpty() || changes.size > 1023
                || changes.any { (key, value) -> key !is String || value !is Boolean }
                || timestamp !is Double || !timestamp.isFinite()
                || automaticNames !is List<*> || automaticNames.size > 1023 || automaticNames.any { it !is String }
                || automaticTimestamp !is Double || !automaticTimestamp.isFinite()) {
                result.error("REJECTED", "Invalid color preference change", null)
                return
            }
        }
        val args = Bundle().apply {
            putString("method", call.method)
            for (key in listOf("faceId", "sourceFaceId", "title", "message", "sectionId", "sectionDisplayName",
                    "setting", "pairId", "epoch", "uploadId", "sha256", "baselineHash", "pin", "action", "discoveryToken", "revision", "text")) {
                call.argument<String>(key)?.let { putString(key, it) }
            }
            call.argument<Boolean>("value")?.let { putBoolean("value", it) }
            call.argument<List<String>>("faceIds")?.let { putStringArrayList("faceIds", ArrayList(it)) }
            if (call.method == "setNativePigmentVisibility") {
                putDouble("sourceTimestamp", call.argument<Double>("sourceTimestamp")!!)
                putStringArrayList("expectedNames", ArrayList(call.argument<List<String>>("expectedNames")!!))
                putDouble("automaticTimestamp", call.argument<Double>("automaticTimestamp")!!)
                putStringArrayList("expectedAutomaticNames", ArrayList(call.argument<List<String>>("expectedAutomaticNames")!!))
                putBundle("changes", Bundle().apply {
                    for ((key, value) in call.argument<Map<String, Boolean>>("changes")!!) putBoolean(key, value)
                })
            }
            for (key in listOf("configuration", "archive", "chunk")) {
                call.argument<ByteArray>(key)?.let { putByteArray(key, it) }
            }
            for (key in listOf("offset", "total")) call.argument<Int>(key)?.let { putInt(key, it) }
            call.argument<Int>("challengeId")?.let { putInt("challengeId", it) }
            call.argument<Map<String, String>>("credentials")?.let { values ->
                putBundle("credentials", Bundle().apply {
                    for ((key, value) in values) putString(key, value)
                })
            }
        }
        val opticalCode = if (call.method in setOf("beginOpticalPairing", "replacePairOptically")) {
            val token = call.argument<String>("opticalToken")
            val payload = token?.let { optical.take(it) }
            if (payload == null) {
                result.error("EXPIRED", "Scan the Watch again before pairing", null)
                return
            }
            args.putByteArray("opticalCode", payload)
            payload
        } else null
        try { client.send(3, args, object : BridgeResult {
            override fun success(value: Any?) = result.success(value)
            override fun error(code: String, message: String, details: Any?) = result.error(code, message, details)
        }) } finally {
            opticalCode?.fill(0)
            args.remove("opticalCode")
        }
    }

    fun resume() = client.resume()
    fun close() {
        if (closed) return
        closed = true
        sink = null
        client.close()
        optical.close()
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
    }
}
