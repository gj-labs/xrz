package com.bindlab.xr.ui

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bindlab.shared.net.Detection
import com.bindlab.shared.net.ProtocolStep
import com.bindlab.shared.net.Troubleshoot
import com.bindlab.xr.VoiceBadge
import com.bindlab.xr.Warning

object Hud {
    val Panel = Color(0xEB14181C)
    val Border = Color(0x22FFFFFF)
    val Teal = Color(0xFF2DD4BF)
    val TealFill = Color(0x332DD4BF)
    val Grey = Color(0xFF9CA3AF)
    val GreyFill = Color(0x33FFFFFF)
    val Body = Color(0xFFD1D5DB)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val Ink = Color(0xFF111827)
    const val MOTION_MS = 150
}

private fun <T> motion() = tween<T>(Hud.MOTION_MS)

// ------------------------------------------------------------------------------------------ step card

@Composable
fun StepCard(
    step: ProtocolStep?,
    index: Int,
    total: Int,
    seen: Set<String>,
    timerRemaining: Int?,
    red: Boolean,
    awaitingReview: Boolean,
    labName: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulseAlpha",
    )
    val borderColor by animateColorAsState(if (red) Hud.Red.copy(alpha = pulse) else Hud.Border, motion(), label = "border")
    Column(
        modifier
            .width(420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Hud.Panel)
            .border(if (red) 2.dp else 1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (step == null) {
            Text(if (total == 0) "BINDLAB" else "PROTOCOL", fontSize = 12.sp, letterSpacing = 2.sp, color = Hud.Grey)
            Text(if (total == 0) "Waiting for protocol" else "All steps done", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                if (total == 0) "Steps are beamed from the backend. Say “start protocol” to begin." else "Waiting for PI review.",
                fontSize = 20.sp, color = Hud.Body,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("STEP ${index + 1} OF $total", fontSize = 12.sp, letterSpacing = 2.sp, color = Hud.Grey)
            if (step.critical) Text("CRITICAL", fontSize = 12.sp, letterSpacing = 2.sp, color = Hud.Amber)
            Spacer(Modifier.weight(1f))
            if (awaitingReview) Text("waiting for review", fontSize = 16.sp, color = Hud.Grey)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(step.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 32.sp)
                if (step.instruction.isNotBlank()) Text(step.instruction, fontSize = 20.sp, color = Hud.Body, lineHeight = 26.sp)
            }
            if (step.timerS != null) TimerRing(timerRemaining ?: step.timerS!!, step.timerS!!)
        }
        if (step.required.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                step.required.forEach { ObjectChip(labName(it), it in seen) }
            }
        }
        ProgressDots(index, total)
    }
}

