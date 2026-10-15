package com.music.vivi.playback.automix

/** Qualifies values without normalizing, inventing confidence or silently changing planner inputs. */
object AnalysisResultValidation {
    fun validate(result: AnalysisResult, window: DecodedWindow, backend: AnalysisBackend,
                 detail: AnalysisDetail): AnalysisResult {
        fun mismatch(code: String) = AnalysisResult.Failed(backend, AnalysisFailure(
            AnalysisFailureStage.RESULT_VALIDATION, AutoMixFailure.SOURCE_CHANGED, code))
        if (result.backend != backend) return mismatch("BACKEND_MISMATCH")
        if (result !is AnalysisResult.Completed) return result
        val m = result.metadata
        if (m.source != window.identity || m.requested != window.requested ||
            m.sampleInterval != window.sampleInterval || m.coverage != window.coverage ||
            m.coverageStatus != window.coverageStatus || m.preprocessingVersion != window.preprocessingVersion ||
            m.detail != detail || m.duration != window.duration || m.sourceFormats != window.sourceFormats ||
            m.repeatedOutputFrames != window.repeatedOutputFrames || m.endOfStreamReached != window.endOfStreamReached) {
            return mismatch("RESULT_CONTEXT_MISMATCH")
        }
        fun <T> qualify(value: MusicalValue<T>, valid: (T) -> Boolean): MusicalValue<T> =
            if (value.value != null && !valid(value.value)) value.copy(nature = EvidenceNature.INVALID) else value
        fun score(value: MusicalValue<Double>) = qualify(value) { it.isFinite() && it in 0.0..1.0 }
        fun nonnegative(value: MusicalValue<Double>) = qualify(value) { it.isFinite() && it >= 0 }
        fun ordered(times: List<Long>): Boolean {
            val span = window.sampleInterval ?: return false
            return times.all { it >= span.startUs && it < span.endUs } &&
                times.zipWithNext().all { (a, b) -> b > a }
        }
        val e = result.evidence
        // Original invalid values remain visible, explicitly INVALID; no arithmetic is changed.
        val qualified = e.copy(
            tempoBpm = qualify(e.tempoBpm) { it.isFinite() && it > 0 },
            beatsUs = qualify(e.beatsUs, ::ordered),
            downbeatsUs = qualify(e.downbeatsUs, ::ordered),
            gridConfidence = score(e.gridConfidence),
            phaseConfidence = score(e.phaseConfidence),
            tempoStability = score(e.tempoStability),
            gridSupport = score(e.gridSupport),
            rms = nonnegative(e.rms),
            peak = nonnegative(e.peak),
            key = qualify(e.key) { it.tonic in 0..11 && it.confidence.isFinite() && it.confidence in 0.0..1.0 },
            keyConfidence = score(e.keyConfidence),
            energy = qualify(e.energy) { points -> ordered(points.map { it.positionUs }) &&
                points.all { it.rms.isFinite() && it.rms >= 0 } },
            vocalRisk = qualify(e.vocalRisk) { points -> ordered(points.map { it.positionUs }) &&
                points.all { it.risk.isFinite() && it.risk in 0.0..1.0 } },
        )
        return if (qualified == e) result else result.copy(evidence = qualified)
    }
}

