package app.pact.override

enum class OverridePhase {
    AVAILABLE,
    WAITING,
    DENIED,
    USED,
    ACTIVE,
}

enum class RequestKind(val wire: String) {
    APP("app"),
    GROUP("group"),
    SETTINGS("settings");

    fun packageNameField(targetId: String): String = when (this) {
        APP -> targetId
        GROUP -> "group.$targetId"
        SETTINGS -> SETTINGS_PACKAGE
    }

    companion object {
        fun fromWire(raw: String?): RequestKind? {
            val key = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == key }
        }
    }
}

data class OverrideRecord(
    val kind: RequestKind,
    val targetId: String,
    val pendingId: String? = null,
    val lastRequestDay: String = "",
    val lastStatus: String = "",
    val comment: String = "",
    val minutes: Int = 0,
    val overrideUntilEpochMs: Long = 0L,
) {
    val key: String get() = recordKey(kind, targetId)
}

data class OverrideSnapshot(
    val phase: OverridePhase,
    val kind: RequestKind = RequestKind.APP,
    val targetId: String = "",
    val pendingId: String? = null,
    val minutes: Int = 0,
    val comment: String = "",
    val untilEpochMs: Long = 0L,
) {
    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        if (phase != OverridePhase.ACTIVE) return 0L
        return (untilEpochMs - now).coerceAtLeast(0L)
    }
}

data class RemoteRequest(
    val id: String,
    val status: String,
    val kind: RequestKind,
    val targetId: String,
    val packageName: String,
    val appLabel: String,
    val minutes: Int,
    val comment: String,
    val day: String,
    val expiresAtMs: Long,
    val live: Boolean,
)

class OverrideException(
    val error: String,
    override val message: String,
    val remote: RemoteRequest? = null,
) : Exception(message)

fun recordKey(kind: RequestKind, targetId: String): String = "${kind.wire}:$targetId"

const val DEFAULT_PACT_SERVER = "https://pact.example"
const val MAX_OVERRIDE_MINUTES = 15
const val SETTINGS_TARGET = "settings"
const val SETTINGS_PACKAGE = "pact.settings"
val OVERRIDE_MINUTE_CHIPS = listOf(1, 5, 10, 15)
