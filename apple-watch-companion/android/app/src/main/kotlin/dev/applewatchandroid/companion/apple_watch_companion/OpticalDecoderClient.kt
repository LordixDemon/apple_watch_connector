package dev.applewatchandroid.companion.apple_watch_companion

import android.content.*
import android.os.*
import java.util.Arrays
import java.util.UUID

/** Serial private worker IPC. Flutter receives public identity and a short-lived
 * opaque handle; optical key bytes never become a Dart string or UI argument. */
internal class OpticalDecoderClient(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var endpoint: Messenger? = null
    private var bound = false
    private var closed = false
    private var request = 0
    private var pending: Pair<Int, BridgeResult>? = null
    private var opening: Bundle? = null
    private var candidate: ByteArray? = null
    private var token: String? = null
    private var expires = 0L
    private val timeout = Runnable { failed() }

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val code = message.data.getByteArray("code")
            val waiting = pending
            try {
                if (closed || message.what != 101 || waiting?.first != message.data.getInt("request")) return
                pending = null; main.removeCallbacks(timeout)
                if (!message.data.getBoolean("ok")) {
                    waiting.second.error("OPTICAL_FAILED", "The optical reader could not process this frame", null)
                    return
                }
                val value = mutableMapOf<String, Any>("frames" to message.data.getInt("frames"), "recognized" to false)
                if (code != null) {
                    val identity = publicIdentity(code)
                    clearCandidate()
                    candidate = code.clone(); token = UUID.randomUUID().toString()
                    expires = SystemClock.elapsedRealtime() + 120000
                    value["recognized"] = true; value["token"] = token!!; value["advertisedName"] = identity
                    val expiringToken = token
                    main.postDelayed({ if (token == expiringToken) clearCandidate() }, 120000)
                    android.util.Log.i("WatchOptical", "Recognition complete; frames=${value["frames"]}; code/key logged=false")
                }
                waiting.second.success(value)
            } catch (_: Exception) {
                waiting?.second?.error("OPTICAL_FAILED", "Invalid optical code", null)
                pending = null; clearCandidate()
            } finally { code?.let { Arrays.fill(it, 0) } }
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            endpoint = Messenger(binder)
            opening?.let { opening = null; dispatch(1, it) }
        }
        override fun onServiceDisconnected(name: ComponentName) = failed()
        override fun onBindingDied(name: ComponentName) = failed()
        override fun onNullBinding(name: ComponentName) = failed()
    }

    fun start(width: Int, height: Int, result: BridgeResult) {
        if (closed || pending != null) { result.error("BUSY", "Optical reader is busy", null); return }
        if (width < 16 || width > 960 || width % 16 != 0 || height < 16 || height > 540) {
            result.error("UNSUPPORTED", "Unsupported optical camera dimensions", null); return
        }
        clearCandidate()
        val args = Bundle().apply { putInt("width", width); putInt("height", height) }
        begin(result)
        if (endpoint != null) dispatch(1, args)
        else {
            if (bound) { context.unbindService(connection); bound = false }
            opening = args
            bound = context.bindService(Intent(context, OpticalDecoderService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) failed()
        }
    }

    fun frame(uv: ByteArray, result: BridgeResult) {
        if (closed || endpoint == null || pending != null) { result.error("BUSY", "Optical reader is unavailable", null); return }
        if (uv.size > 960 * 540 * 2) { result.error("UNSUPPORTED", "Optical frame is too large", null); return }
        begin(result); dispatch(2, Bundle().apply { putByteArray("uv", uv) })
    }

    fun stop(result: BridgeResult) {
        if (closed || pending != null) { result.error("BUSY", "Optical reader is busy", null); return }
        if (endpoint == null) { result.success(null); return }
        begin(result); dispatch(3, Bundle())
    }

    fun take(handle: String): ByteArray? {
        if (closed || handle != token) return null
        if (SystemClock.elapsedRealtime() >= expires) { clearCandidate(); return null }
        val result = candidate; candidate = null; token = null; expires = 0
        return result
    }

    fun discard(handle: String?) { if (handle != null && handle == token) clearCandidate() }

    private fun begin(result: BridgeResult) {
        request++; pending = request to result
        main.postDelayed(timeout, 10000)
    }
    private fun dispatch(what: Int, args: Bundle) {
        args.putInt("request", request)
        try { endpoint?.send(Message.obtain(null, what).apply { data = args; replyTo = replies }) }
        catch (_: RemoteException) { failed() }
    }
    private fun failed() {
        endpoint = null; opening = null
        val waiting = pending; pending = null
        main.removeCallbacksAndMessages(null)
        clearCandidate()
        if (bound) { context.unbindService(connection); bound = false }
        waiting?.second?.error("OPTICAL_UNAVAILABLE", "The optical reader stopped; retry scanning", null)
    }
    private fun clearCandidate() { candidate?.let { Arrays.fill(it, 0) }; candidate = null; token = null; expires = 0 }
    fun close() {
        if (closed) return
        closed = true; failed()
    }

    private fun publicIdentity(code: ByteArray): String {
        require(code.size == 110)
        val fields = mutableListOf<String>(); var start = 0; var at = 0
        while (at + 1 < code.size && fields.size < 3) {
            if (code[at] == '-'.code.toByte() && code[at + 1] == '-'.code.toByte()) {
                require((start until at).all { (code[it].toInt() and 255) in 33..126 })
                fields.add(String(code, start, at - start, Charsets.US_ASCII)); at += 2; start = at
            } else at++
        }
        require(fields.size == 3 && fields[0] == "4" && fields[1].toIntOrNull() != null
            && fields[2].length == 8 && fields[2].take(5).all { it in '0'..'9' })
        return fields[2]
    }
}
