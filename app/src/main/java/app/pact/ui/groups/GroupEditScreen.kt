package app.pact.ui.groups

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pact.PactApp
import app.pact.data.AppGroupWithMembers
import app.pact.data.InstalledApp
import app.pact.data.WindowMode
import app.pact.override.OverridePhase
import app.pact.override.OverrideSnapshot
import app.pact.override.RequestKind
import app.pact.ui.common.ApprovalCard
import app.pact.ui.common.AppIcon
import app.pact.ui.common.QuotaBar
import app.pact.ui.common.DEFAULT_WINDOW_END
import app.pact.ui.common.DEFAULT_WINDOW_START
import app.pact.ui.common.PresetChip
import app.pact.ui.common.TimeWindowCard
import app.pact.ui.common.pickTime
import app.pact.ui.common.windowRuleLabel
import app.pact.ui.theme.Danger
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

data class PickerApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val selected: Boolean,
    val otherGroupName: String?,
    val usedMs: Long = 0L,
)

data class GroupEditState(
    val name: String = "",
    val minutesText: String = "",
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val members: List<PickerApp> = emptyList(),
    val apps: List<PickerApp> = emptyList(),
    val missing: Boolean = false,
    val usedMs: Long = 0L,
    val canSave: Boolean = false,
    val canEdit: Boolean = false,
    val windowMode: WindowMode = WindowMode.NONE,
    val windowStartMinutes: Int = DEFAULT_WINDOW_START,
    val windowEndMinutes: Int = DEFAULT_WINDOW_END,
    val savedWindow: Boolean = false,
    val settings: OverrideSnapshot = OverrideSnapshot(OverridePhase.AVAILABLE),
    val groupAccess: OverrideSnapshot = OverrideSnapshot(OverridePhase.AVAILABLE),
    val groupBlocked: Boolean = false,
)

