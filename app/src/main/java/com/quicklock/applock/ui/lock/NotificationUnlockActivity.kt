package com.quicklock.applock.ui.lock

import android.os.Bundle
import android.view.WindowManager
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
    private var error by mutableStateOf<String?>(null)
    private var authInProgress = false
    private var biometricUsable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY)
        biometricUsable = BiometricHelper.biometricAvailable(this)
        onBackPressedDispatcher.addCallback(this) { finish() }
        setContent { QuickLockTheme { Content() } }
        window.decorView.filterTouchesWhenObscured = true
    }

    override fun onResume() {
        super.onResume()
        val key = notificationKey ?: return
        if (!authInProgress && biometricUsable && quickLock.settings.biometricEnabled.value) {
            authenticateWithFingerprint(key)
        }
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
                restoreNotification(key)
            },
            onEnd = { authInProgress = false },
        )
    }

    private fun restoreNotification(key: String) {
        val notification = quickLock.lockedNotifications[key]
        if (notification == null) {
            error = "This notification is no longer available. QuickLock may have been restarted."
            return
        }
        try {
            LockedNotificationPublisher.postRestored(this, notification)
            quickLock.lockedNotifications.remove(key)
            finish()
        } catch (failure: SecurityException) {
            error = "QuickLock can't show notifications. Allow notifications for QuickLock in system settings, then try again."
        }
    }

    @Composable
    private fun Content() {
        val key = notificationKey
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
                if (appLabel != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(appLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                when {
                    key == null -> Text("This notification couldn't be identified.")
                    quickLock.lockedNotifications[key] == null ->
                        Text("This notification is no longer available. QuickLock may have been restarted.")
                    !quickLock.credentials.isSet -> Text("Set a QuickLock PIN or pattern before unlocking notifications.")
                    else -> AuthPanel(
                        credentials = quickLock.credentials,
                        onAuthenticated = { restoreNotification(key) },
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
    }
}
