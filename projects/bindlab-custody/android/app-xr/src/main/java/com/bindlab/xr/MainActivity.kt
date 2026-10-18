package com.bindlab.xr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bindlab.shared.ui.SettingsScreen
import com.bindlab.xr.camera.HeadlessCamera
import com.bindlab.xr.ui.CompletionCard
import com.bindlab.xr.ui.DetectionOverlay
import com.bindlab.xr.ui.GearButton
import com.bindlab.xr.ui.Hud
import com.bindlab.xr.ui.Pill
import com.bindlab.xr.ui.StatusBar
import com.bindlab.xr.ui.StepCard
import com.bindlab.xr.ui.TroubleshootPanel
import com.bindlab.xr.ui.VoiceBadgeView
import com.bindlab.xr.ui.WarningStrip
import com.bindlab.xr.yolo.YoloDetector

class MainActivity : ComponentActivity() {
    private val vm: XrViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = XrColors, typography = XrTypography) {
                LabScreen(vm)
            }
        }
    }
}

private val XrColors = darkColorScheme(
    primary = Hud.Teal,
    onPrimary = Hud.Ink,
    secondary = Hud.Amber,
    error = Hud.Red,
    background = Color.Transparent,
    surface = Color(0xFF14181C),
    onSurface = Color.White,
    onBackground = Color.White,
)

private val XrTypography = Typography(
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    bodySmall = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 16.sp),
    labelSmall = TextStyle(fontSize = 16.sp),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
