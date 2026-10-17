// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Planning-only desktop measurements. Inputs are explicit precomputed observations, not PCM
 * or purported native accuracy measurements. No wall-clock pass/fail threshold. */
class NativePlannerPerformanceTest {
    private val json=Json {prettyPrint=true}
    @Test fun measuresBoundedSearchAllocationsConcurrencyAndCancellation() {
        val a=NativePlanningFixtures.evidence(220.0,148_000_000,lengthUs=32_000_000)
        val b=NativePlanningFixtures.evidence(220.0,0,"in",lengthUs=32_000_000)
        val request=NativePlanningFixtures.request(a,b)
        val planner=NativeAwareAutoMixPlanner(NativePlannerConfig(maxOutgoingAnchors=32,maxIncomingAnchors=32,maxCandidates=12288))
        repeat(5) {assertTrue(planner.plan(request,a,b) is NativePlanningOutcome.Selected)}
        val bean=ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        fun allocated():Long? = bean?.takeIf {it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled}
            ?.getThreadAllocatedBytes(Thread.currentThread().threadId())
        val expected=(planner.plan(request,a,b) as NativePlanningOutcome.Selected).plan
        val rows=mutableListOf<JsonObject>()
        for(mode in AutoMixPerformance.entries) AnalysisWorkers<NativePlanningOutcome>(mode).use {workers ->
            repeat(4) {
                val futures=(0 until mode.workers*2).map {workers.submitTransient {
                    val before=allocated();val began=System.nanoTime()
                    val result=planner.plan(request,a,b)
                    val elapsed=System.nanoTime()-began;val after=allocated()
                    assertEquals(expected,(result as NativePlanningOutcome.Selected).plan)
                    assertTrue(result.diagnostics.candidateCount<=12288)
                    result to buildJsonObject {
                        put("workers",mode.workers);put("elapsedUs",elapsed/1000.0)
                        put("candidates",result.diagnostics.candidateCount);put("rejected",result.diagnostics.candidateCount-result.diagnostics.acceptedCount)
                        put("anchorGenerationUs",result.diagnostics.anchorGenerationNanos/1000.0)
                        put("candidateEvaluationUs",result.diagnostics.candidateEvaluationNanos/1000.0)
                        put("allocatedJavaBytes",if(before!=null && after!=null) JsonPrimitive(after-before) else JsonNull)
                    }
                }}
                futures.forEach {rows+=it.get(10,TimeUnit.SECONDS).second}
            }
        }
        var calls=0;var canceledAt=0L
        val began=System.nanoTime()
        val canceled=planner.plan(request,a,b,AnalysisControl(cancellation=AnalysisCancellation {
            if(++calls==40) {canceledAt=System.nanoTime();throw CancellationException()}
        }))
        val returned=System.nanoTime()
        assertEquals(PlanningRejection.CANCELED,(canceled as NativePlanningOutcome.Fallback).reason)
        assertTrue(canceled.diagnostics.candidateCount>0)
        val base=a.analysis as AnalysisResult.Completed
        val malformed=a.copy(analysis=base.copy(evidence=base.evidence.copy(beatsUs=base.evidence.beatsUs.copy(
            value=List(512) {148_000_000+it*62_500L}))),signal=null)
        val invalidStart=System.nanoTime()
        val invalid=planner.plan(request,malformed,b)
        val invalidUs=(System.nanoTime()-invalidStart)/1000.0
        assertTrue(invalid is NativePlanningOutcome.Fallback)
        val report=buildJsonObject {
            put("schema",1);put("measurement","desktop JVM planning-only; no audio decoding or DSP in timed sections")
            put("planner",NativeAwareAutoMixPlanner.VERSION);put("rows",JsonArray(rows))
            put("inputBeatsPerTrack",base.evidence.beatsUs.value!!.size)
            put("malformed512BeatVectorRejectedUs",invalidUs)
            put("cancellationRequestedAtCheckpoint",calls)
            put("cancelToReturnUs",(returned-canceledAt)/1000.0);put("totalCanceledCallUs",(returned-began)/1000.0)
            put("peakMemoryMeasured",false)
            put("resourceNotes","Java allocation counters include validation/candidate/diagnostic objects, not native memory or peak heap. Inputs are preallocated. Retained candidates <=12; match residual scratch <=65 longs. Pool limits remain 1/2/4, waiting queue 8, LRU 64.")
        }
        System.getenv("TINIUM_PHASE31_REPORT_DIR")?.let {path ->File(path).apply {mkdirs()}.resolve("performance.json")
            .writeText(json.encodeToString(JsonObject.serializer(),report)+"\n")}
        println("NATIVE_PLANNER_PERFORMANCE samples=${rows.size} cancelToReturnUs=${(returned-canceledAt)/1000.0}")
    }
}
