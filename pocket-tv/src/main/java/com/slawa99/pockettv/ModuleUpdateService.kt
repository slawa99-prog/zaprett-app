package com.slawa99.pockettv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Started only by a visible, user-confirmed update action. Installation itself survives the UI process. */
class ModuleUpdateService : Service() {
    private var started = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("module_update", "Обновление обхода", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("updates", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(72, Notification.Builder(this, "module_update")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Обновление обхода YouTube")
            .setContentText("Откройте SV Pocket, чтобы увидеть прогресс").setContentIntent(launch).setOngoing(true).build())
        if (!started) {
            started = true
            (application as PocketApplication).performModuleUpdate { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
}
