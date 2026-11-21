package app.pact.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Settings are read-only unless the Mac unlocks them over USB (see enforce/UsbControl).
 * The unlock is measured on the boot clock, so changing the phone's time or rebooting
 * cannot stretch it.
 */
class EditLock(context: Context, private val scope: CoroutineScope) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("pact_lock", Context.MODE_PRIVATE)
    private var expiry: Job? = null

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    init {
        val left = remainingMs()
        if (left > 0L) schedule(left)
    }

    fun isUnlocked(): Boolean = remainingMs() > 0L

    fun remainingMs(): Long {
        if (prefs.getInt(KEY_BOOT, -1) != bootCount()) return 0L
        return (prefs.getLong(KEY_UNTIL, 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    @Synchronized
    fun unlock(minutes: Int) {
        val ms = minutes.coerceIn(1, MAX_MINUTES) * 60_000L
        prefs.edit()
            .putInt(KEY_BOOT, bootCount())
            .putLong(KEY_UNTIL, SystemClock.elapsedRealtime() + ms)
            .commit()
        schedule(ms)
    }

    @Synchronized
    fun lock() {
        prefs.edit().remove(KEY_UNTIL).commit()
        expiry?.cancel()
        _unlocked.value = false
    }

    private fun schedule(ms: Long) {
        expiry?.cancel()
        _unlocked.value = true
        expiry = scope.launch {
            delay(ms)
            if (!isUnlocked()) _unlocked.value = false
        }
    }

    private fun bootCount(): Int = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0)

    companion object {
        private const val KEY_BOOT = "boot"
        private const val KEY_UNTIL = "until_elapsed"
        const val MAX_MINUTES = 60
    }
}
