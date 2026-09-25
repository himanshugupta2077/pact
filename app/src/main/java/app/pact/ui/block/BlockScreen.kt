package app.pact.ui.block

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pact.PactApp
import app.pact.override.MAX_OVERRIDE_MINUTES
import app.pact.override.OVERRIDE_MINUTE_CHIPS
import app.pact.override.OverrideException
import app.pact.override.OverridePhase
import app.pact.ui.theme.Ink
import app.pact.ui.theme.InkElevated
import app.pact.ui.theme.Paper
import app.pact.ui.theme.PaperMuted
import app.pact.ui.theme.Rust
import app.pact.ui.theme.pactFieldColors
import kotlinx.coroutines.launch

@Composable
fun BlockScreen(
    packageName: String,
    onGoHome: () -> Unit,
    startOnRequest: Boolean = false,
) {
    BackHandler { onGoHome() }
    val context = LocalContext.current
    val app = context.applicationContext as PactApp
    val copySeed = remember(packageName) { System.nanoTime() }
    val snap = remember(packageName, copySeed) { lockSnapshot(app, packageName, copySeed) }
    val records by app.overrides.records.collectAsStateWithLifecycle()
    val override = remember(packageName, records) { app.overrides.snapshot(packageName) }
    val scope = rememberCoroutineScope()
    var formOpen by remember(packageName) { mutableStateOf(false) }
    var minutes by remember(packageName) { mutableIntStateOf(5) }
    var comment by remember(packageName) { mutableStateOf("") }
    var sending by remember(packageName) { mutableStateOf(false) }
    var error by remember(packageName) { mutableStateOf<String?>(null) }

    LaunchedEffect(packageName, startOnRequest, override.phase) {
        if (startOnRequest && override.phase == OverridePhase.AVAILABLE) {
            formOpen = true
        }
        if (override.phase != OverridePhase.AVAILABLE) {
            formOpen = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink),
        contentAlignment = Alignment.TopCenter,
    ) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            snap.label,
            style = MaterialTheme.typography.labelSmall,
            color = PaperMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            snap.headline,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 36.sp,
                letterSpacing = 1.2.sp,
            ),
            color = Paper,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            LockCard {
                if (snap.availableAgain != null) {
                    MetricLine("Available again at", snap.availableAgain)
                    Spacer(Modifier.height(12.dp))
                }
                MetricLine("Used today", snap.usedToday)
                val groupName = snap.groupName
                val groupUsed = snap.groupUsed
                if (!groupName.isNullOrBlank() && !groupUsed.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    MetricLine(groupName, groupUsed)
                }
                Spacer(Modifier.height(12.dp))
                MetricLine("Used this week", snap.usedWeek)
            }
            Spacer(Modifier.height(14.dp))
            LockCard {
                Text(
                    snap.body,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    color = PaperMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val streak = snap.streakLine
            if (streak != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    streak,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (formOpen && override.phase == OverridePhase.AVAILABLE) {
                Spacer(Modifier.height(14.dp))
                OverrideFormCard(
                    minutes = minutes,
                    comment = comment,
                    sending = sending,
                    error = error,
                    onMinutes = { minutes = it.coerceIn(1, MAX_OVERRIDE_MINUTES) },
                    onComment = { comment = it.take(400) },
                    onSend = {
                        val trimmed = comment.trim()
                        if (trimmed.isEmpty()) {
                            error = "Write a short comment first."
                        } else {
                            sending = true
                            error = null
                            scope.launch {
                                try {
                                    app.overrides.submit(packageName, snap.label, minutes, trimmed)
                                    formOpen = false
                                } catch (e: OverrideException) {
                                    error = e.message
                                } catch (_: Exception) {
                                    error = "Could not send the request."
                                } finally {
                                    sending = false
                                }
                            }
                        }
                    },
                    onCancel = { formOpen = false },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onGoHome,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Paper),
        ) {
            Text("Gotcha!")
        }
        val requestLabel = when (override.phase) {
            OverridePhase.WAITING -> "Waiting for approval"
            OverridePhase.DENIED -> "Request declined · try tomorrow"
            OverridePhase.USED -> "Already requested today"
            OverridePhase.ACTIVE -> "Approved. Open the app again."
            OverridePhase.AVAILABLE -> if (formOpen) null else "Request override"
        }
        if (requestLabel != null) {
            Text(
                requestLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = PaperMuted,
                modifier = Modifier
                    .clickable(
                        enabled = override.phase == OverridePhase.AVAILABLE && !formOpen,
                    ) { formOpen = true }
                    .padding(top = 12.dp, bottom = 4.dp, start = 12.dp, end = 12.dp),
            )
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverrideFormCard(
    minutes: Int,
    comment: String,
    sending: Boolean,
    error: String?,
    onMinutes: (Int) -> Unit,
    onComment: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    LockCard {
        Text(
            "How long?",
            style = MaterialTheme.typography.titleLarge,
            color = Paper,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Max $MAX_OVERRIDE_MINUTES minutes. Once a day per app.",
            style = MaterialTheme.typography.bodyMedium,
            color = PaperMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OVERRIDE_MINUTE_CHIPS.forEach { chip ->
                val selected = minutes == chip
                Text(
                    "${chip}m",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Paper else PaperMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) Rust else Ink)
                        .border(1.dp, if (selected) Rust else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                        .clickable { onMinutes(chip) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = if (minutes > 0) minutes.toString() else "",
            onValueChange = { value ->
                val n = value.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0
                onMinutes(n)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Minutes") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = pactFieldColors(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = comment,
            onValueChange = onComment,
            modifier = Modifier.fillMaxWidth().height(120.dp),
            label = { Text("Comment") },
            placeholder = { Text("Why do you need this?") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(14.dp),
            colors = pactFieldColors(),
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onSend,
            enabled = !sending && minutes in 1..MAX_OVERRIDE_MINUTES && comment.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Paper),
        ) {
            Text(if (sending) "Sending…" else "Send request")
        }
        Text(
            "Cancel",
            style = MaterialTheme.typography.bodyMedium,
            color = PaperMuted,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(enabled = !sending, onClick = onCancel)
                .padding(12.dp),
        )
    }
}

@Composable
private fun LockCard(content: @Composable ColumnScope.() -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(InkElevated)
            .border(1.dp, line, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = PaperMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = Paper,
        )
    }
}
