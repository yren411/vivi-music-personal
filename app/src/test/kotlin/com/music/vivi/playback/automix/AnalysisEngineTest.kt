package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AnalysisEngineTest {
    private fun window() = AnalysisPcmFixtures.window(AnalysisPcmFixtures.groove(120.0, 8))
    private val engine = LegacyKotlinAnalysisEngine()

    @Test fun adapterPreservesEveryLegacyFeatureAndDoesNotMutatePcm() {
        val w = window()
        val original = w.samples.copyOf()
        for (detail in AnalysisDetail.entries) {
            val expected = TrackFeaturesAnalyzer().analyze(w.samples, 22050, 0, 8000, detail = detail)
            val result = engine.analyze(w, detail, AnalysisControl())
            assertEquals(expected, LegacyPlanningFeatures.require(result))
            assertEquals(expected, LegacyPlanningFeatures.require(engine.analyze(w, detail, AnalysisControl())))
        }
        assertArrayEquals(original, w.samples, 0f)
    }

    @Test fun absoluteOffsetsAndFullTrackDurationRemainExact() {
        val w = AnalysisPcmFixtures.window(AnalysisPcmFixtures.groove(120.0, 8), 12_345_000, 216_123_456)
        val direct = TrackFeaturesAnalyzer().analyze(w.samples, 22050, 12345, 216123)
        val result = engine.analyze(w, AnalysisDetail.DETAIL, AnalysisControl()) as AnalysisResult.Completed
        assertEquals(direct, LegacyPlanningFeatures.require(result))
        assertEquals(direct.beatsMs.map { it * 1000 }, result.evidence.beatsUs.value)
        assertEquals(w.requested, result.metadata.requested)
        assertEquals(216_123_456L, result.metadata.duration.durationUs)
        assertEquals(12345L, direct.startMs)
    }

    @Test fun silenceDoesNotInventTempoKeyBeatsOrDownbeats() {
        val w = AnalysisPcmFixtures.window(AnalysisPcmFixtures.silence())
        val result = engine.analyze(w, AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Completed
        assertEquals(LegacyKotlinAnalysisEngine.BACKEND, result.backend)
        assertEquals(PcmPreprocessing.IDENTITY, result.metadata.preprocessingVersion)
        for (value in listOf(result.evidence.tempoBpm, result.evidence.beatsUs, result.evidence.downbeatsUs, result.evidence.key)) {
            assertEquals(EvidenceNature.UNAVAILABLE, value.nature)
            assertNull(value.value)
        }
        assertEquals(EvidenceNature.MEASURED, result.evidence.rms.nature)
        assertEquals(0.0, result.evidence.rms.value!!, 0.0)
        assertEquals(EvidenceNature.UNAVAILABLE, result.evidence.tempoStability.nature)
        assertTrue(result.evidence.confidenceSemantics.contains("not probabilities"))
    }

    @Test fun heuristicsStayInferredAndUnknownDurationIsNotGuessed() {
        val w = window().copy(duration = AudioDuration(null, DurationBasis.UNKNOWN))
        val result = engine.analyze(w, AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Completed
        assertEquals(0L, LegacyPlanningFeatures.require(result).durationMs)
        assertEquals(EvidenceNature.INFERRED, result.evidence.gridConfidence.nature)
        assertEquals(EvidenceNature.INFERRED, result.evidence.vocalRisk.nature)
        assertEquals(EvidenceMethod.LEGACY_FIXED_GRID, result.evidence.beatsUs.method)
        assertEquals(EvidenceNature.UNAVAILABLE, result.evidence.tempoStability.nature)
    }

    @Test fun partialOrUnknownCoverageQualifiesResultsWithoutChangingLegacyAcceptance() {
        val w = window()
        val direct = TrackFeaturesAnalyzer().analyze(w.samples, 22050, 0, 8000, detail = AnalysisDetail.SCAN)
        for (coverage in listOf(AudioCoverage(listOf(AudioIntervalUs(0, 7_900_000)), 174195),
            AudioCoverage(null, null))) {
            val result = engine.analyze(w.copy(coverage = coverage), AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Completed
            assertEquals(direct, LegacyPlanningFeatures.require(result))
            assertEquals(coverage, result.metadata.coverage)
            assertEquals(coverage.status(w.requested), result.metadata.coverageStatus)
        }
    }

    @Test fun deadlineAndExplicitCancellationBecomeStructuredFailuresWithOriginalExceptions() {
        val w = window()
        val expired = engine.analyze(w, AnalysisDetail.SCAN, AnalysisControl(0)) as AnalysisResult.Failed
        assertEquals(AutoMixFailure.ANALYSIS_TIMEOUT, expired.failure.category)
        assertEquals(AnalysisFailureStage.ANALYSIS, expired.failure.stage)
        val token = AnalysisCancellationToken().apply { cancel() }
        val canceled = engine.analyze(w, AnalysisDetail.SCAN, AnalysisControl(cancellation = token)) as AnalysisResult.Failed
        assertEquals(AutoMixFailure.ANALYSIS_CANCELED, canceled.failure.category)
        try { LegacyPlanningFeatures.require(canceled); fail() } catch (_: CancellationException) {}
    }

    @Test fun cancellationIsPolledInsideActualAnalyzerLoops() {
        var polls = 0
        val control = AnalysisControl(cancellation = AnalysisCancellation {
            if (++polls == 100) throw CancellationException("fixture cancellation")
        })
        val result = engine.analyze(window(), AnalysisDetail.DETAIL, control) as AnalysisResult.Failed
        assertEquals(100, polls)
        assertEquals(AutoMixFailure.ANALYSIS_CANCELED, result.failure.category)
    }

    @Test fun threadInterruptRemainsCooperativeAndIsNotCleared() {
        Thread.currentThread().interrupt()
        try {
            val result = engine.analyze(window(), AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Failed
            assertEquals(AutoMixFailure.ANALYSIS_CANCELED, result.failure.category)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun malformedFloatAndShortInputRemainRecoverable() {
        val w = window()
        for (pcm in listOf(FloatArray(2), w.samples.copyOf().apply { this[0] = Float.NaN })) {
            val result = engine.analyze(w.copy(samples = pcm), AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Failed
            assertEquals("INVALID_ANALYZER_INPUT", result.failure.code)
            assertEquals(AutoMixFailure.UNSUPPORTED_FORMAT, result.failure.category)
        }
    }

    @Test fun differentBackendCannotSilentlyFeedLegacyPlanner() {
        val completed = engine.analyze(window(), AnalysisDetail.SCAN, AnalysisControl()) as AnalysisResult.Completed
        val different = completed.copy(metadata = completed.metadata.copy(backend = AnalysisBackend("fixture-native", "1")))
        try { LegacyPlanningFeatures.require(different); fail() } catch (_: IllegalStateException) {}
        try { MusicalValue<Double>(1.0, EvidenceNature.UNAVAILABLE, EvidenceMethod.NOT_AVAILABLE); fail() }
        catch (_: IllegalArgumentException) {}
        val invalid = MusicalValue(Double.NaN, EvidenceNature.INVALID, EvidenceMethod.LEGACY_KEY_PROFILE)
        assertEquals(EvidenceNature.INVALID, invalid.nature)
    }
}

