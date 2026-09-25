package app.pact.data

import android.content.Context
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NotificationStore(context: Context) {
    private val prefs = context.getSharedPreferences("pact_notifications", Context.MODE_PRIVATE)
    private val _todayCount = MutableStateFlow(readToday())
    val todayCount: StateFlow<Int> = _todayCount

    @Synchronized
    fun increment() {
        val key = dayKey()
        val next = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, next).apply()
        _todayCount.value = next
    }

    @Synchronized
    fun peekToday(): Int {
        val n = readToday()
        if (_todayCount.value != n) _todayCount.value = n
        return n
    }

    private fun readToday(): Int = prefs.getInt(dayKey(), 0)

    private fun dayKey(now: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        return "n-${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }
}
