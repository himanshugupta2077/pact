package app.pact.ui.detail

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pact.PactApp
import app.pact.data.WindowMode
import app.pact.override.OverridePhase
import app.pact.override.OverrideSnapshot
import app.pact.ui.common.ApprovalCard
import app.pact.ui.common.AppIcon
import app.pact.ui.common.DEFAULT_WINDOW_END
import app.pact.ui.common.DEFAULT_WINDOW_START
import app.pact.ui.common.PresetChip
import app.pact.ui.common.TimeWindowCard
import app.pact.ui.common.pickTime
import app.pact.ui.common.QuotaBar
import app.pact.ui.common.windowRuleLabel
import app.pact.ui.theme.Paper
import app.pact.ui.theme.Rust
import app.pact.ui.theme.pactFieldColors
import app.pact.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AppDetailState(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val usedMs: Long,
    val limitMinutes: Int,
    val remainingMs: Long?,
    val blocked: Boolean,
    val windowMode: WindowMode,
    val windowStartMinutes: Int,
    val windowEndMinutes: Int,
    val protectedApp: Boolean,
    val groupId: String? = null,
    val groupName: String? = null,
    val groupLimitMinutes: Int = 0,
    val groupUsedMs: Long = 0L,
    val settings: OverrideSnapshot = OverrideSnapshot(OverridePhase.AVAILABLE),
) {
    val hasWindow: Boolean
        get() = windowMode != WindowMode.NONE && windowStartMinutes != windowEndMinutes

    val canEdit: Boolean
        get() = !protectedApp && settings.phase == OverridePhase.ACTIVE
}

