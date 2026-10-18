// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class NativeClockReliabilityTest {
    private val plan get()=mathematicalExecutionPlan()
    private fun clock(position:Long,before:Long,after:Long=before)=NativeDjClockObservation(
        NativeDjDeckState(plan.outgoing.source,180_000_000,position,true,true),before,after)
    @Test fun sequentialQueriesOfSynchronizedClocksDoNotInventDrift() {
        for(order in listOf(false,true))for(delay in listOf(0L,10_000,30_000,125_000)) {
            val aTime=if(order)1_000_000L+delay else 1_000_000L
            val bTime=if(order)1_000_000L else 1_000_000L+delay
            val evidence=NativeDjClockPair(clock(aTime,aTime),clock(bTime,bTime)).offset(0,0)
            assertEquals(-delay*(if(order)-1 else 1),evidence.rawOffsetUs)
            assertEquals(0L,evidence.commonInstantOffsetUs);assertEquals(delay,evidence.guardedDriftUs)
        }
    }
    @Test fun longQueryBracketsRetainUncertaintyInsteadOfOptimisticallyApproving() {
        val evidence=NativeDjClockPair(clock(1_000_000,900_000,1_100_000),clock(1_000_000,900_000,1_100_000)).offset(0,0)
        assertEquals(0L,evidence.commonInstantOffsetUs);assertEquals(200_000L,evidence.uncertaintyUs)
        assertTrue(evidence.guardedDriftUs>120_000)
    }
    @Test fun real121To127msOffsetsRemainExcessiveRegardlessOfQueryOrder() {
        for(drift in listOf(121_000L,122_000,123_000,127_000)) {
            val evidence=NativeDjClockPair(clock(1_000_000,1_000_000),clock(1_030_000-drift,1_030_000)).offset(0,0)
            assertEquals(drift,evidence.commonInstantOffsetUs);assertEquals(drift,evidence.guardedDriftUs)
        }
    }
    @Test fun executorClassifiesWideQueriesWithoutRelabelingThemAsSuccessfulAlignment() {
        val h=VirtualNativeDjHost(plan)
        var useTimedPair=false
        val host=object:NativeDjHost by h {
            override fun observeClocks(incoming:NativeDjDeck,nowUs:()->Long):NativeDjClockPair {
                if(!useTimedPair)return NativeDjClockPair.capture(h.a,incoming,nowUs)
                // A host test of observations 150ms apart: both advancing at exactly 1x.
                // Old raw difference is -150ms; common-instant difference is zero.
                val a=h.a.snapshot();val b=h.b.snapshot();val now=h.now
                return NativeDjClockPair(NativeDjClockObservation(a,now-150_000,now-150_000),
                    NativeDjClockObservation(b.copy(positionUs=b.positionUs+150_000),now,now))
            }
        }
        val e=NativeDjTransitionExecutor(host,{h.now});e.start(h.plan);h.toStart(e)
        useTimedPair=true;h.advance(e,20_000)
        assertEquals(NativeDjExecutionStage.FALLBACK,e.status.stage)
        assertEquals(NativeDjExecutionReason.CLOCK_OBSERVATION_UNCERTAIN,e.status.reason)
        assertEquals(150_000L,e.measurements().maximumRawOffsetUs)
        assertEquals(150_000L,e.measurements().maximumObservedDriftUs)
        assertEquals(0L,e.measurements().lastClock!!.commonInstantOffsetUs)
        assertEquals(0,h.promotions);assertEquals(1,h.b.releaseCount);assertTrue(h.a.playing)
    }
    @Test fun smallFirstProgressDoesNotHideSubsequentGenuineDivergence() {
        val h=VirtualNativeDjHost(plan);h.b.startDelayUs=10_000
        val e=NativeDjTransitionExecutor(h,{h.now});e.start(h.plan);h.toStart(e)
        h.advance(e,20_000)
        assertEquals(10_000L,e.measurements().estimatedStartOffsetUs)
        h.b.position-=117_000;h.advance(e,200_000)
        assertEquals(NativeDjExecutionReason.EXCESSIVE_DRIFT,e.status.reason)
        assertEquals(127_000L,e.measurements().maximumObservedDriftUs)
        assertEquals(0,h.promotions);assertEquals(1,h.b.releaseCount);assertTrue(h.a.playing)
    }
    @Test fun elapsedQueryTimeAlsoCountsAgainstThe40msCommandGuard() {
        val h=VirtualNativeDjHost(plan);var delayQuery=false
        val host=object:NativeDjHost by h {
            override fun observeClocks(incoming:NativeDjDeck,nowUs:()->Long):NativeDjClockPair {
                val before=h.now;val a=h.a.snapshot();if(delayQuery)h.now+=40_001
                return NativeDjClockPair(NativeDjClockObservation(a,before,before),
                    NativeDjClockObservation(h.b.snapshot(),h.now,h.now))
            }
        }
        val e=NativeDjTransitionExecutor(host,{h.now});e.start(h.plan);delayQuery=true;h.toStart(e)
        assertEquals(NativeDjExecutionReason.CLOCK_OBSERVATION_UNCERTAIN,e.status.reason)
        assertFalse(h.b.playing);assertEquals(0,h.promotions)
        // Also count time spent on prepared-format compatibility AFTER snapshots.
        val g=VirtualNativeDjHost(plan);g.now=1_000_000
        g.startup=NativeDjStartupObservation(g.a.source,g.generation,g.now-30_000,g.a.position-30_000,g.now,g.a.position)
        var slowFormat=false
        val formatHost=object:NativeDjHost by g {
            override fun startupTimingCompatible():Boolean {if(slowFormat)g.now+=40_001;return true}
        }
        val delayed=NativeDjTransitionExecutor(formatHost,{g.now});delayed.start(g.plan)
        slowFormat=true;g.toStart(delayed)
        assertEquals(NativeDjExecutionReason.START_MISSED,delayed.status.reason)
        assertFalse(g.b.playing);assertEquals(0,g.promotions);assertEquals(1,g.b.releaseCount)
    }
    @Test fun uncertaintyCannotHideAnActualOffsetAtTheGuardBoundary() {
        val h=VirtualNativeDjHost(plan);var uncertain=false
        val host=object:NativeDjHost by h {
            override fun observeClocks(incoming:NativeDjDeck,nowUs:()->Long):NativeDjClockPair {
                if(!uncertain)return NativeDjClockPair.capture(h.a,incoming,nowUs)
                return NativeDjClockPair(NativeDjClockObservation(h.a.snapshot(),h.now-3000,h.now),
                    NativeDjClockObservation(h.b.snapshot().copy(positionUs=h.b.position-119_000),h.now-3000,h.now))
            }
        }
        val e=NativeDjTransitionExecutor(host,{h.now});e.start(h.plan);h.toStart(e);uncertain=true;h.advance(e,200_000)
        assertEquals(NativeDjExecutionReason.CLOCK_OBSERVATION_UNCERTAIN,e.status.reason)
        assertTrue(e.measurements().maximumObservedDriftUs>120_000);assertEquals(0,h.promotions)
    }
    @Test fun sequentialSamplingCannotHideARealPotentialDriftPastThe120msGuard() {
        val evidence=NativeDjClockPair(clock(1_000_000,1_000_000),clock(1_029_000,1_150_000)).offset(0,0)
        assertEquals(-29_000L,evidence.rawOffsetUs) // Old raw-only check would approve this.
        assertEquals(121_000L,evidence.commonInstantOffsetUs)
        assertEquals(121_000L,evidence.guardedDriftUs)
        assertEquals(-29_000L,evidence.minimumPossibleOffsetUs)
        assertEquals(121_000L,evidence.maximumPossibleOffsetUs)
    }
    @Test fun invalidBracketsAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException::class.java){clock(0,2,1)}
        assertThrows(IllegalArgumentException::class.java){clock(0,-1,1)}
        assertThrows(ArithmeticException::class.java){NativeDjClockPair(clock(Long.MAX_VALUE,1),clock(0,1)).offset(-1,0)}
    }
}
