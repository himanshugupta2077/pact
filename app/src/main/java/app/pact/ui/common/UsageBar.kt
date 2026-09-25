package app.pact.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.pact.ui.theme.Danger
import app.pact.ui.theme.Sage

@Composable
fun UsageBar(
    usedMs: Long,
    totalMs: Long,
    fill: Color,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    val fraction = if (totalMs <= 0L) 0f else (usedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = track, size = size, cornerRadius = radius)
        if (fraction > 0f) {
            val w = (size.width * fraction).coerceAtLeast(if (usedMs > 0L) size.height else 0f)
            drawRoundRect(
                color = fill,
                size = Size(w, size.height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
fun QuotaBar(
    usedMs: Long,
    limitMs: Long,
    modifier: Modifier = Modifier,
) {
    val over = limitMs > 0L && usedMs >= limitMs
    UsageBar(
        usedMs = usedMs,
        totalMs = limitMs,
        fill = if (over) Danger else Sage,
        modifier = modifier,
    )
}
