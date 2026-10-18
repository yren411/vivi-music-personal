// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class NativeAwareAutoMixPlannerTest {
    private val fixtures=NativePlanningFixtures
    private fun out(bpm:Double=120.0)=fixtures.evidence(bpm)
    private fun incoming(bpm:Double=120.0)=fixtures.evidence(bpm,0,"in")
    private fun plan(a:PlanningWindowEvidence=out(),b:PlanningWindowEvidence=incoming(),
                     request:NativePlanningRequest=fixtures.request(a,b),config:NativePlannerConfig=NativePlannerConfig(),
                     control:AnalysisControl=AnalysisControl())=NativeAwareAutoMixPlanner(config).plan(request,a,b,control)
    private fun selected(r:NativePlanningOutcome):NativeTransitionPlan {
        assertTrue("$r",r is NativePlanningOutcome.Selected)
        return (r as NativePlanningOutcome.Selected).plan
    }
    private fun reason(r:NativePlanningOutcome)=(r as NativePlanningOutcome.Fallback).reason

    @Test fun activeWindowDoesNotRequireQuietOutroAndRanksSeveralPairs() {
        val r=plan() as NativePlanningOutcome.Selected
        val c=r.plan.candidate
        assertEquals(16_000_000,c.overlapUs)
        assertEquals(150_137_000,c.outgoingCueUs)
        assertTrue(c.outgoingCueUs<180_000_000-25_000_000)
        assertEquals(137_000,c.incomingCueUs)
        assertEquals(0,c.alignment.maxResidualUs)
        assertEquals(33,c.alignment.matchedPulses)
        assertTrue(r.diagnostics.acceptedCount>1)
        assertEquals(NativePlanReadiness.TIMING_AND_TRANSIENT_SUPPORTED,r.plan.readiness)
        assertEquals("PHASE32_NATIVE_PLAN_EXECUTOR",r.plan.requiredFutureCapability)
        assertTrue(c.uncertainties.containsAll(setOf(PlanningUncertainty.PHRASE_UNKNOWN,PlanningUncertainty.VOCALS_UNKNOWN)))
        assertEquals(1.0,r.plan.playbackRate,0.0)
    }
    @Test fun offsetIncomingPhaseIsARealCueNotAnAlteredGrid() {
        val b=fixtures.evidence(120.0,0,"in",phaseUs=333_333)
        val c=selected(plan(b=b)).candidate
        assertEquals(333_333,c.incomingCueUs)
        assertEquals(0,c.alignment.initialPhaseOffsetUs)
        assertEquals(0,c.alignment.maxResidualUs)
        assertEquals(333_333,(b.analysis as AnalysisResult.Completed).evidence.beatsUs.value!!.first())
    }
    @Test fun metricalHypothesesRetainMeasuredBpmAndMatchRealStridedPulses() {
        for((a,b,relation) in listOf(Triple(87.0,174.0,MetricalRelation.INCOMING_DOUBLE_PULSE),
            Triple(174.0,87.0,MetricalRelation.OUTGOING_DOUBLE_PULSE))) {
            val c=selected(plan(out(a),incoming(b))).candidate
            assertEquals(relation,c.tempo.relation)
            assertEquals(a,c.tempo.measuredOutgoingBpm,0.0)
            assertEquals(b,c.tempo.measuredIncomingBpm,0.0)
            assertTrue(c.uncertainties.contains(PlanningUncertainty.OCTAVE_HYPOTHESIS_UNVERIFIED))
            assertTrue(c.alignment.deliberatelyUnpairedIncomingPulses+c.alignment.deliberatelyUnpairedOutgoingPulses>0)
            assertTrue(c.alignment.maxResidualUs<=1)
        }
    }
    @Test fun incompatibleFixtureTemposNeverBecomeRateShiftedSuccesses() {
        for((a,b) in listOf(90.0 to 120.0,120.0 to 128.0,128.0 to 140.0,150.0 to 174.0))
            assertEquals(PlanningRejection.INCOMPATIBLE_TEMPO,reason(plan(out(a),incoming(b))))
    }
    @Test fun slightTempoDifferenceShortensOverlapRatherThanConcealingDrift() {
        val c=selected(plan(out(128.0),incoming(129.0))).candidate
        assertTrue(c.overlapUs<10_000_000)
        assertTrue(c.alignment.p95ResidualUs<=40_000)
        assertTrue(kotlin.math.abs(c.alignment.signedEndDriftUs)<=70_000)
        assertNotEquals(1.0,c.tempo.hypotheticalIncomingRate,0.0)
    }
    @Test fun strongBpmAndFirstBeatMatchDoNotConcealLaterDrift() {
        assertEquals(PlanningRejection.EXCESSIVE_DRIFT,reason(plan(out(120.0),incoming(123.0))))
    }
    @Test fun residualsAcrossOverlapRejectLocalInstabilityEvenWhenEndReturnsToPhase() {
        var b=incoming()
        b=fixtures.withAnalysis(b) { r -> r.copy(evidence=r.evidence.copy(beatsUs=r.evidence.beatsUs.copy(value=
            r.evidence.beatsUs.value!!.mapIndexed { i,t -> t+if(i%8 in 2..6) 100_000 else 0 }),
            downbeatsUs=MusicalValue(null,EvidenceNature.UNAVAILABLE,EvidenceMethod.NOT_AVAILABLE))) }
        b=b.copy(signal=null)
        val r=plan(b=b)
        assertTrue(r is NativePlanningOutcome.Fallback)
        assertEquals(PlanningRejection.POOR_ALIGNMENT,reason(r))
    }
    @Test fun leadingQuietEvidenceCanJustifyLaterIncomingEntry() {
        var b=fixtures.evidence(120.0,0,"in",phaseUs=18_137_000)
        b=b.copy(signal=b.signal!!.copy(leadingQuietEndUs=18_000_000))
        val c=selected(plan(b=b)).candidate
        assertEquals(18_137_000,c.incomingCueUs)
        assertEquals(8_000_000,c.overlapUs)
        assertTrue(c.uncertainties.contains(PlanningUncertainty.LOW_LEVEL_INTRO_CONTENT_UNKNOWN))
    }
    @Test fun delayedGridWithoutSilenceEvidenceDoesNotAuthorizeSkippingIntro() {
        val b=fixtures.evidence(120.0,0,"in",phaseUs=18_137_000)
        assertEquals(PlanningRejection.CUE_NOT_PRESERVING_INTRO,reason(plan(b=b)))
    }
    @Test fun unseekableIncomingDoesNotReceiveAnArbitraryCue() {
        val a=out();val b=incoming();val request=fixtures.request(a,b)
        assertEquals(PlanningRejection.CUE_NOT_SEEKABLE,reason(plan(a,b,request.copy(incoming=request.incoming.copy(seekable=false)))))
    }
    @Test fun absentAuditIsExplicitlyUnverifiedInsteadOfPretendingToProveRhythm() {
        val r=selected(plan(out().copy(signal=null),incoming().copy(signal=null)))
        assertEquals(NativePlanReadiness.TIMING_ONLY_SIGNAL_UNVERIFIED,r.readiness)
        assertTrue(r.candidate.uncertainties.contains(PlanningUncertainty.SIGNAL_AUDIT_UNAVAILABLE))
    }
    @Test fun highRawConfidenceCannotOverrideMissingTransientSupport() {
        val a=out().let { it.copy(signal=it.signal!!.copy(riseEventsUs=emptyList())) }
        assertEquals(PlanningRejection.SIGNAL_NOT_CORROBORATED,reason(plan(a)))
    }
    @Test fun unsupportedOrIncompleteEvidenceHasSpecificReasons() {
        val base=out()
        val variants=listOf(
            PlanningRejection.UNKNOWN_COVERAGE to fixtures.withAnalysis(base) { it.copy(metadata=it.metadata.copy(coverage=AudioCoverage(null,null))) },
            PlanningRejection.PARTIAL_COVERAGE to fixtures.withAnalysis(base) { r -> r.copy(metadata=r.metadata.copy(
                coverage=AudioCoverage(listOf(AudioIntervalUs(151_000_000,180_000_000)),1),coverageStatus=CoverageStatus.PARTIAL)) },
            PlanningRejection.UNSUPPORTED_BACKEND to fixtures.withAnalysis(base) { it.copy(metadata=it.metadata.copy(backend=LegacyKotlinAnalysisEngine.BACKEND)) },
            PlanningRejection.UNSUPPORTED_PREPROCESSING to fixtures.withAnalysis(base) { it.copy(metadata=it.metadata.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V2)) },
            PlanningRejection.UNSUPPORTED_PREPROCESSING to fixtures.withAnalysis(base) { it.copy(metadata=it.metadata.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V3)) },
            PlanningRejection.UNSUPPORTED_PREPROCESSING to fixtures.withAnalysis(base) { it.copy(metadata=it.metadata.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED_V4)) },
            PlanningRejection.SOURCE_DISCONTINUITY to fixtures.withAnalysis(base) { it.copy(metadata=it.metadata.copy(repeatedOutputFrames=1)) })
        for((expected,a) in variants) assertEquals(expected,reason(plan(a)))
    }
    @Test fun missingGridsAreDistinguishedFromMalformedValues() {
        for(side in 0..2) {
            fun missing(w:PlanningWindowEvidence)=fixtures.withAnalysis(w) {r -> r.copy(evidence=r.evidence.copy(
                beatsUs=MusicalValue(null,EvidenceNature.UNAVAILABLE,EvidenceMethod.NOT_AVAILABLE))) }
            assertEquals(PlanningRejection.MISSING_BEATS,reason(plan(if(side!=1) missing(out()) else out(),if(side!=0) missing(incoming()) else incoming())))
        }
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,250.0)) {
            val a=fixtures.withAnalysis(out()) { r -> r.copy(evidence=r.evidence.copy(tempoBpm=r.evidence.tempoBpm.copy(value=value))) }
            assertEquals(PlanningRejection.INVALID_EVIDENCE,reason(plan(a)))
        }
    }
    @Test fun malformedBeatOrderDuplicatesAndOutsideCoverageAreRejected() {
        for(beats in listOf(listOf(150_000_000L,150_000_000L),listOf(151_000_000L,150_000_000L),listOf(-1L),List(513){150_000_000L})) {
            val a=fixtures.withAnalysis(out()) { r -> r.copy(evidence=r.evidence.copy(beatsUs=r.evidence.beatsUs.copy(value=beats))) }
            assertEquals(PlanningRejection.INVALID_EVIDENCE,reason(plan(a)))
        }
    }
    @Test fun bpmContradictingTheObservedBeatIntervalsIsRejected() {
        val a=fixtures.withAnalysis(out()) { r ->r.copy(evidence=r.evidence.copy(tempoBpm=r.evidence.tempoBpm.copy(value=174.0))) }
        assertEquals(PlanningRejection.TEMPO_GRID_INCONSISTENT,reason(plan(a)))
    }
    @Test fun sourceAndRenditionMismatchCannotPublishAPlan() {
        val a=out();val b=incoming();val request=fixtures.request(a,b)
        assertEquals(PlanningRejection.SOURCE_MISMATCH,reason(plan(a,b,request.copy(outgoing=request.outgoing.copy(source=AnalysisSourceIdentity("out","different rendition"))))))
        assertEquals(PlanningRejection.DURATION_MISMATCH,reason(plan(a,b,request.copy(outgoing=request.outgoing.copy(durationUs=190_000_000)))))
    }
    @Test fun insufficientLeadTimeAndShortWindowsNeverExtendCoverage() {
        val a=out();val b=incoming();val r=fixtures.request(a,b)
        assertEquals(PlanningRejection.PREPARATION_DEADLINE,reason(plan(a,b,r.copy(earliestOutgoingUs=179_000_000))))
        val short=fixtures.evidence(lengthUs=3_000_000)
        assertEquals(PlanningRejection.INSUFFICIENT_BEATS,reason(plan(short,b)))
    }
    @Test fun overlapLimitsAndPreparationLeadAreActuallyHonored() {
        val a=out();val b=incoming();val r=fixtures.request(a,b).copy(earliestOutgoingUs=159_000_000,preparationLeadUs=2_000_000)
        val c=selected(plan(a,b,r,NativePlannerConfig(maxOverlapUs=8_000_000))).candidate
        assertTrue(c.outgoingCueUs>=161_000_000)
        assertTrue(c.overlapUs<=8_000_000)
        assertTrue(c.outgoingCueUs+c.overlapUs<=180_000_000)
        assertEquals(PlanningRejection.OUTSIDE_TRACK_OR_COVERAGE,reason(plan(a,b,r.copy(
            outgoing=r.outgoing.copy(cueRegion=AudioIntervalUs(0,10_000_000))))))
        assertEquals(PlanningRejection.OUTSIDE_TRACK_OR_COVERAGE,reason(plan(a,b,r.copy(
            incoming=r.incoming.copy(cueRegion=AudioIntervalUs(30_000_000,40_000_000))))))
    }
    @Test fun cancellationInsideEnumerationAndOriginalDeadlineWin() {
        val count=AtomicInteger()
        val control=AnalysisControl(cancellation=AnalysisCancellation { if(count.incrementAndGet()>40) throw CancellationException() })
        val r=plan(control=control)
        assertEquals(PlanningRejection.CANCELED,reason(r))
        assertTrue(r.diagnostics.candidateCount>0)
        assertEquals(PlanningRejection.DEADLINE_EXCEEDED,reason(plan(control=AnalysisControl(0))))
    }
    @Test fun deterministicSelectionDoesNotUseWallClockOrRawScoreAsRank() {
        val first=plan() as NativePlanningOutcome.Selected
        repeat(10) { assertEquals(first.plan,selected(plan())) }
        val a=fixtures.withAnalysis(out()) {r ->r.copy(evidence=r.evidence.copy(gridConfidence=r.evidence.gridConfidence.copy(value=.01)),
            metadata=r.metadata.copy(nativeDiagnostic=r.metadata.nativeDiagnostic!!.copy(rawConfidence=.01))) }
        assertEquals(first.plan.candidate,selected(plan(a)).candidate)
    }
    @Test fun finiteSearchAndReportedCandidateStorageRemainBounded() {
        val r=plan(config=NativePlannerConfig(maxCandidates=7,maxReportedCandidates=2))
        assertEquals(7,r.diagnostics.candidateCount)
        assertTrue(r.diagnostics.searchTruncated)
        assertTrue(r.diagnostics.topCandidates.size<=2)
    }
    @Test fun timestampOverflowAndInvalidRegionsReturnSafeFallback() {
        val a=out();val b=incoming();val r=fixtures.request(a,b)
        assertEquals(PlanningRejection.INVALID_REQUEST,reason(plan(a,b,r.copy(earliestOutgoingUs=Long.MAX_VALUE))))
        for(region in listOf(AudioIntervalUs(-1,10),AudioIntervalUs(10,10),AudioIntervalUs(0,190_000_000)))
            assertEquals(PlanningRejection.INVALID_REQUEST,reason(plan(a,b,r.copy(incoming=r.incoming.copy(cueRegion=region)))))
    }
    @Test fun invalidAuditIsNotTreatedAsMissingEvidence() {
        val a=out().let { it.copy(signal=it.signal!!.copy(source=AnalysisSourceIdentity("other","other"))) }
        assertEquals(PlanningRejection.INVALID_SIGNAL_AUDIT,reason(plan(a)))
    }
    @Test fun privacySafeIdsAreSaltedAndContainNoSourceStrings() {
        val a=out();val b=incoming();val r=fixtures.request(a,b)
        val x=plan(a,b,r).diagnostics;val y=plan(a,b,r.copy(diagnosticSalt="another-session")).diagnostics
        assertNotEquals(x.outgoingReportId,y.outgoingReportId)
        assertEquals(24,x.outgoingReportId.length)
        assertFalse(x.toString().contains("generated-planning"))
        assertEquals("redacted-invalid-context",plan(a,b,r.copy(diagnosticSalt="")).diagnostics.outgoingReportId)
    }
    @Test fun configurationCannotExceedExistingPlaybackOrResourceBounds() {
        assertThrows(IllegalArgumentException::class.java) {NativePlannerConfig(maxOverlapUs=49_000_000)}
        assertThrows(IllegalArgumentException::class.java) {NativePlannerConfig(maxCandidates=100000)}
        assertThrows(IllegalArgumentException::class.java) {NativePlannerConfig(maxRelativeTempoDifference=Double.NaN)}
    }
    @Test fun supported64PulseBlendStaysWithinCurrent48SecondPlanAndRealCoverage() {
        val a=fixtures.evidence(128.0,148_000_000,lengthUs=32_000_000)
        val b=fixtures.evidence(128.0,0,"in",lengthUs=32_000_000)
        val c=selected(plan(a,b)).candidate
        assertEquals(64,c.rank.supportedPulseIntervals)
        assertEquals(30_000_000,c.overlapUs)
        assertTrue(c.outgoingCueUs+c.overlapUs<180_000_000)
        assertTrue(c.incomingCueUs+c.overlapUs<32_000_000)
    }
    @Test fun highScoreWrongOctaveRemainsUnverifiedAndNoMeasuredBpmIsCorrected() {
        val c=selected(plan(out(87.0),incoming(174.0))).candidate
        assertEquals(87.0,c.tempo.measuredOutgoingBpm,0.0)
        assertTrue(c.uncertainties.contains(PlanningUncertainty.OCTAVE_HYPOTHESIS_UNVERIFIED))
        assertTrue(c.uncertainties.contains(PlanningUncertainty.METER_UNKNOWN))
    }
    @Test fun validLargeTimelineRetainsExactCueOffsets() {
        val origin=Long.MAX_VALUE-30_000_000
        val a=fixtures.evidence(originUs=origin,durationUs=Long.MAX_VALUE)
        val c=selected(plan(a,incoming())).candidate
        assertEquals(137_000,c.outgoingCueUs-origin)
        assertEquals(0,c.alignment.maxResidualUs)
    }
    @Test fun unsupportedDownbeatClaimsAndInvalidNativeScoresAreRejected() {
        val a=fixtures.withAnalysis(out()) { r->r.copy(evidence=r.evidence.copy(downbeatsUs=r.evidence.downbeatsUs.copy(
            value=listOf(150_138_000)))) }
        assertEquals(PlanningRejection.INVALID_EVIDENCE,reason(plan(a)))
        val bad=fixtures.withAnalysis(out()) {r->r.copy(evidence=r.evidence.copy(gridConfidence=r.evidence.gridConfidence.copy(value=Double.NaN))) }
        assertEquals(PlanningRejection.INVALID_EVIDENCE,reason(plan(bad)))
    }
    @Test fun nativeCancellationAndTimeoutAreNotCollapsedIntoTechnicalFailure() {
        for((category,expected) in listOf(AutoMixFailure.ANALYSIS_CANCELED to PlanningRejection.CANCELED,
            AutoMixFailure.ANALYSIS_TIMEOUT to PlanningRejection.DEADLINE_EXCEEDED)) {
            val request=fixtures.request()
            val failed=PlanningWindowEvidence(AnalysisResult.Failed(BitChordNativeAnalysisEngine.BACKEND,
                AnalysisFailure(AnalysisFailureStage.ANALYSIS,category,category.name)))
            assertEquals(expected,reason(plan(failed,incoming(),request)))
        }
    }
    @Test fun measuredSignalStatisticsCannotBeNonfiniteOrBelongToDifferentPcm() {
        val nan=fixtures.withAnalysis(out()) {r->r.copy(evidence=r.evidence.copy(rms=r.evidence.rms.copy(value=Double.NaN))) }
        assertEquals(PlanningRejection.INVALID_EVIDENCE,reason(plan(nan)))
        val other=out().let {it.copy(signal=it.signal!!.copy(windowRms=.4))}
        assertEquals(PlanningRejection.INVALID_SIGNAL_AUDIT,reason(plan(other)))
    }
    @Test fun legitimateEosClampPreservesFullEffectiveCoverageWithoutClaimingFullRequest() {
        var a=fixtures.evidence(originUs=3_000_000,durationUs=7_500_000,lengthUs=4_500_000)
        a=fixtures.withAnalysis(a) {r->r.copy(metadata=r.metadata.copy(requested=AudioIntervalUs(3_000_000,9_000_000),
            coverageStatus=CoverageStatus.PARTIAL,endOfStreamReached=true))}
        val b=incoming();val r=fixtures.request(a,b).copy(earliestOutgoingUs=0,preparationLeadUs=0)
        val c=selected(plan(a,b,r)).candidate
        assertTrue(c.outgoingCueUs+c.overlapUs<=7_500_000)
    }
}
