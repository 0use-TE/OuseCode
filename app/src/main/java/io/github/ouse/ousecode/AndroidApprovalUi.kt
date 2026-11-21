package io.github.ouse.ousecode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager

/** Process-wide presenter, independent of Activity visibility. No command is stored in intents. */
class AndroidApprovalUi(private val context: Context, private val access: AndroidAccess) {
    private val main = Handler(Looper.getMainLooper())
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var current: AndroidApproval? = null
    private var overlay: AndroidApprovalCard? = null
    private var windows: WindowManager? = null
    private val intents = mutableListOf<PendingIntent>()
    val showingOverlay get() = overlay != null
    private val tick = object : Runnable {
        override fun run() {
            val request = current ?: return
            if (request.remainingMillis == 0L) access.reply(request, false)
            else { notify(request); main.postDelayed(this, 1000) }
        }
    }
    fun sync(force: Boolean = false) {
        val request = access.pendingApproval
        if (current === request && !force) return
        clear()
        current = request
        if (request == null) return
        if (Settings.canDrawOverlays(context)) {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val base = context.createDisplayContext(display)
            val ui = if (Build.VERSION.SDK_INT >= 30) base.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else base
            val manager = ui.getSystemService(WindowManager::class.java)
            val card = AndroidApprovalCard(ui, request) { access.reply(request, it) }
            val density = ui.resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                minOf(ui.resources.displayMetrics.widthPixels - (32 * density).toInt(), (420 * density).toInt()),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.END; x = (16 * density).toInt(); y = (64 * density).toInt(); setTitle("OpenCode Android approval") }
            try { manager.addView(card, params); windows = manager; overlay = card }
            catch (_: WindowManager.BadTokenException) { /* Notification and foreground card remain available. */ }
            catch (_: SecurityException) { /* Permission can be revoked between checking and adding. */ }
        }
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, text("Android 操作确认", "Android approvals"), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
        tick.run()
    }
    private fun text(zh: String, en: String) = if (context.resources.configuration.locales[0].language == "zh") zh else en
    private fun action(request: AndroidApproval, allow: Boolean): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, AndroidApprovalReceiver::class.java).setData(Uri.parse("ousecode-approval://${request.id}/${if (allow) "allow" else "deny"}")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).also { intents += it }
    private fun notify(request: AndroidApproval) {
        if (!notifications.areNotificationsEnabled()) return
        val seconds = (request.remainingMillis + 999) / 1000
        val clock = text("剩余 ", "Left ") + "%d:%02d".format(seconds / 60, seconds % 60)
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // PendingIntents are generated once for this request, then reused by countdown updates.
        if (intents.isEmpty()) { action(request, false); action(request, true) }
        val body = AndroidApprovalCard.visibleText(request.command)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(text("Android 操作待确认", "Android action needs approval")).setContentText(body)
            .setSubText(clock).setStyle(Notification.BigTextStyle().bigText(body + if (request.purpose.isBlank()) "" else "\n\n" + AndroidApprovalCard.visibleText(request.purpose)))
            .setContentIntent(open).setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("OpenCode").setContentText(text("有操作等待确认", "An action is waiting for approval")).build())
            .setOnlyAlertOnce(true).setOngoing(true).setWhen(System.currentTimeMillis() + request.remainingMillis)
            .setUsesChronometer(true).setChronometerCountDown(true).setTimeoutAfter(request.remainingMillis)
            .addAction(Notification.Action.Builder(null, text("拒绝", "Deny"), intents[0]).build())
            .addAction(Notification.Action.Builder(null, text("仅允许本次", "Allow once"), intents[1]).build()).build()
        notifications.notify(NOTIFICATION_ID, notification)
    }
    private fun clear() {
        main.removeCallbacks(tick)
        overlay?.let { windows!!.removeView(it) }; overlay = null; windows = null
        notifications.cancel(NOTIFICATION_ID)
        intents.forEach { it.cancel() }; intents.clear()
        current = null
    }
    companion object { const val CHANNEL = "android-approvals"; const val NOTIFICATION_ID = 2 }
}

class AndroidApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.data ?: return
        val access = AndroidAccess.get(context)
        val request = access.pendingApproval ?: return
        if (data.scheme != "ousecode-approval" || data.host != request.id) return
        when (data.path) { "/allow" -> access.reply(request, true); "/deny" -> access.reply(request, false) }
    }
}
