package com.quicklock.applock.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.quicklock.applock.QuickLockApp
import com.quicklock.applock.ui.lock.LockActivity

/**
 * Event-driven foreground detector. There is no polling, no timer, no wake lock and no foreground
 * service: the system wakes us only when a window changes, we do a few map lookups on the main thread
 * and go back to sleep. The service only listens to TYPE_WINDOW_STATE_CHANGED and cannot read the screen
 * (canRetrieveWindowContent=false).
 */
class AppLockService : AccessibilityService() {

    private val app: QuickLockApp get() = applicationContext as QuickLockApp

    private var imePackages: Set<String> = emptySet()
    private var imeRefreshedAt = 0L
    private var lastLaunchPkg: String? = null
    private var lastLaunchAt = 0L
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> app.engine.onScreenOff()
                // Keyguard dismissed: the previously foreground app is about to reappear, and the
                // system may not emit a window event for it. Re-check it explicitly.
                Intent.ACTION_USER_PRESENT -> app.engine.recheckCurrent()?.let { launchLock(it) }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        // Load the Keystore key off the main thread so the first unlock is instant.
        Thread { runCatching { app.credentials.warmUp() } }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Our own lock screen appearing must not count as "the user switched apps".
        if (pkg == packageName && event.className?.toString() == LockActivity::class.java.name) return
        if (pkg in ALWAYS_IGNORED || isInputMethod(pkg)) return
        if (!app.credentials.isSet) return // nothing to authenticate against yet: never trap the user

        if (app.engine.onForeground(pkg)) launchLock(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun launchLock(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastLaunchPkg && now - lastLaunchAt < 500) return // coalesce event bursts
        lastLaunchPkg = pkg
        lastLaunchAt = now
        val intent = Intent(this, LockActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            .putExtra(LockActivity.EXTRA_PACKAGE, pkg)
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            // Background start blocked by the OS: nothing more a normal app can do. See README.
        }
    }

    /** Keyboards emit window events too; they must not count as leaving the app. Cheap, throttled lookup. */
    private fun isInputMethod(pkg: String): Boolean {
        if (pkg in imePackages) return true
        val now = SystemClock.elapsedRealtime()
        if (now - imeRefreshedAt > 2_000) {
            imeRefreshedAt = now
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imePackages = imm.enabledInputMethodList.mapTo(HashSet()) { it.packageName }
        }
        return pkg in imePackages
    }

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        /**
         * System surfaces that overlay apps without the user actually leaving them. Add to this list if a
         * particular overlay (e.g. a Samsung edge panel) causes unwanted relocks on your device.
         */
        private val ALWAYS_IGNORED = setOf(
            "com.android.systemui",                       // shade, volume, keyguard, recents gestures
            "android",                                    // system dialogs, older share sheets
            "com.android.intentresolver",                 // share sheet (Android 14+)
            "com.google.android.permissioncontroller",    // permission dialogs
            "com.android.permissioncontroller",
            "com.samsung.android.app.cocktailbarservice", // One UI edge panel
            "com.samsung.android.app.taskedge",
        )
    }
}
