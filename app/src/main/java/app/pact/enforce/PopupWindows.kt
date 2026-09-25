package app.pact.enforce

import android.graphics.Rect

/**
 * Nothing OS pop-up view is a floating window: it does not span the screen the way
 * full-screen and split-screen windows do. Picture-in-picture stays countable.
 */
internal data class TrackedWindow(
    val packageName: String,
    val bounds: Rect,
    val pictureInPicture: Boolean,
    val focused: Boolean,
)

internal fun popupPackages(windows: List<TrackedWindow>, screen: Rect): Set<String> {
    if (windows.isEmpty()) return emptySet()
    val out = LinkedHashSet<String>()
    val byPackage = LinkedHashMap<String, MutableList<TrackedWindow>>()
    for (window in windows) {
        byPackage.getOrPut(window.packageName) { ArrayList() }.add(window)
    }
    for ((pkg, group) in byPackage) {
        val main = group.any { isMainWindow(it.bounds, screen, it.pictureInPicture) }
        if (!main) out.add(pkg)
    }
    return out
}

internal fun mainPackage(windows: List<TrackedWindow>, screen: Rect): String? {
    for (window in windows) {
        if (!window.focused || window.pictureInPicture) continue
        if (!isMainWindow(window.bounds, screen, pictureInPicture = false)) continue
        return window.packageName
    }
    return null
}

internal fun isMainWindow(bounds: Rect, screen: Rect, pictureInPicture: Boolean): Boolean {
    if (pictureInPicture) return true
    val sw = screen.width()
    val sh = screen.height()
    if (sw <= 0 || sh <= 0) return true
    val width = bounds.width()
    val height = bounds.height()
    if (width <= 0 || height <= 0) return false
    val slopX = (sw * 0.012f).toInt().coerceIn(8, 20)
    val slopY = (sh * 0.08f).toInt().coerceIn(48, 220)
    val touchLeft = bounds.left <= screen.left + slopX
    val touchRight = bounds.right >= screen.right - slopX
    val touchTop = bounds.top <= screen.top + slopY
    val touchBottom = bounds.bottom >= screen.bottom - slopY
    val widthRatio = width.toFloat() / sw
    val heightRatio = height.toFloat() / sh
    val fullWidth = touchLeft && touchRight && widthRatio >= 0.8f
    if (fullWidth && heightRatio >= 0.42f) return true
    if (fullWidth && heightRatio >= 0.22f && (touchTop || touchBottom)) return true
    return heightRatio >= 0.82f && widthRatio >= 0.28f &&
        touchTop && touchBottom && (touchLeft || touchRight)
}
