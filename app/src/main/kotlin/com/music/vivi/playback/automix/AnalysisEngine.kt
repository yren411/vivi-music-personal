package com.music.vivi.playback.automix

import java.security.MessageDigest

data class AnalysisBackend(val id: String, val version: String)
data class AnalysisProfile(val backend: AnalysisBackend, val preprocessingVersion: String) {
    /** Length-prefixed identities avoid ambiguity; digest keeps resolver details out of keys. */
    fun cacheKey(identity: AnalysisSourceIdentity, startMs: Long, lengthMs: Long, detail: AnalysisDetail): String {
        val fields = listOf(backend.id, backend.version, preprocessingVersion, identity.recordingId,
            identity.renditionKey, startMs.toString(), lengthMs.toString(), detail.name)
        val bytes = fields.joinToString("") { it.length.toString() + ":" + it }.toByteArray(Charsets.UTF_8)
        return "analysis-v1:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

enum class EvidenceNature { MEASURED, INFERRED, UNAVAILABLE, INVALID }
enum class EvidenceMethod { SUPPLIED_PCM_STATISTIC, LEGACY_FIXED_GRID, LEGACY_ACCENT_HEURISTIC,
    LEGACY_KEY_PROFILE, LEGACY_SPECTRAL_PROXY, NOT_AVAILABLE,
    BITCHORD_TEMPO_TRACKER, BITCHORD_FOUR_BEAT_BASS_ACCENT, BITCHORD_UNCALIBRATED_CONFIDENCE }

/** Method and backend qualify scores; no cross-backend probability equivalence is implied. */
data class MusicalValue<T>(val value: T?, val nature: EvidenceNature, val method: EvidenceMethod) {
    init {
        require(nature != EvidenceNature.UNAVAILABLE || value == null)
        require(nature !in setOf(EvidenceNature.MEASURED, EvidenceNature.INFERRED) || value != null)
    }
}

data class AnalysisEnergyPoint(val positionUs: Long, val rms: Double)
data class AnalysisVocalPoint(val positionUs: Long, val risk: Double)

/** Times are absolute microseconds. These are evidence, not permission to schedule a mix. */
data class MusicalEvidence(
    val tempoBpm: MusicalValue<Double>,
    val beatsUs: MusicalValue<List<Long>>,
    val downbeatsUs: MusicalValue<List<Long>>,
    val gridConfidence: MusicalValue<Double>,
    val phaseConfidence: MusicalValue<Double>,
    val tempoStability: MusicalValue<Double>,
    val gridSupport: MusicalValue<Double>,
    val rms: MusicalValue<Double>,
    val peak: MusicalValue<Double>,
    val key: MusicalValue<MusicalKey>,
    val keyConfidence: MusicalValue<Double>,
    val energy: MusicalValue<List<AnalysisEnergyPoint>>,
    val vocalRisk: MusicalValue<List<AnalysisVocalPoint>>,
    val confidenceSemantics: String,
)

data class AnalysisMetadata(
    val backend: AnalysisBackend,
    val preprocessingVersion: String,
    val requested: AudioIntervalUs,
    val sampleInterval: AudioIntervalUs?,
    val coverage: AudioCoverage,
    val coverageStatus: CoverageStatus,
    val source: AnalysisSourceIdentity,
    val detail: AnalysisDetail,
    val duration: AudioDuration,
    val sourceFormats: List<SourcePcmFormat>,
    val repeatedOutputFrames: Long?,
    val endOfStreamReached: Boolean?,
    val preparation: PcmPreparationMetadata? = null,
    val decoderDiagnostic: DecoderDiagnostic? = null,
    val nativeDiagnostic: NativeAnalysisDiagnostic? = null,
)

sealed interface AnalysisResult {
    val backend: AnalysisBackend
    data class Completed(
        val metadata: AnalysisMetadata,
        val evidence: MusicalEvidence,
        /** Exact legacy payload, not reconstructed/rounded. Other engines omit it. */
        internal val legacyFeatures: TrackFeatures? = null,
    ) : AnalysisResult {
        override val backend get() = metadata.backend
    }
    data class Failed(override val backend: AnalysisBackend, val failure: AnalysisFailure) : AnalysisResult
}

interface AnalysisEngine {
    val backend: AnalysisBackend
    fun analyze(window: DecodedWindow, detail: AnalysisDetail, control: AnalysisControl): AnalysisResult
}

/** Runs synchronously on the EXISTING worker; no executor, nested futures, PCM cache or UI work. */
class WindowAnalysisPipeline<S>(
    private val decoder: AnalysisWindowDecoder<S>,
    private val engine: AnalysisEngine,
    val preprocessingVersion: String,
) {
    val profile get() = AnalysisProfile(engine.backend, preprocessingVersion)

    fun analyze(source: S, request: DecodeRequest, detail: AnalysisDetail, control: AnalysisControl): AnalysisResult {
        var stage = AnalysisFailureStage.DECODING
        return try {
            control.checkpoint()
            when (val decoded = decoder.decode(source, request, control)) {
                is DecodeResult.Failed -> AnalysisResult.Failed(engine.backend, decoded.failure)
                is DecodeResult.Decoded -> {
                    stage = AnalysisFailureStage.INPUT_VALIDATION
                    control.checkpoint()
                    val window = decoded.window
                    if (window.identity != request.identity || window.requested != request.interval ||
                        window.preprocessingVersion != preprocessingVersion) {
                        AnalysisResult.Failed(engine.backend, AnalysisFailure(stage,
                            AutoMixFailure.SOURCE_CHANGED, "WINDOW_IDENTITY_MISMATCH"))
                    } else if (window.preprocessingVersion == PcmPreprocessing.BANDLIMITED &&
                        window.coverage.status(window.decodeInterval) != CoverageStatus.FULL) {
                        AnalysisResult.Failed(engine.backend, AnalysisFailure(stage,
                            AutoMixFailure.INSUFFICIENT_AUDIO_DATA, "PARTIAL_SOURCE_COVERAGE"))
                    } else {
                        stage = AnalysisFailureStage.ANALYSIS
                        val result = engine.analyze(window, detail, control)
                        control.checkpoint()
                        AnalysisResultValidation.validate(result, window, engine.backend, detail)
                    }
                }
            }
        } catch (error: Exception) {
            AnalysisResult.Failed(engine.backend, AnalysisFailure(stage, AutoMixFailure.of(error),
                if (error is AnalysisUnavailable) error.reason else AutoMixFailure.of(error).name, error))
        }
    }
}

/** Only this explicit bridge admits legacy semantics to the unchanged planner. */
object LegacyPlanningFeatures {
    fun require(result: AnalysisResult): TrackFeatures = when (result) {
        is AnalysisResult.Failed -> result.failure.throwLegacy()
        is AnalysisResult.Completed -> {
            check(result.backend == LegacyKotlinAnalysisEngine.BACKEND) {
                "An explicit planning adapter is required for a different analysis backend"
            }
            checkNotNull(result.legacyFeatures) { "Legacy analysis payload missing" }
        }
    }
}
