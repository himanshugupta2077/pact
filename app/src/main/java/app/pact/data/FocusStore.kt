package app.pact.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Black-and-white screen and blocked websites. */
class FocusStore(context: Context) {
    private val prefs = context.getSharedPreferences("pact_focus", Context.MODE_PRIVATE)

    private val _grayscale = MutableStateFlow(prefs.getBoolean(KEY_GRAYSCALE, false))
    val grayscale: StateFlow<Boolean> = _grayscale

    private val _colorApps = MutableStateFlow(readSet(KEY_COLOR_APPS, emptySet()))
    val colorApps: StateFlow<Set<String>> = _colorApps

    private val _blockedSites = MutableStateFlow(readSet(KEY_SITES, DEFAULT_SITES))
    val blockedSites: StateFlow<Set<String>> = _blockedSites

    fun setGrayscale(on: Boolean) {
        prefs.edit().putBoolean(KEY_GRAYSCALE, on).apply()
        _grayscale.value = on
    }

    @Synchronized
    fun setKeepInColor(packageName: String, on: Boolean) {
        val next = if (on) _colorApps.value + packageName else _colorApps.value - packageName
        prefs.edit().putStringSet(KEY_COLOR_APPS, next).apply()
        _colorApps.value = next
    }

    /** Returns the stored host, or null when [raw] is not a usable domain. */
    @Synchronized
    fun addSite(raw: String): String? {
        val host = hostOf(raw) ?: return null
        if (!host.contains('.')) return null
        val next = _blockedSites.value + host
        prefs.edit().putStringSet(KEY_SITES, next).apply()
        _blockedSites.value = next
        return host
    }

    @Synchronized
    fun removeSite(host: String) {
        val next = _blockedSites.value - host
        prefs.edit().putStringSet(KEY_SITES, next).apply()
        _blockedSites.value = next
    }

    /** The blocked entry that covers [host] (the domain itself or any subdomain). */
    fun blockedSiteFor(host: String): String? {
        val sites = _blockedSites.value
        var h = host
        while (true) {
            if (h in sites) return h
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return null
            h = h.substring(dot + 1)
        }
    }

    private fun readSet(key: String, fallback: Set<String>): Set<String> {
        if (!prefs.contains(key)) return fallback
        return prefs.getStringSet(key, fallback)?.toSet() ?: fallback
    }

    companion object {
        private const val KEY_GRAYSCALE = "grayscale"
        private const val KEY_COLOR_APPS = "color_apps"
        private const val KEY_SITES = "blocked_sites"

        val DEFAULT_SITES = setOf(
            "youtube.com",
            "youtu.be",
            "instagram.com",
            "x.com",
            "twitter.com",
            "reddit.com",
        )

        /** "https://www.YouTube.com/watch?v=1" -> "youtube.com". */
        fun hostOf(raw: String?): String? {
            var s = raw?.trim()?.lowercase().orEmpty()
            if (s.isEmpty() || s.contains(' ')) return null
            s = s.substringAfter("://")
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')
            s = s.substringAfter('@').substringBefore(':')
            s = s.removePrefix("www.").trimEnd('.')
            if (s.isEmpty() || s.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
            return s
        }
    }
}
