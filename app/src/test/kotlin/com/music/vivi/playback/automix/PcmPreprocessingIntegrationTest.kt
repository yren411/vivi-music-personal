package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object PcmTestPreparation {
    fun prepare(samples:FloatArray,rate:Int,mode:AnalysisPreprocessing):DecodedWindow {
        val request=DecodeRequest(AudioIntervalUs(0,samples.size.toLong()*1_000_000/rate),AnalysisSourceIdentity("generated","rate-"+rate))
        val format=SourcePcmFormat(PcmFormat(rate,1,PcmEncoding.FLOAT32),SourceFormatBasis.DECODER_OUTPUT)
        val duration=AudioDuration(request.interval.endUs,DurationBasis.FIXTURE)
        return if(mode==AnalysisPreprocessing.LEGACY) {
            val capture=LegacyPcmWindowCapture(request,request.interval,duration)
            samples.forEachIndexed { i,value -> capture.accept(value,i*1_000_000L/rate,format) }
            capture.finish(true)
        } else {
            val capture=CorrectedPcmWindowCapture(request,request.interval,duration)
            val buffer=ByteBuffer.allocate(4096*4).order(ByteOrder.nativeOrder())
            var offset=0
            while(offset<samples.size){val frames=minOf(4096,samples.size-offset);buffer.clear()
                repeat(frames){buffer.putFloat(samples[offset+it])};buffer.flip()
                capture.accept(buffer,offset*1_000_000L/rate,format,AnalysisControl());offset+=frames}
            capture.finish(true,AnalysisControl())
        }
    }
}

