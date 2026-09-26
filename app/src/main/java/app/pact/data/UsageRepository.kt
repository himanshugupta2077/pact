package app.pact.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import app.pact.util.hasUsageAccess
import app.pact.util.shiftDays
import app.pact.util.startOfDayMs
import app.pact.util.startOfWeekMs

data class DayUsage(
    val dayStartMs: Long,
    val byPackage: Map<String, Long>,
    val unlocks: Int,
) {
    val totalMs: Long get() = byPackage.values.sum()
}

/** One foreground stretch already clipped to the day and with pop-ups removed. */
data class UsageSpan(
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
)

class UsageRepository(private val context: Context) {

    @Volatile
    var exclusions: OverlayExclusionStore? = null

    @Volatile
    var popups: PopupExclusionStore? = null

    private val lock = Any()
    private val cache = HashMap<Long, CachedDay>()

    fun foregroundMsToday(): Map<String, Long> = synchronized(lock) {
        dayUsageUnlocked(startOfDayMs()).byPackage
    }

    /** Foreground stretches from [dayStart] until [dayEnd], same clock as the limiter. */
    fun foregroundSpans(dayStart: Long, dayEnd: Long): List<UsageSpan> = synchronized(lock) {
        readClock(dayStart, dayEnd)?.spans?.toList() ?: emptyList()
    }

    fun unlocksToday(): Int = synchronized(lock) {
        dayUsageUnlocked(startOfDayMs()).unlocks
    }

    fun prefetchCurrentWeek() {
        val week = startOfWeekMs()
        weekDays(week)
        weekDays(shiftDays(week, -7))
    }

    fun respectedStreak(packageName: String, limitMinutes: Int): Int {
        if (limitMinutes <= 0) return 0
        val limitMs = limitMinutes * 60_000L
        val today = startOfDayMs()
        var streak = 0
        synchronized(lock) {
            for (i in 1..14) {
                val day = shiftDays(today, -i)
                val cached = cache[day] ?: break
                val used = cached.usage.byPackage[packageName] ?: 0L
                val cut = exclusions?.total(packageName, day) ?: 0L
                if (used - cut >= limitMs) break
                streak++
            }
        }
        return streak
    }

    fun weekMs(packageName: String): Long = synchronized(lock) {
        val weekStart = startOfWeekMs()
        var total = 0L
        for (i in 0..6) {
            val day = shiftDays(weekStart, i)
            total += cache[day]?.usage?.byPackage?.get(packageName) ?: 0L
        }
        total
    }

    fun cachedDayMs(dayStart: Long, packageName: String): Long = synchronized(lock) {
        cache[dayStart]?.usage?.byPackage?.get(packageName) ?: 0L
    }

