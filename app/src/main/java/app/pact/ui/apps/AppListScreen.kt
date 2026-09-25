package app.pact.ui.apps

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.pact.data.AppGroupWithMembers
import app.pact.data.AppRule
import app.pact.data.InstalledApp
import app.pact.ui.common.AppIcon
import app.pact.ui.common.QuotaBar
import app.pact.ui.theme.Danger
import app.pact.ui.theme.Sage
import app.pact.ui.theme.pactFieldColors
import app.pact.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AppRow(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val usedMs: Long,
    val hasRule: Boolean,
    val quotaUsedMs: Long = 0L,
    val quotaLimitMs: Long = 0L,
) {
    val hasQuota: Boolean get() = quotaLimitMs > 0L
}

data class AppsScreenState(
    val rows: List<AppRow> = emptyList(),
)

class AppListViewModel(private val app: PactApp) : ViewModel() {
    private val query = MutableStateFlow("")
    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val refresh = MutableStateFlow(0L)

    val search: StateFlow<String> = query

    val state: StateFlow<AppsScreenState> = combine(
        combine(apps, app.rules.observeAll(), app.groups.observeAll()) { installed, rules, groups ->
            Catalog(installed, rules, groups)
        },
        query.debounce { if (it.isEmpty()) 0L else 120L }.distinctUntilChanged(),
        refresh,
    ) { catalog, q, _ ->
        buildState(catalog.installed, catalog.rules, catalog.groups, q)
    }.flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(30_000),
            AppsScreenState(),
        )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadApps()
            refresh.value = 1L
            while (isActive) {
                delay(10_000)
                refresh.value = System.currentTimeMillis()
            }
        }
    }

    fun onSearch(value: String) {
        query.value = value
    }

    fun onResume() {
        viewModelScope.launch(Dispatchers.IO) {
            if (apps.value.isEmpty()) loadApps()
            refresh.value = System.currentTimeMillis()
        }
    }

    private suspend fun loadApps() {
        val extras = app.rules.getAll().map { it.packageName } +
            app.groups.getAll().flatMap { it.packageNames }
        apps.value = app.installed.userApps(extras)
    }

    private fun buildState(
        installed: List<InstalledApp>,
        rules: List<AppRule>,
        groups: List<AppGroupWithMembers>,
        q: String,
    ): AppsScreenState {
        val todayUsage = app.usage.foregroundMsToday()
        val limitedPkgs = HashSet<String>()
        val ruleByPkg = rules.associateBy { it.packageName }
        val groupUsed = HashMap<String, Long>()
        val groupLimitMs = HashMap<String, Long>()
        val pkgGroup = HashMap<String, String>()
        for (group in groups) {
            val rule = group.toRule()
            groupUsed[group.group.id] = app.engine.groupUsedMs(rule)
            if (group.group.dailyLimitMinutes > 0) {
                groupLimitMs[group.group.id] = group.group.dailyLimitMinutes * 60_000L
            }
            for (member in group.members) {
                limitedPkgs += member.packageName
                pkgGroup[member.packageName] = group.group.id
            }
        }
        for (rule in rules) limitedPkgs += rule.packageName
        val needle = q.trim().lowercase()
        val rows = installed
            .asSequence()
            .filter { needle.isEmpty() || it.label.lowercase().contains(needle) }
            .map { item ->
                val used = todayUsage[item.packageName] ?: 0L
                val appLimitMs = ruleByPkg[item.packageName]
                    ?.dailyLimitMinutes
                    ?.takeIf { it > 0 }
                    ?.times(60_000L)
                val gid = pkgGroup[item.packageName]
                val gLimit = gid?.let { groupLimitMs[it] }
                val gUsed = gid?.let { groupUsed[it] } ?: 0L
                val (quotaUsed, quotaLimit) = when {
                    appLimitMs != null && gLimit != null -> {
                        if (gUsed * appLimitMs >= used * gLimit) gUsed to gLimit else used to appLimitMs
                    }
                    appLimitMs != null -> used to appLimitMs
                    gLimit != null -> gUsed to gLimit
                    else -> 0L to 0L
                }
                AppRow(
                    packageName = item.packageName,
                    label = item.label,
                    icon = item.icon,
                    usedMs = used,
                    hasRule = item.packageName in limitedPkgs,
                    quotaUsedMs = quotaUsed,
                    quotaLimitMs = quotaLimit,
                )
            }
            .sortedWith(
                compareByDescending<AppRow> { it.hasRule }
                    .thenByDescending { it.usedMs }
                    .thenBy { it.label.lowercase() },
            )
            .toList()
        val visible = if (needle.isEmpty()) {
            rows.filter { it.hasRule || it.usedMs > 0L }
        } else {
            rows
        }
        return AppsScreenState(rows = visible)
    }

    private data class Catalog(
        val installed: List<InstalledApp>,
        val rules: List<AppRule>,
        val groups: List<AppGroupWithMembers>,
    )

    companion object {
        fun factory(app: PactApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AppListViewModel(app) as T
            }
    }
}

@Composable
fun AppListScreen(
    usageOk: Boolean,
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    vm: AppListViewModel = viewModel(
        factory = AppListViewModel.factory(LocalContext.current.applicationContext as PactApp),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.search.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = scheme.onBackground,
                )
            }
            Text("Apps", style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
        }

        if (!usageOk) {
            PermissionBanner(
                text = "Usage access is off. Times will be empty.",
                onClick = onBack,
            )
        }

        OutlinedTextField(
            value = query,
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

        LazyColumn(
            modifier = Modifier.weight(1f).navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(state.rows, key = { it.packageName }, contentType = { "app" }) { row ->
                AppRowItem(row, onClick = { onOpenApp(row.packageName) })
            }
            if (state.rows.isEmpty()) {
                item(key = "empty-day", contentType = "empty") {
                    Text(
                        if (query.isBlank()) "No apps used today" else "No matching apps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
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
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun AppRowItem(row: AppRow, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label,
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
                    color = if (row.hasRule) Sage else scheme.onSurfaceVariant,
                )
            }
            if (row.hasQuota) {
                Spacer(Modifier.height(6.dp))
                QuotaBar(usedMs = row.quotaUsedMs, limitMs = row.quotaLimitMs)
            }
        }
    }
}
