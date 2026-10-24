package com.quicklock.applock.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quicklock.applock.security.CredentialType
import com.quicklock.applock.ui.components.MIN_PATTERN_NODES
import com.quicklock.applock.ui.components.PatternPad
import com.quicklock.applock.ui.components.PinDots
import com.quicklock.applock.ui.components.PinPad

private const val MAX_PIN = 8
private const val MIN_PIN = 4

/** First-run (and "change PIN/pattern") flow: choose type, enter, confirm. */
@Composable
fun SetupCredentialScreen(
    canCancel: Boolean,
    onCancel: () -> Unit,
    onCreated: (CredentialType, String) -> Unit,
) {
    var type by remember { mutableStateOf<CredentialType?>(null) }
    var first by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (type) {
            null -> {
                Text("Choose how to unlock", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "You'll use this to open protected apps and QuickLock itself. It's stored only on this phone, protected by the hardware keystore.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Button(onClick = { type = CredentialType.PIN }, modifier = Modifier.fillMaxWidth()) { Text("PIN") }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = { type = CredentialType.PATTERN }, modifier = Modifier.fillMaxWidth()) { Text("Pattern") }
            }

            CredentialType.PIN -> {
                val confirming = first != null
                Text(if (confirming) "Confirm PIN" else "Create PIN", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    message ?: "Use $MIN_PIN to $MAX_PIN digits",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                PinDots(slots = maxOf(MIN_PIN, entered.length), filled = entered.length, isError = false)
                Spacer(Modifier.height(24.dp))
                PinPad(
                    enabled = true,
                    onDigit = { if (entered.length < MAX_PIN) entered += it },
                    onDelete = { entered = entered.dropLast(1) },
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    enabled = entered.length >= MIN_PIN,
                    onClick = {
                        val firstEntry = first
                        when {
                            firstEntry == null -> { first = entered; entered = ""; message = null }
                            firstEntry == entered -> onCreated(CredentialType.PIN, entered)
                            else -> { message = "PINs didn't match. Start again."; first = null; entered = "" }
                        }
                    },
                ) { Text(if (confirming) "Confirm" else "Continue") }
            }

            CredentialType.PATTERN -> {
                Text(if (first != null) "Confirm pattern" else "Draw a pattern", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    message ?: "Connect at least $MIN_PATTERN_NODES dots",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                PatternPad(
                    enabled = true,
                    isError = false,
                    onComplete = { nodes ->
                        if (nodes.size < MIN_PATTERN_NODES) {
                            message = "Connect at least $MIN_PATTERN_NODES dots"
                        } else {
                            val secret = nodes.joinToString("")
                            val firstEntry = first
                            when {
                                firstEntry == null -> { first = secret; message = null }
                                firstEntry == secret -> onCreated(CredentialType.PATTERN, secret)
                                else -> { first = null; message = "Patterns didn't match. Try again." }
                            }
                        }
                    },
                )
            }
        }

        if (type != null || canCancel) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                if (type != null) {
                    type = null; first = null; entered = ""; message = null
                } else {
                    onCancel()
                }
            }) { Text(if (type != null) "Back" else "Cancel") }
        }
    }
}
