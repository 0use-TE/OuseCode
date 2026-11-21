package io.github.ouse.ousecode

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Exercises actual Shizuku commands and both background decision actions, without model calls. */
class ApprovalChecks(private val test: Instrumentation) {
    private val context = test.targetContext
    private val access = AndroidAccess.get(context)
    private val manager = RuntimeManager.get(context)
    private fun main(run: () -> Unit) = test.runOnMainSync(run)
    private fun pass(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "PASS: $message\n") })
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 45000
        while (SystemClock.uptimeMillis() < end) { if (predicate()) return; Thread.sleep(50) }
        error(message)
    }
    private fun find(view: View): AndroidApprovalCard? {
        if (view is AndroidApprovalCard) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun card(): AndroidApprovalCard? {
        var result: AndroidApprovalCard? = null
        main { result = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) } }
        return result
    }
    fun run() {
        check(Settings.canDrawOverlays(context)) { "Overlay permission is required for this device check" }
        val original = access.mode
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun currentNotice() = notifications.activeNotifications.firstOrNull { it.id == AndroidApprovalUi.NOTIFICATION_ID }
        fun begin(command: String): FutureTask<String> {
            val future = FutureTask {
                try { access.execute(command, 3, "后台确认测试 · Background approval check").getString("stdout") }
                catch (error: Exception) { error.message.orEmpty() }
            }
            Thread(future, "approval-device-check").start()
            await("Request did not wait for a decision") { access.pendingApproval != null && card() != null && currentNotice() != null }
            check(!future.isDone)
            return future
        }
        try {
            await("Engine did not start") { manager.status.phase == Phase.READY }
            await("Shizuku binder or authorization is unavailable") { access.authorized }
            access.setMode(AndroidMode.SMART, manager.rootfs)
            check(access.execute("id -u", 3).getString("stdout").trim() == access.serviceUid.toString())
            check(access.pendingApproval == null)
            main { activity.moveTaskToBack(true) }
            val denied = begin("printf SHOULD_NOT_RUN")
            val request = access.pendingApproval!!
            val before = request.remainingMillis
            val view = card()!!
            await("Overlay card has no size") { view.width > 0 && view.height > 0 }
            main {
                val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image))
                File(context.getExternalFilesDir(null), "approval-overlay-preview.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            Thread.sleep(1300)
            check(before - request.remainingMillis >= 1200)
            val staleAllow = currentNotice()!!.notification.actions[1].actionIntent
            currentNotice()!!.notification.actions[0].actionIntent.send()
            check(denied.get(5, TimeUnit.SECONDS) == "ANDROID_COMMAND_DENIED")
            await("Dismissed approval remained visible") { card() == null && currentNotice() == null }
            pass("background overlay and notification, shared countdown, denial and cleanup")

            val allowed = begin("printf BACKGROUND_ALLOWED")
            val active = access.pendingApproval!!
            try { staleAllow.send() } catch (_: PendingIntent.CanceledException) { }
            Thread.sleep(150)
            check(access.pendingApproval === active && !allowed.isDone) { "Stale notification approved another command" }
            currentNotice()!!.notification.actions[1].actionIntent.send()
            check(allowed.get(10, TimeUnit.SECONDS) == "BACKGROUND_ALLOWED")
            await("Approved card was not removed") { card() == null }
            pass("notification allow executes exactly once; old action cannot approve the next request")

            access.setMode(AndroidMode.ASK, manager.rootfs)
            val ask = begin("id -u")
            access.reply(access.pendingApproval!!, false)
            check(ask.get(5, TimeUnit.SECONDS) == "ANDROID_COMMAND_DENIED")
            pass("Ask mode routes even known queries through the same native gate")

            access.setMode(AndroidMode.SMART, manager.rootfs)
            val started = SystemClock.elapsedRealtime()
            val expired = begin("printf EXPIRED_MUST_NOT_RUN")
            val expiring = access.pendingApproval!!
            check(expiring.remainingMillis in 1..30000L)
            check(expired.get(35, TimeUnit.SECONDS) == "ANDROID_CONFIRMATION_TIMED_OUT")
            check(SystemClock.elapsedRealtime() - started in 29500..35000L)
            access.reply(expiring, true)
            await("Timed-out approval remained visible") { card() == null && currentNotice() == null }
            pass("actual 30-second timeout removes UI and rejects a late allow")

            val cancelled = begin("printf MODE_CHANGED_MUST_NOT_RUN")
            access.setMode(AndroidMode.OFF, manager.rootfs)
            check(cancelled.get(5, TimeUnit.SECONDS) == "ANDROID_MODE_CHANGED")
            await("Mode change left approval UI") { card() == null && currentNotice() == null }
            pass("mode change cancels command, overlay and notification")
        } finally {
            access.setMode(original, manager.rootfs)
            main { activity.finish() }
        }
    }
}
