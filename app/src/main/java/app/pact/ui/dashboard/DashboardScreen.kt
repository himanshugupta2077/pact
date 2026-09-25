package app.pact.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pact.PactApp
import app.pact.ui.common.WeekBarChart
import app.pact.ui.common.WeekPager
import app.pact.ui.groups.GroupRow
import app.pact.ui.groups.GroupRowItem
import app.pact.ui.theme.Danger
import app.pact.ui.theme.Rust
import app.pact.util.chartAxisLabel
import app.pact.util.chartAxisMaxCount
import app.pact.util.chartAxisMaxMs
import app.pact.util.formatHoursMins
import app.pact.util.formatWeekRangeFull
import app.pact.util.formatWeekdayDayMonth
import app.pact.util.shiftDays
import app.pact.util.shiftWeek
import app.pact.util.startOfDayMs
import app.pact.util.startOfWeekMs
import app.pact.util.weekdayIndexMonday
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DonutSlice(
    val label: String,
    val ms: Long,
    val colorArgb: Long,
)

data class DashboardState(
    val totalMs: Long = 0L,
    val slices: List<DonutSlice> = emptyList(),
    val groups: List<GroupRow> = emptyList(),
    val currentWeekStartMs: Long = 0L,
    val screenWeekStartMs: Long = 0L,
    val unlockWeekStartMs: Long = 0L,
    val dailyMs: List<Long> = List(7) { 0L },
    val dailyUnlocks: List<Long> = List(7) { 0L },
    val todayIndex: Int = 0,
)

class DashboardViewModel(private val app: PactApp) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state

    @Volatile
    private var screenWeekStartMs: Long = 0L

    @Volatile
    private var unlockWeekStartMs: Long = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                _state.value = snapshot()
                delay(4_000)
            }
        }
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = snapshot()
        }
    }

    fun shiftScreenWeek(deltaWeeks: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            screenWeekStartMs = clampWeek(screenWeekStartMs, deltaWeeks)
            _state.value = snapshot()
        }
    }

    fun shiftUnlockWeek(deltaWeeks: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            unlockWeekStartMs = clampWeek(unlockWeekStartMs, deltaWeeks)
            _state.value = snapshot()
        }
    }

    private fun snapshot(): DashboardState {
        val byPackage = app.usage.foregroundMsToday()
        val userApps = app.installed.userApps()
        val labels = userApps.associate { it.packageName to it.label }
        val icons = userApps.associate { it.packageName to it.icon }
        val launcher = app.installed.launcherPackage()
        val ranked = byPackage.entries
            .filter { it.value > 0 && !app.installed.isNoise(it.key) }
            .sortedByDescending { it.value }
        fun isNamed(pkg: String) =
            pkg == app.packageName || pkg == launcher || labels.containsKey(pkg)
        fun sliceLabel(pkg: String) = when (pkg) {
            app.packageName -> "Pact"
            launcher -> "Home"
            else -> labels[pkg] ?: app.installed.labelOf(pkg)
        }
        val named = ranked.filter { isNamed(it.key) }
        val top = named.take(6)
        val topSet = top.map { it.key }.toHashSet()
        val otherMs = ranked.filter { it.key !in topSet }.sumOf { it.value }
        val slices = ArrayList<DonutSlice>(top.size + 1)
        top.forEachIndexed { index, entry ->
            slices += DonutSlice(
                label = sliceLabel(entry.key),
                ms = entry.value,
                colorArgb = PALETTE[index % PALETTE.size],
            )
        }
        if (otherMs > 0) {
            slices += DonutSlice("Other", otherMs, OTHER_COLOR)
        }
        val groups = app.engine.groups().map { group ->
            val used = app.engine.groupUsedMs(group)
            GroupRow(
                id = group.id,
                name = group.name,
                usedMs = used,
                limitMinutes = group.dailyLimitMinutes,
                memberCount = group.packages.size,
                icons = group.packages.take(3).map { pkg ->
                    icons[pkg] ?: app.installed.iconOf(pkg)
                },
                blocked = app.engine.groupRestrictionHit(group),
                windowMode = group.window,
                windowStartMinutes = group.windowStartMinutes,
                windowEndMinutes = group.windowEndMinutes,
            )
        }
        val currentWeek = startOfWeekMs()
        if (screenWeekStartMs == 0L) screenWeekStartMs = currentWeek
        if (unlockWeekStartMs == 0L) unlockWeekStartMs = currentWeek
        val screenWeek = app.usage.weekDays(screenWeekStartMs)
        val unlockWeek = if (unlockWeekStartMs == screenWeekStartMs) {
            screenWeek
        } else {
            app.usage.weekDays(unlockWeekStartMs)
        }
        val dailyMs = screenWeek.map { day ->
            day.byPackage.entries.sumOf { (pkg, ms) ->
                if (app.installed.isNoise(pkg)) 0L else ms
            }
        }
        val dailyUnlocks = unlockWeek.map { it.unlocks.toLong() }
        return DashboardState(
            totalMs = ranked.sumOf { it.value },
            slices = slices,
            groups = groups,
            currentWeekStartMs = currentWeek,
            screenWeekStartMs = screenWeekStartMs,
            unlockWeekStartMs = unlockWeekStartMs,
            dailyMs = dailyMs,
            dailyUnlocks = dailyUnlocks,
            todayIndex = weekdayIndexMonday(startOfDayMs()),
        )
    }

    private fun clampWeek(fromMs: Long, deltaWeeks: Int): Long {
        val current = startOfWeekMs()
        val earliest = shiftWeek(current, -MAX_WEEK_LOOKBACK)
        val base = if (fromMs == 0L) current else fromMs
        val next = shiftWeek(base, deltaWeeks)
        return next.coerceIn(earliest, current)
    }

    companion object {
        private const val MAX_WEEK_LOOKBACK = 16
        private val PALETTE = longArrayOf(
            0xFF5B9CFF,
            0xFFE85D4C,
            0xFFF5C542,
            0xFF3DCC7A,
            0xFF9B6BFF,
            0xFF2EC4B6,
            0xFFFF8A3D,
        )
        private const val OTHER_COLOR = 0xFFA3A04A

        fun factory(app: PactApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(app) as T
            }
    }
}

