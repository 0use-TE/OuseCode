package io.github.ouse.ousecode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.min
import kotlin.random.Random

/** Offline startup visual. The bar uses runtime progress; only the rain is animated. */
class BootAnimationView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val bounds = RectF()
    private var vignette: Shader? = null
    private data class Column(val x: Float, var y: Float, val size: Float, val speed: Float, val depth: Int, val bits: CharArray)
    private val columns = ArrayList<Column>()
    private val logs = ArrayDeque<String>()
    private var stage = if (resources.configuration.locales[0].language == "zh") "准备本机工作区" else "Preparing local workspace"
    private var percent = -1
    private var decoded = 0f
    private var lastFrame = SystemClock.uptimeMillis()
    private var running = true
    private var finished = false
    private var reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    init { setBackgroundColor(Color.BLACK); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; isClickable = true }

    fun setBootProgress(value: Int, message: String) {
        percent = value.coerceIn(-1, 100); stage = message
        if (message.isNotBlank() && logs.lastOrNull() != message) { logs.addLast(message); while (logs.size > 4) logs.removeFirst() }
        contentDescription = "OpenCode · $message" + if (percent >= 0) " · $percent%" else ""
        invalidate()
    }

    fun finishBoot() { finished = true; percent = 100; invalidate() }
    fun resetBoot() { finished = false; percent = -1; decoded = 0f; logs.clear(); invalidate() }
    fun pauseAnimation() { running = false }
    fun resumeAnimation() { running = true; lastFrame = SystemClock.uptimeMillis(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        columns.clear()
        for (depth in 0..2) {
            val spacing = dp(if (depth == 2) 46f else if (depth == 1) 20f else 18f)
            val size = dp(if (depth == 2) 22f else if (depth == 1) 16f else 12f)
            val speed = dp(if (depth == 2) 190f else if (depth == 1) 105f else 50f)
            for (index in 0..(w / spacing).toInt()) columns += Column(index * spacing, Random.nextFloat() * (h + dp(400f)), size, speed * (0.7f + Random.nextFloat() * 0.6f), depth, CharArray(Random.nextInt(10, 28)) { if (Random.nextBoolean()) '0' else '1' })
        }
        vignette = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.7f, intArrayOf(Color.TRANSPARENT, 0xdd000000.toInt()), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            lastFrame = SystemClock.uptimeMillis(); invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val motion = !reducedMotion
        val dt = min(0.05f, (now - lastFrame) / 1000f); lastFrame = now
        canvas.drawColor(Color.BLACK)
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        for (column in columns) {
            if (motion) column.y += column.speed * dt
            if (column.y - column.bits.size * column.size > height) column.y = -Random.nextFloat() * dp(180f)
            if (motion && Random.nextInt(5) == 0) column.bits[Random.nextInt(column.bits.size)] = if (Random.nextBoolean()) '0' else '1'
            paint.textSize = column.size
            for (index in column.bits.indices) {
                val y = column.y - index * column.size
                if (y < -column.size || y > height + column.size) continue
                val brightness = if (index == 0) 245 else if (index < column.bits.size / 2) 110 else 38
                val alpha = ((1f - index.toFloat() / column.bits.size) * if (column.depth == 0) 65 else 210).toInt()
                paint.color = Color.argb(alpha, brightness, brightness, brightness)
                if (index == 0 && column.depth > 0) paint.setShadowLayer(dp(6f), 0f, 0f, 0x99ffffff.toInt())
                canvas.drawText(column.bits, index, 1, column.x, y, paint)
                paint.clearShadowLayer()
            }
        }
        paint.shader = vignette; paint.alpha = 255; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
        val cardWidth = min(dp(500f), width - dp(40f))
        val cardHeight = min(dp(270f), height - dp(32f))
        val left = (width - cardWidth) / 2f; val top = (height - cardHeight) / 2f
        bounds.set(left, top, left + cardWidth, top + cardHeight)
        paint.color = 0xed000000.toInt(); canvas.drawRoundRect(bounds, dp(12f), dp(12f), paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = 0x25ffffff
        canvas.drawRoundRect(bounds, dp(12f), dp(12f), paint); paint.style = Paint.Style.FILL
        val padding = dp(28f); val center = width / 2f
        fun text(value: String, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER, x: Float = center) {
            paint.textSize = dp(size); paint.color = color; paint.textAlign = align
            canvas.drawText(value, x, y, paint)
        }
        text("OPENCODE // UBUNTU.ARM64 // LOCAL.AGENT", top + dp(34f), 9f, 0xff71717a.toInt())
        if (motion && running) decoded = min(8f, decoded + dt * 2.8f)
        val target = "OpenCode"
        val advance = min(dp(32f), (cardWidth - padding * 2) / target.length)
        paint.textSize = min(dp(42f), advance * 1.4f); paint.textAlign = Paint.Align.CENTER; paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.setShadowLayer(dp(14f), 0f, 0f, 0x66ffffff)
        for (index in target.indices) {
            val character = if (finished || !motion || index < decoded.toInt()) target[index] else if ((now / 70 + index) % 2 == 0L) '0' else '1'
            canvas.drawText(character.toString(), center + (index - (target.length - 1) / 2f) * advance, top + dp(88f), paint)
        }
        paint.clearShadowLayer(); paint.typeface = Typeface.MONOSPACE
        fun fit(value: String, available: Float): String {
            val length = paint.breakText(value, true, available, null)
            return if (length < value.length) value.take((length - 1).coerceAtLeast(0)) + "…" else value
        }
        paint.textSize = dp(11f)
        text(fit(stage, cardWidth - padding * 2), top + dp(116f), 11f, 0xffa1a1aa.toInt())
        val barLeft = left + padding; val barRight = left + cardWidth - padding - dp(48f); val barY = top + dp(148f)
        paint.color = 0x29ffffff; canvas.drawRect(barLeft, barY, barRight, barY + dp(2f), paint)
        paint.color = Color.WHITE
        if (percent >= 0) canvas.drawRect(barLeft, barY, barLeft + (barRight - barLeft) * percent / 100f, barY + dp(2f), paint)
        else {
            val start = if (motion) ((now % 1600) / 1600f) * 1.3f - 0.3f else 0.35f
            canvas.drawRect(barLeft + (barRight - barLeft) * start.coerceAtLeast(0f), barY, barLeft + (barRight - barLeft) * (start + 0.3f).coerceAtMost(1f), barY + dp(2f), paint)
        }
        text(if (percent < 0) "…" else "$percent%", barY + dp(4f), 11f, Color.WHITE, Paint.Align.RIGHT, left + cardWidth - padding)
        paint.color = 0x16ffffff; canvas.drawRect(barLeft, top + dp(169f), left + cardWidth - padding, top + dp(170f), paint)
        canvas.save(); canvas.clipRect(left + padding, top + dp(178f), left + cardWidth - padding, top + cardHeight - dp(12f))
        logs.forEachIndexed { index, line ->
            paint.textSize = dp(11f)
            text(fit("> $line", cardWidth - padding * 2 - dp(42f)), top + dp(190f + index * 18f), 11f, if (index == logs.size - 1) Color.WHITE else 0xff71717a.toInt(), Paint.Align.LEFT, barLeft)
            text(if (index < logs.size - 1 || finished) "[OK]" else "…", top + dp(190f + index * 18f), 10f, 0xffa1a1aa.toInt(), Paint.Align.RIGHT, left + cardWidth - padding)
        }
        canvas.restore()
        if (running && motion && isShown && windowVisibility == VISIBLE) postInvalidateDelayed(33)
    }
}
