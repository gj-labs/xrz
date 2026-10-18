package com.bindlab.xr.protocol

import com.bindlab.shared.net.Detection
import com.bindlab.shared.net.ProtocolStep

/**
 * Lock/key gate for the current protocol step.
 * Pass: every required label seen at conf > [conf] in [passFrames] consecutive frames.
 * Anomaly: a forbidden label seen at conf > [conf] for [forbiddenFrames] consecutive frames
 * (3, not 1, so a single-frame false positive does not fire the red card); re-arms once it is gone.
 */
class StepGate(val conf: Float = 0.5f, val passFrames: Int = 8, val forbiddenFrames: Int = 3) {
    sealed interface Event {
        val step: ProtocolStep
        val detections: List<Detection>

        data class Passed(override val step: ProtocolStep, override val detections: List<Detection>) : Event
        data class Anomaly(override val step: ProtocolStep, val label: String, override val detections: List<Detection>) : Event
    }

    var steps: List<ProtocolStep> = emptyList()
        private set
    var index: Int = 0
        private set
    val current: ProtocolStep? get() = steps.getOrNull(index)

    /** Consecutive-frame counts per required label, for the UI. */
    val progress = mutableMapOf<String, Int>()
    private val forbiddenCount = mutableMapOf<String, Int>()
    private var anomalyActive = false
    private var passed = false
    private var frames = 0

    @Synchronized
    fun setProtocol(newSteps: List<ProtocolStep>, startIndex: Int = 0) {
        steps = newSteps
        index = startIndex
        reset()
    }

    @Synchronized
    fun setIndex(i: Int) {
        if (i == index && steps.isNotEmpty() && !passed) return
        index = i
        reset()
    }

    @Synchronized
    fun reset() {
        progress.clear(); forbiddenCount.clear()
        current?.required?.forEach { progress[it] = 0 }
        anomalyActive = false; passed = false; frames = 0
    }

    val isPassed @Synchronized get() = passed
    val isAnomaly @Synchronized get() = anomalyActive

    /** Operator explicitly advanced (voice "next" / button): stop evaluating this step so it cannot fire Passed again. */
    @Synchronized
    fun markPassed() { passed = true; anomalyActive = false }

    /** Clear a held anomaly (operator "Continue anyway") without resetting the required-label progress. */
    @Synchronized
    fun clearAnomaly() { anomalyActive = false; forbiddenCount.clear() }

    @Synchronized
    fun onFrame(dets: List<Detection>): Event? {
        val step = current ?: return null
        if (passed) return null
        frames++
        val seen = dets.filter { it.conf > conf }
        val labels = seen.map { it.label }.toSet()

        var fired: String? = null
        for (f in step.forbidden) {
            val n = if (f in labels) (forbiddenCount[f] ?: 0) + 1 else 0
            forbiddenCount[f] = n
            if (n >= forbiddenFrames) fired = f
        }
        if (fired != null) {
            step.required.forEach { progress[it] = 0 }
            if (!anomalyActive) {
                anomalyActive = true
                return Event.Anomaly(step, fired, seen.filter { it.label == fired } + seen.filter { it.label != fired })
            }
            return null
        }
        if (forbiddenCount.values.all { it == 0 }) anomalyActive = false

        for (r in step.required) progress[r] = if (r in labels) (progress[r] ?: 0) + 1 else 0
        val ok = if (step.required.isEmpty()) frames >= passFrames else step.required.all { (progress[it] ?: 0) >= passFrames }
        if (ok && !anomalyActive) {
            passed = true
            return Event.Passed(step, seen.filter { it.label in step.required } + seen.filter { it.label !in step.required })
        }
        return null
    }
}
