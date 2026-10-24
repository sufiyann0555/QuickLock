package com.quicklock.applock.util

import android.content.ComponentName
import android.content.Context
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import com.quicklock.applock.quickLock
import com.quicklock.applock.security.BiometricHelper
import com.quicklock.applock.service.AppLockService
import com.quicklock.applock.service.LockedAppNotificationListener

data class Health(
    val accessibilityEnabled: Boolean,
    val notificationAccessEnabled: Boolean,
    val credentialSet: Boolean,
    val batteryUnrestricted: Boolean,
    val biometricAvailable: Boolean,
) {
    /** Everything required for reliable protection. */
    val allGood: Boolean get() = accessibilityEnabled && notificationAccessEnabled && credentialSet && batteryUnrestricted
}

object SystemChecks {
    fun health(context: Context) = Health(
        accessibilityEnabled = isAccessibilityEnabled(context),
        notificationAccessEnabled = isNotificationAccessEnabled(context),
        credentialSet = context.quickLock.credentials.isSet,
        batteryUnrestricted = isBatteryUnrestricted(context),
        biometricAvailable = BiometricHelper.biometricAvailable(context),
    )

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(context, AppLockService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (ComponentName.unflattenFromString(entry) == me) return true
        }
        return false
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isNotificationAccessEnabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, LockedAppNotificationListener::class.java))
}
