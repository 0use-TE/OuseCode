package io.github.ouse.ousecode

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Binder, Android shell, PTY and OpenCode MCP checks; no model requests. */
class AndroidAccessChecks(private val test: Instrumentation, private val manager: RuntimeManager, private val existingActivity: android.app.Activity? = null) {
    private val access = AndroidAccess.get(test.targetContext)
    private fun mode(value: AndroidMode) {
        val latch = CountDownLatch(1); var error: String? = null
        manager.updateAndroidMode(value) { error = it; latch.countDown() }
        check(latch.await(40, TimeUnit.SECONDS)) { "Mode change timed out" }
        check(error == null) { "Mode update failed: $error" }
    }
    private fun server() = JSONObject(File(manager.rootfs, "etc/opencode/opencode.json").readText()).getJSONObject("mcp").getJSONObject("servers").getJSONObject("android")
    private fun rpc(method: String, params: JSONObject = JSONObject(), authenticated: Boolean = true): Pair<Int, JSONObject?> {
        val settings = server()
        val connection = URL(settings.getString("url")).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true; connection.connectTimeout = 2000; connection.readTimeout = 140000
        connection.setRequestProperty("Content-Type", "application/json")
        if (authenticated) connection.setRequestProperty("Authorization", settings.getJSONObject("headers").getString("Authorization"))
        try {
            connection.outputStream.use { it.write(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString().toByteArray()) }
            val code = connection.responseCode
            return code to if (code == 200) JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("result") else null
        } finally { connection.disconnect() }
    }
    private fun execute(command: String, timeout: Int = 30): JSONObject {
        val reply = rpc("tools/call", JSONObject().put("name", "exec").put("arguments", JSONObject().put("command", command).put("timeoutSeconds", timeout))).second!!
        check(!reply.optBoolean("isError") || command.contains("exit 7") || timeout == 1) { "Android execution failed: ${reply.getJSONArray("content").getJSONObject(0).getString("text")}" }
        return JSONObject(reply.getJSONArray("content").getJSONObject(0).getString("text"))
    }
    private fun rule(): String {
        val entries = JSONArray(manager.request("/api/config"))
        val own = (0 until entries.length()).map { entries.getJSONObject(it) }.single { it.optString("path") == "/etc/opencode/opencode.json" }
        val rules = own.getJSONObject("info").getJSONArray("permissions")
        return (0 until rules.length()).map { rules.getJSONObject(it) }.single { it.getString("action") == "android_exec" }.getString("effect")
    }
    private fun pass(text: String) { test.sendStatus(0, Bundle().apply { putString("stream", "PASS: $text\n") }) }
    fun run() {
        val activity = existingActivity ?: test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!access.running && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
        check(access.authorized) { "Shizuku unavailable: binder=${access.running}, appPermission=${test.targetContext.checkSelfPermission("moe.shizuku.manager.permission.API_V23")}" }
        val original = access.mode
        try {
            mode(AndroidMode.OFF)
            check(rpc("initialize", authenticated = false).first == 403)
            check(rpc("initialize").second!!.getString("protocolVersion") == "2025-03-26")
            check(rpc("tools/list").second!!.getJSONArray("tools").length() == 0)
            val disabled = rpc("tools/call", JSONObject().put("name", "exec").put("arguments", JSONObject().put("command", "id"))).second!!
            check(disabled.getBoolean("isError") && disabled.toString().contains("ANDROID_ACCESS_DISABLED"))
            check(rule() == "deny")
            pass("authenticated loopback MCP; disabled mode rejects execution")
            mode(AndroidMode.DIRECT)
            check(rule() == "allow")
            var status = manager.request("/api/mcp")
            val connectDeadline = SystemClock.uptimeMillis() + 40000
            while (!status.contains("connected") && !status.contains("failed") && SystemClock.uptimeMillis() < connectDeadline) {
                Thread.sleep(250)
                status = manager.request("/api/mcp")
            }
            check(status.contains("android") && status.contains("connected")) { "OpenCode did not connect the Android MCP" }
            check(rpc("tools/list").second!!.getJSONArray("tools").getJSONObject(0).getString("name") == "exec")
            val uid = execute("id -u").getString("stdout").trim()
            check(uid == access.serviceUid.toString()) { "Android command used the app UID instead of Shizuku" }
            val output = execute("printf STDOUT_OK; printf STDERR_OK >&2; exit 7")
            check(output.getInt("exitCode") == 7 && output.getString("stdout") == "STDOUT_OK" && output.getString("stderr") == "STDERR_OK")
            val timeout = execute("sleep 30", 1)
            check(timeout.getInt("exitCode") == 124 && timeout.getBoolean("timedOut"))
            pass("OpenCode MCP connection, direct policy, real Shizuku UID, stdout/stderr/exit code and timeout")
            checkPty(uid, activity)
            mode(AndroidMode.ASK)
            check(rule() == "allow")
            pass("OpenCode delegates Android Ask policy to the native approval gate")
            if (android.os.Build.VERSION.SDK_INT >= 29) checkManualApproval(activity)
            checkPolicy()
            if (android.os.Build.VERSION.SDK_INT >= 29) checkSmart(activity)
        } finally { mode(original); test.runOnMainSync { activity.finish() } }
    }
    private fun checkPolicy() {
        val queries = listOf("id -u", "/system/bin/id", "toybox uname -a", "ps -A", "df -h", "ls -la /data/local/tmp", "stat /system/bin/sh", "getprop ro.product.model", "cat /proc/meminfo /proc/uptime", "dumpsys battery", "pm list packages -3", "ip addr show", "wm size", "ss -tulnp", "netstat -tun", "date")
        val confirms = listOf("rm -rf /", "chmod 777 /data", "chown root /data", "pm clear example.app", "pm uninstall example.app", "settings put global airplane_mode_on 1", "am start -n example/.Main", "kill -9 123", "sh -c id", "env id", "su -c id", "curl example.com", "cat /data/local/tmp/token", "getprop ro.serialno", "dumpsys notification", "dumpsys battery set level 10", "wm size 100x100", "ip route add default", "ss -K", "date 010112002026", "id; reboot", "id && reboot", "id | sh", "id > /data/local/tmp/test", "ls \$(reboot)", "ls `reboot`", "id\nreboot", "id\rreboot", "ls \\\n; reboot", "'id'", "PATH=/data/local/tmp id", "./id", "/data/local/tmp/id", "/system/bin/../bin/id", "toybox rm /data/test", "ls *", "id\u202ereboot")
        queries.forEach { check(AndroidCommandPolicy.isQuery(it)) { "Query required confirmation: $it" } }
        confirms.forEach { check(!AndroidCommandPolicy.isQuery(it)) { "Command bypassed confirmation: $it" } }
        pass("Smart query grammar and confirmation for mutations, private reads, wrappers and shell syntax")
    }
    @android.annotation.TargetApi(29)
    private fun checkSmart(activity: android.app.Activity) {
        mode(AndroidMode.SMART)
        check(rule() == "allow")
        check(execute("id -u").getString("stdout").trim() == access.serviceUid.toString())
        check(access.pendingApproval == null) { "Known query unexpectedly asked" }
        val path = "/data/local/tmp/ousecode-smart-${SystemClock.uptimeMillis()}"
        fun call(command: String) = rpc("tools/call", JSONObject().put("name", "exec").put("arguments", JSONObject().put("command", command).put("purpose", "OpenCode smart-mode device check"))).second!!
        fun begin(command: String): java.util.concurrent.FutureTask<JSONObject> {
            val future = java.util.concurrent.FutureTask { call(command) }
            Thread(future, "smart-device-check").start()
            val deadline = SystemClock.uptimeMillis() + 10000
            while (access.pendingApproval == null && !future.isDone && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            check(access.pendingApproval?.command == command) { "Smart command did not wait for approval" }
            test.waitForIdleSync()
            return future
        }
        try {
            val denied = begin("printf DENIED > $path")
            check(call("ls $path").getBoolean("isError")) { "Unapproved command already wrote a file" }
            test.runOnMainSync {
                fun find(view: View): AndroidApprovalCard? {
                    if (view is AndroidApprovalCard) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                val decor = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOf { find(it) }
                val image = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                decor.draw(android.graphics.Canvas(image))
                File(test.targetContext.getExternalFilesDir(null), "android-approval-preview.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            click(setOf("拒绝", "Deny"))
            val denial = denied.get(5, TimeUnit.SECONDS)
            check(denial.getBoolean("isError") && denial.toString().contains("ANDROID_COMMAND_DENIED"))
            check(call("ls $path").getBoolean("isError")) { "Denied command wrote a file" }
            val allowed = begin("printf SMART_OK > $path")
            click(setOf("仅允许本次", "Allow once"))
            check(!allowed.get(15, TimeUnit.SECONDS).getBoolean("isError"))
            check(!call("ls $path").getBoolean("isError")) { "Approved command did not execute" }
            mode(AndroidMode.DIRECT)
            check(execute("cat $path").getString("stdout") == "SMART_OK")
            mode(AndroidMode.SMART)
            // The same exact command must ask again, rather than retaining the previous approval.
            val repeated = begin("printf SMART_OK > $path")
            mode(AndroidMode.OFF)
            val cancelled = repeated.get(5, TimeUnit.SECONDS)
            check(cancelled.getBoolean("isError") && cancelled.toString().contains("ANDROID_MODE_CHANGED"))
            pass("Smart MCP auto-query, native approval UI, no execution before consent, denial, allow once, and mode-change cancellation")
        } finally {
            mode(AndroidMode.DIRECT)
            check(execute("rm -f $path").getInt("exitCode") == 0)
        }
    }
    private fun checkPty(uid: String, activity: android.app.Activity) {
        fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
        fun await(message: String, predicate: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < end) {
                var ready = false; var error: Throwable? = null
                test.runOnMainSync { try { ready = predicate() } catch (caught: Throwable) { error = caught } }
                error?.let { throw it }; if (ready) return
                Thread.sleep(100)
            }
            error(message)
        }
        await("App initialization timed out") { walk(activity.window.decorView).any { it is FloatingSettings } }
        test.runOnMainSync { MainActivity::class.java.getDeclaredMethod("showAndroidTerminal").apply { isAccessible = true }.invoke(activity) }
        var panel: TemporaryTerminal? = null
        await("Android PTY failed to open") {
            panel = walk(activity.window.decorView).filterIsInstance<TemporaryTerminal>().firstOrNull { it.androidShell != null }
            panel?.session?.pid?.let { it > 0 } == true
        }
        val terminal = panel!!
        fun screen() = terminal.session.emulator!!.screen.transcriptText
        fun command(value: String) = test.runOnMainSync {
            terminal.terminal.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText(value, 1)
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) terminal.terminal.dispatchKeyEvent(KeyEvent(0, 0, action, KeyEvent.KEYCODE_ENTER, 0))
        }
        command("test -t 0 && test -t 1 && printf 'ANDROID_UID_%s\\n' \"\$(id -u)\"")
        await("Android terminal is not a Shizuku PTY") { screen().contains("ANDROID_UID_$uid") }
        command("sleep 30")
        Thread.sleep(300)
        test.runOnMainSync { terminal.terminal.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON)) }
        command("printf 'PTY_CTRL_%s\\n' OK")
        await("Android PTY Ctrl+C failed") { screen().contains("PTY_CTRL_OK") }
        val pid = terminal.session.pid
        test.runOnMainSync { terminal.hide(); terminal.show(); check(terminal.session.pid == pid) }
        mode(AndroidMode.OFF)
        await("Disabling Android access left the terminal alive") { terminal.isClosed && terminal.session.pid == -1 }
        pass("interactive Android PTY, Ctrl+C, hidden session retention and disable cleanup")
    }
    @android.annotation.TargetApi(29)
    private fun checkManualApproval(activity: android.app.Activity) {
        fun open() = test.runOnMainSync { MainActivity::class.java.getDeclaredMethod("showAndroidTerminal").apply { isAccessible = true }.invoke(activity) }
        fun panel(): TemporaryTerminal? = MainActivity::class.java.getDeclaredField("androidTerminal").apply { isAccessible = true }.get(activity) as TemporaryTerminal?
        open()
        click(setOf("拒绝", "Deny"))
        test.waitForIdleSync()
        test.runOnMainSync { check(panel() == null) { "Cancel opened an Android terminal" } }
        open()
        click(setOf("仅允许本次", "Allow once"))
        val end = SystemClock.uptimeMillis() + 20000
        var terminal: TemporaryTerminal? = null
        while (terminal?.session?.pid?.let { it > 0 } != true && SystemClock.uptimeMillis() < end) {
            test.runOnMainSync { terminal = panel() }; Thread.sleep(100)
        }
        check(terminal?.session?.pid?.let { it > 0 } == true) { "Approved Android terminal did not start" }
        test.runOnMainSync { terminal!!.hide() }
        open()
        test.runOnMainSync { check(panel() === terminal && terminal!!.visibility == View.VISIBLE); terminal!!.close() }
        pass("Ask-mode manual terminal confirmation, cancel, allow and hidden session reuse")
        mode(AndroidMode.OFF)
        test.runOnMainSync {
            val pageType = Class.forName("io.github.ouse.ousecode.MainActivity\$SettingsPage")
            val androidPage = pageType.enumConstants!!.single { it.toString() == "ANDROID" }
            MainActivity::class.java.getDeclaredMethod("openSettingsPanel", pageType).apply { isAccessible = true }.invoke(activity, androidPage)
        }
        test.waitForIdleSync()
        test.runOnMainSync {
            val dialog = MainActivity::class.java.getDeclaredField("settingsDialog").apply { isAccessible = true }.get(activity) as android.app.Dialog
            val decor = dialog.window!!.decorView
            val image = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(image))
            File(test.targetContext.getExternalFilesDir(null), "android-settings-preview.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle(); dialog.dismiss()
        }
    }
    @android.annotation.TargetApi(29)
    private fun click(names: Set<String>) {
        val end = SystemClock.uptimeMillis() + 10000
        var clicked = false
        fun find(view: View): View? {
            if (view is android.widget.TextView && view.isClickable && view.text.toString() in names) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        while (!clicked && SystemClock.uptimeMillis() < end) {
            test.runOnMainSync {
                // Inspect only this process's windows; other desktop apps may have input focus.
                for (root in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    find(root)?.let { it.performClick(); clicked = true }
                    if (clicked) break
                }
            }
            if (!clicked) Thread.sleep(100)
        }
        check(clicked) { "Android terminal confirmation control missing: $names" }
    }

}
