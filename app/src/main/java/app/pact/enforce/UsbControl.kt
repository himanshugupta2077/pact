package app.pact.enforce

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import app.pact.PactApp
import app.pact.data.FocusStore

/**
 * Commands from the Mac over adb (tools/pact-unlock). The receiver needs the DUMP
 * permission, which the adb shell holds and other apps only get when a computer grants it.
 * Unlocking also needs the phone plugged in with USB debugging on.
 */
class UsbControl : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PactApp
        val reply = when (intent.action) {
            ACTION_UNLOCK -> {
                if (!usbConnected(context)) {
                    resultData = "refused: the phone is not plugged in with USB debugging on"
                    return
                }
                val minutes = intent.getIntExtra("minutes", 15)
                app.lock.unlock(minutes)
                "unlocked for ${app.lock.remainingMs() / 60_000L + 1} min"
            }
            ACTION_RESET_TODAY -> {
                if (!usbConnected(context)) {
                    resultData = "refused: the phone is not plugged in with USB debugging on"
                    return
                }
                val cleared = app.usage.resetToday()
                if (cleared == null) {
                    "usage access is off; nothing reset"
                } else {
                    app.engine.refreshUsage(force = true)
                    "today's time reset ($cleared apps cleared)"
                }
            }
            ACTION_ALLOW_SITE -> {
                if (!usbConnected(context)) {
                    resultData = "refused: the phone is not plugged in with USB debugging on"
                    return
                }
                val hosts = intent.getStringExtra("sites").orEmpty().split(',')
                    .mapNotNull { FocusStore.hostOf(it) }
                for (host in hosts) app.focus.removeSite(host, bypassLock = true)
                "allowed ${hosts.joinToString(", ").ifEmpty { "nothing" }}; " +
                    "blocked now: ${app.focus.blockedSites.value.sorted().joinToString(", ")}"
            }
            ACTION_LOCK -> {
                app.lock.lock()
                "locked"
            }
            ACTION_RELEASE -> {
                PactAdmin.release(context)
                "device admin off; adb uninstall app.pact works now"
            }
            ACTION_STATUS -> buildString {
                val left = app.lock.remainingMs()
                append(if (left > 0L) "unlocked, ${left / 60_000L + 1} min left" else "locked")
                append("; device admin ")
                append(if (PactAdmin.isActive(context)) "on" else "off")
            }
            else -> return
        }
        Log.i("PactUsb", reply)
        resultData = reply
    }

    private fun usbConnected(context: Context): Boolean {
        val battery = context.applicationContext
            .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val adb = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        return plugged != 0 && adb == 1
    }

    companion object {
        const val ACTION_UNLOCK = "app.pact.UNLOCK"
        const val ACTION_RESET_TODAY = "app.pact.RESET_TODAY"
        const val ACTION_ALLOW_SITE = "app.pact.ALLOW_SITE"
        const val ACTION_LOCK = "app.pact.LOCK"
        const val ACTION_RELEASE = "app.pact.RELEASE_ADMIN"
        const val ACTION_STATUS = "app.pact.STATUS"
    }
}
