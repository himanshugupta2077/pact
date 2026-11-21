package app.pact.enforce

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import app.pact.PactApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Banking and UPI apps refuse to run while an accessibility service, Developer options or
 * USB debugging is on. While a payment app is in front, switch all of them off for real and
 * put the exact earlier state back once it leaves. Same idea as Warden and IMD, own code.
 *
 * The snapshot is saved before anything changes, so a crash or reboot restores it on the
 * next start. Limits keep it from becoming a way around Pact: a blocked app ends it at
 * once, a session lasts at most 20 minutes, and a day at most 30.
 */
object BankMode {
    private const val TAG = "PactBank"

    private val SYSTEM_SETTINGS = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )
    private const val SESSION_CAP_MS = 20 * 60_000L
    private const val DAILY_BUDGET_MS = 30 * 60_000L
    private const val COOLDOWN_MS = 10 * 60_000L
    private const val AWAY_GRACE_MS = 15_000L
    private const val STALE_MS = SESSION_CAP_MS + 2 * 60_000L

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    private const val KEY_STARTED = "started"
    private const val KEY_PACKAGE = "package"
    private const val KEY_A11Y_SERVICES = "a11y_services"
    private const val KEY_A11Y_ENABLED = "a11y_enabled"
    private const val KEY_DEV = "dev"
    private const val KEY_ADB = "adb"
    private const val KEY_ADB_WIFI = "adb_wifi"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
    private const val KEY_DAY = "day"
    private const val KEY_DAY_USED = "day_used"
    private const val KEY_KEEP_ADB = "keep_adb"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("pact_bank", Context.MODE_PRIVATE)

    fun isActive(context: Context): Boolean = prefs(context).getLong(KEY_STARTED, 0L) > 0L

    /** Called from the accessibility service. Returns true when it switched things off. */
    @Synchronized
    fun enter(context: Context, packageName: String): Boolean {
        if (!SecureSettings.canWrite(context)) return false
        val prefs = prefs(context)
        if (prefs.getLong(KEY_STARTED, 0L) > 0L) return false
        val now = System.currentTimeMillis()
        if (now < prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)) return false
        if (usedToday(prefs, now) >= DAILY_BUDGET_MS) return false

        val resolver = context.contentResolver
        val dev = Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        val adb = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0)
        val adbWifi = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0)
        val saved = prefs.edit()
            .putLong(KEY_STARTED, now)
            .putString(KEY_PACKAGE, packageName)
            .putString(
                KEY_A11Y_SERVICES,
                Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty(),
            )
            .putInt(KEY_A11Y_ENABLED, Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0))
            .putInt(KEY_DEV, dev)
            .putInt(KEY_ADB, adb)
            .putInt(KEY_ADB_WIFI, adbWifi)
            .commit()
        if (!saved) return false

        Log.i(TAG, "on for $packageName")
        // For working on Pact over USB: set by hand with run-as, never from the UI.
        val keepAdb = prefs.getBoolean(KEY_KEEP_ADB, false)
        runCatching {
            if (!keepAdb) {
                if (adbWifi == 1) Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 0)
                if (adb == 1) Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)
                if (dev == 1) Settings.Global.putInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
            }
            // Last, because this unbinds Pact's own accessibility service.
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        }.onFailure { Log.w(TAG, "could not switch off: ${it.message}") }
        return true
    }

    /** Puts back what [enter] saved. Safe to call when nothing is saved. */
    @Synchronized
    fun restore(context: Context, reason: String, cooldown: Boolean = false) {
        val prefs = prefs(context)
        val started = prefs.getLong(KEY_STARTED, 0L)
        if (started == 0L) return
        val resolver = context.contentResolver
        val restored = runCatching {
            if (prefs.getInt(KEY_DEV, 0) == 1) {
                Settings.Global.putInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            }
            if (prefs.getInt(KEY_ADB, 0) == 1) Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
            // Switching wireless debugging on restarts adb, so only when it really changed.
            if (prefs.getInt(KEY_ADB_WIFI, 0) == 1 && Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 0) {
                Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1)
            }
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                prefs.getString(KEY_A11Y_SERVICES, "").orEmpty(),
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, prefs.getInt(KEY_A11Y_ENABLED, 0))
        }
        restored.onFailure { Log.w(TAG, "could not restore: ${it.message}") }

        val now = System.currentTimeMillis()
        val used = (now - started).coerceIn(0L, SESSION_CAP_MS)
        val edit = prefs.edit()
            .remove(KEY_STARTED)
            .remove(KEY_PACKAGE)
            .putLong(KEY_DAY, dayOf(now))
            .putLong(KEY_DAY_USED, usedToday(prefs, now) + used)
        if (cooldown) edit.putLong(KEY_COOLDOWN_UNTIL, now + COOLDOWN_MS)
        edit.commit()
        // Pact's own service comes back even if it was off before the session.
        SecureSettings.ensureAccessibility(context)
        Log.i(TAG, "off after ${used / 1000}s: $reason")
    }

    /** Why [enter] would say no right now, in words for a toast. */
    fun refusal(context: Context): String? {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val cooldown = prefs.getLong(KEY_COOLDOWN_UNTIL, 0L) - now
        return when {
            !SecureSettings.canWrite(context) -> "Pact needs its one-time permission from a computer"
            cooldown > 0L -> "Payment pause rests for ${cooldown / 60_000L + 1} more min"
            usedToday(prefs, now) >= DAILY_BUDGET_MS -> "30 min of payment pause used today"
            else -> null
        }
    }

    /** A session the watcher should have ended long ago. */
    fun isStale(context: Context): Boolean {
        val started = prefs(context).getLong(KEY_STARTED, 0L)
        return started > 0L && System.currentTimeMillis() - started > STALE_MS
    }

    /** Runs for the life of the process and ends sessions. Cheap while nothing is on. */
    suspend fun watch(app: PactApp) {
        var front: String? = null
        var queriedTo = 0L
        var awaySince = 0L
        while (coroutineContext.isActive) {
            delay(1_000)
            val prefs = prefs(app)
            val started = prefs.getLong(KEY_STARTED, 0L)
            if (started == 0L) {
                front = null
                awaySince = 0L
                continue
            }
            val now = System.currentTimeMillis()
            if (front == null) {
                front = prefs.getString(KEY_PACKAGE, null)
                queriedTo = started
            }
            front = latestResumed(app, queriedTo - 2_000L, now) ?: front
            queriedTo = now

            val stays = front != null && (front in app.focus.paymentApps.value || front == smsApp(app))
            val reason = when {
                app.getSystemService(PowerManager::class.java)?.isInteractive == false -> "screen off"
                now - started >= SESSION_CAP_MS -> "20 minute cap"
                usedToday(prefs, now) + (now - started) >= DAILY_BUDGET_MS -> "30 minutes used today"
                front != null && app.engine.shouldBlock(front) -> "blocked app $front"
                // Pact's own pages there are only guarded while accessibility is on.
                front in SYSTEM_SETTINGS -> "opened $front"
                stays -> null
                else -> {
                    if (awaySince == 0L) awaySince = now
                    if (now - awaySince >= AWAY_GRACE_MS) "left for $front" else null
                }
            }
            if (stays) awaySince = 0L
            if (reason != null) {
                val capped = reason.startsWith("20") || reason.startsWith("30")
                restore(app, reason, cooldown = capped)
                front = null
                awaySince = 0L
            }
        }
    }

    private fun latestResumed(context: Context, from: Long, to: Long): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = runCatching { usm.queryEvents(from.coerceAtLeast(0L), to) }.getOrNull() ?: return null
        val resumeType = if (Build.VERSION.SDK_INT >= 29) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == resumeType) latest = event.packageName
        }
        return latest
    }

    /** The default SMS app, so reading an OTP doesn't end the session. */
    private fun smsApp(context: Context): String? =
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    private fun usedToday(prefs: SharedPreferences, now: Long): Long =
        if (prefs.getLong(KEY_DAY, 0L) == dayOf(now)) prefs.getLong(KEY_DAY_USED, 0L) else 0L

    private fun dayOf(millis: Long): Long {
        val zone = java.util.TimeZone.getDefault()
        return (millis + zone.getOffset(millis)) / 86_400_000L
    }
}
