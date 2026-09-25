package app.pact.engine

import android.content.Context
import android.os.SystemClock
import app.pact.data.AppRule
import app.pact.data.GroupRule
import app.pact.data.InstalledApps
import app.pact.data.OverlayExclusionStore
import app.pact.data.PopupExclusionStore
import app.pact.data.UsageRepository
import app.pact.data.WindowMode
import app.pact.util.formatClockMinutes
import app.pact.util.minutesOfDay
import app.pact.util.startOfDayMs
import java.util.concurrent.ConcurrentHashMap

enum class BlockCause {
    NONE,
    APP_LIMIT,
    GROUP_LIMIT,
    APP_WINDOW,
    GROUP_WINDOW,
}

data class BlockDecision(
    val cause: BlockCause = BlockCause.NONE,
    val groupName: String? = null,
    val groupUsedMs: Long = 0L,
    val groupLimitMinutes: Int = 0,
    val windowMode: WindowMode = WindowMode.NONE,
    val windowStartMinutes: Int = 0,
    val windowEndMinutes: Int = 0,
)

class RuleEngine(
    private val context: Context,
    private val usageRepository: UsageRepository,
    private val installedApps: InstalledApps,
    private val exclusions: OverlayExclusionStore,
    private val popups: PopupExclusionStore,
) {
    @Volatile
    private var rules: Map<String, AppRule> = emptyMap()

    @Volatile
    private var groupIndex: GroupIndex = GroupIndex.Empty

    @Volatile
    private var appGrants: Map<String, Long> = emptyMap()

    @Volatile
    private var groupGrants: Map<String, Long> = emptyMap()

    @Volatile
    private var settingsUntil: Long = 0L

    private val usageBase = ConcurrentHashMap<String, Long>()

    @Volatile
    private var popupPackages: Set<String> = emptySet()

    @Volatile
    private var fgPackage: String? = null

    @Volatile
    private var fgSinceElapsed: Long = 0L

    @Volatile
    private var lastUsageRefreshElapsed: Long = 0L

    fun replaceRules(next: List<AppRule>) {
        rules = next.associateBy { it.packageName }
    }

    fun replaceGrants(
        apps: Map<String, Long>,
        groups: Map<String, Long>,
        settingsUntilMs: Long,
    ) {
        appGrants = apps
        groupGrants = groups
        settingsUntil = settingsUntilMs
    }

    fun hasActiveOverride(packageName: String, now: Long = System.currentTimeMillis()): Boolean {
        val until = appGrants[packageName] ?: 0L
        if (until > now) return true
        val group = groupFor(packageName) ?: return false
        return (groupGrants[group.id] ?: 0L) > now
    }

    fun hasActiveGroupOverride(groupId: String, now: Long = System.currentTimeMillis()): Boolean {
        return (groupGrants[groupId] ?: 0L) > now
    }

    fun canEditSettings(now: Long = System.currentTimeMillis()): Boolean {
        return settingsUntil > now
    }

    fun settingsUntilMs(): Long = settingsUntil

    fun replaceGroups(next: List<GroupRule>) {
        val byId = next.associateBy { it.id }
        val byPackage = LinkedHashMap<String, String>(next.sumOf { it.packages.size })
        for (group in next) {
            for (pkg in group.packages) byPackage[pkg] = group.id
        }
        groupIndex = GroupIndex(byId, byPackage, next)
    }

    fun ruleFor(packageName: String): AppRule? = rules[packageName]

    fun groups(): List<GroupRule> = groupIndex.all

    fun groupFor(packageName: String): GroupRule? {
        val id = groupIndex.byPackage[packageName] ?: return null
        return groupIndex.byId[id]
    }

    fun onForeground(packageName: String) {
        if (packageName == context.packageName) return
        if (packageName == "android" || packageName == "com.android.systemui") return
        if (packageName.startsWith("com.android.systemui.")) return
        if (packageName in popupPackages) return
        if (fgPackage != packageName) {
            fgPackage = packageName
            fgSinceElapsed = SystemClock.elapsedRealtime()
        }
    }

    fun setPopupPackages(packages: Set<String>) {
        popupPackages = packages
        popups.setPackages(packages)
        val fg = fgPackage
        if (fg != null && fg in packages) {
            fgSinceElapsed = SystemClock.elapsedRealtime()
        }
    }

    fun setCovering(packageName: String?) {
        val prev = exclusions.coveringPackage()
        exclusions.setCovering(packageName)
        val now = SystemClock.elapsedRealtime()
        if (packageName != null && fgPackage == packageName) {
            fgSinceElapsed = now
        } else if (prev != null && packageName == null && fgPackage == prev) {
            fgSinceElapsed = now
        }
    }

    fun refreshUsage(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUsageRefreshElapsed < 1_200L) return
        lastUsageRefreshElapsed = now
        val snapshot = usageRepository.foregroundMsToday()
        usageBase.clear()
        usageBase.putAll(snapshot)
        // Stats already include the open interval up to wall-clock now.
        // Reset the in-memory extra so we don't double-count.
        fgSinceElapsed = now
    }

    fun usedMs(packageName: String): Long {
        val nowElapsed = SystemClock.elapsedRealtime()
        val fg = fgPackage
        val covering = exclusions.coveringPackage()
        val popped = fg != null && fg in popupPackages
        if ((covering != null && covering == fg) || popped) {
            fgSinceElapsed = nowElapsed
        }
        val base = usageBase[packageName] ?: 0L
        val extra = if (
            fg == packageName &&
            covering != packageName &&
            packageName !in popupPackages
        ) {
            (nowElapsed - fgSinceElapsed).coerceAtLeast(0L)
        } else {
            0L
        }
        return base + extra
    }

    fun remainingMs(packageName: String): Long? {
        val rule = rules[packageName]
        val appRemaining = if (rule != null && rule.hasLimit) {
            (rule.dailyLimitMinutes * 60_000L - usedMs(packageName)).coerceAtLeast(0L)
        } else {
            null
        }
        val group = groupFor(packageName)
        val groupRemaining = if (group != null && group.hasLimit) {
            (group.dailyLimitMinutes * 60_000L - groupUsedMs(group)).coerceAtLeast(0L)
        } else {
            null
        }
        return when {
            appRemaining != null && groupRemaining != null -> minOf(appRemaining, groupRemaining)
            appRemaining != null -> appRemaining
            else -> groupRemaining
        }
    }

    fun groupUsedMs(group: GroupRule): Long {
        var total = 0L
        for (pkg in group.packages) total += usedMs(pkg)
        return total
    }

    fun groupUsedMs(groupId: String): Long {
        val group = groupIndex.byId[groupId] ?: return 0L
        return groupUsedMs(group)
    }

    fun usedMsThisWeek(packageName: String): Long {
        val week = usageRepository.weekMs(packageName)
        val todayCached = usageRepository.cachedDayMs(startOfDayMs(), packageName)
        val todayLive = usedMs(packageName)
        return (week - todayCached + todayLive).coerceAtLeast(todayLive)
    }

    fun respectedStreak(packageName: String): Int {
        val rule = rules[packageName] ?: return 0
        if (!rule.hasLimit) return 0
        return usageRepository.respectedStreak(packageName, rule.dailyLimitMinutes)
    }

    fun isProtected(packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (packageName == installedApps.launcherPackage()) return true
        return PROTECTED_PACKAGES.any { packageName == it || packageName.startsWith("$it.") }
    }

    fun shouldBlock(packageName: String): Boolean =
        blockDecision(packageName).cause != BlockCause.NONE

    fun blockDecision(packageName: String, now: Long = System.currentTimeMillis()): BlockDecision {
        if (isProtected(packageName)) return BlockDecision()
        if (hasActiveOverride(packageName, now)) return BlockDecision()
        val rule = rules[packageName]
        val used = usedMs(packageName)
        if (rule != null && rule.hasLimit && used >= rule.dailyLimitMinutes * 60_000L) {
            return BlockDecision(cause = BlockCause.APP_LIMIT)
        }
        val group = groupFor(packageName)
        if (group != null && group.hasLimit &&
            groupUsedMs(group) >= group.dailyLimitMinutes * 60_000L
        ) {
            return BlockDecision(
                cause = BlockCause.GROUP_LIMIT,
                groupName = group.name,
                groupUsedMs = groupUsedMs(group),
                groupLimitMinutes = group.dailyLimitMinutes,
            )
        }
        val nowMinutes = minutesOfDay(now)
        if (rule != null && rule.blocksAt(nowMinutes)) {
            return BlockDecision(
                cause = BlockCause.APP_WINDOW,
                groupName = group?.name,
                windowMode = rule.window,
                windowStartMinutes = rule.windowStartMinutes,
                windowEndMinutes = rule.windowEndMinutes,
            )
        }
        if (group != null && group.blocksAt(nowMinutes)) {
            return BlockDecision(
                cause = BlockCause.GROUP_WINDOW,
                groupName = group.name,
                groupUsedMs = groupUsedMs(group),
                groupLimitMinutes = group.dailyLimitMinutes,
                windowMode = group.window,
                windowStartMinutes = group.windowStartMinutes,
                windowEndMinutes = group.windowEndMinutes,
            )
        }
        return BlockDecision()
    }

    fun groupRestrictionHit(group: GroupRule, now: Long = System.currentTimeMillis()): Boolean {
        if (hasActiveGroupOverride(group.id, now)) return false
        if (group.hasLimit && groupUsedMs(group) >= group.dailyLimitMinutes * 60_000L) return true
        return group.blocksAt(minutesOfDay())
    }

    fun nextAvailableClock(packageName: String): String? {
        val rule = rules[packageName]
        val used = usedMs(packageName)
        val group = groupFor(packageName)
        val groupHit = group != null && group.hasLimit &&
            groupUsedMs(group) >= group.dailyLimitMinutes * 60_000L
        val limitHit = (rule != null && rule.hasLimit && used >= rule.dailyLimitMinutes * 60_000L) ||
            groupHit
        val nowMinutes = minutesOfDay()
        val windowHit = rule?.blocksAt(nowMinutes) == true || group?.blocksAt(nowMinutes) == true
        if (!limitHit && !windowHit) return null
        val start = nowMinutes + 1
        val end = nowMinutes + 24 * 60 * 2
        for (t in start until end) {
            val day = t / (24 * 60)
            val minute = t % (24 * 60)
            val overLimit = day == 0 && limitHit
            val appWindow = rule?.blocksAt(minute) == true
            val groupWindow = group?.blocksAt(minute) == true
            if (!overLimit && !appWindow && !groupWindow) {
                return formatClockMinutes(minute)
            }
        }
        return formatClockMinutes(0)
    }

    fun currentlyBlockedPackages(): Set<String> {
        val out = LinkedHashSet<String>()
        for (pkg in rules.keys) if (shouldBlock(pkg)) out.add(pkg)
        for (pkg in groupIndex.byPackage.keys) if (shouldBlock(pkg)) out.add(pkg)
        return out
    }

    companion object {
        private val PROTECTED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.nothing.launcher",
            "com.nothing.hearthstone",
        )
    }

    private class GroupIndex(
        val byId: Map<String, GroupRule>,
        val byPackage: Map<String, String>,
        val all: List<GroupRule>,
    ) {
        companion object {
            val Empty = GroupIndex(emptyMap(), emptyMap(), emptyList())
        }
    }
}
