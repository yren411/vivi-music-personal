// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

class ExperimentalNativePlanningSessionTest {
    private val fixtures=NativePlanningFixtures
    private val engine=BitChordNativeAnalysisEngine(NativeDspBridge { w,c ->
        // Synthetic grid for scheduling tests; the packet's measured statistics must still
        // describe the real supplied PCM, just as the production JNI protocol requires.
        val measured=PlanningSignalObservation.measure(w,c)
        NativeAnalysisFixtures.packet(w).also {it.scalars[10]=measured.windowRms;it.scalars[11]=measured.windowPeak}
    })
    private val pcm by lazy {fixtures.pcm(120.0)}
    private fun window(id:String,origin:Long)=fixtures.realWindow(pcm,origin,id)
    private fun source(w:DecodedWindow)=PlanningSourceRequest(w,DecodeRequest(w.requested,w.identity),immutableRendition=true)
    private fun request(a:DecodedWindow,b:DecodedWindow,generation:Long=1)=NativePlanningRequest(
        PlanningTrack(a.identity,180_000_000,a.requested),PlanningTrack(b.identity,180_000_000,b.requested),
        140_000_000,5_000_000,generation,"session-fixture")
    private fun await(session:ExperimentalNativePlanningSession<DecodedWindow>, ticket:NativePlanningTicket):NativePlanningOutcome {
        val limit=System.nanoTime()+10_000_000_000
        while(System.nanoTime()<limit) {
            val state=session.poll(ticket)
            if(state is NativePlanningPoll.Finished)return state.outcome
            // Emulates a caller-side UI/controller pump; no worker ever waits for another future.
            LockSupport.parkNanos(1_000_000)
        }
        throw AssertionError("Planning failed to finish")
    }

