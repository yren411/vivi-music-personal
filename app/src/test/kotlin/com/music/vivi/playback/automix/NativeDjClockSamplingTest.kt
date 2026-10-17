// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class NativeDjClockSamplingTest {
    @Test fun independentlyQuantizedClocksRetainFullProgressAndDoNotTripTheWatchdog() {
        verifyQuantizedClocks(20_000,20_000,10_000)
    }
    @Test fun completedMixDoesNotLoseProgressBetweenOccasionalCoincidentClockUpdates() {
        // Some ticks advance both clocks (so the old watchdog survives), but summing
        // their coincident deltas still discards much of the actual 16-second overlap.
        verifyQuantizedClocks(20_000,30_000,0)
    }
    private fun verifyQuantizedClocks(outPeriod:Long,inPeriod:Long,inPhase:Long) {
        val h=VirtualNativeDjHost(mathematicalExecutionPlan())
        // Both underlying clocks run continuously; only their snapshot cadence differs.
        val out=object:NativeDjDeck by h.a {
            override fun snapshot()=h.a.snapshot().let {
                it.copy(positionUs=it.positionUs-h.now%outPeriod)
            }
        }
        val next=object:NativeDjDeck by h.b {
            override fun snapshot()=h.b.snapshot().let {
                if(!it.playing)it else it.copy(positionUs=it.positionUs-(h.now+inPhase)%inPeriod)
            }
        }
        val host=object:NativeDjHost by h {
            override val outgoing=out
            override fun prepare(plan:NativeTransitionPlan):NativeDjDeck {h.prepare(plan);return next}
            override fun promote(expected:NativeDjQueue,incoming:NativeDjDeck)=h.promote(expected,h.b)
        }
        val e=NativeDjTransitionExecutor(host,{h.now});e.start(h.plan)
        h.finish(e)
        assertEquals(e.report().toString(),NativeDjExecutionStage.COMPLETED,e.status.stage)
        assertTrue(e.report().toString(),e.measurements().maximumObservedDriftUs<=maxOf(outPeriod,inPeriod))
        assertTrue(e.report().toString(),e.measurements().concurrentProgressUs>=h.plan.candidate.overlapUs-maxOf(outPeriod,inPeriod))
        assertEquals(1,h.promotions);assertEquals(1,h.a.releaseCount)
        assertTrue(h.b.playing);assertFalse(h.b.released)
    }
}
