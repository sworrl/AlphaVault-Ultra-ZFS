package com.alphasteg.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class VaultService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "alphavault_service_channel"
        val channelName = "AlphaVault Native Engine"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AlphaVault Native Engine Active")
            .setContentText("100% Pure Kotlin FLAC Steganography Active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
