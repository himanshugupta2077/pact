package app.pact.ui.block

import app.pact.PactApp
import app.pact.data.WindowMode
import app.pact.engine.BlockCause
import app.pact.engine.BlockDecision
import app.pact.util.formatClockMinutes
import app.pact.util.formatDuration
import app.pact.util.minutesOfDay

data class LockSnapshot(
    val label: String,
    val usedToday: String,
    val usedWeek: String,
    val availableAgain: String?,
    val streakDays: Int,
    val headline: String,
    val body: String,
    val groupName: String? = null,
    val groupUsed: String? = null,
) {
    val streakLine: String?
        get() = if (streakDays >= 2) {
            "You've respected this limit $streakDays days in a row."
        } else {
            null
        }
}

data class LockLines(
    val headline: String,
    val body: String,
)

fun lockSnapshot(app: PactApp, packageName: String, seed: Long = 0L): LockSnapshot {
    val used = app.engine.usedMs(packageName)
    val week = app.engine.usedMsThisWeek(packageName)
    val decision = app.engine.blockDecision(packageName)
    val group = app.engine.groupFor(packageName)?.takeIf { it.hasRestriction }
    val groupUsed = when {
        decision.groupLimitMinutes > 0 ->
            "${formatDuration(decision.groupUsedMs, compact = false)} of ${decision.groupLimitMinutes}m"
        group != null && group.hasLimit ->
            "${formatDuration(app.engine.groupUsedMs(group), compact = false)} of ${group.dailyLimitMinutes}m"
        group != null -> formatDuration(app.engine.groupUsedMs(group), compact = false)
        else -> null
    }
    val groupName = decision.groupName ?: group?.name
    val availableAgain = app.engine.nextAvailableClock(packageName)
    val lines = lockLines(
        decision = decision,
        groupUsed = groupUsed,
        availableAgain = availableAgain,
        nowMinutes = minutesOfDay(),
        seed = seed,
    )
    return LockSnapshot(
        label = app.installed.labelOf(packageName),
        usedToday = formatDuration(used, compact = false),
        usedWeek = formatDuration(week, compact = false),
        availableAgain = availableAgain,
        streakDays = app.engine.respectedStreak(packageName),
        headline = lines.headline,
        body = lines.body,
        groupName = groupName,
        groupUsed = groupUsed,
    )
}

fun lockLines(
    decision: BlockDecision,
    groupUsed: String?,
    availableAgain: String?,
    nowMinutes: Int,
    seed: Long,
): LockLines {
    return when (decision.cause) {
        BlockCause.APP_LIMIT -> pick(APP_LIMIT_LINES, seed)
        BlockCause.GROUP_LIMIT -> pick(groupLimitLines(decision.groupName, groupUsed), seed)
        BlockCause.APP_WINDOW, BlockCause.GROUP_WINDOW -> pick(
            windowLines(
                cause = decision.cause,
                groupName = decision.groupName,
                mode = decision.windowMode,
                startMinutes = decision.windowStartMinutes,
                endMinutes = decision.windowEndMinutes,
                nowMinutes = nowMinutes,
                availableAgain = availableAgain,
            ),
            seed,
        )
        BlockCause.NONE -> pick(APP_LIMIT_LINES, seed)
    }
}

internal fun pick(lines: List<LockLines>, seed: Long): LockLines {
    if (lines.isEmpty()) return APP_LIMIT_LINES.first()
    val index = ((seed ushr 1) % lines.size).toInt().let { if (it < 0) it + lines.size else it }
    return lines[index]
}

private val APP_LIMIT_LINES = listOf(
    LockLines(
        "ENOUGH FOR TODAY",
        "You've used this app enough today. That was the deal you made with yourself.",
    ),
    LockLines(
        "THAT'S YOUR LIMIT",
        "Today's time on this app is gone. Come back tomorrow.",
    ),
    LockLines(
        "TIME'S UP",
        "This app already had its minutes. Let the rest of the day be for other things.",
    ),
    LockLines(
        "THAT'S ALL FOR NOW",
        "You gave this app its turn. Closing it now is the pact working.",
    ),
)

