// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.LockSupport
import kotlinx.serialization.json.*

class NativeServiceJniHostTest {
    @Test fun actualCorrectedCaptureJniPlannerAndServiceCoordinatorCompleteAnExtendedVirtualMix() {
        assumeTrue("Optional actual host JNI required",!System.getProperty("tinium.hostNativeLibrary").isNullOrBlank())
        fun window(id:String,origin:Long,phase:Double):DecodedWindow {
            val samples=NativePlanningFixtures.pcm(seconds=32,phase=phase)
            val interval=AudioIntervalUs(origin,origin+32_000_000)
            val capture=CorrectedPcmWindowCapture(DecodeRequest(interval,AnalysisSourceIdentity(id,"phase33-generated")),interval,AudioDuration(180_000_000,DurationBasis.FIXTURE))
            val bytes=ByteBuffer.allocate(samples.size*4).order(ByteOrder.nativeOrder());samples.forEach {bytes.putFloat(it)};bytes.flip()
            capture.accept(bytes,origin,SourcePcmFormat(PcmFormat(22050,1,PcmEncoding.FLOAT32),SourceFormatBasis.DECODER_OUTPUT),AnalysisControl())
            return capture.finish(false,AnalysisControl())
        }
        val a=window("out",148_000_000,.137);val b=window("in",0,.333)
        var position=140_000_000L;var legacy=false;var virtual:VirtualNativeDjHost?=null;var execution:NativeDjTransitionExecutor?=null
        val pair=NativeServicePair(NativeDjQueue(1,a.identity,b.identity),PlanningTrack(a.identity,180_000_000,a.requested),
            PlanningTrack(b.identity,180_000_000,b.requested),a,b,position,true,true,true)
        val host=object:NativeAutoMixServiceHost<DecodedWindow> {
            override fun pair()=pair.copy(positionUs=position)
            override fun incompatibility():String?=null
            override fun legacyEnabled(enabled:Boolean){legacy=enabled}
            override fun execute(plan:NativeTransitionPlan,outgoingLevel:NativeDjLevel?,incomingLevel:NativeDjLevel?):NativeDjTransitionExecutor {
                assertFalse(legacy);assertNotNull(outgoingLevel);assertNotNull(incomingLevel)
                val h=VirtualNativeDjHost(plan);h.a.position=position;virtual=h
                return NativeDjTransitionExecutor(h,{h.now}).also {execution=it;it.start(plan,outgoingLevel,incomingLevel)}
            }
            override fun cancel(reason:NativeDjExecutionReason){execution?.cancel(reason)}
            override fun pause(){execution?.pause()};override fun resume(){execution?.resume()};override fun refreshVolume(){}
            override fun status(value:AutoMixStatus){}
        }
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val analysis=NativePlanningAnalysis(AnalysisWindowDecoder<DecodedWindow>{w,_,c->c.checkpoint();calls.incrementAndGet();DecodeResult.Decoded(w)})
        val recorder=AutoMixRecorder();val began=System.nanoTime()
        NativeAutoMixServiceController(host,analysis,AnalysisWorkers(AutoMixPerformance.PERFORMANCE),recorder).use { controller ->
            controller.configure(true,AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL,AutoMixPerformance.PERFORMANCE)
            val deadline=System.nanoTime()+15_000_000_000
            while(controller.confirmedPlan==null && System.nanoTime()<deadline){controller.tick();LockSupport.parkNanos(1_000_000)}
            val plan=requireNotNull(controller.confirmedPlan)
            position=plan.candidate.outgoingCueUs-4_000_000;controller.tick()
            val e=requireNotNull(execution);virtual!!.finish(e);controller.tick()
            assertEquals(NativeDjExecutionStage.COMPLETED,e.status.stage);assertEquals(1,virtual!!.promotions)
            assertTrue(plan.candidate.overlapUs>=29_000_000);assertEquals(2,calls.get())
            assertTrue(e.measurements().concurrentProgressUs>=plan.candidate.overlapUs-10_000)
            val evidence=recorder.snapshot().attempts.flatMap {it.records}.filter {it.native?.event==NativeServiceEvent.EVIDENCE}
            assertEquals(2,evidence.size);assertEquals(setOf(MixRole.OUTGOING,MixRole.INCOMING),evidence.map {it.role}.toSet())
            assertTrue(evidence.all {it.metrics[MixMetric.BPM]!! in 126.0..130.0 && it.metrics.containsKey(MixMetric.NATIVE_RAW_CONFIDENCE)})
            val report=buildJsonObject {
                put("schema",1);put("evidence","real corrected PCM capture -> host JNI -> actual native planner -> service coordinator -> virtual decks")
                put("androidRuntimeExecuted",false);put("outgoingCueUs",plan.candidate.outgoingCueUs);put("incomingCueUs",plan.candidate.incomingCueUs)
                put("plannedOverlapUs",plan.candidate.overlapUs);put("commonProgressUs",e.measurements().concurrentProgressUs)
                put("outgoingBpm",plan.candidate.tempo.measuredOutgoingBpm);put("incomingBpm",plan.candidate.tempo.measuredIncomingBpm)
                put("nativeProfile",BitChordNativeAnalysisEngine.PROFILE.toString());put("executor",NativeDjTransitionExecutor.VERSION)
                put("promotions",virtual!!.promotions);put("decodedWindows",calls.get());put("hostElapsedNanos",System.nanoTime()-began)
                put("outgoingContentGain",e.report().contentGains.outgoing);put("incomingContentGain",e.report().contentGains.incoming)
            }
            System.getenv("TINIUM_PHASE33_REPORT_DIR")?.let {File(it).mkdirs();File(it,"service-coordinator-host-jni.json").writeText(Json {prettyPrint=true}.encodeToString(JsonElement.serializer(),report)+"\n")}
        }
    }
}
