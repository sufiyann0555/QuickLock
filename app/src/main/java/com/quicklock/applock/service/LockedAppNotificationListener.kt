package com.quicklock.applock.service

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.IconCompat
import com.quicklock.applock.QuickLockApp
import com.quicklock.applock.service.LockedNotificationPublisher.TAG_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

/** Replaces notifications from protected apps with redacted, unlockable notifications. */
class LockedAppNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lockedAppsJob: Job? = null
    private var listenerConnected = false

    private val settings get() = (application as QuickLockApp).settings

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        LockedNotificationPublisher.createChannel(this)
        clearStalePlaceholders()
        lockedAppsJob?.cancel()
        lockedAppsJob = serviceScope.launch {
            settings.lockedApps.collect { lockedApps ->
                if (listenerConnected) reconcileNotifications(lockedApps)
            }
        }
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        lockedAppsJob?.cancel()
        lockedAppsJob = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.packageName !in settings.lockedApps.value) return
        if (LockedNotificationPublisher.canPost(this)) replaceWithRedacted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (sbn.packageName == packageName) {
            if (reason == REASON_CANCEL || reason == REASON_CANCEL_ALL) {
                LockedNotificationPublisher.keyFromTag(sbn.tag)?.let(settingsNotifications::remove)
            }
            return
        }
        if (reason == REASON_LISTENER_CANCEL) return

        settingsNotifications.remove(sbn.key)
        LockedNotificationPublisher.cancel(this, sbn.key, sbn.id)
    }

    override fun onDestroy() {
        listenerConnected = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private val settingsNotifications get() = (application as QuickLockApp).lockedNotifications

    private fun reconcileNotifications(lockedApps: Set<String>) {
        if (!LockedNotificationPublisher.canPost(this)) return
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter { it.packageName in lockedApps && it.packageName != packageName }
            .forEach(::replaceWithRedacted)
        settingsNotifications.all().asSequence()
            .map { it.packageName }
            .distinct()
            .filter { it !in lockedApps }
            .forEach { packageName ->
                settingsNotifications.restoreNotificationsForPackage(this, packageName, null)
            }
    }

    private fun replaceWithRedacted(sbn: StatusBarNotification) {
        val applicationInfo = try {
            packageManager.getApplicationInfo(sbn.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val appLabel = applicationInfo?.let {
            packageManager.getApplicationLabel(it).toString()
        } ?: sbn.packageName
        val appIcons = applicationInfo?.let {
            SourceAppNotificationIcons.load(this, sbn.packageName, it)
        }
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: appLabel
        val smallIcon = try {
            sbn.notification.smallIcon?.let { IconCompat.createFromIcon(this, it) }
        } catch (_: IllegalArgumentException) {
            null
        }
        val notification = LockedNotification(
            key = sbn.key,
            captureToken = UUID.randomUUID().toString(),
            packageName = sbn.packageName,
            originalId = sbn.id,
            originalTag = sbn.tag,
            appLabel = appLabel,
            smallIcon = smallIcon,
            sourceAppIcon = appIcons?.smallIcon,
            sourceAppLargeIcon = appIcons?.largeIcon,
            title = title,
            text = notificationText(extras),
            contentIntent = sbn.notification.contentIntent,
            postedAtMillis = sbn.postTime,
            groupKey = sbn.notification.group,
            isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            number = sbn.notification.number,
            category = sbn.notification.category,
        )
        val previous = settingsNotifications.put(notification)
        try {
            LockedNotificationPublisher.postLocked(this, notification)
        } catch (error: SecurityException) {
            settingsNotifications.rollbackPut(notification, previous)
            Log.e(TAG, "Unable to publish redacted notification", error)
            return
        } catch (error: IllegalArgumentException) {
            settingsNotifications.rollbackPut(notification, previous)
            Log.e(TAG, "Unable to publish redacted notification", error)
            return
        }
        cancelNotification(sbn.key)
    }

    private fun notificationText(extras: Bundle): String? {
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
        val messageLines = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.mapNotNull { message ->
                (message as? Bundle)?.getCharSequence("text")?.toString()?.takeIf(String::isNotBlank)
            }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf(String::isNotBlank)
            ?: textLines
            ?: messageLines
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.takeIf(String::isNotBlank)
    }

    private fun clearStalePlaceholders() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter { it.packageName == packageName && it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { sbn ->
                val key = LockedNotificationPublisher.keyFromTag(sbn.tag) ?: return@forEach
                if (settingsNotifications[key] == null) {
                    LockedNotificationPublisher.cancel(this, key, sbn.id)
                }
            }
    }

    private companion object {
        const val TAG = "QuickLockNotifications"
    }
}

private data class SourceAppIcons(
    val smallIcon: IconCompat?,
    val largeIcon: Bitmap?,
)

private object SourceAppNotificationIcons {
    private val cache = LruCache<String, SourceAppIcons>(16)

    @Synchronized
    fun load(context: LockedAppNotificationListener, packageName: String, appInfo: ApplicationInfo): SourceAppIcons {
        cache.get(packageName)?.let { return it }

        val smallIcon = if (appInfo.icon != 0) {
            try {
                IconCompat.createWithResource(
                    context.packageManager.getResourcesForApplication(appInfo),
                    packageName,
                    appInfo.icon,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: Resources.NotFoundException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }

        val largeIcon = try {
            context.packageManager.getApplicationIcon(appInfo).toBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Resources.NotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val icons = SourceAppIcons(smallIcon, largeIcon)
        cache.put(packageName, icons)
        return icons
    }

    private fun Drawable.toBitmap(): Bitmap {
        val maxDimension = 96
        val intrinsicWidth = intrinsicWidth.takeIf { it > 0 } ?: maxDimension
        val intrinsicHeight = intrinsicHeight.takeIf { it > 0 } ?: maxDimension
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(intrinsicWidth, intrinsicHeight))
        val width = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            setBounds(0, 0, width, height)
            draw(Canvas(bitmap))
        }
    }
}