private fun groupLimitLines(groupName: String?, groupUsed: String?): List<LockLines> {
    val group = groupName?.takeIf { it.isNotBlank() } ?: "this group"
    val used = groupUsed?.takeIf { it.isNotBlank() }
    return listOf(
        LockLines(
            "GROUP'S DONE",
            if (used != null) {
                "You've used $used of $group today. Every app in it waits until midnight."
            } else {
                "$group is out of time for today. Every app in it waits until midnight."
            },
        ),
        LockLines(
            "SHARED TIME'S UP",
            "The $group pool is empty. That shared cap covers all of it until tomorrow.",
        ),
        LockLines(
            "POOL'S EMPTY",
            if (used != null) {
                "You spent $used on $group already. That's the shared limit you set."
            } else {
                "$group hit its shared limit. Leave the whole set alone until tomorrow."
            },
        ),
        LockLines(
            "GROUP'S DONE",
            "$group had its time today. One app using it up locks the rest until midnight.",
        ),
    )
}

private fun windowLines(
    cause: BlockCause,
    groupName: String?,
    mode: WindowMode,
    startMinutes: Int,
    endMinutes: Int,
    nowMinutes: Int,
    availableAgain: String?,
): List<LockLines> {
    val subject = if (cause == BlockCause.GROUP_WINDOW) {
        groupName?.takeIf { it.isNotBlank() } ?: "this group"
    } else {
        "this app"
    }
    val next = availableAgain ?: "later"
    val startClock = formatClockMinutes(startMinutes)
    val endClock = formatClockMinutes(endMinutes)
    val late = isLateHour(nowMinutes)
    val early = isEarlyForAllow(mode, startMinutes, endMinutes, nowMinutes)

    return when {
        late -> lateWindowLines(subject, mode, startClock, endClock)
        early -> earlyWindowLines(subject, startClock)
        else -> genericWindowLines(subject, next)
    }
}

private fun lateWindowLines(
    subject: String,
    mode: WindowMode,
    startClock: String,
    endClock: String,
): List<LockLines> {
    val cutoff = if (mode == WindowMode.ALLOW) endClock else startClock
    return listOf(
        LockLines(
            "IT'S LATE",
            "It's late. You planned not to use $subject after $cutoff. Maybe pick up a book instead.",
        ),
        LockLines(
            "CALL IT A NIGHT",
            "It's so late. After $cutoff this was supposed to stay closed. Try something slower.",
        ),
        LockLines(
            "PAST YOUR HOURS",
            "You set $cutoff as the cutoff. The night is for rest, not $subject.",
        ),
        LockLines(
            "IT'S LATE",
            "Past $cutoff. That was your line. Read, walk, sleep — anything but this.",
        ),
    )
}

private fun earlyWindowLines(subject: String, startClock: String): List<LockLines> {
    return listOf(
        LockLines(
            "TOO EARLY",
            "Not yet. You planned to wait until $startClock before opening $subject.",
        ),
        LockLines(
            "NOT YET",
            "It's early for this. $subject opens at $startClock — that was your rule.",
        ),
        LockLines(
            "HOLD ON",
            "Give it until $startClock. You decided $subject isn't for right now.",
        ),
        LockLines(
            "TOO EARLY",
            "This window hasn't started. Come back at $startClock.",
        ),
    )
}

private fun genericWindowLines(subject: String, next: String): List<LockLines> {
    return listOf(
        LockLines(
            "OUTSIDE YOUR HOURS",
            "This isn't in the hours you allowed for $subject. Next opening is $next.",
        ),
        LockLines(
            "NOT RIGHT NOW",
            "You locked this window on purpose. It opens again at $next.",
        ),
        LockLines(
            "WRONG TIME",
            "Wrong time of day for $subject. You get it back at $next.",
        ),
        LockLines(
            "OUTSIDE YOUR HOURS",
            "Stay out until $next. You set the hours so this wouldn't eat the rest of the day.",
        ),
    )
}

internal fun isLateHour(minutes: Int): Boolean {
    val hour = ((minutes % (24 * 60)) + (24 * 60)) % (24 * 60) / 60
    return hour >= 19 || hour < 5
}

internal fun isEarlyForAllow(
    mode: WindowMode,
    startMinutes: Int,
    endMinutes: Int,
    nowMinutes: Int,
): Boolean {
    if (mode != WindowMode.ALLOW) return false
    val now = ((nowMinutes % (24 * 60)) + (24 * 60)) % (24 * 60)
    val start = ((startMinutes % (24 * 60)) + (24 * 60)) % (24 * 60)
    val end = ((endMinutes % (24 * 60)) + (24 * 60)) % (24 * 60)
    if (start == end) return false
    return if (start < end) now < start else now >= end && now < start
}
