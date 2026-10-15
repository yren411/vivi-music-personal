package com.music.vivi.playback.automix

import kotlin.math.*

/** Independent analysis-only Blackman-windowed sinc. No thread, JNI, or playback dependency. */
internal class BandlimitedResampler(val inputRate: Int, val outputRate: Int = 22_050,
                                    control: AnalysisControl = AnalysisControl()) {
    init { require(inputRate in 8_000..192_000 && outputRate in 8_000..192_000) }
    private val ratio = min(1.0, outputRate.toDouble() / inputRate)
    private val radius = ceil(32 / ratio).toInt()
    private val taps = 2 * radius + 1
    private val phases = 1024
    // One bounded table per window, never an unbounded global rate cache.
    private val kernels: FloatArray = FloatArray(phases * taps)
    val tableBytes: Int get() = kernels.size * 4
    val supportFrames: Int get() = radius

    init {
        val cutoff = 0.94 * ratio // cycles/sample = cutoff/2; 6% Nyquist guard band
        for (phase in 0 until phases) {
            if (phase % 16 == 0) control.checkpoint()
            val fraction = phase.toDouble() / phases
            var sum = 0.0
            for (tap in 0 until taps) {
                val x = tap - radius - fraction
                val window = if (abs(x) >= radius) 0.0 else
                    0.42 + 0.5 * cos(PI * x / radius) + 0.08 * cos(2 * PI * x / radius)
                val sinc = if (abs(x) < 1e-12) cutoff else sin(PI * cutoff * x) / (PI * x)
                val value = sinc * window
                kernels[phase * taps + tap] = value.toFloat()
                sum += value
            }
            for (tap in 0 until taps) kernels[phase * taps + tap] = (kernels[phase * taps + tap] / sum).toFloat()
        }
        control.checkpoint()
    }

    /**
     * Output n samples at exact rational positions firstInputFrame + n*inputRate/outputRate.
     * Symmetric noncausal FIR compensates its own delay; no timestamp shift. Constant extension
     * supplies filter support ONLY at contiguous-region edges, never missing source coverage.
     */
    fun resample(input: FloatArray, count: Int = input.size, firstInputFrame: Double = 0.0,
                 outputFrames: Int = (count.toLong() * outputRate / inputRate).toInt(),
                 control: AnalysisControl = AnalysisControl()): ResampledPcm {
        require(count in 1..input.size && firstInputFrame.isFinite() && firstInputFrame >= 0)
        require(outputFrames >= 0 && outputFrames.toLong() <= 32L * outputRate)
        require(outputFrames == 0 || firstInputFrame + (outputFrames - 1).toDouble() * inputRate / outputRate < count)
        val output = FloatArray(outputFrames)
        var edgeFrames = 0L
        for (i in output.indices) {
            if (i % 256 == 0) control.checkpoint()
            val position = firstInputFrame + i.toDouble() * inputRate / outputRate
            if (inputRate == outputRate && firstInputFrame == 0.0) { output[i] = input[i]; continue }
            var center = floor(position).toInt()
            var phase = ((position - center) * phases).roundToInt()
            if (phase == phases) { center++; phase = 0 }
            val base = phase * taps
            var value = 0.0
            if (center - radius < 0 || center + radius >= count) edgeFrames++
            for (tap in 0 until taps) {
                val source = (center + tap - radius).coerceIn(0, count - 1)
                value += input[source].toDouble() * kernels[base + tap]
            }
            output[i] = value.toFloat()
        }
        control.checkpoint()
        return ResampledPcm(output, edgeFrames)
    }
}

internal data class ResampledPcm(val samples: FloatArray, val edgeSupportedFrames: Long)
