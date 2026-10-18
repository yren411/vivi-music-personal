// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

class NativePlannerJniHostTest {
    private lateinit var engine:AnalysisEngine
    @Before fun realLibrary() {
        assumeTrue("Optional real host JNI library not configured",!System.getProperty("tinium.hostNativeLibrary").isNullOrBlank())
        engine=BitChordNativeAnalysisEngine()
    }
    @Test fun actualNativeEvidenceSupportsSevenCompatiblePairs() {
        for(bpm in listOf(90.0,107.0,120.0,128.0,140.0,150.0,174.0)) NativePlannerJniChecks.compatiblePair(engine,bpm)
    }
    @Test fun nativeFalseGridsDoNotAutomaticallyBecomeSupportedPlans()=NativePlannerJniChecks.nonRhythmicMaterialIsRejected(engine)
    @Test fun realLeadingSilenceCanJustifyALaterCue()=NativePlannerJniChecks.laterCueRequiresRealQuietPrefix(engine)
    @Test fun efficientTwoTrackCoordinatorExecutesActualJniAndReusesCompletedEvidence() {
        AnalysisWorkers<PlanningWindowEvidence>(AutoMixPerformance.EFFICIENT).use {workers ->
            val calls=AtomicInteger()
            val decoder=AnalysisWindowDecoder<DecodedWindow> {w,_,_ ->calls.incrementAndGet();DecodeResult.Decoded(w)}
            val a=NativePlanningFixtures.realWindow(NativePlanningFixtures.pcm(),150_000_000,"out")
            val b=NativePlanningFixtures.realWindow(NativePlanningFixtures.pcm(phase=.333),0,"in")
            val request=NativePlanningRequest(PlanningTrack(a.identity,180_000_000,a.requested),PlanningTrack(b.identity,180_000_000,b.requested),
                140_000_000,5_000_000,1,"real-jni-coordinator-fixture",placementPolicy=NativePlacementPolicy.ANALYZED_REGION)
            fun source(w:DecodedWindow)=PlanningSourceRequest(w,DecodeRequest(w.requested,w.identity),immutableRendition=true)
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl()).use {session ->
                repeat(2) {generation ->
                    val ticket=session.begin(request.copy(generation=generation.toLong()),source(a),source(b))
                    val deadline=System.nanoTime()+10_000_000_000
                    var outcome:NativePlanningOutcome?=null
                    while(outcome==null && System.nanoTime()<deadline) {
                        val state=session.poll(ticket)
                        if(state is NativePlanningPoll.Finished)outcome=state.outcome else LockSupport.parkNanos(1_000_000)
                    }
                    assertTrue("$outcome",outcome is NativePlanningOutcome.Selected)
                    assertEquals(generation.toLong(),(outcome as NativePlanningOutcome.Selected).plan.generation)
                    session.invalidate(ticket)
                }
                assertEquals(2,calls.get())
            }
        }
    }
}
