package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class LegacyAnalysisCharacterizationTest {
    private val engine = LegacyKotlinAnalysisEngine()
    private fun analyze(fixture: AnalysisPcmFixtures.Fixture): TrackFeatures =
        LegacyPlanningFeatures.require(engine.analyze(AnalysisPcmFixtures.window(fixture),
            AnalysisDetail.SCAN, AnalysisControl()))

    @Test fun allRequiredTemposReproduceLegacyResultsAndReportUnmetFutureAccuracyTargets() {
        var failingTargets = 0
        for (bpm in listOf(90.0, 107.0, 120.0, 128.0, 140.0, 150.0, 174.0)) {
            val fixture = AnalysisPcmFixtures.groove(bpm)
            val direct = TrackFeaturesAnalyzer().analyze(fixture.samples, fixture.rate, 0, 30000,
                detail = AnalysisDetail.SCAN)
            val adapted = analyze(fixture)
            assertEquals(direct, adapted)
            val metrics = BeatBenchmark.evaluate(fixture, adapted)
            val failures = FutureAnalysisAccuracyTargets.failures(metrics)
            if (failures.isNotEmpty()) failingTargets++
            println("LEGACY_CHARACTERIZATION bpm=$bpm estimate=" + adapted.bpm +
                " grid=" + adapted.beatConfidence + " phase=" + adapted.phaseConfidence +
                " metrics=$metrics FUTURE_TARGET_FAILURES=$failures")
            // Confirms quantized search, not accuracy. New backends must NOT inherit this requirement.
            assertEquals(6000.0, adapted.bpm * (6000.0 / adapted.bpm).toInt(), 1e-9)
        }
        assertTrue("Known failures must remain visible; no native/DSP fix in this phase", failingTargets >= 3)
    }

    @Test fun legacy128BpmGrooveHasIncorrectTempoAndLongGridDrift() {
        for (seconds in listOf(30, 120)) {
            val fixture = AnalysisPcmFixtures.groove(128.0, seconds)
            val features = analyze(fixture)
            val metrics = BeatBenchmark.evaluate(fixture, features)
            println("LEGACY_128_DRIFT seconds=$seconds estimate=" + features.bpm + " metrics=$metrics")
            assertTrue("Existing defect: tempo error >1%", metrics.tempoErrorPercent!! > 1)
            assertTrue("Existing defect: <.95 F1 even with70ms tolerance", metrics.at70.f1 < 0.95)
            assertTrue("Index-paired drift exposes wrong-period grid", kotlin.math.abs(metrics.accumulatedDriftMs!!) > 70)
            assertTrue(FutureAnalysisAccuracyTargets.failures(metrics).isNotEmpty())
        }
    }

    @Test fun accurateBeatPositionsCanStillHaveConfidenceTooLowForCurrentPlanner() {
        val fixture = AnalysisPcmFixtures.groove(120.0)
        val f = analyze(fixture)
        val metrics = BeatBenchmark.evaluate(fixture, f)
        assertEquals(120.0, f.bpm, 0.0)
        assertEquals(1.0, metrics.at70.f1, 0.0)
        assertTrue("Existing score remains below active candidate gate", f.beatConfidence < 0.65)
    }

    @Test fun phasesLeadingSilenceOffbeatsAndSampleRatesHaveExactAdapterParity() {
        val fixtures = listOf(
            AnalysisPcmFixtures.groove(120.0, 12, phaseSeconds = 0.021),
            AnalysisPcmFixtures.groove(120.0, 12, phaseSeconds = 0.311),
            AnalysisPcmFixtures.groove(120.0, 12, leadingSilenceSeconds = 4.0),
            AnalysisPcmFixtures.groove(120.0, 12, offbeatAccents = true),
            AnalysisPcmFixtures.groove(120.0, 12, rate = 44100),
            AnalysisPcmFixtures.groove(120.0, 12, rate = 48000),
        )
        for (fixture in fixtures) for (detail in AnalysisDetail.entries) {
            val w = AnalysisPcmFixtures.window(fixture, 17_000_000, 216_000_000)
            assertEquals(TrackFeaturesAnalyzer().analyze(fixture.samples, fixture.rate, 17000, 216000, detail = detail),
                LegacyPlanningFeatures.require(engine.analyze(w, detail, AnalysisControl())))
        }
    }

    @Test fun deterministicNonRhythmicControlsAreNotRebrandedAsSuccessfulDjEvidence() {
        for (fixture in listOf(AnalysisPcmFixtures.silence(), AnalysisPcmFixtures.chord(), AnalysisPcmFixtures.noise())) {
            val f = analyze(fixture)
            println("LEGACY_NEGATIVE name=" + fixture.name + " bpm=" + f.bpm +
                " confidence=" + f.beatConfidence + " phase=" + f.phaseConfidence)
            assertEquals(f, analyze(fixture))
            assertTrue(f.beatConfidence < 0.65)
            if (fixture.name == "silence") { assertEquals(0.0, f.bpm, 0.0); assertTrue(f.beatsMs.isEmpty()) }
        }
    }
}

class BeatBenchmarkTest {
    @Test fun duplicatePredictionsCannotMatchOneReferenceBeatTwice() {
        val scores = BeatBenchmark.match(listOf(1_000_000), listOf(999_000, 1_001_000), 70_000)
        assertEquals(1, scores.matches)
        assertEquals(0.5, scores.precision, 0.0)
        assertEquals(1.0, scores.recall, 0.0)
        assertEquals(2.0 / 3, scores.f1, 1e-12)
    }

    @Test fun strictToleranceDoesNotRotatePhaseOrNormalizeOctaves() {
        val ref = listOf(1_000_000L, 1_500_000L, 2_000_000L, 2_500_000L)
        val shifted = ref.map { it + 45_000 }
        assertEquals(1.0, BeatBenchmark.match(ref, shifted, 70_000).f1, 0.0)
        assertEquals(0.0, BeatBenchmark.match(ref, shifted, 30_000).f1, 0.0)
        assertEquals(0.5, BeatBenchmark.match(ref, listOf(1_000_000, 2_000_000), 70_000).recall, 0.0)
        val errors = BeatBenchmark.match(ref, listOf(1_001_000, 1_502_000, 2_003_000, 2_504_000), 70_000)
        assertEquals(2.5, errors.medianErrorMs!!, 0.0)
        assertEquals(4.0, errors.p95ErrorMs!!, 0.0)
    }

    @Test fun perfectFixtureClearsFutureTargetsButOctaveErrorIsReportedSeparately() {
        val fixture = AnalysisPcmFixtures.groove(120.0, 8)
        val perfect = TrackFeatures(8000, 0, 8000, 120.0, 1.0, fixture.beatsUs.map { it / 1000 },
            0.1, 0.5, null, emptyList())
        assertTrue(FutureAnalysisAccuracyTargets.failures(BeatBenchmark.evaluate(fixture, perfect)).isEmpty())
        assertEquals(BeatBenchmark.TempoAmbiguity.HALF_TIME,
            BeatBenchmark.evaluate(fixture, perfect.copy(bpm = 60.0)).ambiguity)
        assertEquals(BeatBenchmark.TempoAmbiguity.DOUBLE_TIME,
            BeatBenchmark.evaluate(fixture, perfect.copy(bpm = 240.0)).ambiguity)
    }
}
