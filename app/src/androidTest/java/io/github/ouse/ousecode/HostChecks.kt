package io.github.ouse.ousecode

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host-only regressions: no model calls, user content inspection, or runtime restart. */
class HostChecks(private val instrumentation: Instrumentation) {
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 45000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(100)
        }
        error(message)
    }
    private fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
    private fun evaluate(web: WebView, expression: String): String {
        val complete = CountDownLatch(1)
        var result = ""
        main { web.evaluateJavascript(expression) { result = it; complete.countDown() } }
        check(complete.await(10, TimeUnit.SECONDS)) { "WebView evaluation timed out" }
        return result
    }
    fun run() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var web: WebView
        await("OpenCode did not become ready") {
            val found = walk(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
            if (found != null) web = found
            found != null && found.progress == 100 && found.alpha == 1f && RuntimeManager.get(context).status.phase == Phase.READY
        }
        val marker = "host-test-${SystemClock.uptimeMillis()}"
        evaluate(web, "window.__ouseHostTest='$marker'")
        val serverField = RuntimeManager::class.java.getDeclaredField("server").apply { isAccessible = true }
        val manager = RuntimeManager.get(context)
        val server = serverField.get(manager) as Process
        var created = 0
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivity) created++ }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        main { activity.application.registerActivityLifecycleCallbacks(callbacks) }
        try {
            repeat(3) {
                main { activity.moveTaskToBack(true) }
                Thread.sleep(350)
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                })
                await("Original Activity did not resume") { activity.hasWindowFocus() }
                check(evaluate(web, "window.__ouseHostTest") == "\"$marker\"") { "Launcher return reloaded the page" }
            }
            main {
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).send()
            }
            instrumentation.waitForIdleSync()
            main { check(created == 0) { "Returning created $created duplicate main activities" } }
            check(evaluate(web, "window.__ouseHostTest") == "\"$marker\"") { "Notification return reloaded the page" }
            check(serverField.get(manager) === server && server.isAlive) { "Returning restarted the runtime" }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: three background/launcher returns and notification reuse the Activity, page and runtime\n") })
            InputChecks(instrumentation, activity).run()
        } finally {
            main { activity.application.unregisterActivityLifecycleCallbacks(callbacks) }
            evaluate(web, "delete window.__ouseHostTest")
        }
    }
}