class PcmPreprocessingIntegrationTest {
    @Test fun productionSelectionAndLegacyWriterRemainUnchanged() {
        assertEquals(AnalysisPreprocessing.LEGACY,AnalysisPreprocessing.PRODUCTION)
        assertEquals("nearest-time-mono-22050-v1",PcmPreprocessing.LEGACY)
        val input=floatArrayOf(0f,1f,2f,3f,4f,5f,6f,7f)
        val w=PcmTestPreparation.prepare(input,8000,AnalysisPreprocessing.LEGACY)
        assertEquals(PcmPreprocessing.LEGACY,w.preprocessingVersion)
        assertNull(w.preparation);assertTrue(w.repeatedOutputFrames!!>0)
        assertEquals(0f,w.samples[0],0f);assertEquals(1f,w.samples[1],0f)
    }
    @Test fun correctedPcmFlowsThroughUnchangedLegacyAnalyzerWithHonestProvenance() {
        val source=AnalysisPcmFixtures.groove(120.0,seconds=5,rate=48000)
        val w=PcmTestPreparation.prepare(source.samples,48000,AnalysisPreprocessing.BANDLIMITED)
        val pipeline=WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->DecodeResult.Decoded(w)},
            LegacyKotlinAnalysisEngine(),PcmPreprocessing.BANDLIMITED)
        val result=pipeline.analyze(Unit,DecodeRequest(w.requested,w.identity),AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Completed
        assertEquals(TrackFeaturesAnalyzer().analyze(w.samples,22050,0,5000,detail=AnalysisDetail.SCAN),LegacyPlanningFeatures.require(result))
        assertEquals(PcmPreprocessing.BANDLIMITED,result.metadata.preprocessingVersion)
        assertEquals(w.preparation,result.metadata.preparation);assertEquals(CoverageStatus.FULL,result.metadata.coverageStatus)
    }
    @Test fun partialAndUnknownCorrectedWindowsCannotMasqueradeAsCompletePlannerInputs() {
        val full=PcmTestPreparation.prepare(FloatArray(8000*5),8000,AnalysisPreprocessing.BANDLIMITED)
        for(coverage in listOf(AudioCoverage(null,40000),AudioCoverage(listOf(AudioIntervalUs(0,4000000)),32000))) {
            val partial=full.copy(coverage=coverage)
            val p=WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->DecodeResult.Decoded(partial)},LegacyKotlinAnalysisEngine(),PcmPreprocessing.BANDLIMITED)
            val result=p.analyze(Unit,DecodeRequest(full.requested,full.identity),AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Failed
            assertEquals("PARTIAL_SOURCE_COVERAGE",result.failure.code)
            assertEquals(AutoMixFailure.INSUFFICIENT_AUDIO_DATA,result.failure.category)
        }
    }
    @Test fun cacheAndInflightDedupCannotSubstitutePreprocessingVersions() {
        val identity=AnalysisSourceIdentity("recording","rendition")
        val old=AnalysisProfile(LegacyKotlinAnalysisEngine.BACKEND,PcmPreprocessing.LEGACY)
        val fresh=old.copy(preprocessingVersion=PcmPreprocessing.BANDLIMITED)
        val oldKey=old.cacheKey(identity,0,5000,AnalysisDetail.SCAN);val newKey=fresh.cacheKey(identity,0,5000,AnalysisDetail.SCAN)
        assertNotEquals(oldKey,newKey)
        val gate=CountDownLatch(1);val entered=CountDownLatch(1)
        AnalysisWorkers<String>(AutoMixPerformance.BALANCED).use { workers ->
            val oldJob=workers.submit(oldKey){entered.countDown();gate.await();"legacy"}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val newJob=workers.submit(newKey){"corrected"}
            assertNotSame(oldJob,newJob);assertEquals("corrected",newJob.get(5,TimeUnit.SECONDS))
            oldJob.cancel(true);gate.countDown()
            assertEquals("corrected",workers.submit(newKey){error("cache miss")}.get(5,TimeUnit.SECONDS))
            assertFalse(workers.hasCached(oldKey))
            assertEquals("legacy",workers.submit(oldKey){"legacy"}.get(5,TimeUnit.SECONDS))
        }
    }
    @Test fun advertisedVersionCannotRelabelLegacyPcmAsCorrected() {
        val old=PcmTestPreparation.prepare(FloatArray(8000*4),8000,AnalysisPreprocessing.LEGACY)
        val p=WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,_->DecodeResult.Decoded(old)},LegacyKotlinAnalysisEngine(),PcmPreprocessing.BANDLIMITED)
        val result=p.analyze(Unit,DecodeRequest(old.requested,old.identity),AnalysisDetail.SCAN,AnalysisControl()) as AnalysisResult.Failed
        assertEquals("WINDOW_IDENTITY_MISMATCH",result.failure.code)
    }
    @Test fun diagnosticExportIsVersionedBoundedAndContainsOnlyMeasuredTypedFields() {
        val recorder=AutoMixRecorder(maxAttempts=2,maxEvents=3)
        val attempt=recorder.begin("https://private.example?token=SECRET","cookie=SECRET")
        val diagnostic=DecoderDiagnostic(AnalysisPreprocessing.BANDLIMITED,DecoderStage.PCM_CONVERSION,
            DecoderFailureSubtype.SOURCE_GAP,AutoMixFailure.INSUFFICIENT_AUDIO_DATA,AudioIntervalUs(0,5000000),
            coverage=AudioCoverage(listOf(AudioIntervalUs(0,1000000)),48000),sourceBytesRead=65536,
            preparation=PcmPreparationMetadata(48000,0,0,0,1,0))
        repeat(10){recorder.record(attempt,MixEvent.DECODER,role=MixRole.OUTGOING,decoder=diagnostic)}
        assertEquals(3,recorder.snapshot().attempts.single().records.size)
        val report=AutoMixRecorder.render(recorder.snapshot(),MixReportHeader("test",77,"17","fixture",true,AutoMixPerformance.PERFORMANCE))
        assertTrue(report.contains("schema 2"));assertTrue(report.contains(PcmPreprocessing.BANDLIMITED))
        assertTrue(report.contains("subtype=SOURCE_GAP"));assertTrue(report.contains("acceptedFrames=48000"))
        assertTrue(report.contains("codecEos=null"));assertTrue(report.contains("synthesizedTimelineFrames=0"))
        for(secret in listOf("https://","private.example","SECRET","cookie="))assertFalse(report.contains(secret))
        assertTrue(report.length<10000)
    }
    @Test fun diagnosticObserverIsPerJobAndMetadataCannotBeReplacedAfterAnalysis() {
        val w=PcmTestPreparation.prepare(FloatArray(8000*4),8000,AnalysisPreprocessing.BANDLIMITED)
        val events=mutableListOf<DecoderDiagnostic>()
        val diag=DecoderDiagnostic(AnalysisPreprocessing.BANDLIMITED,DecoderStage.COMPLETE,DecoderFailureSubtype.NONE,null,w.requested)
        val p=WindowAnalysisPipeline(AnalysisWindowDecoder<Unit>{_,_,control->control.decoderDiagnostics.record(diag);DecodeResult.Decoded(w.copy(decoderDiagnostic=diag))},LegacyKotlinAnalysisEngine(),PcmPreprocessing.BANDLIMITED)
        val result=p.analyze(Unit,DecodeRequest(w.requested,w.identity),AnalysisDetail.SCAN,AnalysisControl(decoderDiagnostics=DecoderDiagnosticSink{events+=it})) as AnalysisResult.Completed
        assertEquals(listOf(diag),events);assertEquals(diag,result.metadata.decoderDiagnostic)
        val modified=result.copy(metadata=result.metadata.copy(decoderDiagnostic=null))
        assertTrue(AnalysisResultValidation.validate(modified,w.copy(decoderDiagnostic=diag),result.backend,AnalysisDetail.SCAN) is AnalysisResult.Failed)
    }
    @Test fun bothPreprocessorsRunSharedMusicalBenchmarkWithoutClaimingRhythmRepair() {
        for(bpm in listOf(90.0,107.0,120.0,128.0,140.0,150.0,174.0)) {
            val source=AnalysisPcmFixtures.groove(bpm,seconds=30,rate=48000)
            for(mode in AnalysisPreprocessing.entries) {
                val w=PcmTestPreparation.prepare(source.samples,source.rate,mode)
                val features=LegacyPlanningFeatures.require(LegacyKotlinAnalysisEngine().analyze(w,AnalysisDetail.SCAN,AnalysisControl()))
                val metrics=BeatBenchmark.evaluate(source,features)
                println("PCM_CORPUS mode="+mode+" groundTruth="+bpm+" bpm="+features.bpm+" grid="+features.beatConfidence+
                    " phase="+features.phaseConfidence+" rms="+features.rms+" peak="+features.peak+" metrics="+metrics+
                    " unmetTargets="+FutureAnalysisAccuracyTargets.failures(metrics))
                assertTrue(features.rms.isFinite());assertEquals(mode.version,w.preprocessingVersion)
                if(bpm==128.0)assertTrue("integer-lag rhythm defect remains",FutureAnalysisAccuracyTargets.failures(metrics).isNotEmpty())
            }
        }
    }
}
