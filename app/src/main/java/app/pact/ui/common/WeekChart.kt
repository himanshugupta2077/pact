package app.pact.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pact.util.WEEKDAY_SHORT

@Composable
fun WeekBarChart(
    values: List<Long>,
    selectedIndex: Int,
    maxSelectableIndex: Int,
    onSelectIndex: (Int) -> Unit,
    axisMax: Long,
    axisTop: String,
    axisMid: String,
    axisBottom: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val selectedColor = scheme.onBackground
    val restFill = scheme.onBackground.copy(alpha = 0.28f)
    val grid = scheme.outline.copy(alpha = 0.45f)
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(148.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(Modifier.fillMaxSize()) {
                    val count = 7
                    val gap = 10.dp.toPx()
                    val w = (size.width - gap * (count - 1)) / count
                    val stub = 3.dp.toPx()
                    val maxVal = axisMax.toFloat().coerceAtLeast(1f)
                    listOf(0f, 0.5f, 1f).forEach { t ->
                        val y = size.height * t
                        drawLine(
                            color = grid,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    values.forEachIndexed { i, value ->
                        val h = ((value / maxVal) * size.height).coerceAtLeast(if (value > 0L) stub else 0f)
                        if (h <= 0f) return@forEachIndexed
                        val x = i * (w + gap)
                        drawRoundRect(
                            color = if (i == selectedIndex) selectedColor else restFill,
                            topLeft = Offset(x, size.height - h),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                        )
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    repeat(7) { i ->
                        val enabled = i <= maxSelectableIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(enabled = enabled) { onSelectIndex(i) },
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .fillMaxHeight()
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(axisTop, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text(axisMid, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text(axisBottom, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(end = 36.dp)) {
            WEEKDAY_SHORT.forEachIndexed { i, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selectedIndex) selectedColor else scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun WeekPager(
    label: String,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = "Previous week",
            tint = if (canPrev) scheme.onBackground else scheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = canPrev, onClick = onPrev)
                .padding(6.dp)
                .size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "Next week",
            tint = if (canNext) scheme.onBackground else scheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = canNext, onClick = onNext)
                .padding(6.dp)
                .size(22.dp),
        )
    }
}
