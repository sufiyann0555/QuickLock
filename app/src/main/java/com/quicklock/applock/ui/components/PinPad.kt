package com.quicklock.applock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KeySize = 72.dp

@Composable
fun PinDots(slots: Int, filled: Int, isError: Boolean, modifier: Modifier = Modifier) {
    val on = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Row(
        modifier = modifier.height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(slots) { i ->
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < filled) on else off))
        }
    }
}

@Composable
fun PinPad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { c -> PadKey(c.toString(), enabled) { onDigit(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.size(KeySize))
            PadKey("0", enabled) { onDigit('0') }
            PadKey("\u232B", enabled, flat = true) { onDelete() }
        }
    }
}

@Composable
private fun PadKey(label: String, enabled: Boolean, flat: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(KeySize)
            .clip(CircleShape)
            .background(if (flat) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
