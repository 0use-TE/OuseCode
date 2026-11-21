package io.github.ouse.ousecode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class RuntimeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val manager by lazy { RuntimeManager.get(this) }
    private val observer: (RuntimeStatus) -> Unit = { status ->
        if (status.phase == Phase.STOPPED || status.phase == Phase.ERROR) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else getSystemService(NotificationManager::class.java).notify(1, notification(status.message))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("runtime", "OpenCode", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCode:runtime").apply { acquire() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(manager.text("正在准备本机环境", "Preparing local environment")))
        when (intent?.action) {
            "stop" -> manager.stop()
            "restart" -> manager.restart()
            "upgrade" -> manager.upgrade()
            else -> manager.start()
        }
        manager.unobserve(observer)
        manager.observe(observer)
        return START_NOT_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RuntimeService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "runtime").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("OpenCode").setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, manager.text("停止", "Stop"), stop).build()).build()
    }
    override fun onDestroy() {
        manager.unobserve(observer)
        if (manager.status.phase == Phase.READY) manager.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
    companion object {
        fun send(context: Context, action: String = "start") {
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(action))
        }
    }
}
