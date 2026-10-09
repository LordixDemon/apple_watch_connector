package dev.applewatchandroid.companion.apple_watch_companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.UUID

internal interface BridgeResult {
    fun success(value: Any?)
    fun error(code: String, message: String, details: Any?)
}

/** Owns only the signature-checked Binder subscription, receipts and deadlines. */
internal class BridgeIpcClient(private val context: Context,
    private val onEvent: (Map<String, Any?>) -> Unit) {
    val bridgePackage = "dev.applewatchandroid.bridge"
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var bridge: Messenger? = null
    private var bound = false
    private val pending = mutableMapOf<String, BridgeResult>()
    var observedState: Map<String, Any> = mapOf("connected" to false)
        private set
    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (closed) return
            val data = message.data
            if (data.getInt("version") != 1) return
            if (message.what == 102) {
                observedState = BridgeStateProjection.from(data)
                emitState()
                android.util.Log.i("WatchCompanion", "BRIDGE STATE connected=" + observedState["connected"]
                    + " batteryObserved=" + ((observedState["batteryLevel"] as? Int ?: -1) in 0..100)
                    + " aboutObserved=" + observedState.containsKey("aboutObservedAt")
                    + " batteryLevel=" + observedState["batteryLevel"]
                    + " charging=" + observedState["isCharging"]
                    + " observedAt=" + observedState["aboutObservedAt"]
                    + " availableBytes=" + observedState["availableStorageBytes"]
                    + " apps=" + observedState["numberOfApps"]
                    + " songs=" + observedState["numberOfSongs"]
                    + " photos=" + observedState["numberOfPhotos"])
                // Bridge issues one About read per IDS epoch; subscribing cannot duplicate it.
            } else if (message.what == 103) {
                val id = data.getString("requestId") ?: return
                val status = data.getString("status") ?: return
                android.util.Log.i("WatchCompanion", "OPERATION id=$id stage=$status")
                onEvent(mapOf("type" to "operation", "data" to mapOf(
                    "requestId" to id, "status" to status, "epoch" to data.getString("epoch"))))
            } else if (message.what == 101) {
                val id = data.getString("requestId") ?: return
                val status = data.getString("status") ?: return
                if (status == "OBSERVED") { observedState = BridgeStateProjection.from(data); emitState(); return }
                pending.remove(id)?.success(buildMap<String, Any?> {
                    put("requestId", id); put("status", status); put("faceId", data.getString("faceId"))
                    put("archive", data.getByteArray("archive")); put("uploadId", data.getString("uploadId"))
                    put("sha256", data.getString("sha256"))
                    for (key in listOf("total", "offset")) if (data.containsKey(key)) put(key, data.getInt(key))
                })
                if (status != "SUBSCRIBED") onEvent(mapOf(
                    "type" to "operation", "data" to mapOf("requestId" to id, "status" to status)))
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            if (name.packageName != bridgePackage || context.packageManager.checkSignatures(
                    context.packageName, bridgePackage) != PackageManager.SIGNATURE_MATCH) {
                disconnected("Bridge signature mismatch")
                return
            }
            bridge = Messenger(binder)
            send(1, Bundle(), null)
        }
        override fun onServiceDisconnected(name: ComponentName) { disconnected("Bridge disconnected") }
        override fun onNullBinding(name: ComponentName) { disconnected("Bridge unavailable") }
        override fun onBindingDied(name: ComponentName) {
            if (closed) return
            disconnected("Bridge updated")
            if (bound) { context.unbindService(this); bound = false }
            bindBridge()
        }
    }

    private fun bindBridge() {
        if (closed || bound) return
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(bridgePackage,
                "$bridgePackage.CompanionBridgeService")), connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) { disconnected("Companion IPC permission denied") }
    }

    private fun disconnected(reason: String) {
        android.util.Log.i("WatchCompanion", reason)
        bridge = null
        observedState = mapOf("connected" to false, "bridgeAvailable" to false)
        pending.values.toList().forEach { it.error("UNAVAILABLE", reason, null) }
        pending.clear()
        emitState()
    }

    fun emitState() {
        android.util.Log.i("WatchCompanion", "PHONE FIND STATE available=${observedState["phoneFindAvailable"]}" +
            " known=${observedState["phoneFindKnown"]} active=${observedState["phoneFindActive"]}" +
            " localProbe=${observedState["phoneFindLocalProbe"]} behavior=${observedState["phoneFindBehavior"]}" +
            " didPlay=${observedState["phoneFindDidPlay"]} observedAt=${observedState["phoneFindObservedAt"]}" +
            " flashPermission=${observedState["phoneFlashPermission"]}")
        onEvent(mapOf("type" to "connection", "data" to observedState))
    }

    fun send(what: Int, args: Bundle, result: BridgeResult?) {
        if (closed) { result?.error("UNAVAILABLE", "Companion closed", null); return }
        val target = bridge
        if (target == null) { result?.error("UNAVAILABLE", "Bridge is not connected", null); return }
        if (pending.size >= 32) { result?.error("BUSY", "IPC request queue is full", null); return }
        val id = UUID.randomUUID().toString()
        args.putInt("version", 1); args.putString("requestId", id)
        val message = Message.obtain(null, what).apply { data = args; replyTo = replies }
        if (result != null) pending[id] = result
        try { target.send(message) }
        catch (_: RemoteException) { disconnected("Bridge IPC failed"); return }
        if (result != null) main.postDelayed({
            pending.remove(id)?.error("TIMEOUT", "No Bridge receipt; Watch result is unknown", id)
        }, 5000)
    }

    fun start() = bindBridge()
    fun resume() { if (!closed && bridge != null) send(2, Bundle(), null) }
    fun close() {
        if (closed) return
        closed = true
        disconnected("Companion closed")
        if (bound) { context.unbindService(connection); bound = false }
        main.removeCallbacksAndMessages(null)
    }
}
