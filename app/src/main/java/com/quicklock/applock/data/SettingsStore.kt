package com.quicklock.applock.data

import android.content.Context
import com.quicklock.applock.service.LockPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-sensitive settings. SharedPreferences is used deliberately instead of DataStore: reads are
 * synchronous and cached in memory, which is what the accessibility service needs (no suspend, no I/O
 * on the hot path). All mutation happens on the main thread.
 */
class SettingsStore(context: Context) : LockPolicy {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _lockedApps = MutableStateFlow(prefs.getStringSet(KEY_LOCKED, emptySet()).orEmpty().toSet())
    val lockedApps: StateFlow<Set<String>> = _lockedApps.asStateFlow()

    private val _relockSeconds = MutableStateFlow(prefs.getInt(KEY_RELOCK, RELOCK_IMMEDIATELY))
    val relockSecondsFlow: StateFlow<Int> = _relockSeconds.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(prefs.getBoolean(KEY_BIOMETRIC, true))
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _protectSystem = MutableStateFlow(prefs.getBoolean(KEY_PROTECT_SYSTEM, true))
    val protectSystem: StateFlow<Boolean> = _protectSystem.asStateFlow()

    // ---- LockPolicy (hot path, called from the accessibility service) ----
    override fun isLocked(pkg: String): Boolean =
        pkg in _lockedApps.value || (_protectSystem.value && pkg in PROTECTED_SYSTEM_PACKAGES)

    override fun relockSeconds(): Int = _relockSeconds.value

    // ---- Mutators ----
    fun setLocked(pkg: String, locked: Boolean) {
        val updated = _lockedApps.value.toMutableSet().apply { if (locked) add(pkg) else remove(pkg) }
        _lockedApps.value = updated
        prefs.edit().putStringSet(KEY_LOCKED, updated).apply()
    }

    fun setRelockSeconds(seconds: Int) {
        _relockSeconds.value = seconds
        prefs.edit().putInt(KEY_RELOCK, seconds).apply()
    }

    fun setBiometricEnabled(enabled: Boolean) {
        _biometricEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    fun setProtectSystem(enabled: Boolean) {
        _protectSystem.value = enabled
        prefs.edit().putBoolean(KEY_PROTECT_SYSTEM, enabled).apply()
    }

    companion object {
        private const val KEY_LOCKED = "locked_apps"
        private const val KEY_RELOCK = "relock_seconds"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_PROTECT_SYSTEM = "protect_system"

        const val RELOCK_IMMEDIATELY = 0
        const val RELOCK_ON_SCREEN_OFF = -1

        val RELOCK_OPTIONS: List<Pair<Int, String>> = listOf(
            RELOCK_IMMEDIATELY to "Immediately after leaving the app",
            15 to "After 15 seconds",
            30 to "After 30 seconds",
            60 to "After 1 minute",
            300 to "After 5 minutes",
            RELOCK_ON_SCREEN_OFF to "When the screen turns off",
        )

        /**
         * "Protect Settings & uninstall": Settings (where the accessibility service can be switched off,
         * the app force-stopped or uninstalled) and the package-installer confirmation dialogs.
         */
        const val SETTINGS_PACKAGE = "com.android.settings"
        val PROTECTED_SYSTEM_PACKAGES: Set<String> = setOf(
            SETTINGS_PACKAGE,
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
        )
    }
}
