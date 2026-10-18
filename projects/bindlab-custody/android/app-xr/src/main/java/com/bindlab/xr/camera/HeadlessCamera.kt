package com.bindlab.xr.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs CameraX ImageAnalysis for YOLO. With a [PreviewView] the same camera stream is shown on
 * screen so detection boxes can be drawn over the image they came from. Without one, analysis
 * binds alone first; if the device's camera HAL refuses (some require a Preview stream), it
 * rebinds with a Preview feeding an off-screen SurfaceTexture.
 */
class HeadlessCamera(private val context: Context, private val onFrame: (android.graphics.Bitmap) -> Unit, private val onStatus: (String) -> Unit) {
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var offscreen: SurfaceTexture? = null
    private var offscreenSurface: Surface? = null

    fun start(owner: LifecycleOwner, previewView: PreviewView? = null) {
        val exec = Executors.newSingleThreadExecutor().also { executor = it }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrElse { onStatus("camera provider failed: ${it.message}"); return@addListener }
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(exec, FrameAnalyzer(onFrame)) }
            // Galaxy XR may expose its world-facing camera without a "back" lens facing.
            val selector = when {
                p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> p.availableCameraInfos.firstOrNull()?.cameraSelector
            }
            if (selector == null) { onStatus("no camera available"); return@addListener }
            p.unbindAll()
            if (previewView != null) {
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                    .build().also { it.surfaceProvider = previewView.surfaceProvider }
                runCatching { p.bindToLifecycle(owner, selector, preview, analysis) }
                    .onFailure { onStatus("camera bind failed: ${it.message}") }
                return@addListener
            }
            runCatching { p.bindToLifecycle(owner, selector, analysis) }
                .onFailure { first ->
                    // Fallback: some HALs need a preview stream; feed one to an off-screen 1x1 texture.
                    val preview = Preview.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                        .build()
                    preview.setSurfaceProvider { request ->
                        val tex = SurfaceTexture(0).apply { setDefaultBufferSize(request.resolution.width, request.resolution.height) }
                        val surface = Surface(tex)
                        offscreen = tex; offscreenSurface = surface
                        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
                            surface.release(); tex.release()
                        }
                    }
                    runCatching { p.unbindAll(); p.bindToLifecycle(owner, selector, preview, analysis) }
                        .onSuccess { onStatus("camera: analysis + off-screen preview (analysis-only bind failed: ${first.message})") }
                        .onFailure { onStatus("camera bind failed: ${it.message}") }
                }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        provider = null
        executor?.shutdown(); executor = null
        offscreenSurface?.release(); offscreen?.release()
        offscreenSurface = null; offscreen = null
    }
}
