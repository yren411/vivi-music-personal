// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*

/** Actual corrected capture/resampling and JNI, simulated source transport, no Android claim. */
class AdaptiveNativeJniHostTest {
    @Test fun actualNativeAnalysisOnVerifiedInsetAndCachedReuseDoesNotDecodeAgain() {
        assumeTrue("Actual host JNI required",!System.getProperty("tinium.hostNativeLibrary").isNullOrBlank())
        val id=AnalysisSourceIdentity("generated-adaptive-host","pcm-regions-v1")
        val source=NativePlanningFixtures.pcm(128.0,seconds=32)
        val bytes=ByteBuffer.allocate(source.size*4).order(ByteOrder.nativeOrder()).apply {source.forEach {putFloat(it)};flip()}
        val count=AtomicInteger()
        val decoder=AnalysisWindowDecoder<Unit>{_,r,c ->
            count.incrementAndGet()
            val capture=CorrectedPcmWindowCapture(r,r.interval,AudioDuration(180_000_000,DurationBasis.FIXTURE))
            capture.accept(bytes.duplicate(),137006,SourcePcmFormat(PcmFormat(22050,1,PcmEncoding.FLOAT32),SourceFormatBasis.DECODER_OUTPUT),c)
            DecodeResult.Decoded(capture.finish(false,c))
        }
        val request=DecodeRequest(AudioIntervalUs(0,32_000_000),id)
        val strict=NativePlanningAnalysis(decoder).analyze(Unit,request,AnalysisDetail.DETAIL,AnalysisControl())
        assertEquals("PARTIAL_SOURCE_COVERAGE",(strict.analysis as AnalysisResult.Failed).failure.code)
        count.set(0)
        val analysis=NativePlanningAnalysis(decoder,adaptive=true)
        val key=AdaptiveNativeRegions.key(request,AnalysisDetail.DETAIL,true,true)
        AnalysisWorkers<PlanningWindowEvidence>().use {workers ->
            val start=System.nanoTime()
            val evidence=workers.acquire(key,true,true){analysis.analyze(Unit,request,AnalysisDetail.DETAIL,AnalysisControl())}.get(15,TimeUnit.SECONDS)
            val coldNanos=System.nanoTime()-start
            val result=evidence.analysis as AnalysisResult.Completed
            assertEquals(138000L,result.metadata.sampleInterval!!.startUs)
            assertEquals(CoverageStatus.FULL,result.metadata.coverageStatus);assertEquals(2,count.get())
            assertTrue(result.evidence.tempoBpm.value!! in 125.44..130.56)
            assertTrue(result.evidence.beatsUs.value!!.all {it>=138000 && it<32_000_000})
            workers.cancelAll() // model schedule invalidation, preserving completed source evidence
            val reuseStart=System.nanoTime()
            repeat(100) {assertSame(evidence,workers.acquire(key,true,true){error("redecode")}.get(3,TimeUnit.SECONDS))}
            val reuseNanos=System.nanoTime()-reuseStart
            assertEquals(2,count.get());assertEquals(100L,workers.snapshot().cacheHits)
            val report=buildJsonObject {
                put("schema",1);put("evidence","actual host corrected capture/resampling/JNI; simulated source; no Android execution")
                put("strategy",AdaptiveNativeRegions.VERSION);put("preprocessing",PcmPreprocessing.BANDLIMITED)
                put("originalStartUs",0);put("originalCoverageStartUs",137006);put("selectedStartUs",138000)
                put("selectedEndUs",32_000_000);put("canonicalFrames",result.metadata.nativeDiagnostic!!.inputFrames)
                put("bpm",result.evidence.tempoBpm.value!!);put("rawConfidence",result.evidence.gridConfidence.value!!)
                put("nativeElapsedUs",result.metadata.nativeDiagnostic.elapsedUs);put("coldHostNanos",coldNanos)
                put("sourceDecodeExecutions",count.get());put("cacheHits",100);put("cache100Nanos",reuseNanos)
                put("syntheticTimelineFrames",result.metadata.preparation!!.synthesizedTimelineFrames)
                put("sourceOriginPreserved",true);put("fullTrackPcmRetained",false)
            }
            println("PHASE334_HOST_JSON "+Json.encodeToString(JsonElement.serializer(),report))
        }
    }
    @Test fun actualSegmentedNativeEnrichmentCoversShortCachedModelWithoutGlobalGridFabrication() {
        assumeTrue("Actual host JNI required",!System.getProperty("tinium.hostNativeLibrary").isNullOrBlank())
        val id=AnalysisSourceIdentity("generated-adaptive-96","tempo128-regions-v1")
        val catalog=NativeRegionCatalog();val count=AtomicInteger()
        val analysis=NativePlanningAnalysis(AnalysisWindowDecoder<Unit>{_,r,c ->
            count.incrementAndGet()
            val period=60.0/128
            val phase=((.137-r.interval.startUs/1_000_000.0)%period+period)%period
            val pcm=NativePlanningFixtures.pcm(128.0,seconds=32,phase=phase)
            val capture=CorrectedPcmWindowCapture(r,r.interval,AudioDuration(96_000_000,DurationBasis.FIXTURE))
            val bytes=ByteBuffer.allocate(pcm.size*4).order(ByteOrder.nativeOrder()).apply {pcm.forEach {putFloat(it)};flip()}
            capture.accept(bytes,r.interval.startUs,SourcePcmFormat(PcmFormat(22050,1,PcmEncoding.FLOAT32),SourceFormatBasis.DECODER_OUTPUT),c)
            DecodeResult.Decoded(capture.finish(false,c))
        },adaptive=true)
        val bpms=mutableListOf<Double>();val times=mutableListOf<Long>();val nativeTimes=mutableListOf<Double>()
        AnalysisWorkers<PlanningWindowEvidence>().use {workers ->
            repeat(3) {
                val span=if(it==0)AudioIntervalUs(0,32_000_000) else requireNotNull(catalog.next(id,96_000_000))
                if(it>0)catalog.started(id,span)
                val request=DecodeRequest(span,id);val began=System.nanoTime()
                val result=workers.acquire(AdaptiveNativeRegions.key(request,AnalysisDetail.DETAIL,true,true),true){
                    analysis.analyze(Unit,request,AnalysisDetail.DETAIL,AnalysisControl())
                }.get(15,TimeUnit.SECONDS)
                times+=(System.nanoTime()-began)
                val completed=result.analysis as AnalysisResult.Completed
                bpms+=requireNotNull(completed.evidence.tempoBpm.value);nativeTimes+=completed.metadata.nativeDiagnostic!!.elapsedUs
                catalog.remember(result)
            }
            val summary=catalog.summary(id)
            assertEquals(96_000_000L,summary.verifiedUnionUs);assertEquals(3,count.get())
            assertNull(catalog.next(id,96_000_000));assertTrue(bpms.all {it in 125.44..130.56})
            assertEquals(NativeTempoAgreement.REGIONAL_AGREEMENT_NOT_GLOBAL_PROOF,summary.tempo)
            val report=buildJsonObject {
                put("schema",1);put("evidence","actual host JNI; generated 96s stable-tempo source model, three independent corrected 32s captures; no Android/cache-I/O claim")
                put("decodedRegions",3);put("maximumPcmWindowFrames",705600);put("verifiedAnalyzedUnionUs",summary.verifiedUnionUs)
                put("firstRegionHostNanos",times[0]);put("optionalTwoRegionsHostNanos",times.drop(1).sum())
                putJsonArray("regionalBpms"){bpms.forEach {add(it)}}
                putJsonArray("regionalNativeElapsedUs"){nativeTimes.forEach {add(it)}}
                put("comparison",summary.tempo.name);put("globalBpmInvented",false);put("gridsStitched",false)
            }
            println("PHASE334_PROGRESSIVE_JSON "+Json.encodeToString(JsonElement.serializer(),report))
        }
    }

}
