package com.bindlab.xr.yolo

import com.bindlab.shared.net.Detection

/**
 * YOLO11 head output [1, 4 + nc, N] (Ultralytics export default) or [1, N, 4 + nc]:
 * cx, cy, w, h in input pixels, then per-class scores (already sigmoid). No objectness.
 * Boxes come back as [x, y, w, h] normalised to the source frame (top-left origin).
 */
object YoloDecoder {
    fun decode(
        out: FloatArray,
        shape: LongArray,
        lb: Letterbox,
        labels: List<String> = CocoLabels.NAMES,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f,
        maxDet: Int = 100,
    ): List<Detection> {
        val channels = 4 + labels.size
        val n = out.size / channels
        if (n == 0) return emptyList()
        // Channel-major unless the shape says [1, N, C].
        val anchorMajor = shape.size == 3 && shape[2].toInt() == channels && shape[1].toInt() != channels
        fun v(c: Int, a: Int) = if (anchorMajor) out[a * channels + c] else out[c * n + a]

        val cands = ArrayList<Cand>()
        for (a in 0 until n) {
            var best = -1
            var bestScore = confThreshold
            for (c in 0 until labels.size) {
                val s = v(4 + c, a)
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (best < 0) continue
            val cx = v(0, a); val cy = v(1, a); val w = v(2, a); val h = v(3, a)
            val x1 = ((cx - w / 2 - lb.padX) / lb.scale).coerceIn(0f, lb.srcW.toFloat())
            val y1 = ((cy - h / 2 - lb.padY) / lb.scale).coerceIn(0f, lb.srcH.toFloat())
            val x2 = ((cx + w / 2 - lb.padX) / lb.scale).coerceIn(0f, lb.srcW.toFloat())
            val y2 = ((cy + h / 2 - lb.padY) / lb.scale).coerceIn(0f, lb.srcH.toFloat())
            if (x2 - x1 < 1f || y2 - y1 < 1f) continue
            cands += Cand(best, bestScore, x1, y1, x2, y2)
        }
        return nms(cands, iouThreshold, maxDet).map {
            Detection(
                labels[it.cls], it.score,
                floatArrayOf(it.x1 / lb.srcW, it.y1 / lb.srcH, (it.x2 - it.x1) / lb.srcW, (it.y2 - it.y1) / lb.srcH),
            )
        }
    }

    class Cand(val cls: Int, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
        val area get() = (x2 - x1) * (y2 - y1)
    }

    /** Greedy per-class NMS. */
    fun nms(cands: List<Cand>, iouThreshold: Float, maxDet: Int): List<Cand> {
        val kept = ArrayList<Cand>()
        for (c in cands.sortedByDescending { it.score }) {
            if (kept.size >= maxDet) break
            if (kept.none { it.cls == c.cls && iou(it, c) > iouThreshold }) kept += c
        }
        return kept
    }

    fun iou(a: Cand, b: Cand): Float {
        val ix = maxOf(0f, minOf(a.x2, b.x2) - maxOf(a.x1, b.x1))
        val iy = maxOf(0f, minOf(a.y2, b.y2) - maxOf(a.y1, b.y1))
        val inter = ix * iy
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}
