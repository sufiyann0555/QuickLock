package com.quicklock.applock.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.quicklock.applock.data.SettingsStore
import com.quicklock.applock.quickLock
import com.quicklock.applock.security.BiometricHelper
import com.quicklock.applock.ui.components.AuthPanel
import com.quicklock.applock.ui.theme.QuickLockTheme
import com.quicklock.applock.util.SystemChecks

/**
 * QuickLock's own UI is protected by the same PIN / pattern / fingerprint, so nobody can browse or change the
 * protected-app list or settings. It relocks [GRACE_MS] after the app leaves the foreground.
 */
class MainActivity : FragmentActivity() {

    private val vm: MainViewModel by viewModels()
    private var resumeTick by mutableIntStateOf(0)
    private var stoppedAt = 0L
    private var authInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { QuickLockTheme { Root() } }
        window.decorView.filterTouchesWhenObscured = true
    }

    override fun onStart() {
        super.onStart()
        if (vm.authed && stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > GRACE_MS) vm.authed = false
    }

    override fun onResume() {
        super.onResume()
        resumeTick++ // re-evaluate accessibility / battery state after returning from system Settings
        vm.refreshApps()
        if (vm.credentialSet && !vm.authed && !vm.changingCredential) tryFingerprint()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    // ------------------------------------------------------------------------------------- UI

    @Composable
    private fun Root() {
        val health = remember(resumeTick, vm.credentialSet) { SystemChecks.health(this) }
        when {
            !vm.credentialSet || vm.changingCredential -> SetupCredentialScreen(
                canCancel = vm.credentialSet,
                onCancel = { vm.changingCredential = false },
                onCreated = { type, secret ->
                    quickLock.credentials.set(type, secret)
                    vm.credentialSet = true
                    vm.changingCredential = false
                    vm.authed = true
                },
            )

            !vm.authed -> Gate(health.biometricAvailable)

            else -> MainScreen(
                vm = vm,
                health = health,
                onEnableAccessibility = { openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onFixBattery = { openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                onOpenAppInfo = {
                    openSystemSettings(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                    )
                },
            )
        }
    }

    @Composable
    private fun Gate(biometricAvailable: Boolean) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("QuickLock", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(28.dp))
                AuthPanel(
                    credentials = quickLock.credentials,
                    onAuthenticated = { vm.onAuthenticated() },
                    onFingerprint = if (biometricAvailable && quickLock.settings.biometricEnabled.value) ({ tryFingerprint() }) else null,
                    onDeviceCredential = if (BiometricHelper.deviceCredentialAvailable(this@MainActivity)) ({ tryDeviceCredential() }) else null,
                )
            }
        }
    }

    // ------------------------------------------------------------------------------- helpers

    private fun tryFingerprint() {
        if (authInProgress || !quickLock.settings.biometricEnabled.value || !BiometricHelper.biometricAvailable(this)) return
        authInProgress = true
        BiometricHelper.authenticate(
            activity = this,
            title = "Unlock QuickLock",
            subtitle = null,
            useDeviceCredential = false,
            negativeText = "Use PIN or pattern",
            onSuccess = { authInProgress = false; vm.onAuthenticated() },
            onEnd = { authInProgress = false },
        )
    }

    private fun tryDeviceCredential() {
        if (authInProgress) return
        authInProgress = true
        BiometricHelper.authenticate(
            activity = this,
            title = "Unlock QuickLock",
            subtitle = "Use your phone screen lock",
            useDeviceCredential = true,
            negativeText = "",
            onSuccess = { authInProgress = false; vm.onAuthenticated() },
            onEnd = { authInProgress = false },
        )
    }

    /** The user already authenticated here, so don't make them do it again when we open system Settings. */
    private fun openSystemSettings(intent: Intent) {
        quickLock.engine.preAuthorize(SettingsStore.SETTINGS_PACKAGE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private companion object {
        const val GRACE_MS = 30_000L
    }
}
