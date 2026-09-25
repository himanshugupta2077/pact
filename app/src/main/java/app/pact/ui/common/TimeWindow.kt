package app.pact.ui.common

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.pact.data.WindowMode
import app.pact.ui.theme.Paper
import app.pact.ui.theme.Rust
import app.pact.util.formatClockMinutes

const val DEFAULT_WINDOW_START = 18 * 60
const val DEFAULT_WINDOW_END = 22 * 60

@Composable
fun TimeWindowCard(
    mode: WindowMode,
    startMinutes: Int,
    endMinutes: Int,
    saved: Boolean,
    enabled: Boolean,
    onMode: (WindowMode) -> Unit,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    subject: String = "This app",
    showSaveButton: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val canSave = enabled && mode != WindowMode.NONE && startMinutes != endMinutes
    val rule = windowRuleLabel(mode, startMinutes, endMinutes)
    val hint = when (mode) {
        WindowMode.ALLOW -> "$subject can be used only between these times."
        WindowMode.BLOCK -> "$subject is locked between these times."
        WindowMode.NONE -> "Choose whether this is allowed only in a range, or locked during a range."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text("Time window", style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
        Spacer(Modifier.height(8.dp))
        if (saved && rule != null) {
            Text(rule, style = MaterialTheme.typography.bodyLarge, color = scheme.onBackground)
            Spacer(Modifier.height(6.dp))
        }
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip(
                label = "Allow only",
                selected = mode == WindowMode.ALLOW,
                enabled = enabled,
            ) { onMode(WindowMode.ALLOW) }
            PresetChip(
                label = "Block during",
                selected = mode == WindowMode.BLOCK,
                enabled = enabled,
            ) { onMode(WindowMode.BLOCK) }
        }
        if (mode != WindowMode.NONE) {
            Spacer(Modifier.height(12.dp))
            TimeRow(label = "Start", value = formatClockMinutes(startMinutes), enabled = enabled, onClick = onPickStart)
            Spacer(Modifier.height(8.dp))
            TimeRow(label = "End", value = formatClockMinutes(endMinutes), enabled = enabled, onClick = onPickEnd)
            if (startMinutes > endMinutes) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This window goes past midnight.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (showSaveButton) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Paper),
                enabled = canSave,
            ) {
                Text("Save window")
            }
        }
        if (saved || mode != WindowMode.NONE) {
            Text(
                "Remove window",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(enabled = enabled, onClick = onRemove)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
fun TimeRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onBackground)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    }
}

@Composable
fun PresetChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            selected -> scheme.onPrimary
            !enabled -> scheme.onSurfaceVariant
            else -> scheme.onBackground
        },
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Rust else scheme.surface)
            .border(1.dp, if (selected) Rust else scheme.outline, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

fun pickTime(context: Context, minutes: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        minutes / 60,
        minutes % 60,
        false,
    ).show()
}

fun windowSummary(mode: WindowMode, startMinutes: Int, endMinutes: Int): String? {
    if (mode == WindowMode.NONE || startMinutes == endMinutes) return null
    val range = "${formatClockMinutes(startMinutes)}–${formatClockMinutes(endMinutes)}"
    return when (mode) {
        WindowMode.ALLOW -> "allow $range"
        WindowMode.BLOCK -> "block $range"
        WindowMode.NONE -> null
    }
}

fun windowRuleLabel(mode: WindowMode, startMinutes: Int, endMinutes: Int): String? {
    if (mode == WindowMode.NONE || startMinutes == endMinutes) return null
    val range = "${formatClockMinutes(startMinutes)} – ${formatClockMinutes(endMinutes)}"
    return when (mode) {
        WindowMode.ALLOW -> "Allowed only $range"
        WindowMode.BLOCK -> "Blocked during $range"
        WindowMode.NONE -> null
    }
}
