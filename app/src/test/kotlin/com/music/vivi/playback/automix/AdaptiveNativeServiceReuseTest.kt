// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

class AdaptiveNativeServiceReuseTest {
    private data class Source(val id:AnalysisSourceIdentity,val duration:Long=180_000_000)
    private class Host:NativeAutoMixServiceHost<Source> {
        var current:NativeServicePair<Source>?=null
        var legacy=false;var latest=AutoMixStatus();var offline=false
        var virtual:VirtualNativeDjHost?=null;var executor:NativeDjTransitionExecutor?=null
        override fun pair()=current
        override fun incompatibility():String?=null
        override fun cachedAnalysisSource(source:Source)=source.takeIf {offline}
        override fun legacyEnabled(enabled:Boolean){legacy=enabled}
        override fun execute(plan:NativeTransitionPlan,outgoingLevel:NativeDjLevel?,incomingLevel:NativeDjLevel?):NativeDjTransitionExecutor {
            check(!legacy)
            return VirtualNativeDjHost(plan).also {virtual=it;it.a.position=current!!.positionUs}.let {v ->
                NativeDjTransitionExecutor(v,{v.now}).also {executor=it;it.start(plan,outgoingLevel,incomingLevel)}
            }
        }
        override fun cancel(reason:NativeDjExecutionReason){executor?.cancel(reason);executor=null}
        override fun pause(){executor?.pause()};override fun resume(){executor?.resume()};override fun refreshVolume(){}
        override fun status(value:AutoMixStatus){latest=value}
    }
    private fun pair():NativeServicePair<Source> {
        val a=Source(AnalysisSourceIdentity("out","source-v1"));val b=Source(AnalysisSourceIdentity("in","source-v1"))
        return NativeServicePair(NativeDjQueue(1,a.id,b.id),PlanningTrack(a.id,a.duration,AudioIntervalUs(148_000_000,a.duration)),
            PlanningTrack(b.id,b.duration,AudioIntervalUs(0,32_000_000)),a,b,0,true,true,true)
    }
    private fun controller(h:Host,calls:AtomicInteger=AtomicInteger(),before:((AnalysisControl)->Unit)?=null):NativeAutoMixServiceController<Source> {
        val decoder=AnalysisWindowDecoder<Source>{s,r,c ->
            calls.incrementAndGet();before?.invoke(c);c.checkpoint()
            val samples=NativePlanningFixtures.pcm(120.0,seconds=((r.interval.endUs-r.interval.startUs)/1_000_000).toInt())
            DecodeResult.Decoded(NativeAnalysisFixtures.window(samples,r.interval.startUs).copy(identity=s.id,
                duration=AudioDuration(s.duration,DurationBasis.FIXTURE)))
        }
        val engine=BitChordNativeAnalysisEngine(NativeDspBridge {w,c ->
            val signal=PlanningSignalObservation.measure(w,c)
            NativeAnalysisFixtures.packet(w).also {it.scalars[10]=signal.windowRms;it.scalars[11]=signal.windowPeak}
        })
        return NativeAutoMixServiceController(h,NativePlanningAnalysis(decoder,engine,adaptive=true),AnalysisWorkers(),AutoMixRecorder()).also {
            it.configure(true,AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL,AutoMixPerformance.BALANCED)
        }
    }
    private fun await(c:NativeAutoMixServiceController<*>,condition:()->Boolean) {
        val deadline=System.nanoTime()+10_000_000_000
        while(!condition() && System.nanoTime()<deadline) {c.tick();LockSupport.parkNanos(100_000)}
        assertTrue("Adaptive service pump did not finish",condition())
    }
    private fun invalidate(h:Host,c:NativeAutoMixServiceController<Source>,reason:NativeDjExecutionReason,position:Long=0) {
        h.current=h.current!!.let {it.copy(queue=it.queue.copy(generation=it.queue.generation+1),positionUs=position)}
        c.invalidate(reason)
        assertNull(c.confirmedPlan);assertNull(h.latest.nativePlan)
    }
    @Test fun earlierForwardBeginningAndRepeatedSamePositionSeeksReuseCompletedEvidence() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            await(c){c.confirmedPlan!=null}
            for(position in listOf(60_000_000L,30_000_000,90_000_000,90_000_000,0)) {
                invalidate(h,c,NativeDjExecutionReason.USER_SEEK,position)
                await(c){c.confirmedPlan!=null}
                assertEquals(2,calls.get());assertTrue(c.confirmedPlan!!.candidate.outgoingCueUs>position)
            }
            assertEquals(5L,c.analysisReuse.seekReplans);assertTrue(c.analysisReuse.workers.cacheHits>=10)
        }
    }
    @Test fun seekDuringAnalysisRejoinsBoundedWorkInsteadOfDecodingTwice() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger();val started=CountDownLatch(2);val release=CountDownLatch(1)
        controller(h,calls){c->started.countDown();check(release.await(3,TimeUnit.SECONDS));c.checkpoint()}.use {c ->
            c.tick();assertTrue(started.await(3,TimeUnit.SECONDS))
            invalidate(h,c,NativeDjExecutionReason.USER_SEEK,20_000_000);c.tick()
            release.countDown();await(c){c.confirmedPlan!=null}
            assertEquals(2,calls.get());assertTrue(c.analysisReuse.workers.coalesced>=2)
            assertEquals(2L,c.confirmedPlan!!.generation)
        }
    }
    @Test fun routeReplanRetainsSourceEvidenceButUsesNewScheduleGeneration() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            await(c){c.confirmedPlan!=null};val old=c.confirmedPlan!!
            invalidate(h,c,NativeDjExecutionReason.ROUTE_CHANGED);await(c){c.confirmedPlan!=null}
            assertEquals(2,calls.get());assertNotEquals(old.generation,c.confirmedPlan!!.generation)
            assertEquals(1L,c.analysisReuse.routeReplans)
        }
    }
    @Test fun seekPastCueRemovesMarkerAndCannotExecuteStalePlan() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            await(c){c.confirmedPlan!=null};val cue=c.confirmedPlan!!.candidate.outgoingCueUs
            invalidate(h,c,NativeDjExecutionReason.USER_SEEK,cue+1_000_000)
            // A later safe cue may exist: cancellation is mandatory; forced fallback is not.
            await(c){h.legacy || c.confirmedPlan!=null}
            c.confirmedPlan?.let {assertTrue(it.candidate.outgoingCueUs>=h.current!!.positionUs+it.assumedPreparationLeadUs)}
            assertNull(h.executor);assertEquals(2,calls.get())
        }
    }
    @Test fun activeSeekCancelsDeckOwnershipWithoutDestroyingMusicEvidence() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            await(c){c.confirmedPlan!=null}
            h.current=h.current!!.copy(positionUs=c.confirmedPlan!!.candidate.outgoingCueUs-4_000_000);c.tick()
            val v=h.virtual!!;val e=h.executor!!;v.toStart(e);v.advance(e,500_000)
            invalidate(h,c,NativeDjExecutionReason.USER_SEEK,20_000_000)
            assertEquals(1,v.b.releaseCount);assertFalse(v.a.released);assertEquals(0,v.promotions)
            await(c){c.confirmedPlan!=null};assertEquals(2,calls.get());assertFalse(c.ownsPlayback)
        }
    }
    @Test fun differentRenditionCannotReuseOldSourceEvidence() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            await(c){c.confirmedPlan!=null}
            val p=h.current!!;val next=p.outgoingSource.copy(id=p.outgoing.source.copy(renditionKey="replacement"))
            h.current=p.copy(queue=NativeDjQueue(2,next.id,p.incoming.source),outgoing=p.outgoing.copy(source=next.id),outgoingSource=next)
            c.validateQueue();await(c){c.confirmedPlan?.generation==2L}
            assertEquals(3,calls.get());assertEquals(next.id,c.confirmedPlan!!.outgoing.source)
        }
    }
    @Test fun unverifiedMutableSourceIsNotRetainedAcrossSeeks() {
        val h=Host().also {it.current=pair().copy(immutableOutgoing=false,immutableIncoming=false)};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            await(c){c.confirmedPlan!=null};invalidate(h,c,NativeDjExecutionReason.USER_SEEK)
            await(c){c.confirmedPlan!=null};assertEquals(4,calls.get())
        }
    }
    @Test fun uncachedSourceNeverStartsOptionalEnrichment() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger()
        controller(h,calls).use {c ->await(c){c.confirmedPlan!=null};repeat(50){c.tick()};assertEquals(2,calls.get())}
    }
    @Test fun cachedExpansionHasFiniteBudgetAndAllSegmentsStayWithinNativeCap() {
        val h=Host().also {it.current=pair();it.offline=true};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            await(c){c.confirmedPlan!=null};await(c){c.analysisReuse.optionalCompleted==6L}
            repeat(50){c.tick()}
            assertEquals(8,calls.get());assertEquals(8L,c.analysisReuse.workers.executions)
            assertTrue(c.analysisReuse.outgoing.regions<=6);assertTrue(c.analysisReuse.incoming.regions<=6)
            assertTrue(c.analysisReuse.outgoing.verifiedUnionUs<180_000_000)
            assertFalse(c.ownsPlayback)
        }
    }
    @Test fun routeAndSeekNeverSilentlyModifySelectedModeOrLegacyDefault() {
        val h=Host().also {it.current=pair()}
        controller(h).use {c ->
            await(c){c.confirmedPlan!=null};invalidate(h,c,NativeDjExecutionReason.ROUTE_CHANGED)
            assertTrue(c.nativeSelected)
            c.configure(true,AutoMixEngineMode.LEGACY,AutoMixPerformance.BALANCED)
            repeat(10){c.tick()};assertTrue(h.legacy);assertFalse(c.nativeSelected)
        }
    }
    @Test fun technicalFallbackCancelsRetainedPendingAnalysisBeforeLegacyWork() {
        val h=Host().also {it.current=pair()};val began=CountDownLatch(2);val stopped=CountDownLatch(2)
        controller(h,before={c->began.countDown();try {CountDownLatch(1).await()}finally {stopped.countDown();c.checkpoint()}}).use {c ->
            c.tick();assertTrue(began.await(3,TimeUnit.SECONDS));c.technicalFailure()
            assertTrue(stopped.await(3,TimeUnit.SECONDS));assertTrue(h.legacy);assertNull(c.confirmedPlan)
            assertEquals("HOST_FAILURE",h.latest.reason);assertFalse(c.ownsPlayback)
        }
    }

}
