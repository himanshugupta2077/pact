package app.pact

import android.app.Application
import app.pact.data.GroupRepository
import app.pact.data.InstalledApps
import app.pact.data.NotificationStore
import app.pact.data.OverlayExclusionStore
import app.pact.data.PactDatabase
import app.pact.data.PopupExclusionStore
import app.pact.data.RuleRepository
import app.pact.data.UsageRepository
import app.pact.engine.RuleEngine
import app.pact.enforce.MediaSilencer
import app.pact.override.OverrideClient
import app.pact.override.OverrideStore
import app.pact.override.OverrideSync
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
    val overrideStore by lazy { OverrideStore(this) }
    val exclusions by lazy { OverlayExclusionStore(this) }
    val popups by lazy { PopupExclusionStore(this) }
    val rules by lazy { RuleRepository(db.appRuleDao()) { overrideStore.canEditSettings() } }
    val groups by lazy { GroupRepository(db.appGroupDao()) { overrideStore.canEditSettings() } }
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
    val overrides by lazy { OverrideSync(overrideStore, OverrideClient(overrideStore), engine) }

    override fun onCreate() {
        super.onCreate()
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
        scope.launch {
            runCatching { overrides.runLoop() }
        }
    }

    companion object {
        lateinit var instance: PactApp
            private set
    }
}
