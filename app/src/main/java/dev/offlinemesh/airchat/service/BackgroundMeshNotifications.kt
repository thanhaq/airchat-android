package dev.offlinemesh.airchat.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.offlinemesh.airchat.MainActivity
import dev.offlinemesh.airchat.R
import dev.offlinemesh.airchat.core.BackgroundAlert
import dev.offlinemesh.airchat.core.BackgroundAlertTracker
import dev.offlinemesh.airchat.transport.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BackgroundMeshNotifications(
    private val context: Context,
    private val router: MeshRouter,
    private val backgroundMeshEnabled: StateFlow<Boolean>,
    private val isUiVisible: () -> Boolean,
    private val scope: CoroutineScope
) {
    private val tracker = BackgroundAlertTracker()
    private val notificationManager = NotificationManagerCompat.from(context)
    private val jobs = mutableListOf<Job>()

    init {
        createNotificationChannel()
        tracker.markExisting(router.messages.value, router.receivedFiles.value)
        jobs += scope.launch {
            router.messages.collect { messages ->
                tracker.consumeMessageAlert(messages, shouldNotify())?.let { alert ->
                    postAlert(NOTIFICATION_ID_MESSAGE, alert)
                }
            }
        }
        jobs += scope.launch {
            router.receivedFiles.collect { files ->
                tracker.consumeFileAlert(files, shouldNotify())?.let { alert ->
                    postAlert(NOTIFICATION_ID_FILE, alert)
                }
            }
        }
    }

    fun clearVisibleNotifications() {
        notificationManager.cancel(NOTIFICATION_ID_MESSAGE)
        notificationManager.cancel(NOTIFICATION_ID_FILE)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        clearVisibleNotifications()
    }

    private fun shouldNotify(): Boolean =
        backgroundMeshEnabled.value && !isUiVisible() && canPostNotifications()

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun postAlert(id: Int, alert: BackgroundAlert) {
        val openIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(id, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.mesh_alerts_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.mesh_alerts_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "airchat_mesh_alerts"
        const val NOTIFICATION_ID_MESSAGE = 43
        const val NOTIFICATION_ID_FILE = 44
        const val REQUEST_OPEN = 3
    }
}
