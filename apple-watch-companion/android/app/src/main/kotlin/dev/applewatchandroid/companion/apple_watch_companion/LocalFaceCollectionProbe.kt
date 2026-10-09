package dev.applewatchandroid.companion.apple_watch_companion

import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import java.util.UUID

/** Private debug probe runs as Companion UID, using the actual Flutter projection. */
class LocalFaceCollectionProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "dev.applewatchandroid.companion.PROBE_FACE_COLLECTION" ||
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val owner = context.applicationContext
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val bridgePackage = "dev.applewatchandroid.bridge"
        var bound = false
        var finished = false
        var phase = 0
        var id = ""
        var target: Messenger? = null
        lateinit var connection: ServiceConnection
        lateinit var reply: Messenger
        fun finish() {
            if (finished) return
            finished = true; main.removeCallbacksAndMessages(null)
            if (bound) try { owner.unbindService(connection) } catch (_: IllegalArgumentException) { }
            pending.finish()
        }
        fun send(command: Boolean) {
            id = UUID.randomUUID().toString()
            val data = Bundle().apply {
                putInt("version", 1); putString("requestId", id)
                if (command) {
                    val method = intent.getStringExtra("method") ?: "refreshFaceCollection"
                    if (method !in setOf("refreshFaceCollection", "setActiveFace", "duplicateNativeFace")) { finish(); return }
                    putString("method", method)
                    intent.getStringExtra("faceId")?.let { putString("faceId", it) }
                    intent.getStringExtra("sourceFaceId")?.let { putString("sourceFaceId", it) }
                }
            }
            try { target?.send(Message.obtain(null, if (command) 3 else 2).apply {
                this.data = data; replyTo = reply
            }) } catch (_: RemoteException) { Log.i("FaceCollectionProbe", "IPC unavailable"); finish() }
        }
        reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (finished || message.what != 101) return
                val data = message.data
                if (data.getInt("version") != 1 || data.getString("requestId") != id) return
                if (phase == 1) {
                    Log.i("FaceCollectionProbe", "COMMAND method=${intent.getStringExtra("method") ?: "refreshFaceCollection"} receipt=${data.getString("status")} requestId=$id faceId=${data.getString("faceId")}; applied not inferred")
                    finish(); return
                }
                if (data.getString("status") != "OBSERVED") { finish(); return }
                val projected = NativeFaceIpcProjection.from(data)
                Log.i("FaceCollectionProbe", "STATE connected=${data.getBoolean("connected")} projection=$projected")
                if (intent.getBooleanExtra("refresh", false) || intent.hasExtra("method")) { phase = 1; send(true) } else finish()
            }
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (finished) return
                if (name.packageName != bridgePackage || owner.packageManager.checkSignatures(
                        owner.packageName, bridgePackage) != PackageManager.SIGNATURE_MATCH) { finish(); return }
                target = Messenger(binder); send(false)
            }
            override fun onServiceDisconnected(name: ComponentName) { finish() }
            override fun onNullBinding(name: ComponentName) { finish() }
            override fun onBindingDied(name: ComponentName) { finish() }
        }
        main.postDelayed({ Log.i("FaceCollectionProbe", "Timed out; no result inferred"); finish() }, 5000)
        try {
            bound = owner.bindService(Intent().setComponent(ComponentName(bridgePackage,
                "$bridgePackage.CompanionBridgeService")), connection, Context.BIND_AUTO_CREATE)
            if (!bound) finish()
        } catch (_: SecurityException) { finish() }
    }
}
