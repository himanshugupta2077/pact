package app.pact.enforce

import android.accessibilityservice.AccessibilityService
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.pact.PactApp
import app.pact.ui.block.lockSnapshot
import kotlin.math.min

class BlockOverlay(
    private val service: AccessibilityService,
    private val onHome: () -> Unit,
) {

    private val density = service.resources.displayMetrics.density
    private val wm = service.getSystemService(WindowManager::class.java)

    private var root: View? = null
    private var contentColumn: LinearLayout? = null
    private var nameView: TextView? = null
    private var headlineView: TextView? = null
    private var bodyView: TextView? = null
    private var availableRow: View? = null
    private var availableValue: TextView? = null
    private var usedTodayValue: TextView? = null
    private var groupRow: View? = null
    private var groupLabel: TextView? = null
    private var groupValue: TextView? = null
    private var usedWeekValue: TextView? = null
    private var streakView: TextView? = null
    private var copySeed = 0L

    var shownPackage: String? = null
        private set

    fun show(packageName: String) {
        if (root != null) {
            bind(packageName)
            return
        }
        val view = build()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            overlayFlags(),
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.FILL
        params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val added = runCatching { wm.addView(view, params) }.isSuccess
        if (!added) return
        root = view
        bind(packageName)
    }

    fun hide() {
        val view = root ?: return
        runCatching { wm.removeView(view) }
        root = null
        contentColumn = null
        nameView = null
        headlineView = null
        bodyView = null
        availableRow = null
        availableValue = null
        usedTodayValue = null
        groupRow = null
        groupLabel = null
        groupValue = null
        usedWeekValue = null
        streakView = null
        shownPackage = null
        copySeed = 0L
    }

    fun onConfigurationChanged() {
        applyChrome()
    }

    private fun bind(packageName: String) {
        if (shownPackage != packageName) {
            copySeed = System.nanoTime()
        }
        shownPackage = packageName
        val app = service.application as? PactApp ?: return
        val snap = lockSnapshot(app, packageName, copySeed)
        nameView?.text = snap.label
        headlineView?.text = snap.headline
        bodyView?.text = snap.body
        usedTodayValue?.text = snap.usedToday
        val groupName = snap.groupName
        val groupUsed = snap.groupUsed
        if (groupName.isNullOrBlank() || groupUsed.isNullOrBlank()) {
            groupRow?.visibility = View.GONE
        } else {
            groupRow?.visibility = View.VISIBLE
            groupLabel?.text = groupName
            groupValue?.text = groupUsed
        }
        usedWeekValue?.text = snap.usedWeek
        val available = snap.availableAgain
        if (available.isNullOrBlank()) {
            availableRow?.visibility = View.GONE
        } else {
            availableRow?.visibility = View.VISIBLE
            availableValue?.text = available
        }
        val streak = snap.streakLine
        if (streak == null) {
            streakView?.visibility = View.GONE
        } else {
            streakView?.visibility = View.VISIBLE
            streakView?.text = streak
        }
    }

    private fun build(): View {
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = false
            descendantFocusability = LinearLayout.FOCUS_AFTER_DESCENDANTS
        }
        contentColumn = column

        nameView = label(sizeSp = 13f, color = MUTED, letter = 0.12f)
        column.addView(nameView, matchWrap(bottom = dp(14)))
        headlineView = label(sizeSp = 36f, color = PAPER, serif = true, letter = 0.04f).apply {
            maxLines = 3
        }
        column.addView(headlineView, matchWrap(bottom = dp(22)))

        val cards = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
        }
        cards.addView(statsCard(), matchWrap())
        cards.addView(reasonCard(), matchWrap(top = dp(14)))
        streakView = label(sizeSp = 13f, color = MUTED, lineSpacing = 1.2f)
        cards.addView(streakView, matchWrap(top = dp(16)))

        val scroller = ScrollView(service).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(cards, matchWrap())
        }
        column.addView(
            scroller,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dp(20)
            },
        )

        column.addView(
            pill("Gotcha!", RUST, PAPER) { onHome() },
            matchWrap(),
        )

        val frame = FrameLayout(service).apply {
            setBackgroundColor(INK)
            isClickable = true
            isFocusable = false
        }
        frame.addView(column, contentLayoutParams())
        applyChrome()
        return frame
    }

    private fun isCompact(): Boolean {
        val cfg = service.resources.configuration
        val shortSide = min(
            service.resources.displayMetrics.widthPixels,
            service.resources.displayMetrics.heightPixels,
        )
        return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            service.resources.displayMetrics.heightPixels < shortSide * 1.2f
    }

    private fun contentLayoutParams(): FrameLayout.LayoutParams {
        val screenW = service.resources.displayMetrics.widthPixels
        val compact = isCompact()
        val width = if (compact) min(dp(420), screenW) else FrameLayout.LayoutParams.MATCH_PARENT
        return FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun applyChrome() {
        val column = contentColumn ?: return
        val compact = isCompact()
        column.setPadding(dp(24), if (compact) dp(20) else dp(52), dp(24), dp(20))
        headlineView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 28f else 36f)
        val next = contentLayoutParams()
        val current = column.layoutParams as? FrameLayout.LayoutParams
        if (current == null || current.width != next.width) {
            column.layoutParams = next
        }
    }

    private fun overlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun statsCard(): LinearLayout {
        val card = card()
        val available = metricRow("Available again at")
        availableRow = available.root
        availableValue = available.value
        card.addView(available.root, matchWrap(bottom = dp(12)))
        val today = metricRow("Used today")
        usedTodayValue = today.value
        card.addView(today.root, matchWrap())
        val group = metricRow("Group")
        groupRow = group.root
        groupLabel = group.label
        groupValue = group.value
        group.root.visibility = View.GONE
        card.addView(group.root, matchWrap(top = dp(12)))
        val week = metricRow("Used this week")
        usedWeekValue = week.value
        card.addView(week.root, matchWrap(top = dp(12)))
        return card
    }

    private fun reasonCard(): LinearLayout {
        val card = card()
        bodyView = label(sizeSp = 15f, color = MUTED, lineSpacing = 1.25f)
        card.addView(bodyView, matchWrap())
        return card
    }

    private fun card(): LinearLayout {
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(CARD)
                setStroke(dp(1), LINE)
                cornerRadius = 16 * density
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
    }

    private fun metricRow(label: String): MetricRow {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = label(label, 14f, MUTED, align = Gravity.START)
        row.addView(
            name,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val value = label("", 14f, PAPER, align = Gravity.END)
        row.addView(
            value,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return MetricRow(row, name, value)
    }

    private fun label(
        text: String = "",
        sizeSp: Float,
        color: Int,
        serif: Boolean = false,
        letter: Float = 0f,
        align: Int = Gravity.CENTER,
        lineSpacing: Float = 1f,
    ): TextView {
        return TextView(service).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            gravity = align
            letterSpacing = letter
            if (serif) typeface = Typeface.SERIF
            if (lineSpacing != 1f) setLineSpacing(0f, lineSpacing)
        }
    }

    private fun pill(
        text: String,
        fill: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(service).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isAllCaps = false
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                cornerRadius = 28 * density
            }
            setOnClickListener { onClick() }
        }
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = top
            bottomMargin = bottom
        }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private class MetricRow(val root: LinearLayout, val label: TextView, val value: TextView)

    companion object {
        private val INK = 0xFF0E0D0B.toInt()
        private val CARD = 0xFF1C1A16.toInt()
        private val LINE = 0xFF3A352E.toInt()
        private val PAPER = 0xFFF4EFE6.toInt()
        private val MUTED = 0xFFC9C1B4.toInt()
        private val RUST = 0xFFC45C26.toInt()
    }
}
