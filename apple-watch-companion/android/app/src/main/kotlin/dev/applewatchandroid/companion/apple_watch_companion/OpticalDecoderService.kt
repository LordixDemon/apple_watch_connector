package dev.applewatchandroid.companion.apple_watch_companion

import android.app.Service
import android.content.Intent
import android.os.*
import java.security.MessageDigest
import java.util.Arrays

/** Non-exported worker process. Neither a native fault nor decoding work can
 * block Flutter's UI process. Replies containing code bytes stay inside this UID. */
class OpticalDecoderService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var endpoint: Messenger
    private var width = 0
    private var height = 0
    private var frames = 0
    private var active = false

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("OpticalReader").apply { start() }
        handler = object : Handler(worker.looper) {
            override fun handleMessage(message: Message) {
                val output = Bundle().apply { putInt("request", message.data.getInt("request")) }
                var uv: ByteArray? = null
                var payload: ByteArray? = null
                try {
                    when (message.what) {
                        1 -> {
                            if (active) OpticalNativeReader.destroy()
                            active = false
                            width = message.data.getInt("width"); height = message.data.getInt("height"); frames = 0
                            val image = assets.open("optical/visual-pairing-23G71.bin").use { it.readBytes() }
                            check(MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
                                == "b4f744035e6f82f1a83808d310be9e0f02f4e2d97355ae063aab418066db1081")
                            active = OpticalNativeReader.initialize(image, width, height)
                            check(active)
                        }
                        2 -> {
                            check(active && frames < 90)
                            uv = message.data.getByteArray("uv") ?: error("Missing frame")
                            check(uv.size == width * height * 2)
                            payload = OpticalNativeReader.process(uv)
                            frames++
                            output.putInt("frames", frames)
                            if (payload != null) {
                                check(payload.size == 110)
                                output.putByteArray("code", payload)
                                OpticalNativeReader.destroy(); active = false
                            }
                        }
                        3 -> { if (active) OpticalNativeReader.destroy(); active = false }
                        else -> error("Unknown optical operation")
                    }
                    output.putBoolean("ok", true)
                } catch (_: Exception) {
                    if (active) OpticalNativeReader.destroy()
                    active = false
                    output.putBoolean("ok", false)
                }
                try { message.replyTo?.send(Message.obtain(null, 101).apply { data = output }) }
                catch (_: RemoteException) { if (active) OpticalNativeReader.destroy(); active = false }
                finally { uv?.let { Arrays.fill(it, 0) }; payload?.let { Arrays.fill(it, 0) } }
            }
        }
        endpoint = Messenger(handler)
    }
    override fun onBind(intent: Intent): IBinder = endpoint.binder
    override fun onDestroy() {
        handler.post { if (active) OpticalNativeReader.destroy(); active = false; worker.quitSafely() }
        super.onDestroy()
    }
}

internal object OpticalNativeReader {
    init { System.loadLibrary("optical_reader") }
    external fun initialize(image: ByteArray, width: Int, height: Int): Boolean
    external fun process(uv: ByteArray): ByteArray?
    external fun destroy()
}
