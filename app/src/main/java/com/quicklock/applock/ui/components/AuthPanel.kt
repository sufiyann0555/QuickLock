package com.quicklock.applock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quicklock.applock.security.CredentialStore
import com.quicklock.applock.security.CredentialType
import com.quicklock.applock.security.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PIN / pattern entry + verification, shared by the lock screen and by QuickLock's own gate.
 * PINs auto-submit on the last digit (no confirm button) for the fastest possible unlock.
 * Verification runs off the main thread; escalating lockouts are enforced by [CredentialStore].
 */
@Composable
fun AuthPanel(
    credentials: CredentialStore,
    onAuthenticated: () -> Unit,
    onFingerprint: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val type = credentials.type
    if (type == null) return

    val pinLength = remember { credentials.pinLength }
    var entered by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lockTick by remember { mutableIntStateOf(0) }
    var remainingMs by remember { mutableLongStateOf(credentials.lockoutRemainingMs()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(lockTick) {
        while (true) {
            val r = credentials.lockoutRemainingMs()
            remainingMs = r
            if (r <= 0L) break
            delay(500)
        }
    }

    fun submit(secret: String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { credentials.verify(secret) }
            if (result is VerifyResult.Success) {
                onAuthenticated()
            } else {
                isError = true
                lockTick++
                delay(350)
                isError = false
                entered = ""
            }
            busy = false
        }
    }

    val inputEnabled = !busy && remainingMs <= 0L

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (remainingMs > 0L) {
            Text(
                "Too many attempts. Try again in ${(remainingMs + 999) / 1000}s",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                if (type == CredentialType.PIN) "Enter PIN" else "Draw your pattern",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(20.dp))

        when (type) {
            CredentialType.PIN -> {
                PinDots(slots = pinLength, filled = entered.length, isError = isError)
                Spacer(Modifier.height(28.dp))
                PinPad(
                    enabled = inputEnabled,
                    onDigit = { digit ->
                        if (entered.length < pinLength) {
                            entered += digit
                            if (entered.length == pinLength) submit(entered)
                        }
                    },
                    onDelete = { entered = entered.dropLast(1) },
                )
            }
            CredentialType.PATTERN -> {
                PatternPad(
                    enabled = inputEnabled,
                    isError = isError,
                    onComplete = { nodes ->
                        if (nodes.size >= MIN_PATTERN_NODES) {
                            submit(nodes.joinToString(""))
                        } else {
                            scope.launch {
                                isError = true
                                delay(350)
                                isError = false
                            }
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onFingerprint != null) {
                TextButton(onClick = onFingerprint) { Text("Use fingerprint") }
            }
        }
    }
}

const val MIN_PATTERN_NODES = 4
