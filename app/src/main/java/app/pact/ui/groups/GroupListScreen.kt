package app.pact.ui.groups

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pact.PactApp
import app.pact.data.WindowMode
import app.pact.override.OverridePhase
import app.pact.override.OverrideSnapshot
import app.pact.ui.common.ApprovalCard
import app.pact.ui.common.AppIcon
import app.pact.ui.common.QuotaBar
import app.pact.ui.theme.Paper
import app.pact.ui.theme.Rust
import app.pact.ui.theme.Sage
import app.pact.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GroupRow(
    val id: String,
    val name: String,
    val usedMs: Long,
    val limitMinutes: Int,
    val memberCount: Int,
    val icons: List<Bitmap?>,
    val blocked: Boolean,
    val windowMode: WindowMode = WindowMode.NONE,
    val windowStartMinutes: Int = 0,
    val windowEndMinutes: Int = 0,
)

data class GroupListState(
    val groups: List<GroupRow> = emptyList(),
    val settings: OverrideSnapshot = OverrideSnapshot(OverridePhase.AVAILABLE),
    val canEdit: Boolean = false,
)

class GroupListViewModel(private val app: PactApp) : ViewModel() {
    private val tick = MutableStateFlow(0L)

    val state = combine(app.groups.observeAll(), app.overrides.records, tick) { stored, _, _ ->
        val settings = app.overrides.settingsSnapshot()
        GroupListState(
            groups = stored.map { item ->
                val rule = item.toRule()
                val used = app.engine.groupUsedMs(rule)
                GroupRow(
                    id = item.group.id,
                    name = item.group.name,
                    usedMs = used,
                    limitMinutes = item.group.dailyLimitMinutes,
                    memberCount = item.members.size,
                    icons = item.packageNames.take(3).map { pkg -> app.installed.iconOf(pkg) },
                    blocked = app.engine.groupRestrictionHit(rule),
                    windowMode = rule.window,
                    windowStartMinutes = rule.windowStartMinutes,
                    windowEndMinutes = rule.windowEndMinutes,
                )
            },
            settings = settings,
            canEdit = settings.phase == OverridePhase.ACTIVE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupListState())

    init {
        viewModelScope.launch {
            while (isActive) {
                tick.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    fun reload() {
        tick.value = System.currentTimeMillis()
    }

    suspend fun requestSettings(minutes: Int, comment: String) {
        app.overrides.submitSettings(minutes, comment)
    }

    companion object {
        fun factory(app: PactApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GroupListViewModel(app) as T
            }
    }
}

@Composable
fun GroupListScreen(
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenGroup: (String) -> Unit,
    vm: GroupListViewModel = viewModel(
        factory = GroupListViewModel.factory(LocalContext.current.applicationContext as PactApp),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val groups = state.groups
    val scheme = MaterialTheme.colorScheme

    LifecycleResumeEffect(Unit) {
        vm.reload()
        onPauseOrDispose { }
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
            Text("Groups", style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "lock") {
                ApprovalCard(
                    snapshot = state.settings,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    title = "Settings lock",
                    body = "New groups and edits need approval first.",
                    requestLabel = "Request to change settings",
                    oncePerDayHint = "Once a day. After approval you can edit for that many minutes.",
                    onSubmit = vm::requestSettings,
                )
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "A shared daily limit and optional time window. When the group is locked, every app in it is covered.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(groups, key = { it.id }) { row ->
                    GroupRowItem(row, onClick = { onOpenGroup(row.id) })
                }
            }
        }

        Button(
            onClick = onNewGroup,
            enabled = state.canEdit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Paper),
        ) {
            Text("New group")
        }
    }
}

@Composable
fun GroupRowItem(row: GroupRow, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupIconStack(row.icons)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDuration(row.usedMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.limitMinutes > 0) Sage else scheme.onSurfaceVariant,
                )
            }
            if (row.limitMinutes > 0) {
                Spacer(Modifier.height(6.dp))
                QuotaBar(
                    usedMs = row.usedMs,
                    limitMs = row.limitMinutes * 60_000L,
                )
            }
        }
    }
}

@Composable
private fun GroupIconStack(icons: List<Bitmap?>, size: Int = 28, slots: Int = 3) {
    val shift = 10
    val width = size + shift * (slots - 1)
    Box(Modifier.width(width.dp).height(size.dp)) {
        icons.take(slots).forEachIndexed { index, icon ->
            Box(Modifier.offset(x = (shift * index).dp)) {
                AppIcon(icon, size = size)
            }
        }
    }
}
