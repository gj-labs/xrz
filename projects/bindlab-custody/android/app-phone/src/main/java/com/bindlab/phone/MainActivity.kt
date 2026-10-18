package com.bindlab.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bindlab.phone.ui.BindLabTheme
import com.bindlab.shared.ui.SettingsScreen

private const val HOME = "home"
private const val SCAN = "scan"
private const val BINDING = "binding"
private const val REVIEW = "review"
private const val SETTINGS = "settings"
private const val LADDER = "ladder"

class MainActivity : ComponentActivity() {
    private val vm: PhoneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BindLabTheme {
                var screen by rememberSaveable { mutableStateOf(HOME) }
                var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
                val goHome = { screen = HOME; vm.refreshRuns() }
                BackHandler(enabled = screen != HOME) {
                    screen = when (screen) {
                        REVIEW -> if (vm.binding.value.score != null) BINDING else HOME
                        LADDER -> SETTINGS
                        else -> HOME
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    when (screen) {
                        HOME -> HomeScreen(
                            vm,
                            onNewExperiment = { screen = SCAN },
                            onOpenExperiment = { e ->
                                if (e.runId != null) { reviewId = e.runId; screen = REVIEW } else if (vm.binding.value.score != null) screen = BINDING
                            },
                            onOpenSettings = { screen = SETTINGS },
                        )
                        SCAN -> QrScanScreen(vm, onBack = goHome, onPredict = { screen = BINDING })
                        BINDING -> BindingScreen(
                            vm,
                            onBack = goHome,
                            onReview = { vm.binding.value.runId?.let { reviewId = it; screen = REVIEW } },
                            onSaved = goHome,
                        )
                        REVIEW -> {
                            val id = reviewId
                            if (id != null) ReviewScreen(vm, id, onBack = { screen = HOME }) else LaunchedEffect(Unit) { screen = HOME }
                        }
                        SETTINGS -> SubScreen(stringResource(R.string.settings), onBack = goHome) {
                            SettingsScreen(vm.config, vm.modelDir, vm::onServerChanged, Modifier.fillMaxSize(), showTitle = false, extra = {
                                FilledTonalButton(onClick = { screen = LADDER; vm.refreshLadder() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                    Text(stringResource(R.string.ladder))
                                }
                            })
                        }
                        LADDER -> SubScreen(stringResource(R.string.ladder), onBack = { screen = SETTINGS }) {
                            LadderScreen(vm, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}
