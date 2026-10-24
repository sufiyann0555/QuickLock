package com.quicklock.applock.ui.lock

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.quicklock.applock.ui.components.AuthPanel
import com.quicklock.applock.ui.theme.QuickLockTheme
import com.quicklock.applock.util.AppIcon
import com.quicklock.applock.util.InstalledApps

/**
 * Full-screen lock shown over a protected app. Started only by [com.quicklock.applock.service.AppLockService].
 * Back = go Home (never reveal the protected app). Leaving this screen any other way also closes it, and the
 * service will show it again the next time the protected app appears.
 */
class LockActivity : FragmentActivity() {

    private var targetPkg by mutableStateOf<String?>(null)
    private var targetLabel by mutableStateOf("")
    private var authInProgress = false
    private var unlocked = false
    private var biometricUsable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide this screen from screenshots / recents thumbnails and ignore taps when another window overlays us.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        readTarget(intent)
        if (targetPkg == null) {
            finishAndRemoveTask()
            return
        }
        biometricUsable = BiometricHelper.biometricAvailable(this)
        onBackPressedDispatcher.addCallback(this) { goHome() }
        setContent { QuickLockTheme { LockContent() } }
        window.decorView.filterTouchesWhenObscured = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readTarget(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!unlocked && biometricUsable && quickLock.settings.biometricEnabled.value) showFingerprint()
    }

    override fun onStop() {
        super.onStop()
        // User left the lock screen (home, recents, screen off) without unlocking: close it.
        if (!authInProgress && !unlocked) finishAndRemoveTask()
    }

    private fun readTarget(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        targetPkg = pkg
        targetLabel = pkg?.let { InstalledApps.labelOf(this, it) } ?: ""
    }

    private fun showFingerprint() {
        if (authInProgress) return
        authInProgress = true
        val secondary = if (quickLock.credentials.type == CredentialType.PATTERN) "Use pattern" else "Use PIN"
        BiometricHelper.authenticate(
            activity = this,
            title = "Unlock $targetLabel",
            subtitle = null,
            negativeText = secondary,
            onSuccess = { authInProgress = false; quickLock.credentials.onExternalSuccess(); onUnlocked() },
            onEnd = { authInProgress = false },
        )
    }

    private fun onUnlocked() {
        val pkg = targetPkg ?: return
        unlocked = true
        quickLock.engine.onUnlocked(pkg)
        finishAndRemoveTask() // returns to the protected app's task underneath
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finishAndRemoveTask()
    }

    @Composable
    private fun LockContent() {
        val pkg = targetPkg
        val biometricOn by quickLock.settings.biometricEnabled.collectAsState()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (pkg != null) {
                    AppIcon(pkg, 72.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(targetLabel, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                }
                Spacer(Modifier.height(28.dp))
                AuthPanel(
                    credentials = quickLock.credentials,
                    onAuthenticated = { onUnlocked() },
                    onFingerprint = if (biometricOn && biometricUsable) ({ showFingerprint() }) else null,
                )
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "target_package"
    }
}
