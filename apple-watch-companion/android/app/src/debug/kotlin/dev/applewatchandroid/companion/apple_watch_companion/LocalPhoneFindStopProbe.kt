package dev.applewatchandroid.companion.apple_watch_companion

import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import java.util.UUID

/** Private debug probe runs as the real Companion UID. Never starts a phone signal or a HAL. */
class LocalPhoneFindStopProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "dev.applewatchandroid.companion.PROBE_STOP_PHONE_FIND" ||
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
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
                if (command) putString("method", "stopPhonePing")
            }
            try { target?.send(Message.obtain(null, if (command) 3 else 2).apply {
                this.data = data; replyTo = reply
            }) } catch (_: RemoteException) { Log.i("PhoneFindProbe", "IPC unavailable"); finish() }
        }
        reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (finished || message.what != 101) return
                val data = message.data
                if (data.getInt("version") != 1 || data.getString("requestId") != requestId) return
                val status = data.getString("status")
                if (phase == 1) {
                    Log.i("PhoneFindProbe", "STOP receipt=$status; Watch applied not inferred")
                    if (status != "STOPPED") { finish(); return }
                    phase = 2; send(false)
                } else if (status == "OBSERVED") {
                    Log.i("PhoneFindProbe", "STATE phase=$phase available=${data.getBoolean("phoneFindAvailable")}" +
                        " known=${data.getBoolean("phoneFindKnown")} active=${data.getBoolean("phoneFindActive")}" +
                        " localProbe=${data.getBoolean("phoneFindLocalProbe")} behavior=${data.getInt("phoneFindBehavior", -1)}" +
                        " didPlay=${data.getBoolean("phoneFindDidPlay")} observedAt=${data.getLong("phoneFindObservedAt")}" +
                        " reason=${data.getString("phoneFindStopReason")}")
                    if (phase == 0) { phase = 1; send(true) } else finish()
                }
            }
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (finished) return
                if (name.packageName != bridgePackage || owner.packageManager.checkSignatures(
                        owner.packageName, bridgePackage) != PackageManager.SIGNATURE_MATCH) {
                    Log.i("PhoneFindProbe", "Signature mismatch"); finish(); return
                }
                target = Messenger(binder); send(false)
            }
            override fun onServiceDisconnected(name: ComponentName) { finish() }
            override fun onNullBinding(name: ComponentName) { finish() }
            override fun onBindingDied(name: ComponentName) { finish() }
        }
        main.postDelayed({ Log.i("PhoneFindProbe", "Timed out; no result inferred"); finish() }, 5000)
        try {
            bound = owner.bindService(Intent().setComponent(ComponentName(bridgePackage,
                "$bridgePackage.CompanionBridgeService")), connection, Context.BIND_AUTO_CREATE)
            if (!bound) finish()
        } catch (_: SecurityException) { Log.i("PhoneFindProbe", "Permission denied"); finish() }
    }
}
