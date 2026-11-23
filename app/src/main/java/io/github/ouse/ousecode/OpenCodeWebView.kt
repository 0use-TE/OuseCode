package io.github.ouse.ousecode

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.webkit.WebView

/** Some tablet touchpads label mouse hover as finger input and send two-finger
 * pans through the touchscreen channel. Normalize those explicit classifications
 * before Chromium handles them; normal touch, mouse and stylus input pass through. */
class OpenCodeWebView(context: Context) : WebView(context) {
    init {
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
    }
    private val configuration = ViewConfiguration.get(context)
    // A classified DOWN starts a pan; UP/CANCEL, focus loss, detaching, or a new
    // unrelated DOWN clears it so an interrupted pan cannot capture the next tap.
    private var scrolling = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private val inputCompatibility by lazy { context.assets.open("webview-input.js").bufferedReader().use { it.readText() } }

    fun installInputCompatibility() = evaluateJavascript(inputCompatibility, null)

    // A physical keyboard can keep composing while Gboard's candidate window is
    // hidden after changing apps/windows. Ask it to show once per composition;
    // the IME still decides how to present its hardware-keyboard candidate UI.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        if (outAttrs.inputType and android.text.InputType.TYPE_MASK_CLASS != android.text.InputType.TYPE_CLASS_TEXT) return connection
        return object : InputConnectionWrapper(connection, false) {
            private var composing = false
            private fun composition(text: CharSequence?) {
                val started = !text.isNullOrEmpty()
                if (started && !composing) post {
                    if (hasWindowFocus() && hasFocus() && resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS) {
                        context.getSystemService(InputMethodManager::class.java).showSoftInput(this@OpenCodeWebView, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                composing = started
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val result = super.setComposingText(text, newCursorPosition)
                if (result) composition(text)
                return result
            }
            @android.annotation.TargetApi(33)
            override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
                val result = super.setComposingText(text, newCursorPosition, textAttribute)
                if (result) composition(text)
                return result
            }
            override fun finishComposingText(): Boolean { composing = false; return super.finishComposingText() }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = false
                return super.commitText(text, newCursorPosition)
            }
            @android.annotation.TargetApi(33)
            override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
                composing = false
                return super.commitText(text, newCursorPosition, textAttribute)
            }
        }
    }

    private fun mouseLike(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        return (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) &&
            (tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_UNKNOWN)
    }

    private fun asMouse(event: MotionEvent): MotionEvent {
        val properties = Array(event.pointerCount) { MotionEvent.PointerProperties().also { p -> event.getPointerProperties(it, p); p.toolType = MotionEvent.TOOL_TYPE_MOUSE } }
        val coordinates = Array(event.pointerCount) { MotionEvent.PointerCoords().also { p -> event.getPointerCoords(it, p) } }
        return MotionEvent.obtain(event.downTime, event.eventTime, event.action, event.pointerCount, properties, coordinates, event.metaState, event.buttonState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, InputDevice.SOURCE_MOUSE, event.flags)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val twoFinger = Build.VERSION.SDK_INT >= 34 && event.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !twoFinger) scrolling = false
        if (!twoFinger && !scrolling) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrolling = true
                anchorX = event.x; anchorY = event.y
                previousX = event.x; previousY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrolling) return super.onTouchEvent(event)
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                val coordinates = MotionEvent.PointerCoords().apply {
                    x = anchorX; y = anchorY
                    setAxisValue(MotionEvent.AXIS_HSCROLL, (event.x - previousX) / configuration.scaledHorizontalScrollFactor)
                    setAxisValue(MotionEvent.AXIS_VSCROLL, (event.y - previousY) / configuration.scaledVerticalScrollFactor)
                }
                previousX = event.x; previousY = event.y
                val wheel = MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coordinates), event.metaState, 0, 1f, 1f, event.deviceId, 0, InputDevice.SOURCE_MOUSE, 0)
                try { super.onGenericMotionEvent(wheel) } finally { wheel.recycle() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scrolling = false
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val hover = event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
        if (!hover || !mouseLike(event)) return super.dispatchGenericMotionEvent(event)
        val mouse = asMouse(event)
        return try { super.dispatchGenericMotionEvent(mouse) } finally { mouse.recycle() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) scrolling = false
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onDetachedFromWindow() {
        scrolling = false
        super.onDetachedFromWindow()
    }
}
