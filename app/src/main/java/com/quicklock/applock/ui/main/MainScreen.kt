package com.quicklock.applock.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quicklock.applock.data.SettingsStore
import com.quicklock.applock.util.AppEntry
import com.quicklock.applock.util.AppIcon
import com.quicklock.applock.util.Health

@Composable
fun MainScreen(
    vm: MainViewModel,
    health: Health,
    onEnableAccessibility: () -> Unit,
    onFixBattery: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    label = { Text("Apps") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (tab == 0) {
                AppsTab(vm, health, onFix = { tab = 1 })
            } else {
                SettingsTab(vm, health, onEnableAccessibility, onFixBattery, onOpenAppInfo)
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- Apps tab

@Composable
private fun AppsTab(vm: MainViewModel, health: Health, onFix: () -> Unit) {
    val all by vm.apps.collectAsState()
    val apps by vm.filtered.collectAsState()
    val locked by vm.settings.lockedApps.collectAsState()
    val query by vm.query.collectAsState()
    val protectedCount = all.count { it.packageName in locked }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "title") {
            Column(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                Text("App Lock", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (protectedCount == 0) "No apps protected yet" else "$protectedCount protected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!health.allGood) {
            item(key = "banner") {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onFix),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Protection isn't fully active", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Tap to finish setup in Settings.", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        item(key = "search") {
            TextField(
                value = query,
                onValueChange = { vm.query.value = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        }
        items(apps, key = { it.packageName }) { app ->
            AppRow(app, checked = app.packageName in locked) { vm.settings.setLocked(app.packageName, it) }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName, 44.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                app.label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

// ------------------------------------------------------------------------------------- Settings tab

@Composable
private fun SettingsTab(
    vm: MainViewModel,
    health: Health,
    onEnableAccessibility: () -> Unit,
    onFixBattery: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val settings = vm.settings
    val relock by settings.relockSecondsFlow.collectAsState()
    val biometricOn by settings.biometricEnabled.collectAsState()
    val protectSystem by settings.protectSystem.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val relockLabel = SettingsStore.RELOCK_OPTIONS.firstOrNull { it.first == relock }?.second ?: "Immediately after leaving the app"

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        item {
            Card("Protection status") {
                StatusRow(
                    title = "Accessibility service",
                    detail = if (health.accessibilityEnabled) "Running" else "Off. Required to detect when a protected app opens.",
                    ok = health.accessibilityEnabled,
                    actionLabel = if (health.accessibilityEnabled) null else "Enable",
                    onAction = onEnableAccessibility,
                )
                StatusRow(
                    title = "Unlock method",
                    detail = if (health.credentialSet) "PIN or pattern set" else "Not set",
                    ok = health.credentialSet,
                )
                StatusRow(
                    title = "Battery: Unrestricted",
                    detail = if (health.batteryUnrestricted) "Samsung won't put the service to sleep" else "Samsung may stop the service in the background",
                    ok = health.batteryUnrestricted,
                    actionLabel = if (health.batteryUnrestricted) null else "Fix",
                    onAction = onFixBattery,
                )
                if (!health.allGood) {
                    Text(
                        "One UI tips: Settings > Battery > Background usage limits: turn off \"Put unused apps to sleep\" and add QuickLock to \"Never sleeping apps\". " +
                            "If the accessibility switch is greyed out after sideloading, open App info > \u22EE > \"Allow restricted settings\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    TextButton(onClick = onOpenAppInfo, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Open app info") }
                }
            }
        }

        item {
            Card("Locking") {
                Box {
                    ClickRow("Relock", relockLabel) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        SettingsStore.RELOCK_OPTIONS.forEach { (seconds, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { settings.setRelockSeconds(seconds); menuOpen = false },
                            )
                        }
                    }
                }
                SwitchRow(
                    title = "Unlock with fingerprint",
                    subtitle = if (health.biometricAvailable) "Uses the system fingerprint prompt" else "No strong biometrics enrolled on this phone",
                    checked = biometricOn && health.biometricAvailable,
                    enabled = health.biometricAvailable,
                    onChange = settings::setBiometricEnabled,
                )
                SwitchRow(
                    title = "Protect Settings & uninstall",
                    subtitle = "Asks to unlock before system Settings or an uninstall dialog opens, so the lock can't be casually switched off",
                    checked = protectSystem,
                    enabled = true,
                    onChange = settings::setProtectSystem,
                )
            }
        }

        item {
            Card("Security") {
                ClickRow("Change PIN or pattern", "You'll be asked to set a new one") { vm.changingCredential = true }
            }
        }

        item {
            Card("Privacy") {
                Text(
                    "QuickLock has no internet permission, no ads, no analytics and collects no personal information. " +
                        "The accessibility service only sees which app is in front, never your screen content. " +
                        "Your PIN or pattern is stored as a keystore-bound hash, never in plain text.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
private fun StatusRow(title: String, detail: String, ok: Boolean, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ClickRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
