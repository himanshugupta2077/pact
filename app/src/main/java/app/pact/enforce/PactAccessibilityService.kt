package app.pact.enforce

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import app.pact.PactApp

class PactAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: BlockOverlay
    private lateinit var media: MediaSilencer

    private var covering: String? = null
    private var lastEnforceUptime = 0L
    private var lastSilenceUptime = 0L
    private var hideScheduled = false

    private val tick = object : Runnable {
        override fun run() {
            enforce(force = true)
            handler.postDelayed(this, 450L)
        }
    }

    private val hideRunnable = Runnable {
        hideScheduled = false
        covering = null
        val app = application as? PactApp
        app?.engine?.setCovering(null)
        if (::overlay.isInitialized) overlay.hide()
        val blocked = app?.engine?.currentlyBlockedPackages().orEmpty()
        media.silence(blocked, false)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = BlockOverlay(this) { goHome() }
        media = (application as? PactApp)?.media ?: MediaSilencer(this)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        enforce(force = false)
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) overlay.onConfigurationChanged()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(hideRunnable)
        covering = null
        (application as? PactApp)?.engine?.setCovering(null)
        if (::overlay.isInitialized) overlay.hide()
        if (::media.isInitialized) media.release()
        super.onDestroy()
    }

    private fun enforce(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastEnforceUptime < 80L) return
        lastEnforceUptime = now

        val app = application as? PactApp ?: return
        if (!::overlay.isInitialized) return

        val popups = noteForeground(app)

        val top = topUserPackage()
        val blockedHit = when {
            top != null && app.engine.shouldBlock(top) -> top
            else -> blockedPipPackage(app)
        }

        if (blockedHit != null) {
            cancelHide()
            val first = covering != blockedHit
            covering = blockedHit
            // Pop-up time is already omitted. Covering it as well would subtract twice.
            val coverForUsage = if (popups != null && blockedHit in popups) null else blockedHit
            app.engine.setCovering(coverForUsage)
            overlay.show(blockedHit)
            if (first) {
                lastSilenceUptime = 0L
                media.silence(app.engine.currentlyBlockedPackages(), true)
            } else {
                silenceIfDue(app, visible = true)
            }
            return
        }

        val held = covering
        if (held != null) {
            if (!stillLocked(app, held)) {
                clearCover(app)
                return
            }
            val launcher = app.installed.launcherPackage()
            val movedAway = top != null &&
                top != held &&
                (top == launcher || top == packageName || !app.engine.shouldBlock(top))
            if (movedAway) {
                if (top == packageName) {
                    clearCover(app)
                    return
                }
                scheduleHide()
                return
            }
            if (packageWindowVisible(held) || top == null || isTransientPackage(top ?: "")) {
                cancelHide()
                overlay.show(held)
                silenceIfDue(app, visible = true)
                return
            }
            scheduleHide()
            return
        }

        if (overlay.shownPackage != null) overlay.hide()
        app.engine.setCovering(null)
        silenceIfDue(app, visible = false)
    }

    private fun stillLocked(app: PactApp, pkg: String): Boolean {
        return app.engine.shouldBlock(pkg)
    }

    private fun goHome() {
        cancelHide()
        val app = application as? PactApp
        covering = null
        app?.engine?.setCovering(null)
        if (::overlay.isInitialized) overlay.hide()
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (app != null && ::media.isInitialized) {
            media.silence(app.engine.currentlyBlockedPackages(), false)
        }
    }

    private fun clearCover(app: PactApp) {
        cancelHide()
        covering = null
        app.engine.setCovering(null)
        overlay.hide()
        media.silence(app.engine.currentlyBlockedPackages(), false)
    }

    private fun scheduleHide() {
        if (hideScheduled) return
        hideScheduled = true
        handler.postDelayed(hideRunnable, 400L)
    }

    private fun cancelHide() {
        if (!hideScheduled) return
        hideScheduled = false
        handler.removeCallbacks(hideRunnable)
    }

    private fun silenceIfDue(app: PactApp, visible: Boolean) {
        val blocked = app.engine.currentlyBlockedPackages()
        if (blocked.isEmpty()) {
            media.silence(emptySet(), false)
            return
        }
        val now = SystemClock.uptimeMillis()
        val gap = if (visible) 400L else 1_500L
        if (now - lastSilenceUptime < gap) return
        lastSilenceUptime = now
        media.silence(blocked, visible)
    }

    private fun noteForeground(app: PactApp): Set<String>? {
        val snapshot = windowSnapshot() ?: return null
        val screen = screenBounds()
        val popups = popupPackages(snapshot, screen)
        app.engine.setPopupPackages(popups)
        val main = mainPackage(snapshot, screen)
        if (main != null) app.engine.onForeground(main)
        return popups
    }

    private fun screenBounds(): Rect {
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun windowSnapshot(): List<TrackedWindow>? {
        val listed = runCatching { this.windows }.getOrNull() ?: return null
        val out = ArrayList<TrackedWindow>(listed.size)
        for (window in listed) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = packageOf(window) ?: continue
            if (pkg == packageName || isTransientPackage(pkg)) continue
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            out.add(
                TrackedWindow(
                    packageName = pkg,
                    bounds = bounds,
                    pictureInPicture = window.isInPictureInPictureMode,
                    focused = window.isFocused,
                ),
            )
        }
        return out
    }

    private fun topUserPackage(): String? {
        val windows = windows
        if (!windows.isNullOrEmpty()) {
            for (window in windows) {
                val type = window.type
                if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                if (type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY) continue
                if (!window.isFocused && !window.isActive) continue
                val pkg = packageOf(window) ?: continue
                if (pkg == packageName) {
                    if (window.isFocused) return pkg
                    continue
                }
                if (isTransientPackage(pkg)) continue
                return pkg
            }
        }
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName && !isTransientPackage(pkg)) {
            return pkg
        }
        return null
    }

    private fun packageWindowVisible(pkg: String): Boolean {
        val windows = windows ?: return false
        for (window in windows) {
            val type = window.type
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (packageOf(window) == pkg) return true
        }
        return false
    }

    private fun blockedPipPackage(app: PactApp): String? {
        val windows = windows ?: return null
        for (window in windows) {
            if (!window.isInPictureInPictureMode) continue
            val pkg = packageOf(window) ?: continue
            if (app.engine.shouldBlock(pkg)) return pkg
        }
        return null
    }

    private fun packageOf(window: AccessibilityWindowInfo): String? {
        val root = window.root ?: return null
        val pkg = root.packageName?.toString()
        if (Build.VERSION.SDK_INT < 33) {
            runCatching { root.recycle() }
        }
        return pkg?.takeIf { it.isNotBlank() }
    }

    private fun isTransientPackage(pkg: String): Boolean {
        if (pkg == "android") return true
        if (pkg == "com.android.systemui") return true
        if (pkg.startsWith("com.android.systemui")) return true
        if (pkg.contains("quickstep", ignoreCase = true)) return true
        return false
    }
}
