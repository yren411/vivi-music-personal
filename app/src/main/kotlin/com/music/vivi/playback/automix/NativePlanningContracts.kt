// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Experimental only: no conversion to AutoMixPlan or injection into the playback controller. */
data class PlanningTrack(
    val source: AnalysisSourceIdentity,
    val durationUs: Long,
    val cueRegion: AudioIntervalUs,
    val seekable: Boolean = true,
)

data class NativePlanningRequest(
    val outgoing: PlanningTrack,
    val incoming: PlanningTrack,
    val earliestOutgoingUs: Long,
    val preparationLeadUs: Long,
    val generation: Long,
    /** Caller/session supplied salt; never emitted. Fixed fixture salts make offline runs reproducible. */
    val diagnosticSalt: String,
) {
    override fun toString()="NativePlanningRequest(generation=$generation, outgoing=$outgoing, incoming=$incoming)"
}

data class NativePlannerConfig(
    val minOverlapUs: Long = 3_000_000,
    val maxOverlapUs: Long = 48_000_000,
    val pulseCounts: List<Int> = listOf(64, 32, 16, 8),
    val maxOutgoingAnchors: Int = 16,
    val maxIncomingAnchors: Int = 16,
    val maxCandidates: Int = 3072,
    val maxReportedCandidates: Int = 12,
    val maxRelativeTempoDifference: Double = 0.03,
    val maxMedianResidualUs: Long = 25_000,
    val maxP95ResidualUs: Long = 40_000,
    val maxResidualUs: Long = 70_000,
    val maxUnverifiedIntroSkipUs: Long = 1_000_000,
) {
    init {
        require(minOverlapUs in 500_000..48_000_000 && maxOverlapUs in minOverlapUs..48_000_000)
        require(pulseCounts.isNotEmpty() && pulseCounts.size <= 4 && pulseCounts.distinct().size == pulseCounts.size)
        require(pulseCounts.all { it in setOf(8, 16, 32, 64) })
        require(maxOutgoingAnchors in 1..32 && maxIncomingAnchors in 1..32)
        require(maxCandidates in 1..12288 && maxReportedCandidates in 1..32)
        require(maxRelativeTempoDifference.isFinite() && maxRelativeTempoDifference in 0.0..0.03)
        require(maxMedianResidualUs in 0..maxP95ResidualUs && maxP95ResidualUs in 0..maxResidualUs && maxResidualUs <= 70_000)
        require(maxUnverifiedIntroSkipUs in 0..1_000_000)
    }
}

enum class PlanningRejection {
    INVALID_REQUEST, SOURCE_MISMATCH, DURATION_MISMATCH, UNSUPPORTED_BACKEND,
    UNSUPPORTED_PREPROCESSING, ANALYSIS_FAILED, MISSING_BEATS, INVALID_EVIDENCE,
    PARTIAL_COVERAGE, UNKNOWN_COVERAGE, SOURCE_DISCONTINUITY, INSUFFICIENT_BEATS,
    TEMPO_GRID_INCONSISTENT, INCOMPATIBLE_TEMPO, CUE_NOT_PRESERVING_INTRO,
    CUE_NOT_SEEKABLE, OUTSIDE_TRACK_OR_COVERAGE, PREPARATION_DEADLINE,
    OVERLAP_UNSUPPORTED, EXCESSIVE_DRIFT, POOR_ALIGNMENT, SIGNAL_NOT_CORROBORATED,
    INVALID_SIGNAL_AUDIT, NO_FEASIBLE_CANDIDATE, CANCELED, DEADLINE_EXCEEDED, QUEUE_CHANGED,
}

enum class PlanningUncertainty {
    UNCALIBRATED_NATIVE_CONFIDENCE, PHRASE_UNKNOWN, VOCALS_UNKNOWN, HARMONY_UNKNOWN,
    METER_UNKNOWN, OCTAVE_HYPOTHESIS_UNVERIFIED, SIGNAL_AUDIT_UNAVAILABLE,
    ACTIVE_OVERLAP_UNKNOWN, LOW_LEVEL_INTRO_CONTENT_UNKNOWN,
}

