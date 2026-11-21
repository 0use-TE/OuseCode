package io.github.ouse.ousecode

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** One temporary PTY, kept alive when hidden and disposed when explicitly closed. */
class TemporaryTerminal(
    private val activity: Activity,
    private val manager: RuntimeManager,
    private val onClosed: (TemporaryTerminal) -> Unit,
    val androidShell: IAndroidShell? = null,
) : LinearLayout(activity), TerminalSessionClient, TerminalViewClient {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun t(zh: String, en: String) = manager.text(zh, en)
    private var closed = false
    val terminal = TerminalView(activity, null)
    val session: TerminalSession

    init {
        orientation = VERTICAL; elevation = dp(14).toFloat()
        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(65, 65, 65)) }
        setPadding(dp(1), dp(1), dp(1), dp(1)); clipToOutline = true
        val header = LinearLayout(activity).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        fun button(drawable: Int, description: String, run: () -> Unit) = ImageButton(activity).apply {
            setImageResource(drawable); imageTintList = ColorStateList.valueOf(Color.rgb(225, 225, 225))
            contentDescription = description; setPadding(dp(12), dp(12), dp(12), dp(12))
            val attributes = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = attributes.getDrawable(0); attributes.recycle()
            layoutParams = LayoutParams(dp(44), dp(44)); setOnClickListener { run() }
        }
        header.addView(button(R.drawable.ic_minimize, t("收起终端", "Hide terminal")) { hide() })
        header.addView(button(R.drawable.ic_close, t("关闭终端", "Close terminal")) { close() })
        addView(header, LayoutParams(-1, dp(44)))
        terminal.apply {
            setTerminalViewClient(this@TemporaryTerminal)
            setIsTerminalViewKeyLoggingEnabled(false)
            setTextSize((14 * resources.displayMetrics.scaledDensity).toInt())
            setTypeface(Typeface.MONOSPACE)
            isFocusableInTouchMode = true
            contentDescription = if (androidShell == null) t("交互式 Ubuntu 终端", "Interactive Ubuntu terminal") else t("交互式 Android 终端", "Interactive Android terminal")
        }
        addView(FrameLayout(activity).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(terminal, FrameLayout.LayoutParams(-1, -1))
        }, LayoutParams(-1, 0, 1f))
        session = if (androidShell == null) manager.createTerminal(this) else AndroidAccess.get(activity).createTerminal(androidShell, this)
        terminal.attachSession(session)
    }

    fun show() {
        visibility = VISIBLE; bringToFront(); terminal.requestFocus()
        if (resources.configuration.keyboard == Configuration.KEYBOARD_NOKEYS) post { showKeyboard() }
    }

    fun hide() {
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(terminal.windowToken, 0)
        visibility = GONE
        onClosed(this) // The host restores web focus but retains this hidden session.
    }

    fun close() {
        if (closed) return
        closed = true
        if (androidShell == null) manager.closeTerminal(session) else session.finishIfRunning()
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(terminal.windowToken, 0)
        (parent as? FrameLayout)?.removeView(this)
        onClosed(this)
    }

    val isClosed get() = closed
    private fun showKeyboard() { activity.getSystemService(InputMethodManager::class.java).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT) }

    override fun onTextChanged(changedSession: TerminalSession) { terminal.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (androidShell == null) manager.forgetTerminal(finishedSession) else AndroidAccess.get(activity).forgetTerminal(finishedSession)
        close()
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) { activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text)) }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(activity)?.let { session.emulator?.paste(it.toString()) }
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { terminal.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) { terminal.invalidate() }
    override fun getTerminalCursorStyle(): Int? = null
    override fun onScale(scale: Float): Float {
        val next = scale.coerceIn(0.8f, 1.8f)
        terminal.setTextSize((14 * resources.displayMetrics.scaledDensity * next).toInt())
        return next
    }
    override fun onSingleTapUp(event: MotionEvent) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = visibility == VISIBLE
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession): Boolean {
        if (event.isCtrlPressed && event.isShiftPressed && keyCode == KeyEvent.KEYCODE_V) { onPasteTextFromClipboard(session); return true }
        return false
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() { terminal.invalidate() }
    override fun logError(tag: String, message: String) { manager.log("$tag: $message") }
    override fun logWarn(tag: String, message: String) { manager.log("$tag: $message") }
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, error: Exception) { manager.log("$tag: $message (${error.javaClass.simpleName})") }
    override fun logStackTrace(tag: String, error: Exception) { manager.log("$tag: ${error.javaClass.simpleName}") }
}
