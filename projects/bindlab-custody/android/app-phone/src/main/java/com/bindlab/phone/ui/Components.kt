package com.bindlab.phone.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bindlab.phone.stageOf

@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) BindLabColors.Teal else BindLabColors.Offline),
    )
}

/** Teal dot with a soft breathing halo; used while waiting for the headset. */
@Composable
fun PulsingDot(modifier: Modifier = Modifier, color: Color = BindLabColors.Teal, size: Dp = 12.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val scale by t.animateFloat(
        1f, 2.4f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "scale",
    )
    val alpha by t.animateFloat(
        0.55f, 0f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "alpha",
    )
    Box(modifier.size(size * 2.6f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).scale(scale).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(color))
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

/** Pill-shaped label (chip without click semantics). */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
    border: Color? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .clip(PillShape)
            .background(container)
            .let { if (border != null) it.border(1.dp, border, PillShape) else it }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Text(
            text, style = MaterialTheme.typography.labelLarge, color = content,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StageChip(state: String?, modifier: Modifier = Modifier) {
    val stage = stageOf(state)
    val (bg, fg) = when (stage) {
        "Running" -> BindLabColors.Amber.copy(alpha = 0.18f) to BindLabColors.Amber
        "Ready for lab" -> BindLabColors.Blue.copy(alpha = 0.18f) to Color(0xFF93C5FD)
        "Reviewed" -> BindLabColors.Teal.copy(alpha = 0.18f) to BindLabColors.Teal
        "In XR" -> BindLabColors.Teal.copy(alpha = 0.18f) to BindLabColors.Teal
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(stage, modifier, container = bg, content = fg)
}

/** Rounded teal→blue gradient used as the molecule thumbnail placeholder. */
@Composable
fun MoleculeThumb(modifier: Modifier = Modifier, size: Dp = 64.dp, seed: Int = 0) {
    val shift = (seed % 5) * 0.08f
    Box(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(
                Brush.linearGradient(
                    0f to BindLabColors.Teal.copy(alpha = 0.95f - shift),
                    1f to BindLabColors.Blue.copy(alpha = 0.9f),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.36f).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
        Box(Modifier.padding(start = size * 0.3f, top = size * 0.3f).size(size * 0.22f).clip(CircleShape).background(BindLabColors.Amber.copy(alpha = 0.85f)))
    }
}

fun formatScore(v: Float): String = "%.2f".format(v)
