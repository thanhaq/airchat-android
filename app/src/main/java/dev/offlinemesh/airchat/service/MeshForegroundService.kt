package dev.offlinemesh.airchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.offlinemesh.airchat.MainActivity
import dev.offlinemesh.airchat.R
import dev.offlinemesh.airchat.core.AirChatRuntime
import dev.offlinemesh.airchat.core.BackgroundPowerStatus

class MeshForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AirChatRuntime.current()?.disableBackgroundMesh()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val runtime = AirChatRuntime.get(applicationContext)
        runtime.enableBackgroundMesh()
        startForegroundCompat(runtime.backgroundPowerStatus.value)
        return START_STICKY
    }

    override fun onDestroy() {
        AirChatRuntime.current()?.disableBackgroundMesh()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(status: BackgroundPowerStatus) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(status: BackgroundPowerStatus): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.background_mesh_title))
            .setContentText(status.notificationText)
            .setSubText("Power: ${status.diagnosticsLabel}")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_launcher, getString(R.string.background_mesh_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.background_mesh_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.background_mesh_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_STOP = "dev.offlinemesh.airchat.action.STOP_BACKGROUND_MESH"
        private const val CHANNEL_ID = "airchat_background_mesh"
        private const val NOTIFICATION_ID = 42
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeshForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
