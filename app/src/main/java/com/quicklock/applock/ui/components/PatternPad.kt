package com.quicklock.applock.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 3x3 pattern pad. Nodes are indexed 0..8 (row-major). [onComplete] receives the ordered node list when
 * the finger lifts; the pad clears itself shortly afterwards.
 */
@Composable
fun PatternPad(
    enabled: Boolean,
    isError: Boolean,
    onComplete: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    padSize: Dp = 280.dp,
) {
    var path by remember { mutableStateOf(emptyList<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var clearJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val latestOnComplete by rememberUpdatedState(onComplete)
    val latestEnabled by rememberUpdatedState(enabled)

    val activeColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Canvas(
        modifier = modifier
            .size(padSize)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!latestEnabled) return@awaitEachGesture
                    clearJob?.cancel()
                    val cell = this@pointerInput.size.width / 3f
                    var points = emptyList<Int>()

                    fun hit(pos: Offset) {
                        val col = (pos.x / cell).toInt()
                        val row = (pos.y / cell).toInt()
                        if (col !in 0..2 || row !in 0..2) return
                        val idx = row * 3 + col
                        val cx = (col + 0.5f) * cell
                        val cy = (row + 0.5f) * cell
                        if (idx !in points && hypot(pos.x - cx, pos.y - cy) < cell * 0.3f) {
                            points = points + idx
                            path = points
                        }
                    }

                    path = emptyList()
                    hit(down.position)
                    finger = down.position
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        hit(change.position)
                        finger = change.position
                        change.consume()
                    } while (event.changes.any { it.pressed })

                    finger = null
                    if (points.isNotEmpty()) {
                        latestOnComplete(points)
                        clearJob = scope.launch {
                            delay(450)
                            path = emptyList()
                        }
                    }
                }
            },
    ) {
        val cell = this.size.width / 3f
        fun center(i: Int) = Offset((i % 3 + 0.5f) * cell, (i / 3 + 0.5f) * cell)
        val stroke = 6.dp.toPx()
        val selected = path
        val tip = finger

        for (k in 0 until selected.size - 1) {
            drawLine(activeColor, center(selected[k]), center(selected[k + 1]), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        if (tip != null && selected.isNotEmpty()) {
            drawLine(activeColor.copy(alpha = 0.6f), center(selected.last()), tip, strokeWidth = stroke, cap = StrokeCap.Round)
        }
        for (i in 0..8) {
            val isSelected = i in selected
            if (isSelected) drawCircle(activeColor.copy(alpha = 0.18f), radius = 28.dp.toPx(), center = center(i))
            drawCircle(
                color = if (isSelected) activeColor else idleColor,
                radius = if (isSelected) 12.dp.toPx() else 8.dp.toPx(),
                center = center(i),
            )
        }
    }
}
