package com.bindlab.phone

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bindlab.phone.ui.BindLabColors
import com.bindlab.phone.ui.CardShape
import com.bindlab.phone.ui.Pill
import com.bindlab.phone.ui.StageChip
import com.bindlab.shared.net.ProtocolStep
import com.bindlab.shared.net.RunEvent
import com.bindlab.shared.net.StepReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReviewScreen(vm: PhoneViewModel, runId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.review.collectAsState()
    val reviewer by vm.reviewer.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(runId) { vm.openReview(runId) }
    val detail = ui.detail

    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.review_title), style = MaterialTheme.typography.titleLarge)
                Text(runId, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            detail?.let { StageChip(it.summary.state, Modifier.padding(end = 12.dp)) }
        }

        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                detail == null -> Unit
                detail.chainOk -> Pill(stringResource(R.string.chain_ok), container = BindLabColors.Teal.copy(alpha = 0.16f), content = BindLabColors.Teal)
                else -> Pill(stringResource(R.string.chain_broken), container = BindLabColors.Red.copy(alpha = 0.16f), content = BindLabColors.Red)
            }
        }

        Box(Modifier.weight(1f)) {
            val step = detail?.reviewStep
            when {
                ui.loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.review_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                detail == null -> Text(
                    ui.error ?: "", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(20.dp),
                )
                detail.events.isEmpty() && step == null -> Text(stringResource(R.string.review_empty), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    if (step != null) item(key = "step:${step.id}") {
                        StepReviewCard(
                            step, index = detail.reviewsAccepted, total = detail.steps.size, report = detail.reviewReport,
                            reviewer = reviewer, onReviewer = vm::setReviewer,
                            busy = ui.reviewing, onDecision = vm::reviewStep,
                        )
                    } else detail.liveStep?.let { live ->
                        item(key = "live:${live.id}") {
                            Text(
                                stringResource(R.string.review_live_step, detail.summary.stepIndex + 1, detail.steps.size, live.title),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }
                    }
                    itemsIndexed(detail.events) { i, e -> TimelineRow(e, last = i == detail.events.lastIndex) }
                }
            }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.error != null && detail != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    vm.exportEvidence { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                },
                enabled = detail != null && !ui.exporting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (ui.exporting) "…" else stringResource(R.string.export_evidence), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** Next step awaiting sign-off, with the headset's recorded verdict. Post-hoc: the headset has already moved on. */
@Composable
private fun StepReviewCard(
    step: ProtocolStep,
    index: Int,
    total: Int,
    report: StepReport?,
    reviewer: String,
    onReviewer: (String) -> Unit,
    busy: Boolean,
    onDecision: (accepted: Boolean, note: String) -> Unit,
) {
    var note by rememberSaveable(step.id) { mutableStateOf("") }
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = BindLabColors.Amber.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.review_step_n, index + 1, total), style = MaterialTheme.typography.labelLarge, color = BindLabColors.Amber)
            Text(step.title, style = MaterialTheme.typography.titleMedium)
            if (step.instruction.isNotEmpty()) Text(step.instruction, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (report != null) {
                val verdict = if (report.claim == "passed") stringResource(R.string.review_headset_passed) else stringResource(R.string.review_headset_anomaly)
                Text(
                    "$verdict · ${timeFmt.format(Date(report.wallTimeMs))}" + (if (report.photoSha256 != null) " · 📷" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (report.claim == "passed") BindLabColors.Teal else BindLabColors.Red,
                )
            }
            OutlinedTextField(
                value = reviewer, onValueChange = onReviewer, singleLine = true,
                label = { Text(stringResource(R.string.reviewer)) }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it }, minLines = 2,
                label = { Text(stringResource(R.string.review_note)) }, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onDecision(false, note) }, enabled = !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.reject)) }
                Button(
                    onClick = { onDecision(true, note) }, enabled = !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(if (busy) "…" else stringResource(R.string.accept)) }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/** teal = pass, amber = hold/review, red = deviation/failure. */
private fun eventColor(e: RunEvent): Color {
    val k = e.kind.lowercase()
    val status = (e.payload.optString("status") + " " + e.payload.optString("client_claim") + " " + e.payload.optString("state")).lowercase()
    return when {
        "anomaly" in status || "deviation" in status || "fail" in k || "fail" in status -> BindLabColors.Red
        "hold" in status || "review" in k || "approval" in k -> BindLabColors.Amber
        else -> BindLabColors.Teal
    }
}

private fun eventTitle(e: RunEvent): String = when {
    e.kind.startsWith("state:") -> e.kind.removePrefix("state:").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    e.kind == "binding" -> "Binding scored"
    e.kind == "lab_handoff" -> "Sent to lab"
    e.kind == "approval" -> "Approval"
    else -> e.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun TimelineRow(e: RunEvent, last: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val color = eventColor(e)
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(12.dp))
        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.weight(1f).padding(bottom = 10.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(eventTitle(e), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(timeFmt.format(Date((e.ts * 1000).toLong())), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val note = e.payload.optString("note").ifEmpty { e.payload.optString("step_id") }
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedVisibility(expanded) {
                    Text(
                        e.payload.toString(2).take(1200),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