class GroupEditViewModel(
    private val app: PactApp,
    private val groupId: String?,
) : ViewModel() {
    private val name = MutableStateFlow("")
    private val minutesText = MutableStateFlow("")
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val windowMode = MutableStateFlow(WindowMode.NONE)
    private val windowStart = MutableStateFlow(DEFAULT_WINDOW_START)
    private val windowEnd = MutableStateFlow(DEFAULT_WINDOW_END)
    private val savedWindow = MutableStateFlow(false)
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val missing = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)
    private val tick = MutableStateFlow(0L)

    val state = combine(
        combine(name, minutesText, query, selected) { n, m, q, s -> Quad(n, m, q, s) },
        combine(windowMode, windowStart, windowEnd, savedWindow) { mode, start, end, saved ->
            WindowDraft(mode, start, end, saved)
        },
        combine(installed, app.groups.observeAll(), missing, loaded, tick) { apps, groups, gone, ready, _ ->
            Catalog(apps, groups, gone, ready)
        },
        app.overrides.records,
    ) { form, window, catalog, _ ->
        val otherByPkg = HashMap<String, String>()
        for (item in catalog.groups) {
            if (item.group.id == groupId) continue
            val label = item.group.name
            for (member in item.members) otherByPkg[member.packageName] = label
        }
        val needle = form.query.trim().lowercase()
        fun toPicker(item: InstalledApp) = PickerApp(
            packageName = item.packageName,
            label = item.label,
            icon = item.icon,
            selected = item.packageName in form.selected,
            otherGroupName = otherByPkg[item.packageName],
            usedMs = app.engine.usedMs(item.packageName),
        )
        val members = catalog.apps
            .asSequence()
            .filter { it.packageName in form.selected }
            .map(::toPicker)
            .sortedBy { it.label.lowercase() }
            .toList()
        val rows = catalog.apps
            .asSequence()
            .filter { item ->
                item.packageName !in form.selected &&
                    (needle.isEmpty() || item.label.lowercase().contains(needle))
            }
            .map(::toPicker)
            .sortedBy { it.label.lowercase() }
            .toList()
        val limit = form.minutes.toIntOrNull() ?: 0
        val hasWindow = window.mode != WindowMode.NONE && window.start != window.end
        val settings = app.overrides.settingsSnapshot()
        val canEdit = settings.phase == OverridePhase.ACTIVE
        val groupAccess = if (groupId != null) {
            app.overrides.snapshot(RequestKind.GROUP, groupId)
        } else {
            OverrideSnapshot(OverridePhase.AVAILABLE, RequestKind.GROUP)
        }
        val groupBlocked = groupId != null &&
            app.engine.groups().firstOrNull { it.id == groupId }?.let { app.engine.groupRestrictionHit(it) } == true
        GroupEditState(
            name = form.name,
            minutesText = form.minutes,
            query = form.query,
            selected = form.selected,
            members = members,
            apps = rows,
            missing = groupId != null && catalog.ready && catalog.gone,
            usedMs = if (groupId != null) app.engine.groupUsedMs(groupId) else 0L,
            canSave = canEdit &&
                form.name.trim().isNotEmpty() &&
                form.selected.isNotEmpty() &&
                (limit > 0 || hasWindow || groupId != null),
            canEdit = canEdit,
            windowMode = window.mode,
            windowStartMinutes = window.start,
            windowEndMinutes = window.end,
            savedWindow = window.saved,
            settings = settings,
            groupAccess = groupAccess,
            groupBlocked = groupBlocked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupEditState())

    init {
        viewModelScope.launch {
            if (groupId != null) {
                val existing = app.groups.get(groupId)
                if (existing == null) {
                    missing.value = true
                } else {
                    name.value = existing.group.name
                    minutesText.value = existing.group.dailyLimitMinutes
                        .takeIf { it > 0 }?.toString().orEmpty()
                    selected.value = existing.packageNames
                    val mode = existing.group.window
                    windowMode.value = mode
                    val hasWindow = existing.group.hasWindow
                    savedWindow.value = hasWindow
                    windowStart.value = if (hasWindow) existing.group.windowStartMinutes else DEFAULT_WINDOW_START
                    windowEnd.value = if (hasWindow) existing.group.windowEndMinutes else DEFAULT_WINDOW_END
                }
            }
            val extras = app.groups.getAll().flatMap { it.packageNames } + selected.value
            installed.value = app.installed.userApps(extras)
            loaded.value = true
        }
        viewModelScope.launch {
            while (isActive) {
                tick.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    fun onName(value: String) {
        name.value = value.take(40)
    }

    fun onMinutes(value: String) {
        minutesText.value = value.filter { it.isDigit() }.take(4)
    }

    fun onSearch(value: String) {
        query.value = value
    }

    fun onWindowMode(mode: WindowMode) {
        windowMode.value = mode
    }

    fun onWindowStart(minutes: Int) {
        windowStart.value = minutes
    }

    fun onWindowEnd(minutes: Int) {
        windowEnd.value = minutes
    }

    fun clearWindow() {
        windowMode.value = WindowMode.NONE
        savedWindow.value = false
    }

    fun toggle(packageName: String) {
        if (!app.overrides.canEditSettings()) return
        val next = selected.value.toMutableSet()
        if (!next.add(packageName)) next.remove(packageName)
        selected.value = next
    }

    fun save(onDone: () -> Unit) {
        val current = state.value
        if (!current.canSave) return
        viewModelScope.launch {
            val id = app.groups.save(
                id = groupId,
                name = current.name,
                dailyLimitMinutes = current.minutesText.toIntOrNull() ?: 0,
                packages = current.selected,
                windowMode = current.windowMode,
                windowStartMinutes = current.windowStartMinutes,
                windowEndMinutes = current.windowEndMinutes,
            )
            if (id != null) onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = groupId ?: return
        viewModelScope.launch {
            if (app.groups.delete(id)) onDone()
        }
    }

    suspend fun requestSettings(minutes: Int, comment: String) {
        app.overrides.submitSettings(minutes, comment)
    }

    suspend fun requestGroupAccess(minutes: Int, comment: String) {
        val id = groupId ?: return
        val label = name.value.trim().ifEmpty { "Group" }
        app.overrides.submitGroup(id, label, minutes, comment)
    }

    private data class Quad(
        val name: String,
        val minutes: String,
        val query: String,
        val selected: Set<String>,
    )

    private data class WindowDraft(
        val mode: WindowMode,
        val start: Int,
        val end: Int,
        val saved: Boolean,
    )

    private data class Catalog(
        val apps: List<InstalledApp>,
        val groups: List<AppGroupWithMembers>,
        val gone: Boolean,
        val ready: Boolean,
    )

    companion object {
        fun factory(app: PactApp, groupId: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GroupEditViewModel(app, groupId) as T
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupEditScreen(
    groupId: String?,
    onBack: () -> Unit,
    vm: GroupEditViewModel = viewModel(
        key = groupId ?: "new",
        factory = GroupEditViewModel.factory(
            LocalContext.current.applicationContext as PactApp,
            groupId,
        ),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val presets = listOf(15, 30, 45, 60, 90)
    val editable = state.canEdit

    LaunchedEffect(state.missing) {
        if (state.missing) onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = scheme.onBackground,
                )
            }
            Text(
                if (groupId != null) "Edit group" else "New group",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onBackground,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "lock") {
                ApprovalCard(
                    snapshot = state.settings,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    title = "Settings lock",
                    body = "Creating or changing a group needs approval. After that you can edit for the granted time.",
                    requestLabel = "Request to change settings",
                    oncePerDayHint = "Once a day. After approval you can edit for that many minutes.",
                    onSubmit = vm::requestSettings,
                )
            }
            if (groupId != null && (state.groupBlocked || state.groupAccess.phase != OverridePhase.AVAILABLE)) {
                item(key = "group-access") {
                    ApprovalCard(
                        snapshot = state.groupAccess,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        title = "Group access",
                        body = "Unlock every app in this group for a few minutes.",
                        requestLabel = "Request group access",
                        oncePerDayHint = "Once a day per group.",
                        onSubmit = vm::requestGroupAccess,
                    )
                }
            }
            item(key = "form") {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = vm::onName,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    enabled = editable,
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Social media") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = pactFieldColors(),
                )
                if (state.members.isNotEmpty()) {
                    Text(
                        if (state.members.size == 1) "In this group · 1 app" else "In this group · ${state.members.size} apps",
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onBackground,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                    val summary = buildString {
                        append(formatDuration(state.usedMs, compact = false))
                        append(" used today")
                        val limit = state.minutesText.toIntOrNull() ?: 0
                        if (limit > 0) append(" of ${limit}m")
                        windowRuleLabel(state.windowMode, state.windowStartMinutes, state.windowEndMinutes)
                            ?.let { append(" · $it") }
                    }
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    val limitMs = (state.minutesText.toIntOrNull() ?: 0) * 60_000L
                    if (limitMs > 0L) {
                        QuotaBar(
                            usedMs = state.usedMs,
                            limitMs = limitMs,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                    state.members.forEach { row ->
                        PickerRow(row, enabled = editable, onToggle = { vm.toggle(row.packageName) })
                    }
                }
                Text(
                    "Daily limit",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onBackground,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                )
                OutlinedTextField(
                    value = state.minutesText,
                    onValueChange = vm::onMinutes,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    enabled = editable,
                    label = { Text("Minutes per day") },
                    placeholder = { Text("e.g. 30") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = pactFieldColors(),
                )
                FlowRow(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { preset ->
                        val selected = state.minutesText.toIntOrNull() == preset
                        PresetChip(
                            label = "${preset}m",
                            selected = selected,
                            enabled = editable,
                        ) { vm.onMinutes(preset.toString()) }
                    }
                }
                TimeWindowCard(
                    mode = state.windowMode,
                    startMinutes = state.windowStartMinutes,
                    endMinutes = state.windowEndMinutes,
                    saved = state.savedWindow,
                    enabled = editable,
                    subject = "This group",
                    onMode = vm::onWindowMode,
                    onPickStart = {
                        pickTime(context, state.windowStartMinutes, vm::onWindowStart)
                    },
                    onPickEnd = {
                        pickTime(context, state.windowEndMinutes, vm::onWindowEnd)
                    },
                    onSave = { },
                    onRemove = vm::clearWindow,
                    showSaveButton = false,
                )
                Text(
                    "Add apps",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onBackground,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                )
                Text(
                    "An app can only be in one group. Picking one that is already grouped moves it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onSearch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    placeholder = { Text("Search apps", color = scheme.onSurfaceVariant) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = pactFieldColors(),
                )
            }
            items(state.apps, key = { it.packageName }) { row ->
                PickerRow(row, enabled = editable, onToggle = { vm.toggle(row.packageName) })
            }
            if (state.apps.isEmpty()) {
                item(key = "no-apps") {
                    Text(
                        if (state.query.isBlank()) "No apps to add" else "No matching apps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
        }

        Button(
            onClick = { vm.save(onBack) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Paper),
            enabled = state.canSave,
        ) {
            Text("Save group")
        }
        if (groupId != null) {
            Text(
                "Delete group",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(enabled = editable) { confirmDelete = true }
                    .padding(12.dp),
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete group?") },
            text = {
                val label = state.name.trim().ifEmpty { "this group" }
                Text("\"$label\" will be removed. Apps keep any limits of their own.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        vm.delete(onBack)
                    },
                ) {
                    Text("Delete", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PickerRow(row: PickerApp, enabled: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val other = row.otherGroupName
            if (!row.selected && !other.isNullOrBlank()) {
                Text(
                    "In $other",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Text(
            formatDuration(row.usedMs),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        if (row.selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = Rust)
        }
    }
}
