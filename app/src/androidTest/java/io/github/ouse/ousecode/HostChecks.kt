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
class HostChecks(private val instrumentation: Instrumentation, private val bootOnly: Boolean = false) {
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
    private fun checkSignalRendering(activity: Activity, splash: BootAnimationView) {
        val parent = splash.parent as ViewGroup
        // Hide user content while capturing the actual hardware-rendered tear.
        val backdrop = View(activity).apply { setBackgroundColor(0xff181818.toInt()) }
        val progress = BootAnimationView::class.java.getDeclaredField("exitProgress").apply { isAccessible = true }
        main {
            parent.addView(backdrop, parent.indexOfChild(splash), ViewGroup.LayoutParams(-1, -1))
            splash.resetBoot(); splash.visibility = View.VISIBLE; splash.finishBoot()
            splash.playExit {}; splash.pauseAnimation()
        }
        try {
            val hashes = HashSet<Int>()
            for ((index, value) in listOf(.08f, .28f, .56f, 1f).withIndex()) {
                main { progress.setFloat(splash, value); splash.invalidate() }
                Thread.sleep(80)
                val image = android.graphics.Bitmap.createBitmap(960, 540, android.graphics.Bitmap.Config.ARGB_8888)
                val latch = CountDownLatch(1)
                var result = -1
                main {
                    val origin = IntArray(2); splash.getLocationInWindow(origin)
                    val bounds = android.graphics.Rect(origin[0], origin[1], origin[0] + splash.width, origin[1] + splash.height)
                    android.view.PixelCopy.request(activity.window, bounds, image, { result = it; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
                }
                check(latch.await(10, TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS) { "Signal screenshot failed: $result" }
                val pixels = IntArray(960 * 540); image.getPixels(pixels, 0, 960, 0, 0, 960, 540)
                hashes.add(pixels.contentHashCode())
                if (value == 1f) check(image.getPixel(480, 270) == 0xff181818.toInt()) { "Signal tear left an opaque background" }
                java.io.File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "signal-$index.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            check(hashes.size == 4) { "Signal tear stages did not visibly change" }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: four hardware-rendered signal stages differ; final frame exposes the workspace\n") })
        } finally {
            main { splash.resetBoot(); splash.visibility = View.GONE; splash.pauseAnimation(); parent.removeView(backdrop) }
        }
    }
    fun run() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var web: WebView
        val exitProgress = BootAnimationView::class.java.getDeclaredField("exitProgress").apply { isAccessible = true }
        val coldFrames = ArrayList<Float>()
        await("OpenCode did not become ready") {
            walk(activity.window.decorView).filterIsInstance<BootAnimationView>().singleOrNull()?.let {
                val p = exitProgress.getFloat(it)
                if (it.visibility == View.VISIBLE && p >= 0f) coldFrames.add(p)
            }
            val found = walk(activity.window.decorView).filterIsInstance<WebView>().singleOrNull()
            if (found != null) web = found
            found != null && found.progress == 100 && found.alpha == 1f && RuntimeManager.get(context).status.phase == Phase.READY &&
                walk(activity.window.decorView).none { it is BootAnimationView && it.visibility == View.VISIBLE }
        }
        check(coldFrames.size >= 3 && coldFrames.last() > coldFrames.first()) { "Actual startup skipped the visible signal tear: $coldFrames" }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: actual startup displayed ${coldFrames.size} sampled signal-tear frames, progress ${coldFrames.first()} to ${coldFrames.last()}\n") })
        val splash = walk(activity.window.decorView).filterIsInstance<BootAnimationView>().single()
        val finishedField = MainActivity::class.java.getDeclaredField("splashFinished").apply { isAccessible = true }
        val finishBoot = MainActivity::class.java.getDeclaredMethod("finishBoot").apply { isAccessible = true }
        val metricsThread = android.os.HandlerThread("boot-frame-checks").apply { start() }
        val frames = java.util.Collections.synchronizedList(ArrayList<Long>())
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION))
        }
        main { activity.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(metricsThread.looper)) }
        try {
            repeat(3) { attempt ->
                main {
                    splash.resetBoot(); splash.visibility = View.VISIBLE; splash.resumeAnimation()
                    splash.setBootProgress(100, "Local workspace ready")
                    finishedField.setBoolean(activity, false)
                }
                Thread.sleep(500) // Render the rain before measuring the handoff.
                frames.clear()
                val start = SystemClock.uptimeMillis()
                var kickoff = 0L
                main {
                    val before = SystemClock.uptimeMillis()
                    finishBoot.invoke(activity)
                    kickoff = SystemClock.uptimeMillis() - before
                }
                await("Boot reveal did not finish") {
                    check(web.alpha == 1f && web.scaleX == 1f && web.scaleY == 1f) { "Boot reveal transformed the WebView" }
                    splash.visibility == View.GONE
                }
                val elapsed = SystemClock.uptimeMillis() - start
                Thread.sleep(100)
                val durations = synchronized(frames) { frames.toList() }
                val slow = durations.count { it > 32_000_000 }
                val worst = (durations.maxOrNull() ?: 0) / 1_000_000f
                main { check(splash.layerType == View.LAYER_TYPE_NONE) { "Boot reveal retained its hardware layer" } }
                main {
                    val frame = BootAnimationView::class.java.getDeclaredField("signalFrame").apply { isAccessible = true }.get(splash)
                    check(frame != null && frame.javaClass.getMethod("getReady").invoke(frame) == false) { "Boot reveal retained its GPU frame" }
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "PASS: boot reveal ${attempt + 1}, elapsed=${elapsed}ms, kickoff=${kickoff}ms, frames=${durations.size}, over32ms=$slow, worst=${worst}ms; static WebView, released layer\n")
                })
            }
            main {
                var completed = false
                splash.resetBoot(); splash.visibility = View.VISIBLE; splash.resumeAnimation()
                splash.playExit { completed = true }
                val tick = BootAnimationView::class.java.getDeclaredField("exitTick").apply { isAccessible = true }.get(splash) as android.view.Choreographer.FrameCallback
                tick.doFrame(1_000_000_000L); tick.doFrame(6_000_000_000L)
                check(exitProgress.getFloat(splash) < .04f) { "Busy startup consumed the whole signal tear" }
                splash.resetBoot()
                check(!completed) { "Cancelled transition completed its old callback" }
                check(splash.alpha == 1f)
                splash.visibility = View.GONE; splash.pauseAnimation(); splash.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        } finally {
            main { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            metricsThread.quitSafely()
        }
        checkSignalRendering(activity, splash)
        if (bootOnly) return
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
