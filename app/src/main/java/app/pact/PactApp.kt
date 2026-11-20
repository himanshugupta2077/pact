package app.pact

import android.app.Application
import app.pact.data.FocusStore
import app.pact.data.GroupRepository
import app.pact.data.InstalledApps
import app.pact.data.NotificationStore
import app.pact.data.OverlayExclusionStore
import app.pact.data.PactDatabase
import app.pact.data.PopupExclusionStore
import app.pact.data.RuleRepository
import app.pact.data.UsageRepository
import app.pact.engine.RuleEngine
import app.pact.enforce.BankMode
import app.pact.enforce.MediaSilencer
import app.pact.enforce.SecureSettings
import android.util.Log
import app.pact.ship.PhoneShip
import app.pact.util.hasUsageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PactApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db by lazy { PactDatabase.create(this) }
    val exclusions by lazy { OverlayExclusionStore(this) }
    val popups by lazy { PopupExclusionStore(this) }
    val rules by lazy { RuleRepository(db.appRuleDao()) }
    val groups by lazy { GroupRepository(db.appGroupDao()) }
    val usage by lazy {
        UsageRepository(this).also {
            it.exclusions = exclusions
            it.popups = popups
        }
    }
    val installed by lazy { InstalledApps(this) }
    val notifications by lazy { NotificationStore(this) }
    val engine by lazy { RuleEngine(this, usage, installed, exclusions, popups) }
    val media by lazy { MediaSilencer(this) }
    val focus by lazy { FocusStore(this) }

    override fun onCreate() {
        super.onCreate()
        // Leftover from the removed override feature: drops any saved grant.
        deleteSharedPreferences("pact_overrides")
        instance = this
        usage.exclusions = exclusions
        usage.popups = popups
        scope.launch {
            rules.observeAll().collect { engine.replaceRules(it) }
        }
        scope.launch {
            groups.observeAll().collect { engine.replaceGroups(it.map { group -> group.toRule() }) }
        }
        scope.launch {
            runCatching { installed.userApps() }
            if (hasUsageAccess(this@PactApp)) {
                runCatching { usage.prefetchCurrentWeek() }
            }
        }
        scope.launch {
            while (isActive) {
                if (hasUsageAccess(this@PactApp)) {
                    runCatching { engine.refreshUsage(force = true) }
                }
                delay(4_000)
            }
        }
        // A payment-app session left over from a crash or reboot: nothing is watching it now.
        BankMode.restore(this, "Pact restarted")
        scope.launch { BankMode.watch(this@PactApp) }
        scope.launch { runCatching { PaymentLaunchActivity.publishShortcuts(this@PactApp) } }
        scope.launch {
            while (isActive) {
                if (BankMode.isActive(this@PactApp)) {
                    if (BankMode.isStale(this@PactApp)) BankMode.restore(this@PactApp, "stale")
                } else if (SecureSettings.ensureAccessibility(this@PactApp)) {
                    Log.i("Pact", "accessibility service was off; turned it back on")
                }
                delay(15_000)
            }
        }
        scope.launch {
            while (isActive) {
                runCatching { PhoneShip(this@PactApp).uploadToday() }
                    .onFailure { Log.w("PactShip", "upload failed: ${it.message}") }
                delay(5 * 60 * 1000L)
            }
        }
    }

    companion object {
        lateinit var instance: PactApp
            private set
    }
}
