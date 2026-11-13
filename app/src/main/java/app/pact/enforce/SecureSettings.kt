package app.pact.enforce

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings

/**
 * Writes to Settings.Secure. Needs WRITE_SECURE_SETTINGS, granted once over ADB:
 * adb shell pm grant app.pact android.permission.WRITE_SECURE_SETTINGS
 */
object SecureSettings {
    const val GRANT_COMMAND = "adb shell pm grant app.pact android.permission.WRITE_SECURE_SETTINGS"

    private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    private const val DALTONIZER = "accessibility_display_daltonizer"
    private const val MONOCHROMACY = 0

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isGrayscale(context: Context): Boolean {
        val resolver = context.contentResolver
        return Settings.Secure.getInt(resolver, DALTONIZER_ENABLED, 0) == 1 &&
            Settings.Secure.getInt(resolver, DALTONIZER, -1) == MONOCHROMACY
    }

    /** Compares against the live setting, so a change made in system Settings is undone. */
    fun setGrayscale(context: Context, on: Boolean) {
        if (!canWrite(context) || isGrayscale(context) == on) return
        val resolver = context.contentResolver
        runCatching {
            if (on) {
                Settings.Secure.putInt(resolver, DALTONIZER, MONOCHROMACY)
                Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, 1)
            } else {
                Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, 0)
            }
        }
    }

    /** Voice or video call in any app, or a phone ringing. */
    fun inCall(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    /**
     * Force-stopping or reinstalling Pact removes its accessibility service from the
     * enabled list. Put it back. Returns true when it had to.
     */
    fun ensureAccessibility(context: Context): Boolean {
        if (!canWrite(context)) return false
        val resolver = context.contentResolver
        val ours = ComponentName(context, PactAccessibilityService::class.java)
        val raw = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val entries = raw.split(':').filter { it.isNotBlank() }
        val present = entries.any { ComponentName.unflattenFromString(it) == ours }
        val on = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (present && on) return false
        return runCatching {
            if (!present) {
                val next = (entries + ours.flattenToString()).joinToString(":")
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }
}
