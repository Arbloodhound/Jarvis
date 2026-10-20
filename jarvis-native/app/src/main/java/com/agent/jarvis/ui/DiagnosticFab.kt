package com.agent.jarvis.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.jarvis.diag.LiveDiagnostic
import kotlin.math.roundToInt

/**
 * The floating diagnostic button drawn over every app screen (MainActivity). Tap to run the live
 * diagnostic: it glows green with a countdown while it captures the real app for 15 minutes
 * ([LiveDiagnostic]), tap again to stop early. When it finishes it shows two separate buttons:
 * SEND (share sheet) and RESET (back to DIAG for a new capture), so neither is hit by accident
 * (owner, 2026-10-20: it used to stay on SEND with no way to start over).
 * Draggable so it never sits on top of what the owner needs.
 */
@Composable
fun DiagnosticFab(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val running by LiveDiagnostic.running
    val remaining by LiveDiagnostic.remainingSec
    val file by LiveDiagnostic.lastFile

    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }

    // The green pulse while capturing.
    val pulse = rememberInfiniteTransition(label = "diag")
    val glow by pulse.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "glow",
    )
    val green = Color(0xFF22C55E)
    val accent = Color(0xFF22D3EE)
    val bg by animateColorAsState(
        if (running) green.copy(alpha = glow) else Color(0xFF111417), label = "bg",
    )

    Box(
        modifier = modifier
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .diagGestures(
                // In the finished state the surface is drag-only: SEND/RESET own their taps, and
                // a stray press between them must not start a new capture over the unsent file.
                tapEnabled = running || file == null,
                onTap = { LiveDiagnostic.toggle(ctx) },
                onDrag = { mx, my -> dx += mx; dy += my },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val done = file
        if (!running && done != null) {
            // Finished: SEND and RESET as two buttons, apart, so one is never hit for the other.
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Chip("SEND", green) { LiveDiagnostic.share(ctx, done) }
                Chip("RESET", Color(0xFFF59E0B)) { LiveDiagnostic.lastFile.value = null }
            }
            return@Box
        }
        Box(
            modifier = Modifier
                .size(if (running) 84.dp else 60.dp)
                .background(bg, CircleShape)
                .border(2.dp, if (running) green else accent.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    running -> {
                        Text("REC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(mmss(remaining), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                    }
                    else -> Text("DIAG", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Tap + drag for the whole button in ONE gesture detector.
 *
 * The old arrangement — detectDragGestures on this outer box, detectTapGestures on the circle
 * inside it, in two competing pointerInputs — let a drag eat a tap: any finger drift past the
 * touch slop made the parent start consuming moves, which cancelled the child's wait-for-up, so
 * the press silently did nothing but nudge the button. Here one detector decides: a release that
 * never passed the slop is a tap; past the slop it is a drag and no tap fires. The callbacks are
 * read through rememberUpdatedState and the detector is keyed on Unit, so it is never torn down
 * mid-gesture (the old tap detector was keyed on running/file and restarted on every state flip).
 * An up that a child (a SEND/RESET chip) already consumed is the child's press, never ours.
 */
@Composable
private fun Modifier.diagGestures(
    tapEnabled: Boolean,
    onTap: () -> Unit,
    onDrag: (mx: Float, my: Float) -> Unit,
): Modifier {
    val tapOn by rememberUpdatedState(onTap)
    val dragOn by rememberUpdatedState(onDrag)
    val tapAllowed by rememberUpdatedState(tapEnabled)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragging = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUp()) {
                    if (!dragging && tapAllowed && !change.isConsumed) tapOn()
                    break
                }
                if (!change.pressed) break   // gesture cancelled: neither tap nor drag
                if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) {
                        change.consume()
                        dragOn(delta.x, delta.y)
                    }
                }
            }
        }
    }
}

/** One round button of the finished state. */
@Composable
private fun Chip(label: String, color: Color, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(Color(0xFF111417), CircleShape)
            .border(2.dp, color, CircleShape)
            .pointerInput(label) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

private fun mmss(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
