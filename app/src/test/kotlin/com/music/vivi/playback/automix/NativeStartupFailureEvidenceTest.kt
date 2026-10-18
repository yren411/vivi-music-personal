// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class NativeStartupFailureEvidenceTest {
    @Test fun reportedEarlyStartupMismatchRetainsBothOutputsBeforeCleanupAndStillRejects() {
        val h=VirtualNativeDjHost(mathematicalExecutionPlan());h.now=1_000_000
        h.startup=NativeDjStartupObservation(h.a.source,h.generation,h.now-259_556,h.a.position-30_000,h.now,h.a.position,
            null,NativeDjOutputTimestamp(660,1_000_000_000,22050))
        var reads=0
        val beforeRelease=NativeDjOutputTiming(NativeDjOutputTimestamp(216333,2_000_000_000,22050),
            NativeDjOutputTimestamp(0,2_000_000_000,22050),2_000_000)
        val host=object:NativeDjHost by h {
            override fun outputTiming():NativeDjOutputTiming {
                reads++
                return if(h.b.released)beforeRelease.copy(incoming=null) else beforeRelease
            }
        }
        val e=NativeDjTransitionExecutor(host,{h.now});e.start(h.plan)
        h.b.position-=689 // Media3's millisecond-floor seek vs fractional approved cue
        h.advance(e,h.plan.candidate.outgoingCueUs-229_556-h.a.position+3_491)
        assertEquals(NativeDjExecutionStage.STARTING,e.status.stage)
        // At 11,124 us, outgoing +10,000 / incoming +64,000 gives the owner's
        // -279,376 us raw offset. This models reported clocks, NOT acoustic delay.
        h.now+=11_124;h.a.position+=10_000;h.b.position+=64_000;e.tick()
        assertEquals(-279_376L,e.measurements().lastClock!!.rawOffsetUs)
        assertEquals(NativeDjExecutionReason.EXCESSIVE_DRIFT,e.status.reason)
        assertEquals(0,h.promotions);assertTrue(h.b.released);assertTrue(h.a.playing)
        assertEquals(1.0,h.a.applied,0.0)
        val report=e.report()
        assertEquals(beforeRelease,report.outputTiming);assertEquals(1,reads)
        assertEquals(229_556L,report.measurements.startupLeadUs)
        assertNotNull(report.startupReference);assertEquals(660L,report.startupReference!!.observedOutput!!.framePosition)
        assertFalse(report.startupReference.toString().contains(h.a.source.recordingId))
    }
    @Test fun unavailableDiagnosticTimestampNeverPreventsFallbackCleanup() {
        val h=VirtualNativeDjHost(mathematicalExecutionPlan())
        val host=object:NativeDjHost by h {override fun outputTiming():NativeDjOutputTiming?=throw IllegalStateException("Released track")}
        val e=NativeDjTransitionExecutor(host,{h.now});e.start(h.plan);h.toStart(e)
        e.cancel(NativeDjExecutionReason.ROUTE_CHANGED)
        assertEquals(NativeDjExecutionReason.ROUTE_CHANGED,e.status.reason)
        assertNull(e.report().outputTiming);assertEquals(1,h.b.releaseCount);assertEquals(0,h.promotions)
        assertTrue(h.a.playing);assertEquals(1.0,h.a.applied,0.0)
    }
}
