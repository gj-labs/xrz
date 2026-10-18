package com.bindlab.phone

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bindlab.phone.ui.BindLabColors
import com.bindlab.phone.ui.CardShape
import com.bindlab.phone.ui.Pill
import com.bindlab.phone.ui.PulsingDot
import com.bindlab.phone.ui.StatusDot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun BindingScreen(vm: PhoneViewModel, onBack: () -> Unit, onReview: () -> Unit, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.binding.collectAsState()
    val runState by vm.runState.collectAsState()
    val size = ui.size

    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.binding_title), style = MaterialTheme.typography.titleLarge)
        }

        // Top ~60%: result card. Bottom ~40%: thumb-zone actions.
        Box(Modifier.weight(0.6f).fillMaxWidth().padding(horizontal = 20.dp)) {
            ResultCard(ui, size, Modifier.fillMaxSize())
        }

        Column(
            Modifier.weight(0.4f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            if (ui.message.isNotEmpty() && !ui.running) {
                Text(ui.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            XrStatus(ui, runState)
            val canAct = ui.score != null && !ui.running
            Button(
                onClick = vm::openInXr,
                enabled = canAct && ui.xrPhase != XrPhase.Posting && ui.xrPhase != XrPhase.Waiting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    when (ui.xrPhase) {
                        XrPhase.Posting, XrPhase.Waiting -> stringResource(R.string.put_on_headset)
                        XrPhase.Confirmed -> stringResource(R.string.xr_live)
                        XrPhase.Failed -> stringResource(R.string.retry)
                        else -> stringResource(R.string.open_in_xr)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = vm::sendToLab,
                    enabled = canAct && ui.labPhase != "sending" && ui.labPhase != "sent",
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(
                        when {
                            ui.labPhase == "sent" -> stringResource(R.string.stage_ready_lab)
                            ui.labPhase == "sending" -> "…"
                            else -> stringResource(R.string.send_to_lab)
                        },
                    )
                }
                FilledTonalButton(
                    onClick = { vm.saveExperiment(); onSaved() },
                    enabled = canAct,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text(stringResource(if (ui.saved) R.string.saved else R.string.save)) }
            }
            if (ui.labPhase.startsWith("failed:")) {
                Text(ui.labPhase.removePrefix("failed:"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (ui.runId != null) {
                FilledTonalButton(onClick = onReview, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.review))
                }
            }
        }
    }
}

@Composable
private fun XrStatus(ui: BindingUi, runState: String?) {
    when (ui.xrPhase) {
        XrPhase.Posting, XrPhase.Waiting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PulsingDot()
            Text(stringResource(R.string.put_on_headset), style = MaterialTheme.typography.bodyLarge)
        }
        XrPhase.Sent, XrPhase.Confirmed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(true, size = 12.dp)
            Text(
                if (ui.xrPhase == XrPhase.Confirmed) stringResource(R.string.xr_live) else stringResource(R.string.sent_to_xr),
                style = MaterialTheme.typography.bodyLarge,
            )
            runState?.let { Text(stageOf(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        else -> Unit
    }
}

@Composable
private fun ResultCard(ui: BindingUi, size: String, modifier: Modifier = Modifier) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("ESM2‑$size", container = MaterialTheme.colorScheme.surfaceContainerHighest)
                Spacer(Modifier.weight(1f))
                if (ui.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            ComplexPreview(
                targetResidues = ui.targetResidues, binderResidues = ui.binderResidues,
                spinning = ui.score != null, modifier = Modifier.fillMaxWidth().height(170.dp),
            )
            val s = ui.score
            if (s != null) {
                var goal by remember(s) { mutableFloatStateOf(0f) }
                LaunchedEffect(s) { goal = s.value }
                val animated by animateFloatAsState(goal, tween(600), label = "score")
                Text(
                    "%.2f".format(animated),
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (s.method != "cosine" && !s.bound) BindLabColors.Amber else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(if (s.method == "cosine") R.string.binding_similarity else R.string.binding_predicted),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        s.method == "cosine" -> stringResource(R.string.binding_confidence_cosine)
                        s.bound -> stringResource(R.string.binding_confidence_high, s.threshold)
                        else -> stringResource(R.string.binding_confidence_low, s.threshold)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ResidueRow(stringResource(R.string.binding_target), ui.targetResidues, BindLabColors.Teal)
                ResidueRow(stringResource(R.string.binding_binder), ui.binderResidues, BindLabColors.Amber)
                Spacer(Modifier.height(4.dp))
                ProofChip(ui.backend, ui.latencyMs)
                Text(stringResource(R.string.binding_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    if (ui.running) ui.message.ifEmpty { stringResource(R.string.binding_running) } else stringResource(R.string.binding_running),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** "<backend> · ExecuTorch · <latency> ms" — only says NPU when the backend string does. */
@Composable
private fun ProofChip(backend: String, latencyMs: Double?) {
    val label = backendLabel(backend)
    val latency = latencyMs?.let { "%.0f ms".format(it) } ?: "– ms"
    Pill(
        "$label · ExecuTorch · $latency",
        container = BindLabColors.Teal.copy(alpha = 0.14f),
        content = BindLabColors.Teal,
        border = BindLabColors.Teal.copy(alpha = 0.4f),
    )
}

@Composable
private fun ResidueRow(label: String, residues: List<Int>, color: Color) {
    if (residues.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
        residues.take(6).forEach { r ->
            Pill(r.toString(), container = color.copy(alpha = 0.16f), content = color, modifier = Modifier.padding(0.dp))
        }
        if (residues.size > 6) Pill("+${residues.size - 6}", container = MaterialTheme.colorScheme.surfaceContainerHighest)
    }
}

private class Blob(val angle: Float, val radius: Float, val size: Float, val interface_: Boolean)

/** Stylised complex: target cloud (blue-grey, teal at the interface) with an orbiting amber binder ring. */
@Composable
fun ComplexPreview(
    targetResidues: List<Int>,
    binderResidues: List<Int>,
    spinning: Boolean,
    modifier: Modifier = Modifier,
) {
    val rnd = remember { Random(42) }
    val target = remember {
        List(60) { i ->
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = 0.15f + rnd.nextFloat() * 0.55f
            Blob(a, r, 0.05f + rnd.nextFloat() * 0.06f, i % 4 == 0)
        }
    }
    val binder = remember {
        List(20) { i -> Blob(i / 20f * 2f * PI.toFloat(), 0.95f + rnd.nextFloat() * 0.1f, 0.045f + rnd.nextFloat() * 0.03f, false) }
    }
    val t = rememberInfiniteTransition(label = "spin")
    val rot by t.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), label = "rot")
    val hasInterface = targetResidues.isNotEmpty()
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val unit = minOf(size.width, size.height) / 2.4f
        drawCircle(Brush.radialGradient(listOf(BindLabColors.Teal.copy(alpha = 0.10f), Color.Transparent)), radius = unit * 1.3f, center = c)
        val wobble = if (spinning) rot * 0.35f else 0f
        target.forEach { b ->
            val a = b.angle + wobble
            val p = c + Offset(cos(a) * b.radius * unit, sin(a) * b.radius * unit * 0.8f)
            val col = if (b.interface_ && (hasInterface || !spinning)) BindLabColors.Teal else BindLabColors.TargetGrey
            drawCircle(col.copy(alpha = 0.25f), radius = b.size * unit * 1.9f, center = p)
            drawCircle(col.copy(alpha = 0.9f), radius = b.size * unit, center = p)
        }
        val ring = if (spinning) rot else 0f
        binder.forEach { b ->
            val a = b.angle + ring
            val p = c + Offset(cos(a) * b.radius * unit, sin(a) * b.radius * unit * 0.55f)
            val depth = (sin(a) + 1f) / 2f
            drawCircle(BindLabColors.Amber.copy(alpha = 0.2f + 0.2f * depth), radius = b.size * unit * 1.8f, center = p)
            drawCircle(BindLabColors.Amber.copy(alpha = 0.55f + 0.45f * depth), radius = b.size * unit * (0.8f + 0.4f * depth), center = p)
        }
    }
}