@Composable
private fun TimerRing(remaining: Int, total: Int) {
    val frac = if (total <= 0) 0f else (remaining.toFloat() / total).coerceIn(0f, 1f)
    val color = if (remaining == 0) Hud.Amber else Hud.Teal
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Hud.GreyFill, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("%d:%02d".format(remaining / 60, remaining % 60), fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ObjectChip(text: String, seen: Boolean) {
    val bg by animateColorAsState(if (seen) Hud.TealFill else Hud.GreyFill, motion(), label = "chipBg")
    val fg by animateColorAsState(if (seen) Hud.Teal else Hud.Grey, motion(), label = "chipFg")
    Text(
        text, fontSize = 16.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun ProgressDots(index: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val c = when { i < index -> Hud.Teal; i == index -> Color.White; else -> Hud.GreyFill }
            Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        }
    }
}

// ------------------------------------------------------------------------------------------ warning strip

@Composable
fun WarningStrip(
    warning: Warning,
    holdActive: Boolean,
    onRedo: () -> Unit,
    onContinue: () -> Unit,
    onWhatsWrong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = warning !is Warning.None,
        enter = fadeIn(motion()) + slideInVertically(motion()) { -it / 2 },
        exit = fadeOut(motion()) + slideOutVertically(motion()) { -it / 2 },
        modifier = modifier,
    ) {
        val red = warning is Warning.Red
        val bg = if (red) Hud.Red else Hud.Amber
        val fg = if (red) Color.White else Hud.Ink
        val text = when (warning) { is Warning.Amber -> warning.text; is Warning.Red -> warning.text; Warning.None -> "" }
        Column(
            Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = fg, lineHeight = 26.sp)
            if (red && holdActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Pill("Redo", Color.White, Hud.Ink, Modifier.weight(1f), onRedo)
                    Pill("Continue anyway", Color(0x33FFFFFF), Color.White, Modifier.weight(1.3f), onContinue)
                    Pill("What's wrong?", Color(0x33FFFFFF), Color.White, Modifier.weight(1.2f), onWhatsWrong)
                }
            }
        }
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(999.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

// ------------------------------------------------------------------------------------------ troubleshooting

@Composable
fun TroubleshootPanel(items: List<Troubleshoot>, onApply: (Troubleshoot) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(420.dp).clip(RoundedCornerShape(20.dp)).background(Hud.Panel).border(1.dp, Hud.Border, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("TROUBLESHOOTING", fontSize = 12.sp, letterSpacing = 2.sp, color = Hud.Grey)
        if (items.isEmpty()) Text("No troubleshooting notes for this step.", fontSize = 18.sp, color = Hud.Body)
        items.forEach { t ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.symptom, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Cause · ${t.cause}", fontSize = 16.sp, color = Hud.Grey)
                Text("Fix · ${t.fix}", fontSize = 18.sp, color = Hud.Teal)
                Pill("Apply & redo", Hud.TealFill, Hud.Teal, Modifier.widthIn(min = 160.dp)) { onApply(t) }
            }
        }
        Pill("Close", Hud.GreyFill, Color.White, Modifier.fillMaxWidth(), onClose)
    }
}

// ------------------------------------------------------------------------------------------ completion

@Composable
fun CompletionCard(deviations: Int, chainOk: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.width(420.dp).clip(RoundedCornerShape(20.dp)).background(Hud.Panel).border(1.dp, Hud.Teal.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("COMPLETE", fontSize = 12.sp, letterSpacing = 2.sp, color = Hud.Teal)
        Text("Protocol complete", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "$deviations deviation${if (deviations == 1) "" else "s"} · chain ${if (chainOk) "OK" else "pending"}",
            fontSize = 20.sp, color = Hud.Body,
        )
        Text("Review on phone", fontSize = 18.sp, color = Hud.Teal, modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Hud.TealFill).padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

// ------------------------------------------------------------------------------------------ voice badge

@Composable
fun VoiceBadgeView(badge: VoiceBadge, modifier: Modifier = Modifier) {
    AnimatedVisibility(badge !is VoiceBadge.Idle, enter = fadeIn(motion()), exit = fadeOut(motion()), modifier = modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(Hud.Panel).border(1.dp, Hud.Border, RoundedCornerShape(999.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (badge) {
                VoiceBadge.Listening -> {
                    val a by rememberInfiniteTransition(label = "mic").animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "micDot")
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Hud.Teal.copy(alpha = a)))
                    Text("🎙 listening…", fontSize = 16.sp, color = Hud.Grey)
                }
                is VoiceBadge.Heard -> Text(badge.text, fontSize = 18.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp))
                is VoiceBadge.Command -> Text(
                    badge.text, fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = Hud.Teal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Hud.TealFill).padding(horizontal = 12.dp, vertical = 4.dp).widthIn(max = 420.dp),
                )
                VoiceBadge.Idle -> Unit
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ detections

/**
 * Corner brackets + label chip for each detection. Boxes are normalised to the camera frame, so they
 * are mapped onto the fit-centre rectangle the frame occupies (same as PreviewView FIT_CENTER).
 */
@Composable
fun DetectionOverlay(dets: List<Detection>, step: ProtocolStep?, frame: Pair<Int, Int>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val stroke = 3.dp.toPx()
        val pad = 6.dp.toPx()
        val paint = Paint().apply { textSize = 16.sp.toPx(); isAntiAlias = true; isFakeBoldText = true }
        val fw = frame.first.coerceAtLeast(1).toFloat(); val fh = frame.second.coerceAtLeast(1).toFloat()
        val scale = minOf(size.width / fw, size.height / fh)
        val vw = fw * scale; val vh = fh * scale
        val ox = (size.width - vw) / 2f; val oy = (size.height - vh) / 2f
        for (d in dets) {
            val color = when {
                step != null && d.label in step.forbidden -> Hud.Red
                step != null && d.label in step.required -> Hud.Teal
                else -> Color(0xCCFFFFFF)
            }
            val x = ox + d.box[0] * vw; val y = oy + d.box[1] * vh
            val w = d.box[2] * vw; val h = d.box[3] * vh
            val lx = w * 0.18f; val ly = h * 0.18f
            fun seg(a: Offset, b: Offset) = drawLine(color, a, b, stroke, StrokeCap.Round)
            seg(Offset(x, y + ly), Offset(x, y)); seg(Offset(x, y), Offset(x + lx, y))
            seg(Offset(x + w - lx, y), Offset(x + w, y)); seg(Offset(x + w, y), Offset(x + w, y + ly))
            seg(Offset(x, y + h - ly), Offset(x, y + h)); seg(Offset(x, y + h), Offset(x + lx, y + h))
            seg(Offset(x + w - lx, y + h), Offset(x + w, y + h)); seg(Offset(x + w, y + h), Offset(x + w, y + h - ly))

            val label = d.label
            val tw = paint.measureText(label)
            val th = paint.textSize
            val chipTop = y - th - pad * 2 - 4
            val top = if (chipTop < 0) y + pad else chipTop
            drawRoundRect(color, Offset(x, top), Size(tw + pad * 2, th + pad * 2), androidx.compose.ui.geometry.CornerRadius(999f))
            paint.color = (if (color == Hud.Teal || color == Hud.Red) Hud.Ink else Color.White).toArgb()
            drawContext.canvas.nativeCanvas.drawText(label, x + pad, top + pad + th * 0.8f, paint)
        }
    }
}

@Composable
fun GearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).background(Hud.Panel).border(1.dp, Hud.Border, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("⚙", fontSize = 22.sp, color = Color.White, modifier = Modifier.alpha(0.9f)) }
}

/** One-line status strip: model / engine, inference time, server link. */
@Composable
fun StatusBar(model: String, inferMs: Double?, connected: Boolean, detections: Int, modifier: Modifier = Modifier) {
    val bad = model.startsWith("model missing") || model.contains("failed") || model.contains("error")
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(Hud.Panel).border(1.dp, Hud.Border, RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Hud.Teal else Hud.Red))
        Text(if (connected) "server" else "offline", fontSize = 14.sp, color = Hud.Grey)
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (bad) Hud.Red else Hud.Teal))
        Text(
            if (bad) model.substringBefore(":") else (inferMs?.let { "YOLO %.0f ms · %d objects".format(it, detections) } ?: "YOLO loading…"),
            fontSize = 14.sp, color = if (bad) Color(0xFFFFB4AB) else Hud.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}
