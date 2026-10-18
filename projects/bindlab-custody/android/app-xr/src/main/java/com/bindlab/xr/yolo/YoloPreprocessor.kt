package com.bindlab.xr.yolo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Letterbox geometry, needed to map 640x640 model coords back to the source frame. */
class Letterbox(val scale: Float, val padX: Float, val padY: Float, val srcW: Int, val srcH: Int)

/** Letterbox to [size] (gray 114 padding, aspect kept) -> NCHW float32 RGB in 0..1. Reuses its buffers. */
class YoloPreprocessor(val size: Int = 640) {
    private val canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(size * size)
    val input = FloatArray(3 * size * size)
    val shape = longArrayOf(1, 3, size.toLong(), size.toLong())

    fun process(src: Bitmap): Letterbox {
        val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val nw = src.width * scale
        val nh = src.height * scale
        val padX = (size - nw) / 2f
        val padY = (size - nh) / 2f
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(src, null, RectF(padX, padY, padX + nw, padY + nh), paint)
        canvasBitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val plane = size * size
        for (i in 0 until plane) {
            val p = pixels[i]
            input[i] = ((p shr 16) and 0xFF) / 255f
            input[plane + i] = ((p shr 8) and 0xFF) / 255f
            input[2 * plane + i] = (p and 0xFF) / 255f
        }
        return Letterbox(scale, padX, padY, src.width, src.height)
    }
}
