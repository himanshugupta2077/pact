package app.pact

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import app.pact.enforce.BankMode

/**
 * Behind Pact's payment shortcuts (long-press Pact's icon, or the double-press power gesture).
 * Some apps, like BHIM, check for accessibility services the instant they start, before Pact
 * can react, so this switches everything off first and opens the app only once the system
 * reports no services.
 */
class PaymentLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg == null) {
            finish()
            return
        }
        if (!BankMode.isActive(this) && !BankMode.enter(this, pkg)) {
            BankMode.refusal(this)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
        // The power-button gesture passes through the lock screen: ask for unlock, and open
        // the app behind it so it is there once unlocked.
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        val screen = intent.getStringExtra(EXTRA_SCREEN)
        // Off the main thread, so the wait doesn't depend on this invisible activity's lifecycle.
        Thread { waitThenOpen(pkg, screen) }.start()
    }

    // Settings change at once, but services unbind a moment later, and the app asks the system.
    private fun waitThenOpen(pkg: String, screen: String?) {
        val manager = getSystemService(AccessibilityManager::class.java)
        var waited = 0L
        while (BankMode.isActive(this) && waited < 2_500L) {
            val left = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (left.isNullOrEmpty()) break
            Thread.sleep(100L)
            waited += 100L
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        Log.i(TAG, "opening $pkg after ${waited}ms")
        // The app's own shortcut extra, e.g. BHIM's QR scanner. Its shortcut uses VIEW, but
        // Android 16 only lets another app send an intent that matches a declared filter, so
        // keep the launcher's MAIN action and add the extra (extras aren't filtered).
        launch?.let {
            if (screen != null) it.putExtra(SHORTCUT_NAME, screen)
            runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { e -> Log.w(TAG, "could not open $pkg", e) }
        }
        runOnUiThread { finish() }
    }

    companion object {
        private const val TAG = "PactBank"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_SCREEN = "screen"
        private const val SHORTCUT_NAME = "android.intent.extra.shortcut.NAME"
        private const val BHIM = "in.org.npci.upiapp"

        /** Id the double-press power gesture points at (secure nt_double_tap_power_data). */
        const val SCAN_SHORTCUT_ID = "pay-scan-bhim"

        /** One shortcut per installed payment app, BHIM's QR scanner first. */
        fun publishShortcuts(app: PactApp) {
            val manager = app.getSystemService(ShortcutManager::class.java) ?: return
            val installed = app.focus.paymentApps.value.filter {
                app.packageManager.getLaunchIntentForPackage(it) != null
            }
            val shortcuts = ArrayList<ShortcutInfo>()
            if (BHIM in installed) {
                shortcuts += shortcut(app, SCAN_SHORTCUT_ID, "BHIM Scan", BHIM, "ScanQRNavigator")
            }
            for (pkg in installed) {
                shortcuts += shortcut(app, "pay:$pkg", app.installed.labelOf(pkg), pkg, null)
            }
            val max = manager.maxShortcutCountPerActivity
            val ranked = shortcuts.take(max).mapIndexed { rank, info ->
                ShortcutInfo.Builder(app, info.id).setRank(rank).build()
            }
            runCatching {
                manager.dynamicShortcuts = shortcuts.take(max)
                manager.updateShortcuts(ranked)
            }
        }

        private fun shortcut(app: PactApp, id: String, label: String, pkg: String, screen: String?): ShortcutInfo {
            val intent = Intent(app, PaymentLaunchActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PACKAGE, pkg)
            if (screen != null) intent.putExtra(EXTRA_SCREEN, screen)
            val icon = app.installed.iconOf(pkg)
            return ShortcutInfo.Builder(app, id)
                .setShortLabel(label)
                .setIntent(intent)
                .apply { if (icon != null) setIcon(Icon.createWithBitmap(icon)) }
                .build()
        }
    }
}
