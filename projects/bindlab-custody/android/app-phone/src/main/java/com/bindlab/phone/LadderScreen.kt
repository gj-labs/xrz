package com.bindlab.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bindlab.phone.ui.BindLabColors
import com.bindlab.phone.ui.CardShape
import com.bindlab.phone.ui.Pill

@Composable
fun LadderScreen(vm: PhoneViewModel, modifier: Modifier = Modifier) {
    val rows by vm.ladder.collectAsState()
    val running by vm.ladderRunning.collectAsState()
    val sustained by vm.sustained.collectAsState()
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("10 warm-up + 50 timed runs per model; results POST to /bench.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) Button(onClick = vm::cancelLadder) { Text("Stop") }
            else Button(onClick = vm::runLadder, enabled = rows.any { it.available }) { Text("Run ladder") }
            OutlinedButton(onClick = vm::refreshLadder, enabled = !running) { Text("Rescan files") }
            Switch(checked = sustained, onCheckedChange = { vm.sustained.value = it }, enabled = !running)
            Text("2-min sustained", style = MaterialTheme.typography.bodySmall)
        }
        if (rows.none { it.available }) {
            Text("No models found. adb push esm2_<size>_<backend>.pte to:\n${vm.modelDir}", color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.size + it.backend }) { row -> LadderCard(row) }
        }
    }
}

@Composable
private fun LadderCard(row: LadderRow) {
    val accent = when (row.phase) {
        "ok" -> BindLabColors.Teal
        "oom", "load_failed" -> BindLabColors.Red
        "running" -> BindLabColors.Amber
        "missing" -> BindLabColors.Offline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("esm2_${row.size}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill(
                    "${row.backend} · ${if (row.phase == "oom") "limit reached (oom)" else row.phase}",
                    container = accent.copy(alpha = 0.16f), content = accent,
                )
            }
            if (row.detail.isNotEmpty()) Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
