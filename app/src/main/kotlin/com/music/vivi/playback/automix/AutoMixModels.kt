/* Independently implemented for Tinium. See docs/AUTOMIX.md for reference and licensing. */
package com.music.vivi.playback.automix

enum class AutoMixPerformance(val workers: Int) { EFFICIENT(1), BALANCED(2), PERFORMANCE(4) }

data class MusicalKey(val tonic: Int, val minor: Boolean, val confidence: Double)
enum class AnalysisDetail { SCAN, DETAIL }
data class RhythmSection(val startMs: Long, val endMs: Long, val bpm: Double, val confidence: Double,
                         val phaseConfidence: Double, val stability: Double, val detailed: Boolean,
                         val downbeatsMs: List<Long> = emptyList(), val downbeatConfidence: Double = 0.0)
data class SectionBoundary(val positionMs: Long, val confidence: Double, val beforeRms: Double,
                           val afterRms: Double, val beforeVocal: Double, val afterVocal: Double)
data class EnergyPoint(val positionMs: Long, val rms: Double, val vocalRisk: Double)
data class TrackFeatures(
    val durationMs: Long,
    val startMs: Long,
    val lengthMs: Long,
    val bpm: Double,
    val beatConfidence: Double,
    val beatsMs: List<Long>,
    val rms: Double,
    val peak: Double,
    val key: MusicalKey?,
    val energy: List<EnergyPoint>,
    val phaseConfidence: Double = beatConfidence,
    val rhythmStability: Double = beatConfidence,
    val sections: List<RhythmSection> = emptyList(),
    val boundaries: List<SectionBoundary> = emptyList(),
)

enum class MixTechnique { BEAT_BLEND, BASS_HANDOFF, GENTLE_FILTER, ENERGY_FADE, SAFE_FADE }
data class AutoMixPlan(
    val outgoingStartMs: Long,
    val incomingCueMs: Long,
    val durationMs: Long,
    val incomingRate: Float = 1f,
    val technique: MixTechnique = MixTechnique.SAFE_FADE,
    val outgoingLevel: Double = 1.0,
    val incomingLevel: Double = 1.0,
    val score: Double = 0.0,
    val reason: String = "insufficient-analysis",
    val phaseConfidence: Double = 0.0,
) {
    init {
        require(outgoingStartMs >= 0 && incomingCueMs >= 0 && durationMs in 500..24_000)
        require(incomingRate.isFinite() && incomingRate in 0.96f..1.04f)
        require(outgoingLevel in 0.0..1.0 && incomingLevel in 0.0..1.0)
    }
}

/** Effective state, without overwriting either user's stored preference. */
data class MixingPolicy(val autoMix: Boolean, val crossfade: Boolean, val offload: Boolean)
fun mixingPolicy(autoMix: Boolean, crossfade: Boolean, offload: Boolean, inSharedRoom: Boolean = false) =
    MixingPolicy(autoMix && !inSharedRoom, crossfade && !autoMix && !inSharedRoom, offload && !autoMix && !crossfade)

internal fun checkAnalysisCancellation(deadlineNanos: Long) {
    if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
        throw java.util.concurrent.CancellationException("AutoMix analysis canceled or timed out")
    }
}
