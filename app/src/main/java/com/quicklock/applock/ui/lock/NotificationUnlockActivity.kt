package com.quicklock.applock.ui.lock

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.quicklock.applock.quickLock
import com.quicklock.applock.security.BiometricHelper
import com.quicklock.applock.security.CredentialType
import com.quicklock.applock.service.LockedNotificationPublisher
import com.quicklock.applock.ui.components.AuthPanel
import com.quicklock.applock.ui.theme.QuickLockTheme

class NotificationUnlockActivity : FragmentActivity() {

    private var notificationKey by mutableStateOf<String?>(null)
    private var captureToken by mutableStateOf<String?>(null)
    private var targetPackage by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    private var authInProgress = false
    private var authenticationHandled = false
    private var biometricUsable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY)
        captureToken = intent.getStringExtra(EXTRA_CAPTURE_TOKEN)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        error = savedInstanceState?.getString(STATE_ERROR)
        authenticationHandled = savedInstanceState?.getBoolean(STATE_AUTHENTICATION_HANDLED) ?: false
        biometricUsable = BiometricHelper.biometricAvailable(this)
        onBackPressedDispatcher.addCallback(this) { finish() }
        setContent { QuickLockTheme { Content() } }
        window.decorView.filterTouchesWhenObscured = true
    }

    override fun onResume() {
        super.onResume()
        val key = notificationKey ?: return
        if (!authenticationHandled && !authInProgress && biometricUsable && quickLock.settings.biometricEnabled.value) {
            authenticateWithFingerprint(key)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_ERROR, error)
        outState.putBoolean(STATE_AUTHENTICATION_HANDLED, authenticationHandled)
        super.onSaveInstanceState(outState)
    }

    private fun authenticateWithFingerprint(key: String) {
        if (authInProgress) return
        authInProgress = true
        BiometricHelper.authenticate(
            activity = this,
            title = "Unlock to view notification",
            subtitle = quickLock.lockedNotifications[key]?.appLabel,
            negativeText = if (quickLock.credentials.type == CredentialType.PATTERN) "Use pattern" else "Use PIN",
            onSuccess = {
                authInProgress = false
                quickLock.credentials.onExternalSuccess()
                onAuthenticated()
            },
            onEnd = { authInProgress = false },
        )
    }

    private fun onAuthenticated() {
        if (authenticationHandled) return
        authenticationHandled = true

        val key = notificationKey
        val token = captureToken
        val pkg = targetPackage
        if (key == null || token == null || pkg == null) {
            error = "This notification couldn't be identified. No app was opened."
            return
        }
        if (pkg !in quickLock.settings.lockedApps.value) {
            error = "This app is no longer protected. Reopen its notification from the drawer."
            return
        }
        try {
            packageManager.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            quickLock.lockedNotifications.discardPackage(this, pkg)
            error = "The source app is no longer installed. Its saved notifications were discarded."
            return
        }

        val notification = quickLock.lockedNotifications[key]
            ?.takeIf { it.packageName == pkg }
        if (notification == null) {
            error = "This notification is no longer available. Its contents and destination were not restored."
            return
        }

        val tapAction = quickLock.lockedNotifications.tapAction(key, token)
            ?.takeIf { it.packageName == pkg }
        if (tapAction == null) {
            error = "This notification action is no longer available. No app was opened."
            return
        }
        if (!quickLock.lockedNotifications.claimTapAction(tapAction)) {
            error = "This notification has already been opened or is no longer available."
            return
        }

        val result = quickLock.lockedNotifications.restoreNotificationsForPackage(this, pkg, key, token)
        if (result.tappedNotification == null) {
            quickLock.lockedNotifications.finishTapAction(tapAction)
            error = "This notification is no longer available. Its destination was not opened."
            return
        }
        if (result.failedCount > 0) {
            Log.w(TAG, "Could not restore ${result.failedCount} notification(s) for package $pkg")
            Toast.makeText(
                this,
                "Some ${notification.appLabel} notifications couldn't be restored.",
                Toast.LENGTH_LONG,
            ).show()
        }

        val contentIntent = tapAction.contentIntent
        if (contentIntent == null) {
            quickLock.lockedNotifications.finishTapAction(tapAction)
            error = "The notification was restored, but its original destination isn't available."
            return
        }

        quickLock.engine.preAuthorize(pkg)
        try {
            contentIntent.send()
            finish()
        } catch (failure: PendingIntent.CanceledException) {
            quickLock.engine.revokePreAuthorization(pkg)
            Log.w(TAG, "Original notification action is no longer available for package $pkg")
            error = "The notification was restored, but its original destination is no longer available."
        } catch (failure: SecurityException) {
            quickLock.engine.revokePreAuthorization(pkg)
            Log.w(TAG, "Android denied the original notification action for package $pkg", failure)
            error = "The notification was restored, but Android couldn't open its original destination."
        } catch (failure: ActivityNotFoundException) {
            quickLock.engine.revokePreAuthorization(pkg)
            Log.w(TAG, "No activity can handle the original notification action for package $pkg")
            error = "The notification was restored, but its destination app is unavailable."
        } finally {
            quickLock.lockedNotifications.finishTapAction(tapAction)
        }
    }

    @Composable
    private fun Content() {
        val key = notificationKey
        val pkg = targetPackage
        val appLabel = key?.let { quickLock.lockedNotifications[it]?.appLabel }
        val biometricOn by quickLock.settings.biometricEnabled.collectAsState()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Unlock to view", style = MaterialTheme.typography.headlineSmall)
                if (appLabel != null && pkg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(appLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                when {
                    authenticationHandled -> Text("Authentication complete.")
                    key == null -> Text("This notification couldn't be identified.")
                    pkg == null -> Text("The source app couldn't be identified.")
                    quickLock.lockedNotifications[key] == null ->
                        Text("This notification is no longer available. QuickLock may have been restarted.")
                    !quickLock.credentials.isSet -> Text("Set a QuickLock PIN or pattern before unlocking notifications.")
                    else -> AuthPanel(
                        credentials = quickLock.credentials,
                        onAuthenticated = { onAuthenticated() },
                        onFingerprint = if (biometricOn && biometricUsable) {
                            { authenticateWithFingerprint(key) }
                        } else {
                            null
                        },
                    )
                }
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    companion object {
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
        const val EXTRA_PACKAGE_NAME = "source_package"
        const val EXTRA_CAPTURE_TOKEN = "capture_token"
        private const val STATE_AUTHENTICATION_HANDLED = "authentication_handled"
        private const val STATE_ERROR = "unlock_error"
        private const val TAG = "QuickLockNotifications"
    }
}
