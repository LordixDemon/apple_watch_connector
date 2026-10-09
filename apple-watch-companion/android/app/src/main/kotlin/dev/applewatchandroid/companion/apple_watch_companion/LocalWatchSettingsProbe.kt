package dev.applewatchandroid.companion.apple_watch_companion

import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import java.util.UUID

/** Private debug acceptance: real Companion Binder read → optional typed write → read. */
class LocalWatchSettingsProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "dev.applewatchandroid.companion.PROBE_WATCH_SETTINGS" ||
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val setting = intent.getStringExtra("setting")
        if (setting != null && (setting !in setOf("RIGHT_WRIST", "INVERT_SCREEN", "TIME_24_HOUR") ||
                !intent.hasExtra("value"))) return
        val owner = context.applicationContext
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val bridgePackage = "dev.applewatchandroid.bridge"
        var bound = false
        var finished = false
        var phase = 0
        var requestId = ""
        var target: Messenger? = null
        lateinit var connection: ServiceConnection
        lateinit var reply: Messenger
        fun finish() {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(null)
            if (bound) { try { owner.unbindService(connection) } catch (_: IllegalArgumentException) { } }
            pending.finish()
        }
        fun send(command: Boolean) {
            requestId = UUID.randomUUID().toString()
            val data = Bundle().apply {
                putInt("version", 1); putString("requestId", requestId)
                if (command) {
                    putString("method", "setWatchSetting"); putString("setting", setting)
                    putBoolean("value", intent.getBooleanExtra("value", false))
                }
            }
            try { target?.send(Message.obtain(null, if (command) 3 else 2).apply {
                this.data = data; replyTo = reply
            }) } catch (_: RemoteException) { Log.i("WatchSettingsProbe", "IPC unavailable"); finish() }
        }
        reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (finished || message.what != 101) return
                val data = message.data
                if (data.getInt("version") != 1 || data.getString("requestId") != requestId) return
                val status = data.getString("status")
                if (phase == 1) {
                    Log.i("WatchSettingsProbe", "WRITE receipt=$status requestId=$requestId; applied not inferred")
                    phase = 2; send(false)
                } else if (status == "OBSERVED") {
                    for (name in listOf("RIGHT_WRIST", "INVERT_SCREEN", "TIME_24_HOUR")) {
                        val key = "watchSetting_$name"
                        Log.i("WatchSettingsProbe", "STATE phase=$phase setting=$name known=${data.containsKey(key)}" +
                            " value=${if (data.containsKey(key)) data.getBoolean(key) else null}" +
                            " observedAt=${data.getLong("${key}_observedAt")}")
                    }
                    if (phase == 0 && setting != null && data.getBoolean("connected")) {
                        phase = 1; send(true)
                    } else finish()
                }
            }
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (finished) return
                if (name.packageName != bridgePackage || owner.packageManager.checkSignatures(
                        owner.packageName, bridgePackage) != PackageManager.SIGNATURE_MATCH) {
                    Log.i("WatchSettingsProbe", "Signature mismatch"); finish(); return
                }
                target = Messenger(binder); send(false)
            }
            override fun onServiceDisconnected(name: ComponentName) { finish() }
            override fun onNullBinding(name: ComponentName) { finish() }
            override fun onBindingDied(name: ComponentName) { finish() }
        }
        main.postDelayed({ Log.i("WatchSettingsProbe", "Probe deadline reached"); finish() }, 5000)
        try {
            bound = owner.bindService(Intent().setComponent(ComponentName(bridgePackage,
                "$bridgePackage.CompanionBridgeService")), connection, Context.BIND_AUTO_CREATE)
            if (!bound) finish()
        } catch (_: SecurityException) { Log.i("WatchSettingsProbe", "Permission denied"); finish() }
    }
}
