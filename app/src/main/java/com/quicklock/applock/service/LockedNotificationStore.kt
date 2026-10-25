package com.quicklock.applock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.quicklock.applock.R
import com.quicklock.applock.ui.lock.NotificationUnlockActivity
import java.util.concurrent.ConcurrentHashMap

data class LockedNotification(
    val key: String,
    val captureToken: String,
    val packageName: String,
    val originalId: Int,
    val originalTag: String?,
    val appLabel: String,
    val smallIcon: IconCompat?,
    val sourceAppIcon: IconCompat?,
    val sourceAppLargeIcon: Bitmap?,
    val title: String,
    val text: String?,
    val contentIntent: PendingIntent?,
    val postedAtMillis: Long,
    val groupKey: String?,
    val isGroupSummary: Boolean,
    val number: Int,
    val category: String?,
)

data class NotificationTapAction(
    val notificationKey: String,
    val captureToken: String,
    val packageName: String,
    val contentIntent: PendingIntent?,
    val capturedAtMillis: Long,
)

data class PackageRestoreResult(
    val tappedNotification: LockedNotification?,
    val restoredCount: Int,
    val failedCount: Int,
)

class LockedNotificationStore {
    private val notifications = ConcurrentHashMap<String, LockedNotification>()
    private val tapActions = ConcurrentHashMap<String, NotificationTapAction>()
    private val claimedActions = ConcurrentHashMap.newKeySet<String>()

    fun put(notification: LockedNotification): LockedNotification? {
        val previous = notifications.put(notification.key, notification)
        tapActions[notification.captureToken] = notification.tapAction()
        previous?.let { tapActions.putIfAbsent(it.captureToken, it.tapAction()) }
        trimOldTapActions(notification.key, notification.captureToken)
        if (previous == null) claimedActions.remove(notification.captureToken)
        return previous
    }

    fun rollbackPut(notification: LockedNotification, previous: LockedNotification?) {
        if (!notifications.remove(notification.key, notification)) return
        tapActions.remove(notification.captureToken)
        if (previous != null) {
            notifications[previous.key] = previous
            tapActions[previous.captureToken] = previous.tapAction()
        }
    }

    operator fun get(key: String): LockedNotification? = notifications[key]

    fun remove(key: String) {
        val removed = notifications.remove(key)
        if (removed != null) {
            tapActions.remove(removed.captureToken)
            claimedActions.remove(removed.captureToken)
        }
        tapActions.entries.removeIf { entry ->
            val action = entry.value
            if (action.notificationKey == key) {
                claimedActions.remove(action.captureToken)
                true
            } else {
                false
            }
        }
    }

    fun discardPackage(context: Context, packageName: String) {
        for (notification in forPackage(packageName)) {
            remove(notification.key)
            LockedNotificationPublisher.cancel(context, notification.key, notification.originalId)
        }
    }

    fun all(): List<LockedNotification> = notifications.values.toList()

    fun tapAction(notificationKey: String, captureToken: String): NotificationTapAction? =
        tapActions[captureToken]?.takeIf { it.notificationKey == notificationKey }

    fun forPackage(packageName: String): List<LockedNotification> =
        notifications.values
            .filter { it.packageName == packageName }
            .sortedBy { it.postedAtMillis }

    @Synchronized
    fun claimTapAction(action: NotificationTapAction): Boolean {
        if (tapActions[action.captureToken] !== action) return false
        return claimedActions.add(action.captureToken)
    }

    fun finishTapAction(action: NotificationTapAction) {
        val stillRedacted = notifications[action.notificationKey]?.captureToken == action.captureToken
        if (stillRedacted) {
            claimedActions.remove(action.captureToken)
        } else {
            tapActions.remove(action.captureToken, action)
            claimedActions.remove(action.captureToken)
        }
    }

    fun restoreNotificationsForPackage(
        context: Context,
        packageName: String,
        tappedNotificationKey: String?,
        tappedCaptureToken: String? = null,
    ): PackageRestoreResult {
        val tapped = tappedNotificationKey
            ?.let(notifications::get)
            ?.takeIf { it.packageName == packageName }
        var restoredCount = 0
        var failedCount = 0

        for (notification in forPackage(packageName)) {
            try {
                LockedNotificationPublisher.postRestored(context, notification)
                if (notifications.remove(notification.key, notification)) {
                    tapActions.entries.removeIf { entry ->
                        val action = entry.value
                        val keepTappedAction =
                            notification.key == tappedNotificationKey &&
                                action.captureToken == tappedCaptureToken
                        if (action.notificationKey == notification.key && !keepTappedAction) {
                            claimedActions.remove(action.captureToken)
                            true
                        } else {
                            false
                        }
                    }
                    restoredCount++
                }
            } catch (failure: SecurityException) {
                Log.e(TAG, "Unable to restore notification for package $packageName", failure)
                failedCount++
            } catch (failure: IllegalArgumentException) {
                Log.e(TAG, "Unable to restore notification for package $packageName", failure)
                failedCount++
            }
        }
        return PackageRestoreResult(tapped, restoredCount, failedCount)
    }