enum class MetricalRelation { ONE_TO_ONE, INCOMING_DOUBLE_PULSE, OUTGOING_DOUBLE_PULSE }
data class TempoHypothesis(
    val relation: MetricalRelation,
    val outgoingStride: Int,
    val incomingStride: Int,
    val measuredOutgoingBpm: Double,
    val measuredIncomingBpm: Double,
    val effectiveOutgoingBpm: Double,
    val effectiveIncomingBpm: Double,
    val absoluteDifferenceBpm: Double,
    val relativeDifference: Double,
    val periodDifferenceUs: Double,
    /** Diagnostic only, never an instruction to change playback speed. */
    val hypotheticalIncomingRate: Double,
)

data class BeatAlignment(
    val matchedPulses: Int,
    val outgoingStride: Int,
    val incomingStride: Int,
    val initialPhaseOffsetUs: Long,
    val medianResidualUs: Long,
    val p95ResidualUs: Long,
    val maxResidualUs: Long,
    val signedEndDriftUs: Long,
    val predictedTempoDriftUs: Long,
    val deliberatelyUnpairedOutgoingPulses: Int,
    val deliberatelyUnpairedIncomingPulses: Int,
)

/** Lexicographic rank, NOT a probability. Hard timing and signal gates precede ranking. */
data class CandidateRank(
    val corroboratedTracks: Int,
    val octaveHypothesis: Boolean,
    val supportedPulseIntervals: Int,
    val p95ResidualUs: Long,
    val incomingSkipUs: Long,
    val outgoingPreferenceDistanceUs: Long,
    val heuristicDownbeatAnchors: Int,
)

data class NativeTransitionCandidate(
    val outgoingCueUs: Long,
    val incomingCueUs: Long,
    val overlapUs: Long,
    val tempo: TempoHypothesis,
    val alignment: BeatAlignment,
    val rank: CandidateRank,
    val uncertainties: Set<PlanningUncertainty>,
    val outgoingTransientSupport: Double?,
    val incomingTransientSupport: Double?,
)

enum class NativePlanReadiness { TIMING_AND_TRANSIENT_SUPPORTED, TIMING_ONLY_SIGNAL_UNVERIFIED }
data class NativeTransitionPlan(
    val plannerVersion: String,
    val generation: Long,
    val outgoing: PlanningTrack,
    val incoming: PlanningTrack,
    val outgoingProfile: AnalysisProfile,
    val incomingProfile: AnalysisProfile,
    val outgoingAnalyzedInterval: AudioIntervalUs,
    val incomingAnalyzedInterval: AudioIntervalUs,
    val assumedEarliestOutgoingUs: Long,
    val assumedPreparationLeadUs: Long,
    val candidate: NativeTransitionCandidate,
    val readiness: NativePlanReadiness,
    val outgoingRawNativeConfidence: Double,
    val incomingRawNativeConfidence: Double,
    /** Explicit handoff gate; there is deliberately no AutoMixPlan adapter in Phase 3.1. */
    val requiredFutureCapability: String = "PHASE32_NATIVE_PLAN_EXECUTOR",
    val playbackRate: Double = 1.0,
)

data class PlanningDiagnostics(
    val plannerVersion: String,
    val outgoingReportId: String,
    val incomingReportId: String,
    val candidateCount: Int,
    val acceptedCount: Int,
    val rejections: Map<PlanningRejection, Int>,
    val topCandidates: List<NativeTransitionCandidate>,
    val enumerationAndScoringNanos: Long,
    val validationNanos: Long,
    val totalNanos: Long,
    val searchTruncated: Boolean,
    val anchorGenerationNanos: Long = 0,
    val candidateEvaluationNanos: Long = 0,
)

sealed interface NativePlanningOutcome {
    val diagnostics: PlanningDiagnostics
    data class Selected(val plan: NativeTransitionPlan, override val diagnostics: PlanningDiagnostics,
                        val outgoingLevel:NativeDjLevel?=null,val incomingLevel:NativeDjLevel?=null) : NativePlanningOutcome
    /** Recommendation only: ordinary safe playback / independently sourced legacy planning. No fade is executed. */
    data class Fallback(val reason: PlanningRejection, override val diagnostics: PlanningDiagnostics,
                        val analysisFailure: AnalysisFailure? = null) : NativePlanningOutcome
}

/** Compact evidence only; never retains DecodedWindow or PCM. */
data class PlanningWindowEvidence(val analysis: AnalysisResult, val signal: PlanningSignalObservation? = null,
                                  val level:NativeDjLevel?=null)