    fun weekDays(weekStartMs: Long): List<DayUsage> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val todayStart = startOfDayMs(now)
        val days = (0..6).map { shiftDays(weekStartMs, it) }
        for (day in days) {
            if (day > now) continue
            val cached = cache[day]
            val stale = when {
                cached == null -> true
                day < todayStart -> !cached.complete
                else -> now - cached.loadedAt >= TODAY_TTL_MS
            }
            if (stale) {
                val end = if (day < todayStart) shiftDays(day, 1) else now
                fillFromEvents(day, end)
            }
        }
        days.map { day ->
            when {
                day > now -> DayUsage(day, emptyMap(), 0)
                else -> adjust(cache[day]?.usage ?: DayUsage(day, emptyMap(), 0))
            }
        }
    }

    private fun dayUsageUnlocked(dayStart: Long): DayUsage {
        val now = System.currentTimeMillis()
        if (dayStart > now) return DayUsage(dayStart, emptyMap(), 0)
        val todayStart = startOfDayMs(now)
        val cached = cache[dayStart]
        val fresh = when {
            cached == null -> false
            dayStart < todayStart -> cached.complete
            else -> now - cached.loadedAt < TODAY_TTL_MS
        }
        if (fresh) return adjust(cached!!.usage)
        val end = if (dayStart < todayStart) shiftDays(dayStart, 1) else now
        fillFromEvents(dayStart, end)
        return adjust(cache[dayStart]?.usage ?: DayUsage(dayStart, emptyMap(), 0))
    }

    private fun readClock(rangeStart: Long, rangeEnd: Long): ForegroundClock? {
        if (rangeEnd <= rangeStart) return null
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val queryStart = (rangeStart - LOOKBACK_MS).coerceAtLeast(0L)
        val events = runCatching { usm.queryEvents(queryStart, rangeEnd) }.getOrNull() ?: return null
        val event = UsageEvents.Event()

        val resumeType = if (Build.VERSION.SDK_INT >= 29) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val pauseType = if (Build.VERSION.SDK_INT >= 29) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }
        val screenOnType = if (Build.VERSION.SDK_INT >= 28) UsageEvents.Event.SCREEN_INTERACTIVE else Int.MIN_VALUE
        val screenOffType = if (Build.VERSION.SDK_INT >= 28) UsageEvents.Event.SCREEN_NON_INTERACTIVE else Int.MIN_VALUE
        val keyguardShownType = if (Build.VERSION.SDK_INT >= 28) UsageEvents.Event.KEYGUARD_SHOWN else Int.MIN_VALUE
        val keyguardHiddenType = if (Build.VERSION.SDK_INT >= 28) UsageEvents.Event.KEYGUARD_HIDDEN else Int.MIN_VALUE
        val shutdownType = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.DEVICE_SHUTDOWN else Int.MIN_VALUE

        val clock = ForegroundClock(rangeStart, rangeEnd, queryStart, popups)

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val t = event.timeStamp
            val pkg = event.packageName
            when (val type = event.eventType) {
                resumeType -> {
                    if (!pkg.isNullOrBlank()) clock.resume(pkg, t)
                }
                pauseType -> {
                    if (!pkg.isNullOrBlank()) clock.pause(pkg, t)
                }
                else -> when (type) {
                    screenOnType -> clock.screen(true, t)
                    screenOffType -> clock.screen(false, t)
                    keyguardShownType -> clock.keyguard(shown = true, t = t, countUnlock = false)
                    keyguardHiddenType -> clock.keyguard(shown = false, t = t, countUnlock = true)
                    shutdownType -> clock.shutdown(t)
                }
            }
        }
        clock.finish(rangeEnd)
        return clock
    }

    private fun fillFromEvents(rangeStart: Long, rangeEnd: Long) {
        val clock = readClock(rangeStart, rangeEnd) ?: return

        val now = System.currentTimeMillis()
        val todayStart = startOfDayMs(now)
        var day = startOfDayMs(rangeStart)
        while (day < rangeEnd) {
            val map = clock.totals[day]?.toMap() ?: emptyMap()
            val u = clock.unlocks[day] ?: 0
            cache[day] = CachedDay(
                usage = DayUsage(day, map, u),
                complete = day < todayStart,
                loadedAt = now,
            )
            day = shiftDays(day, 1)
        }
    }

    private fun adjust(usage: DayUsage): DayUsage {
        return exclusions?.adjust(usage) ?: usage
    }

    /**
     * One full-screen app at a time. Nested activities in the same package stay open;
     * a different package, screen-off, or lock ends the previous session.
     * Pop-up view is not screen time: that resume does not replace the app behind it,
     * and the popped package's minutes are left out. Expanding it back to full screen
     * starts counting it.
     * Time is clipped to [commitStart, commitEnd) so a lookback query can
     * recover the app that was already in front at midnight.
     */
    private class ForegroundClock(
        private val commitStart: Long,
        private val commitEnd: Long,
        private val edgeFloor: Long,
        private val popups: PopupExclusionStore?,
    ) {
        private var pkg: String? = null
        private var depth: Int = 0
        private var started: Long = 0L
        private var screenOn: Boolean = true
        private var unlocked: Boolean = true
        private var cursor: Long = edgeFloor
        private val suspended = LinkedHashMap<String, Int>()
        val totals = HashMap<Long, HashMap<String, Long>>()
        val unlocks = HashMap<Long, Int>()
        val spans = ArrayList<UsageSpan>()

        fun resume(packageName: String, t: Long) {
            drain(t)
            if (packageName != pkg && isPopupResume(packageName, t)) {
                suspended[packageName] = (suspended[packageName] ?: 0) + 1
                return
            }
            suspended.remove(packageName)
            if (pkg != packageName) {
                close(t)
                pkg = packageName
                depth = 1
                if (canCount()) started = t
            } else {
                depth += 1
            }
        }

        fun pause(packageName: String, t: Long) {
            drain(t)
            val held = suspended[packageName]
            if (held != null && pkg != packageName) {
                if (held <= 1) suspended.remove(packageName) else suspended[packageName] = held - 1
                return
            }
            if (pkg != packageName) return
            depth -= 1
            if (depth <= 0) {
                close(t)
                pkg = null
                depth = 0
                suspended.remove(packageName)
            }
        }

        fun screen(on: Boolean, t: Long) {
            drain(t)
            if (screenOn == on) return
            if (on) {
                screenOn = true
                if (canCount() && pkg != null && started == 0L && !inPopup(pkg!!, t)) started = t
            } else {
                close(t)
                screenOn = false
            }
        }

        fun keyguard(shown: Boolean, t: Long, countUnlock: Boolean) {
            drain(t)
            if (!shown && countUnlock && t >= commitStart && t < commitEnd) {
                val day = startOfDayMs(t)
                unlocks[day] = (unlocks[day] ?: 0) + 1
            }
            val nextUnlocked = !shown
            if (unlocked == nextUnlocked) return
            if (shown) {
                close(t)
                unlocked = false
            } else {
                unlocked = true
                if (canCount() && pkg != null && started == 0L && !inPopup(pkg!!, t)) started = t
            }
        }

        fun shutdown(t: Long) {
            drain(t)
            close(t)
            pkg = null
            depth = 0
            suspended.clear()
        }

        fun finish(t: Long) {
            drain(t)
            close(t)
        }

        private fun canCount(): Boolean = screenOn && unlocked

        private fun isPopupResume(packageName: String, t: Long): Boolean {
            val store = popups ?: return false
            return store.contains(packageName, t) ||
                store.startsWithin(packageName, t, POPUP_LEAD_MS)
        }

        private fun inPopup(packageName: String, t: Long): Boolean {
            return popups?.contains(packageName, t) == true
        }

        private fun drain(t: Long) {
            val store = popups
            if (store == null) {
                if (t > cursor) cursor = t
                return
            }
            var guard = 0
            while (guard++ < 2_000) {
                val batch = store.endsBetween(cursor, t)
                if (batch.isEmpty()) break
                val at = batch[0].timeMs
                if (at <= cursor) break
                for (edge in batch) onPopupEnded(edge.packageName, edge.timeMs)
                cursor = at
            }
            if (t > cursor) cursor = t
        }

        private fun onPopupEnded(packageName: String, t: Long) {
            if (popups?.contains(packageName, t) == true) return
            if (pkg == packageName) {
                if (started == 0L && canCount()) started = t
                return
            }
            val held = suspended.remove(packageName) ?: return
            close(t)
            pkg = packageName
            depth = held
            started = if (canCount()) t else 0L
        }

        private fun close(t: Long) {
            val open = pkg
            val from = started
            started = 0L
            if (open == null || from <= 0L) return
            commit(open, from, t)
        }

        private fun commit(pkg: String, from: Long, to: Long) {
            var a = maxOf(from, commitStart)
            val end = minOf(to, commitEnd)
            if (end <= a) return
            var guard = 0
            while (a < end && guard++ < 100_000) {
                if (inPopup(pkg, a)) {
                    val skipTo = (popups?.endExclusive(pkg, a) ?: end).coerceAtMost(end)
                    if (skipTo <= a) {
                        a += 1
                        continue
                    }
                    a = skipTo
                    continue
                }
                val day = startOfDayMs(a)
                val nextDay = shiftDays(day, 1)
                var clip = minOf(end, nextDay)
                val pop = popups?.startAfter(pkg, a)
                if (pop != null && pop < clip) clip = pop
                val add = clip - a
                if (add > 0) {
                    val map = totals.getOrPut(day) { HashMap() }
                    map[pkg] = (map[pkg] ?: 0L) + add
                    spans.add(UsageSpan(pkg, a, clip))
                }
                if (clip <= a) break
                a = clip
            }
        }

        companion object {
            private const val POPUP_LEAD_MS = 2_000L
        }
    }

    private class CachedDay(
        val usage: DayUsage,
        val complete: Boolean,
        val loadedAt: Long,
    )

    companion object {
        private const val TODAY_TTL_MS = 5_000L
        private const val LOOKBACK_MS = 3L * 60L * 60L * 1000L
    }
}
