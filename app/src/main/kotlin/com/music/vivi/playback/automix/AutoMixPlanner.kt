package com.music.vivi.playback.automix

import kotlin.math.*

/** Conservative analysis-driven policy, independent of the user's Crossfade duration/curve. */
class AutoMixPlanner {
    fun fallback(outgoingDurationMs: Long, incomingDurationMs: Long): AutoMixPlan? {
        if (outgoingDurationMs < 15_000 || incomingDurationMs < 15_000) return null
        return AutoMixPlan(outgoingDurationMs - 2500, 0, 2500)
    }

    fun plan(outgoing: TrackFeatures?, incoming: TrackFeatures?, outgoingDurationMs: Long,
             incomingDurationMs: Long): AutoMixPlan? {
        if (outgoing == null || incoming == null) return fallback(outgoingDurationMs, incomingDurationMs)
        if (outgoingDurationMs < 20_000 || incomingDurationMs < 20_000 || outgoing.peak < 0.001 || incoming.peak < 0.001) return null
        val confident = outgoing.beatConfidence >= 0.65 && incoming.beatConfidence >= 0.65 &&
            outgoing.bpm in 60.0..182.0 && incoming.bpm in 60.0..182.0
        val ratio = if (confident) outgoing.bpm / incoming.bpm else 1.0
        val tempoCompatible = confident && ratio in 0.96..1.04
        val harmonic = compatibleKeys(outgoing.key, incoming.key)
        val outgoingVoice = outgoing.energy.filter { it.positionMs >= outgoingDurationMs - 10_000 }.map { it.vocalRisk }.average().orZero()
        val incomingVoice = incoming.energy.filter { it.positionMs <= 10_000 }.map { it.vocalRisk }.average().orZero()
        val vocalOverlap = outgoingVoice > 0.55 && incomingVoice > 0.55
        val longBlend = tempoCompatible && harmonic && !vocalOverlap
        val duration = when {
            longBlend -> (8 * 60_000 / outgoing.bpm).roundToLong().coerceIn(4000, 10_000)
            vocalOverlap -> 1500L
            !harmonic -> 2000L
            else -> 3500L
        }
        val nominalStart = outgoingDurationMs - duration
        val start = if (longBlend) {
            val beatMs = 60_000 / outgoing.bpm
            val candidates = outgoing.beatsMs.withIndex().filter {
                it.value <= nominalStart && it.value >= max((outgoingDurationMs - 12_000).toDouble(), nominalStart - beatMs * 4)
            }
            candidates.minByOrNull { beat ->
                val region = outgoing.energy.filter { abs(it.positionMs - beat.value) <= 200 }
                val energy = region.map { it.rms }.average().orZero() / outgoing.rms.coerceAtLeast(0.005)
                val voice = region.map { it.vocalRisk }.average().orZero()
                // Energy valleys and lower vocal risk, with only a weak four-beat phrase heuristic.
                // Autocorrelation cannot identify meter/downbeats; this is not a claimed bar detector.
                energy + voice * 0.35 + (nominalStart - beat.value) / 10_000.0 + if (beat.index % 4 == 0) 0.0 else 0.1
            }?.value ?: nominalStart
        } else nominalStart
        // Only skip measured leading silence, never jump deep into an intro to manufacture a blend.
        val firstSound = incoming.energy.firstOrNull { it.rms >= max(0.005, incoming.rms * 0.15) }?.positionMs ?: 0
        val cue = if (longBlend) incoming.beatsMs.firstOrNull { it >= firstSound && it <= 2000 } ?: firstSound.coerceAtMost(2000)
            else firstSound.coerceAtMost(1000)
        val technique = when {
            longBlend && outgoing.rms > 0.025 && incoming.rms > 0.025 -> MixTechnique.BASS_HANDOFF
            longBlend -> MixTechnique.BEAT_BLEND
            !harmonic && !vocalOverlap -> MixTechnique.GENTLE_FILTER
            else -> MixTechnique.ENERGY_FADE
        }
        val quieter = min(outgoing.rms, incoming.rms).coerceAtLeast(0.005)
        return AutoMixPlan(start, cue, (outgoingDurationMs - start).coerceAtMost(12_000),
            if (longBlend) ratio.toFloat() else 1f, technique,
            min(1.0, quieter / outgoing.rms.coerceAtLeast(0.005)),
            min(1.0, quieter / incoming.rms.coerceAtLeast(0.005)))
    }

    fun compatibleKeys(a: MusicalKey?, b: MusicalKey?): Boolean {
        if (a == null || b == null || a.confidence < 0.2 || b.confidence < 0.2) return true // unknown, no claimed key match
        val distance = (b.tonic - a.tonic + 12) % 12
        if (a.minor == b.minor) return distance in setOf(0, 5, 7)
        return if (a.minor) distance == 3 else distance == 9 // relative major/minor
    }

    private fun Double.orZero() = if (isFinite()) this else 0.0
}