private fun LabScreen(vm: XrViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var cameraGranted by remember { mutableStateOf(has(Manifest.permission.CAMERA)) }
    var micGranted by remember { mutableStateOf(has(Manifest.permission.RECORD_AUDIO)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        cameraGranted = r[Manifest.permission.CAMERA] ?: cameraGranted
        micGranted = r[Manifest.permission.RECORD_AUDIO] ?: micGranted
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted || !micGranted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    LaunchedEffect(micGranted) { vm.onMicPermission(micGranted) }

    // Camera image is shown on screen (FIT_CENTER) so the detection boxes line up with what YOLO saw.
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(cameraGranted, owner) {
        val cam = if (cameraGranted) HeadlessCamera(context, vm::onFrame) { vm.modelStatus.value = it }.also { it.start(owner, previewView) } else null
        onDispose { cam?.stop() }
    }

    val step by vm.currentStep.collectAsState()
    val steps by vm.steps.collectAsState()
    val index by vm.stepIndex.collectAsState()
    val seen by vm.seenLabels.collectAsState()
    val timer by vm.timerRemaining.collectAsState()
    val warning by vm.warning.collectAsState()
    val hold by vm.holdActive.collectAsState()
    val awaiting by vm.awaitingReview.collectAsState()
    val troubleshoot by vm.troubleshootOpen.collectAsState()
    val complete by vm.complete.collectAsState()
    val deviations by vm.deviationCount.collectAsState()
    val badge by vm.voiceBadge.collectAsState()
    val dets by vm.detections.collectAsState()
    val frame by vm.frameSize.collectAsState()
    val showDets by vm.showDetections.collectAsState()
    val connected by vm.ws.connected.collectAsState()
    val status by vm.modelStatus.collectAsState()
    val ms by vm.inferMs.collectAsState()
    val dev by vm.devMode.collectAsState()
    var settings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Transparent)) {
        // Camera box has the frame's aspect ratio, so the image fills it with no bars.
        val aspect = frame.first.toFloat() / frame.second.coerceAtLeast(1)
        Box(Modifier.fillMaxHeight().aspectRatio(aspect).align(Alignment.CenterStart).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            if (showDets) DetectionOverlay(dets, step, frame)
            StatusBar(status, ms, connected, dets.size, Modifier.align(Alignment.TopStart).padding(16.dp))
            Row(Modifier.align(Alignment.BottomStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    steps.isEmpty() -> Pill("▶ Start protocol", Hud.Teal, Hud.Ink, onClick = vm::startProtocol)
                    complete -> Pill("↺ Restart protocol", Hud.Teal, Hud.Ink, onClick = vm::startProtocol)
                    dev -> Pill("Skip step ▶ (dev)", Hud.Amber, Hud.Ink, onClick = vm::requestAdvance)
                    else -> Pill(if (awaiting) "Waiting for review…" else "Next step ▶", if (awaiting) Hud.GreyFill else Hud.TealFill, if (awaiting) Hud.Grey else Hud.Teal, onClick = vm::requestAdvance)
                }
                if (!cameraGranted) Pill("Allow camera & microphone", Hud.Amber, Hud.Ink) {
                    launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                }
            }
        }

        // HUD cards float top-right over the transparent window.
        Column(
            Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(end = 24.dp, top = 24.dp, bottom = 80.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (complete) {
                CompletionCard(deviations, chainOk = connected)
            } else {
                StepCard(step, index, steps.size, seen, timer, red = warning is Warning.Red, awaitingReview = awaiting, labName = vm::lab)
                WarningStrip(warning, hold, onRedo = vm::redo, onContinue = vm::continueAnyway, onWhatsWrong = { vm.openTroubleshoot(true) })
                AnimatedVisibility(troubleshoot && step != null, enter = fadeIn(), exit = fadeOut()) {
                    TroubleshootPanel(step?.troubleshoot.orEmpty(), onApply = vm::applyFix, onClose = { vm.openTroubleshoot(false) })
                }
            }
        }

        VoiceBadgeView(badge, Modifier.align(Alignment.BottomEnd).padding(start = 24.dp, end = 24.dp, bottom = 80.dp))
        GearButton({ settings = !settings }, Modifier.align(Alignment.BottomEnd).padding(16.dp))

        AnimatedVisibility(settings, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            SettingsPanel(vm, onClose = { settings = false })
        }
    }
}

@Composable
private fun SettingsPanel(vm: XrViewModel, onClose: () -> Unit) {
    val status by vm.modelStatus.collectAsState()
    val connected by vm.ws.connected.collectAsState()
    val runId by vm.runId.collectAsState()
    val runState by vm.runState.collectAsState()
    val log by vm.log.collectAsState()
    val ms by vm.inferMs.collectAsState()
    val dets by vm.detections.collectAsState()
    val showDets by vm.showDetections.collectAsState()
    val dev by vm.devMode.collectAsState()
    val mic by vm.micEnabled.collectAsState()
    val voiceStatus by vm.voiceStatus.collectAsState()
    val notes by vm.notes.collectAsState()
    val deviations by vm.deviationCount.collectAsState()
    var server by remember { mutableStateOf(false) }

    Column(
        Modifier.width(720.dp).fillMaxHeight(0.9f).clip(RoundedCornerShape(20.dp)).background(Hud.Panel)
            .border(1.dp, Hud.Border, RoundedCornerShape(20.dp)).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) Hud.Teal else Hud.Red))
            Text(if (connected) "server connected" else "server offline · ${vm.config.server}", color = Hud.Body, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Pill("Close", Hud.GreyFill, Color.White, onClick = onClose)
        }
        if (server) {
            Pill("← Back", Hud.GreyFill, Color.White) { server = false }
            SettingsScreen(vm.config, vm.modelDir, vm::onServerChanged, Modifier.fillMaxWidth(), extra = { EngineChooser(vm) })
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Pill("Sync", Hud.GreyFill, Color.White, Modifier.weight(1f), vm::sync)
            Pill("Demo steps", Hud.GreyFill, Color.White, Modifier.weight(1f), vm::loadDemoProtocol)
            Pill("Server…", Hud.GreyFill, Color.White, Modifier.weight(1f)) { server = true }
        }
        Pill("Open molecule viewer", Hud.TealFill, Hud.Teal, Modifier.fillMaxWidth()) { vm.openMoleculeViewer(runId ?: "") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Pill("Sim pass", Hud.TealFill, Hud.Teal, Modifier.weight(1f)) { vm.simulate(true) }
            Pill("Sim anomaly", Color(0x33EF4444), Hud.Red, Modifier.weight(1f)) { vm.simulate(false) }
            Pill("Next step", Hud.GreyFill, Color.White, Modifier.weight(1f), vm::requestAdvance)
        }
        ToggleRow("Show detections", showDets, vm::setShowDetections)
        ToggleRow("Developer mode (skip gates, holds, review)", dev, vm::setDevMode)
        ToggleRow("Microphone (voice commands)", mic, vm::setMicEnabled)
        Text("voice: $voiceStatus", color = Hud.Grey, fontSize = 16.sp)
        Text("run ${runId ?: "-"} ${runState ?: ""} · deviations $deviations", color = Hud.Body, fontSize = 16.sp)
        Text(status, color = if (status.startsWith("model missing") || status.contains("failed")) Color(0xFFFFB4AB) else Hud.Grey, fontSize = 16.sp)
        Text("Detections" + (ms?.let { " · %.0f ms".format(it) } ?: "") + ": " + (if (dets.isEmpty()) "none" else dets.take(6).joinToString { "${it.label} %.2f".format(it.conf) }), color = Hud.Body, fontSize = 16.sp)
        if (log.isNotEmpty()) Text(log, color = Hud.Grey, fontSize = 16.sp)
        if (notes.isNotEmpty()) {
            Text("Notes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            notes.takeLast(8).forEach { Text("· $it", color = Hud.Body, fontSize = 16.sp) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun EngineChooser(vm: XrViewModel) {
    val mode by vm.engineMode.collectAsState()
    Text("Inference engine", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(mode == YoloDetector.MODE_AAR, { vm.setEngine(YoloDetector.MODE_AAR) }, label = { Text("ExecuTorch AAR") })
        FilterChip(mode == YoloDetector.MODE_RUNNER, { vm.setEngine(YoloDetector.MODE_RUNNER) }, label = { Text("qnn_executor_runner") })
    }
    Text(
        "AAR tries yolo11n_qnn.pte then yolo11n_xnnpack.pte. Runner execs jniLibs/libqnn_executor_runner.so per frame (slow fallback).",
        style = MaterialTheme.typography.bodySmall,
    )
}
