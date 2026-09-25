package app.pact.data

import android.content.Context
import android.os.SystemClock
import app.pact.util.startOfDayMs
import org.json.JSONObject

/**
 * Time the lock overlay is covering an app is not screen time.
 * UsageStats still counts the app as foreground; this store is subtracted from it.
 */
class OverlayExclusionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val byDay = HashMap<Long, HashMap<String, Long>>()

    @Volatile
    private var coveringPkg: String? = null
    private var coveringSinceElapsed = 0L

    init {
        synchronized(lock) {
            loadLocked()
            recoverCoveringLocked()
            pruneLocked()
        }
    }

    fun coveringPackage(): String? = coveringPkg

    fun setCovering(packageName: String?) {
        synchronized(lock) {
            rolloverLocked()
            val nowElapsed = SystemClock.elapsedRealtime()
            val next = packageName?.takeIf { it.isNotBlank() }
            if (coveringPkg == next) return
            flushCoveringLocked(nowElapsed)
            coveringPkg = next
            coveringSinceElapsed = if (next != null) nowElapsed else 0L
            persistCoveringLocked()
        }
    }

    fun total(packageName: String, dayStartMs: Long = startOfDayMs()): Long {
        synchronized(lock) {
            rolloverLocked()
            val persisted = byDay[dayStartMs]?.get(packageName) ?: 0L
            val live = liveLocked(packageName, dayStartMs)
            return persisted + live
        }
    }

    fun adjust(usage: DayUsage): DayUsage {
        if (usage.byPackage.isEmpty()) return usage
        synchronized(lock) {
            rolloverLocked()
            var changed = false
            val map = LinkedHashMap<String, Long>(usage.byPackage.size)
            for ((pkg, ms) in usage.byPackage) {
                val cut = (byDay[usage.dayStartMs]?.get(pkg) ?: 0L) +
                    liveLocked(pkg, usage.dayStartMs)
                val next = (ms - cut).coerceAtLeast(0L)
                if (next != ms) changed = true
                if (next > 0L) map[pkg] = next
            }
            return if (!changed) usage else usage.copy(byPackage = map)
        }
    }

    private fun liveLocked(packageName: String, dayStartMs: Long): Long {
        if (coveringPkg != packageName) return 0L
        if (dayStartMs != startOfDayMs()) return 0L
        if (coveringSinceElapsed <= 0L) return 0L
        return (SystemClock.elapsedRealtime() - coveringSinceElapsed).coerceAtLeast(0L)
    }

    private fun flushCoveringLocked(nowElapsed: Long) {
        val pkg = coveringPkg ?: return
        val since = coveringSinceElapsed
        coveringPkg = null
        coveringSinceElapsed = 0L
        if (since <= 0L) return
        val add = (nowElapsed - since).coerceAtLeast(0L)
        if (add <= 0L) return
        val day = startOfDayMs()
        val row = byDay.getOrPut(day) { HashMap() }
        row[pkg] = (row[pkg] ?: 0L) + add
        persistDaysLocked()
    }

    private fun recoverCoveringLocked() {
        val pkg = prefs.getString(KEY_COVER_PKG, null)?.takeIf { it.isNotBlank() } ?: return
        val sinceWall = prefs.getLong(KEY_COVER_WALL, 0L)
        prefs.edit().remove(KEY_COVER_PKG).remove(KEY_COVER_WALL).apply()
        if (sinceWall <= 0L) return
        val add = (System.currentTimeMillis() - sinceWall).coerceIn(0L, 6L * 60L * 60L * 1000L)
        if (add <= 0L) return
        val day = startOfDayMs()
        val row = byDay.getOrPut(day) { HashMap() }
        row[pkg] = (row[pkg] ?: 0L) + add
        persistDaysLocked()
    }

    private fun rolloverLocked() {
        val today = startOfDayMs()
        val stored = prefs.getLong(KEY_DAY, 0L)
        if (stored != today) {
            coveringPkg = null
            coveringSinceElapsed = 0L
            persistCoveringLocked()
            pruneLocked()
        }
    }

    private fun pruneLocked() {
        val today = startOfDayMs()
        val keepFrom = today - 14L * 24L * 60L * 60L * 1000L
        val stale = byDay.keys.filter { it < keepFrom }
        if (stale.isNotEmpty()) {
            for (day in stale) byDay.remove(day)
            persistDaysLocked()
        }
        prefs.edit().putLong(KEY_DAY, today).apply()
    }

    private fun loadLocked() {
        val raw = prefs.getString(KEY_DAYS, null) ?: return
        runCatching {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val dayKey = keys.next()
                val day = dayKey.toLongOrNull() ?: continue
                val row = obj.optJSONObject(dayKey) ?: continue
                val map = HashMap<String, Long>()
                val pkgs = row.keys()
                while (pkgs.hasNext()) {
                    val pkg = pkgs.next()
                    val ms = row.optLong(pkg, 0L)
                    if (ms > 0L) map[pkg] = ms
                }
                if (map.isNotEmpty()) byDay[day] = map
            }
        }
    }

    private fun persistDaysLocked() {
        val obj = JSONObject()
        for ((day, row) in byDay) {
            val nested = JSONObject()
            for ((pkg, ms) in row) {
                if (ms > 0L) nested.put(pkg, ms)
            }
            if (nested.length() > 0) obj.put(day.toString(), nested)
        }
        prefs.edit().putString(KEY_DAYS, obj.toString()).apply()
    }

    private fun persistCoveringLocked() {
        val pkg = coveringPkg
        val edit = prefs.edit()
        if (pkg == null) {
            edit.remove(KEY_COVER_PKG).remove(KEY_COVER_WALL)
        } else {
            edit.putString(KEY_COVER_PKG, pkg)
                .putLong(KEY_COVER_WALL, System.currentTimeMillis())
        }
        edit.apply()
    }

    companion object {
        private const val PREFS = "pact_overlay_excl"
        private const val KEY_DAYS = "days"
        private const val KEY_DAY = "day"
        private const val KEY_COVER_PKG = "cover_pkg"
        private const val KEY_COVER_WALL = "cover_wall"
    }
}
