package io.github.ouse.ousecode

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Shared app/overlay card; the backend owns the deadline and the decision. */
class AndroidApprovalCard(context: Context, val request: AndroidApproval, reply: (Boolean) -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun t(zh: String, en: String) = if (resources.configuration.locales[0].language == "zh") zh else en
    private val ink = Color.rgb(235, 239, 247)
    private val muted = Color.rgb(168, 180, 198)
    private val accent = Color.rgb(174, 204, 250)
    private val clock = TextView(context)
    private val timer = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val actions = mutableListOf<TextView>()
    private val tick = object : Runnable {
        override fun run() {
            val seconds = (request.remainingMillis + 999) / 1000
            clock.text = t("剩余 ", "Left ") + "%d:%02d".format(seconds / 60, seconds % 60)
            timer.progress = request.remainingMillis.toInt()
            actions.forEach { it.isEnabled = seconds > 0; it.alpha = if (seconds > 0) 1f else 0.4f }
            if (seconds > 0) postDelayed(this, 250)
        }
    }
    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        elevation = dp(12).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat(); setColor(Color.rgb(31, 37, 47)); setStroke(dp(1), Color.rgb(76, 88, 107))
        }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(context).apply { text = t("Android 操作", "Android action"); textSize = 16f; setTextColor(ink); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LayoutParams(0, dp(30), 1f))
        clock.textSize = 13f; clock.setTextColor(accent); heading.addView(clock)
        addView(heading)
        val content = LinearLayout(context).apply { orientation = VERTICAL }
        if (request.purpose.isNotBlank()) content.addView(TextView(context).apply {
            text = visibleText(request.purpose); textSize = 14f; setTextColor(muted); setPadding(0, dp(4), 0, dp(10))
        })
        content.addView(TextView(context).apply {
            text = visibleText(request.command); textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(ink); setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(18, 23, 31)) }
        })
        addView(object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(200), MeasureSpec.AT_MOST))
            }
        }.apply { addView(content) }, LayoutParams(-1, -2).apply { topMargin = dp(6) })
        timer.max = 30000; timer.progressTintList = ColorStateList.valueOf(accent); timer.progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(53, 64, 79))
        addView(timer, LayoutParams(-1, dp(3)).apply { topMargin = dp(14); bottomMargin = dp(12) })
        val row = LinearLayout(context).apply { gravity = Gravity.END }
        fun button(title: String, allow: Boolean) = TextView(context).apply {
            text = title; textSize = 14f; gravity = Gravity.CENTER; setTextColor(if (allow) Color.rgb(25, 38, 57) else ink)
            val shape = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (allow) accent else Color.rgb(49, 58, 72)) }
            background = RippleDrawable(ColorStateList.valueOf(0x226E93CD), shape, null)
            isFocusable = true; filterTouchesWhenObscured = true; setOnClickListener { reply(allow) }
            actions += this
        }
        row.addView(button(t("拒绝", "Deny"), false), LayoutParams(0, dp(42), 1f))
        row.addView(button(t("仅允许本次", "Allow once"), true), LayoutParams(0, dp(42), 1f).apply { marginStart = dp(10) })
        addView(row)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); tick.run() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    companion object {
        fun visibleText(value: String): String = value.map { char ->
            if ((char.code < 32 && char != '\n' && char != '\t') || char.code == 127 || Character.getType(char) == Character.FORMAT.toInt()) "\\u%04x".format(char.code) else char.toString()
        }.joinToString("")
    }
}
