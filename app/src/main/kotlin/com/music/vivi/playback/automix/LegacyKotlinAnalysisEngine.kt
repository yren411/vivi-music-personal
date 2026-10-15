package com.music.vivi.playback.automix

/** Keeps numerical DSP unchanged. Legacy grid/key/vocal scores remain uncalibrated heuristics. */
class LegacyKotlinAnalysisEngine(private val analyzer: TrackFeaturesAnalyzer = TrackFeaturesAnalyzer()) : AnalysisEngine {
    override val backend = BACKEND

    override fun analyze(window: DecodedWindow, detail: AnalysisDetail, control: AnalysisControl): AnalysisResult {
        val invalid = window.invalidReason()
        if (invalid != null || window.format.channels != 1 || window.sampleTimelineStartUs % 1000 != 0L) {
            return AnalysisResult.Failed(backend, AnalysisFailure(AnalysisFailureStage.INPUT_VALIDATION,
                if (invalid == "EMPTY_PCM") AutoMixFailure.INSUFFICIENT_AUDIO_DATA else AutoMixFailure.UNSUPPORTED_FORMAT,
                invalid ?: "LEGACY_REQUIRES_MONO_MILLISECOND_ORIGIN"))
        }
        return try {
            control.checkpoint()
            // Unknown full-track duration remains 0, never guessed from a window endpoint.
            val features = analyzer.analyze(window.samples, window.format.sampleRateHz!!,
                window.sampleTimelineStartUs / 1000, window.duration.durationUs?.div(1000) ?: 0,
                control.deadlineNanos, detail, control.cancellation)
            control.checkpoint()
            val grid = EvidenceMethod.LEGACY_FIXED_GRID
            fun inferred(value: Double, method: EvidenceMethod = grid, valid: Boolean = value.isFinite()) =
                MusicalValue(value, if (valid) EvidenceNature.INFERRED else EvidenceNature.INVALID, method)
            fun measured(value: Double) = MusicalValue(value,
                if (value.isFinite() && value >= 0) EvidenceNature.MEASURED else EvidenceNature.INVALID,
                EvidenceMethod.SUPPLIED_PCM_STATISTIC)
            fun <T> absent() = MusicalValue<T>(null, EvidenceNature.UNAVAILABLE, EvidenceMethod.NOT_AVAILABLE)
            val bars = features.sections.flatMap { it.downbeatsMs }.distinct().sorted()
            val key = features.key
            val evidence = MusicalEvidence(
                if (features.bpm > 0) inferred(features.bpm) else absent(),
                if (features.beatsMs.isNotEmpty()) MusicalValue(features.beatsMs.map { it * 1000 },
                    EvidenceNature.INFERRED, grid) else absent(),
                if (bars.isNotEmpty()) MusicalValue(bars.map { it * 1000 }, EvidenceNature.INFERRED,
                    EvidenceMethod.LEGACY_ACCENT_HEURISTIC) else absent(),
                inferred(features.beatConfidence, valid = features.beatConfidence in 0.0..1.0),
                inferred(features.phaseConfidence, valid = features.phaseConfidence in 0.0..1.0),
                absent(), // No independent tempo-variation measurement in the legacy algorithm.
                inferred(features.rhythmStability, valid = features.rhythmStability in 0.0..1.0),
                measured(features.rms), measured(features.peak),
                if (key != null) MusicalValue(key,
                    if (key.tonic in 0..11 && key.confidence in 0.0..1.0) EvidenceNature.INFERRED else EvidenceNature.INVALID,
                    EvidenceMethod.LEGACY_KEY_PROFILE) else absent(),
                if (key != null) inferred(key.confidence, EvidenceMethod.LEGACY_KEY_PROFILE,
                    key.confidence in 0.0..1.0) else absent(),
                if (features.energy.isNotEmpty()) MusicalValue(features.energy.map { AnalysisEnergyPoint(it.positionMs * 1000, it.rms) }, EvidenceNature.MEASURED,
                    EvidenceMethod.SUPPLIED_PCM_STATISTIC) else absent(),
                if (features.energy.isNotEmpty()) MusicalValue(features.energy.map { AnalysisVocalPoint(it.positionMs * 1000, it.vocalRisk) },
                    EvidenceNature.INFERRED, EvidenceMethod.LEGACY_SPECTRAL_PROXY) else absent(),
                "legacy-v5: correlation*sqrt(onset-capture)*sqrt(support); phase=onset-capture; " +
                    "stability=grid-support; key=profile-margin*3; scores are not probabilities",
            )
            AnalysisResult.Completed(AnalysisMetadata(backend, window.preprocessingVersion, window.requested,
                window.sampleInterval, window.coverage, window.coverageStatus, window.identity, detail,
                window.duration, window.sourceFormats, window.repeatedOutputFrames, window.endOfStreamReached), evidence, features)
        } catch (error: Exception) {
            AnalysisResult.Failed(backend, AnalysisFailure(AnalysisFailureStage.ANALYSIS,
                AutoMixFailure.of(error), if (error is AnalysisUnavailable) error.reason else
                    if (error is IllegalArgumentException) "INVALID_ANALYZER_INPUT" else AutoMixFailure.of(error).name,
                error))
        }
    }

    companion object {
        val BACKEND = AnalysisBackend("tinium-legacy-kotlin", "5")
        val PRODUCTION_PROFILE = AnalysisProfile(BACKEND, PcmPreprocessing.LEGACY)
    }
}
