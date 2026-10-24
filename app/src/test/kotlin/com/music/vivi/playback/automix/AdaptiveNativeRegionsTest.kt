// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

class AdaptiveNativeRegionsTest {
    private val identity=AnalysisSourceIdentity("generated-adaptive","rendition-v1")
    private fun window(request:AudioIntervalUs,start:Long=request.startUs,end:Long=request.endUs):DecodedWindow {
        val origin=(maxOf(request.startUs,start)+999)/1000*1000
        val size=((minOf(end,request.endUs)-origin)*22050/1_000_000).toInt().coerceAtLeast(1)
        return NativeAnalysisFixtures.window(FloatArray(size){.1f},origin).copy(requested=request,decodeInterval=request,
            coverage=AudioCoverage(listOf(AudioIntervalUs(maxOf(start,request.startUs),minOf(end,request.endUs))),size.toLong()),
            identity=identity,duration=AudioDuration(180_000_000,DurationBasis.FIXTURE))
    }
    private fun engine()=BitChordNativeAnalysisEngine(NativeDspBridge {w,c->
        val signal=PlanningSignalObservation.measure(w,c)
        NativeAnalysisFixtures.packet(w).also {it.scalars[10]=signal.windowRms;it.scalars[11]=signal.windowPeak}
    })
    @Test fun zeroOriginAlreadyFullNeedsNoAdaptation() {
        assertNull(AdaptiveNativeRegions.supportedInset(window(AudioIntervalUs(0,32_000_000))))
    }
    @Test fun reported137006usStartSelectsAnActualSupportedIntervalWithoutRebasing() {
        val original=window(AudioIntervalUs(0,32_000_000),137006)
        assertEquals(CoverageStatus.PARTIAL,original.coverageStatus)
        assertEquals(AudioIntervalUs(138000,32_000_000),AdaptiveNativeRegions.supportedInset(original))
        assertEquals(137006L,original.actualStartUs) // original request remains partial
    }
    @Test fun reported144usEndMovesOnlyNewRequestedEndpointInward() {
        val original=window(AudioIntervalUs(123_063_000,155_063_000),end=155_062_856)
        assertEquals(AudioIntervalUs(123_063_000,155_062_000),AdaptiveNativeRegions.supportedInset(original))
        assertEquals(CoverageStatus.PARTIAL,original.coverageStatus)
    }
    @Test fun unknownAndMultipleCoverageAndSyntheticSamplesCannotGuideAdaptation() {
        val w=window(AudioIntervalUs(0,32_000_000),137006)
        val variants=listOf(w.copy(coverage=AudioCoverage(null,10)),w.copy(coverage=AudioCoverage(listOf(AudioIntervalUs(137006,8_000_000),AudioIntervalUs(9_000_000,32_000_000)),10)),
            w.copy(repeatedOutputFrames=1),w.copy(preparation=PcmPreparationMetadata(1,0,0,0,1,0)),
            w.copy(preparation=PcmPreparationMetadata(1,0,0,0,0,0,synthesizedTimelineFrames=1)),w.copy(identity=identity,preprocessingVersion=PcmPreprocessing.LEGACY))
        variants.forEach {assertNull(AdaptiveNativeRegions.supportedInset(it))}
    }
    @Test fun shortSupportedIslandAndInvalidMetadataRemainRejected() {
        assertNull(AdaptiveNativeRegions.supportedInset(window(AudioIntervalUs(0,32_000_000),25_000_000)))
        val w=window(AudioIntervalUs(0,32_000_000),137006)
        assertNull(AdaptiveNativeRegions.supportedInset(w.copy(coverage=w.coverage.copy(acceptedDecodedFrames=0))))
        assertNull(AdaptiveNativeRegions.supportedInset(w.copy(coverage=AudioCoverage(listOf(AudioIntervalUs(0,40_000_000)),100))))
    }
    @Test fun adaptationRedecodesNewIntervalAndMapsFractionalNativeBeatsExactlyOnce() {
        val requests=mutableListOf<DecodeRequest>()
        val decoder=AnalysisWindowDecoder<Unit>{_,r,_->requests+=r;DecodeResult.Decoded(window(r.interval,137006))}
        val evidence=NativePlanningAnalysis(decoder,engine(),adaptive=true).analyze(Unit,DecodeRequest(AudioIntervalUs(0,32_000_000),identity),AnalysisDetail.DETAIL,AnalysisControl())
        val result=evidence.analysis as AnalysisResult.Completed
        assertEquals(listOf(AudioIntervalUs(0,32_000_000),AudioIntervalUs(138000,32_000_000)),requests.map {it.interval})
        assertEquals(CoverageStatus.FULL,result.metadata.coverageStatus)
        assertEquals(138000L,result.metadata.sampleInterval!!.startUs)
        assertEquals(275000L,result.evidence.beatsUs.value!!.first())
        assertEquals(2,evidence.region!!.decodeExecutions);assertTrue(evidence.region.adapted)
        assertEquals(PcmPreprocessing.BANDLIMITED,result.metadata.preprocessingVersion)
    }
    @Test fun secondRequestMustIndependentlyPassFullCoverageNoThirdRetry() {
        var count=0
        val decoder=AnalysisWindowDecoder<Unit>{_,r,_->count++;DecodeResult.Decoded(window(r.interval,r.interval.startUs+137006))}
        val result=NativePlanningAnalysis(decoder,engine(),adaptive=true).analyze(Unit,DecodeRequest(AudioIntervalUs(0,32_000_000),identity),AnalysisDetail.DETAIL,AnalysisControl())
        assertTrue(result.analysis is AnalysisResult.Failed);assertEquals(2,count)
        assertEquals("PARTIAL_SOURCE_COVERAGE",(result.analysis as AnalysisResult.Failed).failure.code)
    }
    @Test fun failedInteriorGapDoesNotTriggerBoundaryRetry() {
        var count=0
        val decoder=AnalysisWindowDecoder<Unit>{_,_,_->count++;DecodeResult.Failed(AnalysisFailure(AnalysisFailureStage.DECODING,AutoMixFailure.INSUFFICIENT_AUDIO_DATA,"SOURCE_GAP"))}
        val result=NativePlanningAnalysis(decoder,engine(),adaptive=true).analyze(Unit,DecodeRequest(AudioIntervalUs(0,32_000_000),identity),AnalysisDetail.DETAIL,AnalysisControl())
        assertEquals(1,count);assertEquals("SOURCE_GAP",(result.analysis as AnalysisResult.Failed).failure.code)
    }
    @Test fun unchangedStrictEntryPointRetainsOldBoundaryFailure() {
        val decoder=AnalysisWindowDecoder<Unit>{_,r,_->DecodeResult.Decoded(window(r.interval,137006))}
        val result=NativePlanningAnalysis(decoder,engine()).analyze(Unit,DecodeRequest(AudioIntervalUs(0,32_000_000),identity),AnalysisDetail.DETAIL,AnalysisControl())
        assertEquals("PARTIAL_SOURCE_COVERAGE",(result.analysis as AnalysisResult.Failed).failure.code)
    }
    @Test fun realSourceFrameCapturePreservesPrerollNegativePtsPaddingAndNoGapToleranceChange() {
        for(rate in listOf(44100,48000))for(ch in listOf(1,2)) {
            val r=DecodeRequest(AudioIntervalUs(0,8_000_000),identity)
            val c=CorrectedPcmWindowCapture(r,r.interval,AudioDuration(10_000_000,DurationBasis.FIXTURE))
            val format=SourcePcmFormat(PcmFormat(rate,ch,PcmEncoding.PCM16),SourceFormatBasis.DECODER_OUTPUT)
            // Already codec-owned front trim: real PCM starts at zero; no second application trim.
            val bytes=ByteBuffer.allocate(rate*ch*2).order(ByteOrder.nativeOrder()).apply {repeat(rate*ch){putShort((it%100).toShort())};flip()}
            repeat(8) {c.accept(bytes.duplicate(),it*1_000_000L,format,AnalysisControl())}
            val w=c.finish(true,AnalysisControl())
            assertEquals(CoverageStatus.FULL,w.coverageStatus);assertEquals(176400,w.frameCount)
            assertNull(AdaptiveNativeRegions.supportedInset(w));assertEquals(0,w.preparation!!.gapCount)
        }
    }
    @Test fun shortCachedSourceCanHaveFullRegionalUnionWithoutRetainingPcm() {
        val catalog=NativeRegionCatalog()
        catalog.remember(NativePlanningFixtures.evidence(originUs=0,id="track",durationUs=40_000_000,lengthUs=32_000_000))
        val id=AnalysisSourceIdentity("track","generated-planning-v1")
        assertEquals(AudioIntervalUs(8_000_000,40_000_000),catalog.next(id,40_000_000))
        catalog.remember(NativePlanningFixtures.evidence(originUs=8_000_000,id="track",durationUs=40_000_000,lengthUs=32_000_000))
        assertEquals(40_000_000L,catalog.summary(id).verifiedUnionUs)
        assertNull(catalog.next(id,40_000_000))
    }
    @Test fun longTrackHasBoundedSelectiveCandidatesAndFiniteAttemptBudget() {
        val candidates=AdaptiveNativeRegions.candidates(1_800_000_000)
        assertTrue(candidates.size<=6);assertTrue(candidates.all {it.endUs-it.startUs==32_000_000L})
        val catalog=NativeRegionCatalog()
        repeat(3) {val r=catalog.next(identity,1_800_000_000)!!;catalog.started(identity,r)}
        assertNull(catalog.next(identity,1_800_000_000));assertTrue(candidates.sumOf {it.endUs-it.startUs}<1_800_000_000)
    }
    @Test fun conflictingAndOctaveBpmsRemainSeparateAndAreNeverAveraged() {
        val catalog=NativeRegionCatalog()
        for((time,bpm) in listOf(0L to 90.0,32_000_000L to 180.0,64_000_000L to 125.0))
            catalog.remember(NativePlanningFixtures.evidence(bpm,originUs=time,id="track",durationUs=180_000_000,lengthUs=32_000_000))
        val summary=catalog.summary(AnalysisSourceIdentity("track","generated-planning-v1"))
        assertEquals(3,summary.regions);assertEquals(NativeTempoAgreement.CONFLICT_OR_OCTAVE_UNVERIFIED,summary.tempo)
        assertEquals(96_000_000L,summary.verifiedUnionUs)
    }
    @Test fun differingIntervalsRenditionsDetailAndStrategiesHaveDistinctKeys() {
        val a=DecodeRequest(AudioIntervalUs(0,32_000_000),identity)
        val keys=listOf(AdaptiveNativeRegions.key(a,AnalysisDetail.DETAIL,true,true),
            AdaptiveNativeRegions.key(a,AnalysisDetail.DETAIL,true,false),
            AdaptiveNativeRegions.key(a.copy(identity=identity.copy(renditionKey="other")),AnalysisDetail.DETAIL,true,true),
            AdaptiveNativeRegions.key(a.copy(interval=AudioIntervalUs(1_000_000,32_000_000)),AnalysisDetail.DETAIL,true,true),
            AdaptiveNativeRegions.key(a,AnalysisDetail.SCAN,true,true))
        assertEquals(keys.size,keys.distinct().size)
    }
}
