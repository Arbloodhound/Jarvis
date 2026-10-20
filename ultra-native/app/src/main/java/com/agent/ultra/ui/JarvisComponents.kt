package com.agent.ultra.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The shared Jarvis building blocks, so every screen is assembled from the
 * same pieces: one top bar, one section title, one switch row, one hint
 * style, one card. Near-black surfaces, the cyan accent, hairline dividers
 * instead of heavy containers (see ui.theme.AgentUltraTheme).
 */

/** The one top bar: back arrow, title, optional trailing actions, hairline under. */
@Composable
fun JarvisHeader(
    title: String,
    onBack: () -> Unit,
    backText: String = "←",
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(backText, style = MaterialTheme.typography.titleLarge) }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f))
            actions()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A section title: tracked caps in the accent, with a hairline under it. */
@Composable
fun JarvisSectionTitle(text: String, modifier: Modifier = Modifier, divider: Boolean = true) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        if (divider) HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Dim explanatory text under a title or control. */
@Composable
fun JarvisHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

/** The one switch row: title, optional detail, switch on the right. */
@Composable
fun JarvisSwitchRow(
    title: String,
    detail: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    indent: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        modifier = modifier.fillMaxWidth().padding(start = indent, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (detail != null) Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * alpha),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/** A calm content block: surface with a hairline outline, no heavy fill. */
@Composable
fun JarvisCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}
