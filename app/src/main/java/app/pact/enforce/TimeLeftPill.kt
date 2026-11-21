package app.pact.enforce

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.pact.engine.RuleEngine
import app.pact.util.formatClockMinutes
import app.pact.util.formatDuration
import app.pact.util.minutesOfDay

/**
 * A small "8m left · 12m of 20m" chip at the top of the screen in apps with a limit.
 * Near the evening block it reads "8m left · blocked at 22:00".
 * Shows when the app opens and when 10, 5 and 1 minutes are left, fades after 3 s,
 * and can be swiped away. Touches outside it go to the app underneath.
 */
class TimeLeftPill(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private var view: TextView? = null
    private var shownFor: String? = null
    private var front: String? = null
    private val shownAt = HashMap<String, Long>()
    private val lastLeft = HashMap<String, Long>()
    private val hideRunnable = Runnable { hide() }

    /** Called on every enforcement pass with the app in front. */
    fun update(top: String?, engine: RuleEngine) {
        val entered = top != front
        front = top
        if (top == null) return
        val (used, limit) = engine.quota(top) ?: return
        val left = minOf(limit - used, engine.msUntilWindow(top) ?: Long.MAX_VALUE)
        val byWindow = left < limit - used
        val detail = if (byWindow) {
            "blocked at ${formatClockMinutes(minutesOfDay() + ((left + 59_999L) / 60_000L).toInt())}"
        } else {
            "${formatDuration(used)} of ${formatDuration(limit)}"
        }
        val before = lastLeft.put(top, left)
        if (left <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val crossed = before != null && THRESHOLDS.any { left <= it && before > it }
        val fresh = entered && now - (shownAt[top] ?: Long.MIN_VALUE / 2) > REOPEN_GAP_MS
        if (crossed || fresh) {
            shownAt[top] = now
            show(top, left, detail)
        } else if (shownFor == top) {
            view?.text = label(left, detail)
        }
        if (shownFor != null && shownFor != top) hide()
    }

    fun hide() {
        val v = view ?: return
        v.removeCallbacks(hideRunnable)
        runCatching { wm.removeViewImmediate(v) }
        view = null
        shownFor = null
    }

    private fun show(pkg: String, left: Long, detail: String) {
        val v = view ?: build()?.also { view = it } ?: return
        shownFor = pkg
        v.text = label(left, detail)
        v.animate().cancel()
        v.alpha = if (reducedMotion()) 1f else 0f
        v.translationX = 0f
        v.animate().alpha(1f).setDuration(160).start()
        v.removeCallbacks(hideRunnable)
        v.postDelayed({ fadeOut(v) }, VISIBLE_MS)
    }

    private fun fadeOut(v: View) {
        if (reducedMotion()) return hide()
        v.animate().alpha(0f).setDuration(220).withEndAction(hideRunnable).start()
    }

    private fun label(left: Long, detail: String): CharSequence {
        // Rounded up, so the 10-minute warning reads "10m left", not "9m".
        val strong = "${formatDuration((left + 59_999L) / 60_000L * 60_000L)} left"
        val text = SpannableStringBuilder(strong)
            .append("  ·  $detail")
        text.setSpan(StyleSpan(Typeface.BOLD), 0, strong.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val tone = if (left <= 60_000L) WARN else Color.WHITE
        text.setSpan(ForegroundColorSpan(tone), 0, strong.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): TextView? {
        val v = TextView(service).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(FILL)
            }
            elevation = dp(2).toFloat()
        }
        var downX = 0f
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    view.removeCallbacks(hideRunnable)
                    view.animate().cancel()
                    view.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> view.translationX = e.rawX - downX
                // A swipe or a tap both dismiss it.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val away = if (e.rawX < downX) -view.width.toFloat() else view.width.toFloat()
                    view.animate().translationX(away).alpha(0f).setDuration(160)
                        .withEndAction(hideRunnable).start()
                }
            }
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(36)
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return if (runCatching { wm.addView(v, params) }.isSuccess) v else null
    }

    private fun reducedMotion(): Boolean =
        Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        private val THRESHOLDS = longArrayOf(10 * 60_000L, 5 * 60_000L, 60_000L)
        private const val REOPEN_GAP_MS = 5 * 60_000L
        private const val VISIBLE_MS = 3_000L
        private val FILL = Color.argb(0xCC, 0x1C, 0x1B, 0x1A)
        private val MUTED = Color.argb(0xB3, 0xFF, 0xFF, 0xFF)
        private val WARN = Color.rgb(0xF2, 0xA6, 0x5A)
    }
}
