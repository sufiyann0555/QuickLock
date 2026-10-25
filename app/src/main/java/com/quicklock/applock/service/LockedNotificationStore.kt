package com.quicklock.applock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.quicklock.applock.R
import com.quicklock.applock.ui.lock.NotificationUnlockActivity
import java.util.concurrent.ConcurrentHashMap

data class LockedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val smallIcon: IconCompat?,
    val title: String,
    val text: String?,
    val contentIntent: PendingIntent?,
)

class LockedNotificationStore {
    private val notifications = ConcurrentHashMap<String, LockedNotification>()

    fun put(notification: LockedNotification) {
        notifications[notification.key] = notification
    }

    operator fun get(key: String): LockedNotification? = notifications[key]

    fun remove(key: String) {
        notifications.remove(key)
    }

    fun all(): List<LockedNotification> = notifications.values.toList()
}

object LockedNotificationPublisher {
    const val CHANNEL_ID = "locked_app_notifications"
    const val TAG_PREFIX = "quicklock_locked:"
    private const val CHANNEL_NAME = "Locked app notifications"

    fun canPost(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        return manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Redacted alerts from locked apps"
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun postLocked(context: Context, notification: LockedNotification) {
        val unlockIntent = Intent(context, NotificationUnlockActivity::class.java)
            .setData(Uri.parse("quicklock://locked-notification/${Uri.encode(notification.key)}"))
            .putExtra(NotificationUnlockActivity.EXTRA_NOTIFICATION_KEY, notification.key)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.key.hashCode(),
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val redacted = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(notification.smallIcon ?: IconCompat.createWithResource(context, R.drawable.ic_notification_lock))
            .setContentTitle(notification.appLabel)
            .setContentText("Unlock to view this notification")
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification_lock, "Unlock to view", pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, notification.appLabel, notification.smallIcon))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(tag(notification.key), notification.key.hashCode(), redacted)
    }

    fun postRestored(context: Context, notification: LockedNotification) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(notification.smallIcon ?: IconCompat.createWithResource(context, R.drawable.ic_notification_lock))
            .setContentTitle(notification.title.ifBlank { notification.appLabel })
            .setContentIntent(notification.contentIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, notification.appLabel, notification.smallIcon))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        notification.text?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        NotificationManagerCompat.from(context).notify(
            tag(notification.key),
            notification.key.hashCode(),
            builder.build(),
        )
    }

    fun cancel(context: Context, key: String) {
        NotificationManagerCompat.from(context).cancel(tag(key), key.hashCode())
    }

    fun keyFromTag(tag: String?): String? =
        tag?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)

    private fun tag(key: String) = TAG_PREFIX + key

    private fun publicVersion(context: Context, appLabel: String, smallIcon: IconCompat?): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon ?: IconCompat.createWithResource(context, R.drawable.ic_notification_lock))
            .setContentTitle(appLabel)
            .setContentText("Unlock to view this notification")
            .build()
}
