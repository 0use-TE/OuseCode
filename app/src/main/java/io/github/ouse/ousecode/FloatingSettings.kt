package io.github.ouse.ousecode

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView

/** App controls sit above the upstream page without changing its DOM or layout. */
class FloatingSettings(context: Context, private val open: () -> Unit) : FrameLayout(context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private var right = prefs.getBoolean("edgeRight", true)
    private var fraction = prefs.getFloat("edgePosition", 0.35f)
    private var expanded = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var foreground = Color.LTGRAY
    private var surface = Color.rgb(35, 35, 35)
    private val collapse = Runnable { setExpanded(false) }
    private val grip = View(context)
    private val gear = ImageView(context).apply { setImageResource(R.drawable.ic_settings); setPadding(dp(11), dp(11), dp(11), dp(11)) }

    init {
        isClickable = true
        isFocusable = true
        addView(grip, LayoutParams(dp(4), dp(26), Gravity.CENTER))
        addView(gear, LayoutParams(-1, -1))
        setOnClickListener {
            if (expanded) { removeCallbacks(collapse); open() }
            else setExpanded(true)
        }
        setOnHoverListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) setExpanded(true)
            false
        }
        setExpanded(false)
    }

    fun attachTo(root: FrameLayout) {
        root.addView(this, LayoutParams(dp(24), dp(48)))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!dragging) place() }
        post { place() }
    }

    fun updateTheme(background: Int, text: Int) {
        surface = background; foreground = text
        drawAppearance()
    }

    private fun drawAppearance() {
        gear.imageTintList = ColorStateList.valueOf(foreground)
        grip.background = GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            setColor((foreground and 0x00ffffff) or 0x55000000)
        }
        background = if (expanded) GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(surface)
            setStroke(dp(1), (foreground and 0x00ffffff) or 0x22000000)
        } else null
        elevation = if (expanded) dp(5).toFloat() else 0f
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        gear.visibility = if (value) VISIBLE else GONE
        grip.visibility = if (value) GONE else VISIBLE
        contentDescription = if (resources.configuration.locales[0].language == "zh") {
            if (value) "OpenCode 设置" else "展开 OpenCode 设置"
        } else if (value) "OpenCode settings" else "Expand OpenCode settings"
        layoutParams?.let { it.width = dp(if (value) 44 else 24); layoutParams = it }
        drawAppearance()
        place()
        removeCallbacks(collapse)
        if (value) postDelayed(collapse, 3000)
    }

    private fun place() {
        val root = parent as? FrameLayout ?: return
        val buttonWidth = dp(if (expanded) 44 else 24)
        x = (if (right) root.width - buttonWidth else 0).coerceAtLeast(0).toFloat()
        y = dp(12) + (root.height - dp(72)).coerceAtLeast(0) * fraction
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val root = parent as? FrameLayout ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(collapse)
                downX = event.rawX; downY = event.rawY; startY = y; dragging = false
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (dx * dx + dy * dy > slop * slop) dragging = true
                if (dragging) {
                    y = (startY + dy).coerceIn(0f, (root.height - height).coerceAtLeast(0).toFloat())
                    val location = IntArray(2); root.getLocationOnScreen(location)
                    x = (event.rawX - location[0] - width / 2f).coerceIn(0f, (root.width - width).coerceAtLeast(0).toFloat())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val moved = dragging; dragging = false
                if (moved) {
                    right = x + width / 2f > root.width / 2f
                    fraction = ((y - dp(12)) / (root.height - dp(72)).coerceAtLeast(1)).coerceIn(0f, 1f)
                    prefs.edit().putBoolean("edgeRight", right).putFloat("edgePosition", fraction).apply()
                    place()
                } else if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                if (expanded) { removeCallbacks(collapse); postDelayed(collapse, 3000) }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
    override fun onDetachedFromWindow() { removeCallbacks(collapse); super.onDetachedFromWindow() }
}