    @Test fun efficientBalancedAndPerformanceFinishWithoutNestedFutureStarvation() {
        for(mode in AutoMixPerformance.entries) AnalysisWorkers<PlanningWindowEvidence>(mode).use { workers ->
            val calls=AtomicInteger()
            val decoder=AnalysisWindowDecoder<DecodedWindow> {w,_,c ->c.checkpoint();calls.incrementAndGet();DecodeResult.Decoded(w)}
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl()).use { session ->
                val a=window("out",150_000_000);val b=window("in",0)
                val ticket=session.begin(request(a,b),source(a),source(b))
                assertTrue(await(session,ticket) is NativePlanningOutcome.Selected)
                assertEquals(2,calls.get())
                assertEquals(mode,workers.mode)
            }
        }
    }
    @Test fun completedEvidenceIsReusedAfterPairInvalidationWithoutRepeatDecode() {
        AnalysisWorkers<PlanningWindowEvidence>(AutoMixPerformance.EFFICIENT).use { workers ->
            val calls=AtomicInteger();val decoder=AnalysisWindowDecoder<DecodedWindow> {w,_,_ ->calls.incrementAndGet();DecodeResult.Decoded(w)}
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl()).use { session ->
                val a=window("out",150_000_000);val b=window("in",0)
                val first=session.begin(request(a,b),source(a),source(b))
                val plan=(await(session,first) as NativePlanningOutcome.Selected).plan
                session.invalidate(first)
                assertEquals(PlanningRejection.QUEUE_CHANGED,((session.poll(first) as NativePlanningPoll.Finished).outcome as NativePlanningOutcome.Fallback).reason)
                val second=session.begin(request(a,b,2),source(a),source(b))
                val newPlan=(await(session,second) as NativePlanningOutcome.Selected).plan
                assertEquals(plan.candidate,newPlan.candidate)
                assertEquals(2,newPlan.generation)
                assertEquals(2,calls.get())
            }
        }
    }
    @Test fun invalidatingOneSharedAttemptDoesNotCancelAnotherOrDuplicateDecode() {
        AnalysisWorkers<PlanningWindowEvidence>(AutoMixPerformance.BALANCED).use { workers ->
            val entered=CountDownLatch(2);val release=CountDownLatch(1);val calls=AtomicInteger()
            val decoder=AnalysisWindowDecoder<DecodedWindow> {w,_,c ->
                calls.incrementAndGet();entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));c.checkpoint();DecodeResult.Decoded(w)
            }
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl()).use { session ->
                val a=window("out",150_000_000);val b=window("in",0)
                val first=session.begin(request(a,b),source(a),source(b))
                val second=session.begin(request(a,b,2),source(a),source(b))
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                assertTrue(session.poll(second) is NativePlanningPoll.Pending)
                session.invalidate(first)
                release.countDown()
                val p=(await(session,second) as NativePlanningOutcome.Selected).plan
                assertEquals(2,p.generation);assertEquals(2,calls.get())
                assertEquals(PlanningRejection.QUEUE_CHANGED,(await(session,first) as NativePlanningOutcome.Fallback).reason)
            }
        }
    }
    @Test fun replacingIncomingPreservesStillNeededOutgoingAndRejectsOldPair() {
        AnalysisWorkers<PlanningWindowEvidence>(AutoMixPerformance.PERFORMANCE).use { workers ->
            val started=CountDownLatch(2);val release=CountDownLatch(1);val outgoingCalls=AtomicInteger()
            val decoder=AnalysisWindowDecoder<DecodedWindow> {w,_,c ->
                if(w.identity.recordingId=="out")outgoingCalls.incrementAndGet()
                started.countDown();release.await(5,TimeUnit.SECONDS);c.checkpoint();DecodeResult.Decoded(w)
            }
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl()).use { session ->
                val a=window("out",150_000_000);val b=window("in",0);val c=window("replacement",0)
                val first=session.begin(request(a,b),source(a),source(b))
                assertTrue(started.await(5,TimeUnit.SECONDS))
                val next=session.begin(request(a,c,2),source(a),source(c))
                session.invalidate(first);release.countDown()
                val selected=(await(session,next) as NativePlanningOutcome.Selected).plan
                assertEquals(c.identity,selected.incoming.source)
                assertEquals(1,outgoingCalls.get())
                assertEquals(PlanningRejection.QUEUE_CHANGED,(await(session,first) as NativePlanningOutcome.Fallback).reason)
            }
        }
    }
    @Test fun lastObserverCancellationStopsActualAnalysisAndCannotPublishLater() {
        AnalysisWorkers<PlanningWindowEvidence>(AutoMixPerformance.EFFICIENT).use { workers ->
            val entered=CountDownLatch(1);val interrupted=CountDownLatch(1)
            val decoder=AnalysisWindowDecoder<DecodedWindow> {w,_,_ ->
                entered.countDown()
                try {CountDownLatch(1).await(5,TimeUnit.SECONDS)} finally {interrupted.countDown()}
                DecodeResult.Decoded(w)
            }
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl()).use { session ->
                val a=window("out",150_000_000);val b=window("in",0)
                val ticket=session.begin(request(a,b),source(a),source(b))
                assertTrue(entered.await(5,TimeUnit.SECONDS));session.invalidate(ticket)
                assertTrue(interrupted.await(5,TimeUnit.SECONDS))
                assertEquals(PlanningRejection.QUEUE_CHANGED,(await(session,ticket) as NativePlanningOutcome.Fallback).reason)
            }
        }
    }
    @Test fun analysisFailureIsNotCachedAndDeadlineCannotRestartExpensiveWork() {
        AnalysisWorkers<PlanningWindowEvidence>().use { workers ->
            val calls=AtomicInteger()
            val decoder=AnalysisWindowDecoder<DecodedWindow> {_,_,_ ->calls.incrementAndGet();DecodeResult.Failed(
                AnalysisFailure(AnalysisFailureStage.DECODING,AutoMixFailure.DECODER_FAILED,"CONTROLLED_FIXTURE_FAILURE"))}
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl()).use { session ->
                val a=window("out",150_000_000);val b=window("in",0)
                repeat(2) { generation ->
                    val ticket=session.begin(request(a,b,generation.toLong()),source(a),source(b))
                    val result=await(session,ticket) as NativePlanningOutcome.Fallback
                    assertEquals(PlanningRejection.ANALYSIS_FAILED,result.reason)
                    assertEquals(AutoMixFailure.DECODER_FAILED,result.analysisFailure!!.category)
                }
                assertEquals(4,calls.get())
            }
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl(0)).use { session ->
                val a=window("out",150_000_000);val b=window("in",0)
                assertThrows(AnalysisUnavailable::class.java) {session.begin(request(a,b),source(a),source(b))}
                assertEquals(4,calls.get())
            }
        }
    }
    @Test fun mutableRenditionsAreNotRetainedAcrossAttempts() {
        AnalysisWorkers<PlanningWindowEvidence>().use { workers ->
            val calls=AtomicInteger();val decoder=AnalysisWindowDecoder<DecodedWindow> {w,_,_ ->calls.incrementAndGet();DecodeResult.Decoded(w)}
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl()).use { session ->
                val a=window("out",150_000_000);val b=window("in",0)
                repeat(2) {
                    assertTrue(await(session,session.begin(request(a,b,it.toLong()),source(a).copy(immutableRendition=false),
                        source(b).copy(immutableRendition=false))) is NativePlanningOutcome.Selected)
                }
                assertEquals(4,calls.get())
            }
        }
    }
    @Test fun concurrentPairJobsUseOnlyTheExistingOneTwoOrFourWorkers() {
        for(mode in AutoMixPerformance.entries) AnalysisWorkers<PlanningWindowEvidence>(mode).use { workers ->
            val active=AtomicInteger();val maximum=AtomicInteger();val started=CountDownLatch(mode.workers);val release=CountDownLatch(1)
            val decoder=AnalysisWindowDecoder<DecodedWindow> {w,_,c ->
                val current=active.incrementAndGet();maximum.updateAndGet {maxOf(it,current)};started.countDown()
                try { assertTrue(release.await(5,TimeUnit.SECONDS));c.checkpoint();DecodeResult.Decoded(w) }
                finally {active.decrementAndGet()}
            }
            ExperimentalNativePlanningSession(workers,NativePlanningAnalysis(decoder,engine),AnalysisControl()).use { session ->
                val tickets=(0 until 4).map { index ->
                    val a=window("out-$index",150_000_000);val b=window("in-$index",0)
                    session.begin(request(a,b),source(a),source(b))
                }
                try {
                    assertTrue(started.await(5,TimeUnit.SECONDS))
                    assertEquals(mode.workers,maximum.get())
                    assertEquals(8-mode.workers,workers.queuedCount)
                } finally {release.countDown()}
                tickets.forEach { assertTrue(await(session,it) is NativePlanningOutcome.Selected) }
                assertTrue(maximum.get()<=mode.workers)
            }
        }
    }
    @Test fun ticketFromClosedBatchCannotReadOrInvalidateANewPair() {
        AnalysisWorkers<PlanningWindowEvidence>().use {workers ->
            val decoder=AnalysisWindowDecoder<DecodedWindow> {w,_,_ ->DecodeResult.Decoded(w)}
            val analysis=NativePlanningAnalysis(decoder,engine)
            val a=window("out",150_000_000);val b=window("in",0)
            val old=ExperimentalNativePlanningSession(workers,analysis,AnalysisControl()).use {session ->
                session.begin(request(a,b),source(a),source(b)).also {
                    assertTrue(await(session,it) is NativePlanningOutcome.Selected)
                }
            }
            ExperimentalNativePlanningSession(workers,analysis,AnalysisControl()).use {session ->
                val next=session.begin(request(a,b,2),source(a),source(b))
                assertThrows(IllegalArgumentException::class.java) {session.poll(old)}
                session.invalidate(old) // must NOT cancel the new batch's first ticket
                assertEquals(2,((await(session,next) as NativePlanningOutcome.Selected).plan.generation))
            }
        }
    }
}