class AppDetailViewModel(
    private val app: PactApp,
    private val packageName: String,
) : ViewModel() {
    private val tick = MutableStateFlow(0L)
    private val meta = MutableStateFlow(
        Triple(
            app.installed.labelOf(packageName),
            app.installed.iconOf(packageName),
            app.engine.isProtected(packageName),
        ),
    )

    val state = combine(
        app.rules.observe(packageName),
        app.groups.observeAll(),
        app.overrides.records,
        tick,
        meta,
    ) { rule, groups, _, _, info ->
        val used = app.engine.usedMs(packageName)
        val group = groups.firstOrNull { item ->
            item.members.any { it.packageName == packageName }
        }
        AppDetailState(
            packageName = packageName,
            label = info.first,
            icon = info.second,
            usedMs = used,
            limitMinutes = rule?.dailyLimitMinutes ?: 0,
            remainingMs = app.engine.remainingMs(packageName),
            blocked = app.engine.shouldBlock(packageName),
            windowMode = rule?.window ?: WindowMode.NONE,
            windowStartMinutes = rule?.windowStartMinutes ?: 0,
            windowEndMinutes = rule?.windowEndMinutes ?: 0,
            protectedApp = info.third,
            groupId = group?.group?.id,
            groupName = group?.group?.name,
            groupLimitMinutes = group?.group?.dailyLimitMinutes ?: 0,
            groupUsedMs = group?.let { app.engine.groupUsedMs(it.group.id) } ?: 0L,
            settings = app.overrides.settingsSnapshot(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(3_000),
        AppDetailState(
            packageName = packageName,
            label = meta.value.first,
            icon = meta.value.second,
            usedMs = 0,
            limitMinutes = 0,
            remainingMs = null,
            blocked = false,
            windowMode = WindowMode.NONE,
            windowStartMinutes = 0,
            windowEndMinutes = 0,
            protectedApp = meta.value.third,
        ),
    )

    init {
        viewModelScope.launch {
            while (isActive) {
                tick.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    fun saveLimit(minutes: Int) {
        viewModelScope.launch { app.rules.setLimit(packageName, minutes) }
    }

    fun saveWindow(mode: WindowMode, startMinutes: Int, endMinutes: Int) {
        viewModelScope.launch { app.rules.setWindow(packageName, mode, startMinutes, endMinutes) }
    }

    fun removeLimit() {
        viewModelScope.launch { app.rules.setLimit(packageName, 0) }
    }

    suspend fun requestSettings(minutes: Int, comment: String) {
        app.overrides.submitSettings(minutes, comment)
    }

    companion object {
        fun factory(app: PactApp, pkg: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AppDetailViewModel(app, pkg) as T
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    vm: AppDetailViewModel = viewModel(
        factory = AppDetailViewModel.factory(LocalContext.current.applicationContext as PactApp, packageName),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var minutesText by remember(state.limitMinutes) {
        mutableStateOf(if (state.limitMinutes > 0) state.limitMinutes.toString() else "")
    }
    var windowMode by remember(state.windowMode) { mutableStateOf(state.windowMode) }
    var windowStart by remember(state.windowStartMinutes, state.hasWindow) {
        mutableIntStateOf(if (state.hasWindow) state.windowStartMinutes else DEFAULT_WINDOW_START)
    }
    var windowEnd by remember(state.windowEndMinutes, state.hasWindow) {
        mutableIntStateOf(if (state.hasWindow) state.windowEndMinutes else DEFAULT_WINDOW_END)
    }
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val presets = listOf(15, 30, 45, 60, 90)
    val limitMinutes = minutesText.toIntOrNull() ?: 0
    val editable = state.canEdit
    val usedHint = when {
        state.protectedApp -> "This app cannot be blocked (recovery / system)."
        state.blocked && (state.remainingMs ?: 1L) <= 0L &&
            (state.limitMinutes > 0 || state.groupLimitMinutes > 0) -> {
            val groupName = state.groupName
            val groupHit = state.groupLimitMinutes > 0 &&
                state.groupUsedMs >= state.groupLimitMinutes * 60_000L
            if (!groupName.isNullOrBlank() && groupHit) {
                "Locked with $groupName until midnight."
            } else {
                "Locked until midnight."
            }
        }
        state.blocked && state.hasWindow -> when (state.windowMode) {
            WindowMode.ALLOW -> "Outside the allowed hours."
            WindowMode.BLOCK -> "Blocked by the time window."
            WindowMode.NONE -> null
        }
        state.remainingMs != null -> {
            val remaining = state.remainingMs ?: 0L
            "${formatDuration(remaining, compact = false)} remaining"
        }
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = scheme.onBackground,
                )
            }
            Text("Limit", style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            AppIcon(state.icon, size = 48)
            Spacer(Modifier.height(10.dp))
            Text(state.label, style = MaterialTheme.typography.headlineMedium, color = scheme.onBackground)
        }

        Spacer(Modifier.height(28.dp))
        StatBlock(
            title = "Used today",
            value = formatDuration(state.usedMs, compact = false),
            hint = usedHint,
            usedMs = state.usedMs,
            limitMs = if (state.limitMinutes > 0) state.limitMinutes * 60_000L else 0L,
        )

        val groupId = state.groupId
        val groupName = state.groupName
        if (groupId != null && !groupName.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surface)
                    .border(1.dp, scheme.outline, RoundedCornerShape(16.dp))
                    .clickable { onOpenGroup(groupId) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("GROUP", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(groupName, style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
                    val limit = state.groupLimitMinutes
                    val sub = buildString {
                        append(formatDuration(state.groupUsedMs, compact = false))
                        append(" used")
                        if (limit > 0) append(" of ${limit}m shared")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    if (limit > 0) {
                        Spacer(Modifier.height(8.dp))
                        QuotaBar(
                            usedMs = state.groupUsedMs,
                            limitMs = limit * 60_000L,
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "Open group",
                    tint = scheme.onSurfaceVariant,
                )
            }
        }

        val windowLabel = windowRuleLabel(
            state.windowMode,
            state.windowStartMinutes,
            state.windowEndMinutes,
        )
        if (state.limitMinutes > 0 || windowLabel != null) {
            Spacer(Modifier.height(12.dp))
            CurrentRuleCard(
                limitMinutes = state.limitMinutes,
                windowLabel = windowLabel,
            )
        }

        if (!state.protectedApp) {
            Spacer(Modifier.height(12.dp))
            ApprovalCard(
                snapshot = state.settings,
                modifier = Modifier.padding(horizontal = 24.dp),
                title = "Settings lock",
                body = "Daily limits and time windows stay as they are until someone approves a change.",
                requestLabel = "Request to change settings",
                oncePerDayHint = "Once a day. After approval you can edit for that many minutes.",
                onSubmit = vm::requestSettings,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Daily limit",
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = minutesText,
            onValueChange = { value -> minutesText = value.filter { it.isDigit() }.take(4) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            enabled = editable,
            label = { Text("Minutes per day") },
            placeholder = { Text("e.g. 30") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = pactFieldColors(),
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                val selected = minutesText.toIntOrNull() == preset
                PresetChip(label = "${preset}m", selected = selected, enabled = editable) {
                    minutesText = preset.toString()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.saveLimit(limitMinutes) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Paper),
            enabled = editable && limitMinutes > 0,
        ) {
            Text("Save limit")
        }
        if (state.limitMinutes > 0) {
            Text(
                "Remove limit",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(enabled = editable) {
                        vm.removeLimit()
                        minutesText = ""
                    }
                    .padding(12.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        TimeWindowCard(
            mode = windowMode,
            startMinutes = windowStart,
            endMinutes = windowEnd,
            saved = state.hasWindow,
            enabled = editable,
            onMode = { windowMode = it },
            onPickStart = {
                pickTime(context, windowStart) { windowStart = it }
            },
            onPickEnd = {
                pickTime(context, windowEnd) { windowEnd = it }
            },
            onSave = { vm.saveWindow(windowMode, windowStart, windowEnd) },
            onRemove = {
                windowMode = WindowMode.NONE
                vm.saveWindow(WindowMode.NONE, 0, 0)
            },
        )
    }
}

@Composable
private fun CurrentRuleCard(limitMinutes: Int, windowLabel: String?) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text("RULE", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        if (limitMinutes > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "$limitMinutes minutes per day",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onBackground,
            )
        }
        if (!windowLabel.isNullOrBlank()) {
            Spacer(Modifier.height(if (limitMinutes > 0) 4.dp else 8.dp))
            Text(
                windowLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onBackground,
            )
        }
    }
}

@Composable
private fun StatBlock(
    title: String,
    value: String,
    hint: String?,
    usedMs: Long = 0L,
    limitMs: Long = 0L,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineLarge, color = scheme.onBackground)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        if (limitMs > 0L) {
            Spacer(Modifier.height(10.dp))
            QuotaBar(usedMs = usedMs, limitMs = limitMs)
        }
    }
}
