package io.github.ouse.ousecode

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Exercises the actual packaged ARM64 PRoot/Ubuntu/OpenCode, without model credentials. */
class SmokeInstrumentation : Instrumentation() {
    private var extended = false
    private var ui = false
    private var terminal = false
    private var androidChecksEnabled = false
    private var inspect = false
    private var host = false
    private var approvals = false
    private var testedActivity: Activity? = null
    override fun onCreate(arguments: Bundle?) { extended = arguments?.getString("extended") == "true"; ui = arguments?.getString("ui") == "true"; terminal = arguments?.getString("terminal") == "true"; androidChecksEnabled = arguments?.getString("android") == "true"; inspect = arguments?.getString("inspect") == "true"; host = arguments?.getString("host") == "true"; approvals = arguments?.getString("approvals") == "true"; super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            if (inspect) { inspectInput(); finish(Activity.RESULT_OK, result); return }
            if (host) { HostChecks(this).run(); finish(Activity.RESULT_OK, result); return }
            if (approvals) { ApprovalChecks(this).run(); finish(Activity.RESULT_OK, result); return }
            val context = targetContext
            check(context.applicationInfo.loadLabel(context.packageManager).toString() == "OpenCode") { "Application display name was not updated" }
            check(context.applicationInfo.loadIcon(context.packageManager) is android.graphics.drawable.AdaptiveIconDrawable) { "Launcher icon must support Android masks" }
            val manager = RuntimeManager.get(context)
            RuntimeService.send(context)
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4)
            while (manager.status.phase != Phase.READY && System.nanoTime() < deadline) {
                check(manager.status.phase != Phase.ERROR) { manager.logs() + "\n" + manager.status.message }
                Thread.sleep(500)
            }
            check(manager.status.phase == Phase.READY) { "Runtime startup timed out: ${manager.status}" }
            val info = JSONObject(manager.request("/api/info"))
            check(info.getString("version").isNotBlank())
            sendStatus(0, Bundle().apply { putString("stream", "PASS: bundled Ubuntu + PRoot + OpenCode ${info.getString("version")}\n") })
            val connection = URL(manager.baseUrl + "/api/info").openConnection() as HttpURLConnection
            try { check(connection.responseCode == 401) { "Server must require authentication" } } finally { connection.disconnect() }
            val html = manager.request("/", false)
            check(html.contains("<html", true) && html.contains("script")) { "Web interface is missing" }
            check(manager.pairingUrl().startsWith(manager.baseUrl + "/auth/connect/")) { "Official WebView pairing is unavailable" }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: web page and API authentication\n") })
            val (exit, output) = manager.command("set -e; . /etc/os-release; test \"\$VERSION_ID\" = 24.04; test \"\$(uname -m)\" = aarch64; node --version; git --version; npm --version; printf 'OpenCode persistence OK\\n' > /root/workspace/.ousecode-smoke; cat /root/workspace/.ousecode-smoke; test -s /etc/ssl/certs/ca-certificates.crt", 90)
            check(exit == 0 && output.contains("OpenCode persistence OK")) { "Guest commands failed ($exit): $output" }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: ARM64 Ubuntu, Node, npm, Git, CA bundle, persistent workspace\n") })
            val (groupsExit, groupsOutput) = manager.command("set -e; groups; id -Gn; bash -ic 'printf INTERACTIVE_OK'; bash -lic 'printf LOGIN_OK'")
            check(groupsExit == 0 && !groupsOutput.contains("cannot find name for group ID") && groupsOutput.contains("INTERACTIVE_OK") && groupsOutput.contains("LOGIN_OK")) { "Ubuntu group name lookup or shell startup failed: $groupsOutput" }
            val groupBeforeRestart = java.io.File(manager.rootfs, "etc/group").readBytes()
            manager.restart()
            val restartDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
            while (manager.status.phase != Phase.READY && System.nanoTime() < restartDeadline) {
                check(manager.status.phase != Phase.ERROR) { manager.logs() }
                Thread.sleep(500)
            }
            check(manager.status.phase == Phase.READY)
            check(groupBeforeRestart.contentEquals(java.io.File(manager.rootfs, "etc/group").readBytes())) { "Restart duplicated or changed group entries" }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: Android supplementary group names, clean interactive/login shells, idempotent group setup\n") })
            val (again, persisted) = manager.command("cat /root/workspace/.ousecode-smoke; rm /root/workspace/.ousecode-smoke")
            check(again == 0 && persisted.contains("OpenCode persistence OK"))
            if (ui) { check(android.os.Build.VERSION.SDK_INT >= 29) { "UI device checks require Android 10+" }; checkInterface(manager) }
            if (terminal) checkTerminal(manager)
            if (androidChecksEnabled) AndroidAccessChecks(this, manager, testedActivity).run()
            if (extended) {
                val (networkExit, networkOutput) = manager.command("node -e \"require('https').get('https://opencode.ai/',r=>{console.log('HTTPS '+r.statusCode);r.resume()}).on('error',e=>{console.error(e.code);process.exit(1)})\"", 45)
                check(networkExit == 0 && networkOutput.contains("HTTPS 200")) { "Guest HTTPS failed ($networkExit): $networkOutput" }
                sendStatus(0, Bundle().apply { putString("stream", "PASS: guest DNS and HTTPS\n") })
                manager.upgrade()
                val updateDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(11)
                while (manager.status.phase != Phase.READY && System.nanoTime() < updateDeadline) {
                    check(manager.status.phase != Phase.ERROR) { manager.logs() }
                    Thread.sleep(500)
                }
                check(manager.status.phase == Phase.READY) { "Update did not finish" }
                sendStatus(0, Bundle().apply { putString("stream", "PASS: in-app OpenCode update and restart\n") })
            }
            result.putString("stream", "PASS: restart and data persistence\nAll device smoke checks passed.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun inspectInput() {
        val stop = java.io.File(targetContext.getExternalFilesDir(null), "inspect-stop")
        stop.delete()
        runOnMainSync { WebView.setWebContentsDebuggingEnabled(true) }
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        sendStatus(0, Bundle().apply { putString("stream", "INSPECT_READY pid=${android.os.Process.myPid()}\n") })
        try {
            val deadline = SystemClock.uptimeMillis() + 15 * 60 * 1000
            while (!stop.exists() && SystemClock.uptimeMillis() < deadline) Thread.sleep(500)
        } finally {
            stop.delete()
            runOnMainSync { WebView.setWebContentsDebuggingEnabled(false); activity.finish() }
        }
    }
    private fun checkTerminal(manager: RuntimeManager) {
        val activity = testedActivity ?: startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { testedActivity = it }
        fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                var failure: Throwable? = null
                runOnMainSync { try { ready = predicate() } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
                if (ready) return
                Thread.sleep(100)
            }
            error(message)
        }
        fun shortcut() = runOnMainSync {
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) activity.dispatchKeyEvent(KeyEvent(0, 0, action, KeyEvent.KEYCODE_T, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON))
        }
        lateinit var panel: TemporaryTerminal
        var panelCreated = false
        fun screen() = panel.session.emulator?.screen?.transcriptText.orEmpty()
        fun type(text: String) = runOnMainSync {
            check(panel.terminal.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText(text, 1))
        }
        fun key(code: Int, modifiers: Int = 0) = runOnMainSync {
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) panel.terminal.dispatchKeyEvent(KeyEvent(0, 0, action, code, 0, modifiers))
        }
        fun command(text: String) { type(text); key(KeyEvent.KEYCODE_ENTER) }
        try {
            await("WebView initialization timed out") { walk(activity.window.decorView).any { it is WebView && it.progress == 100 && it.url?.startsWith(manager.baseUrl) == true } }
            runOnMainSync { walk(activity.window.decorView).filterIsInstance<WebView>().single().requestFocus() }
            shortcut()
            runOnMainSync {
                val found = walk(activity.window.decorView).filterIsInstance<TemporaryTerminal>().singleOrNull()
                if (found != null) { panel = found; panelCreated = true }
            }
            check(panelCreated) { "Terminal panel missing; phase=${manager.status.phase}" }
            await("Ubuntu PTY did not start") {
                check(!panel.isClosed) { "Ubuntu shell exited (${panel.session.exitStatus}): ${screen()}" }
                panel.session.pid > 0 && screen().contains("#")
            }
            check(!screen().contains("cannot find name for group ID")) { "Ubuntu terminal still reports unnamed groups" }
            command("test -t 0 && test -t 1 && . /etc/os-release && printf 'PTY_%s_%s\\n' \"\$ID\" \"\$VERSION_ID\"")
            await("Terminal is not a real Ubuntu PTY") { screen().contains("PTY_ubuntu_24.04") }
            command("mkdir -p .ousecode-terminal-smoke; cd .ousecode-terminal-smoke; printf '中文_OK\\n' > proof; printf 'CWD_%s\\n' \"\$PWD\"")
            await("Interactive shell did not preserve cwd") { screen().contains("CWD_/root/workspace/.ousecode-terminal-smoke") }
            val (exit, output) = manager.command("cat /root/workspace/.ousecode-terminal-smoke/proof")
            check(exit == 0 && output.contains("中文_OK")) { "PTY does not share the Ubuntu workspace" }
            val pid = panel.session.pid
            shortcut()
            runOnMainSync { check(panel.visibility == View.GONE && panel.session.pid == pid) }
            shortcut()
            runOnMainSync { check(panel.visibility == View.VISIBLE && panel.session.pid == pid && panel.terminal.hasFocus()) }
            command("printf 'RESUME_%s\\n' \"\$PWD\"")
            await("Hidden terminal lost cwd") { screen().contains("RESUME_/root/workspace/.ousecode-terminal-smoke") }
            command("sleep 600")
            Thread.sleep(500)
            key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
            command("printf 'CTRL_%s\\n' OK")
            await("Ctrl+C did not interrupt foreground command") { screen().contains("CTRL_OK") }
            command("printf 'HISTORY_%s\\n' OK")
            await("History command failed") { screen().contains("HISTORY_OK") }
            key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_ENTER)
            await("Arrow history did not execute") { screen().split("HISTORY_OK").size >= 3 }
            type("cat pro"); key(KeyEvent.KEYCODE_TAB); key(KeyEvent.KEYCODE_ENTER)
            await("Tab completion or Unicode output failed") { screen().contains("中文_OK") }
            runOnMainSync {
                val bitmap = android.graphics.Bitmap.createBitmap(panel.width, panel.height, android.graphics.Bitmap.Config.ARGB_8888)
                panel.draw(android.graphics.Canvas(bitmap))
                java.io.File(targetContext.getExternalFilesDir(null), "terminal-preview.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            command("sleep 600 & echo \$! > /root/workspace/.ousecode-terminal-smoke/child.pid")
            await("Background job PID missing") { java.io.File(manager.home, "workspace/.ousecode-terminal-smoke/child.pid").isFile }
            val child = java.io.File(manager.home, "workspace/.ousecode-terminal-smoke/child.pid").readText().trim().toInt()
            runOnMainSync { walk(panel).single { it.contentDescription?.toString() in setOf("关闭终端", "Close terminal") }.performClick() }
            await("Terminal close did not reap PRoot") { panel.session.pid == -1 }
            val childDeadline = SystemClock.uptimeMillis() + 10000
            while (java.io.File("/proc/$child/stat").let { it.exists() && it.readText().substringAfterLast(") ").first() != 'Z' } && SystemClock.uptimeMillis() < childDeadline) Thread.sleep(100)
            check(!java.io.File("/proc/$child/stat").exists() || java.io.File("/proc/$child/stat").readText().substringAfterLast(") ").first() == 'Z') { "Closing terminal left its child running" }
            shortcut()
            await("Fresh terminal failed") { walk(activity.window.decorView).filterIsInstance<TemporaryTerminal>().firstOrNull()?.let { panel = it; it.session.pid > 0 } == true }
            shortcut()
            manager.restart()
            await("Restart did not close hidden terminal") { panel.isClosed && panel.session.pid == -1 }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: Ubuntu PTY, shared workspace, Unicode, IME, Ctrl+C, Tab, arrow history, global toggle, retained session, close/child cleanup, restart cleanup\n") })
        } finally {
            if (panelCreated) runOnMainSync { panel.close() }
            manager.command("rm -rf /root/workspace/.ousecode-terminal-smoke")
        }
    }

    @android.annotation.TargetApi(29)
    private fun checkInterface(manager: RuntimeManager) {
        val original = manager.terminalDisplayName()
        val appearance = targetContext.getSharedPreferences("appearance", 0)
        val originalPosition = appearance.getFloat("edgePosition", 0.35f)
        val originalRight = appearance.getBoolean("edgeRight", true)
        try {
            manager.setTerminalDisplayName("测试@OpenCode")
            val (exit, output) = manager.command("bash -ic 'printf \"PROMPT=%s\\nUID=%s\\nUSER=%s\\n\" \"\$PS1\" \"\$(id -u)\" \"\$(id -un)\"'")
            check(exit == 0 && output.contains("测试@OpenCode") && output.contains("UID=0") && output.contains("USER=root")) { "Prompt must change while the root identity stays intact" }
            val (loginExit, loginOutput) = manager.command("bash -lic 'printf \"PROMPT=%s\\n\" \"\$PS1\"'")
            check(loginExit == 0 && loginOutput.contains("测试@OpenCode")) { "Login shell did not load prompt" }
            check(runCatching { manager.setTerminalDisplayName("\$(touch /root/workspace/prompt-injection)") }.exceptionOrNull() is IllegalArgumentException) { "Shell expressions must not enter PS1" }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: interactive/login prompts, unchanged root UID, shell syntax rejected\n") })

            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { testedActivity = it }
            lateinit var edge: FloatingSettings
            lateinit var web: WebView
            fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
            val initializationDeadline = SystemClock.uptimeMillis() + 15000
            var initialized = false
            while (!initialized && SystemClock.uptimeMillis() < initializationDeadline) {
                runOnMainSync { initialized = walk(activity.window.decorView).any { it is WebView } }
                Thread.sleep(100)
            }
            check(initialized)
            runOnMainSync {
                val views = walk(activity.window.decorView)
                edge = views.filterIsInstance<FloatingSettings>().single()
                web = views.filterIsInstance<WebView>().single()
                val boot = views.filterIsInstance<BootAnimationView>().single()
                check(views.filterIsInstance<android.widget.LinearLayout>().single { it.parent === web.parent }.visibility == View.GONE) { "Legacy loading controls cover the native animation" }
                check(boot.width > 0 && boot.height > 0) { "Native startup view has no size" }
                val bitmap = android.graphics.Bitmap.createBitmap(boot.width, boot.height, android.graphics.Bitmap.Config.ARGB_8888)
                boot.draw(android.graphics.Canvas(bitmap))
                java.io.File(targetContext.getExternalFilesDir(null), "boot-preview.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val deadline = SystemClock.uptimeMillis() + 30000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                runOnMainSync { ready = web.visibility == View.VISIBLE && web.progress == 100 && web.url?.startsWith(manager.baseUrl) == true && walk(activity.window.decorView).filterIsInstance<BootAnimationView>().single().visibility == View.GONE }
                Thread.sleep(300)
            }
            check(ready) { "OpenCode WebView did not load" }
            runOnMainSync {
                val parent = web.parent as View
                check(web.alpha == 1f && web.scaleX == 1f && web.scaleY == 1f) { "Boot transition left the page faded or scaled" }
                check(web.top == 0 && web.height == parent.height && web.width == parent.width) { "Native toolbar still reserves page space" }
                check(edge.width == (24 * activity.resources.displayMetrics.density).toInt())
                edge.performClick()
            }
            waitForIdleSync()
            runOnMainSync { check(edge.layoutParams.width == (44 * activity.resources.displayMetrics.density).toInt()) { "Settings handle did not expand" } }
            Thread.sleep(3500)
            runOnMainSync { check(edge.layoutParams.width == (24 * activity.resources.displayMetrics.density).toInt()) { "Settings button did not collapse" } }
            val point = IntArray(2)
            var endY = 0f
            var originalY = 0f
            runOnMainSync { edge.getLocationOnScreen(point); originalY = edge.y; endY = (edge.parent as View).height * 0.6f + point[1] - edge.y }
            val startX = point[0] + edge.width / 2f; val startY = point[1] + edge.height / 2f
            val now = SystemClock.uptimeMillis()
            // Dispatch to our own view: tablet desktop windows can live on another input display.
            fun pointer(action: Int, time: Long, y: Float) = runOnMainSync {
                val event = MotionEvent.obtain(now, time, action, startX, y, 0)
                event.offsetLocation(-point[0].toFloat(), -point[1].toFloat())
                edge.dispatchTouchEvent(event); event.recycle()
            }
            pointer(MotionEvent.ACTION_DOWN, now, startY)
            pointer(MotionEvent.ACTION_MOVE, now + 100, endY)
            pointer(MotionEvent.ACTION_UP, now + 200, endY)
            waitForIdleSync()
            runOnMainSync { check(kotlin.math.abs(edge.y - (originalY + endY - startY)) < 6 * activity.resources.displayMetrics.density) { "Dragging did not reposition the handle" } }
            // Exercise Activity keyboard routing and the actual native form, without reading web content.
            runOnMainSync {
                activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_O, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON))
                activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_O, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON))
            }
            fun awaitControl(predicate: (View) -> Boolean): View {
                val end = SystemClock.uptimeMillis() + 10000
                while (SystemClock.uptimeMillis() < end) {
                    var found: View? = null
                    runOnMainSync {
                        found = android.view.inspector.WindowInspector.getGlobalWindowViews()
                            .asSequence().flatMap { walk(it).asSequence() }.firstOrNull(predicate)
                    }
                    found?.let { return it }; Thread.sleep(100)
                }
                error("Expected settings control not found")
            }
            val terminalControl = awaitControl { it is android.widget.TextView && it.text.toString() in setOf("终端  ›", "Terminal  ›") && it.isClickable }
            runOnMainSync { terminalControl.performClick() }
            val nameControl = awaitControl { it is android.widget.TextView && it.text.toString() in setOf("终端显示名称", "Terminal display name") && it.isClickable }
            runOnMainSync {
                val field = MainActivity::class.java.getDeclaredField("settingsDialog").apply { isAccessible = true }
                val decor = (field.get(activity) as android.app.Dialog).window!!.decorView
                val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                decor.draw(android.graphics.Canvas(bitmap))
                java.io.File(targetContext.getExternalFilesDir(null), "settings-preview.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            runOnMainSync { nameControl.performClick() }
            val input = awaitControl { it is android.widget.EditText } as android.widget.EditText
            runOnMainSync { input.setText("ouse-ui@OpenCode") }
            val save = awaitControl { it is android.widget.TextView && it.text.toString() in setOf("保存", "Save") && it.isClickable }
            runOnMainSync { save.performClick() }
            Thread.sleep(500)
            check(manager.terminalDisplayName() == "ouse-ui@OpenCode") { "Settings form did not save prompt" }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: native boot transition, full-page WebView, collapsed/draggable settings, keyboard entry, prompt settings form\n") })
        } finally {
            manager.setTerminalDisplayName(original)
            appearance.edit().putFloat("edgePosition", originalPosition).putBoolean("edgeRight", originalRight).commit()
        }
    }
}
