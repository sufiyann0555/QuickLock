package com.quicklock.applock.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import com.quicklock.applock.QuickLockApp
import com.quicklock.applock.service.LockedNotificationPublisher.TAG_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
        LockedNotificationPublisher.cancel(this, sbn.key)
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
        settingsNotifications.all().forEach { notification ->
            if (notification.packageName !in lockedApps) restore(notification)
        }
    }

    private fun replaceWithRedacted(sbn: StatusBarNotification) {
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() } ?: appLabel
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        val notification = LockedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            contentIntent = sbn.notification.contentIntent,
        )
        settingsNotifications.put(notification)
        try {
            LockedNotificationPublisher.postLocked(this, notification)
        } catch (error: SecurityException) {
            settingsNotifications.remove(sbn.key)
            Log.e(TAG, "Unable to publish redacted notification", error)
            return
        }
        cancelNotification(sbn.key)
    }

    private fun restore(notification: LockedNotification) {
        try {
            LockedNotificationPublisher.postRestored(this, notification)
            settingsNotifications.remove(notification.key)
        } catch (error: SecurityException) {
            Log.e(TAG, "Unable to restore notification", error)
        }
    }

    private fun clearStalePlaceholders() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter { it.packageName == packageName && it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { sbn ->
                val key = LockedNotificationPublisher.keyFromTag(sbn.tag) ?: return@forEach
                if (settingsNotifications[key] == null) LockedNotificationPublisher.cancel(this, key)
            }
    }

    private companion object {
        const val TAG = "QuickLockNotifications"
    }
}
