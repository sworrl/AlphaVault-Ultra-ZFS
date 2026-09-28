package com.alphasteg.pro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.alphasteg.pro.security.DuressWipe

/**
 * Runs [DuressWipe] in the foreground so it outlives the activity that started it:
 * backgrounding or swiping the app away does not stop it. The notification reads
 * as routine library maintenance.
 */
class DuressWipeService : Service() {

    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!running) {
            running = true
            Thread {
                runCatching { DuressWipe.run(this) { _, _, _ -> } }
                running = false
                stopSelf()
            }.start()
        }
        // If the process is killed, the pending flag brings the wipe back on the next launch.
        return START_STICKY
    }

    private fun startInForeground() {
        val channelId = "library_maintenance"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(channelId, "Library maintenance", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Updating music library")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val NOTIFICATION_ID = 1002
    }
}
