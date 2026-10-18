package com.bindlab.phone

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bindlab.phone.ui.BindLabColors
import com.bindlab.phone.ui.Pill
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun QrScanScreen(vm: PhoneViewModel, onBack: () -> Unit, onPredict: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Pick up .pte files pushed after the app started.
    LaunchedEffect(Unit) { vm.refreshLadder() }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var scanning by remember { mutableStateOf(true) }
    var parsed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    val target by vm.target.collectAsState()
    val binder by vm.binder.collectAsState()
    val rows by vm.ladder.collectAsState()
    val available = rows.filter { it.available }
    val default = remember(rows) { vm.defaultModel() }
    var modelSize by rememberSaveable(default) { mutableStateOf(default.first) }
    var be by rememberSaveable(default) { mutableStateOf(default.second) }
    val tooLong = target.length > EsmTokenizer.MAX_RESIDUES || binder.length > EsmTokenizer.MAX_RESIDUES
    val ready = target.isNotBlank() && binder.isNotBlank() && !tooLong && available.isNotEmpty()

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (granted && scanning) {
            QrCamera(Modifier.fillMaxSize()) { raw ->
                val p = SequenceParser.parse(raw)
                if (p != null) {
                    vm.setSequences(p.target, p.binder)
                    vm.tick()
                    parsed = true; error = null; scanning = false
                } else error = context.getString(R.string.scan_no_sequence)
            }
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF14202A), BindLabColors.Surface)),
                ),
            )
        }
        Reticle(Modifier.align(Alignment.TopCenter).padding(top = 160.dp), locked = parsed)

        FilledIconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(12.dp).size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = BindLabColors.Frost, contentColor = Color.White),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }

        // Frosted sheet (no backdrop blur in Compose: translucent surface + hairline top border).
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(BindLabColors.Frost)
                .drawBehind { drawLine(BindLabColors.FrostBorder, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
                .animateContentSize()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFFFFF)))
            Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)

            when {
                !granted -> {
                    Text(stringResource(R.string.scan_helper), color = BindLabColors.OnSurfaceVariant)
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.scan_allow_camera))
                    }
                }
                parsed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("${stringResource(R.string.scan_target)} ${target.length} aa", container = BindLabColors.Teal.copy(alpha = 0.18f), content = BindLabColors.Teal)
                        Pill("${stringResource(R.string.scan_binder)} ${binder.length} aa", container = BindLabColors.Amber.copy(alpha = 0.18f), content = BindLabColors.Amber)
                    }
                    Text(stringResource(R.string.scan_parsed), color = BindLabColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(error ?: stringResource(R.string.scan_helper), color = if (error != null) BindLabColors.Amber else BindLabColors.OnSurfaceVariant)
            }

            TextButton(onClick = { showPaste = !showPaste }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(stringResource(if (showPaste) R.string.scan_hide_paste else R.string.scan_paste), color = BindLabColors.Teal)
            }
            AnimatedVisibility(showPaste) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BindLabColors.Teal, unfocusedBorderColor = BindLabColors.Outline,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = BindLabColors.Teal, unfocusedLabelColor = BindLabColors.OnSurfaceVariant,
                    )
                    OutlinedTextField(
                        target, { vm.setSequences(it, null); parsed = false }, singleLine = false, maxLines = 3,
                        label = { Text("${stringResource(R.string.scan_target)} (${target.length} aa)") },
                        shape = MaterialTheme.shapes.medium, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        binder, { vm.setSequences(null, it); parsed = false }, singleLine = false, maxLines = 3,
                        label = { Text("${stringResource(R.string.scan_binder)} (${binder.length} aa)") },
                        shape = MaterialTheme.shapes.medium, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.setSequences(SequenceParser.DEMO_TARGET, SequenceParser.DEMO_BINDER); parsed = false }) {
                            Text(stringResource(R.string.scan_demo), color = BindLabColors.Teal)
                        }
                        if (!scanning) TextButton(onClick = { scanning = true; parsed = false; error = null }) {
                            Text(stringResource(R.string.scan_again), color = BindLabColors.Teal)
                        }
                    }
                    if (available.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            available.forEach { r ->
                                FilterChip(
                                    selected = r.size == modelSize && r.backend == be,
                                    onClick = { modelSize = r.size; be = r.backend },
                                    label = { Text("${r.size} Â· ${r.backend}") },
                                )
                            }
                        }
                    }
                }
            }

            if (tooLong) Text(stringResource(R.string.scan_too_long, EsmTokenizer.MAX_RESIDUES), color = BindLabColors.Red, style = MaterialTheme.typography.bodySmall)
            if (available.isEmpty()) Text(stringResource(R.string.scan_model_missing), color = BindLabColors.Amber, style = MaterialTheme.typography.bodySmall)

            if (parsed || showPaste) {
                Button(
                    onClick = { vm.runBinding(modelSize, be); onPredict() },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BindLabColors.Teal, contentColor = Color(0xFF00201B)),
                ) { Text(stringResource(R.string.predict), style = MaterialTheme.typography.titleMedium) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 240.dp rounded square with four teal corner marks. */
@Composable
private fun Reticle(modifier: Modifier = Modifier, locked: Boolean) {
    val color = if (locked) BindLabColors.Teal else Color.White.copy(alpha = 0.9f)
    Canvas(modifier.size(240.dp)) {
        val w = size.width
        val r = 36.dp.toPx()
        val len = 40.dp.toPx()
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        val arc = Size(2 * r, 2 * r)
        fun arcPath(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = stroke)
        arcPath { moveTo(0f, r + len); lineTo(0f, r); arcTo(Rect(Offset(0f, 0f), arc), 180f, 90f, false); lineTo(r + len, 0f) }
        arcPath { moveTo(w - r - len, 0f); lineTo(w - r, 0f); arcTo(Rect(Offset(w - 2 * r, 0f), arc), 270f, 90f, false); lineTo(w, r + len) }
        arcPath { moveTo(w, w - r - len); lineTo(w, w - r); arcTo(Rect(Offset(w - 2 * r, w - 2 * r), arc), 0f, 90f, false); lineTo(w - r - len, w) }
        arcPath { moveTo(r + len, w); lineTo(r, w); arcTo(Rect(Offset(0f, w - 2 * r), arc), 90f, 90f, false); lineTo(0f, w - r - len) }
        if (locked) drawRoundRect(BindLabColors.Teal.copy(alpha = 0.12f), cornerRadius = CornerRadius(r))
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun QrCamera(modifier: Modifier = Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var done = false
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || done) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        codes.firstOrNull { it.rawValue != null }?.rawValue?.let { if (!done) { done = true; onCode(it) } }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, modifier)
}
