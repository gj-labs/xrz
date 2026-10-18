package com.bindlab.xr.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** RGBA ImageProxy -> upright Bitmap -> [onFrame]. Runs on the analysis executor. */
class FrameAnalyzer(private val onFrame: (Bitmap) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        val bmp = try {
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            if (rot == 0) raw
            else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        } finally {
            image.close()
        }
        onFrame(bmp)
    }
}
