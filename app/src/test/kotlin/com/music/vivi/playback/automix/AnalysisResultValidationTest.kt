package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class AnalysisResultValidationTest {
    private val engine = LegacyKotlinAnalysisEngine()
    private fun window() = AnalysisPcmFixtures.window(AnalysisPcmFixtures.groove(120.0, 8))
    private fun result(w: DecodedWindow) =
        engine.analyze(w, AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Completed

    @Test fun invalidValuesAreQualifiedWithoutMutatingExactLegacyPlannerPayload() {
        val w = window()
        val original = result(w)
        val bad = original.copy(evidence = original.evidence.copy(
            gridConfidence = original.evidence.gridConfidence.copy(value = Double.NaN),
            key = MusicalValue(MusicalKey(12, false, 2.0), EvidenceNature.INFERRED, EvidenceMethod.LEGACY_KEY_PROFILE),
            beatsUs = MusicalValue(listOf(-1L, 5L), EvidenceNature.INFERRED, EvidenceMethod.LEGACY_FIXED_GRID),
            rms = original.evidence.rms.copy(value = -1.0)))
        val checked = AnalysisResultValidation.validate(bad, w, engine.backend, AnalysisDetail.SCAN) as AnalysisResult.Completed
        for (field in listOf(checked.evidence.gridConfidence, checked.evidence.key,
            checked.evidence.beatsUs, checked.evidence.rms)) assertEquals(EvidenceNature.INVALID, field.nature)
        assertTrue(checked.evidence.gridConfidence.value!!.isNaN())
        assertSame(original.legacyFeatures, LegacyPlanningFeatures.require(checked))
    }

    @Test fun metadataAndBackendMismatchesAreStructuredResultValidationFailures() {
        val w = window()
        val original = result(w)
        val bad = listOf(
            original.copy(metadata = original.metadata.copy(backend = AnalysisBackend("other", "1"))),
            original.copy(metadata = original.metadata.copy(source = AnalysisSourceIdentity("other", "source"))),
            original.copy(metadata = original.metadata.copy(coverageStatus = CoverageStatus.UNKNOWN)),
            original.copy(metadata = original.metadata.copy(preprocessingVersion = "other")),
            original.copy(metadata = original.metadata.copy(detail = AnalysisDetail.DETAIL)),
        )
        for (value in bad) {
            val checked = AnalysisResultValidation.validate(value, w, engine.backend, AnalysisDetail.SCAN) as AnalysisResult.Failed
            assertEquals(AnalysisFailureStage.RESULT_VALIDATION, checked.failure.stage)
            assertEquals(AutoMixFailure.SOURCE_CHANGED, checked.failure.category)
        }
    }

    @Test fun incomingBackendEvidenceKeepsItsOwnProvenanceAndCannotReachLegacyPlanner() {
        val w = window()
        val backend = AnalysisBackend("fixture-engine", "future")
        val original = result(w)
        val supplied = original.copy(metadata = original.metadata.copy(backend = backend),
            evidence = original.evidence.copy(confidenceSemantics = "fixture-specific score; no legacy equivalence"),
            legacyFeatures = null)
        val checked = AnalysisResultValidation.validate(supplied, w, backend, AnalysisDetail.SCAN) as AnalysisResult.Completed
        assertEquals(backend, checked.backend)
        assertEquals(supplied.evidence.confidenceSemantics, checked.evidence.confidenceSemantics)
        try { LegacyPlanningFeatures.require(checked); fail() } catch (_: IllegalStateException) {}
    }

    @Test fun pipelineActuallyValidatesEngineResultsBeforeReturningThem() {
        val w = window()
        val actual = result(w)
        val fake = object : AnalysisEngine {
            override val backend = actual.backend
            override fun analyze(window: DecodedWindow, detail: AnalysisDetail, control: AnalysisControl) =
                actual.copy(metadata = actual.metadata.copy(requested = AudioIntervalUs(0, 999)))
        }
        val pipeline = WindowAnalysisPipeline(AnalysisWindowDecoder<Unit> { _, _, _ -> DecodeResult.Decoded(w) },
            fake, PcmPreprocessing.IDENTITY)
        val checked = pipeline.analyze(Unit, DecodeRequest(w.requested, w.identity), AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Failed
        assertEquals("RESULT_CONTEXT_MISMATCH", checked.failure.code)
    }
}
