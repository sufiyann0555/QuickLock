package com.quicklock.applock

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.quicklock.applock.data.SettingsStore
import com.quicklock.applock.security.CredentialStore
import com.quicklock.applock.service.LockEngine

/**
 * Process-wide singletons. Everything is small and created synchronously (a couple of
 * SharedPreferences reads) so the accessibility service has correct state from its very first event.
 */
class QuickLockApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var credentials: CredentialStore
        private set
    lateinit var engine: LockEngine
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        credentials = CredentialStore(this)
        engine = LockEngine(settings) { SystemClock.elapsedRealtime() }
    }
}

val Context.quickLock: QuickLockApp get() = applicationContext as QuickLockApp
