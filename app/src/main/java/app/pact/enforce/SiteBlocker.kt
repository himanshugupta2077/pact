package app.pact.enforce

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.pact.data.FocusStore

/**
 * Reads the address bar of known browsers and leaves blocked sites.
 * First hit goes back; if the site is still open shortly after, it goes home.
 */
class SiteBlocker(private val service: AccessibilityService) {

    private var lastCheckUptime = 0L
    private var lastBackUptime = 0L
    private var lastToastUptime = 0L

    fun enforce(topPackage: String?, focus: FocusStore) {
        val ids = URL_BAR_IDS[topPackage ?: return] ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastCheckUptime < 300L) return
        lastCheckUptime = now

        val host = blockedHost(topPackage, ids, focus) ?: return
        if (now - lastBackUptime < 600L) return
        val recentlyBacked = now - lastBackUptime < 2_500L
        lastBackUptime = now
        service.performGlobalAction(
            if (recentlyBacked) AccessibilityService.GLOBAL_ACTION_HOME
            else AccessibilityService.GLOBAL_ACTION_BACK,
        )
        if (now - lastToastUptime > 3_000L) {
            lastToastUptime = now
            Toast.makeText(service, "$host is blocked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun blockedHost(pkg: String, ids: List<String>, focus: FocusStore): String? {
        val root = service.rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != pkg) return null
            for (id in ids) {
                val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                try {
                    for (node in nodes) {
                        // Leave the bar alone while it is being typed into.
                        if (node.isFocused) return null
                        val host = FocusStore.hostOf(node.text?.toString()) ?: continue
                        focus.blockedSiteFor(host)?.let { return it }
                    }
                } finally {
                    nodes.forEach { it.recycleCompat() }
                }
            }
            return null
        } finally {
            root.recycleCompat()
        }
    }

    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < 33) runCatching { recycle() }
    }

    companion object {
        private val CHROMIUM = listOf("url_bar")

        val URL_BAR_IDS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to CHROMIUM,
            "com.chrome.beta" to CHROMIUM,
            "com.chrome.dev" to CHROMIUM,
            "com.brave.browser" to CHROMIUM,
            "com.brave.browser_beta" to CHROMIUM,
            "com.microsoft.emmx" to CHROMIUM,
            "com.vivaldi.browser" to CHROMIUM,
            "com.kiwibrowser.browser" to CHROMIUM,
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view"),
            "org.mozilla.focus" to listOf("mozac_browser_toolbar_url_view"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
            "com.opera.browser" to listOf("url_field"),
            "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
        )
    }
}
