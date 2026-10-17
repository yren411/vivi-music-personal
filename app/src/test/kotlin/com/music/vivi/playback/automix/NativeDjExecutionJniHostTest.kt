// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NativeDjExecutionJniHostTest {
    @Test fun genuineCorrectedPcmNativePlansExecuteOnVirtualDecksAndReportMeasurements() {
        assumeTrue("Optional actual host JNI required",!System.getProperty("tinium.hostNativeLibrary").isNullOrBlank())
        NativeJniChecks.loading(JniBitChordBridge())
        val rows=mutableListOf<JsonObject>()
        for((bpm,seconds,leading) in listOf(Triple(90.0,30,0.0),Triple(107.0,30,0.0),Triple(120.0,30,0.0),
            Triple(128.0,30,0.0),Triple(140.0,30,0.0),Triple(150.0,30,0.0),Triple(174.0,30,0.0),
            Triple(128.0,32,0.0),Triple(128.0,30,18.0))) {
            val sourceA=NativePlanningFixtures.pcm(bpm,seconds)
            val sourceB=NativePlanningFixtures.pcm(bpm,seconds,phase=.333,leadingSeconds=leading)
            fun corrected(pcm:FloatArray,origin:Long,id:String):DecodedWindow {
                // Actual corrected timestamp-aware preparation, not relabeled legacy PCM.
                val interval=AudioIntervalUs(origin,origin+seconds*1_000_000L)
                val capture=CorrectedPcmWindowCapture(DecodeRequest(interval,AnalysisSourceIdentity(id,"phase32-jni-source")),interval,AudioDuration(180_000_000,DurationBasis.FIXTURE))
                val buffer=java.nio.ByteBuffer.allocate(pcm.size*4).order(java.nio.ByteOrder.nativeOrder())
                pcm.forEach {buffer.putFloat(it)};buffer.flip()
                capture.accept(buffer,origin,SourcePcmFormat(PcmFormat(22050,1,PcmEncoding.FLOAT32),SourceFormatBasis.DECODER_OUTPUT),AnalysisControl())
                return capture.finish(false,AnalysisControl())
            }
            val aw=corrected(sourceA,if(seconds==32)148_000_000 else 150_000_000,"out")
            val bw=corrected(sourceB,0,"in")
            val engine=BitChordNativeAnalysisEngine()
            fun evidence(w:DecodedWindow)=PlanningWindowEvidence(engine.analyze(w,AnalysisDetail.DETAIL,AnalysisControl()),PlanningSignalObservation.measure(w,AnalysisControl()))
            val a=evidence(aw);val b=evidence(bw)
            val result=NativeAwareAutoMixPlanner().plan(NativePlanningFixtures.request(a,b),a,b)
            assertTrue("$bpm/$seconds/$leading: $result",result is NativePlanningOutcome.Selected)
            val p=(result as NativePlanningOutcome.Selected).plan
            val h=VirtualNativeDjHost(p);val e=NativeDjTransitionExecutor(h,{h.now})
            val begin=System.nanoTime();e.start(p,NativeDjLevel.measure(aw),NativeDjLevel.measure(bw));h.toStart(e);h.finish(e)
            val runtime=System.nanoTime()-begin
            assertEquals("$bpm/$seconds/$leading",NativeDjExecutionStage.COMPLETED,e.status.stage)
            val m=e.measurements()
            assertEquals(1,h.promotions);assertTrue(h.b.position>p.candidate.incomingCueUs)
            assertTrue(m.concurrentProgressUs>=p.candidate.overlapUs-10_000)
            if(seconds==32)assertTrue(p.candidate.overlapUs>29_000_000)
            if(leading>0)assertTrue(p.candidate.incomingCueUs>18_000_000)
            rows+=buildJsonObject {
                put("scenario","native-$bpm-${seconds}s-leading$leading")
                put("planSource","actual corrected capture -> real host JNI -> Phase 3.1 planner")
                put("timingMethod","virtual media clocks; no Android playback or audio capture")
                put("outgoingBpm",p.candidate.tempo.measuredOutgoingBpm);put("incomingBpm",p.candidate.tempo.measuredIncomingBpm)
                put("outgoingCueUs",p.candidate.outgoingCueUs);put("incomingCueUs",p.candidate.incomingCueUs);put("overlapUs",p.candidate.overlapUs)
                put("envelope",NativeDjCurve.RESTRAINED_DJ.name);put("contentOutgoing",NativeDjGain.content(p,NativeDjLevel.measure(aw),NativeDjLevel.measure(bw)).outgoing)
                put("contentIncoming",NativeDjGain.content(p,NativeDjLevel.measure(aw),NativeDjLevel.measure(bw)).incoming)
                put("simulatedStartOffsetUs",m.estimatedStartOffsetUs!!);put("concurrentProgressUs",m.concurrentProgressUs)
                put("status",e.status.stage.name);put("promotions",h.promotions);put("virtualUnderruns",0)
                put("hostExecutionNanos",runtime);put("acousticTimingMeasured",false)
            }
        }
        System.getenv("TINIUM_PHASE32_REPORT_DIR")?.let {directory ->File(directory).mkdirs();File(directory,"native-virtual-execution.json").writeText(Json {prettyPrint=true}.encodeToString(JsonElement.serializer(),JsonArray(rows))+"\n")}
    }
}
