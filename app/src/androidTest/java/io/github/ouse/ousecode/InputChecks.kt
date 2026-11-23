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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import android.webkit.WebViewClient
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
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { web.installInputCompatibility() }
            }
            parent.addView(web, ViewGroup.LayoutParams(-1, -1))
            web.requestFocus()
            web.loadDataWithBaseURL("https://input-check.invalid/", """
                <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{margin:0;background:#171717}#list,#menu{position:absolute;top:30px;width:300px;height:300px;overflow:auto;cursor:default}#list{left:20px}#menu{left:400px}#handle{position:absolute;left:350px;top:0;width:20px;height:400px;cursor:col-resize}.content{height:2000px}</style>
                <div id="list"><div class="content">List</div></div><div id="handle" data-component="resize-handle"></div><div id="menu"><div class="content">Menu</div></div>
                <div id="editor" contenteditable="true" style="position:absolute;top:450px;left:20px;width:500px;height:60px;color:white"></div>
                <script>
                  window.inputResults={mouse:0,touch:0,wheel:0,resizes:0,cancels:0};
                  addEventListener('pointermove',e=>{inputResults[e.pointerType]++});
                  addEventListener('wheel',()=>inputResults.wheel++);
                  let drag;
                  handle.addEventListener('pointerdown',e=>{
                    drag=e.pointerId;if(e.isTrusted)handle.setPointerCapture(drag);handle.setAttribute('data-dragging','');
                    document.body.style.cursor='col-resize';document.body.style.userSelect='none';document.body.style.overflow='hidden';
                  });
                  addEventListener('pointermove',e=>{if(e.pointerId===drag){inputResults.resizes++;inputResults.lastResize=e.clientX}});
                  const end=e=>{if(e.pointerId!==drag)return;if(e.type==='pointercancel')inputResults.cancels++;drag=undefined;handle.removeAttribute('data-dragging');document.body.style.cursor='';document.body.style.userSelect='';document.body.style.overflow=''};
                  addEventListener('pointerup',end);addEventListener('pointercancel',end);
                  window.inputReady=true;
                </script>
            """.trimIndent(), "text/html", "utf-8", null)
        }
        try {
            await("Input test page did not load") { evaluate("window.inputReady") == "true" }
            await("Host input compatibility did not load") { evaluate("window.__openCodeHostInput") == "true" }
            main { check(!web.settings.supportZoom() && !web.settings.builtInZoomControls && !web.settings.displayZoomControls) }
            check(evaluate("(()=>{const e=new WheelEvent('wheel',{ctrlKey:true,cancelable:true,bubbles:true});document.body.dispatchEvent(e);return e.defaultPrevented})()") == "true") { "Trackpad pinch Ctrl+wheel was not cancelled" }
            check(evaluate("(()=>{const e=new WheelEvent('wheel',{cancelable:true,bubbles:true});document.body.dispatchEvent(e);return e.defaultPrevented})()") == "false") { "Ordinary wheel scrolling was blocked" }
            val scale = web.width.toFloat() / evaluate("innerWidth").toFloat()
            val viewportScale = evaluate("visualViewport.scale").toFloat()
            val pinchStart = SystemClock.uptimeMillis()
            for (step in 0..12) {
                main {
                    val count = if (step == 0 || step == 12) 1 else 2
                    val action = when (step) {
                        0 -> MotionEvent.ACTION_DOWN
                        1 -> MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        11 -> MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        12 -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                    val spread = 40f + step.coerceAtMost(10) * 8f
                    val pointers = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                    val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply { x = (190f + if (index == 0) -spread else spread) * scale; y = 180 * scale; pressure = 1f } }
                    val pinch = MotionEvent.obtain(pinchStart, SystemClock.uptimeMillis(), action, count, pointers, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { web.dispatchTouchEvent(pinch) } finally { pinch.recycle() }
                }
                Thread.sleep(25)
            }
            Thread.sleep(150)
            check(kotlin.math.abs(evaluate("visualViewport.scale").toFloat() - viewportScale) < 0.01f) { "Native touch pinch changed the web viewport scale" }
            evaluate("inputResults.touch=0;true")
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

            for (step in 0..10) {
                main {
                    val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    val touch = event(action, (355 + step * 10) * scale, (110 + step * 4) * scale, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER)
                    try { web.dispatchTouchEvent(touch) } finally { touch.recycle() }
                }
                Thread.sleep(35)
            }
            await("Touch resize did not follow the full drag") { evaluate("inputResults.lastResize").toFloat() >= 440 }
            check(evaluate("inputResults.cancels").toInt() == 0) { "Browser cancelled touch resize to pan" }
            await("Touch release left resize styles locked") { evaluate("document.body.style.cursor") == "\"\"" }

            // Reproduce a mouse-up lost during a window transition, then a hover
            // elsewhere. The shim must clear both pointer capture and body lock.
            evaluate("handle.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:77,pointerType:'mouse',button:0,buttons:1}));window.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,pointerId:77,pointerType:'mouse',buttons:0}));true")
            check(evaluate("document.body.style.cursor") == "\"\"")
            check(evaluate("handle.hasAttribute('data-dragging')") == "false")
            evaluate("handle.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:78,pointerType:'mouse',button:0,buttons:1}));window.dispatchEvent(new Event('blur'));true")
            check(evaluate("document.body.style.cursor") == "\"\"")
            check(evaluate("document.body.style.userSelect") == "\"\"")
            check(evaluate("document.body.style.overflow") == "\"\"")
            hover(100f, PointerIcon.TYPE_ARROW)

            evaluate("editor.focus();true")
            lateinit var connection: InputConnection
            await("Web editor did not create a text input connection") {
                var ready = false
                main {
                    val info = EditorInfo()
                    val found = web.onCreateInputConnection(info)
                    ready = found != null && info.inputType and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_TEXT
                    if (ready) connection = found!!
                }
                ready
            }
            check(connection.setComposingText("ni", 1))
            await("Composition was not forwarded to the web editor") { evaluate("editor.textContent") == "\"ni\"" }
            check(connection.commitText("你", 1))
            await("Chinese commit was not forwarded") { evaluate("editor.textContent") == "\"你\"" }
            if (Build.VERSION.SDK_INT >= 33) {
                check(connection.setComposingText("hao", 1, null))
                check(connection.commitText("好", 1, null))
                await("Modern IME composition/commit was not forwarded") { evaluate("editor.textContent") == "\"你好\"" }
            }
            check(connection.finishComposingText())
            main { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(web.windowToken, 0) }

            // The touch menu disappears on pointerup, before Android synthesizes
            // its click. That click must not dismiss the replacement dialog.
            evaluate("""
                (()=>{
                  const menu=document.createElement('button');menu.id='deleteFixture';document.body.appendChild(menu);
                  const overlay=document.createElement('div');overlay.dataset.component='dialog-overlay';
                  overlay.addEventListener('click',()=>overlay.remove());
                  menu.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerType:'touch',buttons:1}));
                  menu.remove();document.body.appendChild(overlay);
                  overlay.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,detail:1}));
                  window.fixtureOverlay=overlay;
                  return true;
                })()
            """.trimIndent())
            check(evaluate("fixtureOverlay.isConnected") == "true") { "Touch compatibility click dismissed the confirmation" }
            evaluate("fixtureOverlay.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerType:'touch',buttons:1}));fixtureOverlay.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,detail:1}));true")
            check(evaluate("fixtureOverlay.isConnected") == "false") { "Real backdrop click no longer dismisses dialogs" }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: two-finger scrolling, cursor restoration, continuous touch resize, lost-release/blur cleanup and classic/modern Chinese input connections\n") })
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: fixed web zoom, pinch wheel cancellation, ordinary wheel preservation and touch menu-to-dialog click-through protection\n") })
        } finally {
            main { parent.removeView(web); web.destroy() }
        }
    }
}
