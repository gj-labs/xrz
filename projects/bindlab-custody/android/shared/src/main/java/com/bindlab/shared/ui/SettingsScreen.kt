package com.bindlab.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bindlab.shared.net.BackendClient
import com.bindlab.shared.net.ConfigStore
import kotlinx.coroutines.launch

/** Server URL editor (persisted over assets/config.json) + connection test. [extra] adds app rows. */
@Composable
fun SettingsScreen(
    config: ConfigStore,
    modelDir: String,
    onServerChanged: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
    showTitle: Boolean = true,
) {
    var url by remember { mutableStateOf(config.server) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTitle) Text("Settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Server (http://<LAN_IP>:8000)") }, singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { config.server = url; url = config.server; status = "saved"; onServerChanged() },
                modifier = Modifier.height(48.dp),
            ) {
                Text("Save")
            }
            FilledTonalButton(onClick = {
                config.server = url
                status = "testing…"
                scope.launch {
                    status = runCatching { "OK: " + BackendClient({ url }).health() }
                        .getOrElse { "failed: ${it.message}" }
                }
            }, modifier = Modifier.height(48.dp)) { Text("Test") }
            FilledTonalButton(
                onClick = { config.resetServer(); url = config.server; status = "reset to config.json"; onServerChanged() },
                modifier = Modifier.height(48.dp),
            ) {
                Text("Reset")
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        Text("config.json default: ${config.assetServer}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Model folder (adb push here):\n$modelDir", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra()
    }
}
