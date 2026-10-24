package com.quicklock.applock.ui.main

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quicklock.applock.QuickLockApp
import com.quicklock.applock.data.SettingsStore
import com.quicklock.applock.util.AppEntry
import com.quicklock.applock.util.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QuickLockApp
    val settings: SettingsStore get() = app.settings

    /** Is QuickLock's own UI unlocked? Lives in the ViewModel so rotation doesn't re-prompt. */
    var authed by mutableStateOf(false)
    var credentialSet by mutableStateOf(app.credentials.isSet)
    var changingCredential by mutableStateOf(false)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    val query = MutableStateFlow("")

    val filtered: StateFlow<List<AppEntry>> = combine(_apps, query, settings.lockedApps) { list, q, locked ->
        val needle = q.trim()
        val ordered = list.sortedWith(
            compareByDescending<AppEntry> { it.packageName in locked }
                .thenBy { it.label.lowercase() },
        )
        if (needle.isEmpty()) ordered else ordered.filter { it.label.contains(needle, ignoreCase = true) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var loadedAt = 0L

    init {
        refreshApps(force = true)
    }

    fun refreshApps(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && _apps.value.isNotEmpty() && now - loadedAt < 60_000) return
        loadedAt = now
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { InstalledApps.load(app) }
        }
    }

    fun onAuthenticated() {
        app.credentials.onExternalSuccess()
        authed = true
    }
}
