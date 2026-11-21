package io.github.ouse.ousecode

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebView input and native cursor checks against an isolated, known page. */
class InputChecks(private val instrumentation: Instrumentation, private val activity: Activity) {
    private lateinit var web: OpenCodeWebView
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun evaluate(expression: String): String {
        val complete = CountDownLatch(1)
        var result = ""
        main { web.evaluateJavascript(expression) { result = it; complete.countDown() } }
        check(complete.await(10, TimeUnit.SECONDS)) { "Input page evaluation timed out" }
        return result
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error(message)
    }
    private fun event(action: Int, x: Float, y: Float, source: Int, tool: Int, classification: Int = 0): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
        val now = SystemClock.uptimeMillis()
        return if (Build.VERSION.SDK_INT >= 34) {
            MotionEvent.obtain(now - 100, now, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, source, activity.display!!.displayId, 0, classification)!!
        } else MotionEvent.obtain(now - 100, now, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, source, 0)
    }
    private fun pan(x: Float, y: Float, classification: Int, end: Int = MotionEvent.ACTION_UP) {
        main {
            val hover = event(MotionEvent.ACTION_HOVER_MOVE, x, y, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_FINGER)
            try { web.dispatchGenericMotionEvent(hover) } finally { hover.recycle() }
        }
        Thread.sleep(400) // Separate wheel transactions when moving between scroll areas.
        for (step in 0..7) {
            main {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 7 -> end; else -> MotionEvent.ACTION_MOVE }
                val input = event(action, x, y - step * 30, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, classification)
                try { web.dispatchTouchEvent(input) } finally { input.recycle() }
            }
            Thread.sleep(20)
        }
    }
    fun run() {
        val parent = activity.findViewById<ViewGroup>(android.R.id.content)
        main {
            web = OpenCodeWebView(activity)
            web.settings.javaScriptEnabled = true
            parent.addView(web, ViewGroup.LayoutParams(-1, -1))
            web.requestFocus()
            web.loadDataWithBaseURL("https://input-check.invalid/", """
                <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{margin:0;background:#171717}#list,#menu{position:absolute;top:30px;width:300px;height:300px;overflow:auto;cursor:default}#list{left:20px}#menu{left:400px}#handle{position:absolute;left:350px;top:0;width:20px;height:400px;cursor:col-resize}.content{height:2000px}</style>
                <div id="list"><div class="content">List</div></div><div id="handle"></div><div id="menu"><div class="content">Menu</div></div>
                <script>window.inputResults={mouse:0,touch:0,wheel:0};addEventListener('pointermove',e=>{inputResults[e.pointerType]++});addEventListener('wheel',()=>inputResults.wheel++);window.inputReady=true;</script>
            """.trimIndent(), "text/html", "utf-8", null)
        }
        try {
            await("Input test page did not load") { evaluate("window.inputReady") == "true" }
            val scale = web.width.toFloat() / evaluate("innerWidth").toFloat()
            if (Build.VERSION.SDK_INT >= 34) {
                pan(100 * scale, 250 * scale, MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)
                await("Two-finger input did not scroll the list") { evaluate("list.scrollTop").toFloat() > 30 }
                pan(500 * scale, 250 * scale, MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE, MotionEvent.ACTION_CANCEL)
                await("Two-finger input did not scroll the nested menu") { evaluate("menu.scrollTop").toFloat() > 30 }
                check(evaluate("inputResults.wheel").toInt() > 0)
                check(evaluate("inputResults.touch").toInt() == 0) { "Trackpad pan leaked touch selection gestures" }
            }
            pan(100 * scale, 250 * scale, 0)
            await("Ordinary touchscreen movement was intercepted") { evaluate("inputResults.touch").toInt() > 0 }
            fun hover(x: Float, expected: Int) {
                main {
                    val input = event(MotionEvent.ACTION_HOVER_MOVE, x * scale, 100 * scale, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_FINGER)
                    try { web.dispatchGenericMotionEvent(input) } finally { input.recycle() }
                }
                await("Cursor did not update at x=$x (expected $expected)") {
                    var matches = false
                    main { matches = web.pointerIcon == PointerIcon.getSystemIcon(activity, expected) }
                    matches
                }
            }
            repeat(3) {
                hover(355f, PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
                hover(100f, PointerIcon.TYPE_ARROW)
            }
            check(evaluate("inputResults.mouse").toInt() >= 6) { "Trackpad hover did not reach the page as mouse input" }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: two-finger list/menu scrolling, cancellation, ordinary touch, and native divider cursor restoration\n") })
        } finally {
            main { parent.removeView(web); web.destroy() }
        }
    }
}
