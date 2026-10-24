package com.quicklock.applock.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.quicklock.applock.QuickLockApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Removes notifications from apps explicitly selected in QuickLock's protected-app list. */
class LockedAppNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lockedAppsJob: Job? = null
    private var listenerConnected = false

    private val settings get() = (application as QuickLockApp).settings

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        lockedAppsJob?.cancel()
        lockedAppsJob = serviceScope.launch {
            settings.lockedApps.collect { lockedApps ->
                if (listenerConnected) cancelNotificationsFrom(lockedApps)
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
        if (sbn.packageName in settings.lockedApps.value) {
            cancelNotification(sbn.key)
        }
    }

    override fun onDestroy() {
        listenerConnected = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun cancelNotificationsFrom(lockedApps: Set<String>) {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter { it.packageName in lockedApps }.forEach { cancelNotification(it.key) }
    }
}
