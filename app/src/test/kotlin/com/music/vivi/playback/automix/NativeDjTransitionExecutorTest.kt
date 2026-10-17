// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class NativeDjTransitionExecutorTest {
    private val p get()=mathematicalExecutionPlan()
    private fun run(plan:NativeTransitionPlan=p,change:(VirtualNativeDjHost)->Unit={}):Pair<VirtualNativeDjHost,NativeDjTransitionExecutor> {
        val h=VirtualNativeDjHost(plan);change(h);val e=NativeDjTransitionExecutor(h,{h.now});e.start(plan);return h to e
    }
    @Test fun fullPlanExecutes16SecondsWithBothDecksAndExactlyOnePromotion() {
        val (h,e)=run();assertEquals(NativeDjExecutionStage.ARMED,e.status.stage)
        assertEquals(p.candidate.incomingCueUs,h.b.position);assertFalse(h.b.playing)
        h.toStart(e);assertEquals(NativeDjExecutionStage.MIXING,e.status.stage)
        h.advance(e,8_000_000)
        assertTrue(h.a.playing && h.b.playing);assertTrue(h.a.applied>.3 && h.b.applied>.3)
        h.finish(e)
        assertEquals(NativeDjExecutionStage.COMPLETED,e.status.stage);assertEquals(1,h.promotions)
        assertEquals(1,h.a.releaseCount);assertEquals(0,h.b.releaseCount);assertTrue(h.b.playing)
        assertEquals(1.0,h.b.applied,1e-12);assertTrue(h.b.position>p.candidate.incomingCueUs)
        assertTrue(h.a.position<h.a.duration);assertEquals(16_000_000,e.measurements().concurrentProgressUs)
        repeat(10){e.tick();e.close()};assertEquals(1,h.promotions)
    }
    @Test fun rejectsUnknownExecutorCapabilityWithoutPreparing() {
        val (h,e)=run(p.copy(requiredFutureCapability="unknown"));assertEquals(NativeDjExecutionReason.UNSUPPORTED_CAPABILITY,e.status.reason);assertFalse(h.claimed);assertFalse(h.b.released)
    }
    @Test fun rejectsUnverifiedSignalReadiness(){val (_,e)=run(p.copy(readiness=NativePlanReadiness.TIMING_ONLY_SIGNAL_UNVERIFIED));assertEquals(NativeDjExecutionReason.UNVERIFIED_SIGNAL,e.status.reason)}
    @Test fun rejectsWrongBackendAndLegacyPcmProfiles(){for(profile in listOf(AnalysisProfile(LegacyKotlinAnalysisEngine.BACKEND,PcmPreprocessing.LEGACY),AnalysisProfile(BitChordNativeAnalysisEngine.BACKEND,PcmPreprocessing.BANDLIMITED_V2))){val (_,e)=run(p.copy(outgoingProfile=profile));assertEquals(NativeDjExecutionReason.INVALID_PLAN,e.status.reason)}}
    @Test fun rejectsStaleQueueBeforeClaim(){val (_,e)=run(change={it.generation++});assertEquals(NativeDjExecutionReason.STALE_QUEUE,e.status.reason)}
    @Test fun rejectsInsufficientPreparationLead(){val (_,e)=run(change={it.a.position=p.candidate.outgoingCueUs-1});assertEquals(NativeDjExecutionReason.START_MISSED,e.status.reason)}
    @Test fun rejectsMissingExclusiveOwnership(){val (h,e)=run(change={it.canClaim=false});assertEquals(NativeDjExecutionReason.OWNERSHIP_BUSY,e.status.reason);assertEquals(0,h.restores)}
    @Test fun rejectsPlaybackSpeedChange(){val (_,e)=run(change={it.a.speed=1.01});assertEquals(NativeDjExecutionReason.UNSUPPORTED_RATE,e.status.reason)}
    @Test fun rejectsUnsupportedIncomingSeek(){val (_,e)=run(p.copy(incoming=p.incoming.copy(seekable=false)));assertEquals(NativeDjExecutionReason.NOT_SEEKABLE,e.status.reason)}
    @Test fun rejectsOutsideCoverage(){val q=p;val (_,e)=run(q.copy(incomingAnalyzedInterval=AudioIntervalUs(0,1)));assertEquals(NativeDjExecutionReason.INVALID_PLAN,e.status.reason)}
    @Test fun rejectsMalformedTimingOverflowAndNonfiniteValues(){for(q in listOf(p.copy(candidate=p.candidate.copy(overlapUs=500_000)),p.copy(candidate=p.candidate.copy(alignment=p.candidate.alignment.copy(matchedPulses=10))),p.copy(candidate=p.candidate.copy(tempo=NativeTempoCompatibility.hypotheses(120.0,123.0).first())),p.copy(candidate=p.candidate.copy(incomingCueUs=Long.MAX_VALUE)),p.copy(outgoingRawNativeConfidence=Double.NaN),p.copy(candidate=p.candidate.copy(tempo=p.candidate.tempo.copy(measuredOutgoingBpm=Double.NaN))))){val (_,e)=run(q);assertEquals(NativeDjExecutionReason.INVALID_PLAN,e.status.reason)}}
    @Test fun preparationFailureRestoresOutgoingWithoutStoppingIt(){val (h,e)=run(change={it.prepareFailure=true});assertEquals(NativeDjExecutionReason.PREPARATION_FAILED,e.status.reason);assertTrue(h.a.playing);assertEquals(1,h.restores);assertFalse(h.a.released)}
    @Test fun unreadyDeckNeverStartsAtDeadline(){val (h,e)=run(change={it.incomingReady=false});h.toStart(e);assertEquals(NativeDjExecutionReason.START_MISSED,e.status.reason);assertTrue(h.a.playing);assertTrue(h.b.released);assertEquals(0,h.promotions)}
    @Test fun preparationTimeoutIsBounded(){val (h,e)=run(change={it.incomingReady=false});h.now=10_000_001;e.tick();assertEquals(NativeDjExecutionReason.PREPARATION_TIMEOUT,e.status.reason)}
    @Test fun startMoreThan40msLateNeverClaimsAlignment(){val (h,e)=run();h.advance(e,p.candidate.outgoingCueUs-h.a.position+40_001);assertEquals(NativeDjExecutionReason.START_MISSED,e.status.reason);assertFalse(h.b.playing)}
    @Test fun queueChangeDuringOverlapCancelsWithoutPromoting(){val (h,e)=run();h.toStart(e);h.advance(e,1_000_000);h.generation++;e.tick();assertEquals(NativeDjExecutionReason.QUEUE_CHANGED,e.status.reason);assertEquals(0,h.promotions);assertEquals(1.0,h.a.applied,0.0);assertTrue(h.b.released)}
    @Test fun cancelBeforeStartDoesNotRestartOutgoing(){val (h,e)=run();val position=h.a.position;e.cancel();assertEquals(position,h.a.position);assertTrue(h.a.playing);assertEquals(1,h.b.releaseCount);assertEquals(1,h.restores)}
    @Test fun pauseFreezesPositionsGainsAndResumePreservesRemainingOverlap(){val (h,e)=run();h.toStart(e);h.advance(e,2_000_000);e.pause();val pos=h.b.position;val gain=h.b.applied;h.advance(e,90_000_000);assertEquals(pos,h.b.position);assertEquals(gain,h.b.applied,0.0);e.resume();h.finish(e);assertEquals(NativeDjExecutionStage.COMPLETED,e.status.stage);assertEquals(16_000_000,e.measurements().concurrentProgressUs)}
    @Test fun seekingWhilePausedInvalidatesRatherThanRebasing(){val (h,e)=run();h.toStart(e);e.pause();h.a.position+=1_000_000;e.resume();assertEquals(NativeDjExecutionReason.POSITION_DISCONTINUITY,e.status.reason)}
    @Test fun unexpectedSeekIsDetected(){val (h,e)=run();h.toStart(e);h.b.position+=2_000_000;h.advance(e,10_000);assertEquals(NativeDjExecutionReason.POSITION_DISCONTINUITY,e.status.reason)}
    @Test fun smallAccumulatedDriftIsDetectedWithoutSpeedCorrection(){val (h,e)=run();h.toStart(e);repeat(130){if(!e.terminal){h.b.position-=1000;h.advance(e,10_000)}};assertEquals(NativeDjExecutionReason.EXCESSIVE_DRIFT,e.status.reason);assertEquals(1.0,h.b.speed,0.0)}
    @Test fun incomingUnderrunPreservesOutgoing(){val (h,e)=run();h.toStart(e);h.advance(e,1_000_000);h.b.ready=false;e.tick();assertEquals(NativeDjExecutionReason.BUFFERING,e.status.reason);assertTrue(h.a.playing);assertTrue(h.b.released)}
    @Test fun outgoingFailureAfterAudibleIncomingUsesExplicitRecovery(){val (h,e)=run();h.toStart(e);h.advance(e,8_000_000);h.a.failed=true;e.tick();assertEquals(NativeDjExecutionStage.RECOVERED,e.status.stage);assertEquals(1,h.promotions);assertTrue(h.b.playing)}
    @Test fun outgoingFailureBeforeIncomingIsAudibleDoesNotPromote(){val (h,e)=run();h.a.failed=true;e.tick();assertEquals(NativeDjExecutionStage.FAILED,e.status.stage);assertEquals(0,h.promotions)}
    @Test fun generationChangesInsidePromotionCannotAdvanceQueue(){val (h,e)=run(change={it.promoteAllowed=false});h.toStart(e);h.finish(e);assertEquals(NativeDjExecutionReason.STALE_QUEUE,e.status.reason);assertEquals(0,h.promotions);assertFalse(h.a.released)}
    @Test fun explicitUserAndLifecycleEventsCancelIdempotently(){for(reason in listOf(NativeDjExecutionReason.USER_SEEK,NativeDjExecutionReason.USER_SKIP,NativeDjExecutionReason.ROUTE_CHANGED,NativeDjExecutionReason.SHUTDOWN)){val (h,e)=run();h.toStart(e);e.cancel(reason);e.cancel();e.close();assertEquals(reason,e.status.reason);assertEquals(1,h.b.releaseCount);assertEquals(0,h.promotions)}}
    @Test fun concurrentCancellationAndTickNeverDoublePromoteOrRelease(){repeat(30){val (h,e)=run();h.toStart(e);val pool=Executors.newFixedThreadPool(2);try{val a=pool.submit{e.cancel()};val b=pool.submit{e.tick()};a.get();b.get();assertEquals(1,h.b.releaseCount);assertEquals(0,h.promotions)}finally{pool.shutdownNow()}}}
    @Test fun diagnosticHistoryIsBoundedAndPrivacySafe(){val (h,e)=run();h.toStart(e);h.finish(e,10_000);assertTrue(e.measurements().events.size<=128);val report=e.measurements().toString();assertFalse(report.contains("generated-planning"));assertFalse(report.contains("rendition"))
        val unsafe=p.copy(plannerVersion="https://private-token",outgoingProfile=AnalysisProfile(AnalysisBackend("private-secret","private-version"),"private-url"))
        val (_,invalid)=run(unsafe);assertFalse(invalid.report().toString().contains("private-"))
    }
    @Test fun incomingThatNeverProgressesIsNotCalledASuccess(){val (h,e)=run();h.toStart(e);h.b.playing=false;h.advance(e,1_500_001);assertEquals(NativeDjExecutionReason.START_FAILED,e.status.reason);assertEquals(0,h.promotions)}
    @Test fun incomingSeekDriftBeforeStartIsRejected(){val (h,e)=run();h.b.position+=20_000;e.tick();assertEquals(NativeDjExecutionReason.SEEK_FAILED,e.status.reason);assertEquals(0,h.promotions)}
    @Test fun unexpectedPauseBeforeAnchorCannotLeaveAnArmedTimerRunning(){val (h,e)=run();h.a.pause();e.tick();assertEquals(NativeDjExecutionReason.UNEXPECTED_PAUSE,e.status.reason);assertTrue(e.terminal)}
    @Test fun outgoingBufferingBeforeAnchorPreservesSafeFallback(){val (h,e)=run();h.a.ready=false;e.tick();assertEquals(NativeDjExecutionReason.BUFFERING,e.status.reason);assertEquals(0,h.promotions)}
    @Test fun pauseDuringPreparationDoesNotSpendItsTimeout(){val (h,e)=run(change={it.incomingReady=false});e.pause();h.advance(e,60_000_000);e.resume();h.b.ready=true;e.tick();assertEquals(NativeDjExecutionStage.ARMED,e.status.stage)}
    @Test fun outgoingEosAtThePlannedEndpointCanComplete(){
        val original=p;val end=original.candidate.outgoingCueUs+original.candidate.overlapUs
        val q=original.copy(outgoing=original.outgoing.copy(durationUs=end,cueRegion=AudioIntervalUs(original.outgoing.cueRegion.startUs,end)),outgoingAnalyzedInterval=AudioIntervalUs(original.outgoingAnalyzedInterval.startUs,end))
        val (h,e)=run(q);h.toStart(e);h.a.position=end;h.a.ended=true;h.a.ready=false;h.a.playing=false
        h.b.position=q.candidate.incomingCueUs+q.candidate.overlapUs;h.now+=q.candidate.overlapUs;e.tick()
        assertEquals(NativeDjExecutionStage.COMPLETED,e.status.stage);assertEquals(1,h.promotions)
    }
    @Test fun sourceMismatchCannotAcquireDeckOwnership(){
        val q=p;val h=VirtualNativeDjHost(q)
        val host=object:NativeDjHost by h {override val outgoing=object:NativeDjDeck by h.a {override fun snapshot()=h.a.snapshot().copy(source=AnalysisSourceIdentity("wrong","wrong"))}}
        val e=NativeDjTransitionExecutor(host,{h.now});e.start(q);assertEquals(NativeDjExecutionReason.SOURCE_MISMATCH,e.status.reason);assertFalse(h.claimed)
    }
    @Test fun runtimeRateChangeCancelsWithoutRetiming(){val (h,e)=run();h.toStart(e);h.b.speed=1.02;e.tick();assertEquals(NativeDjExecutionReason.UNSUPPORTED_RATE,e.status.reason);assertEquals(0,h.promotions)}

    @Test fun readyButFrozenMediaClocksCannotLeaveAnOverlapRunningForever(){val (h,e)=run();h.toStart(e);h.advance(e,100_000);h.now+=2_000_001;e.tick();assertEquals(NativeDjExecutionReason.PLAYBACK_STALLED,e.status.reason);assertEquals(0,h.promotions);assertTrue(h.b.released);assertTrue(h.a.playing)}

    @Test fun reportedAndroidColdStartOffsetsCannotBeRelabeledAsAlignedMixes() {
        // Owner-observed offsets from the three failing S25 Ultra tests. isPlaying/READY
        // alone does not prove that the incoming clock has advanced with the outgoing one.
        for (offsetUs in listOf(125_004L,129_004L,139_163L)) {
            val (h,e)=run();h.toStart(e)
            h.now+=offsetUs+1_000
            h.a.position+=offsetUs+1_000
            h.b.position+=1_000
            e.tick()
            assertEquals(NativeDjExecutionStage.FALLBACK,e.status.stage)
            assertEquals(NativeDjExecutionReason.EXCESSIVE_DRIFT,e.status.reason)
            assertEquals(offsetUs,e.measurements().estimatedStartOffsetUs)
            assertEquals(0,h.promotions);assertEquals(1,h.b.releaseCount)
            assertTrue(h.a.playing);assertEquals(1.0,h.a.applied,0.0)
        }
    }

}