@Composable
fun DashboardScreen(
    usageOk: Boolean,
    accessOk: Boolean,
    notificationOk: Boolean,
    onOpenSetup: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenGroup: (String) -> Unit,
    vm: DashboardViewModel = viewModel(
        factory = DashboardViewModel.factory(LocalContext.current.applicationContext as PactApp),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var screenDay by remember { mutableIntStateOf(weekdayIndexMonday(startOfDayMs())) }
    var unlockDay by remember { mutableIntStateOf(weekdayIndexMonday(startOfDayMs())) }
    val screenMax = weekDayCap(state.screenWeekStartMs, state.currentWeekStartMs, state.todayIndex)
    val unlockMax = weekDayCap(state.unlockWeekStartMs, state.currentWeekStartMs, state.todayIndex)
    val screenIndex = screenDay.coerceIn(0, screenMax)
    val unlockIndex = unlockDay.coerceIn(0, unlockMax)
    val canScreenPrev = state.screenWeekStartMs > 0L &&
        state.currentWeekStartMs > 0L &&
        state.screenWeekStartMs > shiftWeek(state.currentWeekStartMs, -16)
    val canScreenNext = state.screenWeekStartMs > 0L &&
        state.currentWeekStartMs > 0L &&
        state.screenWeekStartMs < state.currentWeekStartMs
    val canUnlockPrev = state.unlockWeekStartMs > 0L &&
        state.currentWeekStartMs > 0L &&
        state.unlockWeekStartMs > shiftWeek(state.currentWeekStartMs, -16)
    val canUnlockNext = state.unlockWeekStartMs > 0L &&
        state.currentWeekStartMs > 0L &&
        state.unlockWeekStartMs < state.currentWeekStartMs

    LifecycleResumeEffect(Unit) {
        vm.reload()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PACT", style = MaterialTheme.typography.labelSmall, color = Rust, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSetup) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Permissions",
                    tint = scheme.onBackground,
                )
            }
        }

        if (!usageOk || !accessOk || !notificationOk) {
            PermissionBanner(
                text = buildString {
                    if (!usageOk) append("Usage access is off. Times will be empty. ")
                    if (!accessOk) append("Accessibility is off. Blocking will not run. ")
                    if (!notificationOk) append("Notification access is off. Counts and media pause will miss events.")
                }.trim(),
                onClick = onOpenSetup,
            )
        }

        Text(
            "Screen time today",
            style = MaterialTheme.typography.headlineLarge,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        )
        UsageRing(
            slices = state.slices,
            totalMs = state.totalMs,
            onClick = onOpenApps,
        )

        Spacer(Modifier.height(28.dp))
        SectionLink(title = "Apps", contentDescription = "Open apps", onClick = onOpenApps)

        Spacer(Modifier.height(36.dp))
        WeekSection(
            title = "Screen time this week",
            value = formatHoursMins(state.dailyMs.getOrElse(screenIndex) { 0L }),
            caption = dayCaption(state.screenWeekStartMs, screenIndex, state.currentWeekStartMs, state.todayIndex),
            weekStartMs = state.screenWeekStartMs,
            canPrevWeek = canScreenPrev,
            canNextWeek = canScreenNext,
            onPrevWeek = { vm.shiftScreenWeek(-1) },
            onNextWeek = { vm.shiftScreenWeek(1) },
            values = state.dailyMs,
            selectedIndex = screenIndex,
            maxSelectableIndex = screenMax,
            onSelectIndex = { screenDay = it },
            axisMax = chartAxisMaxMs(state.dailyMs.maxOrNull() ?: 0L),
            timeAxis = true,
        )
        Spacer(Modifier.height(36.dp))
        WeekSection(
            title = "Unlocks this week",
            value = state.dailyUnlocks.getOrElse(unlockIndex) { 0L }.toString(),
            caption = dayCaption(state.unlockWeekStartMs, unlockIndex, state.currentWeekStartMs, state.todayIndex),
            weekStartMs = state.unlockWeekStartMs,
            canPrevWeek = canUnlockPrev,
            canNextWeek = canUnlockNext,
            onPrevWeek = { vm.shiftUnlockWeek(-1) },
            onNextWeek = { vm.shiftUnlockWeek(1) },
            values = state.dailyUnlocks,
            selectedIndex = unlockIndex,
            maxSelectableIndex = unlockMax,
            onSelectIndex = { unlockDay = it },
            axisMax = chartAxisMaxCount(state.dailyUnlocks.maxOrNull() ?: 0L),
            timeAxis = false,
        )

        Spacer(Modifier.height(36.dp))
        SectionLink(title = "Groups", contentDescription = "Open groups", onClick = onOpenGroups)
        if (state.groups.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            state.groups.forEach { row ->
                GroupRowItem(row, onClick = { onOpenGroup(row.id) })
            }
        } else {
            Text(
                "Share one daily limit across several apps.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SectionLink(title: String, contentDescription: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = contentDescription,
            tint = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekSection(
    title: String,
    value: String,
    caption: String,
    weekStartMs: Long,
    canPrevWeek: Boolean,
    canNextWeek: Boolean,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    values: List<Long>,
    selectedIndex: Int,
    maxSelectableIndex: Int,
    onSelectIndex: (Int) -> Unit,
    axisMax: Long,
    timeAxis: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val top = if (timeAxis) chartAxisLabel(axisMax) else axisMax.toString()
    val mid = if (timeAxis) chartAxisLabel(axisMax / 2L) else (axisMax / 2L).toString()
    val bottom = if (timeAxis) "0h" else "0"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        WeekBarChart(
            values = values,
            selectedIndex = selectedIndex,
            maxSelectableIndex = maxSelectableIndex,
            onSelectIndex = onSelectIndex,
            axisMax = axisMax,
            axisTop = top,
            axisMid = mid,
            axisBottom = bottom,
        )
        Spacer(Modifier.height(10.dp))
        WeekPager(
            label = if (weekStartMs > 0L) formatWeekRangeFull(weekStartMs) else "",
            canPrev = canPrevWeek,
            canNext = canNextWeek,
            onPrev = onPrevWeek,
            onNext = onNextWeek,
        )
    }
}

private fun weekDayCap(weekStartMs: Long, currentWeekStartMs: Long, todayIndex: Int): Int {
    if (weekStartMs <= 0L || currentWeekStartMs <= 0L) return todayIndex.coerceIn(0, 6)
    return if (weekStartMs >= currentWeekStartMs) todayIndex.coerceIn(0, 6) else 6
}

private fun dayCaption(
    weekStartMs: Long,
    selectedIndex: Int,
    currentWeekStartMs: Long,
    todayIndex: Int,
): String {
    if (weekStartMs <= 0L) return ""
    if (weekStartMs == currentWeekStartMs && selectedIndex == todayIndex) return "Today"
    return formatWeekdayDayMonth(shiftDays(weekStartMs, selectedIndex))
}

@Composable
private fun UsageRing(
    slices: List<DonutSlice>,
    totalMs: Long,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.outline.copy(alpha = 0.35f)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onBackground)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val strokeWidth = 16.dp.toPx()
            val sidePad = 80.dp.toPx()
            val radius = (min(size.width, size.height) / 2f - sidePad)
                .coerceIn(90.dp.toPx(), 116.dp.toPx())
            val ringOrigin = Offset(cx - radius, cy - radius)
            val ringSize = Size(radius * 2f, radius * 2f)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = ringOrigin,
                size = ringSize,
                style = stroke,
            )
            if (totalMs > 0L && slices.isNotEmpty()) {
                var start = -90f
                for (slice in slices) {
                    val sweep = slice.ms / totalMs.toFloat() * 360f
                    val gap = if (sweep > 4f) 2.2f else 0f
                    drawArc(
                        color = Color(slice.colorArgb),
                        startAngle = start,
                        sweepAngle = (sweep - gap).coerceAtLeast(0.4f),
                        useCenter = false,
                        topLeft = ringOrigin,
                        size = ringSize,
                        style = stroke,
                    )
                    start += sweep
                }
                val labels = placeRingLabels(
                    measurer = measurer,
                    style = labelStyle,
                    slices = slices,
                    totalMs = totalMs,
                    cx = cx,
                    cy = cy,
                    labelRadius = radius + strokeWidth / 2f + 10.dp.toPx(),
                    width = size.width,
                    height = size.height,
                    pad = 6.dp.toPx(),
                    gap = 3.dp.toPx(),
                    maxLabelWidth = size.width * 0.36f,
                )
                for (label in labels) {
                    drawText(textLayoutResult = label.layout, topLeft = Offset(label.x, label.y))
                }
            }
        }
        Text(
            formatHoursMins(totalMs),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

private class RingLabel(
    val layout: TextLayoutResult,
    val right: Boolean,
    var x: Float,
    var y: Float,
    val ms: Long,
)

private fun placeRingLabels(
    measurer: TextMeasurer,
    style: TextStyle,
    slices: List<DonutSlice>,
    totalMs: Long,
    cx: Float,
    cy: Float,
    labelRadius: Float,
    width: Float,
    height: Float,
    pad: Float,
    gap: Float,
    maxLabelWidth: Float,
): List<RingLabel> {
    val maxW = maxLabelWidth.toInt().coerceAtLeast(32)
    val built = ArrayList<RingLabel>(slices.size)
    var start = -90f
    for (slice in slices) {
        val sweep = slice.ms / totalMs.toFloat() * 360f
        val mid = start + sweep / 2f
        start += sweep
        if (slice.ms / totalMs.toFloat() < 0.045f) continue
        val layout = measurer.measure(
            text = slice.label,
            style = style,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            constraints = Constraints(maxWidth = maxW),
        )
        val rad = Math.toRadians(mid.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val px = cx + c * labelRadius
        val py = cy + s * labelRadius
        val tw = layout.size.width.toFloat()
        val th = layout.size.height.toFloat()
        val right = c >= 0f
        val x: Float
        val y: Float
        when {
            s < -0.78f -> {
                x = px - tw / 2f
                y = py - th
            }
            s > 0.78f -> {
                x = px - tw / 2f
                y = py
            }
            right -> {
                x = px + 6f
                y = py - th / 2f
            }
            else -> {
                x = px - tw - 6f
                y = py - th / 2f
            }
        }
        built += RingLabel(layout, right, x, y, slice.ms)
    }
    var remaining: List<RingLabel> = built
    repeat(4) {
        val left = remaining.filter { !it.right }.toMutableList()
        val right = remaining.filter { it.right }.toMutableList()
        unstackLabels(left, height, pad, gap)
        unstackLabels(right, height, pad, gap)
        val placed = left + right
        for (label in placed) {
            val tw = label.layout.size.width.toFloat()
            label.x = label.x.coerceIn(pad, (width - tw - pad).coerceAtLeast(pad))
        }
        val overflowing = placed.filter { label ->
            label.y < pad || label.y + label.layout.size.height > height - pad
        }
        if (overflowing.isEmpty()) return placed
        val drop = overflowing.minByOrNull { it.ms } ?: return placed
        remaining = remaining.filter { it !== drop }
    }
    return remaining
}

private fun unstackLabels(
    side: MutableList<RingLabel>,
    height: Float,
    pad: Float,
    gap: Float,
) {
    if (side.isEmpty()) return
    side.sortBy { it.y }
    for (i in 1 until side.size) {
        val prev = side[i - 1]
        val minY = prev.y + prev.layout.size.height + gap
        if (side[i].y < minY) side[i].y = minY
    }
    val last = side.last()
    val overflow = last.y + last.layout.size.height - (height - pad)
    if (overflow > 0f) {
        for (label in side) label.y -= overflow
    }
    if (side[0].y < pad) {
        side[0].y = pad
        for (i in 1 until side.size) {
            val minY = side[i - 1].y + side[i - 1].layout.size.height + gap
            if (side[i].y < minY) side[i].y = minY
        }
    }
}

@Composable
private fun PermissionBanner(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Danger.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Danger, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}
