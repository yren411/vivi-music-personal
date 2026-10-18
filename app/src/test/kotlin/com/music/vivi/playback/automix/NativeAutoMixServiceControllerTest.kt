// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.music.vivi.constants.AutoMixEngineModeKey
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

class NativeAutoMixServiceControllerTest {
    private val pcm by lazy {NativePlanningFixtures.pcm(120.0,seconds=32)}
    private class Host:NativeAutoMixServiceHost<DecodedWindow> {
        var current:NativeServicePair<DecodedWindow>?=null
        var legacy=false;var prepareFails=false;var executes=0;var cancellations=0;var unavailable:String?=null
        var virtual:VirtualNativeDjHost?=null;var executor:NativeDjTransitionExecutor?=null
        var outputPending=false
        var latest=AutoMixStatus();var paused=0;var resumed=0;var refreshed=0
        override fun pair()=current?.let {v->v.copy(positionUs=virtual?.a?.position ?: v.positionUs)}
        override fun waitingForOutput()=outputPending
        override fun incompatibility()=unavailable
        override fun legacyEnabled(enabled:Boolean){legacy=enabled}
        override fun execute(plan:NativeTransitionPlan,outgoingLevel:NativeDjLevel?,incomingLevel:NativeDjLevel?):NativeDjTransitionExecutor {
            assertFalse(legacy);executes++;check(!prepareFails)
            val h=VirtualNativeDjHost(plan);h.a.position=current!!.positionUs;virtual=h
            return NativeDjTransitionExecutor(h,{h.now}).also {executor=it;it.start(plan,outgoingLevel,incomingLevel)}
        }
        override fun cancel(reason:NativeDjExecutionReason){cancellations++;executor?.cancel(reason)}
        override fun status(value:AutoMixStatus){latest=value}
        override fun pause(){paused++;executor?.pause()}
        override fun resume(){resumed++;executor?.resume()}
        override fun refreshVolume(){refreshed++}
    }
    private fun pair():NativeServicePair<DecodedWindow> {
        val a=NativePlanningFixtures.realWindow(pcm,148_000_000,"out")
        val b=NativePlanningFixtures.realWindow(pcm,0,"in")
        return NativeServicePair(NativeDjQueue(1,a.identity,b.identity),
            PlanningTrack(a.identity,180_000_000,a.requested),PlanningTrack(b.identity,180_000_000,b.requested),
            a,b,140_000_000,true,true,true)
    }
    private fun engine()=BitChordNativeAnalysisEngine(NativeDspBridge {w,c ->
        val measured=PlanningSignalObservation.measure(w,c)
        NativeAnalysisFixtures.packet(w).also {it.scalars[10]=measured.windowRms;it.scalars[11]=measured.windowPeak}
    })
    private fun controller(h:Host,calls:AtomicInteger=AtomicInteger(),
        decode:(DecodedWindow,AnalysisControl)->DecodeResult={w,c->c.checkpoint();DecodeResult.Decoded(w)},
        mode:AutoMixPerformance=AutoMixPerformance.BALANCED):NativeAutoMixServiceController<DecodedWindow> {
        val decoder=AnalysisWindowDecoder<DecodedWindow>{w,_,c->calls.incrementAndGet();decode(w,c)}
        return NativeAutoMixServiceController(h,NativePlanningAnalysis(decoder,engine()),AnalysisWorkers(mode),AutoMixRecorder()).also {
            it.configure(true,AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL,mode)
        }
    }
    private fun await(c:NativeAutoMixServiceController<*>,condition:()->Boolean) {
        val end=System.nanoTime()+10_000_000_000
        while(!condition() && System.nanoTime()<end){c.tick();LockSupport.parkNanos(1_000_000)}
        assertTrue("Native service pump did not reach requested state",condition())
    }
    @Test fun missingAndUnknownSavedValuesRemainLegacy() {
        assertEquals(AutoMixEngineMode.LEGACY,readAutoMixSettings(emptyPreferences()).engineMode)
        assertEquals(AutoMixEngineMode.LEGACY,readAutoMixSettings(mutablePreferencesOf(AutoMixEngineModeKey to "future-mode")).engineMode)
        assertEquals(AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL,readAutoMixSettings(mutablePreferencesOf(AutoMixEngineModeKey to "NATIVE_DJ_EXPERIMENTAL")).engineMode)
    }
    @Test fun legacyDefaultAndDisabledOptInPerformNoAnalysis() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            c.configure(true,AutoMixEngineMode.LEGACY,AutoMixPerformance.BALANCED);repeat(20){c.tick()}
            assertTrue(h.legacy);assertEquals(0,calls.get());assertEquals(0,h.executes)
            c.configure(false,AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL,AutoMixPerformance.BALANCED);c.tick()
            assertFalse(c.nativeSelected);assertEquals(0,calls.get());assertEquals(AutoMixPhase.DISABLED,h.latest.phase)
        }
    }
    @Test fun allWorkerModesProduceActualPlannerPlansWithoutNestedWaits() {
        for(mode in AutoMixPerformance.entries) {
            val h=Host().also {it.current=pair()};val calls=AtomicInteger()
            controller(h,calls,mode=mode).use {c ->
                await(c){c.confirmedPlan!=null}
                assertEquals(2,calls.get());assertEquals(NativePlanReadiness.TIMING_AND_TRANSIENT_SUPPORTED,c.confirmedPlan!!.readiness)
                assertEquals(BitChordNativeAnalysisEngine.PROFILE,c.confirmedPlan!!.outgoingProfile)
                assertFalse(h.legacy);assertEquals(0,h.executes)
            }
        }
    }
    @Test fun fullPlanRunsOnceWithNativeOwnershipThenPreservesSurvivingPlayer() {
        val h=Host().also {it.current=pair()}
        controller(h).use {c ->
            await(c){c.confirmedPlan!=null};val p=c.confirmedPlan!!
            h.current=h.current!!.copy(positionUs=p.candidate.outgoingCueUs-4_000_000)
            c.tick();val v=h.virtual!!;val e=h.executor!!
            assertTrue(c.ownsPlayback);assertFalse(h.legacy)
            v.finish(e);c.tick()
            assertEquals(NativeDjExecutionStage.COMPLETED,e.status.stage)
            assertEquals(1,v.promotions);assertEquals(1,v.a.releaseCount);assertFalse(v.b.released)
            assertTrue(e.measurements().concurrentProgressUs>=p.candidate.overlapUs-10_000)
            assertTrue(v.b.position>p.candidate.incomingCueUs);assertEquals(1,h.executes)
        }
    }
    @Test fun unresolvedOutputDoesNotLatchTransientProcessorRejectionAndSamePairCanPlanWhenReady() {
        val h=Host().also {it.current=pair();it.outputPending=true;it.unavailable="PCM_PROCESSOR_UNAVAILABLE"}
        val calls=AtomicInteger()
        controller(h,calls).use {c ->
            repeat(20){c.tick()}
            assertTrue(h.legacy);assertEquals(0,calls.get());assertEquals(0,h.executes)
            assertEquals("WAITING_PLAYER_OUTPUT",h.latest.reason)
            // Same generation/source pair, now the real sink has configured its processor.
            h.outputPending=false;h.unavailable=null
            await(c){c.confirmedPlan!=null}
            assertEquals(2,calls.get());assertFalse(h.legacy)
            assertEquals(NativePlanReadiness.TIMING_AND_TRANSIENT_SUPPORTED,c.confirmedPlan!!.readiness)
        }
    }
    @Test fun configuredButUnsupportedOutputStillFallsBackOnceWithoutNativeAnalysis() {
        val h=Host().also {it.current=pair();it.outputPending=true;it.unavailable="PCM_PROCESSOR_UNAVAILABLE"}
        val calls=AtomicInteger()
        controller(h,calls).use {c ->
            c.tick();assertEquals("WAITING_PLAYER_OUTPUT",h.latest.reason)
            h.outputPending=false
            repeat(20){c.tick()}
            assertTrue(h.legacy);assertEquals(0,calls.get());assertEquals(0,h.executes)
            assertEquals("PCM_PROCESSOR_UNAVAILABLE",h.latest.reason)
        }
    }
    @Test fun nextTrackChangeWhileOutputIsPendingCannotAnalyzeTheObsoletePair() {
        val h=Host().also {it.current=pair();it.outputPending=true};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            c.tick();assertEquals(0,calls.get())
            h.current=null;c.validateQueue();h.outputPending=false
            repeat(20){c.tick()}
            assertEquals(0,calls.get());assertEquals(0,h.executes);assertNull(c.confirmedPlan)
            assertTrue(h.legacy);assertEquals("WAITING_RESOLVED_PAIR",h.latest.reason)
        }
    }
    @Test fun unsupportedConfigurationFallsBackOnceWithoutAnalysis() {
        val h=Host().also {it.current=pair();it.unavailable="UNSUPPORTED_RATE"};val calls=AtomicInteger()
        controller(h,calls).use {c -> repeat(20){c.tick()};assertTrue(h.legacy);assertEquals(0,calls.get());assertEquals(0,h.executes)}
    }
    @Test fun decoderFailureUsesGenuineLegacyRouteWithoutReinterpretingNativeEvidence() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger()
        controller(h,calls,decode={_,_->DecodeResult.Failed(AnalysisFailure(AnalysisFailureStage.DECODING,AutoMixFailure.DECODER_FAILED,"DECODER_FAILED"))}).use {c ->
            await(c){h.legacy};repeat(20){c.tick()};assertEquals(2,calls.get());assertEquals(0,h.executes);assertNull(c.confirmedPlan)
        }
    }
    @Test fun pauseResumeAndVolumeUseOnlyTheOwnedNativeDecks() {
        val h=Host().also {it.current=pair()}
        controller(h).use {c ->
            await(c){c.confirmedPlan!=null};h.current=h.current!!.copy(positionUs=c.confirmedPlan!!.candidate.outgoingCueUs-4_000_000);c.tick()
            c.playbackChanged(false);assertEquals(NativeDjExecutionStage.PAUSED,h.executor!!.status.stage)
            c.playbackChanged(true);c.volumeChanged();assertEquals(1,h.paused);assertEquals(1,h.resumed);assertEquals(1,h.refreshed)
        }
    }
    @Test fun switchingBackToLegacyCancelsPreparedDeckAndRestoresOutgoing() {
        val h=Host().also {it.current=pair()}
        controller(h).use {c ->
            await(c){c.confirmedPlan!=null};h.current=h.current!!.copy(positionUs=c.confirmedPlan!!.candidate.outgoingCueUs-4_000_000);c.tick()
            c.configure(true,AutoMixEngineMode.LEGACY,AutoMixPerformance.BALANCED)
            assertTrue(h.legacy);assertFalse(c.ownsPlayback);assertEquals(1,h.virtual!!.b.releaseCount);assertTrue(h.virtual!!.a.playing)
        }
    }
    @Test fun changedQueueInvalidatesConfirmedPlanBeforePreparation() {
        val h=Host().also {it.current=pair()}
        controller(h).use {c ->await(c){c.confirmedPlan!=null};h.current=null;c.validateQueue();assertNull(c.confirmedPlan);assertEquals(0,h.executes)}
    }
    @Test fun cancelDuringActualDecodeDoesNotPublishAnObsoletePlan() {
        val h=Host().also {it.current=pair()};val began=CountDownLatch(1);val stopped=CountDownLatch(1)
        controller(h,decode={w,c ->
            began.countDown()
            try {CountDownLatch(1).await();DecodeResult.Decoded(w)}finally {stopped.countDown();c.checkpoint()}
        }).use {c ->
            c.tick();assertTrue(began.await(3,TimeUnit.SECONDS));h.current=null;c.validateQueue()
            assertTrue(stopped.await(3,TimeUnit.SECONDS));assertNull(c.confirmedPlan);assertEquals(0,h.executes)
        }
    }
    @Test fun missingSourcePreservesPlaybackWithoutStartingNativeDecode() {
        val h=Host();val calls=AtomicInteger()
        controller(h,calls).use {c ->repeat(10){c.tick()};assertTrue(h.legacy);assertEquals(0,calls.get());assertEquals(AutoMixPhase.WAITING_SOURCE,h.latest.phase)}
    }
    @Test fun planThatBecomesTooLateFallsBackWithoutStartingADeck() {
        val h=Host().also {it.current=pair()}
        controller(h).use {c ->await(c){c.confirmedPlan!=null}
            h.current=h.current!!.copy(positionUs=c.confirmedPlan!!.candidate.outgoingCueUs-1)
            c.tick();assertTrue(h.legacy);assertEquals(0,h.executes);assertNull(c.confirmedPlan)
        }
    }
    @Test fun sourceConfigurationChangeAfterPlanningCannotExecuteThePlan() {
        val h=Host().also {it.current=pair()}
        controller(h).use {c ->await(c){c.confirmedPlan!=null};h.unavailable="UNSUPPORTED_RATE";c.tick()
            assertTrue(h.legacy);assertEquals(0,h.executes);assertNull(c.confirmedPlan)
        }
    }
    @Test fun repeatedTechnicalFailuresSuspendTheSessionWithoutChangingSavedSelection() {
        val h=Host().also {it.current=pair()};val calls=AtomicInteger()
        controller(h,calls).use {c ->
            repeat(3){c.technicalFailure()};repeat(10){c.tick()}
            assertTrue(c.nativeSelected);assertTrue(h.legacy);assertEquals(0,calls.get())
            c.configure(true,AutoMixEngineMode.LEGACY,AutoMixPerformance.BALANCED)
            c.configure(true,AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL,AutoMixPerformance.BALANCED)
            await(c){c.confirmedPlan!=null};assertEquals(2,calls.get())
        }
    }
    @Test fun missingActualDurationCannotBePromotedFromCatalogGeometry() {
        val h=Host().also {it.current=pair()}
        controller(h,decode={w,_->DecodeResult.Decoded(w.copy(duration=AudioDuration(null,DurationBasis.UNKNOWN)))}).use {c ->
            await(c){h.legacy};assertEquals(0,h.executes);assertNull(c.confirmedPlan)
            assertEquals("DURATION_MISMATCH",h.latest.reason)
        }
    }
    @Test fun failedPreparationReturnsOwnershipToLegacyWithoutStartingPlayback() {
        val h=Host().also {it.current=pair();it.prepareFails=true}
        controller(h).use {c ->await(c){c.confirmedPlan!=null}
            h.current=h.current!!.copy(positionUs=c.confirmedPlan!!.candidate.outgoingCueUs-4_000_000);c.tick()
            assertTrue(h.legacy);assertFalse(c.ownsPlayback);assertEquals("PREPARATION_FAILED",h.latest.reason)
        }
    }
    @Test fun activeIncomingFailureReleasesStandbyAndRestoresOutgoingGain() {
        val h=Host().also {it.current=pair()}
        controller(h).use {c ->await(c){c.confirmedPlan!=null}
            h.current=h.current!!.copy(positionUs=c.confirmedPlan!!.candidate.outgoingCueUs-4_000_000);c.tick()
            val v=h.virtual!!;v.toStart(h.executor!!);v.advance(h.executor!!,1_000_000);v.b.failed=true;h.executor!!.tick();c.tick()
            assertTrue(h.legacy);assertFalse(c.ownsPlayback);assertEquals(1,v.b.releaseCount)
            assertTrue(v.a.playing);assertEquals(1.0,v.a.applied,0.0);assertEquals(0,v.promotions)
        }
    }
    @Test fun activeQueueInvalidationCannotPromoteTheOldIncoming() {
        val h=Host().also {it.current=pair()}
        controller(h).use {c ->await(c){c.confirmedPlan!=null}
            h.current=h.current!!.copy(positionUs=c.confirmedPlan!!.candidate.outgoingCueUs-4_000_000);c.tick()
            val v=h.virtual!!;h.current=h.current!!.copy(queue=h.current!!.queue.copy(generation=2))
            c.validateQueue();assertFalse(c.ownsPlayback);assertNull(c.confirmedPlan)
            assertEquals(1,v.b.releaseCount);assertEquals(0,v.promotions);assertTrue(v.a.playing)
        }
    }
    @Test fun unavailableSourceHandlesAreRedacted() {
        val a=pair().copy(outgoingSource=pair().outgoingSource,incomingSource=pair().incomingSource)
        assertEquals("NativeServicePair(generation=1, sources=redacted)",a.toString())
    }
    @Test fun diagnosticsAreBoundedAndCannotExportSourceUrlsOrLocalPaths() {
        val r=AutoMixRecorder(maxAttempts=2,maxEvents=3)
        repeat(4){val id=r.begin("https://private?token=secret","C:/private/music");repeat(5){r.native(id,NativeServiceEvent.FALLBACK,reason="https://secret/token")}}
        val history=r.snapshot();assertEquals(2,history.attempts.size);assertTrue(history.attempts.all{it.records.size==3})
        val text=AutoMixRecorder.render(history,MixReportHeader("test",1,"17","fixture",true,AutoMixPerformance.BALANCED))
        assertFalse(text.contains("secret"));assertFalse(text.contains("C:/private"));assertFalse(text.contains("https://"));assertTrue(text.contains(PcmPreprocessing.BANDLIMITED))
    }
}
