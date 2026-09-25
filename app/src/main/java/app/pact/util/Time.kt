package app.pact.util

import java.util.Calendar
import java.util.concurrent.TimeUnit

private val MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sept", "Oct", "Nov", "Dec",
)

private val MONTHS_FULL = arrayOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

val WEEKDAY_SHORT = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun startOfDayMs(now: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

fun startOfWeekMs(now: Long = System.currentTimeMillis()): Long {
    val start = startOfDayMs(now)
    val cal = Calendar.getInstance()
    cal.timeInMillis = start
    val daysFromMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    cal.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
    return cal.timeInMillis
}

fun shiftDays(dayStartMs: Long, days: Int): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = dayStartMs
    cal.add(Calendar.DAY_OF_MONTH, days)
    return startOfDayMs(cal.timeInMillis)
}

fun shiftWeek(weekStartMs: Long, deltaWeeks: Int): Long =
    shiftDays(weekStartMs, deltaWeeks * 7)

fun isoWeekNumber(ms: Long): Int {
    val cal = Calendar.getInstance()
    cal.firstDayOfWeek = Calendar.MONDAY
    cal.minimalDaysInFirstWeek = 4
    cal.timeInMillis = ms
    return cal.get(Calendar.WEEK_OF_YEAR)
}

fun formatDayMonth(ms: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    return "${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]}"
}

fun formatWeekRange(weekStartMs: Long): String {
    val end = shiftDays(weekStartMs, 6)
    return "${formatDayMonth(weekStartMs)} to ${formatDayMonth(end)}"
}

fun formatDayMonthFull(ms: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    return "${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS_FULL[cal.get(Calendar.MONTH)]}"
}

fun formatWeekRangeFull(weekStartMs: Long): String {
    val end = shiftDays(weekStartMs, 6)
    return "${formatDayMonthFull(weekStartMs)} to ${formatDayMonthFull(end)}"
}

fun formatWeekdayDayMonth(ms: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    val weekday = WEEKDAY_SHORT[weekdayIndexMonday(ms)]
    return "$weekday, ${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]}"
}

fun chartAxisMaxMs(peakMs: Long): Long {
    val hour = TimeUnit.HOURS.toMillis(1)
    if (peakMs <= 0L) return 2L * hour
    val hours = peakMs.toDouble() / hour
    val nice = longArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24)
    val pick = nice.firstOrNull { it >= hours * 1.08 } ?: 24L
    return pick * hour
}

fun chartAxisLabel(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    if (hours > 0L) return "${hours}h"
    val mins = TimeUnit.MILLISECONDS.toMinutes(ms)
    return if (mins > 0L) "${mins}m" else "0h"
}

fun chartAxisMaxCount(peak: Long): Long {
    if (peak <= 0L) return 10L
    val nice = longArrayOf(5, 10, 15, 20, 30, 40, 50, 75, 100, 150, 200, 300, 500, 750, 1000, 1500, 2000)
    return nice.firstOrNull { it >= peak * 1.08 } ?: ((peak + 99) / 100 * 100)
}

fun weekdayIndexMonday(ms: Long): Int {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
}

fun minutesOfDay(now: Long = System.currentTimeMillis()): Int {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

fun formatClockMinutes(totalMinutes: Int): String {
    val minutes = ((totalMinutes % (24 * 60)) + (24 * 60)) % (24 * 60)
    val hour24 = minutes / 60
    val min = minutes % 60
    val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
    val amPm = if (hour24 < 12) "AM" else "PM"
    return "$hour12:${min.toString().padStart(2, '0')} $amPm"
}

fun inTimeWindow(nowMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean {
    val now = ((nowMinutes % (24 * 60)) + (24 * 60)) % (24 * 60)
    val start = ((startMinutes % (24 * 60)) + (24 * 60)) % (24 * 60)
    val end = ((endMinutes % (24 * 60)) + (24 * 60)) % (24 * 60)
    if (start == end) return false
    return if (start < end) now in start until end else now >= start || now < end
}

fun formatDuration(ms: Long, compact: Boolean = true): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        compact && h > 0 -> "${h}h ${m}m"
        compact && m > 0 -> "${m}m"
        compact -> "${s}s"
        h > 0 -> "${h}h ${m}m ${s}s"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

fun formatHoursMins(ms: Long): String {
    val totalMin = TimeUnit.MILLISECONDS.toMinutes(ms.coerceAtLeast(0))
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${hrs(h)}, ${mins(m)}"
        h > 0 -> hrs(h)
        else -> mins(m)
    }
}

private fun hrs(n: Long) = if (n == 1L) "1 hr" else "$n hrs"

private fun mins(n: Long) = if (n == 1L) "1 min" else "$n mins"
