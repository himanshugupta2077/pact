package app.pact.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.pact.override.MAX_OVERRIDE_MINUTES
import app.pact.override.OVERRIDE_MINUTE_CHIPS
import app.pact.override.OverrideException
import app.pact.override.OverridePhase
import app.pact.override.OverrideSnapshot
import app.pact.ui.theme.Paper
import app.pact.ui.theme.Rust
import app.pact.ui.theme.Sage
import app.pact.ui.theme.pactFieldColors
import app.pact.util.formatDuration
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalCard(
    snapshot: OverrideSnapshot,
    title: String,
    body: String,
    requestLabel: String,
    oncePerDayHint: String,
    modifier: Modifier = Modifier,
    onSubmit: suspend (minutes: Int, comment: String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var formOpen by remember { mutableStateOf(false) }
    var minutes by remember { mutableIntStateOf(5) }
    var comment by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val remaining = snapshot.remainingMs()

    if (snapshot.phase != OverridePhase.AVAILABLE && formOpen) {
        formOpen = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
        Spacer(Modifier.height(6.dp))
        val status = when (snapshot.phase) {
            OverridePhase.ACTIVE ->
                "Approved for ${formatDuration(remaining, compact = false)}."
            OverridePhase.WAITING -> "Waiting for approval."
            OverridePhase.DENIED -> "Request declined · try tomorrow."
            OverridePhase.USED -> "Already requested today."
            OverridePhase.AVAILABLE -> body
        }
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (snapshot.phase == OverridePhase.ACTIVE) Sage else scheme.onSurfaceVariant,
        )

        if (snapshot.phase == OverridePhase.AVAILABLE && !formOpen) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { formOpen = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Paper),
            ) {
                Text(requestLabel)
            }
        }

        if (formOpen && snapshot.phase == OverridePhase.AVAILABLE) {
            Spacer(Modifier.height(12.dp))
            Text(
                oncePerDayHint,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OVERRIDE_MINUTE_CHIPS.forEach { chip ->
                    PresetChip(
                        label = "${chip}m",
                        selected = minutes == chip,
                    ) { minutes = chip }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it.take(400) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Why?") },
                placeholder = { Text("Short comment") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                shape = RoundedCornerShape(14.dp),
                colors = pactFieldColors(),
            )
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = scheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val trimmed = comment.trim()
                    if (trimmed.isEmpty()) {
                        error = "Write a short comment first."
                        return@Button
                    }
                    sending = true
                    error = null
                    scope.launch {
                        try {
                            onSubmit(minutes.coerceIn(1, MAX_OVERRIDE_MINUTES), trimmed)
                            formOpen = false
                            comment = ""
                        } catch (e: OverrideException) {
                            error = e.message
                        } catch (_: Exception) {
                            error = "Could not send the request."
                        } finally {
                            sending = false
                        }
                    }
                },
                enabled = !sending && minutes in 1..MAX_OVERRIDE_MINUTES,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Paper),
            ) {
                Text(if (sending) "Sending…" else "Send request")
            }
            Text(
                "Cancel",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(enabled = !sending) { formOpen = false }
                    .padding(12.dp),
            )
        }
    }
}
