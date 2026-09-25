package app.pact.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wall-clock intervals while a package is in pop-up view.
 * Usage events still mark that package resumed; callers omit these intervals.
 */
class PopupExclusionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val closed = HashMap<String, ArrayList<Span>>()
    private val open = HashMap<String, Long>()
    private val pending = HashMap<String, Long>()
    private val leaving = HashMap<String, Long>()
    private var seenWall = 0L

    init {
        synchronized(lock) {
            loadLocked()
        }
    }

    fun setPackages(packages: Set<String>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            var spansChanged = false
            for (pkg in packages) {
                leaving.remove(pkg)
                if (pkg in open) continue
                val seen = pending[pkg]
                if (seen == null) {
                    pending[pkg] = now
                } else if (now - seen >= CONFIRM_MS) {
                    pending.remove(pkg)
                    open[pkg] = seen
                    seenWall = now
                    spansChanged = true
                }
            }
            val dropPending = pending.keys.filter { it !in packages }
            for (pkg in dropPending) pending.remove(pkg)
            val dropOpen = open.keys.filter { it !in packages }
            for (pkg in dropOpen) {
                val since = leaving[pkg]
                if (since == null) {
                    leaving[pkg] = now
                    continue
                }
                if (now - since < CONFIRM_MS) continue
                val start = open.remove(pkg) ?: continue
                leaving.remove(pkg)
                addClosedLocked(pkg, start, since)
                spansChanged = true
            }
            if (open.isNotEmpty() && now - seenWall >= SEEN_GAP_MS) {
                seenWall = now
                spansChanged = true
            }
            if (spansChanged) {
                pruneLocked()
                persistLocked()
            }
        }
    }

    fun contains(packageName: String, timeMs: Long): Boolean {
        synchronized(lock) {
            return endExclusiveLocked(packageName, timeMs) != null
        }
    }

    fun startsWithin(packageName: String, timeMs: Long, leadMs: Long): Boolean {
        synchronized(lock) {
            val until = timeMs + leadMs
            val openStart = open[packageName]
            if (openStart != null && openStart > timeMs && openStart <= until) return true
            val spans = closed[packageName] ?: return false
            for (span in spans) {
                if (span.start > timeMs && span.start <= until) return true
            }
            return false
        }
    }

    /** Exclusive end of the pop-up interval covering [timeMs], if any. */
    fun endExclusive(packageName: String, timeMs: Long): Long? {
        synchronized(lock) {
            return endExclusiveLocked(packageName, timeMs)
        }
    }

    /** Next pop-up start strictly after [timeMs]. */
    fun startAfter(packageName: String, timeMs: Long): Long? {
        synchronized(lock) {
            var best: Long? = null
            val openStart = open[packageName]
            if (openStart != null && openStart > timeMs) best = openStart
            val spans = closed[packageName]
            if (spans != null) {
                for (span in spans) {
                    if (span.start > timeMs && (best == null || span.start < best)) {
                        best = span.start
                    }
                }
            }
            return best
        }
    }

    /** Pop-up intervals that end at the earliest time in (afterMs, untilMs]. */
    fun endsBetween(afterMs: Long, untilMs: Long): List<PopupEnd> {
        if (untilMs <= afterMs) return emptyList()
        synchronized(lock) {
            var best = Long.MAX_VALUE
            val found = ArrayList<PopupEnd>()
            for ((pkg, spans) in closed) {
                for (span in spans) {
                    val end = span.end
                    if (end <= afterMs || end > untilMs) continue
                    if (end < best) {
                        best = end
                        found.clear()
                        found.add(PopupEnd(pkg, end))
                    } else if (end == best) {
                        found.add(PopupEnd(pkg, end))
                    }
                }
            }
            return found
        }
    }

    private fun endExclusiveLocked(packageName: String, timeMs: Long): Long? {
        val openStart = open[packageName]
        if (openStart != null && timeMs >= openStart) return Long.MAX_VALUE
        val spans = closed[packageName] ?: return null
        var end: Long? = null
        for (span in spans) {
            if (timeMs >= span.start && timeMs < span.end) {
                end = maxOf(end ?: span.end, span.end)
            }
        }
        return end
    }

    private fun addClosedLocked(packageName: String, start: Long, end: Long) {
        if (end <= start) return
        val list = closed.getOrPut(packageName) { ArrayList() }
        list.add(Span(start, end))
        mergeLocked(list)
    }

    private fun mergeLocked(list: ArrayList<Span>) {
        if (list.size <= 1) return
        list.sortBy { it.start }
        val merged = ArrayList<Span>(list.size)
        var current = list[0]
        for (i in 1 until list.size) {
            val next = list[i]
            if (next.start <= current.end) {
                current = Span(current.start, maxOf(current.end, next.end))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        list.clear()
        list.addAll(merged)
    }

    private fun loadLocked() {
        val raw = prefs.getString(KEY_JSON, null)
        if (raw != null) {
            runCatching {
                val obj = JSONObject(raw)
                val spans = obj.optJSONObject(KEY_SPANS)
                if (spans != null) {
                    val keys = spans.keys()
                    while (keys.hasNext()) {
                        val pkg = keys.next()
                        val arr = spans.optJSONArray(pkg) ?: continue
                        val list = ArrayList<Span>()
                        for (i in 0 until arr.length()) {
                            val pair = arr.optJSONArray(i) ?: continue
                            val start = pair.optLong(0)
                            val end = pair.optLong(1)
                            if (end > start) list.add(Span(start, end))
                        }
                        if (list.isNotEmpty()) {
                            mergeLocked(list)
                            closed[pkg] = list
                        }
                    }
                }
                seenWall = obj.optLong(KEY_SEEN, 0L)
                val openObj = obj.optJSONObject(KEY_OPEN)
                if (openObj != null) {
                    val now = System.currentTimeMillis()
                    val seen = seenWall.coerceAtMost(now)
                    val keys = openObj.keys()
                    while (keys.hasNext()) {
                        val pkg = keys.next()
                        val start = openObj.optLong(pkg, 0L)
                        if (start <= 0L || start >= now) continue
                        val cap = start + MAX_OPEN_MS
                        val end = if (seen > start) minOf(seen, cap) else minOf(now, cap)
                        addClosedLocked(pkg, start, end)
                    }
                }
            }
        }
        pruneLocked()
        persistLocked()
    }

    private fun pruneLocked() {
        val keepFrom = System.currentTimeMillis() - KEEP_MS
        val empty = ArrayList<String>()
        for ((pkg, list) in closed) {
            list.removeAll { it.end <= keepFrom }
            if (list.isEmpty()) empty.add(pkg)
        }
        for (pkg in empty) closed.remove(pkg)
    }

    private fun persistLocked() {
        val spans = JSONObject()
        for ((pkg, list) in closed) {
            val arr = JSONArray()
            for (span in list) {
                val pair = JSONArray()
                pair.put(span.start)
                pair.put(span.end)
                arr.put(pair)
            }
            if (arr.length() > 0) spans.put(pkg, arr)
        }
        val openObj = JSONObject()
        for ((pkg, start) in open) openObj.put(pkg, start)
        val now = System.currentTimeMillis()
        val root = JSONObject()
        root.put(KEY_SPANS, spans)
        root.put(KEY_OPEN, openObj)
        root.put(KEY_SEEN, if (seenWall > 0L) seenWall else now)
        prefs.edit().putString(KEY_JSON, root.toString()).apply()
    }

    private data class Span(val start: Long, val end: Long)

    companion object {
        private const val PREFS = "pact_popup_excl"
        private const val KEY_JSON = "json"
        private const val KEY_SPANS = "spans"
        private const val KEY_OPEN = "open"
        private const val KEY_SEEN = "seen"
        private const val CONFIRM_MS = 300L
        private const val SEEN_GAP_MS = 15_000L
        private const val MAX_OPEN_MS = 6L * 60L * 60L * 1000L
        private const val KEEP_MS = 15L * 24L * 60L * 60L * 1000L
    }
}

data class PopupEnd(val packageName: String, val timeMs: Long)