    private fun trimOldTapActions(notificationKey: String, currentToken: String) {
        val older = tapActions.values
            .filter { it.notificationKey == notificationKey && it.captureToken != currentToken }
            .sortedByDescending { it.capturedAtMillis }
        older.drop(MAX_OLD_ACTIONS).forEach { action ->
            tapActions.remove(action.captureToken, action)
            claimedActions.remove(action.captureToken)
        }
    }

    private fun LockedNotification.tapAction() = NotificationTapAction(
        notificationKey = key,
        captureToken = captureToken,
        packageName = packageName,
        contentIntent = contentIntent,
        capturedAtMillis = postedAtMillis,
    )

    private companion object {
        const val TAG = "QuickLockNotifications"
        const val MAX_OLD_ACTIONS = 8
    }
}

object LockedNotificationPublisher {
    const val CHANNEL_ID = "locked_app_notifications"
    const val TAG_PREFIX = "quicklock_locked:"
    private const val CHANNEL_NAME = "Locked app notifications"

    fun canPost(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
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
            .setData(
                Uri.parse(
                    "quicklock://locked-notification/${Uri.encode(notification.key)}/${notification.captureToken}",
                ),
            )
            .putExtra(NotificationUnlockActivity.EXTRA_PACKAGE_NAME, notification.packageName)
            .putExtra(NotificationUnlockActivity.EXTRA_NOTIFICATION_KEY, notification.key)
            .putExtra(NotificationUnlockActivity.EXTRA_CAPTURE_TOKEN, notification.captureToken)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.key.hashCode(),
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = NotificationManagerCompat.from(context)
        try {
            manager.notify(
                tag(notification.key),
                notification.originalId,
                buildLocked(
                    context,
                    notification,
                    notification.sourceAppIcon ?: notification.smallIcon,
                    pendingIntent,
                ),
            )
        } catch (failure: IllegalArgumentException) {
            if (notification.sourceAppIcon == null && notification.smallIcon == null) throw failure
            manager.notify(
                tag(notification.key),
                notification.originalId,
                buildLocked(context, notification, notification.smallIcon, pendingIntent),
            )
        }
    }

    private fun buildLocked(
        context: Context,
        notification: LockedNotification,
        smallIcon: IconCompat?,
        pendingIntent: PendingIntent,
    ): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(smallIcon ?: fallbackIcon(context))
        .setContentTitle(notification.appLabel)
        .setContentText("Unlock to view this notification")
        .setLargeIcon(notification.sourceAppLargeIcon)
        .setContentIntent(pendingIntent)
        .addAction(R.drawable.ic_notification_lock, "Unlock to view", pendingIntent)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion(context, notification.appLabel, smallIcon))
        .setWhen(notification.postedAtMillis)
        .setNumber(notification.number)
        .applyOriginalGroup(notification)
        .setOnlyAlertOnce(true)
        .setAutoCancel(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    fun postRestored(context: Context, notification: LockedNotification) {
        val manager = NotificationManagerCompat.from(context)
        try {
            manager.notify(
                tag(notification.key),
                notification.originalId,
                buildRestored(context, notification, notification.smallIcon),
            )
        } catch (failure: IllegalArgumentException) {
            if (notification.smallIcon == null) throw failure
            manager.notify(
                tag(notification.key),
                notification.originalId,
                buildRestored(context, notification, null),
            )
        }
    }

    private fun buildRestored(
        context: Context,
        notification: LockedNotification,
        smallIcon: IconCompat?,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon ?: fallbackIcon(context))
            .setContentTitle(notification.title.ifBlank { notification.appLabel })
            .setContentIntent(notification.contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, notification.appLabel, smallIcon))
            .setWhen(notification.postedAtMillis)
            .setNumber(notification.number)
            .setCategory(notification.category ?: NotificationCompat.CATEGORY_MESSAGE)
            .applyOriginalGroup(notification)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        notification.text?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        return builder.build()
    }

    fun cancel(context: Context, key: String, notificationId: Int = key.hashCode()) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(tag(key), notificationId)
        if (notificationId != key.hashCode()) manager.cancel(tag(key), key.hashCode())
    }

    fun keyFromTag(tag: String?): String? =
        tag?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)

    private fun tag(key: String) = TAG_PREFIX + key

    private fun fallbackIcon(context: Context) =
        IconCompat.createWithResource(context, R.drawable.ic_notification_lock)

    private fun NotificationCompat.Builder.applyOriginalGroup(notification: LockedNotification) = apply {
        notification.groupKey?.let { originalGroup ->
            setGroup("quicklock:${notification.packageName}:$originalGroup")
            setGroupSummary(notification.isGroupSummary)
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        }
    }

    private fun publicVersion(context: Context, appLabel: String, smallIcon: IconCompat?): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon ?: fallbackIcon(context))
            .setContentTitle(appLabel)
            .setContentText("Unlock to view this notification")
            .build()
}
