package app.pact.override

import android.content.Context
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

class OverrideStore(context: Context) {
    private val prefs = context.getSharedPreferences("pact_overrides", Context.MODE_PRIVATE)
    private val _records = MutableStateFlow(readAll())
    val records: StateFlow<Map<String, OverrideRecord>> = _records

    init {
        resetDailyRequestQuotaOnce()
    }

    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE, null)
        if (!existing.isNullOrBlank()) return existing
        val id = "pact-" + UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    fun serverUrl(): String {
        val raw = prefs.getString(KEY_SERVER, DEFAULT_PACT_SERVER).orEmpty().trim()
        return raw.ifBlank { DEFAULT_PACT_SERVER }.trimEnd('/')
    }

    fun setServerUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_SERVER, cleaned.ifBlank { DEFAULT_PACT_SERVER }).apply()
    }

    fun get(kind: RequestKind, targetId: String): OverrideRecord? = _records.value[recordKey(kind, targetId)]

    fun get(packageName: String): OverrideRecord? = get(RequestKind.APP, packageName)

    @Synchronized
    fun put(record: OverrideRecord) {
        val next = _records.value.toMutableMap()
        next[record.key] = record
        persist(next)
        _records.value = next
    }

    @Synchronized
    fun putAll(records: Map<String, OverrideRecord>) {
        persist(records)
        _records.value = records
    }

    @Synchronized
    fun resetDailyRequestQuotaOnce() {
        if (prefs.getBoolean(KEY_QUOTA_RESET_V1, false)) return
        val next = LinkedHashMap<String, OverrideRecord>(_records.value.size)
        for ((key, rec) in _records.value) {
            next[key] = rec.copy(
                pendingId = null,
                lastRequestDay = "",
                lastStatus = "",
            )
        }
        persist(next)
        _records.value = next
        prefs.edit().putBoolean(KEY_QUOTA_RESET_V1, true).apply()
    }

    @Synchronized
    fun expireActive() {
        val next = LinkedHashMap<String, OverrideRecord>(_records.value.size)
        var changed = false
        for ((key, rec) in _records.value) {
            if (rec.overrideUntilEpochMs > 0L) {
                next[key] = rec.copy(overrideUntilEpochMs = 0L)
                changed = true
            } else {
                next[key] = rec
            }
        }
        if (!changed) return
        persist(next)
        _records.value = next
    }

    fun snapshot(
        kind: RequestKind,
        targetId: String,
        now: Long = System.currentTimeMillis(),
    ): OverrideSnapshot {
        val rec = get(kind, targetId) ?: return OverrideSnapshot(OverridePhase.AVAILABLE, kind, targetId)
        if (rec.overrideUntilEpochMs > now) {
            return OverrideSnapshot(
                phase = OverridePhase.ACTIVE,
                kind = kind,
                targetId = targetId,
                minutes = rec.minutes,
                comment = rec.comment,
                untilEpochMs = rec.overrideUntilEpochMs,
            )
        }
        if (!rec.pendingId.isNullOrBlank()) {
            return OverrideSnapshot(
                phase = OverridePhase.WAITING,
                kind = kind,
                targetId = targetId,
                pendingId = rec.pendingId,
                minutes = rec.minutes,
                comment = rec.comment,
            )
        }
        if (rec.lastRequestDay == localDay(now)) {
            val phase = if (rec.lastStatus == "denied") OverridePhase.DENIED else OverridePhase.USED
            return OverrideSnapshot(
                phase = phase,
                kind = kind,
                targetId = targetId,
                minutes = rec.minutes,
                comment = rec.comment,
            )
        }
        return OverrideSnapshot(OverridePhase.AVAILABLE, kind, targetId)
    }

    fun snapshot(packageName: String, now: Long = System.currentTimeMillis()): OverrideSnapshot =
        snapshot(RequestKind.APP, packageName, now)

    fun settingsSnapshot(now: Long = System.currentTimeMillis()): OverrideSnapshot =
        snapshot(RequestKind.SETTINGS, SETTINGS_TARGET, now)

    fun canEditSettings(now: Long = System.currentTimeMillis()): Boolean =
        settingsSnapshot(now).phase == OverridePhase.ACTIVE

    fun canEditServerUrl(now: Long = System.currentTimeMillis()): Boolean =
        canEditSettings(now) || _records.value.isEmpty()

    fun activeUntil(kind: RequestKind, now: Long = System.currentTimeMillis()): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (rec in _records.value.values) {
            if (rec.kind != kind) continue
            if (rec.overrideUntilEpochMs > now) out[rec.targetId] = rec.overrideUntilEpochMs
        }
        return out
    }

    fun activeUntil(): Map<String, Long> = activeUntil(RequestKind.APP)

    fun settingsUntil(now: Long = System.currentTimeMillis()): Long {
        val rec = get(RequestKind.SETTINGS, SETTINGS_TARGET) ?: return 0L
        return if (rec.overrideUntilEpochMs > now) rec.overrideUntilEpochMs else 0L
    }

    fun noteWallClock(now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs.getLong(KEY_WALL, 0L)
        prefs.edit().putLong(KEY_WALL, now).apply()
        return last > 0L && now + CLOCK_ROLLBACK_MS < last
    }

    private fun readAll(): Map<String, OverrideRecord> {
        val raw = prefs.getString(KEY_APPS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, OverrideRecord>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val storedKey = keys.next()
                val row = obj.optJSONObject(storedKey) ?: continue
                val rec = readRow(storedKey, row) ?: continue
                out[rec.key] = rec
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun readRow(storedKey: String, row: JSONObject): OverrideRecord? {
        val kind = RequestKind.fromWire(row.optString("kind"))
            ?: if (storedKey.startsWith("group:")) RequestKind.GROUP
            else if (storedKey.startsWith("settings:")) RequestKind.SETTINGS
            else RequestKind.APP
        val targetId = row.optString("targetId").ifBlank {
            when {
                storedKey.startsWith("${kind.wire}:") -> storedKey.removePrefix("${kind.wire}:")
                kind == RequestKind.SETTINGS -> SETTINGS_TARGET
                else -> storedKey
            }
        }.ifBlank { return null }
        return OverrideRecord(
            kind = kind,
            targetId = targetId,
            pendingId = row.optString("pendingId").ifBlank { null },
            lastRequestDay = row.optString("lastRequestDay"),
            lastStatus = row.optString("lastStatus"),
            comment = row.optString("comment"),
            minutes = row.optInt("minutes"),
            overrideUntilEpochMs = row.optLong("overrideUntilEpochMs"),
        )
    }

    private fun persist(records: Map<String, OverrideRecord>) {
        val obj = JSONObject()
        for ((_, rec) in records) {
            obj.put(
                rec.key,
                JSONObject()
                    .put("kind", rec.kind.wire)
                    .put("targetId", rec.targetId)
                    .put("pendingId", rec.pendingId ?: "")
                    .put("lastRequestDay", rec.lastRequestDay)
                    .put("lastStatus", rec.lastStatus)
                    .put("comment", rec.comment)
                    .put("minutes", rec.minutes)
                    .put("overrideUntilEpochMs", rec.overrideUntilEpochMs),
            )
        }
        prefs.edit().putString(KEY_APPS, obj.toString()).apply()
    }

    companion object {
        private const val KEY_DEVICE = "device_id"
        private const val KEY_SERVER = "server_url"
        private const val KEY_APPS = "apps"
        private const val KEY_WALL = "last_wall"
        private const val KEY_QUOTA_RESET_V1 = "daily_request_quota_reset_v1"
        private const val CLOCK_ROLLBACK_MS = 2L * 60L * 1000L

        fun localDay(now: Long = System.currentTimeMillis()): String {
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return "%04d-%02d-%02d".format(y, m, d)
        }
    }
}
