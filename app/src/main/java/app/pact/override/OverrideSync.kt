package app.pact.override

import app.pact.engine.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class OverrideSync(
    private val store: OverrideStore,
    private val client: OverrideClient,
    private val engine: RuleEngine,
) {
    val records: StateFlow<Map<String, OverrideRecord>> = store.records

    init {
        pushEngine()
    }

    fun snapshot(packageName: String): OverrideSnapshot = store.snapshot(packageName)

    fun snapshot(kind: RequestKind, targetId: String): OverrideSnapshot = store.snapshot(kind, targetId)

    fun settingsSnapshot(): OverrideSnapshot = store.settingsSnapshot()

    fun canEditSettings(): Boolean = store.canEditSettings()

    fun canEditServerUrl(): Boolean = store.canEditServerUrl()

    fun settingsRemainingMs(now: Long = System.currentTimeMillis()): Long =
        store.settingsSnapshot(now).remainingMs(now)

    fun serverUrl(): String = store.serverUrl()

    fun setServerUrl(url: String) {
        if (!store.canEditServerUrl()) return
        store.setServerUrl(url)
    }

    suspend fun submit(
        packageName: String,
        appLabel: String,
        minutes: Int,
        comment: String,
    ): OverrideSnapshot = submit(RequestKind.APP, packageName, appLabel, minutes, comment)

    suspend fun submitSettings(minutes: Int, comment: String): OverrideSnapshot =
        submit(RequestKind.SETTINGS, SETTINGS_TARGET, "Pact settings", minutes, comment)

    suspend fun submitGroup(
        groupId: String,
        groupName: String,
        minutes: Int,
        comment: String,
    ): OverrideSnapshot = submit(RequestKind.GROUP, groupId, groupName, minutes, comment)

    suspend fun submit(
        kind: RequestKind,
        targetId: String,
        label: String,
        minutes: Int,
        comment: String,
    ): OverrideSnapshot = withContext(Dispatchers.IO) {
        val capped = minutes.coerceIn(1, MAX_OVERRIDE_MINUTES)
        val trimmed = comment.trim()
        if (trimmed.isEmpty()) {
            throw OverrideException("comment_required", "Write a short comment first.")
        }
        val current = store.snapshot(kind, targetId)
        if (current.phase != OverridePhase.AVAILABLE) {
            return@withContext current
        }
        val remote = try {
            client.create(kind, targetId, label, capped, trimmed)
        } catch (e: OverrideException) {
            e.remote?.let { applyRemote(it, kind, targetId) }
            throw e
        }
        applyRemote(remote, kind, targetId)
        store.snapshot(kind, targetId)
    }

    suspend fun runLoop() {
        while (coroutineContext.isActive) {
            if (store.noteWallClock()) {
                store.expireActive()
            }
            pollPending()
            pushEngine()
            val waiting = store.records.value.values.any { !it.pendingId.isNullOrBlank() }
            delay(if (waiting) 2_500L else 12_000L)
        }
    }

    suspend fun pollPending() = withContext(Dispatchers.IO) {
        val pending = store.records.value.values.filter { !it.pendingId.isNullOrBlank() }
        for (rec in pending) {
            val id = rec.pendingId ?: continue
            val remote = runCatching { client.get(id) }.getOrNull() ?: continue
            applyRemote(remote, rec.kind, rec.targetId)
        }
        pushEngine()
    }

    private fun applyRemote(
        remote: RemoteRequest,
        fallbackKind: RequestKind? = null,
        fallbackTarget: String? = null,
    ) {
        val kind = remote.kind
        val targetId = remote.targetId.ifBlank { fallbackTarget ?: remote.packageName }
        val prev = store.get(kind, targetId) ?: fallbackKind?.let { k ->
            fallbackTarget?.let { store.get(k, it) }
        }
        val until = when {
            remote.status == "approved" && remote.expiresAtMs > 0L -> remote.expiresAtMs
            remote.status == "approved" && remote.minutes > 0 ->
                System.currentTimeMillis() + remote.minutes * 60_000L
            else -> prev?.overrideUntilEpochMs ?: 0L
        }
        val pendingId = if (remote.status == "pending") remote.id else null
        store.put(
            OverrideRecord(
                kind = kind,
                targetId = targetId,
                pendingId = pendingId,
                lastRequestDay = remote.day.ifBlank { OverrideStore.localDay() },
                lastStatus = remote.status,
                comment = remote.comment.ifBlank { prev?.comment.orEmpty() },
                minutes = if (remote.minutes > 0) remote.minutes else prev?.minutes ?: 0,
                overrideUntilEpochMs = until,
            ),
        )
        pushEngine()
    }

    private fun pushEngine() {
        engine.replaceGrants(
            apps = store.activeUntil(RequestKind.APP),
            groups = store.activeUntil(RequestKind.GROUP),
            settingsUntilMs = store.settingsUntil(),
        )
    }
}
