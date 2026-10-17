// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class NativeDjStartupTimingTest {
    private fun reference(h:VirtualNativeDjHost,delay:Long)=NativeDjStartupObservation(
        h.a.source,h.generation,h.now-delay-30_000,h.a.position-30_000,h.now,h.a.position)
    private fun host(delay:Long=130_000,actualDelay:Long=delay):VirtualNativeDjHost {
        val h=VirtualNativeDjHost(mathematicalExecutionPlan());h.now=1_000_000
        h.startup=reference(h,delay);h.b.startDelayUs=actualDelay
        return h
    }
    private fun execute(h:VirtualNativeDjHost)=NativeDjTransitionExecutor(h,{h.now}).also {it.start(h.plan)}
    private fun dispatch(h:VirtualNativeDjHost,e:NativeDjTransitionExecutor,lead:Long) {
        h.advance(e,h.plan.candidate.outgoingCueUs-lead-h.a.position)
    }

    @Test fun delayedIncomingStartsAtOriginalCueAndCompletesFullOverlapWithMeasuredLead() {
        for(delay in listOf(90_000L,120_000L,140_000L)) {
            val h=host(delay);val e=execute(h);dispatch(h,e,delay)
            assertEquals(NativeDjExecutionStage.STARTING,e.status.stage)
            assertEquals(h.plan.candidate.incomingCueUs,h.b.position)
            assertEquals(0.0,h.b.applied,0.0);assertEquals(1.0,h.a.applied,0.0)
            assertEquals(h.plan.candidate.outgoingCueUs-delay,e.measurements().commandOutgoingUs)
            h.advance(e,delay)
            assertEquals(NativeDjExecutionStage.MIXING,e.status.stage)
            h.finish(e)
            assertEquals(NativeDjExecutionStage.COMPLETED,e.status.stage)
            assertEquals(0L,e.measurements().estimatedStartOffsetUs)
            assertEquals(16_000_000L,e.measurements().concurrentProgressUs)
            assertEquals(16_000_000L,e.measurements().plannedOverlapUs)
            assertEquals(1,h.promotions);assertEquals(1,h.a.releaseCount)
            assertFalse(h.b.released);assertTrue(h.b.playing)
            assertEquals(h.plan.candidate.incomingCueUs+16_000_000,h.b.position)
            assertEquals(1.0,h.b.speed,0.0)
        }
    }
    @Test fun smallPredictionErrorIsMeasuredAgainstOriginalAnchorsWithoutRebasing() {
        val h=host(delay=120_000,actualDelay=135_000);val e=execute(h);dispatch(h,e,120_000)
        h.finish(e)
        assertEquals(NativeDjExecutionStage.COMPLETED,e.status.stage)
        assertEquals(15_000L,e.measurements().estimatedStartOffsetUs)
        assertEquals(15_000L,e.measurements().maximumObservedDriftUs)
        assertEquals(120_000L,e.measurements().startupLeadUs)
        assertTrue(e.measurements().concurrentProgressUs>=16_000_000-150_000)
    }
    @Test fun absentObservationStillRejectsTheReportedColdStartDelay() {
        val h=host();h.startup=null;val e=execute(h);dispatch(h,e,0);h.finish(e)
        assertEquals(NativeDjExecutionReason.EXCESSIVE_DRIFT,e.status.reason)
        assertEquals(130_000L,e.measurements().estimatedStartOffsetUs)
        assertEquals(0L,e.measurements().startupLeadUs);assertEquals(0,h.promotions)
        assertTrue(h.b.released);assertTrue(h.a.playing)
    }
    @Test fun incorrectPredictionsStillRejectExcessiveEarlyOrLateAlignment() {
        for((predicted,actual) in listOf(250_000L to 0L,20_000L to 170_000L)) {
            val h=host(predicted,actual);val e=execute(h);dispatch(h,e,predicted);h.finish(e)
            assertEquals(NativeDjExecutionReason.EXCESSIVE_DRIFT,e.status.reason)
            assertTrue(abs(e.measurements().estimatedStartOffsetUs!!)>120_000)
            assertEquals(0,h.promotions);assertTrue(h.b.released)
            assertTrue(h.a.playing);assertEquals(1.0,h.a.applied,0.0)
        }
    }
    @Test fun dispatchMustMeetTheOriginal40msLimitRelativeToItsMeasuredLead() {
        val h=host();val e=execute(h)
        h.advance(e,h.plan.candidate.outgoingCueUs-130_000-h.a.position+40_001)
        assertEquals(NativeDjExecutionReason.START_MISSED,e.status.reason)
        assertFalse(h.b.playing);assertEquals(0,h.promotions)
    }
    @Test fun queueChangesDuringMutedStartingCannotPromoteOrContinue() {
        val h=host();val e=execute(h);dispatch(h,e,130_000);h.generation++;e.tick()
        assertEquals(NativeDjExecutionReason.QUEUE_CHANGED,e.status.reason)
        assertTrue(h.b.released);assertEquals(0,h.promotions);assertTrue(h.a.playing)
    }
    @Test fun pauseBeforeIncomingProgressResumesOutgoingWithoutInventingAlignment() {
        val h=host();val e=execute(h);dispatch(h,e,130_000)
        e.pause();val a=h.a.position;val b=h.b.position;h.advance(e,60_000_000)
        assertEquals(a,h.a.position);assertEquals(b,h.b.position)
        e.resume()
        assertEquals(NativeDjExecutionStage.CANCELED,e.status.stage)
        assertEquals(NativeDjExecutionReason.STARTUP_INTERRUPTED,e.status.reason)
        assertNull(e.measurements().estimatedStartOffsetUs)
        assertEquals(0,h.promotions);assertEquals(1,h.b.releaseCount);assertEquals(0,h.a.releaseCount)
        assertTrue(h.a.playing);assertEquals(1.0,h.a.applied,0.0)
        assertEquals(h.plan.candidate.incomingCueUs,h.b.position)
    }
    @Test fun pauseAfterObservedProgressRetainsTheOriginalAlignmentAndCue() {
        val h=host(delay=130_000,actualDelay=110_000);val e=execute(h);dispatch(h,e,130_000)
        h.advance(e,120_000)
        assertEquals(NativeDjExecutionStage.STARTING,e.status.stage)
        assertEquals(-20_000L,e.measurements().estimatedStartOffsetUs)
        e.pause();val a=h.a.position;val b=h.b.position;h.advance(e,60_000_000)
        assertEquals(a,h.a.position);assertEquals(b,h.b.position)
        h.a.startDelayUs=110_000
        e.resume();h.finish(e)
        assertEquals(NativeDjExecutionStage.COMPLETED,e.status.stage)
        assertEquals(-20_000L,e.measurements().estimatedStartOffsetUs)
        assertEquals(20_000L,e.measurements().maximumObservedDriftUs)
        assertEquals(1,h.promotions);assertEquals(h.plan.candidate.incomingCueUs+16_020_000,h.b.position)
    }
    @Test fun bufferingOrFailureDuringMutedStartingNeverPromotes() {
        for(outgoingFails in listOf(false,true)) {
            val h=host();val e=execute(h);dispatch(h,e,130_000)
            if(outgoingFails)h.a.failed=true else h.b.ready=false
            e.tick()
            assertEquals(if(outgoingFails)NativeDjExecutionReason.PLAYER_FAILED else NativeDjExecutionReason.BUFFERING,e.status.reason)
            assertEquals(0,h.promotions);assertEquals(1,h.b.releaseCount);assertEquals(0,h.a.releaseCount)
            assertEquals(1.0,h.a.applied,0.0)
        }
    }
    @Test fun sourceGenerationAgeOverflowAndUnboundedReferencesCannotSupplyLead() {
        val h=host();val good=h.startup!!;val q=h.queue()
        assertEquals(130_000L,good.leadUs(q,h.now))
        for(bad in listOf(good.copy(source=h.b.source),good.copy(generation=h.generation+1),
            good.copy(observedMonotonicUs=h.now+1),good.copy(commandMonotonicUs=Long.MIN_VALUE,observedMonotonicUs=Long.MAX_VALUE),
            good.copy(commandPositionUs=-1),good.copy(observedPositionUs=good.commandPositionUs),
            good.copy(observedMonotonicUs=good.commandMonotonicUs+280_001),good.copy(observedPositionUs=good.commandPositionUs+100_001))) {
            assertNull(bad.leadUs(q,h.now))
        }
        assertNull(good.leadUs(q,good.observedMonotonicUs+10_000_001))
    }
    @Test fun agedReferenceCannotBeUsedAfterWaitingForPreparationOrAnAnchor() {
        val h=host();h.a.position-=20_000_000;h.startup=reference(h,130_000);val e=execute(h)
        h.advance(e,10_000_001)
        assertEquals(0L,e.measurements().startupLeadUs)
        assertEquals(NativeDjExecutionStage.ARMED,e.status.stage)
    }
    @Test fun pauseBeforeDispatchInvalidatesColdStartPrediction() {
        val h=host();val e=execute(h);e.pause();h.advance(e,400_000);e.resume()
        assertEquals(0L,e.measurements().startupLeadUs)
        assertEquals(NativeDjExecutionStage.ARMED,e.status.stage)
    }
}
