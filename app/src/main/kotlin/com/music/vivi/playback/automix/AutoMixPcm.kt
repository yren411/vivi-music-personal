package com.music.vivi.playback.automix

import kotlin.math.*

data class MixGains(val outgoing: Double, val incoming: Double)

/** Equal-power shapes with worst-case correlation headroom; never boost either track. */
fun mixGains(progress: Double, outgoingLevel: Double = 1.0, incomingLevel: Double = 1.0): MixGains {
    val p = progress.coerceIn(0.0, 1.0)
    val a = cos(p * PI / 2) * outgoingLevel.coerceIn(0.0, 1.0)
    val b = sin(p * PI / 2) * incomingLevel.coerceIn(0.0, 1.0)
    val guard = min(1.0, 0.94 / (a + b).coerceAtLeast(0.001))
    return MixGains(a * guard, b * guard)
}

/** Stateful per-decoder filter/gain kernel used by the real Media3 processor and audio-fixture tests. */
class AutoMixPcm(private val sampleRate: Int, channels: Int) {
    private val low = DoubleArray(channels)
    private var gain = 1.0
    private var bassCut = 0.0
    private var trebleCut = 0.0
    private val bassAlpha = 1 - exp(-2 * PI * 180 / sampleRate)
    private val smoothing = 1 - exp(-1.0 / (sampleRate * 0.008)) // 8ms dezipper
    private val highLow = DoubleArray(channels)
    private val highAlpha = 1 - exp(-2 * PI * 4000 / sampleRate)

    fun reset(initialGain: Double = 1.0) {
        low.fill(0.0); highLow.fill(0.0); gain = initialGain; bassCut = 0.0; trebleCut = 0.0
    }

    fun sample(input: Float, channel: Int, targetGain: Double, targetBassCut: Double, targetTrebleCut: Double): Float {
        if (channel == 0) {
            gain += smoothing * (targetGain.coerceIn(0.0, 1.0) - gain)
            bassCut += smoothing * (targetBassCut.coerceIn(0.0, 0.85) - bassCut)
            trebleCut += smoothing * (targetTrebleCut.coerceIn(0.0, 0.75) - trebleCut)
        }
        val x = if (input.isFinite()) input.toDouble() else 0.0
        low[channel] += bassAlpha * (x - low[channel])
        highLow[channel] += highAlpha * (x - highLow[channel])
        val filtered = x - bassCut * low[channel] - trebleCut * (x - highLow[channel])
        return (filtered.coerceIn(-1.0, 1.0) * gain).toFloat()
    }
}
