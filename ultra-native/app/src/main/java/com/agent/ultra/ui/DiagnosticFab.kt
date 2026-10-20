package com.agent.ultra.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ultra.diag.LiveDiagnostic
import kotlin.math.roundToInt

/**
 * The floating diagnostic button drawn over every app screen (MainActivity). Tap to run the live
 * diagnostic: it glows green with a countdown while it captures the real app for 5 minutes
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
    val bg by animateColorAsState(
        if (running) green.copy(alpha = glow) else Color(0xFF2A2A2E), label = "bg",
    )

    Box(
        modifier = modifier
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag -> change.consume(); dx += drag.x; dy += drag.y }
            },
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
                .border(2.dp, if (running) green else green.copy(alpha = 0.5f), CircleShape)
                .pointerInput(running, file) {
                    detectTapGestures(
                        onTap = { LiveDiagnostic.toggle(ctx) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    running -> {
                        Text("REC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(mmss(remaining), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                    }
                    else -> Text("DIAG", color = green, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
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
            .background(Color(0xFF2A2A2E), CircleShape)
            .border(2.dp, color, CircleShape)
            .pointerInput(label) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

private fun mmss(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
