// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class NativeCuePreservationTest {
    private fun run(duration:Long=180_000_000,tempo:Double=120.0,inTempo:Double=tempo,outPhase:Long=137_000,
                    change:(PlanningWindowEvidence)->PlanningWindowEvidence={it}):NativePlanningOutcome {
        val length=minOf(duration,32_000_000);val origin=duration-length
        val a=change(NativePlanningFixtures.evidence(tempo,origin,"out",phaseUs=outPhase,durationUs=duration,lengthUs=length))
        val b=NativePlanningFixtures.evidence(inTempo,0,"in",durationUs=180_000_000,lengthUs=32_000_000)
        val req=NativePlanningFixtures.request(a,b).copy(earliestOutgoingUs=(origin-1_000_000).coerceAtLeast(0),
            preparationLeadUs=0,placementPolicy=NativePlacementPolicy.PRESERVE_OUTGOING)
        return NativeAwareAutoMixPlanner().plan(req,a,b)
    }
    private fun selected(result:NativePlanningOutcome):NativeTransitionPlan {
        assertTrue("$result",result is NativePlanningOutcome.Selected);return (result as NativePlanningOutcome.Selected).plan
    }
    private fun clipBeats(w:PlanningWindowEvidence,end:Long):PlanningWindowEvidence {
        val r=w.analysis as AnalysisResult.Completed
        val beats=r.evidence.beatsUs.value!!.filter {it<end}
        return w.copy(analysis=r.copy(evidence=r.evidence.copy(beatsUs=r.evidence.beatsUs.copy(value=beats),
            downbeatsUs=r.evidence.downbeatsUs.copy(value=r.evidence.downbeatsUs.value!!.filter {it<end}))),
            signal=w.signal!!.copy(riseEventsUs=beats))
    }
    @Test fun reportedEarlyCueLoses25SecondsAndCannotBeAdmittedWithoutMusicalProof() {
        val old=NativeCuePreservation.describe(163_865_000,132_097_200,6_490_457,147.95433486768678)
        assertEquals(25_277_343L,old.remainingUs)
        assertTrue(old.remainingUs>old.allowedLossUs)
        val plan=selected(run(163_865_000,147.95433486768678,146.44518730720463,outPhase=232_200))
        val fresh=requireNotNull(plan.candidate.placement)
        assertTrue(fresh.remainingUs<old.remainingUs);assertTrue(fresh.remainingUs<=fresh.allowedLossUs)
        assertTrue(fresh.preservedFraction>=.95)
        assertTrue(plan.candidate.alignment.maxResidualUs<=70_000)
        assertEquals(1.0,plan.playbackRate,0.0)
        println("PHASE332_PLACEMENT old=$old selected=${plan.candidate} policy=${plan.placementPolicy}")
    }
    @Test fun strongRhythmicAndInstrumentalEndingsRetainLongSupportedOverlaps() {
        for(duration in listOf(40_000_000L,180_000_000,600_000_000,1_200_000_000)) {
            val p=selected(run(duration));val c=p.candidate
            assertTrue(c.overlapUs>=15_000_000);assertTrue(c.placement!!.remainingUs<=c.placement.allowedLossUs)
            assertTrue(c.outgoingCueUs+c.overlapUs<=duration)
        }
    }
    @Test fun shortAbruptSongHasANearEndCueWithoutInventedExtraDuration() {
        val p=selected(run(8_000_000));assertTrue(p.candidate.placement!!.remainingUs<=400_000)
        assertTrue(p.candidate.overlapUs>=3_000_000)
    }
    @Test fun extendedSilentTailAndDisappearingPercussionPreferFallbackToEarlyCut() {
        for(tail in listOf(8_000_000L,15_000_000,25_000_000)) {
            val result=run(change={clipBeats(it,180_000_000-tail)})
            assertTrue("tail=$tail $result",result is NativePlanningOutcome.Fallback)
            assertEquals(PlanningRejection.EXCESSIVE_OUTGOING_LOSS,(result as NativePlanningOutcome.Fallback).reason)
        }
    }
    @Test fun fadeOutWithoutSupportedLateTransientsDoesNotBecomeInventedPhrasing() {
        val result=run(change={it.copy(signal=it.signal!!.copy(riseEventsUs=it.signal.riseEventsUs.filter {t->t<165_000_000}))})
        assertTrue(result is NativePlanningOutcome.Fallback)
    }
    @Test fun poorLateAlignmentDoesNotWinMerelyBecauseItIsLater() {
        val result=run(change={w ->
            val r=w.analysis as AnalysisResult.Completed
            val beats=r.evidence.beatsUs.value!!.mapIndexed {i,t->t+if(t>175_000_000 && i%3==1)150_000 else 0}
            w.copy(analysis=r.copy(evidence=r.evidence.copy(beatsUs=r.evidence.beatsUs.copy(value=beats))),
                signal=w.signal!!.copy(riseEventsUs=beats))
        })
        if(result is NativePlanningOutcome.Selected) {
            assertTrue(result.plan.candidate.alignment.maxResidualUs<=70_000)
            assertTrue(result.plan.candidate.placement!!.remainingUs<=result.plan.candidate.placement.allowedLossUs)
        } else assertTrue(result is NativePlanningOutcome.Fallback)
    }
    @Test fun incompatibleTemposStillRejectRatherThanForceALateMix() {
        val r=run(180_000_000,120.0,140.0)
        assertEquals(PlanningRejection.INCOMPATIBLE_TEMPO,(r as NativePlanningOutcome.Fallback).reason)
    }
    @Test fun silenceAndInsufficientCoverageRemainHardFailures() {
        val r=run(change={it.copy(signal=it.signal!!.copy(riseEventsUs=emptyList()))})
        assertEquals(PlanningRejection.SIGNAL_NOT_CORROBORATED,(r as NativePlanningOutcome.Fallback).reason)
        val partial=run(change={w ->NativePlanningFixtures.withAnalysis(w){a ->a.copy(metadata=a.metadata.copy(coverageStatus=CoverageStatus.PARTIAL))}})
        assertTrue(partial is NativePlanningOutcome.Fallback)
    }
    @Test fun incomingIntroPreservationRemainsIndependentOfOutgoingPlacement() {
        val p=selected(run());assertTrue(p.candidate.incomingCueUs<=1_000_000)
        assertTrue(p.candidate.uncertainties.containsAll(setOf(PlanningUncertainty.PHRASE_UNKNOWN,PlanningUncertainty.VOCALS_UNKNOWN)))
    }
    @Test fun oldWindowToolsRetainExplicitIntentButCannotMasqueradeAsServicePolicy() {
        val old=mathematicalExecutionPlan()
        assertEquals(NativePlacementPolicy.ANALYZED_REGION,old.placementPolicy)
        val fresh=selected(run());assertEquals(NativePlacementPolicy.PRESERVE_OUTGOING,fresh.placementPolicy)
        assertEquals(NativePlacementPolicy.PRESERVE_OUTGOING,NativePlanningRequest(fresh.outgoing,fresh.incoming,0,0,1,"default-check").placementPolicy)
        assertNotEquals("tinium-native-dj-planner-v1",fresh.plannerVersion)
        assertEquals(PcmPreprocessing.BANDLIMITED,fresh.outgoingProfile.preprocessingVersion)
        assertTrue(fresh.candidate.outgoingCueUs>old.candidate.outgoingCueUs)
    }
    @Test fun executorRechecksPreservationAndRejectsForgedOrStalePlacement() {
        val p=selected(run())
        for(bad in listOf(p.copy(candidate=p.candidate.copy(placement=null)),
            p.copy(candidate=p.candidate.copy(placement=p.candidate.placement!!.copy(remainingUs=0))),
            p.copy(plannerVersion="tinium-native-dj-planner-v1"))) {
            val h=VirtualNativeDjHost(bad);val e=NativeDjTransitionExecutor(h,{h.now});e.start(bad)
            assertEquals(NativeDjExecutionReason.INVALID_PLAN,e.status.reason);assertFalse(h.claimed)
        }
    }
    @Test fun checkedEndpointMathAndThePreservationBudgetAreTransparent() {
        val p=NativeCuePreservation.describe(180_000_000,160_000_000,16_000_000,120.0)
        assertEquals(176_000_000L,p.expectedEndpointUs);assertEquals(4_000_000L,p.allowedLossUs)
        assertEquals(8L,p.endDistancePulses)
        assertThrows(ArithmeticException::class.java){NativeCuePreservation.describe(Long.MAX_VALUE,Long.MAX_VALUE-2,4,120.0)}
        // Existing octave hypotheses may use an effective pulse rate below the
        // measured detector's 60 BPM lower bound. Do not reject that hypothesis
        // just because placement uses its pulse period instead of measured BPM.
        val half=selected(run(180_000_000,119.5,60.0))
        assertEquals(MetricalRelation.OUTGOING_DOUBLE_PULSE,half.candidate.tempo.relation)
        assertEquals(59.75,half.candidate.tempo.effectiveOutgoingBpm,0.0)
        assertTrue(half.candidate.uncertainties.contains(PlanningUncertainty.OCTAVE_HYPOTHESIS_UNVERIFIED))
    }
}
